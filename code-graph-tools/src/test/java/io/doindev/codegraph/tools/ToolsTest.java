package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private InMemoryCodeGraph graph;
    private List<GraphTool> tools;
    private final AtomicReference<String> reindexed = new AtomicReference<>();

    private static SymbolId sym(String qname) {
        return new SymbolId("java", "src/Acme.java", qname, 1);
    }

    private static Node fn(SymbolId id) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, name + "(String)",
                new SourceSpan(id.relPath(), 10, 1, 20, 1), Metrics.NONE, Map.of());
    }

    @BeforeEach
    void setUp() {
        graph = new InMemoryCodeGraph();
        // chain: c -> b -> a, all in one file
        List<Node> nodes = new ArrayList<>(List.of(fn(sym("Acme.a")), fn(sym("Acme.b")), fn(sym("Acme.c"))));
        List<Edge> edges = List.of(
                new Edge(sym("Acme.b"), sym("Acme.a"), EdgeKind.CALLS),
                new Edge(sym("Acme.c"), sym("Acme.b"), EdgeKind.CALLS),
                new Edge(new FileId("src/Acme.java"), sym("Acme.a"), EdgeKind.CONTAINS));
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
        tools = CodeGraphTools.standard(graph, CodeGraphConfig.defaults(), reindexed::set);
    }

    private GraphTool tool(String name) {
        return tools.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    private JsonNode call(String name, String argsJson) throws Exception {
        ToolResponse response = tool(name).call(JSON.readTree(argsJson));
        assertFalse(response.error(), name + " errored: " + response.json());
        return JSON.readTree(response.json());
    }

    @Test
    void searchSymbolsReturnsCompactRows() throws Exception {
        JsonNode out = call("search_symbols", "{\"query\":\"acme.b\"}");
        assertEquals(1, out.get("total").asInt());
        assertEquals("java:src/Acme.java#Acme.b/1", out.get("symbols").get(0).get("id").asText());
        assertEquals("function", out.get("symbols").get(0).get("kind").asText());
        assertFalse(out.get("truncated").asBoolean());
    }

    @Test
    void searchSymbolsMarksTruncation() throws Exception {
        JsonNode out = call("search_symbols", "{\"query\":\"acme\",\"limit\":1}");
        assertEquals(3, out.get("total").asInt());
        assertTrue(out.get("truncated").asBoolean());
        assertEquals(2, out.get("omitted").asInt());
    }

    @Test
    void getSymbolReportsCounts() throws Exception {
        JsonNode out = call("get_symbol", "{\"symbol_id\":\"java:src/Acme.java#Acme.a/1\"}");
        assertEquals(1, out.get("counts").get("callers").asInt());
        assertEquals(2, out.get("counts").get("transitiveDependents").asInt());
        assertEquals("src/Acme.java", out.get("file").asText());
    }

    @Test
    void getSymbolUnknownIdIsAnActionableError() {
        ToolResponse response = tool("get_symbol").call(JSON.createObjectNode()
                .put("symbol_id", "java:src/Acme.java#Nope.nope/9"));
        assertTrue(response.error());
        assertTrue(response.json().contains("search_symbols"));
    }

    @Test
    void impactRadiusCompressesTransitiveClosure() throws Exception {
        JsonNode out = call("get_impact_radius", "{\"target\":\"java:src/Acme.java#Acme.a/1\"}");
        JsonNode dependents = out.get("dependents");
        assertEquals(2, dependents.get("total").asInt());
        assertEquals(1, dependents.get("byDepth").get(0).asInt());
        assertEquals(1, dependents.get("byDepth").get(1).asInt());
        assertEquals("java:src/Acme.java#Acme.b/1", dependents.get("direct").get(0).asText());
        assertEquals("src", dependents.get("modules").get(0).asText());
    }

    @Test
    void callGraphUsesIndexPairAdjacency() throws Exception {
        JsonNode out = call("get_call_graph",
                "{\"function\":\"java:src/Acme.java#Acme.b/1\",\"direction\":\"both\",\"depth\":1}");
        assertEquals("java:src/Acme.java#Acme.b/1", out.get("nodes").get(0).asText());
        assertEquals(3, out.get("nodes").size());
        assertEquals(1, out.get("up").size());   // c -> b
        assertEquals(1, out.get("down").size()); // b -> a
        // pairs are [caller, callee]
        assertEquals(0, out.get("down").get(0).get(0).asInt());
    }

    @Test
    void indexStatusAndReindexRoundTrip() throws Exception {
        JsonNode status = call("index_status", "{}");
        assertEquals("ready", status.get("state").asText());
        assertEquals(3, status.get("symbols").asInt());

        JsonNode reindex = call("reindex", "{\"path\":\"src\"}");
        assertTrue(reindex.get("accepted").asBoolean());
        assertEquals("src", reindexed.get());
    }

    @Test
    void oversizedResponsesAreRejectedNotEmitted() {
        // tiny budget forces the cap error path
        CodeGraphConfig tiny = new CodeGraphConfig(null, null,
                new CodeGraphConfig.Limits(50, 1024), null, null, null, Map.of(), null).withDefaults();
        // hydrate a graph large enough that search output exceeds 1KB
        List<Node> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(fn(new SymbolId("java", "src/Acme.java", "Acme.pad" + i, 1)));
        }
        graph.apply(new GraphDelta(2, List.of(), many, List.of(), List.of()));
        GraphTool search = CodeGraphTools.standard(graph, tiny, s -> { }).get(0);
        ToolResponse response = search.call(JSON.createObjectNode().put("query", "pad").put("limit", 50));
        assertTrue(response.error());
        assertTrue(response.json().contains("narrow the query"));
    }
}
