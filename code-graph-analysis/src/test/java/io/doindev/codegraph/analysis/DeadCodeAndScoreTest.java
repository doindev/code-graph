package io.doindev.codegraph.analysis;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadCodeAndScoreTest {

    private InMemoryCodeGraph graph;

    private static SymbolId fnId(String path, String qname) {
        return new SymbolId("java", path, qname, 0);
    }

    private static Node fn(SymbolId id) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, name + "()",
                new SourceSpan(id.relPath(), 1, 1, 5, 1), Metrics.NONE, Map.of());
    }

    @BeforeEach
    void setUp() {
        graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        // popular: called from a test file and a main file in another module
        nodes.add(fn(fnId("core/src/Service.java", "Service.popular")));
        nodes.add(fn(fnId("api/src/Api.java", "Api.caller")));
        nodes.add(fn(fnId("core/src/test/ServiceTest.java", "ServiceTest.test")));
        edges.add(new Edge(fnId("api/src/Api.java", "Api.caller"),
                fnId("core/src/Service.java", "Service.popular"), EdgeKind.CALLS));
        edges.add(new Edge(fnId("core/src/test/ServiceTest.java", "ServiceTest.test"),
                fnId("core/src/Service.java", "Service.popular"), EdgeKind.CALLS));
        // orphan: nothing references it
        nodes.add(fn(fnId("core/src/Orphan.java", "Orphan.unused")));
        // selfish: referenced only from its own file
        nodes.add(fn(fnId("core/src/Selfish.java", "Selfish.helper")));
        nodes.add(fn(fnId("core/src/Selfish.java", "Selfish.user")));
        edges.add(new Edge(fnId("core/src/Selfish.java", "Selfish.user"),
                fnId("core/src/Selfish.java", "Selfish.helper"), EdgeKind.CALLS));
        // entry point by name
        nodes.add(fn(fnId("app/src/Main.java", "Main.main")));
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
    }

    @Test
    void deadCodeTiersAndEntryPointExclusion() {
        DeadCode deadCode = new DeadCode(graph, CodeGraphConfig.defaults());
        List<DeadCode.Candidate> candidates = deadCode.find(null, Set.of(), 50);
        Map<String, String> byId = new java.util.HashMap<>();
        candidates.forEach(c -> byId.put(c.id(), c.confidence()));

        assertEquals("high", byId.get(fnId("core/src/Orphan.java", "Orphan.unused").value()));
        assertEquals("medium", byId.get(fnId("core/src/Selfish.java", "Selfish.helper").value()));
        // 'main' is an entry point; popular has real callers; the caller/test themselves are unreferenced=high
        assertTrue(!byId.containsKey(fnId("app/src/Main.java", "Main.main").value()));
        assertTrue(!byId.containsKey(fnId("core/src/Service.java", "Service.popular").value()));
        // high sorts before medium
        assertEquals("high", candidates.get(0).confidence());
    }

    @Test
    void blastScoreDetectsTestCoverageAndSpread() {
        // default tests glob "**/src/test/**" matches core/src/test/ServiceTest.java
        BlastScore blast = new BlastScore(graph, CodeGraphConfig.defaults());
        BlastScore.Scored popular = blast.compute(fnId("core/src/Service.java", "Service.popular"));
        BlastScore.Scored orphan = blast.compute(fnId("core/src/Orphan.java", "Orphan.unused"));

        assertTrue(popular.score() > orphan.score());
        assertEquals(0, factorRaw(popular, "untested"), 0.001); // has a test dependent
        // no dependents ⇒ no change risk from missing tests, and a zero score overall
        assertEquals(0, factorRaw(orphan, "untested"), 0.001);
        assertEquals(2, factorRaw(popular, "spread"), 0.001);   // api + core modules
        assertEquals(0, orphan.score());
    }

    @Test
    void fileScoreIsMaxOverContainedSymbols() {
        // add file node + CONTAINS so the file resolves members
        FileId file = new FileId("core/src/Service.java");
        graph.apply(new GraphDelta(2, List.of(),
                List.of(new Node(file, NodeKind.FILE, "Service.java", "Service.java",
                        new SourceSpan("core/src/Service.java", 1, 1, 50, 1), Metrics.NONE, Map.of())),
                List.of(new Edge(file, fnId("core/src/Service.java", "Service.popular"), EdgeKind.CONTAINS)),
                List.of()));
        BlastScore blast = new BlastScore(graph, CodeGraphConfig.defaults());
        BlastScore.Scored fileScore = blast.compute(file);
        BlastScore.Scored memberScore = blast.compute(fnId("core/src/Service.java", "Service.popular"));
        assertEquals(memberScore.score(), fileScore.score());
        assertTrue(fileScore.explanation().contains("Service.popular")
                || fileScore.score() == 0);
    }

    private static double factorRaw(BlastScore.Scored scored, String name) {
        return scored.factors().stream().filter(f -> f.name().equals(name))
                .findFirst().orElseThrow().raw();
    }
}
