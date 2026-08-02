package io.doindev.codegraph.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;

/**
 * Live GitHub upsert — runs only when explicitly pointed at a sacrificial PR:
 * {@code GITHUB_TOKEN}, {@code GITHUB_REPOSITORY} (owner/repo) and
 * {@code CODE_GRAPH_LIVE_PR} (PR number) must all be set. Posts (or edits) the marker comment
 * on that PR; run it twice to watch the upsert edit in place instead of duplicating.
 */
@EnabledIfEnvironmentVariable(named = "GITHUB_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GITHUB_REPOSITORY", matches = ".+/.+")
@EnabledIfEnvironmentVariable(named = "CODE_GRAPH_LIVE_PR", matches = "\\d+")
class GithubPosterLiveTest {

    @Test
    void upsertsTheMarkerCommentOnTheConfiguredPr() throws Exception {
        GithubPoster poster = GithubPoster.fromEnv(HttpClient.newHttpClient(), System.getenv(),
                System.getenv("CODE_GRAPH_LIVE_PR"));
        poster.upsert(CiReport.MARKER + "\nlive upsert smoke test — " + java.time.Instant.now());
    }
}
