package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exit gate for this module: boots the real stack (index a mini repo, Jetty, MCP streamable
 * HTTP) on an ephemeral port and drives the protocol with a plain {@link HttpClient} —
 * initialize, initialized notification, tools/list.
 */
class HttpEndToEndTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path repo;

    @Test
    @Timeout(120)
    void initializeThenListToolsOverStreamableHttp() throws Exception {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src").resolve("Hello.java"), """
                public class Hello {
                    public static int add(int a, int b) {
                        return a + b;
                    }
                }
                """, StandardCharsets.UTF_8);

        try (HttpServer server = HttpServer.start(repo, 0)) {
            URI endpoint = URI.create("http://localhost:" + server.port() + HttpServer.MCP_ENDPOINT);
            HttpClient client = HttpClient.newHttpClient();

            // 1. initialize — the transport answers this one as plain JSON + mcp-session-id header
            HttpResponse<String> initResponse = client.send(post(endpoint, null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
                    "protocolVersion":"2025-06-18","capabilities":{},\
                    "clientInfo":{"name":"e2e-test","version":"0.0.1"}}}"""),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initResponse.statusCode());
            assertTrue(payload(initResponse.body()).contains("serverInfo"),
                    "initialize result should carry serverInfo, got: " + initResponse.body());
            String sessionId = initResponse.headers().firstValue("mcp-session-id").orElseThrow(
                    () -> new AssertionError("no mcp-session-id header on initialize response"));

            // 2. initialized notification — accepted with 202, no body
            HttpResponse<String> initialized = client.send(post(endpoint, sessionId,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, initialized.statusCode());

            // 3. tools/list — comes back SSE-framed; stream it in case the connection lingers
            HttpResponse<java.io.InputStream> toolsResponse = client.send(post(endpoint, sessionId,
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, toolsResponse.statusCode());
            String toolsBody = readUntil(toolsResponse.body(), "search_symbols");
            assertTrue(payload(toolsBody).contains("search_symbols"),
                    "tools/list should include search_symbols, got: " + toolsBody);
        }
    }

    @Test
    @Timeout(120)
    void emptyWorkspaceServesUiAndStableMcpToolCatalog() throws Exception {
        try (HttpServer server = HttpServer.start(List.of(), 0, 0)) {
            HttpClient client = HttpClient.newHttpClient();
            URI projectsEndpoint = URI.create(
                    "http://localhost:" + server.vizPort() + "/api/projects");
            HttpResponse<String> projects = client.send(
                    HttpRequest.newBuilder(projectsEndpoint).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, projects.statusCode());
            assertEquals("[]", projects.body());

            URI endpoint = URI.create("http://localhost:" + server.port() + HttpServer.MCP_ENDPOINT);
            HttpResponse<String> initResponse = client.send(post(endpoint, null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
                    "protocolVersion":"2025-06-18","capabilities":{},\
                    "clientInfo":{"name":"empty-e2e-test","version":"0.0.1"}}}"""),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, initResponse.statusCode());
            String sessionId = initResponse.headers().firstValue("mcp-session-id").orElseThrow();
            client.send(post(endpoint, sessionId,
                            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    HttpResponse.BodyHandlers.ofString());

            HttpResponse<java.io.InputStream> toolsResponse = client.send(post(endpoint, sessionId,
                            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, toolsResponse.statusCode());
            assertTrue(payload(readUntil(toolsResponse.body(), "search_symbols"))
                    .contains("search_symbols"));

            HttpResponse<java.io.InputStream> callResponse = client.send(post(endpoint, sessionId,
                            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                                    + "\"name\":\"search_symbols\",\"arguments\":{\"query\":\"x\"}}}"),
                    HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, callResponse.statusCode());
            assertTrue(payload(readUntil(callResponse.body(), "no projects onboarded"))
                    .contains("no projects onboarded"));
        }
    }

    @Test
    @Timeout(120)
    void mcpOnboardingWorksWithoutTheUi() throws Exception {
        exerciseMcpOnboarding(false);
    }

    @Test
    @Timeout(120)
    void mcpOnboardingWorksWithReadOnlyUiAndUpdatesItsRoster() throws Exception {
        exerciseMcpOnboarding(true);
    }

    private void exerciseMcpOnboarding(boolean readOnlyUi) throws Exception {
        exerciseMcpOnboarding(readOnlyUi, false);
    }

    @Test @Timeout(120)
    void hybridMcpOnboardingSearchWatchingAndRemoval() throws Exception {
        exerciseMcpOnboarding(true, true);
    }

    private void exerciseMcpOnboarding(boolean readOnlyUi, boolean hybrid) throws Exception {
        Path parent = Files.createDirectory(repo.resolve("project"));
        Path child = Files.createDirectory(parent.resolve("child"));
        Path sibling = Files.createDirectory(repo.resolve("project-other"));
        Files.writeString(parent.resolve("Hello.java"), "public class Hello {}\n");
        var workspace = io.doindev.codegraph.index.Workspace.open(List.of(), io.doindev.codegraph.index.Analyzers.discover(),
                io.doindev.codegraph.config.loader.ConfigLoader::load, hybrid, 32L << 20);
        try (HttpServer server = HttpServer.start(workspace, 0, readOnlyUi ? 0 : -1, false, java.time.Duration.ofHours(1))) {
            HttpClient client = HttpClient.newHttpClient();
            URI endpoint = URI.create("http://localhost:" + server.port() + HttpServer.MCP_ENDPOINT);
            HttpResponse<String> init = client.send(post(endpoint, null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
                    "protocolVersion":"2025-06-18","capabilities":{},\
                    "clientInfo":{"name":"onboarding-test","version":"1.0"}}}"""),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, init.statusCode());
            String session = init.headers().firstValue("mcp-session-id").orElseThrow();
            client.send(post(endpoint, session,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    HttpResponse.BodyHandlers.ofString());

            JsonNode catalog = rpc(client, endpoint, session, "tools/list", Map.of()).get("tools");
            assertEquals(13, catalog.size());
            assertTrue(catalog.toString().contains("add_project"));
            JsonNode added = callTool(client, endpoint, session, "add_project",
                    Map.of("path", parent.toString()));
            assertFalse(added.path("isError").asBoolean(), added.toString());
            assertEquals("project", toolPayload(added).path("project").asText());
            assertEquals("ready", toolPayload(added).path("state").asText());
            assertEquals(1, toolPayload(callTool(client, endpoint, session, "list_projects", Map.of()))
                    .path("projects").size());
            assertTrue(toolPayload(callTool(client, endpoint, session, "search_symbols",
                    Map.of("query", "Hello"))).path("total").asInt() > 0);

            if (readOnlyUi) {
                String base = "http://localhost:" + server.vizPort();
                HttpResponse<String> roster = client.send(HttpRequest.newBuilder(
                        URI.create(base + "/api/projects")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(1, JSON.readTree(roster.body()).size());
                HttpResponse<String> uiAction = client.send(HttpRequest.newBuilder(
                                URI.create(base + "/api/project?path=ignored"))
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(403, uiAction.statusCode(), "MCP onboarding must not enable UI admin");
            }

            JsonNode denied = callTool(client, endpoint, session, "add_project",
                    Map.of("path", child.toString()));
            assertTrue(denied.path("isError").asBoolean());
            assertTrue(toolPayload(denied).path("error").asText().contains("child path"));
            for (Path invalid : List.of(parent, parent.resolve("Hello.java"), repo.resolve("missing"))) {
                assertTrue(callTool(client, endpoint, session, "add_project",
                        Map.of("path", invalid.toString())).path("isError").asBoolean());
            }
            assertEquals(1, toolPayload(callTool(client, endpoint, session, "list_projects", Map.of()))
                    .path("projects").size());
            assertFalse(callTool(client, endpoint, session, "add_project",
                    Map.of("path", sibling.toString())).path("isError").asBoolean());

            // The first runtime project also gets a watcher, even though startup had no roots.
            Files.writeString(parent.resolve("Later.java"), "public class Later {}\n");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            JsonNode search;
            do {
                search = toolPayload(callTool(client, endpoint, session, "search_symbols",
                        Map.of("query", "Later", "project", "project")));
                if (search.path("total").asInt() > 0) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            assertTrue(search.path("total").asInt() > 0, "newly onboarded project should be watched");

            for (String name : List.of("project", "project-other")) {
                assertFalse(callTool(client, endpoint, session, "remove_project",
                        Map.of("project", name)).path("isError").asBoolean());
            }
            assertEquals(0, toolPayload(callTool(client, endpoint, session, "list_projects", Map.of()))
                    .path("projects").size());
            assertFalse(callTool(client, endpoint, session, "add_project",
                    Map.of("path", child.toString())).path("isError").asBoolean());
        }
    }

    private static JsonNode callTool(HttpClient client, URI endpoint, String session,
                                     String name, Map<String, Object> arguments) throws Exception {
        return rpc(client, endpoint, session, "tools/call", Map.of("name", name, "arguments", arguments));
    }

    @Test
    @Timeout(30)
    void scheduledExpiryIgnoresMcpListingsAndWatcherChanges() throws Exception {
        Path source = Files.writeString(repo.resolve("Hello.java"), "class Hello {}\n");
        var workspace = io.doindev.codegraph.index.Workspace.open(List.of(repo),
                io.doindev.codegraph.index.Analyzers.discover(),
                io.doindev.codegraph.config.loader.ConfigLoader::load);
        try (HttpServer server = HttpServer.start(workspace, 0, 0, true, java.time.Duration.ofSeconds(1))) {
            var client = HttpClient.newHttpClient();
            URI endpoint = URI.create("http://127.0.0.1:" + server.port() + "/mcp");
            var init = client.send(post(endpoint, null, """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{\
                    "protocolVersion":"2025-06-18","capabilities":{},\
                    "clientInfo":{"name":"ttl-test","version":"1.0"}}}"""), HttpResponse.BodyHandlers.ofString());
            String session = init.headers().firstValue("mcp-session-id").orElseThrow();
            client.send(post(endpoint, session, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode roster;
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(12).toNanos();
            int changes = 0;
            do {
                Files.writeString(source, "class Hello { int v = " + changes++ + "; }\n");
                roster = toolPayload(callTool(client, endpoint, session, "list_projects", Map.of()));
                if (roster.path("projects").isEmpty()) break;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            assertTrue(roster.path("projects").isEmpty(), roster.toString());
            assertTrue(workspace.projects().isEmpty());
            assertTrue(Files.exists(source));
            var uiRoster = client.send(HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + server.vizPort() + "/api/projects")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("[]", uiRoster.body());
            assertFalse(callTool(client, endpoint, session, "add_project", Map.of("path", repo.toString()))
                    .path("isError").asBoolean(), "expired roots can be re-onboarded");
        }
    }

    private static JsonNode toolPayload(JsonNode result) throws Exception {
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }

    private static JsonNode rpc(HttpClient client, URI endpoint, String session,
                                String method, Map<String, Object> params) throws Exception {
        String request = JSON.writeValueAsString(Map.of(
                "jsonrpc", "2.0", "id", 42, "method", method, "params", params));
        HttpResponse<java.io.InputStream> response = client.send(post(endpoint, session, request),
                HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, response.statusCode());
        JsonNode json = JSON.readTree(payload(readUntil(response.body(), "\"jsonrpc\"")));
        assertFalse(json.has("error"), json.toString());
        return json.get("result");
    }

    private static HttpRequest post(URI endpoint, String sessionId, String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (sessionId != null) {
            builder.header("mcp-session-id", sessionId);
        }
        return builder.build();
    }

    /** Responses may be SSE-framed or plain JSON — reduce either to the JSON payload(s). */
    private static String payload(String body) {
        if (!body.contains("data:")) {
            return body;
        }
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).strip()).append('\n');
            }
        }
        return data.toString();
    }

    /** Reads lines until the marker shows up or the stream ends, then closes the stream. */
    private static String readUntil(java.io.InputStream stream, String marker) throws Exception {
        StringBuilder seen = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                seen.append(line).append('\n');
                if (seen.indexOf(marker) >= 0) {
                    break;
                }
            }
        }
        return seen.toString();
    }
}
