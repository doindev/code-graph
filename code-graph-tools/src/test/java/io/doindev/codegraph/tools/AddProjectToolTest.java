package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AddProjectToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void appearsInEmptyCatalogAndReturnsTheAssignedName() throws Exception {
        WorkspaceTools registry = CodeGraphTools.workspace(List.of(), p -> { });
        AtomicReference<String> requested = new AtomicReference<>();
        GraphTool add = registry.tools(path -> {
            requested.set(path);
            return "project-2";
        }).stream().filter(t -> t.spec().name().equals("add_project")).findFirst().orElseThrow();
        assertTrue(JSON.readTree(add.spec().inputSchemaJson()).get("properties").has("path"));
        ToolResponse result = add.call(JSON.createObjectNode().put("path", "C:/repos/my project"));
        assertFalse(result.error());
        assertEquals("C:/repos/my project", requested.get());
        assertEquals("project-2", JSON.readTree(result.json()).get("project").asText());
        assertEquals("ready", JSON.readTree(result.json()).get("state").asText());
    }

    @Test
    void missingBlankAndNonStringPathsAreRejectedBeforeOnboarding() throws Exception {
        AddProjectTool tool = new AddProjectTool(path -> {
            fail("invalid arguments must not invoke onboarding");
            return null;
        });
        assertTrue(tool.call(null).error());
        for (String json : List.of("{}", "null", "{\"path\":null}", "{\"path\":\"\"}",
                "{\"path\":\"  \"}", "{\"path\":123}", "{\"path\":true}", "{\"path\":[]}")) {
            assertTrue(tool.call(JSON.readTree(json)).error(), json);
        }
    }

    @Test
    void onboardingFailuresBecomeMcpToolErrors() throws Exception {
        AddProjectTool tool = new AddProjectTool(path -> {
            throw new IllegalArgumentException("child path of project 'parent'");
        });
        ToolResponse result = tool.call(JSON.createObjectNode().put("path", "repo/child"));
        assertTrue(result.error());
        assertTrue(JSON.readTree(result.json()).get("error").asText().contains("child path"));
    }
}
