package io.doindev.codegraph.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline poster tests: the upsert decision (POST new vs PATCH/PUT existing marker comment)
 * is exercised against a local {@link HttpServer} stub — no tokens, no network.
 */
class PosterTest {

    private record Recorded(String method, String path, String body) {
    }

    private HttpServer server;
    private final List<Recorded> recorded = new ArrayList<>();
    private final Map<String, String> responses = new HashMap<>();

    private String apiBase;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            recorded.add(new Recorded(exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(), body));
            byte[] response = responses.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private static final String MD = CiReport.MARKER + "\n# report body";

    // ---- GitHub ----

    @Test
    void githubPostsANewCommentWhenNoMarkerExists() throws Exception {
        responses.put("GET /repos/acme/app/issues/7/comments", "[{\"id\":1,\"body\":\"unrelated\"}]");

        new GithubPoster(HttpClient.newHttpClient(), apiBase, "token", "acme/app", 7).upsert(MD);

        Recorded write = recorded.get(recorded.size() - 1);
        assertEquals("POST", write.method());
        assertEquals("/repos/acme/app/issues/7/comments", write.path());
        assertTrue(write.body().contains("code-graph-report"));
    }

    @Test
    void githubPatchesTheExistingMarkerComment() throws Exception {
        responses.put("GET /repos/acme/app/issues/7/comments",
                "[{\"id\":41,\"body\":\"hi\"},{\"id\":42,\"body\":\"" + CiReport.MARKER + " old\"}]");

        new GithubPoster(HttpClient.newHttpClient(), apiBase, "token", "acme/app", 7).upsert(MD);

        Recorded write = recorded.get(recorded.size() - 1);
        assertEquals("PATCH", write.method());
        assertEquals("/repos/acme/app/issues/comments/42", write.path());
    }

    @Test
    void githubPrNumberComesFromRefOrFlag() {
        assertEquals(123, GithubPoster.prFromRef("refs/pull/123/merge"));
        assertThrows(IllegalStateException.class, () -> GithubPoster.prFromRef("refs/heads/main"));
        assertThrows(IllegalStateException.class, () -> GithubPoster.prFromRef(null));

        GithubPoster fromFlag = GithubPoster.fromEnv(HttpClient.newHttpClient(),
                Map.of("GITHUB_TOKEN", "t", "GITHUB_REPOSITORY", "acme/app",
                        "GITHUB_API_URL", apiBase),
                "9");
        assertTrue(fromFlag instanceof PrCommentPoster);
        assertThrows(IllegalStateException.class, () -> GithubPoster.fromEnv(
                HttpClient.newHttpClient(), Map.of("GITHUB_REPOSITORY", "acme/app"), "9"));
    }

    // ---- GitLab ----

    @Test
    void gitlabPostsANewNoteWhenNoMarkerExists() throws Exception {
        responses.put("GET /projects/123/merge_requests/9/notes", "[]");

        new GitlabPoster(HttpClient.newHttpClient(), apiBase, "PRIVATE-TOKEN", "token",
                "123", "9").upsert(MD);

        Recorded write = recorded.get(recorded.size() - 1);
        assertEquals("POST", write.method());
        assertEquals("/projects/123/merge_requests/9/notes", write.path());
    }

    @Test
    void gitlabPutsTheExistingMarkerNote() throws Exception {
        responses.put("GET /projects/123/merge_requests/9/notes",
                "[{\"id\":55,\"body\":\"" + CiReport.MARKER + " old\"}]");

        new GitlabPoster(HttpClient.newHttpClient(), apiBase, "PRIVATE-TOKEN", "token",
                "123", "9").upsert(MD);

        Recorded write = recorded.get(recorded.size() - 1);
        assertEquals("PUT", write.method());
        assertEquals("/projects/123/merge_requests/9/notes/55", write.path());
    }

    @Test
    void gitlabEnvPrefersPrivateTokenThenJobToken() {
        Map<String, String> privateEnv = Map.of("GITLAB_TOKEN", "t", "CI_PROJECT_ID", "123",
                "CI_MERGE_REQUEST_IID", "9", "CI_API_V4_URL", apiBase);
        GitlabPoster fromPrivate = GitlabPoster.fromEnv(HttpClient.newHttpClient(), privateEnv, null);
        assertTrue(fromPrivate instanceof PrCommentPoster);

        Map<String, String> jobEnv = Map.of("CI_JOB_TOKEN", "j", "CI_PROJECT_ID", "123",
                "CI_MERGE_REQUEST_IID", "9", "CI_API_V4_URL", apiBase);
        GitlabPoster fromJob = GitlabPoster.fromEnv(HttpClient.newHttpClient(), jobEnv, null);
        assertTrue(fromJob instanceof PrCommentPoster);

        assertThrows(IllegalStateException.class, () -> GitlabPoster.fromEnv(
                HttpClient.newHttpClient(), Map.of("CI_PROJECT_ID", "123"), null));
    }
}
