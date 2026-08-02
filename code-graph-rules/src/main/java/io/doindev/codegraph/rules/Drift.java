package io.doindev.codegraph.rules;

import io.doindev.codegraph.analysis.Tarjan;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Blueprint evaluation: layer/boundary violations (module edge {@code A→B} where {@code B} is
 * not in {@code allowedDependencies[A]}) and module cycles (Tarjan SCC). Violations carry
 * stable fingerprints (no line numbers — pure moves must not churn) so a baseline diff can
 * report only what is NEW.
 */
public final class Drift {

    /** One architecture violation. */
    public record Violation(String type, String rule, String from, String to,
                            List<String> witnesses, String fingerprint) {
    }

    public record Report(List<Violation> violations, int unassignedFiles, String unassignedPolicy) {
    }

    /** Diff vs a baseline: only fingerprints decide identity. */
    public record Diff(List<Violation> newViolations, int fixed, int preexisting) {
    }

    private Drift() {
    }

    public static Report evaluate(GraphQuery graph, CodeGraphConfig config) {
        CodeGraphConfig effective = config.withDefaults();
        CodeGraphConfig.Architecture architecture = effective.architecture();
        ModuleGraph modules = ModuleGraph.of(graph, effective);
        List<Violation> violations = new ArrayList<>();

        if (architecture != null && !architecture.allowedDependencies().isEmpty()) {
            for (ModuleGraph.ModuleEdge edge : modules.moduleEdges()) {
                List<String> allowed = architecture.allowedDependencies().get(edge.from());
                boolean known = architecture.modules().stream()
                        .anyMatch(m -> m.name().equals(edge.from()));
                if (!known) {
                    continue; // fallback (directory) modules are not governed by the blueprint
                }
                if (allowed != null && allowed.contains(edge.to())) {
                    continue;
                }
                String witness = edge.witnesses().isEmpty() ? "" : edge.witnesses().get(0);
                String witnessSymbol = witness.contains(" -> ")
                        ? witness.substring(0, witness.indexOf(" -> ")) : witness;
                violations.add(new Violation("layer",
                        edge.from() + " -/-> " + edge.to(),
                        edge.from(), edge.to(), edge.witnesses(),
                        "layer:" + edge.from() + "->" + edge.to() + ":" + shortName(witnessSymbol)));
            }
        }

        if (architecture == null || architecture.forbidCycles()) {
            for (List<String> cycle : Tarjan.cycles(modules.adjacency())) {
                violations.add(new Violation("cycle",
                        "module cycle: " + String.join(" <-> ", cycle),
                        cycle.get(0), cycle.get(cycle.size() - 1),
                        cycleWitnesses(modules, cycle),
                        "cycle:" + String.join("|", cycle)));
            }
        }
        String policy = architecture == null ? "ignore" : architecture.unassigned();
        return new Report(List.copyOf(violations), modules.unassignedCount(), policy);
    }

    public static Diff diff(Report current, Set<String> baselineFingerprints) {
        List<Violation> fresh = new ArrayList<>();
        int preexisting = 0;
        Set<String> currentFingerprints = new LinkedHashSet<>();
        for (Violation violation : current.violations()) {
            currentFingerprints.add(violation.fingerprint());
            if (baselineFingerprints.contains(violation.fingerprint())) {
                preexisting++;
            } else {
                fresh.add(violation);
            }
        }
        int fixed = (int) baselineFingerprints.stream()
                .filter(f -> !currentFingerprints.contains(f)).count();
        return new Diff(List.copyOf(fresh), fixed, preexisting);
    }

    private static List<String> cycleWitnesses(ModuleGraph modules, List<String> cycle) {
        Set<String> members = Set.copyOf(cycle);
        List<String> witnesses = new ArrayList<>();
        for (ModuleGraph.ModuleEdge edge : modules.moduleEdges()) {
            if (members.contains(edge.from()) && members.contains(edge.to())
                    && !edge.witnesses().isEmpty() && witnesses.size() < 4) {
                witnesses.add(edge.witnesses().get(0));
            }
        }
        return witnesses;
    }

    /** Symbol id → qualified name without path noise, keeping fingerprints move-stable. */
    private static String shortName(String symbolId) {
        int hash = symbolId.indexOf('#');
        return hash >= 0 ? symbolId.substring(hash + 1) : symbolId;
    }
}
