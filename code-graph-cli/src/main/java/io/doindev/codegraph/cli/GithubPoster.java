package io.doindev.codegraph.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub PR comment upsert over the issues API (PR comments are issue comments). Env contract
 * of a GitHub Actions job: {@code GITHUB_TOKEN}, {@code GITHUB_REPOSITORY} ({@code owner/repo})
 * and the PR number from {@code --pr} or {@code GITHUB_REF} ({@code refs/pull/<n>/merge}).
 * Takes the {@link HttpClient} as a constructor argument so tests can point it at a stub server.
 */
public final class GithubPoster implements PrCommentPoster {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PULL_REF = Pattern.compile("refs/pull/(\\d+)/");

    private final HttpClient http;
    private final String apiBase;
    private final String token;
    private final String repository;
    private final int prNumber;

    public GithubPoster(HttpClient http, String apiBase, String token, String repository, int prNumber) {
        this.http = http;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.token = token;
        this.repository = repository;
        this.prNumber = prNumber;
    }

    public static GithubPoster fromEnv(HttpClient http, Map<String, String> env, String prFlag) {
        String token = required(env, "GITHUB_TOKEN");
        String repository = required(env, "GITHUB_REPOSITORY");
        int prNumber = prFlag != null ? Integer.parseInt(prFlag.strip()) : prFromRef(env.get("GITHUB_REF"));
        String apiBase = env.getOrDefault("GITHUB_API_URL", "https://api.github.com");
        return new GithubPoster(http, apiBase, token, repository, prNumber);
    }

    /** PR number from a {@code refs/pull/<n>/merge} ref. */
    static int prFromRef(String ref) {
        if (ref != null) {
            Matcher matcher = PULL_REF.matcher(ref);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        throw new IllegalStateException(
                "cannot determine PR number: pass --pr or set GITHUB_REF to refs/pull/<n>/merge");
    }

    @Override
    public void upsert(String markdown) throws IOException, InterruptedException {
        long existing = findMarkerComment();
        String payload = JSON.writeValueAsString(Map.of("body", markdown));
        HttpRequest request = existing >= 0
                ? builder("/repos/" + repository + "/issues/comments/" + existing)
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(payload)).build()
                : builder("/repos/" + repository + "/issues/" + prNumber + "/comments")
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        expectSuccess(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /** Id of the existing report comment on the PR, or -1 when none carries the marker. */
    long findMarkerComment() throws IOException, InterruptedException {
        HttpRequest request = builder(
                "/repos/" + repository + "/issues/" + prNumber + "/comments?per_page=100")
                .GET().build();
        HttpResponse<String> response = expectSuccess(
                http.send(request, HttpResponse.BodyHandlers.ofString()));
        for (JsonNode comment : JSON.readTree(response.body())) {
            if (comment.path("body").asText("").contains(MARKER)) {
                return comment.path("id").asLong();
            }
        }
        return -1;
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json");
    }

    private static HttpResponse<String> expectSuccess(HttpResponse<String> response) {
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("GitHub API " + response.request().method() + " "
                    + response.request().uri() + " returned " + response.statusCode()
                    + ": " + response.body());
        }
        return response;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is not set");
        }
        return value;
    }
}
