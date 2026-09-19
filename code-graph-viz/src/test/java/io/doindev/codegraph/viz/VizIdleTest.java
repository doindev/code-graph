package io.doindev.codegraph.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class VizIdleTest {
    private final AtomicLong nanos = new AtomicLong();
    private final ConcurrentHashMap<String, VizControl.VizProject> projects = new ConcurrentHashMap<>();
    private final ProjectLifecycle policy = new ProjectLifecycle(projects::remove, nanos::get,
            () -> Instant.EPOCH.plusNanos(nanos.get()));
    private final HttpClient client = HttpClient.newHttpClient();

    private VizControl control(boolean admin) {
        return new VizControl() {
            public List<VizProject> projects() { return List.copyOf(projects.values()); }
            public String mcpEndpoint() { return "stdio"; }
            public boolean mutable() { return admin; }
            public ProjectLifecycle lifecycle() { return policy; }
        };
    }

    private long add() {
        return policy.register("demo", () -> projects.put("demo", new VizControl.VizProject(
                "demo", new InMemoryCodeGraph(), CodeGraphConfig.defaults())));
    }

    private HttpResponse<String> request(VizServer server, String path, String method, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void readonlyUiQueriesAndInteractionRenewButRostersDoNot() throws Exception {
        try (policy; VizServer server = VizServer.start(control(false), 0)) {
            long original = add();
            nanos.set(Duration.ofMinutes(30).toNanos());
            assertEquals(200, request(server, "/api/p/demo/overview", "GET", "").statusCode());
            assertEquals(3600, policy.status("demo").remainingSeconds());
            nanos.set(Duration.ofMinutes(60).toNanos());
            assertEquals(200, request(server, "/api/p/demo/activity", "POST", "").statusCode());
            nanos.set(Duration.ofMinutes(120).toNanos());
            var roster = request(server, "/api/projects", "GET", "");
            assertTrue(roster.body().contains("lastActivityAt"));
            assertEquals(1, policy.expireIdle());
            assertTrue(projects.isEmpty());
            // No cached VizApi can serve or retain the old graph after retirement.
            assertEquals(404, request(server, "/api/p/demo/overview", "GET", "").statusCode());
            assertEquals("[]", request(server, "/api/projects", "GET", "").body());
            add();
            nanos.addAndGet(Duration.ofSeconds(10).toNanos());
            var stale = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/p/demo/activity"))
                    .header("X-Project-Instance", Long.toString(original)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, stale.statusCode());
            assertEquals(3590, policy.status("demo").remainingSeconds());
            assertEquals(403, request(server, "/api/settings", "PUT", "{\"projectTtl\":\"2h\"}").statusCode());
        }
    }

    @Test void adminCanChangeTimeoutWhenEmptyAndShortenExistingDeadlines() throws Exception {
        try (policy; VizServer server = VizServer.start(control(true), 0)) {
            var saved = request(server, "/api/settings", "PUT", "{\"projectTtl\":\"30m\"}");
            assertEquals(200, saved.statusCode());
            assertEquals(1800, new ObjectMapper().readTree(saved.body()).get("projectTtlSeconds").asInt());
            add(); nanos.set(Duration.ofMinutes(20).toNanos());
            assertEquals(200, request(server, "/api/settings", "PUT", "{\"projectTtl\":\"10m\"}").statusCode());
            assertEquals(1, policy.expireIdle());
            for (String json : List.of("{}", "{broken", "{\"projectTtl\":0}", "{\"projectTtl\":\"0s\"}")) {
                assertEquals(400, request(server, "/api/settings", "PUT", json).statusCode());
            }
            assertEquals(Duration.ofMinutes(10), policy.ttl());
        }
    }

    @Test void combinedSettingsValidateBeforePublishingTimeout() throws Exception {
        var memory = new java.util.concurrent.atomic.AtomicReference<>("1536m");
        VizControl control = new VizControl() {
            public List<VizProject> projects() { return List.of(); }
            public String mcpEndpoint() { return "stdio"; }
            public boolean mutable() { return true; }
            public ProjectLifecycle lifecycle() { return policy; }
            public void graphMemory(String budget) {
                if (!List.of("1536m", "2g").contains(budget)) throw new IllegalArgumentException("budget below reserve");
                memory.set(budget);
            }
        };
        try (policy; VizServer server = VizServer.start(control, 0)) {
            assertEquals(400, request(server, "/api/settings", "PUT",
                    "{\"projectTtl\":\"0s\",\"graphMemory\":\"2g\"}").statusCode());
            assertEquals("1536m", memory.get());
            assertEquals(400, request(server, "/api/settings", "PUT",
                    "{\"projectTtl\":\"10m\",\"graphMemory\":\"1m\"}").statusCode());
            assertEquals(Duration.ofHours(1), policy.ttl());
            assertEquals(200, request(server, "/api/settings", "PUT",
                    "{\"projectTtl\":\"10m\",\"graphMemory\":\"2g\"}").statusCode());
            assertEquals(Duration.ofMinutes(10), policy.ttl());
            assertEquals("2g", memory.get());
            assertEquals(200, request(server, "/api/settings", "PUT",
                    "{\"graphMemory\":\"1536m\"}").statusCode());
            assertEquals(Duration.ofMinutes(10), policy.ttl());
            assertEquals("1536m", memory.get());
            assertEquals(400, request(server, "/api/settings", "PUT",
                    "{\"projectTtl\":\"20m\",\"graphMemory\":false}").statusCode());
            assertEquals(Duration.ofMinutes(10), policy.ttl());
        }
    }
}
