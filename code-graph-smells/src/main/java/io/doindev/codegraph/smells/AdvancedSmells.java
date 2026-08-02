package io.doindev.codegraph.smells;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.GraphQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wave-B detectors that need only the graph: feature envy, data clumps, refused bequest.
 * All three are approximations over static structure (documented per detector) — findings
 * carry their evidence so reviewers can judge.
 */
final class AdvancedSmells {

    private AdvancedSmells() {
    }

    /** Owning TYPE of a symbol via its CONTAINS parent, or {@code null}. */
    private static NodeId ownerType(GraphQuery graph, NodeId id) {
        for (Edge edge : graph.edges(id, Direction.IN, Set.of(EdgeKind.CONTAINS))) {
            Node parent = graph.node(edge.from()).orElse(null);
            if (parent != null && parent.kind() == NodeKind.TYPE) {
                return parent.id();
            }
        }
        return null;
    }

    /**
     * Feature envy: a method whose calls into one foreign type outnumber calls into its own
     * type. Approximation: only CALLS edges count (field access is not yet modeled), and only
     * confident edges (>= 0.8) vote.
     */
    static final class FeatureEnvy implements SmellEngine.Detector {
        @Override
        public String id() {
            return "feature-envy";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double minForeign = engine.threshold(id(), "minForeignCalls", 3);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node method : graph.allNodes(Set.of(NodeKind.FUNCTION))) {
                NodeId home = ownerType(graph, method.id());
                if (home == null) {
                    continue;
                }
                int ownCalls = 0;
                Map<NodeId, Integer> foreign = new HashMap<>();
                for (Edge call : graph.edges(method.id(), Direction.OUT, Set.of(EdgeKind.CALLS))) {
                    if (call.confidence() < 0.8f) {
                        continue;
                    }
                    NodeId targetOwner = ownerType(graph, call.to());
                    if (targetOwner == null) {
                        continue;
                    }
                    if (targetOwner.equals(home)) {
                        ownCalls++;
                    } else {
                        foreign.merge(targetOwner, 1, Integer::sum);
                    }
                }
                var enviedEntry = foreign.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                if (enviedEntry == null || enviedEntry.getValue() < minForeign
                        || enviedEntry.getValue() <= ownCalls) {
                    continue;
                }
                findings.add(new SmellFinding(id(), method.id().value(), engine.severity(this),
                        SmellEngine.ev("enviedType", enviedEntry.getKey().value(),
                                "foreignCalls", String.valueOf(enviedEntry.getValue()),
                                "ownCalls", String.valueOf(ownCalls)),
                        "a method talking more to another type than its own probably belongs there"));
            }
            return findings;
        }
    }

    /**
     * Data clumps: the same group of >= 3 parameter names recurring across >= 3 functions.
     * Approximation: parameter names are recovered from display signatures (last identifier
     * per comma-separated parameter), so unnamed/positional styles are invisible.
     */
    static final class DataClumps implements SmellEngine.Detector {

        private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

        @Override
        public String id() {
            return "data-clumps";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double minParams = engine.threshold(id(), "minGroupSize", 3);
            double minFunctions = engine.threshold(id(), "minOccurrences", 3);
            Map<String, List<Node>> byClump = new HashMap<>();
            for (Node method : graph.allNodes(Set.of(NodeKind.FUNCTION))) {
                Set<String> names = parameterNames(method.displaySignature());
                if (names.size() < minParams) {
                    continue;
                }
                byClump.computeIfAbsent(String.join(",", names), k -> new ArrayList<>()).add(method);
            }
            List<SmellFinding> findings = new ArrayList<>();
            byClump.forEach((clump, methods) -> {
                // the same clump inside one type is normal (overloads/builders); require spread
                long distinctFiles = methods.stream()
                        .map(m -> m.id() instanceof SymbolId s ? s.relPath() : "")
                        .distinct().count();
                if (methods.size() < minFunctions || distinctFiles < 2) {
                    return;
                }
                List<String> ids = methods.stream().map(m -> m.id().value()).sorted().limit(5).toList();
                findings.add(new SmellFinding(id(), ids.get(0), engine.severity(this),
                        SmellEngine.ev("parameters", clump,
                                "functions", methods.size() + " across " + distinctFiles + " files",
                                "examples", String.join("; ", ids)),
                        "the same parameter group travelling together wants to be a type"));
            });
            return findings;
        }

        static Set<String> parameterNames(String signature) {
            int open = signature.indexOf('(');
            int close = signature.lastIndexOf(')');
            if (open < 0 || close <= open + 1) {
                return Set.of();
            }
            Set<String> names = new TreeSet<>();
            for (String param : signature.substring(open + 1, close).split(",")) {
                String candidate = null;
                Matcher matcher = IDENTIFIER.matcher(param);
                while (matcher.find()) {
                    candidate = matcher.group(); // last identifier = the name in most languages
                }
                if (candidate != null && candidate.length() > 1) {
                    names.add(candidate.toLowerCase(Locale.ROOT));
                }
            }
            return names;
        }
    }

    /**
     * Refused bequest: a subtype using almost nothing of a sizeable parent — the inheritance is
     * probably wrong. Usage = overriding a parent method (same simple name) or calling one.
     */
    static final class RefusedBequest implements SmellEngine.Detector {
        @Override
        public String id() {
            return "refused-bequest";
        }

        @Override
        public String defaultSeverity() {
            return "info";
        }

        @Override
        public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
            double minParentMethods = engine.threshold(id(), "minParentMethods", 5);
            double maxUsageRatio = engine.threshold(id(), "maxUsageRatio", 0.2);
            List<SmellFinding> findings = new ArrayList<>();
            for (Node subtype : graph.allNodes(Set.of(NodeKind.TYPE))) {
                for (Edge extendsEdge : graph.edges(subtype.id(), Direction.OUT, Set.of(EdgeKind.EXTENDS))) {
                    if (extendsEdge.confidence() < 0.8f) {
                        continue;
                    }
                    Node parent = graph.node(extendsEdge.to()).orElse(null);
                    if (parent == null || parent.kind() != NodeKind.TYPE) {
                        continue;
                    }
                    Set<String> parentMethods = memberNames(graph, parent.id());
                    if (parentMethods.size() < minParentMethods) {
                        continue;
                    }
                    Set<NodeId> parentMethodIds = memberIds(graph, parent.id());
                    Set<String> used = new HashSet<>();
                    for (NodeId memberId : memberIds(graph, subtype.id())) {
                        Node member = graph.node(memberId).orElse(null);
                        if (member == null) {
                            continue;
                        }
                        if (parentMethods.contains(member.name())) {
                            used.add(member.name()); // override
                        }
                        for (Edge call : graph.edges(memberId, Direction.OUT, Set.of(EdgeKind.CALLS))) {
                            if (parentMethodIds.contains(call.to())) {
                                Node target = graph.node(call.to()).orElse(null);
                                if (target != null) {
                                    used.add(target.name());
                                }
                            }
                        }
                    }
                    double ratio = (double) used.size() / parentMethods.size();
                    if (ratio >= maxUsageRatio) {
                        continue;
                    }
                    findings.add(new SmellFinding(id(), subtype.id().value(), engine.severity(this),
                            SmellEngine.ev("parent", parent.id().value(),
                                    "parentMethods", String.valueOf(parentMethods.size()),
                                    "usedMethods", used.size() + " (" + String.format(Locale.ROOT, "%.0f%%", ratio * 100) + ")"),
                            "inheriting a large API and using under " + (int) (maxUsageRatio * 100)
                                    + "% of it suggests composition instead of inheritance"));
                }
            }
            return findings;
        }

        private static Set<String> memberNames(GraphQuery graph, NodeId type) {
            Set<String> names = new HashSet<>();
            for (NodeId id : memberIds(graph, type)) {
                graph.node(id).filter(n -> n.kind() == NodeKind.FUNCTION)
                        .ifPresent(n -> names.add(n.name()));
            }
            return names;
        }

        private static Set<NodeId> memberIds(GraphQuery graph, NodeId type) {
            Set<NodeId> ids = new HashSet<>();
            for (Edge edge : graph.edges(type, Direction.OUT, Set.of(EdgeKind.CONTAINS))) {
                ids.add(edge.to());
            }
            return ids;
        }
    }
}
