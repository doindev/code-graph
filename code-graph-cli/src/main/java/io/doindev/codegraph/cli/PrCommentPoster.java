package io.doindev.codegraph.cli;

import java.io.IOException;

/**
 * Upserts the CI report as a single PR/MR comment: the poster looks for an existing comment
 * containing {@link #MARKER} and edits it in place, so a busy PR gets one living report
 * instead of a trail of stale ones.
 */
public sealed interface PrCommentPoster permits GithubPoster, GitlabPoster {

    String MARKER = CiReport.MARKER;

    /** Create-or-update the report comment with this markdown body. */
    void upsert(String markdown) throws IOException, InterruptedException;
}
