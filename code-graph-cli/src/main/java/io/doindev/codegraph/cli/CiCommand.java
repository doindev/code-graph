package io.doindev.codegraph.cli;

import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.rules.Drift;
import io.doindev.codegraph.smells.SmellEngine;
import io.doindev.codegraph.smells.SmellFinding;

import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code code-graph ci} — the PR pipeline:
 * <ol>
 *   <li>changed files via {@code git diff base...head} (ACMR only)</li>
 *   <li>baseline graph for the merge-base sha, cached as
 *       {@code .code-graph/snapshots/<sha>.snap}; on a miss the merge-base is indexed in a
 *       detached {@code git worktree} that is always removed afterwards</li>
 *   <li>head index of the working tree; per-changed-file blast scores, drift diff vs the
 *       baseline fingerprints, smells scoped to changed files</li>
 *   <li>markdown/JSON report, optional GitHub/GitLab comment upsert, gating exit code</li>
 * </ol>
 */
public final class CiCommand {

    /** Everything the pipeline needs — parseable from flags, constructible from tests. */
    public record Options(Path root, String base, String head, Path report, String format,
                          boolean githubComment, boolean gitlabComment,
                          List<String> failOn, Integer threshold, String pr) {

        static Options parse(List<String> argv) {
            Args args = Args.parse(argv,
                    Set.of("--base", "--head", "--root", "--report", "--format",
                            "--fail-on", "--threshold", "--pr"),
                    Set.of("--github-comment", "--gitlab-comment"));
            String base = args.require("--base");
            String format = args.value("--format", "md");
            if (!format.equals("md") && !format.equals("json")) {
                throw new Args.UsageException("--format must be md or json, got: " + format);
            }
            Integer threshold = null;
            String rawThreshold = args.value("--threshold");
            if (rawThreshold != null) {
                try {
                    threshold = Integer.parseInt(rawThreshold.strip());
                } catch (NumberFormatException e) {
                    throw new Args.UsageException("--threshold must be an integer, got: " + rawThreshold);
                }
            }
            String rawFailOn = args.value("--fail-on");
            List<String> failOn = rawFailOn == null ? List.of()
                    : Arrays.stream(rawFailOn.split(",")).map(String::strip)
                            .filter(s -> !s.isEmpty()).toList();
            for (String category : failOn) {
                if (!category.equals("blast") && !category.equals("drift")) {
                    throw new Args.UsageException("--fail-on accepts blast,drift — got: " + category);
                }
            }
            String report = args.value("--report");
            return new Options(
                    Path.of(args.value("--root", ".")).toAbsolutePath().normalize(),
                    base, args.value("--head", "HEAD"),
                    report == null ? null : Path.of(report),
                    format, args.has("--github-comment"), args.has("--gitlab-comment"),
                    failOn, threshold, args.value("--pr"));
        }
    }

    private CiCommand() {
    }

    /** Runs the pipeline; returns the process exit code (1 on execution errors). */
    public static int run(Options opts, PrintStream out, PrintStream err) {
        try {
            return pipeline(opts, out, err);
        } catch (Args.UsageException e) {
            throw e;
        } catch (Exception e) {
            err.println("code-graph ci: " + e.getMessage());
            return 1;
        }
    }

    private static int pipeline(Options opts, PrintStream out, PrintStream err) throws Exception {
        Path root = opts.root();

        // 1. refs and changed files
        String mergeBase = Git.mergeBase(root, opts.base(), opts.head());
        String headSha = Git.revParse(root, opts.head());
        List<String> changed = Git.changedFiles(root, opts.base(), opts.head());
        err.println("code-graph ci: " + changed.size() + " changed file(s), merge-base " + mergeBase);

        // 2. baseline graph (cached per merge-base sha)
        Path snapshot = root.resolve(".code-graph").resolve("snapshots").resolve(mergeBase + ".snap");
        InMemoryCodeGraph baseline;
        if (Files.isRegularFile(snapshot)) {
            baseline = Snapshots.read(snapshot);
            err.println("code-graph ci: baseline cache hit (" + snapshot.getFileName() + ")");
        } else {
            baseline = indexBaseline(root, mergeBase, err);
            Snapshots.write(baseline, snapshot);
            err.println("code-graph ci: baseline indexed and cached (" + snapshot.getFileName() + ")");
        }

        // 3. head index (the working tree) + measurements
        Indexing.Indexed head = Indexing.index(root);
        CodeGraphConfig config = withOverrides(head.config(), opts);

        Set<String> baselineFingerprints = Drift.evaluate(baseline, config).violations().stream()
                .map(Drift.Violation::fingerprint)
                .collect(Collectors.toUnmodifiableSet());
        Drift.Diff drift = Drift.diff(Drift.evaluate(head.graph(), config), baselineFingerprints);

        BlastScore blast = new BlastScore(head.graph(), config);
        List<CiReport.FileRow> rows = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String relPath : changed) {
            if (!Files.isRegularFile(root.resolve(relPath))) {
                skipped.add(relPath);
                continue;
            }
            FileId fileId = new FileId(relPath);
            if (head.graph().node(fileId).isEmpty()) {
                skipped.add(relPath);
                continue;
            }
            rows.add(CiReport.FileRow.of(relPath, blast.compute(fileId)));
        }
        rows.sort(Comparator.comparingInt(CiReport.FileRow::score).reversed()
                .thenComparing(CiReport.FileRow::path));

        Set<String> changedSet = Set.copyOf(changed);
        List<SmellFinding> smells = new SmellEngine(config).findAll(head.graph(), null, null).stream()
                .filter(finding -> changedSet.contains(pathOf(finding.targetId())))
                .toList();

        // 4. report + gates
        List<String> failOn = opts.failOn().isEmpty() ? config.gating().failCiOn() : opts.failOn();
        CiReport report = new CiReport(opts.base(), opts.head(), mergeBase, headSha,
                config.gating().threshold(), failOn, rows, skipped, drift, smells);

        String rendered = "json".equals(opts.format()) ? report.toJson() : report.toMarkdown();
        if (opts.report() != null) {
            Path file = opts.report().toAbsolutePath().normalize();
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, rendered);
            out.println("report written to " + file);
        } else {
            out.println(rendered);
        }

        // 5. optional PR comment upsert (always markdown — the marker drives the upsert)
        if (opts.githubComment()) {
            GithubPoster.fromEnv(HttpClient.newHttpClient(), System.getenv(), opts.pr())
                    .upsert(report.toMarkdown());
            err.println("code-graph ci: GitHub PR comment upserted");
        }
        if (opts.gitlabComment()) {
            GitlabPoster.fromEnv(HttpClient.newHttpClient(), System.getenv(), opts.pr())
                    .upsert(report.toMarkdown());
            err.println("code-graph ci: GitLab MR note upserted");
        }

        out.println("verdict: " + report.verdict());
        return report.exitCode();
    }

    /** Index the merge-base in a detached worktree; the worktree is removed in all cases. */
    private static InMemoryCodeGraph indexBaseline(Path root, String sha, PrintStream err) throws IOException {
        Path worktree = Git.worktreeAdd(root, sha);
        try {
            return Indexing.index(worktree).graph();
        } finally {
            Git.worktreeRemove(root, worktree, err);
        }
    }

    private static CodeGraphConfig withOverrides(CodeGraphConfig config, Options opts) {
        if (opts.threshold() == null) {
            return config;
        }
        CodeGraphConfig.Gating gating = config.gating();
        return new CodeGraphConfig(config.paths(), config.tests(), config.limits(), config.scoring(),
                new CodeGraphConfig.Gating(opts.threshold(), gating.attachRiskReport(), gating.failCiOn()),
                config.deadCode(), config.smells(), config.architecture());
    }

    /** Repo-relative path buried in a target id: {@code java:src/A.java#A.m/1} → {@code src/A.java}. */
    static String pathOf(String targetId) {
        int hash = targetId.indexOf('#');
        String head = hash >= 0 ? targetId.substring(0, hash) : targetId;
        int colon = head.indexOf(':');
        return colon >= 0 ? head.substring(colon + 1) : head;
    }
}
