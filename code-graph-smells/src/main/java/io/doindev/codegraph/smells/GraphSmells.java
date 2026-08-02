package io.doindev.codegraph.smells;

import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.analysis.Tarjan;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.rules.ModuleGraph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detectors over graph shape: fan-out, hubs, file cycles, unstable dependencies. */
final class GraphSmells {

    private GraphSmells() {
    }

    /** High fan-out: a function calling too many distinct callees knows too much. */
    static final class HighFanOut implements SmellEngine.Detector {
        @Override
        public String id() {
            return "high-fan-out";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double fanOut = engine.threshold(id(), "fanOut", 25);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FUNCTION))) {
                long distinct = graph.edges(node.id(), Direction.OUT, Set.of(EdgeKind.CALLS)).stream()
                        .map(Edge::to).distinct().count();
                if (distinct < fanOut) {
                    continue;
                }
                findings.add(new SmellFinding(id(), node.id().value(), engine.severity(this),
                        SmellEngine.ev("distinctCallees", SmellEngine.evidence(String.valueOf(distinct), fanOut)),
                        "calling >= " + (long) fanOut + " distinct functions couples this code to too much of the system"));
            }
            return findings;
        }
    }

    /** Hub / bottleneck: high fan-in AND high fan-out — every change path goes through here. */
    static final class Hub implements SmellEngine.Detector {
        @Override
        public String id() {
            return "hub";
        }

        @Override
        public String defaultSeverity() {
            return "warning";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double minEach = engine.threshold(id(), "minFanEach", 10);
            double product = engine.threshold(id(), "fanProduct", 400);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FUNCTION, NodeKind.TYPE))) {
                int fanIn = graph.edges(node.id(), Direction.IN, BlastScore.IMPACT_KINDS).size();
                int fanOut = graph.edges(node.id(), Direction.OUT, BlastScore.IMPACT_KINDS).size();
                if (fanIn < minEach || fanOut < minEach || (long) fanIn * fanOut < product) {
                    continue;
                }
                findings.add(new SmellFinding(id(), node.id().value(), engine.severity(this),
                        SmellEngine.ev("fanIn", String.valueOf(fanIn), "fanOut", String.valueOf(fanOut),
                                "product", SmellEngine.evidence(String.valueOf((long) fanIn * fanOut), product)),
                        "high fan-in x fan-out makes this a change bottleneck: everything depends on it and it depends on everything"));
            }
            return findings;
        }
    }

    /** Cyclic file dependencies within a module (module-level cycles are architecture drift). */
    static final class CyclicFiles implements SmellEngine.Detector {
        @Override
        public String id() {
            return "cyclic-files";
        }

        @Override
        public String defaultSeverity() {
            return "warning";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            Map<String, Set<String>> fileAdjacency = new HashMap<>();
            for (Node node : graph.allNodes(Set.of(NodeKind.FUNCTION, NodeKind.TYPE, NodeKind.VARIABLE))) {
                String fromFile = node.relPath();
                if (fromFile == null) {
                    continue;
                }
                for (Edge edge : graph.edges(node.id(), Direction.OUT, BlastScore.IMPACT_KINDS)) {
                    // heuristic name-matches must not weld unrelated files into one giant SCC
                    if (edge.confidence() < 0.85f) {
                        continue;
                    }
                    if (edge.to() instanceof SymbolId target && !target.relPath().equals(fromFile)) {
                        fileAdjacency.computeIfAbsent(fromFile, k -> new HashSet<>()).add(target.relPath());
                    }
                }
            }
            List<SmellFinding> findings = new ArrayList<>();
            for (List<String> cycle : Tarjan.cycles(fileAdjacency)) {
                findings.add(new SmellFinding(id(), "file:" + cycle.get(0), engine.severity(this),
                        SmellEngine.ev("cycle", String.join(" <-> ", cycle)),
                        "files that depend on each other cannot be changed or tested independently"));
            }
            return findings;
        }
    }

    /**
     * Unstable dependency: a stable module (low instability I = fanOut/(fanIn+fanOut)) depending
     * on a markedly less stable one — churn flows the wrong way.
     */
    static final class UnstableDependency implements SmellEngine.Detector {
        @Override
        public String id() {
            return "unstable-dependency";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double gap = engine.threshold(id(), "instabilityGap", 0.5);
            ModuleGraph modules = ModuleGraph.of(graph, config);
            Map<String, int[]> fan = new HashMap<>(); // [in, out]
            for (ModuleGraph.ModuleEdge edge : modules.moduleEdges()) {
                fan.computeIfAbsent(edge.from(), k -> new int[2])[1] += edge.count();
                fan.computeIfAbsent(edge.to(), k -> new int[2])[0] += edge.count();
            }
            List<SmellFinding> findings = new ArrayList<>();
            for (ModuleGraph.ModuleEdge edge : modules.moduleEdges()) {
                double fromInstability = instability(fan.get(edge.from()));
                double toInstability = instability(fan.get(edge.to()));
                if (toInstability - fromInstability < gap) {
                    continue;
                }
                findings.add(new SmellFinding(id(), "mod:" + edge.from(), engine.severity(this),
                        SmellEngine.ev("dependency", edge.from() + " -> " + edge.to(),
                                "instability", String.format("%.2f -> %.2f (gap >= %.2f)",
                                        fromInstability, toInstability, gap)),
                        "a stable module depending on an unstable one inherits its churn"));
            }
            return findings;
        }

        private static double instability(int[] fanInOut) {
            if (fanInOut == null) {
                return 0;
            }
            int total = fanInOut[0] + fanInOut[1];
            return total == 0 ? 0 : (double) fanInOut[1] / total;
        }
    }
}
