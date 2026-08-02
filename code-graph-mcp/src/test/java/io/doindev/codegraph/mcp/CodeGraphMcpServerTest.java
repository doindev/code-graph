package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.tools.GraphTool;
import io.doindev.codegraph.tools.ToolResponse;
import io.doindev.codegraph.tools.ToolSpec;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the SDK adapter mapping directly (no transport), mirroring SomaMcpServerTest. */
class CodeGraphMcpServerTest {

    private static final GraphTool ECHO = new GraphTool() {
        @Override
        public ToolSpec spec() {
            return new ToolSpec("echo", "echoes the input",
                    "{ \"type\": \"object\", \"properties\": { \"v\": { \"type\": \"string\" } } }");
        }

        @Override
        public ToolResponse call(JsonNode args) {
            JsonNode v = args.get("v");
            if (v == null) {
                return ToolResponse.fail("v is required");
            }
            return ToolResponse.ok("{\"echoed\":\"" + v.asText() + "\"}");
        }
    };

    @Test
    void toolSpecificationCarriesNameDescriptionAndSchema() {
        McpServerFeatures.SyncToolSpecification spec = CodeGraphMcpServer.toSpecification(ECHO);
        assertEquals("echo", spec.tool().name());
        assertEquals("echoes the input", spec.tool().description());
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) spec.tool().inputSchema().get("properties");
        assertTrue(properties.containsKey("v"));
    }

    @Test
    void handlerExecutesToolAndMapsResult() {
        McpSchema.CallToolResult result = CodeGraphMcpServer.toSpecification(ECHO).callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of("v", "hi")));
        assertFalse(result.isError());
        assertEquals("{\"echoed\":\"hi\"}", ((McpSchema.TextContent) result.content().get(0)).text());
    }

    @Test
    void toolErrorBecomesErrorResultNotCrash() {
        McpSchema.CallToolResult result = CodeGraphMcpServer.toSpecification(ECHO).callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of()));
        assertTrue(result.isError());
    }
}
