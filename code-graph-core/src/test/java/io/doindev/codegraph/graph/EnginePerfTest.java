package io.doindev.codegraph.graph;

import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-1 exit gate: p99 closure query under 5ms on a synthetic 1M-edge graph.
 * Env-gated ({@code CODE_GRAPH_PERF=1}) so CI stays fast; run manually:
 * {@code CODE_GRAPH_PERF=1 mvn -pl code-graph-core test -Dtest=EnginePerfTest}.
 */
@EnabledIfEnvironmentVariable(named = "CODE_GRAPH_PERF", matches = "1")
class EnginePerfTest {

    private static final int NODES = 100_000;
    private static final int EDGES = 1_000_000;
    private static final int QUERIES = 2_000;

    @Test
    void p99ClosureUnder5msOnMillionEdgeGraph() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        Random random = new Random(42);

        SymbolId[] ids = new SymbolId[NODES];
        List<Node> nodes = new ArrayList<>(NODES);
        for (int i = 0; i < NODES; i++) {
            String path = "src/p" + (i % 500) + "/F" + (i / 500) + ".java";
            ids[i] = new SymbolId("java", path, "C" + (i / 20) + ".m" + i, 1);
            nodes.add(new Node(ids[i], NodeKind.FUNCTION, "m" + i, "void m" + i + "(int)",
                    new SourceSpan(path, 1, 1, 5, 1), Metrics.NONE, Map.of()));
        }
        List<Edge> edges = new ArrayList<>(EDGES);
        for (int i = 0; i < EDGES; i++) {
            int from = random.nextInt(NODES);
            int to = random.nextInt(NODES);
            edges.add(new Edge(ids[from], ids[to], EdgeKind.CALLS));
        }
        long buildStart = System.nanoTime();
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
        System.out.printf("bulk apply of %,d nodes / %,d edges: %d ms%n",
                NODES, EDGES, (System.nanoTime() - buildStart) / 1_000_000);

        // warmup
        for (int i = 0; i < 200; i++) {
            graph.closure(ids[random.nextInt(NODES)], Direction.IN, null, 3, 500, 0f);
        }

        long[] samples = new long[QUERIES];
        for (int i = 0; i < QUERIES; i++) {
            SymbolId start = ids[random.nextInt(NODES)];
            long t0 = System.nanoTime();
            ClosureResult r = graph.closure(start, Direction.IN, null, 3, 500, 0f);
            samples[i] = System.nanoTime() - t0;
            if (r.hits().isEmpty() && !r.truncated()) {
                // fine — isolated node; keep the sample, it's a realistic query
            }
        }
        Arrays.sort(samples);
        long p50 = samples[QUERIES / 2] / 1_000;
        long p99 = samples[(int) (QUERIES * 0.99)] / 1_000;
        System.out.printf("closure(depth=3, cap=500) p50=%dus p99=%dus%n", p50, p99);
        assertTrue(p99 < 5_000_000 / 1_000, "p99 closure query took " + p99 + "us (limit 5000us)");
    }
}
