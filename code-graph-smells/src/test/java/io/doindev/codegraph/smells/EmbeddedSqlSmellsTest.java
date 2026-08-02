package io.doindev.codegraph.smells;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedSqlSmellsTest {

    @Test
    void findsEmbeddedSqlAndFlagsConcatenation(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("src"));

        // (a) parameterized query — should be found, informational
        Files.writeString(repo.resolve("src/Safe.java"), """
                class Safe {
                    static final String Q =
                        "SELECT id, name FROM users WHERE id = ?";
                    Object run(java.sql.Connection c) throws Exception {
                        return c.prepareStatement(Q);
                    }
                }
                """);

        // (b) concatenated query — should be found, warning (injection risk)
        Files.writeString(repo.resolve("src/Unsafe.java"), """
                class Unsafe {
                    String byId(String id) {
                        return "SELECT * FROM users WHERE id = " + id;
                    }
                }
                """);

        // (c) no SQL — must not be flagged
        Files.writeString(repo.resolve("src/Prose.java"), """
                class Prose {
                    String hi() { return "Please select an option from the menu below."; }
                }
                """);

        InMemoryCodeGraph graph = graphOf(List.of("src/Safe.java", "src/Unsafe.java", "src/Prose.java"));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults(), repo)
                .findAll(graph, null, "embedded-sql");

        assertEquals(2, findings.size(), "expected exactly the two real SQL literals");

        SmellFinding safe = findingFor(findings, "src/Safe.java");
        assertEquals("info", safe.severity());
        assertEquals("select", safe.evidence().get("kind"));
        assertEquals("users", safe.evidence().get("tables"));
        assertEquals("literal/parameterized", safe.evidence().get("built"));

        SmellFinding unsafe = findingFor(findings, "src/Unsafe.java");
        assertEquals("warning", unsafe.severity());
        assertEquals("select", unsafe.evidence().get("kind"));
        assertEquals("users", unsafe.evidence().get("tables"));
        assertTrue(unsafe.evidence().get("built").contains("injection risk"));

        assertTrue(findings.stream().noneMatch(f -> f.targetId().contains("Prose.java")),
                "prose must not be flagged");
    }

    @Test
    void skipsSqlFilesThemselves(@TempDir Path repo) throws IOException {
        Files.createDirectories(repo.resolve("db"));
        Files.writeString(repo.resolve("db/schema.sql"),
                "SELECT * FROM users WHERE id = 1;\nINSERT INTO accounts (id) VALUES (1);\n");
        InMemoryCodeGraph graph = graphOf(List.of("db/schema.sql"));

        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults(), repo)
                .findAll(graph, null, "embedded-sql");
        assertTrue(findings.isEmpty(), ".sql files carry no embedded SQL");
    }

    @Test
    void absentWithoutRepoRoot() {
        InMemoryCodeGraph graph = graphOf(List.of("src/Unsafe.java"));
        List<SmellFinding> findings = new SmellEngine(CodeGraphConfig.defaults())
                .findAll(graph, null, "embedded-sql");
        assertTrue(findings.isEmpty(), "detector needs the working tree; no repoRoot => no findings");
    }

    private static InMemoryCodeGraph graphOf(List<String> paths) {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        for (String path : paths) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            nodes.add(new Node(new FileId(path), NodeKind.FILE, name, path,
                    new SourceSpan(path, 1, 1, 20, 1), Metrics.NONE, Map.of()));
        }
        graph.apply(new GraphDelta(1, List.of(), nodes, List.of(), List.of()));
        return graph;
    }

    private static SmellFinding findingFor(List<SmellFinding> findings, String relPath) {
        return findings.stream().filter(f -> f.targetId().equals("file:" + relPath))
                .findFirst().orElseThrow(() -> new AssertionError("no finding for " + relPath));
    }
}
