package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private List<GraphTool> tools;

    private static InMemoryCodeGraph graphWith(String path, String qname) {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        SymbolId id = new SymbolId("java", path, qname, 0);
        graph.apply(new GraphDelta(1, List.of(),
                List.of(new Node(id, NodeKind.FUNCTION, qname.substring(qname.lastIndexOf('.') + 1),
                        qname + "()", new SourceSpan(path, 1, 1, 3, 1), Metrics.NONE, Map.of())),
                List.of(), List.of()));
        return graph;
    }

    @BeforeEach
    void setUp() {
        CodeGraphConfig config = CodeGraphConfig.defaults();
        tools = CodeGraphTools.workspace(List.of(
                new CodeGraphTools.ProjectTools("alpha", graphWith("src/A.java", "A.fromAlpha"),
                        config, null, s -> { }),
                new CodeGraphTools.ProjectTools("beta", graphWith("src/B.java", "B.fromBeta"),
                        config, null, s -> { })));
    }

    private GraphTool tool(String name) {
        return tools.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void schemasAdvertiseTheProjectParameter() throws Exception {
        JsonNode schema = JSON.readTree(tool("search_symbols").spec().inputSchemaJson());
        assertTrue(schema.get("properties").has("project"));
        assertTrue(schema.get("properties").get("project").get("description").asText().contains("alpha"));
    }

    @Test
    void routingDefaultsToFirstProjectAndHonorsExplicitChoice() throws Exception {
        JsonNode defaulted = JSON.readTree(tool("search_symbols")
                .call(JSON.createObjectNode().put("query", "from")).json());
        assertEquals(1, defaulted.get("total").asInt());
        assertTrue(defaulted.get("symbols").get(0).get("id").asText().contains("A.java"));

        JsonNode routed = JSON.readTree(tool("search_symbols")
                .call(JSON.createObjectNode().put("query", "from").put("project", "beta")).json());
        assertTrue(routed.get("symbols").get(0).get("id").asText().contains("B.java"));
    }

    @Test
    void unknownProjectIsAnActionableError() {
        ToolResponse response = tool("search_symbols")
                .call(JSON.createObjectNode().put("query", "x").put("project", "gamma"));
        assertTrue(response.error());
        assertTrue(response.json().contains("alpha"));
    }

    @Test
    void listProjectsShowsRosterWithDefaultFlag() throws Exception {
        JsonNode out = JSON.readTree(tool("list_projects").call(JSON.createObjectNode()).json());
        assertEquals(2, out.get("projects").size());
        assertTrue(out.get("projects").get(0).get("default").asBoolean());
        assertFalse(out.get("projects").get(1).get("default").asBoolean());
        assertEquals("beta", out.get("projects").get(1).get("name").asText());
    }

    @Test
    void removeProjectStopsRoutingAndRepointsDefault() throws Exception {
        java.util.List<String> removed = new java.util.ArrayList<>();
        CodeGraphConfig config = CodeGraphConfig.defaults();
        WorkspaceTools registry = CodeGraphTools.workspace(java.util.List.of(
                new CodeGraphTools.ProjectTools("alpha", graphWith("src/A.java", "A.fromAlpha"),
                        config, null, s -> { }),
                new CodeGraphTools.ProjectTools("beta", graphWith("src/B.java", "B.fromBeta"),
                        config, null, s -> { })), removed::add);
        GraphTool search = registry.tools().stream()
                .filter(t -> t.spec().name().equals("search_symbols")).findFirst().orElseThrow();
        GraphTool remove = registry.tools().stream()
                .filter(t -> t.spec().name().equals("remove_project")).findFirst().orElseThrow();

        // remove the default (alpha); beta becomes default
        JsonNode removeOut = JSON.readTree(
                remove.call(JSON.createObjectNode().put("project", "alpha")).json());
        assertEquals("alpha", removeOut.get("removed").asText());
        assertEquals(java.util.List.of("alpha"), removed);

        assertTrue(search.call(JSON.createObjectNode().put("query", "from").put("project", "alpha")).error());
        JsonNode defaulted = JSON.readTree(
                search.call(JSON.createObjectNode().put("query", "from")).json());
        assertTrue(defaulted.get("symbols").get(0).get("id").asText().contains("B.java"));
    }

    @Test
    void lastProjectCannotBeRemoved() {
        WorkspaceTools registry = CodeGraphTools.workspace(java.util.List.of(
                new CodeGraphTools.ProjectTools("solo", graphWith("src/A.java", "A.x"),
                        CodeGraphConfig.defaults(), null, s -> { })), r -> { });
        GraphTool remove = registry.tools().stream()
                .filter(t -> t.spec().name().equals("remove_project")).findFirst().orElseThrow();
        ToolResponse response = remove.call(JSON.createObjectNode().put("project", "solo"));
        assertTrue(response.error());
        assertTrue(response.json().contains("last project"));
    }

    @Test
    void addedProjectBecomesRoutable() throws Exception {
        WorkspaceTools registry = CodeGraphTools.workspace(java.util.List.of(
                new CodeGraphTools.ProjectTools("alpha", graphWith("src/A.java", "A.fromAlpha"),
                        CodeGraphConfig.defaults(), null, s -> { })), r -> { });
        registry.addProject(new CodeGraphTools.ProjectTools("gamma",
                graphWith("src/G.java", "G.fromGamma"), CodeGraphConfig.defaults(), null, s -> { }));
        GraphTool search = registry.tools().stream()
                .filter(t -> t.spec().name().equals("search_symbols")).findFirst().orElseThrow();
        JsonNode out = JSON.readTree(
                search.call(JSON.createObjectNode().put("query", "from").put("project", "gamma")).json());
        assertTrue(out.get("symbols").get(0).get("id").asText().contains("G.java"));
    }
}
