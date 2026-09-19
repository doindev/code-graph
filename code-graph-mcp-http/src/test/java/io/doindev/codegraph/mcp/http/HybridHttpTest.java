package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.index.*;
import io.doindev.codegraph.config.CodeGraphConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridHttpTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpResponse<String> request(String url, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test @Timeout(120) void adminBudgetUiQueriesAndTtlCleanup() throws Exception {
        Files.writeString(root.resolve("A.java"), "class A { void hello() {} }");
        var workspace = Workspace.open(List.of(root), Analyzers.discover(), p -> CodeGraphConfig.defaults(), true, 64L << 20);
        try (var server = HttpServer.start(workspace, 0, 0, true, Duration.ofSeconds(2))) {
            String base = "http://localhost:" + server.vizPort();
            assertEquals(200, request(base + "/", "GET", "").statusCode());
            assertTrue(request(base + "/", "GET", "").body().contains("root-settings"));
            var settings = request(base + "/api/settings", "PUT", "{\"graphMemory\":\"32m\"}");
            assertEquals(200, settings.statusCode(), settings.body());
            assertEquals(32L << 20, json.readTree(settings.body()).path("graphStorage").path("budgetBytes").asLong());
            assertEquals(400, request(base + "/api/settings", "PUT", "{\"graphMemory\":\"1m\"}").statusCode());
            String project = URLEncoder.encode(workspace.names().getFirst(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(200, request(base + "/api/p/" + project + "/search?q=hello", "GET", "").statusCode());
            assertEquals(200, request(base + "/api/p/" + project + "/overview", "GET", "").statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (!workspace.names().isEmpty() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(workspace.names().isEmpty()); assertEquals(0, workspace.storageStatus().get("activeAndStagedStores"));
            assertTrue(Files.exists(root.resolve("A.java")));
        }
    }
    @Test @Timeout(60) void readOnlyAdminCannotResize() throws Exception {
        var workspace = Workspace.open(List.of(), Analyzers.discover(), p -> CodeGraphConfig.defaults(), true, 64L << 20);
        try (var server = HttpServer.start(workspace, 0, 0, false, Duration.ofHours(1))) {
            assertEquals(403, request("http://localhost:" + server.vizPort() + "/api/settings", "PUT", "{\"graphMemory\":\"32m\"}").statusCode());
            assertEquals(64L << 20, workspace.storageStatus().get("budgetBytes"));
        }
    }

    @Test @Timeout(60) void combinedDraftOnRealHybridStorage() throws Exception {
        var workspace = Workspace.open(List.of(), Analyzers.discover(), p -> CodeGraphConfig.defaults(), true, 64L << 20);
        try (var server = HttpServer.start(workspace, 0, 0, true, Duration.ofHours(1))) {
            String base = "http://localhost:" + server.vizPort();
            assertEquals(400, request(base + "/api/settings", "PUT",
                    "{\"graphMemory\":\"1m\",\"projectTtl\":\"10m\"}").statusCode());
            var unchanged = json.readTree(request(base + "/api/server", "GET", "").body());
            assertEquals(3600, unchanged.path("projectTtlSeconds").asInt());
            assertEquals(64L << 20, unchanged.path("graphStorage").path("budgetBytes").asLong());
            var saved = request(base + "/api/settings", "PUT",
                    "{\"graphMemory\":\"32m\",\"projectTtl\":\"10m\"}");
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals(600, json.readTree(saved.body()).path("projectTtlSeconds").asInt());
            assertEquals(32L << 20, workspace.storageStatus().get("budgetBytes"));
        }
    }
}
