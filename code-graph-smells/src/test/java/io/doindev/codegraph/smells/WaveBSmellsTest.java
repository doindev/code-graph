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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaveBSmellsTest {

    private static SymbolId sym(String path, String qname, int arity) {
        return new SymbolId("java", path, qname, arity);
    }

    private static Node type(SymbolId id) {
        return new Node(id, NodeKind.TYPE, id.qualifiedName(), id.qualifiedName(),
                new SourceSpan(id.relPath(), 1, 1, 40, 1), Metrics.NONE, Map.of());
    }

    private static Node fn(SymbolId id, String signature) {
        String name = id.qualifiedName().substring(id.qualifiedName().lastIndexOf('.') + 1);
        return new Node(id, NodeKind.FUNCTION, name, signature,
                new SourceSpan(id.relPath(), 2, 1, 8, 1), Metrics.NONE, Map.of());
    }

    private static void contain(List<Edge> edges, SymbolId owner, SymbolId member) {
        edges.add(new Edge(owner, member, EdgeKind.CONTAINS));
    }

    @Test
    void featureEnvyFiresWhenForeignCallsDominate() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        SymbolId home = sym("src/Home.java", "Home", 0);
        SymbolId envious = sym("src/Home.java", "Home.report", 0);
        SymbolId ownHelper = sym("src/Home.java", "Home.helper", 0);
        SymbolId other = sym("src/Other.java", "Other", 0);
        nodes.add(type(home));
        nodes.add(fn(envious, "report()"));
        nodes.add(fn(ownHelper, "helper()"));
        nodes.add(type(other));
        contain(edges, home, envious);
        contain(edges, home, ownHelper);
        for (int i = 0; i < 4; i++) {
            SymbolId foreign = sym("src/Other.java", "Other.m" + i, 0);
            nodes.add(fn(foreign, "m" + i + "()"));
            contain(edges, other, foreign);
            edges.add(new Edge(envious, foreign, EdgeKind.CALLS));
        }
        edges.add(new Edge(envious, ownHelper, EdgeKind.CALLS));
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults())
                .findAll(graph, null, "feature-envy");
        assertEquals(1, findings.size());
        assertEquals(envious.value(), findings.get(0).targetId());
        assertTrue(findings.get(0).evidence().get("enviedType").contains("Other"));
    }

    @Test
    void dataClumpsRequireSpreadAcrossFiles() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        String clumpSig = "(String street, String city, String zipCode)";
        nodes.add(fn(sym("src/A.java", "A.ship", 3), "ship" + clumpSig));
        nodes.add(fn(sym("src/B.java", "B.bill", 3), "bill" + clumpSig));
        nodes.add(fn(sym("src/C.java", "C.route", 3), "route" + clumpSig));
        // control group: clump repeated within one file only
        nodes.add(fn(sym("src/D.java", "D.x", 3), "x(int alpha, int beta, int gamma)"));
        nodes.add(fn(sym("src/D.java", "D.y", 3), "y(int alpha, int beta, int gamma)"));
        nodes.add(fn(sym("src/D.java", "D.z", 3), "z(int alpha, int beta, int gamma)"));
        graph.apply(new GraphDelta(1, List.of(), nodes, List.of(), List.of()));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults())
                .findAll(graph, null, "data-clumps");
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).evidence().get("parameters").contains("street"));
    }

    @Test
    void refusedBequestFiresOnUnusedLargeParent() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        SymbolId parent = sym("src/Base.java", "Base", 0);
        nodes.add(type(parent));
        for (int i = 0; i < 6; i++) {
            SymbolId method = sym("src/Base.java", "Base.op" + i, 0);
            nodes.add(fn(method, "op" + i + "()"));
            contain(edges, parent, method);
        }
        SymbolId refuser = sym("src/Refuser.java", "Refuser", 0);
        SymbolId unrelated = sym("src/Refuser.java", "Refuser.somethingElse", 0);
        nodes.add(type(refuser));
        nodes.add(fn(unrelated, "somethingElse()"));
        contain(edges, refuser, unrelated);
        edges.add(new Edge(refuser, parent, EdgeKind.EXTENDS, 0.9f, Map.of()));

        SymbolId goodChild = sym("src/Good.java", "Good", 0);
        nodes.add(type(goodChild));
        for (int i = 0; i < 3; i++) { // overrides half the parent API
            SymbolId override = sym("src/Good.java", "Good.op" + i, 0);
            nodes.add(fn(override, "op" + i + "()"));
            contain(edges, goodChild, override);
        }
        edges.add(new Edge(goodChild, parent, EdgeKind.EXTENDS, 0.9f, Map.of()));
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults())
                .findAll(graph, null, "refused-bequest");
        assertEquals(1, findings.size());
        assertEquals(refuser.value(), findings.get(0).targetId());
    }

    @Test
    void cloneDetectionFindsDuplicatedBlocks(@TempDir Path repo) throws IOException {
        StringBuilder block = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            block.append("int value").append(i).append(" = compute(").append(i).append(") + offset;\n");
        }
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/One.java"), "class One {\nvoid a() {\n" + block + "}\n}\n");
        Files.writeString(repo.resolve("src/Two.java"), "class Two {\nvoid b() {\n" + block + "}\n}\n");
        Files.writeString(repo.resolve("src/Other.java"), "class Other {\nvoid c() { unrelated(); }\n}\n");

        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        for (String path : List.of("src/One.java", "src/Two.java", "src/Other.java")) {
            nodes.add(new Node(new FileId(path), NodeKind.FILE, path.substring(4), path,
                    new SourceSpan(path, 1, 1, 25, 1), Metrics.NONE, Map.of()));
        }
        graph.apply(new GraphDelta(1, List.of(), nodes, List.of(), List.of()));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults(), repo)
                .findAll(graph, null, "duplicated-logic");
        assertEquals(1, findings.size());
        SmellFinding clone = findings.get(0);
        assertTrue(clone.targetId().contains("One.java"));
        assertTrue(clone.evidence().get("with").contains("Two.java"));
    }

    @Test
    void temporalCouplingMinesGitCoChanges(@TempDir Path repo) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(gitAvailable(), "git not on PATH");
        Files.createDirectories(repo.resolve("src"));
        run(repo, "git", "init", "-q");
        run(repo, "git", "config", "user.email", "t@t");
        run(repo, "git", "config", "user.name", "t");
        for (int i = 0; i < 6; i++) {
            Files.writeString(repo.resolve("src/Left.java"), "class Left { int v = " + i + "; }");
            Files.writeString(repo.resolve("src/Right.java"), "class Right { int v = " + i + "; }");
            run(repo, "git", "add", ".");
            run(repo, "git", "commit", "-q", "-m", "change " + i);
        }
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        for (String path : List.of("src/Left.java", "src/Right.java")) {
            nodes.add(new Node(new FileId(path), NodeKind.FILE, path.substring(4), path,
                    new SourceSpan(path, 1, 1, 2, 1), Metrics.NONE, Map.of()));
        }
        graph.apply(new GraphDelta(1, List.of(), nodes, List.of(), List.of()));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults(), repo)
                .findAll(graph, null, "temporal-coupling");
        assertEquals(1, findings.size());
        assertTrue(findings.get(0).evidence().get("coChanges").startsWith("6"));
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static void run(Path dir, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(dir.toFile())
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed");
        }
    }
}
