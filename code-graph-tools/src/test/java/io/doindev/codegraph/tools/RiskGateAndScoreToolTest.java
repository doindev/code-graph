package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskGateAndScoreToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private List<GraphTool> tools;
    private SymbolId hot;
    private SymbolId cold;

    private static Node fn(SymbolId id) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, name + "()",
                new SourceSpan(id.relPath(), 1, 1, 5, 1), Metrics.NONE, Map.of());
    }

    @BeforeEach
    void setUp() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        hot = new SymbolId("java", "core/src/Hot.java", "Hot.method", 0);
        cold = new SymbolId("java", "core/src/Cold.java", "Cold.method", 0);
        List<Node> nodes = new ArrayList<>(List.of(fn(hot), fn(cold)));
        List<Edge> edges = new ArrayList<>();
        // hot: 40 direct callers spread across 6 modules — untested
        for (int i = 0; i < 40; i++) {
            SymbolId caller = new SymbolId("java", "mod" + (i % 6) + "/src/C" + i + ".java", "C" + i + ".call", 0);
            nodes.add(fn(caller));
            edges.add(new Edge(caller, hot, EdgeKind.CALLS));
        }
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
        // threshold 50 so 'hot' gates and 'cold' does not
        CodeGraphConfig config = new CodeGraphConfig(null, null, null, null,
                new CodeGraphConfig.Gating(50, true, List.of("blast")), null, Map.of(), null);
        tools = CodeGraphTools.standard(graph, config, s -> { });
    }

    private GraphTool tool(String name) {
        return tools.stream().filter(t -> t.spec().name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void blastScoreToolItemizesFactors() throws Exception {
        ToolResponse response = tool("get_blast_score").call(JSON.createObjectNode().put("target", hot.value()));
        assertFalse(response.error(), response.json());
        JsonNode out = JSON.readTree(response.json());
        assertTrue(out.get("score").asInt() >= 50, out.toString());
        assertEquals("fail", out.get("gate").asText());
        assertEquals(5, out.get("factors").size());
        double sum = 0;
        for (JsonNode factor : out.get("factors")) {
            sum += factor.get("points").asDouble();
        }
        assertEquals(out.get("score").asInt(), Math.round(sum), 1.0);
        assertNotNull(out.get("explanation"));
    }

    @Test
    void riskGateAttachesMandatoryReportAboveThreshold() throws Exception {
        ToolResponse response = tool("get_impact_radius")
                .call(JSON.createObjectNode().put("target", hot.value()));
        JsonNode out = JSON.readTree(response.json());
        assertNotNull(out.get("risk"), "risk block missing on high-score target");
        assertTrue(out.get("risk").get("note").asText().contains("MANDATORY RISK REVIEW"));
        assertEquals("fail", out.get("risk").get("gate").asText());
    }

    @Test
    void riskGateStaysQuietBelowThreshold() throws Exception {
        ToolResponse response = tool("get_impact_radius")
                .call(JSON.createObjectNode().put("target", cold.value()));
        JsonNode out = JSON.readTree(response.json());
        assertNull(out.get("risk"));
    }

    @Test
    void deadCodeToolCarriesCaveats() throws Exception {
        ToolResponse response = tool("find_dead_code").call(JSON.createObjectNode());
        JsonNode out = JSON.readTree(response.json());
        assertTrue(out.get("caveats").size() >= 3);
        // cold has no callers → a high-confidence candidate
        boolean found = false;
        for (JsonNode row : out.get("candidates")) {
            if (row.get("id").asText().equals(cold.value())) {
                assertEquals("high", row.get("confidence").asText());
                found = true;
            }
        }
        assertTrue(found, "cold symbol should be a dead-code candidate");
    }
}
