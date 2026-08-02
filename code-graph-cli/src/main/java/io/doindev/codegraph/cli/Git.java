package io.doindev.codegraph.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git plumbing via {@link ProcessBuilder} — no JGit dependency. Every helper runs {@code git}
 * in the given directory and throws {@link IllegalStateException} carrying git's stderr on a
 * non-zero exit, so CI failures name the actual git problem (bad ref, shallow clone, …).
 */
final class Git {

    record Exec(int exitCode, String stdout, String stderr) {
    }

    private Git() {
    }

    /** Run git and return exit/stdout/stderr without judging the exit code. */
    static Exec exec(Path dir, String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).directory(dir.toFile()).start();
            // drain stderr concurrently so a chatty command can't deadlock the pipe
            StringBuilder stderr = new StringBuilder();
            Thread drainer = Thread.ofVirtual().name("git-stderr").start(() -> {
                try {
                    stderr.append(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                }
            });
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            drainer.join();
            return new Exec(exitCode, stdout, stderr.toString());
        } catch (IOException e) {
            throw new UncheckedIOException("failed to run git " + String.join(" ", args), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running git", e);
        }
    }

    /** Run git and require success. */
    static Exec run(Path dir, String... args) {
        Exec result = exec(dir, args);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited "
                    + result.exitCode() + ": " + result.stderr().strip());
        }
        return result;
    }

    static boolean available() {
        try {
            return exec(Path.of("."), "--version").exitCode() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Added/copied/modified/renamed files between the merge-base of {@code base} and
     * {@code head} — the triple-dot form, matching what the PR actually introduces.
     */
    static List<String> changedFiles(Path root, String base, String head) {
        Exec result = run(root, "diff", "--name-only", "--find-renames", "--diff-filter=ACMR",
                base + "..." + head);
        return result.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    static String mergeBase(Path root, String base, String head) {
        return run(root, "merge-base", base, head).stdout().strip();
    }

    static String revParse(Path root, String ref) {
        return run(root, "rev-parse", ref).stdout().strip();
    }

    /** Detached worktree of {@code sha} under a fresh temp directory; caller must remove it. */
    static Path worktreeAdd(Path root, String sha) throws IOException {
        Path parent = Files.createTempDirectory("code-graph-baseline-");
        Path worktree = parent.resolve("wt");
        run(root, "worktree", "add", "--detach", worktree.toString(), sha);
        return worktree;
    }

    /**
     * Remove a baseline worktree. Windows file locks (antivirus, indexers still mapping
     * grammar libs) can make the first attempt fail — retry once after a short sleep, then
     * degrade to a warning rather than failing the whole CI run.
     */
    static void worktreeRemove(Path root, Path worktree, PrintStream err) {
        try {
            run(root, "worktree", "remove", "--force", worktree.toString());
        } catch (RuntimeException first) {
            try {
                Thread.sleep(750);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                run(root, "worktree", "remove", "--force", worktree.toString());
            } catch (RuntimeException second) {
                err.println("code-graph: warning: could not remove baseline worktree " + worktree
                        + " (" + second.getMessage().strip() + "); delete it manually and run `git worktree prune`");
                return;
            }
        }
        try {
            Files.deleteIfExists(worktree.getParent()); // the temp parent we created
        } catch (IOException ignored) {
        }
    }
}
