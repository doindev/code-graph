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

    @Test void workspaceContextPagesProjectSummariesWithoutDatabaseData()throws Exception{
        var args=JSON.createObjectNode().put("limit",1);
        var first=JSON.readTree(tool("get_workspace_context").call(args).json());
        assertFalse(first.path("dbaEnabled").asBoolean());assertEquals(1,first.path("entries").size());
        args.put("cursor",first.path("nextCursor").asText());
        var second=JSON.readTree(tool("get_workspace_context").call(args).json());
        assertEquals("beta",second.path("entries").get(0).path("name").asText());
        assertFalse(second.path("truncated").asBoolean());
    }

    @Test
    void mcpActivityRenewsOnlyResolvedProjectAndListsDoNot() throws Exception {
        var time = new java.util.concurrent.atomic.AtomicLong();
        var removed = new java.util.ArrayList<String>();
        try (WorkspaceTools registry = new WorkspaceTools(List.of(
                new CodeGraphTools.ProjectTools("alpha", graphWith("A.java", "A.x"), CodeGraphConfig.defaults(), null, s -> {}),
                new CodeGraphTools.ProjectTools("beta", graphWith("B.java", "B.x"), CodeGraphConfig.defaults(), null, s -> {})),
                removed::add, time::get, () -> java.time.Instant.EPOCH.plusNanos(time.get()))) {
            tools = registry.tools();
            time.set(java.time.Duration.ofMinutes(30).toNanos());
            tool("index_status").call(JSON.createObjectNode()); // implicit alpha
            tool("search_symbols").call(JSON.createObjectNode().put("project", "missing").put("query", "x"));
            time.set(java.time.Duration.ofHours(1).toNanos());
            JsonNode roster = JSON.readTree(tool("list_projects").call(JSON.createObjectNode()).json());
            assertEquals(0, roster.get("projects").get(1).get("remainingSeconds").asInt());
            assertEquals(1, registry.lifecycle().expireIdle());
            assertEquals(List.of("beta"), removed);
            assertEquals(List.of("alpha"), registry.projectNames());
            time.set(java.time.Duration.ofMinutes(90).toNanos());
            assertEquals(1, registry.lifecycle().expireIdle());
            assertTrue(registry.projectNames().isEmpty());
            assertTrue(tool("index_status").call(JSON.createObjectNode()).error());
        }
    }

    @Test
    void schemasAdvertiseTheProjectParameter() throws Exception {
        JsonNode schema = JSON.readTree(tool("search_symbols").spec().inputSchemaJson());
        assertTrue(schema.get("properties").has("project"));
        assertTrue(schema.get("properties").get("project").get("description").asText()
                .contains("list_projects"));
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
    void lastProjectCanBeRemovedAndLeavesActionableEmptyRoutes() throws Exception {
        WorkspaceTools registry = CodeGraphTools.workspace(java.util.List.of(
                new CodeGraphTools.ProjectTools("solo", graphWith("src/A.java", "A.x"),
                        CodeGraphConfig.defaults(), null, s -> { })), r -> { });
        GraphTool remove = registry.tools().stream()
                .filter(t -> t.spec().name().equals("remove_project")).findFirst().orElseThrow();
        ToolResponse response = remove.call(JSON.createObjectNode().put("project", "solo"));
        assertFalse(response.error());
        assertEquals(0, JSON.readTree(response.json()).get("remaining").size());

        GraphTool search = registry.tools().stream()
                .filter(t -> t.spec().name().equals("search_symbols")).findFirst().orElseThrow();
        ToolResponse empty = search.call(JSON.createObjectNode().put("query", "x"));
        assertTrue(empty.error());
        assertTrue(empty.json().contains("no projects onboarded"));
    }

    @Test
    void emptyWorkspaceKeepsStableToolsAndFirstAdditionBecomesDefault() throws Exception {
        WorkspaceTools registry = CodeGraphTools.workspace(List.of(), r -> { });
        assertTrue(registry.tools().stream().anyMatch(t -> t.spec().name().equals("search_symbols")));
        GraphTool list = registry.tools().stream()
                .filter(t -> t.spec().name().equals("list_projects")).findFirst().orElseThrow();
        assertEquals(0, JSON.readTree(list.call(JSON.createObjectNode()).json()).get("projects").size());

        registry.addProject(new CodeGraphTools.ProjectTools("first",
                graphWith("src/F.java", "F.first"), CodeGraphConfig.defaults(), null, s -> { }));
        GraphTool search = registry.tools().stream()
                .filter(t -> t.spec().name().equals("search_symbols")).findFirst().orElseThrow();
        JsonNode out = JSON.readTree(search.call(
                JSON.createObjectNode().put("query", "first")).json());
        assertTrue(out.get("symbols").get(0).get("id").asText().contains("F.java"));
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
