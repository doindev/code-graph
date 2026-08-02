package io.doindev.codegraph.query;

import java.util.List;

/**
 * Result of a transitive-closure traversal. {@code hits} are in BFS order (depth 1 first) and
 * exclude the start node; {@code truncated} is set when the node limit stopped the traversal
 * early — callers must surface truncation, never present a cut-off closure as complete.
 */
public record ClosureResult(List<ClosureHit> hits, boolean truncated) {

    public ClosureResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
