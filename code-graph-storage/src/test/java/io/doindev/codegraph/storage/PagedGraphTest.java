package io.doindev.codegraph.storage;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PagedGraphTest {
    static Node node(int i) {
        String path = "src/F" + i % 500 + ".java";
        return new Node(new SymbolId("java", path, "C.m" + i, 0), NodeKind.FUNCTION, "m" + i, "void m" + i + "()",
                new SourceSpan(path, 1, 1, 2, 1), Metrics.NONE, Map.of());
    }
    static Edge edge(int from, int to, float confidence) {
        return new Edge(node(from).id(), node(to).id(), EdgeKind.CALLS, confidence, Map.of());
    }
    static void load(PagedGraph graph, int nodes, int edges) {
        graph.rebuild(b -> {
            for (int i = 0; i < nodes; i++) b.node(node(i));
            for (int i = 0; i < edges; i++) b.edge(edge(i % nodes, (i * 17 + 1) % nodes, i % 5 == 0 ? .5f : 1f));
            return null;
        });
    }

    @Test void equivalentQueriesFailureIsolationAndCleanup() {
        Path directory;
        try (var storage = new GraphStorage(true, 32L << 20)) {
            directory = storage.directory();
            var first = (PagedGraph) storage.create(); var second = (PagedGraph) storage.create();
            var memory = new InMemoryCodeGraph();
            var nodes = java.util.stream.IntStream.range(0, 100).mapToObj(PagedGraphTest::node).toList();
            var edges = java.util.stream.IntStream.range(0, 300).mapToObj(i -> edge(i % 100, (i * 17 + 1) % 100, i % 5 == 0 ? .5f : 1f)).toList();
            memory.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
            load(first, 100, 300); load(second, 10, 10);
            assertEquals(memory.findSymbols("C.M1", null, "java", 10), first.findSymbols("C.M1", null, "java", 10));
            for (Direction direction : Direction.values()) {
                assertEquals(memory.edges(node(0).id(), direction, Set.of(EdgeKind.CALLS)), first.edges(node(0).id(), direction, Set.of(EdgeKind.CALLS)));
                var memoryScan=new ArrayList<Edge>(); var diskScan=new ArrayList<Edge>();
                memory.scanEdges(node(0).id(),direction,Set.of(EdgeKind.CALLS),memoryScan::add);
                first.scanEdges(node(0).id(),direction,Set.of(EdgeKind.CALLS),diskScan::add);
                assertEquals(memoryScan,diskScan);
                assertEquals(first.edges(node(0).id(),direction,Set.of(EdgeKind.CALLS)),diskScan);
                assertEquals(memory.closure(node(0).id(), direction, null, 4, 25, .8f), first.closure(node(0).id(), direction, null, 4, 25, .8f));
            }
            assertThrows(IllegalStateException.class, () -> first.rebuild(b -> { b.node(node(999)); throw new IllegalStateException("injected write failure"); }));
            assertEquals(1, first.generation()); assertTrue(first.node(node(999).id()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> first.rebuild(b -> {
                b.auxiliary("oversized", new byte[PagedGraph.MAX_RECORD_BYTES + 1]); return null;
            }));
            assertEquals(1, first.generation());
            assertEquals(2, storage.status().get("activeAndStagedStores"));
            first.close(); assertEquals(1, second.generation()); assertEquals(1, storage.status().get("activeAndStagedStores"));
            assertThrows(IllegalStateException.class, () -> first.node(node(0).id()));
            assertThrows(IllegalArgumentException.class, () -> storage.checkRoot(directory.getParent()));
        }
        assertFalse(Files.exists(directory));
    }

    @Test @Timeout(60) void compoundReadersSeeOneGeneration() throws Exception {
        try (var storage = new GraphStorage(true, 32L << 20); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var graph = (PagedGraph) storage.create(); load(graph, 10, 10);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var reader = executor.submit(() -> graph.read(() -> {
                long generation = graph.generation(); entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                assertEquals(generation, graph.generation()); return generation;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var writer = executor.submit(() -> load(graph, 20, 20));
            release.countDown(); assertEquals(1L, reader.get()); writer.get(); assertEquals(2, graph.generation());
        }
    }

    @Test void cacheIsSharedShrinkableAndScanResistant() {
        try (var storage = new GraphStorage(true, 64L << 20)) {
            var first = (PagedGraph) storage.create(); var second = (PagedGraph) storage.create();
            load(first, 200, 600); load(second, 200, 600);
            for (int i = 0; i < 200; i++) { first.node(node(i).id()); second.node(node(i).id()); }
            long used = ((Number) storage.status().get("cacheUsedBytesEstimate")).longValue();
            first.scanNodes(null, n -> first.edges(n.id(), Direction.OUT, null));
            assertEquals(used, storage.status().get("cacheUsedBytesEstimate"));
            // Force a full shared cache without manufacturing a duplicate graph in heap.
            for (int i = 0; i < 100; i++) storage.cache("pressure/" + i, new byte[1 << 20]);
            storage.resize(32L << 20);
            assertTrue(((Number) storage.status().get("cacheUsedBytesEstimate")).longValue()
                    <= ((Number) storage.status().get("cacheCapacityBytes")).longValue());
            first.node(node(0).id()); second.node(node(0).id());
            long hits = ((Number) storage.status().get("cacheHits")).longValue();
            first.node(node(0).id()); second.node(node(0).id());
            assertEquals(hits + 2, storage.status().get("cacheHits"));
            assertEquals(1, first.generation()); assertEquals(1, second.generation());
            assertThrows(IllegalArgumentException.class, () -> storage.resize(1L << 20));
            storage.resize(64L << 20);
        }
    }

    @Test void validatesConfigurationAndMemoryDefault() {
        assertEquals(1L << 30, GraphStorage.parseBudget("1g"));
        assertEquals(32L << 20, GraphStorage.parseBudget("32MiB"));
        for (String invalid : List.of("0g", "1m", "-1g", "1.5g", "999999999999999g"))
            assertThrows(IllegalArgumentException.class, () -> GraphStorage.parseBudget(invalid));
        try (var storage = new GraphStorage(false, GraphStorage.DEFAULT_BUDGET)) {
            assertInstanceOf(InMemoryCodeGraph.class, storage.create()); assertNull(storage.directory());
            assertThrows(IllegalArgumentException.class, () -> storage.resize(64L << 20));
        }
    }

    @Test void publishedBuilderCannotMutateAVisibleGeneration() {
        try (var storage = new GraphStorage(true, 32L << 20)) {
            var graph = (PagedGraph) storage.create();
            var builder = graph.rebuild(b -> { b.node(node(1)); return b; });
            assertThrows(IllegalStateException.class, () -> builder.node(node(2)));
            assertEquals(1, graph.status().symbolCount());
        }
    }

    @Test @Timeout(value = 20, unit = TimeUnit.MINUTES)
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "hybrid.stress", matches = "true")
    void graphLargerThanCacheUnderConstrainedHeap() {
        assertTrue(Runtime.getRuntime().maxMemory() <= 256L << 20, "stress test requires -Xmx256m");
        try (var storage = new GraphStorage(true, 32L << 20)) {
            var graph = (PagedGraph) storage.create();
            long start = System.nanoTime(); load(graph, 100_000, 1_000_000);
            long indexedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(100_000, graph.status().symbolCount()); assertEquals(1_000_000, graph.status().edgeCount());
            int[] count = {0}; graph.scanNodes(null, n -> count[0]++); assertEquals(100_000, count[0]);
            graph.node(node(42).id());
            long[] latency = new long[300];
            for (int i = 0; i < latency.length; i++) {
                start = System.nanoTime(); graph.node(node(42).id()); graph.edges(node(42).id(), Direction.IN, Set.of(EdgeKind.CALLS));
                latency[i] = System.nanoTime() - start;
            }
            Arrays.sort(latency);
            assertTrue(((Number) storage.status().get("diskBytes")).longValue() > 32L << 20);
            System.out.println("HYBRID_STRESS indexingMs=" + indexedMs + " p50Ns=" + latency[150] + " p95Ns=" + latency[285] + " p99Ns=" + latency[297] + " stats=" + storage.status());
        }
    }
}
