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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("object", spec.tool().outputSchema().get("type"));
        assertFalse(spec.tool().annotations().readOnlyHint(), "Unknown tools must not be declared safe");
        assertTrue(spec.tool().annotations().destructiveHint());
    }

    @Test
    void handlerExecutesToolAndMapsResult() {
        McpSchema.CallToolResult result = CodeGraphMcpServer.toSpecification(ECHO).callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of("v", "hi")));
        assertFalse(result.isError());
        assertEquals("{\"echoed\":\"hi\"}", ((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals(Map.of("echoed", "hi"), ((Map<?,?>) result.structuredContent()).get("data"));
    }

    @Test
    void toolErrorBecomesErrorResultNotCrash() {
        McpSchema.CallToolResult result = CodeGraphMcpServer.toSpecification(ECHO).callHandler()
                .apply(null, new McpSchema.CallToolRequest("echo", Map.of()));
        assertTrue(result.isError());
        assertEquals("error", ((Map<?,?>)((Map<?,?>)result.structuredContent()).get("meta")).get("invocationState"));
    }

    @Test void annotationsAreConservativeForLiveJobsAndMutations() {
        assertTrue(McpToolContracts.annotations("search_symbols").readOnlyHint());
        assertTrue(McpToolContracts.annotations("search_symbols").idempotentHint());
        assertFalse(McpToolContracts.annotations("dba_execute_read_query").idempotentHint());
        assertFalse(McpToolContracts.annotations("dba_request_live_sql").readOnlyHint());
        assertTrue(McpToolContracts.annotations("dba_request_live_sql").destructiveHint());
        assertTrue(McpToolContracts.annotations("remove_project").destructiveHint());
    }

    @Test void legacyArrayResultsHaveAnObjectStructuredEnvelope() {
        assertEquals(java.util.List.of(1,2), McpToolContracts.structured(ToolResponse.ok("[1,2]"), "test").get("data"));
        assertThrows(IllegalStateException.class, () -> McpToolContracts.structured(ToolResponse.ok("not json"), "test"));
    }
}
