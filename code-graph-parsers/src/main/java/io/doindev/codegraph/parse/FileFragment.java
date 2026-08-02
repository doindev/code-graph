package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;

import java.util.List;

/**
 * Everything pass 1 extracts from one file: declared nodes, local (same-file, exact) edges,
 * and unresolved references for pass 2. Fragments are the unit of graph ownership — every
 * node and edge is attributable to exactly one file, which is what makes incremental
 * re-index patches atomic.
 *
 * @param file         owning file
 * @param lang         language id
 * @param contentHash  SHA-256 hex of the file content (change detection)
 * @param declarations declared nodes, including the FILE node itself
 * @param localEdges   exact edges (CONTAINS, same-file calls resolved in pass 1)
 * @param rawRefs      unresolved references for pass 2
 * @param imports      import strings as written (pass-2 resolution context)
 */
public record FileFragment(FileId file, String lang, String contentHash,
                           List<Node> declarations, List<Edge> localEdges,
                           List<RawRef> rawRefs, List<String> imports) {

    public FileFragment {
        declarations = List.copyOf(declarations);
        localEdges = List.copyOf(localEdges);
        rawRefs = List.copyOf(rawRefs);
        imports = List.copyOf(imports);
    }
}
