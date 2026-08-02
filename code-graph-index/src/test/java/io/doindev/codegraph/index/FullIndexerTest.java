package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
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

/** Golden corpus: a small mixed-language repo with known-correct extraction results. */
class FullIndexerTest {

    @TempDir
    Path repo;

    private InMemoryCodeGraph graph;
    private FullIndexer.Result result;

    private void write(String relPath, String content) throws IOException {
        Path file = repo.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @BeforeEach
    void indexCorpus() throws IOException {
        write("src/main/java/com/acme/auth/AuthService.java", """
                package com.acme.auth;

                public class AuthService {
                    private String issuer;

                    public boolean validateToken(String jwt) {
                        if (jwt == null) {
                            return false;
                        }
                        return parse(jwt);
                    }

                    private boolean parse(String jwt) {
                        return jwt.length() > 0;
                    }
                }
                """);
        write("src/main/java/com/acme/api/BaseController.java", """
                package com.acme.api;

                public abstract class BaseController {
                    protected void audit(String action) { }
                }
                """);
        write("src/main/java/com/acme/api/LoginController.java", """
                package com.acme.api;

                import com.acme.auth.AuthService;

                public class LoginController extends BaseController {
                    private final AuthService auth = new AuthService();

                    public boolean login(String user) {
                        audit("login");
                        return auth.validateToken(user);
                    }
                }
                """);
        write("web/src/client.ts", """
                export function refresh(token: string): boolean {
                    return validate(token);
                }

                function validate(t: string): boolean {
                    return t.length > 0;
                }
                """);
        write("scripts/repo.py", """
                class Repo:
                    def save(self, item):
                        return persist(item)


                def persist(item):
                    return True
                """);
        write("node_modules/should/be/skipped.js", "function skipped() {}");

        graph = new InMemoryCodeGraph();
        FullIndexer indexer = new FullIndexer(Analyzers.discover(), CodeGraphConfig.defaults());
        result = indexer.index(repo, graph);
    }

    private static SymbolId javaSym(String path, String qname, int arity) {
        return new SymbolId("java", path, qname, arity);
    }

    @Test
    void extractionFailuresAreEmptyAndIgnoredDirsSkipped() {
        assertEquals(List.of(), result.failedFiles());
        assertEquals(5, result.filesIndexed()); // node_modules file skipped
        assertEquals("ready", graph.status().state());
    }

    @Test
    void javaDeclarationsGetPackageQualifiedNamesAndMetrics() {
        Node type = graph.node(javaSym("src/main/java/com/acme/auth/AuthService.java",
                "com.acme.auth.AuthService", 0)).orElseThrow();
        assertEquals(NodeKind.TYPE, type.kind());
        assertEquals(2, type.metrics().methodCount());
        assertEquals(1, type.metrics().fieldCount());

        Node method = graph.node(javaSym("src/main/java/com/acme/auth/AuthService.java",
                "com.acme.auth.AuthService.validateToken", 1)).orElseThrow();
        assertEquals(NodeKind.FUNCTION, method.kind());
        assertTrue(method.metrics().cyclomaticApprox() >= 2, "if-branch should raise complexity");
    }

    @Test
    void crossFileJavaCallResolvesByUniqueName() {
        SymbolId login = javaSym("src/main/java/com/acme/api/LoginController.java",
                "com.acme.api.LoginController.login", 1);
        SymbolId validateToken = javaSym("src/main/java/com/acme/auth/AuthService.java",
                "com.acme.auth.AuthService.validateToken", 1);
        List<Edge> calls = graph.edges(validateToken, Direction.IN, Set.of(EdgeKind.CALLS));
        assertTrue(calls.stream().anyMatch(e -> e.from().equals(login) && e.confidence() >= 0.8f),
                "login -> validateToken call edge missing or low confidence: " + calls);
    }

    @Test
    void extendsEdgeResolvesSameDirectory() {
        SymbolId base = javaSym("src/main/java/com/acme/api/BaseController.java",
                "com.acme.api.BaseController", 0);
        List<Edge> extendsEdges = graph.edges(base, Direction.IN, Set.of(EdgeKind.EXTENDS));
        assertEquals(1, extendsEdges.size());
        assertEquals(0.9f, extendsEdges.get(0).confidence(), 0.001f);
    }

    @Test
    void typeScriptSameFileCallResolvesHighConfidence() {
        SymbolId validate = new SymbolId("ts", "web/src/client.ts", "validate", 1);
        List<Edge> calls = graph.edges(validate, Direction.IN, Set.of(EdgeKind.CALLS));
        assertEquals(1, calls.size());
        assertEquals(0.95f, calls.get(0).confidence(), 0.001f);
        assertEquals(new SymbolId("ts", "web/src/client.ts", "refresh", 1), calls.get(0).from());
    }

    @Test
    void pythonMethodAritySkipsSelfAndCallResolves() {
        SymbolId save = new SymbolId("py", "scripts/repo.py", "Repo.save", 1);
        assertTrue(graph.node(save).isPresent(), "Repo.save/1 (self excluded) missing");
        SymbolId persist = new SymbolId("py", "scripts/repo.py", "persist", 1);
        List<Edge> calls = graph.edges(persist, Direction.IN, Set.of(EdgeKind.CALLS));
        assertEquals(1, calls.size());
        assertEquals(save, calls.get(0).from());
    }

    @Test
    void containsChainLinksFileToTypeToMethod() {
        SymbolId type = javaSym("src/main/java/com/acme/auth/AuthService.java",
                "com.acme.auth.AuthService", 0);
        List<Edge> members = graph.edges(type, Direction.OUT, Set.of(EdgeKind.CONTAINS));
        assertTrue(members.size() >= 3, "type should contain 2 methods + 1 field: " + members);
        List<Edge> parents = graph.edges(type, Direction.IN, Set.of(EdgeKind.CONTAINS));
        assertEquals(1, parents.size());
    }

    @Test
    void statusCountsPerLanguage() {
        var status = graph.status();
        assertEquals(3, status.filesPerLang().get("java"));
        assertEquals(1, status.filesPerLang().get("ts"));
        assertEquals(1, status.filesPerLang().get("py"));
    }
}
