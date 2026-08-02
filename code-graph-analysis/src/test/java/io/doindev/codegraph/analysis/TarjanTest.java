package io.doindev.codegraph.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarjanTest {

    @Test
    void findsCyclesAndIgnoresAcyclicNodes() {
        Map<String, Set<String>> graph = Map.of(
                "a", Set.of("b"),
                "b", Set.of("c"),
                "c", Set.of("a", "d"),   // a-b-c cycle
                "d", Set.of("e"),
                "e", Set.of("d"),        // d-e cycle
                "f", Set.of("a"));       // acyclic feeder
        List<List<String>> cycles = Tarjan.cycles(graph);
        assertEquals(2, cycles.size());
        assertTrue(cycles.contains(List.of("a", "b", "c")));
        assertTrue(cycles.contains(List.of("d", "e")));
    }

    @Test
    void selfLoopAloneIsNotACycleComponent() {
        // size-1 SCCs are excluded even with self edges (module self-dependency is normal)
        assertEquals(0, Tarjan.cycles(Map.of("a", Set.of("a"))).size());
    }

    @Test
    void deepChainDoesNotOverflow() {
        Map<String, Set<String>> chain = new java.util.HashMap<>();
        for (int i = 0; i < 50_000; i++) {
            chain.put("n" + i, Set.of("n" + (i + 1)));
        }
        chain.put("n50000", Set.of("n0")); // one giant cycle
        List<List<String>> cycles = Tarjan.cycles(chain);
        assertEquals(1, cycles.size());
        assertEquals(50_001, cycles.get(0).size());
    }
}
