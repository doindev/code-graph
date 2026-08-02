package io.doindev.codegraph.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.analysis.BlastFormula;
import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.rules.Drift;
import io.doindev.codegraph.smells.SmellFinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CI report — one immutable value carrying everything the pipeline measured, rendered as
 * markdown (leading with {@link #MARKER} so PR-comment upserts can find their own comment) or
 * as JSON. Gate verdicts and exit codes are computed here so they are unit-testable without
 * git or an index.
 *
 * <p>Exit codes: 0 pass · 1 execution error (raised elsewhere) · 2 blast gate failed ·
 * 3 drift gate failed · 4 both. Gates only fire for categories listed in {@code failOn}.
 */
public record CiReport(String base, String head, String baseSha, String headSha,
                       int threshold, List<String> failOn,
                       List<FileRow> rows, List<String> skipped,
                       Drift.Diff drift, List<SmellFinding> smells) {

    /** First line of every posted comment; the upsert looks for it. */
    public static final String MARKER = "<!-- code-graph-report -->";

    private static final ObjectMapper JSON = new ObjectMapper();

    public CiReport {
        failOn = List.copyOf(failOn);
        rows = List.copyOf(rows);
        skipped = List.copyOf(skipped);
        smells = List.copyOf(smells);
    }

    /** One changed file's blast measurement. */
    public record FileRow(String path, int score, String band, long dependents, boolean untested,
                          List<BlastFormula.Factor> factors, List<String> topDependents,
                          String explanation) {

        public FileRow {
            factors = List.copyOf(factors);
            topDependents = List.copyOf(topDependents);
        }

        public static FileRow of(String path, BlastScore.Scored scored) {
            return new FileRow(path, scored.score(), scored.band(),
                    (long) factorRaw(scored.factors(), "reach"),
                    factorRaw(scored.factors(), "untested") > 0,
                    scored.factors(), scored.topDependents(), scored.explanation());
        }

        private static double factorRaw(List<BlastFormula.Factor> factors, String name) {
            return factors.stream().filter(f -> f.name().equals(name))
                    .mapToDouble(BlastFormula.Factor::raw).findFirst().orElse(0);
        }
    }

    // ---- gating ----

    public boolean blastFailed() {
        return failOn.contains("blast") && rows.stream().anyMatch(row -> row.score() >= threshold);
    }

    public boolean driftFailed() {
        return failOn.contains("drift") && !drift.newViolations().isEmpty();
    }

    public int exitCode() {
        return exitCode(blastFailed(), driftFailed());
    }

    public static int exitCode(boolean blastFailed, boolean driftFailed) {
        if (blastFailed && driftFailed) {
            return 4;
        }
        if (blastFailed) {
            return 2;
        }
        if (driftFailed) {
            return 3;
        }
        return 0;
    }

    public String verdict() {
        if (!blastFailed() && !driftFailed()) {
            return "PASS";
        }
        List<String> failed = new ArrayList<>(2);
        if (blastFailed()) {
            failed.add("blast gate (threshold " + threshold + ")");
        }
        if (driftFailed()) {
            failed.add("drift gate (" + drift.newViolations().size() + " new violation(s))");
        }
        return "FAIL — " + String.join(", ", failed);
    }

    // ---- rendering ----

    public String toMarkdown() {
        StringBuilder md = new StringBuilder(4096);
        md.append(MARKER).append('\n');
        md.append("# code-graph CI report\n\n");
        md.append("**Verdict: ").append(verdict()).append("**\n\n");

        if (rows.isEmpty()) {
            md.append("No indexed source files changed.\n\n");
        } else {
            md.append("| Changed file | Blast | Band | Dependents | Untested |\n");
            md.append("|---|---|---|---|---|\n");
            for (FileRow row : rows) {
                md.append("| `").append(row.path()).append("` | ").append(row.score())
                        .append(" | ").append(row.band())
                        .append(" | ").append(row.dependents())
                        .append(" | ").append(row.untested() ? "yes" : "no").append(" |\n");
            }
            md.append('\n');
        }

        List<FileRow> risky = rows.stream().filter(row -> row.score() >= threshold).toList();
        if (!risky.isEmpty()) {
            md.append("## Mandatory risk review\n\n");
            md.append("Changed files at or above the gating threshold (").append(threshold).append("):\n\n");
            for (FileRow row : risky) {
                md.append("### `").append(row.path()).append("` — ").append(row.score())
                        .append(" (").append(row.band()).append(")\n\n");
                md.append(factorTable(row.factors())).append('\n');
                if (!row.topDependents().isEmpty()) {
                    md.append("Top dependents: `")
                            .append(String.join("`, `", row.topDependents())).append("`\n\n");
                }
                md.append(row.explanation()).append("\n\n");
            }
        }

        md.append("## New architecture violations\n\n");
        if (drift.newViolations().isEmpty()) {
            md.append("None.\n");
        } else {
            for (Drift.Violation violation : drift.newViolations()) {
                md.append("- **").append(violation.type()).append("** ").append(violation.rule())
                        .append(" — `").append(violation.fingerprint()).append("`\n");
                for (String witness : violation.witnesses()) {
                    md.append("  - witness: `").append(witness).append("`\n");
                }
            }
        }
        md.append('\n').append(drift.fixed()).append(" fixed, ")
                .append(drift.preexisting()).append(" preexisting.\n\n");

        md.append("## Code smells in changed files\n\n");
        if (smells.isEmpty()) {
            md.append("None.\n\n");
        } else {
            for (SmellFinding finding : smells) {
                md.append("- **").append(finding.smell()).append("** `").append(finding.targetId())
                        .append("` (").append(finding.severity()).append(") — ").append(finding.rule());
                if (!finding.evidence().isEmpty()) {
                    md.append(" [");
                    md.append(String.join("; ", finding.evidence().entrySet().stream()
                            .map(e -> e.getKey() + ": " + e.getValue()).toList()));
                    md.append(']');
                }
                md.append('\n');
            }
            md.append('\n');
        }

        if (!skipped.isEmpty()) {
            md.append("Skipped changed files (deleted or not indexed): `")
                    .append(String.join("`, `", skipped)).append("`\n\n");
        }

        md.append("---\n");
        md.append("base `").append(baseSha).append("` (merge-base of `").append(base)
                .append("`) → head `").append(headSha).append("` (`").append(head).append("`)\n");
        return md.toString();
    }

    /** Markdown factor table — the same shape the MCP tools return, so numbers stay auditable. */
    public static String factorTable(List<BlastFormula.Factor> factors) {
        StringBuilder md = new StringBuilder();
        md.append("| factor | raw | normalized | weight | points |\n");
        md.append("|---|---|---|---|---|\n");
        for (BlastFormula.Factor factor : factors) {
            md.append("| ").append(factor.name())
                    .append(" | ").append(trim(factor.raw()))
                    .append(" | ").append(factor.normalized())
                    .append(" | ").append(factor.weight())
                    .append(" | ").append(factor.points()).append(" |\n");
        }
        return md.toString();
    }

    public String toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("verdict", verdict());
        json.put("exitCode", exitCode());
        json.put("base", base);
        json.put("head", head);
        json.put("baseSha", baseSha);
        json.put("headSha", headSha);
        json.put("threshold", threshold);
        json.put("failOn", failOn);
        json.put("changedFiles", rows);
        json.put("skipped", skipped);
        json.put("drift", Map.of(
                "newViolations", drift.newViolations(),
                "fixed", drift.fixed(),
                "preexisting", drift.preexisting()));
        json.put("smells", smells);
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize CI report", e);
        }
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
