package io.doindev.codegraph.viz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VizServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static VizServer server;
    private static HttpClient client;
    private static String base;

    private static SymbolId sym(String path, String qname) {
        return new SymbolId("java", path, qname, 0);
    }

    @BeforeAll
    static void start() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        // two top-level modules: api/ and core/
        for (String path : List.of("api/Login.java", "core/Auth.java")) {
            nodes.add(new Node(new FileId(path), NodeKind.FILE, path.substring(path.indexOf('/') + 1),
                    path, new SourceSpan(path, 1, 1, 60, 1),
                    new Metrics(60, 0, 0, 0, 0, 0, Float.NaN), Map.of()));
        }
        SymbolId login = sym("api/Login.java", "Login.login");
        SymbolId validate = sym("core/Auth.java", "Auth.validate");
        nodes.add(new Node(login, NodeKind.FUNCTION, "login", "login(u)",
                new SourceSpan("api/Login.java", 5, 1, 15, 1), Metrics.NONE, Map.of()));
        nodes.add(new Node(validate, NodeKind.FUNCTION, "validate", "validate(t)",
                new SourceSpan("core/Auth.java", 5, 1, 15, 1), Metrics.NONE, Map.of()));
        edges.add(new Edge(new FileId("api/Login.java"), login, EdgeKind.CONTAINS));
        edges.add(new Edge(new FileId("core/Auth.java"), validate, EdgeKind.CONTAINS));
        edges.add(new Edge(login, validate, EdgeKind.CALLS));
        graph.apply(new GraphDelta(1, List.of(), nodes, edges, List.of()));

        server = VizServer.start(VizControl.readOnly(List.of(
                new VizControl.VizProject("demo", graph, CodeGraphConfig.defaults())), "stdio"), 0);
        client = HttpClient.newHttpClient();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " -> " + response.body());
        return JSON.readTree(response.body());
    }

    @Test
    void staticAssetsServe() throws Exception {
        HttpResponse<String> vendor = client.send(
                HttpRequest.newBuilder(URI.create(base + "/vendor/3d-force-graph.min.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, vendor.statusCode());
        assertTrue(vendor.body().contains("ForceGraph3D"));
    }

    @Test
    void projectsAndOverview() throws Exception {
        JsonNode projects = get("/api/projects");
        assertEquals("demo", projects.get(0).get("name").asText());
        assertEquals(2, projects.get(0).get("files").asInt());

        JsonNode overview = get("/api/p/demo/overview");
        assertEquals(2, overview.get("nodes").size()); // api + core modules
        assertEquals(1, overview.get("links").size()); // api -> core
        assertEquals("mod:api", overview.get("links").get(0).get("source").asText());
    }

    @Test
    void moduleFileEgoDrillDown() throws Exception {
        JsonNode module = get("/api/p/demo/module?name=api");
        assertTrue(module.get("nodes").toString().contains("file:api/Login.java"));
        assertTrue(module.get("links").toString().contains("mod:core"),
                "boundary edge to core module expected: " + module);

        JsonNode file = get("/api/p/demo/file?path=" + enc("api/Login.java"));
        assertTrue(file.get("nodes").toString().contains("Login.login"));

        JsonNode ego = get("/api/p/demo/ego?id=" + enc("java:core/Auth.java#Auth.validate/0"));
        assertEquals(2, ego.get("nodes").size());
        assertEquals("CALLS", ego.get("links").get(0).get("kind").asText());
    }

    @Test
    void galaxyNodeDetailsAndSearch() throws Exception {
        JsonNode galaxy = get("/api/p/demo/galaxy?cap=100");
        assertEquals(2, galaxy.get("nodes").size());

        JsonNode details = get("/api/p/demo/node?id=" + enc("java:core/Auth.java#Auth.validate/0"));
        assertEquals(1, details.get("counts").get("callers").asInt());
        assertTrue(details.get("blast").has("score"));
        assertEquals(5, details.get("blast").get("factors").size());

        JsonNode search = get("/api/p/demo/search?q=validate");
        assertEquals(1, search.size());
    }

    @Test
    void unknownProjectAndBadParamsAreClean() throws Exception {
        HttpResponse<String> unknown = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/p/nope/overview")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unknown.statusCode());

        HttpResponse<String> missing = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/p/demo/ego")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, missing.statusCode());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
