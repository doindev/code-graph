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

    private CodeGraphMcpServer(McpSyncServer server) {
        this.server = server;
    }

    /** Serve over stdio — the transport local agent hosts (Claude Code, IDEs) use. */
    public static CodeGraphMcpServer serveStdio(String serverName, String version, List<GraphTool> tools) {
        return serve(serverName, version, tools, new StdioServerTransportProvider(JSON));
    }

    /** Serve over any single-session SDK transport provider. */
    public static CodeGraphMcpServer serve(String serverName, String version, List<GraphTool> tools,
                                           McpServerTransportProvider transportProvider) {
        var builder = McpServer.sync(transportProvider)
                .serverInfo(serverName, version)
                .jsonMapper(JSON)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
        for (GraphTool tool : tools) {
            builder.tools(toSpecification(tool));
        }
        return new CodeGraphMcpServer(builder.build());
    }

    /** Serve over a streamable HTTP transport provider (used by code-graph-mcp-http). */
    public static CodeGraphMcpServer serve(String serverName, String version, List<GraphTool> tools,
                                           McpStreamableServerTransportProvider transportProvider) {
        var builder = McpServer.sync(transportProvider)
                .serverInfo(serverName, version)
                .jsonMapper(JSON)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());
        for (GraphTool tool : tools) {
            builder.tools(toSpecification(tool));
        }
        return new CodeGraphMcpServer(builder.build());
    }

    static McpServerFeatures.SyncToolSpecification toSpecification(GraphTool tool) {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder()
                .name(tool.spec().name())
                .description(tool.spec().description())
                .inputSchema(JSON, tool.spec().inputSchemaJson())
                .build();
        return new McpServerFeatures.SyncToolSpecification(mcpTool, (exchange, request) -> {
            ToolResponse response = tool instanceof DbaMcpTools.AgentTool agent
                    ? agent.execute((String)exchange.transportContext().get(DbaMcpTools.PRINCIPAL), MAPPER.valueToTree(request.arguments()))
                    : execute(tool, request.arguments());
            return new McpSchema.CallToolResult(
                    List.of((McpSchema.Content) new McpSchema.TextContent(response.json())),
                    response.error(), null, null);
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
        server.closeGracefully();
    }
}
