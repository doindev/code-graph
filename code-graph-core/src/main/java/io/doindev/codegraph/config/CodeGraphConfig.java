package io.doindev.codegraph.config;

import java.util.List;
import java.util.Map;

/**
 * Immutable configuration, normally loaded from a repo-root {@code code-graph.json} (canonical,
 * comments allowed) or {@code code-graph.yaml} by the {@code code-graph-config} module.
 * Precedence: CLI flags &gt; {@code CODE_GRAPH_*} env vars &gt; file &gt; these record defaults.
 * Every section tolerates being absent — {@link #withDefaults()} fills missing sections so the
 * rest of the codebase never null-checks config.
 */
public record CodeGraphConfig(Paths paths, Tests tests, Limits limits, Scoring scoring,
                              Gating gating, DeadCode deadCode, Map<String, SmellRule> smells,
                              Architecture architecture) {

    public CodeGraphConfig {
        smells = smells == null ? Map.of() : Map.copyOf(smells);
    }

    public static CodeGraphConfig defaults() {
        return new CodeGraphConfig(null, null, null, null, null, null, Map.of(), null).withDefaults();
    }

    /** Returns a config with every absent section replaced by its defaults. */
    public CodeGraphConfig withDefaults() {
        return new CodeGraphConfig(
                paths != null ? paths : new Paths(List.of("**"), List.of()),
                tests != null ? tests : new Tests(List.of(
                        "**/src/test/**", "**/*.test.ts", "**/*.test.js", "**/*.spec.ts",
                        "**/test_*.py", "**/*_test.py", "**/*_test.go", "**/*Tests.cs", "**/*Test.java")),
                limits != null ? limits : new Limits(50, 32_768),
                scoring != null ? scoring : Scoring.defaults(),
                gating != null ? gating : new Gating(70, true, List.of("blast", "drift")),
                deadCode != null ? deadCode : new DeadCode(List.of(), List.of()),
                smells,
                architecture);
    }

    /** Files considered part of the indexed source tree, as repo-relative globs. */
    public record Paths(List<String> include, List<String> exclude) {
        public Paths {
            include = include == null ? List.of() : List.copyOf(include);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }
    }

    /** Globs identifying test files — feeds the blast score's {@code untested} factor. */
    public record Tests(List<String> globs) {
        public Tests {
            globs = globs == null ? List.of() : List.copyOf(globs);
        }
    }

    /** Token-frugality caps enforced on every tool response. */
    public record Limits(int maxResults, int maxResponseBytes) {
        public Limits {
            if (maxResults < 1 || maxResponseBytes < 1024) {
                throw new IllegalArgumentException("maxResults >= 1 and maxResponseBytes >= 1024 required");
            }
        }
    }

    /**
     * Blast-score weights and normalization reference constants. Weights should sum to 1;
     * {@code reachRef}/{@code faninRef} are the counts at which those log-damped factors saturate.
     */
    public record Scoring(Map<String, Double> weights, int reachRef, int faninRef) {
        public Scoring {
            weights = weights == null ? Map.of() : Map.copyOf(weights);
        }

        public static Scoring defaults() {
            return new Scoring(Map.of(
                    "reach", 0.40, "fanin", 0.25, "spread", 0.20, "lang", 0.05, "untested", 0.10),
                    1000, 100);
        }
    }

    /** Risk gating: score at/above {@code threshold} attaches a mandatory risk report and fails CI. */
    public record Gating(int threshold, boolean attachRiskReport, List<String> failCiOn) {
        public Gating {
            if (threshold < 0 || threshold > 100) {
                throw new IllegalArgumentException("threshold must be 0..100");
            }
            failCiOn = failCiOn == null ? List.of() : List.copyOf(failCiOn);
        }
    }

    /** Dead-code tuning: entry points are never reported; excluded globs are skipped entirely. */
    public record DeadCode(List<String> entryPoints, List<String> exclude) {
        public DeadCode {
            entryPoints = entryPoints == null ? List.of() : List.copyOf(entryPoints);
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }
    }

    /** One smell detector's tuning: enabled flag, reported severity, and named numeric thresholds. */
    public record SmellRule(boolean enabled, String severity, Map<String, Double> thresholds) {
        public SmellRule {
            severity = severity == null ? "warning" : severity;
            thresholds = thresholds == null ? Map.of() : Map.copyOf(thresholds);
        }
    }

    /**
     * Golden-blueprint architecture rules (ArchUnit-lite): named modules as first-match-wins
     * path globs, an allowed-dependency map ({@code A -> [B, C]}; a module absent from the map
     * may depend on nothing), cycle prohibition, and handling of files matching no module.
     */
    public record Architecture(List<ArchModule> modules, Map<String, List<String>> allowedDependencies,
                               boolean forbidCycles, String unassigned) {
        public Architecture {
            modules = modules == null ? List.of() : List.copyOf(modules);
            allowedDependencies = allowedDependencies == null ? Map.of() : Map.copyOf(allowedDependencies);
            unassigned = unassigned == null ? "warn" : unassigned;
        }
    }

    /** One blueprint module: a name and the path globs that define it. */
    public record ArchModule(String name, List<String> paths) {
        public ArchModule {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("module name must not be blank");
            }
            paths = paths == null ? List.of() : List.copyOf(paths);
        }
    }
}
