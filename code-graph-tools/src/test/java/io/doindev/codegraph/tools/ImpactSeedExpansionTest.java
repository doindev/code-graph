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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A type's blast radius is the union of its members' dependents (CALL edges land on methods). */
class ImpactSeedExpansionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void typeImpactIncludesCallersOfItsMethods() throws Exception {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        SymbolId service = new SymbolId("java", "src/S.java", "S", 0);
        SymbolId method = new SymbolId("java", "src/S.java", "S.m", 1);
        SymbolId caller = new SymbolId("java", "src/C.java", "C.c", 0);
        Node typeNode = new Node(service, NodeKind.TYPE, "S", "S",
                new SourceSpan("src/S.java", 1, 1, 9, 1), Metrics.NONE, Map.of());
        Node methodNode = new Node(method, NodeKind.FUNCTION, "m", "m(String)",
                new SourceSpan("src/S.java", 2, 1, 4, 1), Metrics.NONE, Map.of());
        Node callerNode = new Node(caller, NodeKind.FUNCTION, "c", "c()",
                new SourceSpan("src/C.java", 1, 1, 3, 1), Metrics.NONE, Map.of());
        Node fileNode = new Node(new FileId("src/S.java"), NodeKind.FILE, "S.java", "S.java",
                new SourceSpan("src/S.java", 1, 1, 9, 1), Metrics.NONE, Map.of());
        graph.apply(new GraphDelta(1, List.of(),
                List.of(typeNode, methodNode, callerNode, fileNode),
                List.of(new Edge(new FileId("src/S.java"), service, EdgeKind.CONTAINS),
                        new Edge(service, method, EdgeKind.CONTAINS),
                        new Edge(caller, method, EdgeKind.CALLS)),
                List.of()));

        GraphTool impact = CodeGraphTools.standard(graph, CodeGraphConfig.defaults(), s -> { })
                .stream().filter(t -> t.spec().name().equals("get_impact_radius")).findFirst().orElseThrow();

        // impact of the TYPE finds the caller of its method
        ToolResponse response = impact.call(JSON.createObjectNode().put("target", service.value()));
        JsonNode dependents = JSON.readTree(response.json()).get("dependents");
        assertEquals(1, dependents.get("total").asInt());
        assertEquals(caller.value(), dependents.get("direct").get(0).asText());

        // impact of the FILE finds it too
        response = impact.call(JSON.createObjectNode().put("target", "src/S.java"));
        dependents = JSON.readTree(response.json()).get("dependents");
        assertEquals(1, dependents.get("total").asInt());
    }
}
