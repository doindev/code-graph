package io.doindev.codegraph.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline smoke test of the whole {@code ci} pipeline against a throwaway git repo: baseline
 * indexing happens in a real detached worktree, the snapshot cache is written under
 * {@code .code-graph/snapshots}, and the second run hits that cache.
 */
class CiEndToEndTest {

    @TempDir
    Path repo;

    @BeforeAll
    static void requiresGit() {
        assumeTrue(Git.available(), "git not on PATH — skipping end-to-end test");
    }

    @Test
    void ciPipelineProducesAReportForTheChangedFiles() throws IOException {
        String base = CiGitTest.writeAndCommitTwice(repo);
        Path reportFile = repo.resolve("target-report").resolve("code-graph-report.md");

        CiCommand.Options options = new CiCommand.Options(repo, base, "HEAD", reportFile, "md",
                false, false, List.of("blast", "drift"), null, null);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int exit = CiCommand.run(options, new PrintStream(stdout), new PrintStream(stderr));

        assertTrue(exit == 0 || exit == 2 || exit == 3 || exit == 4,
                "gate exit expected, got " + exit + "\n" + stderr);
        assertTrue(Files.isRegularFile(reportFile), "report file must be produced");
        String report = Files.readString(reportFile);
        assertTrue(report.startsWith(CiReport.MARKER + "\n"));
        assertTrue(report.contains("src/App.java"), "changed file must be listed:\n" + report);
        assertTrue(report.contains("src/Util.java"), "added file must be listed:\n" + report);

        // baseline snapshot cached under the merge-base sha
        String mergeBase = Git.mergeBase(repo, base, "HEAD");
        Path snapshot = repo.resolve(".code-graph").resolve("snapshots").resolve(mergeBase + ".snap");
        assertTrue(Files.isRegularFile(snapshot), "baseline snapshot must be cached");

        // second run: same verdict, baseline served from the cache (no worktree this time)
        ByteArrayOutputStream stderr2 = new ByteArrayOutputStream();
        int second = CiCommand.run(options, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(stderr2));
        assertEquals(exit, second);
        assertTrue(stderr2.toString().contains("baseline cache hit"), stderr2.toString());
    }

    @Test
    void jsonFormatWritesParseableJson() throws IOException {
        String base = CiGitTest.writeAndCommitTwice(repo);
        Path reportFile = repo.resolve("report.json");

        CiCommand.Options options = new CiCommand.Options(repo, base, "HEAD", reportFile, "json",
                false, false, List.of(), null, null);
        int exit = CiCommand.run(options, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream()));

        assertTrue(exit == 0 || exit == 2 || exit == 3 || exit == 4);
        String json = Files.readString(reportFile);
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        assertTrue(root.get("changedFiles").isArray());
        assertEquals(base, root.get("baseSha").asText(), "merge-base of a linear history is the base");
    }
}
