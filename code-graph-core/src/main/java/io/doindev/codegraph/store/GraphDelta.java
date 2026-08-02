package io.doindev.codegraph.store;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;

import java.util.List;

/**
 * One atomic, ordered graph mutation — the unit the single-writer indexer produces and every
 * {@link GraphStore} consumes. Removal is file-scoped: {@code removedFiles} drops every node and
 * edge owned by those files before {@code addNodes}/{@code addEdges} are applied, which is what
 * makes incremental re-index patches atomic.
 *
 * @param generation  strictly increasing sequence number; stores apply deltas in order
 * @param removedFiles files whose owned nodes/edges are dropped first
 * @param addNodes     nodes to add (or replace by id)
 * @param addEdges     edges to add
 * @param removeEdges  individual edges to remove (exact from/to/kind match)
 */
public record GraphDelta(long generation, List<FileId> removedFiles,
                         List<Node> addNodes, List<Edge> addEdges, List<Edge> removeEdges) {

    public GraphDelta {
        removedFiles = removedFiles == null ? List.of() : List.copyOf(removedFiles);
        addNodes = addNodes == null ? List.of() : List.copyOf(addNodes);
        addEdges = addEdges == null ? List.of() : List.copyOf(addEdges);
        removeEdges = removeEdges == null ? List.of() : List.copyOf(removeEdges);
    }
}
