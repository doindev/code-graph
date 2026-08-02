package io.doindev.codegraph.analysis;

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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The dead-code detector must not flag methods reached through dynamic dispatch. */
class DeadCodeHierarchyTest {

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();

    private static SymbolId type(String path, String qname) {
        return new SymbolId("java", path, qname, 0);
    }

    private static SymbolId method(String path, String qname, int arity) {
        return new SymbolId("java", path, qname, arity);
    }

    private void addType(SymbolId id) {
        nodes.add(new Node(id, NodeKind.TYPE, simple(id), simple(id),
                new SourceSpan(id.relPath(), 1, 1, 30, 1), Metrics.NONE, Map.of()));
    }

    private void addMethod(SymbolId owner, SymbolId m) {
        nodes.add(new Node(m, NodeKind.FUNCTION, simple(m), simple(m) + "()",
                new SourceSpan(m.relPath(), 2, 1, 5, 1), Metrics.NONE, Map.of()));
        edges.add(new Edge(owner, m, EdgeKind.CONTAINS));
    }

    private static String simple(SymbolId id) {
        String q = id.qualifiedName();
        return q.substring(q.lastIndexOf('.') + 1);
    }

    private List<DeadCode.Candidate> run() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));
        return new DeadCode(graph, CodeGraphConfig.defaults()).find(null, Set.of(NodeKind.FUNCTION), 50);
    }

    @Test
    void overrideOfACalledBaseMethodIsNotDead() {
        // Base.run() is called; Impl extends Base and overrides run() with no direct caller.
        SymbolId base = type("src/Base.java", "Base");
        SymbolId baseRun = method("src/Base.java", "Base.run", 0);
        SymbolId caller = method("src/Driver.java", "Driver.go", 0);
        SymbolId impl = type("src/Impl.java", "Impl");
        SymbolId implRun = method("src/Impl.java", "Impl.run", 0);
        addType(base);
        addMethod(base, baseRun);
        nodes.add(new Node(caller, NodeKind.FUNCTION, "go", "go()",
                new SourceSpan("src/Driver.java", 1, 1, 3, 1), Metrics.NONE, Map.of()));
        addType(impl);
        addMethod(impl, implRun);
        edges.add(new Edge(caller, baseRun, EdgeKind.CALLS));               // Base.run() is called
        edges.add(new Edge(impl, base, EdgeKind.EXTENDS, 0.9f, Map.of()));  // Impl extends Base

        List<DeadCode.Candidate> dead = run();
        assertTrue(dead.stream().noneMatch(c -> c.id().equals(implRun.value())),
                "override of a called base method must be excluded (reached via dynamic dispatch): " + dead);
        // the base method has a caller, so it isn't dead either
        assertTrue(dead.stream().noneMatch(c -> c.id().equals(baseRun.value())));
    }

    @Test
    void interfaceMethodWithImplementationsIsLowNotHigh() {
        // Api.handle() — an interface method with an implementing class; nobody calls it directly.
        SymbolId api = type("src/Api.java", "Api");
        SymbolId apiHandle = method("src/Api.java", "Api.handle", 1);
        SymbolId impl = type("src/Impl.java", "Impl");
        SymbolId implHandle = method("src/Impl.java", "Impl.handle", 1);
        addType(api);
        addMethod(api, apiHandle);
        addType(impl);
        addMethod(impl, implHandle);
        edges.add(new Edge(impl, api, EdgeKind.IMPLEMENTS, 0.9f, Map.of()));

        List<DeadCode.Candidate> dead = run();
        DeadCode.Candidate handle = dead.stream()
                .filter(c -> c.id().equals(apiHandle.value())).findFirst().orElse(null);
        assertTrue(handle != null, "interface method with impls should still be listed");
        assertEquals("low", handle.confidence(), "should be lowered, not a firm 'high' hit");
        assertTrue(handle.reason().contains("dynamic dispatch"));
    }

    @Test
    void transitiveInterfaceImplementationIsNotHigh() {
        // Impl -> Base(abstract) -> Api(interface); Api.run() is called; Impl.run() overrides it
        // two hops up. It must be excluded (reached via dynamic dispatch), not a firm hit.
        SymbolId api = type("src/Api.java", "Api");
        SymbolId apiRun = method("src/Api.java", "Api.run", 0);
        SymbolId caller = method("src/Driver.java", "Driver.go", 0);
        SymbolId base = type("src/Base.java", "Base");
        SymbolId impl = type("src/Impl.java", "Impl");
        SymbolId implRun = method("src/Impl.java", "Impl.run", 0);
        addType(api);
        addMethod(api, apiRun);
        nodes.add(new Node(caller, NodeKind.FUNCTION, "go", "go()",
                new SourceSpan("src/Driver.java", 1, 1, 3, 1), Metrics.NONE, Map.of()));
        addType(base);
        addType(impl);
        addMethod(impl, implRun);
        edges.add(new Edge(caller, apiRun, EdgeKind.CALLS));                 // Api.run() called
        edges.add(new Edge(base, api, EdgeKind.IMPLEMENTS, 0.9f, Map.of())); // Base implements Api
        edges.add(new Edge(impl, base, EdgeKind.EXTENDS, 0.9f, Map.of()));   // Impl extends Base

        List<DeadCode.Candidate> dead = run();
        assertTrue(dead.stream().noneMatch(c -> c.id().equals(implRun.value())),
                "two-hop interface override of a called method must be excluded: " + dead);
    }

    @Test
    void aGenuinelyUnreferencedStandaloneMethodStaysHigh() {
        SymbolId util = type("src/Util.java", "Util");
        SymbolId orphan = method("src/Util.java", "Util.orphan", 0);
        addType(util);
        addMethod(util, orphan);

        List<DeadCode.Candidate> dead = run();
        DeadCode.Candidate hit = dead.stream()
                .filter(c -> c.id().equals(orphan.value())).findFirst().orElseThrow();
        assertEquals("high", hit.confidence(), "a real orphan with no hierarchy involvement stays high");
    }
}
