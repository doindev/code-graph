package io.doindev.codegraph.viz;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The UI shell (index.html, app.js, style.css, vendored libs) must serve from the classpath. */
class StaticAssetsTest {

    private static List<VizControl.VizProject> tinyProject() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        Node file = new Node(new FileId("app/Main.java"), NodeKind.FILE, "Main.java",
                "app/Main.java", new SourceSpan("app/Main.java", 1, 1, 10, 1),
                new Metrics(10, 0, 0, 0, 0, 0, Float.NaN), Map.of());
        graph.apply(new GraphDelta(1, List.of(), List.of(file), List.of(), List.of()));
        return List.of(new VizControl.VizProject("tiny", graph, CodeGraphConfig.defaults()));
    }

    @Test
    void uiShellAssetsServe() throws Exception {
        try (VizServer server = VizServer.start(VizControl.readOnly(tinyProject(), "stdio"), 0)) {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> index = get(client, base + "/");
            assertEquals(200, index.statusCode());
            assertTrue(index.body().contains("app.js"), "index.html should reference app.js");
            assertTrue(index.body().contains("dim-2d"), "index.html should have the 3D | 2D renderer toggle");
            assertTrue(index.body().contains("add-project"), "index.html should have the add-project affordance");
            assertTrue(index.body().contains("reindex-project"), "index.html should have the reindex affordance");
            assertTrue(index.body().contains("scan-overlay"), "index.html should have the scanning loading modal");
            assertTrue(index.body().contains("scan-timer"), "index.html should have the scan elapsed-time timer");
            assertTrue(index.body().contains("scan-cancel"), "index.html should have the scan cancel button");
            assertTrue(index.body().contains("node-tooltip"), "index.html should have the custom hover tooltip");

            HttpResponse<String> app = get(client, base + "/app.js");
            assertEquals(200, app.statusCode());
            assertTrue(app.body().contains("ForceGraph3D"), "app.js should use the vendored ForceGraph3D global");

            HttpResponse<String> css = get(client, base + "/style.css");
            assertEquals(200, css.statusCode());

            HttpResponse<String> vendor2d = get(client, base + "/vendor/force-graph.min.js");
            assertEquals(200, vendor2d.statusCode());

            HttpResponse<String> serverInfo = get(client, base + "/api/server");
            assertEquals(200, serverInfo.statusCode());
            assertTrue(serverInfo.body().contains("\"mcpEndpoint\":\"stdio\""), serverInfo.body());
            assertTrue(serverInfo.body().contains("\"mutable\":false"), serverInfo.body());
        }
    }

    @Test
    void mutableServerExposesActions() throws Exception {
        List<VizControl.VizProject> projects = tinyProject();
        VizControl mutableControl = new VizControl() {
            @Override
            public List<VizProject> projects() {
                return projects;
            }

            @Override
            public String mcpEndpoint() {
                return "http://127.0.0.1:3000/mcp";
            }

            @Override
            public boolean mutable() {
                return true;
            }

            @Override
            public DirListing browse(String path) {
                return new DirListing("/repos", null,
                        List.of(new DirListing.Entry("demo", "/repos/demo", true)));
            }
        };
        try (VizServer server = VizServer.start(mutableControl, 0)) {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> serverInfo = get(client, base + "/api/server");
            assertEquals(200, serverInfo.statusCode());
            assertTrue(serverInfo.body().contains("\"mutable\":true"), serverInfo.body());

            HttpResponse<String> browse = get(client, base + "/api/browse");
            assertEquals(200, browse.statusCode());
            assertTrue(browse.body().contains("looksLikeRepo"), browse.body());
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
