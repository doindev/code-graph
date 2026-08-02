package io.doindev.codegraph.smells;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmellEngineTest {

    private InMemoryCodeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();

        // god class: 30 methods, 20 fields, 600 LOC
        SymbolId god = new SymbolId("java", "core/src/God.java", "God", 0);
        nodes.add(new Node(god, NodeKind.TYPE, "God", "God",
                new SourceSpan("core/src/God.java", 1, 1, 600, 1),
                new Metrics(600, 30, 20, 0, 0, 0, Float.NaN), Map.of()));

        // long method: 120 LOC, cyclomatic 22, nesting 6
        SymbolId monster = new SymbolId("java", "core/src/Monster.java", "Monster.process", 2);
        nodes.add(new Node(monster, NodeKind.FUNCTION, "process", "process(a, b)",
                new SourceSpan("core/src/Monster.java", 1, 1, 120, 1),
                new Metrics(120, 0, 0, 2, 6, 22, Float.NaN), Map.of()));

        // clean function for contrast
        SymbolId clean = new SymbolId("java", "core/src/Clean.java", "Clean.ok", 1);
        nodes.add(new Node(clean, NodeKind.FUNCTION, "ok", "ok(x)",
                new SourceSpan("core/src/Clean.java", 1, 1, 8, 1),
                new Metrics(8, 0, 0, 1, 1, 2, Float.NaN), Map.of()));

        // cyclic files: x.java <-> y.java
        SymbolId x = new SymbolId("java", "core/src/x/X.java", "X.a", 0);
        SymbolId y = new SymbolId("java", "core/src/y/Y.java", "Y.b", 0);
        nodes.add(fn(x));
        nodes.add(fn(y));
        edges.add(new Edge(x, y, EdgeKind.CALLS));
        edges.add(new Edge(y, x, EdgeKind.CALLS));

        // large file
        nodes.add(new Node(new FileId("core/src/Big.java"), NodeKind.FILE, "Big.java", "Big.java",
                new SourceSpan("core/src/Big.java", 1, 1, 1500, 1),
                new Metrics(1500, 0, 0, 0, 0, 0, Float.NaN), Map.of()));

        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
    }

    private static Node fn(SymbolId id) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, name + "()",
                new SourceSpan(id.relPath(), 1, 1, 5, 1), Metrics.NONE, Map.of());
    }

    @Test
    void waveADetectorsFireWithEvidence() {
        SmellEngine engine = new SmellEngine(CodeGraphConfig.defaults());
        List<SmellFinding> findings = engine.findAll(graph, null, null);
        Map<String, SmellFinding> bySmell = new java.util.HashMap<>();
        findings.forEach(f -> bySmell.putIfAbsent(f.smell(), f));

        SmellFinding godClass = bySmell.get("god-class");
        assertEquals("java:core/src/God.java#God/0", godClass.targetId());
        assertTrue(godClass.evidence().get("methodCount").startsWith("30"));

        SmellFinding longMethod = bySmell.get("long-method");
        assertEquals("java:core/src/Monster.java#Monster.process/2", longMethod.targetId());
        assertTrue(longMethod.evidence().get("exceeded").contains("cyclomatic 22"));

        assertTrue(bySmell.containsKey("cyclic-files"));
        assertTrue(bySmell.get("cyclic-files").evidence().get("cycle").contains("X.java"));
        assertTrue(bySmell.containsKey("large-file"));

        // the clean function fires nothing
        assertTrue(findings.stream().noneMatch(f -> f.targetId().contains("Clean")));
    }

    @Test
    void configCanDisableAndRetuneDetectors() {
        CodeGraphConfig config = new CodeGraphConfig(null, null, null, null, null, null,
                Map.of("god-class", new CodeGraphConfig.SmellRule(false, "warning", Map.of()),
                        "long-method", new CodeGraphConfig.SmellRule(true, "error",
                                Map.of("loc", 1000.0, "cyclomatic", 1000.0, "nesting", 1000.0))),
                null);
        SmellEngine engine = new SmellEngine(config);
        List<SmellFinding> findings = engine.findAll(graph, null, null);
        assertTrue(findings.stream().noneMatch(f -> f.smell().equals("god-class")), "disabled detector fired");
        assertTrue(findings.stream().noneMatch(f -> f.smell().equals("long-method")), "raised thresholds still fired");
    }

    @Test
    void scopeAndSmellFiltersNarrowResults() {
        SmellEngine engine = new SmellEngine(CodeGraphConfig.defaults());
        List<SmellFinding> onlyGod = engine.findAll(graph, null, "god-class");
        assertEquals(1, onlyGod.size());
        List<SmellFinding> scoped = engine.findAll(graph, "core/src/God.java", null);
        assertTrue(scoped.stream().allMatch(f -> f.targetId().contains("God.java")));
    }
}
