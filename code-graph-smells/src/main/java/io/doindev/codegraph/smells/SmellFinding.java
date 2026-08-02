package io.doindev.codegraph.smells;

import java.util.Map;

/**
 * One detector hit. Findings are auditable like the blast score: {@code evidence} carries the
 * measured values against their thresholds, {@code rule} names the exact rule that fired.
 *
 * @param smell    detector id, e.g. {@code god-class}
 * @param targetId symbol/file id the finding is about
 * @param severity {@code info}, {@code warning} or {@code error} (config-overridable per detector)
 * @param evidence measured values, e.g. {@code {"methodCount": "31 >= 25", "loc": "612 >= 500"}}
 * @param rule     human-readable rule statement
 */
public record SmellFinding(String smell, String targetId, String severity,
                           Map<String, String> evidence, String rule) {

    public SmellFinding {
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }
}
