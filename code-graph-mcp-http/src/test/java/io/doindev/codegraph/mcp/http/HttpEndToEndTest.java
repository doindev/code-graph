package io.doindev.codegraph.mcp.http;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exit gate for this module: boots the real stack (index a mini repo, Jetty, MCP streamable
 * HTTP) on an ephemeral port and drives the protocol with a plain {@link HttpClient} —
 * initialize, initialized notification, tools/list.
 */
class HttpEndToEndTest {

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
