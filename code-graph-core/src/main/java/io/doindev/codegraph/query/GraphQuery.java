package io.doindev.codegraph.query;

import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read API over the code property graph — the frozen contract the tool, analysis, rules and
 * smell layers code against. Implementations must be safe for concurrent reads while a single
 * writer applies deltas; the in-memory engine guarantees sub-5ms reads via generation-swapped
 * immutable state.
 */
public interface GraphQuery {

    /** Compound read scope. Paging backends override this to pin a generation; never mutate inside it. */
    default <T> T read(java.util.function.Supplier<T> query) { return query.get(); }

    /** Upper bound for auxiliary materialized structures; paging implementations may restrict it. */
    default int materializationLimit() { return Integer.MAX_VALUE; }

    /** Streaming scan; visitors must not retain the complete graph. */
    default void scanNodes(Set<NodeKind> kinds, java.util.function.Consumer<Node> visitor) {
        allNodes(kinds).forEach(visitor);
    }

    Optional<Node> node(NodeId id);

    /**
     * Case-insensitive substring search over simple and qualified names.
     *
     * @param pattern substring, e.g. {@code "Service.validate"}
     * @param kinds   restrict to these kinds; empty or {@code null} = all
     * @param lang    restrict to a language code (e.g. {@code "java"}); {@code null} = all
     * @param limit   maximum results (&gt; 0)
     */
    List<Node> findSymbols(String pattern, Set<NodeKind> kinds, String lang, int limit);

    /**
     * Direct edges touching {@code id}. For {@link Direction#IN} the returned edges have
     * {@code to() == id}; for {@link Direction#OUT} they have {@code from() == id}.
     *
     * @param kinds restrict to these kinds; empty or {@code null} = all
     */
    List<Edge> edges(NodeId id, Direction direction, Set<EdgeKind> kinds);

    /**
     * Transitive closure by breadth-first traversal — the blast-radius primitive.
     * {@link Direction#IN} over CALLS/REFERENCES answers "who breaks if this changes".
     *
     * @param maxDepth      maximum BFS depth (&gt;= 1)
     * @param nodeLimit     hard cap on visited nodes; exceeding it sets {@code truncated}
     * @param minConfidence drop edges below this confidence (0 = keep all)
     */
    ClosureResult closure(NodeId start, Direction direction, Set<EdgeKind> kinds,
                          int maxDepth, int nodeLimit, float minConfidence);

    /**
     * Snapshot of all nodes of the given kinds (empty or {@code null} = all) — for whole-graph
     * analyses (dead code, smells, drift). The list is a point-in-time copy; do not assume it
     * reflects later deltas.
     */
    List<Node> allNodes(Set<NodeKind> kinds);

    /** Freshness and size of the index backing this graph. */
    IndexStatus status();
}
