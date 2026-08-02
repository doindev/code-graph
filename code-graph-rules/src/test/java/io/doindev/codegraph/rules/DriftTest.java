package io.doindev.codegraph.rules;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftTest {

    private InMemoryCodeGraph graph;
    private CodeGraphConfig config;

    private static SymbolId sym(String path, String qname) {
        return new SymbolId("java", path, qname, 0);
    }

    private static Node fn(SymbolId id) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, name + "()",
                new SourceSpan(id.relPath(), 1, 1, 3, 1), Metrics.NONE, Map.of());
    }

    @BeforeEach
    void setUp() {
        graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        SymbolId apiFn = sym("api/src/Api.java", "Api.handle");
        SymbolId domainFn = sym("domain/src/Order.java", "Order.total");
        SymbolId infraFn = sym("infra/src/Jdbc.java", "Jdbc.save");
        nodes.add(fn(apiFn));
        nodes.add(fn(domainFn));
        nodes.add(fn(infraFn));
        edges.add(new Edge(apiFn, domainFn, EdgeKind.CALLS));      // allowed: api -> domain
        edges.add(new Edge(domainFn, infraFn, EdgeKind.CALLS));    // violation: domain -/-> infra
        edges.add(new Edge(infraFn, domainFn, EdgeKind.REFERENCES)); // allowed: infra -> domain, closes d<->i cycle
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));

        config = new CodeGraphConfig(null, null, null, null, null, null, Map.of(),
                new CodeGraphConfig.Architecture(
                        List.of(new CodeGraphConfig.ArchModule("api", List.of("api/**")),
                                new CodeGraphConfig.ArchModule("domain", List.of("domain/**")),
                                new CodeGraphConfig.ArchModule("infra", List.of("infra/**"))),
                        Map.of("api", List.of("domain"), "infra", List.of("domain")),
                        true, "warn"));
    }

    @Test
    void detectsLayerViolationAndCycleWithStableFingerprints() {
        Drift.Report report = Drift.evaluate(graph, config);
        assertEquals(2, report.violations().size(), report.violations().toString());

        Drift.Violation layer = report.violations().stream()
                .filter(v -> v.type().equals("layer")).findFirst().orElseThrow();
        assertEquals("domain", layer.from());
        assertEquals("infra", layer.to());
        assertEquals("layer:domain->infra:Order.total/0", layer.fingerprint());
        assertTrue(layer.witnesses().get(0).contains("Jdbc.save"));

        Drift.Violation cycle = report.violations().stream()
                .filter(v -> v.type().equals("cycle")).findFirst().orElseThrow();
        assertEquals("cycle:domain|infra", cycle.fingerprint());
    }

    @Test
    void diffReportsOnlyNewViolations() {
        Drift.Report report = Drift.evaluate(graph, config);
        // baseline already had the cycle but not the layer violation
        Drift.Diff diff = Drift.diff(report, Set.of("cycle:domain|infra", "layer:api->infra:Gone.symbol/0"));
        assertEquals(1, diff.newViolations().size());
        assertEquals("layer", diff.newViolations().get(0).type());
        assertEquals(1, diff.preexisting());
        assertEquals(1, diff.fixed());
    }

    @Test
    void noBlueprintMeansOnlyCycleChecksOnDirectoryModules() {
        Drift.Report report = Drift.evaluate(graph, CodeGraphConfig.defaults());
        assertTrue(report.violations().stream().allMatch(v -> v.type().equals("cycle")));
        assertEquals(1, report.violations().size()); // domain <-> infra via top-level dirs
    }
}
