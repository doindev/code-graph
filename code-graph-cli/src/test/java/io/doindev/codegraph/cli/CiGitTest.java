package io.doindev.codegraph.cli;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exercises the git helpers against a real throwaway repository. */
class CiGitTest {

    @TempDir
    Path repo;

    @BeforeAll
    static void requiresGit() {
        assumeTrue(Git.available(), "git not on PATH — skipping git-backed tests");
    }

    static void commit(Path repo, String message) {
        Git.run(repo, "-c", "user.email=ci@test.local", "-c", "user.name=code-graph-ci",
                "-c", "commit.gpgsign=false", "commit", "-m", message);
    }

    static String writeAndCommitTwice(Path repo) throws IOException {
        Git.run(repo, "init");
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/App.java"),
                "public class App { void run() { System.out.println(\"v1\"); } }\n");
        Git.run(repo, "add", ".");
        commit(repo, "one");
        String first = Git.revParse(repo, "HEAD");

        Files.writeString(repo.resolve("src/App.java"),
                "public class App { void run() { System.out.println(\"v2\"); } void extra() { } }\n");
        Files.writeString(repo.resolve("src/Util.java"),
                "public class Util { static int add(int a, int b) { return a + b; } }\n");
        Git.run(repo, "add", ".");
        commit(repo, "two");
        return first;
    }

    @Test
    void changedFilesAndMergeBaseAgainstARealRepo() throws IOException {
        String first = writeAndCommitTwice(repo);

        List<String> changed = Git.changedFiles(repo, first, "HEAD");
        assertEquals(List.of("src/App.java", "src/Util.java"), changed.stream().sorted().toList());

        assertEquals(first, Git.mergeBase(repo, first, "HEAD"));
        assertEquals(40, Git.revParse(repo, "HEAD").length(), "rev-parse must yield a full sha");
    }

    @Test
    void deletedFilesAreExcludedByTheDiffFilter() throws IOException {
        String first = writeAndCommitTwice(repo);
        Git.run(repo, "rm", "src/App.java");
        commit(repo, "three");

        List<String> changed = Git.changedFiles(repo, first, "HEAD");
        assertEquals(List.of("src/Util.java"), changed);
        assertFalse(changed.contains("src/App.java"));
    }

    @Test
    void worktreeAddAndRemoveRoundTrip() throws IOException {
        String first = writeAndCommitTwice(repo);

        Path worktree = Git.worktreeAdd(repo, first);
        try {
            assertTrue(Files.isRegularFile(worktree.resolve("src/App.java")));
            String content = Files.readString(worktree.resolve("src/App.java"));
            assertTrue(content.contains("v1"), "worktree must hold the baseline revision");
            assertFalse(Files.exists(worktree.resolve("src/Util.java")));
        } finally {
            Git.worktreeRemove(repo, worktree, System.err);
        }
        assertFalse(Files.exists(worktree), "worktree directory should be gone after removal");
    }
}
