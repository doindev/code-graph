package io.doindev.codegraph.smells;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.util.Globs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Runs the enabled smell detectors. Per-detector tuning comes from
 * {@code code-graph.json → smells.<id>}: {@code enabled} (default true), {@code severity}
 * override, and named numeric {@code thresholds}.
 */
public final class SmellEngine {

    /** One detector; implementations read thresholds through {@link #threshold}. */
    public interface Detector {
        String id();

        String defaultSeverity();

        List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine);
    }

    private final CodeGraphConfig config;
    private final List<Detector> detectors;

    /** Graph-only detectors (Wave A + the graph-based Wave B trio). */
    public SmellEngine(CodeGraphConfig config) {
        this(config, null);
    }

    /**
     * All detectors. With a non-null {@code repoRoot} the history-based detectors
     * (temporal coupling via git, duplicated logic via file contents) run too.
     */
    public SmellEngine(CodeGraphConfig config, java.nio.file.Path repoRoot) {
        this.config = config.withDefaults();
        List<Detector> all = new ArrayList<>(List.of(
                new MetricSmells.GodClass(),
                new MetricSmells.LongMethod(),
                new MetricSmells.LongParameterList(),
                new MetricSmells.LargeFile(),
                new GraphSmells.HighFanOut(),
                new GraphSmells.Hub(),
                new GraphSmells.CyclicFiles(),
                new GraphSmells.UnstableDependency(),
                new AdvancedSmells.FeatureEnvy(),
                new AdvancedSmells.DataClumps(),
                new AdvancedSmells.RefusedBequest()));
        if (repoRoot != null) {
            all.add(new HistorySmells.TemporalCoupling(repoRoot));
            all.add(new HistorySmells.Clones(repoRoot));
            all.add(new EmbeddedSqlSmells(repoRoot));
        }
        this.detectors = List.copyOf(all);
    }

    public List<SmellFinding> findAll(GraphQuery graph, String scopeGlob, String smellFilter) {
        List<SmellFinding> findings = new ArrayList<>();
        for (Detector detector : detectors) {
            if (smellFilter != null && !smellFilter.equals(detector.id())) {
                continue;
            }
            CodeGraphConfig.SmellRule rule = config.smells().get(detector.id());
            if (rule != null && !rule.enabled()) {
                continue;
            }
            findings.addAll(detector.detect(graph, config, this));
        }
        if (scopeGlob != null) {
            findings = findings.stream()
                    .filter(f -> Globs.matches(scopeGlob, pathOf(f.targetId())))
                    .toList();
        }
        List<SmellFinding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator
                .comparingInt((SmellFinding f) -> switch (f.severity()) {
                    case "error" -> 0;
                    case "warning" -> 1;
                    default -> 2;
                })
                .thenComparing(SmellFinding::smell)
                .thenComparing(SmellFinding::targetId));
        return sorted;
    }

    /** Config threshold for a detector, falling back to the built-in default. */
    public double threshold(String detectorId, String key, double fallback) {
        CodeGraphConfig.SmellRule rule = config.smells().get(detectorId);
        if (rule == null) {
            return fallback;
        }
        return rule.thresholds().getOrDefault(key, fallback);
    }

    /** Effective severity for a detector. */
    public String severity(Detector detector) {
        CodeGraphConfig.SmellRule rule = config.smells().get(detector.id());
        return rule == null ? detector.defaultSeverity() : rule.severity();
    }

    static String evidence(String value, double threshold) {
        return value + " >= " + trim(threshold);
    }

    private static String trim(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static String pathOf(String targetId) {
        int hash = targetId.indexOf('#');
        String head = hash >= 0 ? targetId.substring(0, hash) : targetId;
        int colon = head.indexOf(':');
        return colon >= 0 ? head.substring(colon + 1) : head;
    }

    static Map<String, String> ev(Object... kv) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        }
        return map;
    }
}
