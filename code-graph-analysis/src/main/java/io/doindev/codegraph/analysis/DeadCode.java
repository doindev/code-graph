package io.doindev.codegraph.analysis;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.util.Globs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Static dead-code candidates: symbols with no inbound CALLS/REFERENCES/EXTENDS/IMPLEMENTS/
 * IMPORTS edges. Reflection, DI and serialization references are invisible to a static graph,
 * so every result carries a confidence tier and the response carries explicit caveats — low
 * confidence is never presented as fact.
 *
 * <p><strong>Hierarchy awareness.</strong> A method with no direct caller is often still reached
 * through dynamic dispatch. So for functions the detector inspects the type hierarchy
 * (EXTENDS/IMPLEMENTS): a method that overrides a supertype method which itself has callers is
 * <em>excluded</em> (it is invoked polymorphically); a method that otherwise participates in an
 * override/implements relationship (overrides an uncalled base, or is a base/interface method
 * with implementations) is <em>downgraded</em> to low confidence rather than reported as a firm
 * hit.
 */
public final class DeadCode {

    /** Names that are externally invoked entry points regardless of graph edges. */
    private static final Set<String> ENTRY_NAMES = Set.of("main", "__main__", "__init__", "index", "handler");

    /** Type-hierarchy edge kinds used to reason about dynamic dispatch. */
    private static final Set<EdgeKind> HIERARCHY_KINDS = Set.of(EdgeKind.EXTENDS, EdgeKind.IMPLEMENTS);

    private static final float CONFIDENT = 0.8f;

    public record Candidate(String id, String kind, String confidence, String reason) {
    }

    public static final List<String> CAVEATS = List.of(
            "reflection/DI/serialization references are not modeled",
            "framework entry points may appear unreferenced; tune deadCode.entryPoints",
            "public API of a library is reported low-confidence (external consumers invisible)",
            "override/interface methods reached via dynamic dispatch are excluded or lowered");

    /** Whether a method participates in dynamic dispatch, and how confidently that makes it live. */
    private enum Poly { REACHABLE_OVERRIDE, HIERARCHY, NONE }

    private final GraphQuery graph;
    private final CodeGraphConfig config;

    public DeadCode(GraphQuery graph, CodeGraphConfig config) {
        this.graph = graph;
        this.config = config.withDefaults();
    }

    public List<Candidate> find(String scopeGlob, Set<NodeKind> kinds, int limit) {
        Set<NodeKind> effective = kinds == null || kinds.isEmpty()
                ? Set.of(NodeKind.FUNCTION, NodeKind.TYPE, NodeKind.VARIABLE) : kinds;
        List<Candidate> candidates = new ArrayList<>();
        for (Node node : graph.allNodes(effective)) {
            String relPath = node.relPath();
            if (relPath == null) {
                continue;
            }
            if (scopeGlob != null && !Globs.matches(scopeGlob, relPath)) {
                continue;
            }
            if (Globs.matchesAny(config.deadCode().exclude(), relPath)
                    || Globs.matchesAny(config.tests().globs(), relPath)) {
                continue;
            }
            List<Edge> inbound = graph.edges(node.id(), Direction.IN, BlastScore.IMPACT_KINDS);
            if (inbound.isEmpty()) {
                if (Globs.matchesAny(config.deadCode().entryPoints(), relPath)
                        || ENTRY_NAMES.contains(node.name())) {
                    continue;
                }
                if (node.kind() == NodeKind.VARIABLE) {
                    candidates.add(new Candidate(node.id().value(), kindName(node), "low",
                            "no inbound references; field reads/writes are not yet modeled"));
                } else if (node.kind() == NodeKind.FUNCTION) {
                    Poly poly = polymorphism(node);
                    if (poly == Poly.REACHABLE_OVERRIDE) {
                        continue; // overrides a called supertype method — reached via dynamic dispatch
                    }
                    if (poly == Poly.HIERARCHY) {
                        candidates.add(new Candidate(node.id().value(), kindName(node), "low",
                                "no direct calls, but overrides or is overridden in a type hierarchy; "
                                        + "may be invoked via dynamic dispatch"));
                    } else {
                        candidates.add(new Candidate(node.id().value(), kindName(node), "high",
                                "0 inbound calls/references; not an entry point; not a test"));
                    }
                } else { // TYPE
                    candidates.add(new Candidate(node.id().value(), kindName(node), "high",
                            "0 inbound calls/references; not an entry point; not a test"));
                }
            } else {
                boolean allSameFile = inbound.stream().allMatch(e -> {
                    Node from = graph.node(e.from()).orElse(null);
                    return from != null && relPath.equals(from.relPath());
                });
                if (allSameFile) {
                    candidates.add(new Candidate(node.id().value(), kindName(node), "medium",
                            "referenced only from its own file"));
                }
            }
        }
        candidates.sort(Comparator.comparingInt(c -> switch (c.confidence()) {
            case "high" -> 0;
            case "medium" -> 1;
            default -> 2;
        }));
        return candidates.size() > limit ? List.copyOf(candidates.subList(0, limit)) : candidates;
    }

    /**
     * Classify a caller-less method by its place in the type hierarchy:
     * <ul>
     *   <li>{@code REACHABLE_OVERRIDE} — it overrides a supertype method that has callers, so a
     *       call through the base type dispatches here; treat as live.</li>
     *   <li>{@code HIERARCHY} — it overrides an uncalled base, or it is itself a base/interface
     *       method with implementations; dynamic dispatch is plausible, so lower confidence.</li>
     *   <li>{@code NONE} — no override relationship; a firm candidate.</li>
     * </ul>
     */
    private Poly polymorphism(Node method) {
        if (!(method.id() instanceof SymbolId sym)) {
            return Poly.NONE;
        }
        Node owner = ownerType(method);
        if (owner == null) {
            return Poly.NONE;
        }
        String name = method.name();
        int arity = sym.arity();
        boolean hierarchy = false;

        // any transitive supertype/interface this method may override
        for (Node superType : relatedTypes(owner, Direction.OUT)) {
            Node base = memberMethod(superType, name, arity);
            if (base != null) {
                hierarchy = true;
                if (!graph.edges(base.id(), Direction.IN, BlastScore.IMPACT_KINDS).isEmpty()) {
                    return Poly.REACHABLE_OVERRIDE; // a called base → the override is reached via dispatch
                }
            }
        }
        // any transitive subtype overrides this (i.e. this is a base/interface method with impls)
        for (Node subType : relatedTypes(owner, Direction.IN)) {
            if (memberMethod(subType, name, arity) != null) {
                hierarchy = true;
                break;
            }
        }
        return hierarchy ? Poly.HIERARCHY : Poly.NONE;
    }

    /**
     * Transitive closure of a type's supertypes ({@link Direction#OUT}) or subtypes
     * ({@link Direction#IN}) over confident EXTENDS/IMPLEMENTS edges. Bounded so a pathological
     * hierarchy can't run away.
     */
    private List<Node> relatedTypes(Node start, Direction direction) {
        List<Node> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        seen.add(start.id().value());
        queue.add(start);
        while (!queue.isEmpty() && result.size() < 256) {
            Node current = queue.poll();
            for (Edge edge : graph.edges(current.id(), direction, HIERARCHY_KINDS)) {
                if (edge.confidence() < CONFIDENT) {
                    continue;
                }
                io.doindev.codegraph.model.NodeId nextId =
                        direction == Direction.OUT ? edge.to() : edge.from();
                if (!seen.add(nextId.value())) {
                    continue;
                }
                Node next = graph.node(nextId).orElse(null);
                if (next != null && next.kind() == NodeKind.TYPE) {
                    result.add(next);
                    queue.add(next);
                }
            }
        }
        return result;
    }

    /** The TYPE that directly contains {@code method}, or {@code null}. */
    private Node ownerType(Node method) {
        for (Edge edge : graph.edges(method.id(), Direction.IN, Set.of(EdgeKind.CONTAINS))) {
            Node parent = graph.node(edge.from()).orElse(null);
            if (parent != null && parent.kind() == NodeKind.TYPE) {
                return parent;
            }
        }
        return null;
    }

    /** A method member of {@code type} with the given simple name and arity, or {@code null}. */
    private Node memberMethod(Node type, String name, int arity) {
        if (type == null || type.kind() != NodeKind.TYPE) {
            return null;
        }
        for (Edge edge : graph.edges(type.id(), Direction.OUT, Set.of(EdgeKind.CONTAINS))) {
            Node member = graph.node(edge.to()).orElse(null);
            if (member != null && member.kind() == NodeKind.FUNCTION && member.name().equals(name)
                    && member.id() instanceof SymbolId s && s.arity() == arity) {
                return member;
            }
        }
        return null;
    }

    private static String kindName(Node node) {
        return node.kind().name().toLowerCase();
    }
}
