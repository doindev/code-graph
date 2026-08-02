package io.doindev.codegraph.store.h2;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.store.GraphSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class H2GraphStoreTest {

    @TempDir
    Path dir;

    private static SymbolId sym(String path, String qname) {
        return new SymbolId("java", path, qname, 1);
    }

    private static Node fn(SymbolId id) {
        return new Node(id, NodeKind.FUNCTION, "m", "void m(int)",
                new SourceSpan(id.relPath(), 3, 1, 9, 2),
                new Metrics(7, 0, 0, 1, 2, 3, Float.NaN),
                Map.of("visibility", "public", "doc", "a;b=c\\d"));
    }

    private static Node fileNode(String path) {
        return new Node(new FileId(path), NodeKind.FILE,
                path.substring(path.lastIndexOf('/') + 1), null, null, Metrics.NONE, Map.of());
    }

    private static final Node FILE_A = fileNode("src/A.java");
    private static final Node FILE_B = fileNode("src/B.java");
    private static final Node SYM_A = fn(sym("src/A.java", "A.m"));
    private static final Node SYM_B = fn(sym("src/B.java", "B.m"));
    private static final Edge CONTAINS_A = new Edge(FILE_A.id(), SYM_A.id(), EdgeKind.CONTAINS);
    private static final Edge CONTAINS_B = new Edge(FILE_B.id(), SYM_B.id(), EdgeKind.CONTAINS);
    private static final Edge CALLS_B_TO_A = new Edge(SYM_B.id(), SYM_A.id(), EdgeKind.CALLS,
            0.8f, Map.of("resolution", "heuristic"));

    private static GraphDelta twoFilesGen1() {
        return new GraphDelta(1, List.of(), List.of(FILE_A, SYM_A), List.of(CONTAINS_A), List.of());
    }

    private static GraphDelta twoFilesGen2() {
        return new GraphDelta(2, List.of(), List.of(FILE_B, SYM_B),
                List.of(CONTAINS_B, CALLS_B_TO_A), List.of());
    }

    private static final class Collector implements GraphSink {
        final Set<Node> nodes = new HashSet<>();
        final Set<Edge> edges = new HashSet<>();

        @Override
        public void node(Node node) {
            nodes.add(node);
        }

        @Override
        public void edge(Edge edge) {
            edges.add(edge);
        }
    }

    private H2GraphStore open() {
        return new H2GraphStore(dir.resolve("graph"));
    }

    @Test
    void replayRoundTripsRecordsExactly() {
        try (H2GraphStore store = open()) {
            store.apply(twoFilesGen1());
            store.apply(twoFilesGen2());
            assertEquals(2, store.appliedGeneration());

            Collector collector = new Collector();
            store.replay(collector);
            assertEquals(Set.of(FILE_A, FILE_B, SYM_A, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_A, CONTAINS_B, CALLS_B_TO_A), collector.edges);
        }
    }

    @Test
    void removedFileDropsItsNodesAndEveryTouchingEdge() {
        try (H2GraphStore store = open()) {
            store.apply(twoFilesGen1());
            store.apply(twoFilesGen2());
            store.apply(new GraphDelta(3, List.of(new FileId("src/A.java")),
                    List.of(), List.of(), List.of()));
            assertEquals(3, store.appliedGeneration());

            Collector collector = new Collector();
            store.replay(collector);
            // survivor keeps its nodes; the CALLS edge from surviving B into removed A is gone too
            assertEquals(Set.of(FILE_B, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_B), collector.edges);
        }
    }

    @Test
    void removeEdgesDeletesExactFromToKindMatches() {
        try (H2GraphStore store = open()) {
            store.apply(twoFilesGen1());
            store.apply(twoFilesGen2());
            store.apply(new GraphDelta(3, List.of(), List.of(), List.of(),
                    List.of(new Edge(SYM_B.id(), SYM_A.id(), EdgeKind.CALLS))));

            Collector collector = new Collector();
            store.replay(collector);
            assertEquals(Set.of(FILE_A, FILE_B, SYM_A, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_A, CONTAINS_B), collector.edges);
        }
    }

    @Test
    void generationAndDataSurviveCloseAndReopen() {
        try (H2GraphStore store = open()) {
            store.apply(twoFilesGen1());
            store.apply(twoFilesGen2());
            assertEquals(2, store.appliedGeneration());
        }
        try (H2GraphStore reopened = open()) {
            assertEquals(2, reopened.appliedGeneration());
            Collector collector = new Collector();
            reopened.replay(collector);
            assertEquals(Set.of(FILE_A, FILE_B, SYM_A, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_A, CONTAINS_B, CALLS_B_TO_A), collector.edges);
        }
    }
}
