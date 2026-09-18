package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.SymbolId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IncrementalIndexerTest {

    @TempDir
    Path repo;

    private InMemoryCodeGraph graph;
    private IncrementalIndexer indexer;

    private void write(String relPath, String content) throws IOException {
        Path file = repo.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static SymbolId sym(String path, String qname, int arity) {
        return new SymbolId("java", path, qname, arity);
    }

    @BeforeEach
    void setUp() throws IOException {
        write("src/A.java", """
                public class A {
                    public void a() { }
                }
                """);
        write("src/B.java", """
                public class B {
                    public void b() { new A().a(); }
                }
                """);
        graph = new InMemoryCodeGraph();
        indexer = new IncrementalIndexer(repo, Analyzers.discover(), CodeGraphConfig.defaults(), graph);
        indexer.fullIndex();
    }

    @Test
    void modifyingACalleeKeepsCrossFileEdgesHealthy() throws IOException {
        SymbolId a = sym("src/A.java", "A.a", 0);
        assertEquals(1, graph.edges(a, Direction.IN, Set.of(EdgeKind.CALLS)).size());
        var reference=graph.edges(a,Direction.IN,Set.of(EdgeKind.CALLS)).getFirst();
        assertEquals("src/B.java",reference.attrs().get("referencePath"));
        assertEquals("identifier_token",reference.attrs().get("referencePrecision"));
        assertEquals("2",reference.attrs().get("referenceStartLine"));

        // add a new method to A — a() keeps its identity, edge must survive re-extraction
        write("src/A.java", """
                public class A {
                    public void a() { helper(); }
                    private void helper() { }
                }
                """);
        indexer.applyChanges(List.of("src/A.java"));

        List<Edge> calls = graph.edges(a, Direction.IN, Set.of(EdgeKind.CALLS));
        assertEquals(1, calls.size(), "cross-file call edge must survive callee re-extraction");
        assertTrue(graph.node(sym("src/A.java", "A.helper", 0)).isPresent());
        // a() -> helper() resolved same-file
        assertEquals(1, graph.edges(sym("src/A.java", "A.helper", 0),
                Direction.IN, Set.of(EdgeKind.CALLS)).size());
    }

    @Test
    void deletingAFileDropsItsSymbolsAndDanglingEdges() throws IOException {
        Files.delete(repo.resolve("src/A.java"));
        indexer.applyChanges(List.of("src/A.java"));

        assertTrue(graph.node(sym("src/A.java", "A.a", 0)).isEmpty());
        // B's call edge is gone (target vanished) but B itself survives
        assertTrue(graph.node(sym("src/B.java", "B.b", 0)).isPresent());
        assertEquals(0, graph.edges(sym("src/B.java", "B.b", 0),
                Direction.OUT, Set.of(EdgeKind.CALLS)).size());
    }

    @Test
    void reAddingAFileHealsPendingReferences() throws IOException {
        Files.delete(repo.resolve("src/A.java"));
        indexer.applyChanges(List.of("src/A.java"));

        // bring A back — B's unresolved call must heal without touching B
        write("src/A.java", """
                public class A {
                    public void a() { }
                }
                """);
        indexer.applyChanges(List.of("src/A.java"));

        SymbolId a = sym("src/A.java", "A.a", 0);
        List<Edge> calls = graph.edges(a, Direction.IN, Set.of(EdgeKind.CALLS));
        assertEquals(1, calls.size(), "pending ref should heal when the target reappears");
        assertEquals(sym("src/B.java", "B.b", 0), calls.get(0).from());
    }

    @Test
    void newFileCreatesSymbolsAndResolvesItsCalls() throws IOException {
        write("src/C.java", """
                public class C {
                    public void c() { new A().a(); }
                }
                """);
        indexer.applyChanges(List.of("src/C.java"));

        SymbolId a = sym("src/A.java", "A.a", 0);
        assertEquals(2, graph.edges(a, Direction.IN, Set.of(EdgeKind.CALLS)).size());
        assertEquals(2L, graph.generation()); // full index = 1, this batch = 2
    }

    @Test
    void touchOnlyEventsAreDropped() throws IOException {
        long generation = graph.generation();
        // rewrite identical content
        write("src/A.java", Files.readString(repo.resolve("src/A.java")));
        indexer.applyChanges(List.of("src/A.java"));
        assertEquals(generation, graph.generation(), "unchanged content must not bump the graph");
    }
    @Test
    void overloadAmbiguityAndUnicodeReferencePositionsAreExplicit() throws IOException {
        String line="  void call() { String emoji = \"🙂\"; pick(1); }";
        write("Overloads.java","class Overloads {\n void pick(int x) {}\n void pick(String x) {}\n"+line+"\n}");
        indexer.applyChanges(List.of("Overloads.java"));
        var caller=graph.findSymbols("Overloads.call",null,"java",10).getFirst();
        var refs=graph.edges(caller.id(),Direction.OUT,Set.of(EdgeKind.CALLS));
        assertEquals(2,refs.size());
        for(var edge:refs){
            assertTrue(edge.confidence()<.5f);assertTrue(edge.attrs().get("resolution").endsWith("ambiguous"));
            assertEquals("identifier_token",edge.attrs().get("referencePrecision"));
            assertEquals(String.valueOf(line.indexOf("pick")+1),edge.attrs().get("referenceStartColumn"));
            assertEquals(String.valueOf(line.indexOf("pick")+4),edge.attrs().get("referenceEndColumn"));
        }
    }
}
