package io.doindev.codegraph.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GitLab MR note upsert. Env contract of a GitLab CI job: {@code GITLAB_TOKEN} (sent as
 * {@code PRIVATE-TOKEN}) or the built-in {@code CI_JOB_TOKEN} (sent as {@code JOB-TOKEN}),
 * plus {@code CI_PROJECT_ID} and {@code CI_MERGE_REQUEST_IID} (overridable with {@code --pr}).
 * API base comes from {@code CI_API_V4_URL} (default {@code https://gitlab.com/api/v4}).
 */
public final class GitlabPoster implements PrCommentPoster {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String apiBase;
    private final String tokenHeader;
    private final String token;
    private final String projectId;
    private final String mergeRequestIid;

    public GitlabPoster(HttpClient http, String apiBase, String tokenHeader, String token,
                        String projectId, String mergeRequestIid) {
        this.http = http;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.tokenHeader = tokenHeader;
        this.token = token;
        this.projectId = URLEncoder.encode(projectId, StandardCharsets.UTF_8);
        this.mergeRequestIid = mergeRequestIid;
    }

    public static GitlabPoster fromEnv(HttpClient http, Map<String, String> env, String prFlag) {
        String tokenHeader;
        String token = env.get("GITLAB_TOKEN");
        if (token != null && !token.isBlank()) {
            tokenHeader = "PRIVATE-TOKEN";
        } else {
            token = env.get("CI_JOB_TOKEN");
            if (token == null || token.isBlank()) {
                throw new IllegalStateException("set GITLAB_TOKEN (PRIVATE-TOKEN) or run inside "
                        + "GitLab CI where CI_JOB_TOKEN is provided");
            }
            tokenHeader = "JOB-TOKEN";
        }
        String projectId = required(env, "CI_PROJECT_ID");
        String iid = prFlag != null ? prFlag.strip() : required(env, "CI_MERGE_REQUEST_IID");
        String apiBase = env.getOrDefault("CI_API_V4_URL", "https://gitlab.com/api/v4");
        return new GitlabPoster(http, apiBase, tokenHeader, token, projectId, iid);
    }

    @Override
    public void upsert(String markdown) throws IOException, InterruptedException {
        long existing = findMarkerNote();
        String payload = JSON.writeValueAsString(Map.of("body", markdown));
        String notes = "/projects/" + projectId + "/merge_requests/" + mergeRequestIid + "/notes";
        HttpRequest request = existing >= 0
                ? builder(notes + "/" + existing)
                        .PUT(HttpRequest.BodyPublishers.ofString(payload)).build()
                : builder(notes)
                        .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        expectSuccess(http.send(request, HttpResponse.BodyHandlers.ofString()));
    }

    /** Id of the existing report note on the MR, or -1 when none carries the marker. */
    long findMarkerNote() throws IOException, InterruptedException {
        HttpRequest request = builder("/projects/" + projectId + "/merge_requests/"
                + mergeRequestIid + "/notes?per_page=100").GET().build();
        HttpResponse<String> response = expectSuccess(
                http.send(request, HttpResponse.BodyHandlers.ofString()));
        for (JsonNode note : JSON.readTree(response.body())) {
            if (note.path("body").asText("").contains(MARKER)) {
                return note.path("id").asLong();
            }
        }
        return -1;
    }

    private HttpRequest.Builder builder(String path) {
        return HttpRequest.newBuilder(URI.create(apiBase + path))
                .header(tokenHeader, token)
                .header("Content-Type", "application/json");
    }

    private static HttpResponse<String> expectSuccess(HttpResponse<String> response) {
        if (response.statusCode() >= 300) {
            throw new IllegalStateException("GitLab API " + response.request().method() + " "
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
