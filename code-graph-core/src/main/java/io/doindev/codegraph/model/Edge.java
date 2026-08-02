package io.doindev.codegraph.model;

import java.util.Map;

/**
 * A directed edge in the code property graph.
 *
 * @param from       source node
 * @param to         target node
 * @param kind       edge kind
 * @param confidence resolution confidence in (0, 1]; 1.0 = resolved exactly (import-driven or
 *                   same-scope), lower values mark heuristic name-based resolution — consumers
 *                   filter by a minimum confidence, never trust silently
 * @param attrs      extensible string attributes (e.g. {@code callSiteLine}, {@code resolution=heuristic})
 */
public record Edge(NodeId from, NodeId to, EdgeKind kind, float confidence, Map<String, String> attrs) {

    public Edge {
        if (from == null || to == null || kind == null) {
            throw new IllegalArgumentException("from, to and kind must not be null");
        }
        if (!(confidence > 0f) || confidence > 1f) {
            throw new IllegalArgumentException("confidence must be in (0, 1]: " + confidence);
        }
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    public Edge(NodeId from, NodeId to, EdgeKind kind) {
        this(from, to, kind, 1.0f, Map.of());
    }
}
