package io.doindev.codegraph.snapshot;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.doindev.codegraph.store.GraphDelta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSnapshotStoreTest {

    @TempDir
    Path dir;

    private static SymbolId sym(String path, String qname) {
        return new SymbolId("java", path, qname, 1);
    }

    private static Node fn(SymbolId id) {
        return new Node(id, NodeKind.FUNCTION, "m", "void m(int)",
                new SourceSpan(id.relPath(), 3, 1, 9, 1),
                new Metrics(7, 0, 0, 1, 2, 3, Float.NaN),
                Map.of("visibility", "public"));
    }

    private static GraphDelta delta(long gen, String path, String qname) {
        SymbolId id = sym(path, qname);
        return new GraphDelta(gen, List.of(), List.of(fn(id)),
                List.of(new Edge(new FileId(path), id, EdgeKind.CONTAINS)), List.of());
    }

    @Test
    void snapshotRoundTripPreservesEverything() throws IOException {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        graph.apply(delta(1, "src/A.java", "A.m"));
        graph.apply(delta(2, "src/B.java", "B.m"));
        graph.apply(new GraphDelta(3, List.of(),
                List.of(fn(sym("src/C.java", "C.m"))),
                List.of(new Edge(sym("src/C.java", "C.m"), sym("src/A.java", "A.m"),
                        EdgeKind.CALLS, 0.8f, Map.of("resolution", "heuristic"))),
                List.of()));

        var out = new java.io.ByteArrayOutputStream();
        SnapshotCodec.Writer writer = new SnapshotCodec.Writer();
        graph.export(writer);
        writer.writeTo(out, graph.generation(), InMemoryCodeGraph.ENGINE_VERSION);

        SnapshotCodec.Writer readBack = new SnapshotCodec.Writer();
        long generation = SnapshotCodec.read(new java.io.ByteArrayInputStream(out.toByteArray()), readBack);
        assertEquals(3, generation);

        InMemoryCodeGraph restored = new InMemoryCodeGraph();
        java.util.List<Node> nodes = new java.util.ArrayList<>();
        java.util.List<Edge> edges = new java.util.ArrayList<>();
        readBack.drainTo(nodes::add, edges::add);
        restored.apply(new GraphDelta(generation, List.of(), nodes, edges, List.of()));

        Node original = graph.node(sym("src/A.java", "A.m")).orElseThrow();
        Node roundTripped = restored.node(sym("src/A.java", "A.m")).orElseThrow();
        assertEquals(original, roundTripped);
        List<Edge> calls = restored.edges(sym("src/A.java", "A.m"), Direction.IN, Set.of(EdgeKind.CALLS));
        assertEquals(1, calls.size());
        assertEquals(0.8f, calls.get(0).confidence());
        assertEquals("heuristic", calls.get(0).attrs().get("resolution"));
        assertEquals(graph.status().edgeCount(), restored.status().edgeCount());
    }

    @Test
    void journalSurvivesRestartAndReplays() {
        try (FileSnapshotStore store = new FileSnapshotStore(dir)) {
            store.apply(delta(1, "src/A.java", "A.m"));
            store.apply(delta(2, "src/B.java", "B.m"));
            assertEquals(2, store.appliedGeneration());
        }
        try (FileSnapshotStore reopened = new FileSnapshotStore(dir)) {
            assertEquals(2, reopened.appliedGeneration());
            InMemoryCodeGraph graph = reopened.loadReduced();
            assertEquals(2, graph.generation());
            assertTrue(graph.node(sym("src/A.java", "A.m")).isPresent());
            assertTrue(graph.node(sym("src/B.java", "B.m")).isPresent());
            assertEquals(2, graph.status().edgeCount());
        }
    }

    @Test
    void compactionFoldsJournalIntoSnapshotAndKeepsSemantics() {
        try (FileSnapshotStore store = new FileSnapshotStore(dir)) {
            store.apply(delta(1, "src/A.java", "A.m"));
            store.apply(delta(2, "src/B.java", "B.m"));
            // file removal must survive both journal replay and compaction
            store.apply(new GraphDelta(3, List.of(new FileId("src/A.java")), List.of(), List.of(), List.of()));

            InMemoryCodeGraph current = store.loadReduced();
            assertTrue(current.node(sym("src/A.java", "A.m")).isEmpty());
            store.compact(current);

            store.apply(delta(4, "src/D.java", "D.m"));
        }
        try (FileSnapshotStore reopened = new FileSnapshotStore(dir)) {
            InMemoryCodeGraph graph = reopened.loadReduced();
            assertEquals(4, graph.generation());
            assertTrue(graph.node(sym("src/A.java", "A.m")).isEmpty());
            assertTrue(graph.node(sym("src/B.java", "B.m")).isPresent());
            assertTrue(graph.node(sym("src/D.java", "D.m")).isPresent());
        }
    }

    @Test
    void tornJournalTailIsIgnored() throws IOException {
        try (FileSnapshotStore store = new FileSnapshotStore(dir)) {
            store.apply(delta(1, "src/A.java", "A.m"));
        }
        // simulate a crash mid-append: a length prefix promising more bytes than exist
        Files.write(dir.resolve("graph.journal"), new byte[] {0, 0, 1, 0, 42, 42},
                StandardOpenOption.APPEND);
        try (FileSnapshotStore reopened = new FileSnapshotStore(dir)) {
            InMemoryCodeGraph graph = reopened.loadReduced();
            assertEquals(1, graph.generation());
            assertTrue(graph.node(sym("src/A.java", "A.m")).isPresent());
        }
    }
}
