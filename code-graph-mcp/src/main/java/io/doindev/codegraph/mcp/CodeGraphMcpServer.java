package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.tools.GraphTool;
import io.doindev.codegraph.tools.ToolResponse;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

import java.util.List;

/**
 * MCP adapter — the only class that touches the MCP SDK (2.0.0). Tools stay SDK-free
 * {@link GraphTool}s; schemas pass through verbatim; a throwing tool becomes an MCP error
 * result, never a crashed server. Modeled on soma-graphs' SomaMcpServer idiom.
 */
public final class CodeGraphMcpServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JacksonMcpJsonMapper JSON = new JacksonMcpJsonMapper(MAPPER);

    private final McpSyncServer server;
    private final java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.node.ObjectNode> identity;
    private List<GraphTool> ownedTools=List.of();
    private java.util.concurrent.CountDownLatch stdioEnded;

    private CodeGraphMcpServer(McpSyncServer server, java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.node.ObjectNode> identity) {
        this.server = server;
        this.identity = identity;
    }

    /** Serve over stdio — the transport local agent hosts (Claude Code, IDEs) use. */
    public static CodeGraphMcpServer serveStdio(String serverName, String version, List<GraphTool> tools) {
        var ended=new java.util.concurrent.CountDownLatch(1);
        java.io.InputStream input=new java.io.FilterInputStream(System.in){
            private void finish(){endSessions(tools);ended.countDown();}
            @Override public int read()throws java.io.IOException{try{int n=super.read();if(n<0)finish();return n;}catch(java.io.IOException e){finish();throw e;}}
            @Override public int read(byte[] b,int off,int len)throws java.io.IOException{try{int n=in.read(b,off,len);if(n<0)finish();return n;}catch(java.io.IOException e){finish();throw e;}}
            @Override public void close()throws java.io.IOException{try{super.close();}finally{finish();}}
        };
        CodeGraphMcpServer result=serve(serverName, version, tools, new StdioServerTransportProvider(JSON,input,System.out));
        result.ownedTools=tools;result.stdioEnded=ended;return result;
    }
    private static void endSessions(List<GraphTool> tools){for(GraphTool tool:tools)if(tool instanceof DbaMcpTools.AgentTool agent)agent.endSession();}
    public void awaitStdioTermination()throws InterruptedException{if(stdioEnded==null)throw new IllegalStateException("Not a stdio server");stdioEnded.await();}

    /** Serve over any single-session SDK transport provider. */
    public static CodeGraphMcpServer serve(String serverName, String version, List<GraphTool> tools,
                                           McpServerTransportProvider transportProvider) {
        var identity = initialIdentity(tools);
        var builder = McpServer.sync(transportProvider)
                .serverInfo(serverName, version)
                .jsonMapper(JSON)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
        for (GraphTool tool : tools) {
            builder.tools(toSpecification(tool, identity::get));
        }
        return new CodeGraphMcpServer(builder.build(), identity);
    }

    /** Serve over a streamable HTTP transport provider (used by code-graph-mcp-http). */
    public static CodeGraphMcpServer serve(String serverName, String version, List<GraphTool> tools,
                                           McpStreamableServerTransportProvider transportProvider) {
        var identity = initialIdentity(tools);
        var builder = McpServer.sync(transportProvider)
                .serverInfo(serverName, version)
                .jsonMapper(JSON)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
        for (GraphTool tool : tools) {
            builder.tools(toSpecification(tool, identity::get));
        }
        return new CodeGraphMcpServer(builder.build(), identity);
    }

    private static java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.node.ObjectNode> initialIdentity(List<GraphTool> tools) {
        var specs = tools.stream().map(CodeGraphMcpServer::toSpecification).map(McpServerFeatures.SyncToolSpecification::tool).toList();
        if (specs.stream().map(McpSchema.Tool::name).distinct().count() != specs.size()) throw new IllegalArgumentException("Duplicate tool names");
        return new java.util.concurrent.atomic.AtomicReference<>(ToolCatalogIdentity.describe(specs));
    }

    /** Owner-only catalog updates. SDK mutations notify clients; indexing never calls this. */
    public synchronized boolean replaceTools(List<GraphTool> tools) {
        var desired = initialIdentity(tools).get();
        if (desired.path("toolCatalogFingerprint").equals(identity.get().path("toolCatalogFingerprint"))) return false;
        var old = new java.util.HashMap<String, McpSchema.Tool>();
        server.listTools().forEach(tool -> old.put(tool.name(), tool));
        try {
            for (GraphTool tool : tools) {
                var specification = toSpecification(tool, identity::get);
                var previous = old.remove(tool.spec().name());
                if (!specification.tool().equals(previous)) server.addTool(specification);
            }
            for (String removed : old.keySet()) server.removeTool(removed);
        } finally {
            identity.set(ToolCatalogIdentity.describe(server.listTools()));
        }
        return true;
    }

    static McpServerFeatures.SyncToolSpecification toSpecification(GraphTool tool) {
        return toSpecification(tool, () -> null);
    }
    private static McpServerFeatures.SyncToolSpecification toSpecification(GraphTool tool,
            java.util.function.Supplier<com.fasterxml.jackson.databind.node.ObjectNode> identity) {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                .name(tool.spec().name())
                .description(tool.spec().description())
                .inputSchema(JSON, tool.spec().inputSchemaJson())
                .outputSchema(JSON, McpToolContracts.OUTPUT_SCHEMA)
                .annotations(McpToolContracts.annotations(tool.spec().name()))
                .build();
        return new McpServerFeatures.SyncToolSpecification(mcpTool, (exchange, request) -> {
            ToolResponse response = tool instanceof DbaMcpTools.AgentTool agent
                    ? agent.execute((String)exchange.transportContext().get(DbaMcpTools.PRINCIPAL), (String)exchange.transportContext().get(DbaMcpTools.SESSION), MAPPER.valueToTree(request.arguments()))
                    : execute(tool, request.arguments());
            if (!response.error() && java.util.Set.of("index_status", "get_workspace_context").contains(tool.spec().name()) && identity.get() != null) {
                try {
                    JsonNode data = MAPPER.readTree(response.json());
                    if (data instanceof com.fasterxml.jackson.databind.node.ObjectNode object) {
                        object.set("server", identity.get());
                        response = ToolResponse.ok(object.toString());
                    }
                } catch (java.io.IOException ignored) { /* Existing structured error handling below. */ }
            }
            java.util.Map<String, Object> structured;
            try { structured = McpToolContracts.structured(response, tool.spec().name()); }
            catch (IllegalStateException invalidPayload) {
                response = ToolResponse.fail("Tool returned an invalid JSON payload");
                structured = McpToolContracts.structured(response, tool.spec().name());
            }
            return new McpSchema.CallToolResult(
                    List.of((McpSchema.Content) new McpSchema.TextContent(response.json())),
                    response.error(), structured, null);
        });
    }

    static ToolResponse execute(GraphTool tool, Object arguments) {
        try {
            JsonNode args = MAPPER.valueToTree(arguments);
            return tool.call(args);
        } catch (RuntimeException e) {
            return ToolResponse.fail(tool.spec().name() + " failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        endSessions(ownedTools);if(stdioEnded!=null)stdioEnded.countDown();
        server.closeGracefully();
    }
}
