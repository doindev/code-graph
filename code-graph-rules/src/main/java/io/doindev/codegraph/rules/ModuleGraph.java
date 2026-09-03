package io.doindev.codegraph.rules;

import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.util.Globs;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Projection of the symbol graph onto blueprint modules: each node maps to its first-matching
 * module glob (falling back to the top-level directory when no blueprint is configured), and
 * cross-module CALLS/REFERENCES/IMPORTS/EXTENDS/IMPLEMENTS edges aggregate into module edges
 * with up to {@value #MAX_WITNESSES} witness pairs each.
 */
public final class ModuleGraph {

    static final int MAX_WITNESSES = 3;

    /** Architecture verdicts must not rest on heuristic name-matching — only confident edges project. */
    static final float MIN_CONFIDENCE = 0.8f;

    /** One aggregated cross-module dependency. */
    public record ModuleEdge(String from, String to, int count, List<String> witnesses) {
    }

    private final Map<String, Map<String, ModuleEdgeBuilder>> edges = new TreeMap<>();
    private final Map<String, String> moduleByPath = new HashMap<>();
    private int unassigned;
    private int limit = Integer.MAX_VALUE;
    private int edgePairs;

    private ModuleGraph() {
    }

    public static ModuleGraph of(GraphQuery graph, CodeGraphConfig config) {
        CodeGraphConfig effective = config.withDefaults();
        CodeGraphConfig.Architecture architecture = effective.architecture();
        ModuleGraph result = new ModuleGraph();
        result.limit = graph.materializationLimit();
        graph.scanNodes(Set.of(NodeKind.TYPE, NodeKind.FUNCTION, NodeKind.VARIABLE, NodeKind.FILE), node -> {
            String fromPath = node.relPath();
            if (fromPath == null) {
                return;
            }
            String fromModule = result.moduleOf(fromPath, architecture);
            for (Edge edge : graph.edges(node.id(), Direction.OUT, BlastScore.IMPACT_KINDS)) {
                if (edge.confidence() < MIN_CONFIDENCE) {
                    continue;
                }
                Node target = graph.node(edge.to()).orElse(null);
                if (target == null || target.relPath() == null) {
                    continue;
                }
                String toModule = result.moduleOf(target.relPath(), architecture);
                if (fromModule.equals(toModule)) {
                    continue;
                }
                result.edges.computeIfAbsent(fromModule, k -> new TreeMap<>())
                        .computeIfAbsent(toModule, k -> {
                            if (++result.edgePairs > result.limit) throw new IllegalArgumentException("module projection exceeds hybrid query bound");
                            return new ModuleEdgeBuilder();
                        })
                        .add(node.id().value() + " -> " + edge.to().value());
            }
        });
        return result;
    }

    /** First-match-wins blueprint glob; falls back to the top-level directory. */
    public String moduleOf(String relPath, CodeGraphConfig.Architecture architecture) {
        return moduleByPath.computeIfAbsent(relPath, path -> {
            if (moduleByPath.size() >= limit) throw new IllegalArgumentException("file projection exceeds hybrid query bound; use a scoped query");
            if (architecture != null) {
                for (CodeGraphConfig.ArchModule module : architecture.modules()) {
                    if (Globs.matchesAny(module.paths(), path)) {
                        return module.name();
                    }
                }
                if (!architecture.modules().isEmpty()) {
                    unassigned++;
                }
            }
            return path.contains("/") ? path.substring(0, path.indexOf('/')) : "(root)";
        });
    }

    public List<ModuleEdge> moduleEdges() {
        List<ModuleEdge> result = new java.util.ArrayList<>();
        edges.forEach((from, targets) -> targets.forEach((to, builder) ->
                result.add(new ModuleEdge(from, to, builder.count, List.copyOf(builder.witnesses)))));
        return result;
    }

    /** Adjacency for cycle detection. */
    public Map<String, Set<String>> adjacency() {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        edges.forEach((from, targets) -> adjacency.put(from, Set.copyOf(targets.keySet())));
        return adjacency;
    }

    public int unassignedCount() {
        return unassigned;
    }

    private static final class ModuleEdgeBuilder {
        int count;
        final List<String> witnesses = new java.util.ArrayList<>(MAX_WITNESSES);

        void add(String witness) {
            count++;
            if (witnesses.size() < MAX_WITNESSES) {
                witnesses.add(witness);
            }
        }
    }
}
