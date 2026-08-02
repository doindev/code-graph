package io.doindev.codegraph.linker;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpRouteLinkerTest {

    @TempDir
    Path repo;

    private final HttpRouteLinker linker = new HttpRouteLinker();

    @BeforeEach
    void fixture() throws IOException {
        write("web/src/api.ts", """
                export async function getUser(id: string) {
                  const res = await fetch('/api/users/' + id);
                  return res.json();
                }

                export async function createOrder(body: unknown) {
                  return axios.post('/api/orders', body);
                }
                """);
        write("server/app.py", """
                from fastapi import FastAPI

                app = FastAPI()


                @app.get("/api/users/{user_id}")
                def get_user(user_id: int):
                    return {"id": user_id}


                @app.post("/api/orders")
                def create_order(order: dict):
                    return order


                @app.get("/internal/only-server")
                def internal_only():
                    return {}
                """);
        write("server-java/src/main/java/demo/UserController.java", """
                package demo;

                @RestController
                public class UserController {

                    @GetMapping("/api/users/{id}")
                    public String user(@PathVariable long id) {
                        return "user-" + id;
                    }
                }
                """);
    }

    private void write(String relPath, String content) throws IOException {
        Path file = repo.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Optional<Edge> find(List<Edge> edges, String from, String to, String route) {
        return edges.stream()
                .filter(e -> e.from().equals(new FileId(from))
                        && e.to().equals(new FileId(to))
                        && route.equals(e.attrs().get("route")))
                .findFirst();
    }

    @Test
    void tsClientLinksToPythonUsersRoute() {
        List<Edge> edges = linker.link(repo, null);
        Edge edge = find(edges, "web/src/api.ts", "server/app.py", "/api/users/{}")
                .orElseThrow(() -> new AssertionError("missing ts->py users edge in " + edges));
        assertEquals(EdgeKind.INVOKES_REMOTE, edge.kind());
        assertTrue(edge.confidence() >= 0.5f, "confidence was " + edge.confidence());
        assertEquals("/api/users/{}", edge.attrs().get("route"));
        assertEquals("heuristic", edge.attrs().get("resolution"));
        assertEquals("2", edge.attrs().get("clientLine"));
        assertEquals("6", edge.attrs().get("serverLine"));
    }

    @Test
    void tsClientLinksToPythonOrdersRouteWithMethodBonus() {
        List<Edge> edges = linker.link(repo, null);
        Edge edge = find(edges, "web/src/api.ts", "server/app.py", "/api/orders")
                .orElseThrow(() -> new AssertionError("missing ts->py orders edge in " + edges));
        assertEquals("POST", edge.attrs().get("method"));
        assertEquals(0.8f, edge.confidence(), 0.0001f, "exact path + method match must earn the bonus");
    }

    @Test
    void tsClientAlsoLinksToJavaSpringController() {
        List<Edge> edges = linker.link(repo, null);
        Edge edge = find(edges, "web/src/api.ts",
                "server-java/src/main/java/demo/UserController.java", "/api/users/{}")
                .orElseThrow(() -> new AssertionError("missing ts->java users edge in " + edges));
        assertTrue(edge.confidence() >= 0.5f);
        assertEquals("GET", edge.attrs().get("method"), "Spring @GetMapping pins the method");
    }

    @Test
    void neverEmitsSelfEdges() {
        List<Edge> edges = linker.link(repo, null);
        assertTrue(edges.stream().noneMatch(e -> e.from().equals(e.to())),
                "self edges are forbidden: " + edges);
    }

    @Test
    void unmatchedServerOnlyRouteProducesNoEdge() {
        List<Edge> edges = linker.link(repo, null);
        assertTrue(edges.stream().noneMatch(e -> "/internal/only-server".equals(e.attrs().get("route"))),
                "server-only route must not produce an edge: " + edges);
    }

    @Test
    void allEdgesAreHeuristicInvokesRemote() {
        List<Edge> edges = linker.link(repo, null);
        assertTrue(edges.stream().allMatch(e -> e.kind() == EdgeKind.INVOKES_REMOTE));
        assertTrue(edges.stream().allMatch(e -> "heuristic".equals(e.attrs().get("resolution"))));
        assertTrue(edges.stream().allMatch(e -> e.confidence() < 1.0f));
    }

    @Test
    void scanSeesAllFixtureRoutesAndCalls() {
        RouteScan scan = HttpRouteLinker.scan(repo);
        assertEquals(2, scan.clientCalls().size(), "fetch + axios.post: " + scan.clientCalls());
        // 3 python routes + 1 spring route
        assertEquals(4, scan.serverRoutes().size(), scan.serverRoutes().toString());
        assertTrue(scan.serverRoutes().stream()
                .anyMatch(r -> r.normalizedPath().equals("/internal/only-server")));
    }

    @Test
    void ignoredDirectoriesAndAssetsAreSkipped() throws IOException {
        write("node_modules/dep/index.js", "app.get('/api/users/{id}', handler);\n");
        write("web/src/assets.ts", "fetch('/static/logo.png');\n");
        RouteScan scan = HttpRouteLinker.scan(repo);
        assertTrue(scan.serverRoutes().stream().noneMatch(r -> r.relPath().startsWith("node_modules/")),
                "node_modules must be skipped");
        assertTrue(scan.clientCalls().stream().noneMatch(c -> c.rawPath().endsWith(".png")),
                "asset paths must be rejected");
    }

    @Test
    void templateLiteralClientPathMatchesToo() throws IOException {
        write("web/src/api2.ts", "const r = await fetch(`/api/users/${id}`);\n");
        List<Edge> edges = linker.link(repo, null);
        assertTrue(find(edges, "web/src/api2.ts", "server/app.py", "/api/users/{}").isPresent(),
                "template-literal path must normalize to {} segment: " + edges);
    }

    @Test
    void discoveredViaServiceLoader() {
        List<Linker> linkers = ServiceLoader.load(Linker.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertTrue(linkers.stream().anyMatch(l -> "http-routes".equals(l.id())),
                "ServiceLoader must find http-routes, found: "
                        + linkers.stream().map(Linker::id).toList());
    }
}
