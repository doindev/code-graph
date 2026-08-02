package io.doindev.codegraph.graph;

import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryCodeGraphTest {

    private static SymbolId sym(String path, String qname) {
        return new SymbolId("java", path, qname, 0);
    }

    private static Node fn(SymbolId id) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, name + "()",
                new SourceSpan(id.relPath(), 1, 1, 5, 1), Metrics.NONE, Map.of());
    }

    /** a <- b <- c (b calls a, c calls b) plus d referencing a heuristically. */
    private InMemoryCodeGraph chainGraph() {
        InMemoryCodeGraph g = new InMemoryCodeGraph();
        SymbolId a = sym("src/A.java", "A.a");
        SymbolId b = sym("src/B.java", "B.b");
        SymbolId c = sym("src/C.java", "C.c");
        SymbolId d = sym("src/D.java", "D.d");
        g.apply(new GraphDelta(1L, List.of(),
                List.of(fn(a), fn(b), fn(c), fn(d)),
                List.of(new Edge(b, a, EdgeKind.CALLS),
                        new Edge(c, b, EdgeKind.CALLS),
                        new Edge(d, a, EdgeKind.REFERENCES, 0.5f, Map.of("resolution", "heuristic"))),
                List.of()));
        return g;
    }

    @Test
    void edgesRespectDirectionAndKind() {
        InMemoryCodeGraph g = chainGraph();
        NodeId a = sym("src/A.java", "A.a");
        assertEquals(1, g.edges(a, Direction.IN, Set.of(EdgeKind.CALLS)).size());
        assertEquals(2, g.edges(a, Direction.IN, null).size());
        assertEquals(0, g.edges(a, Direction.OUT, null).size());
    }

    @Test
    void closureFindsTransitiveDependents() {
        InMemoryCodeGraph g = chainGraph();
        ClosureResult r = g.closure(sym("src/A.java", "A.a"), Direction.IN,
                Set.of(EdgeKind.CALLS, EdgeKind.REFERENCES), 10, 100, 0f);
        assertEquals(3, r.hits().size());
        assertFalse(r.truncated());
        assertEquals(1, r.hits().get(0).depth());
        assertEquals(2, r.hits().stream().filter(h -> h.depth() == 1).count()); // b and d
    }

    @Test
    void closureFiltersByConfidenceAndDepth() {
        InMemoryCodeGraph g = chainGraph();
        ClosureResult confident = g.closure(sym("src/A.java", "A.a"), Direction.IN,
                null, 10, 100, 0.9f);
        assertEquals(2, confident.hits().size()); // d's 0.5 edge dropped

        ClosureResult shallow = g.closure(sym("src/A.java", "A.a"), Direction.IN,
                null, 1, 100, 0f);
        assertTrue(shallow.hits().stream().allMatch(h -> h.depth() == 1));
    }

    @Test
    void closureTruncatesAtNodeLimit() {
        ClosureResult r = chainGraph().closure(sym("src/A.java", "A.a"), Direction.IN,
                null, 10, 1, 0f);
        assertEquals(1, r.hits().size());
        assertTrue(r.truncated());
    }

    @Test
    void removingAFileDropsItsNodesAndAllTouchingEdges() {
        InMemoryCodeGraph g = chainGraph();
        NodeId a = sym("src/A.java", "A.a");
        g.apply(new GraphDelta(2L, List.of(new FileId("src/B.java")), List.of(), List.of(), List.of()));

        assertTrue(g.node(sym("src/B.java", "B.b")).isEmpty());
        // b->a gone; only the heuristic d->a remains inbound on a
        assertEquals(1, g.edges(a, Direction.IN, null).size());
        // c->b gone with b
        assertEquals(0, g.edges(sym("src/C.java", "C.c"), Direction.OUT, null).size());
        assertEquals(2L, g.status().generation());
    }

    @Test
    void findSymbolsMatchesQualifiedNameCaseInsensitive() {
        InMemoryCodeGraph g = chainGraph();
        List<Node> hits = g.findSymbols("b.B", Set.of(NodeKind.FUNCTION), "java", 10);
        assertEquals(1, hits.size());
        assertEquals("b", hits.get(0).name());
        assertEquals(0, g.findSymbols("b.B", null, "py", 10).size());
    }

    @Test
    void statusReflectsGraphContents() {
        InMemoryCodeGraph g = chainGraph();
        var status = g.status();
        assertEquals("ready", status.state());
        assertEquals(4, status.symbolCount());
        assertEquals(4, status.filesIndexed());
        assertEquals(3L, status.edgeCount());
        assertEquals("empty", new InMemoryCodeGraph().status().state());
    }
}
