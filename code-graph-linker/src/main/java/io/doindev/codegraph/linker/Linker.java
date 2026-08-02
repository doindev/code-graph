package io.doindev.codegraph.linker;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.query.GraphQuery;

import java.nio.file.Path;
import java.util.List;

/**
 * Cross-language linker SPI: produces heuristic edges (confidence &lt; 1) that no single-language
 * analyzer can see — e.g. an HTTP route literal in a TypeScript client matching a Python route
 * declaration. Implementations are discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link #link} is a pure read: it never mutates the graph, only returns candidate edges
 *       for the caller to apply.</li>
 *   <li>Returned edges carry {@code confidence < 1.0} and {@code attrs["resolution"]="heuristic"}
 *       so consumers can filter — never trust silently.</li>
 *   <li>A linker never emits an edge from a node to itself.</li>
 * </ul>
 */
public interface Linker {

    /** Stable identifier, e.g. {@code "http-routes"} — used for config enable/disable and logging. */
    String id();

    /**
     * Scan the repository at {@code repoRoot} (and optionally consult {@code graph} for existing
     * nodes) and return cross-language candidate edges. Must tolerate {@code graph == null}:
     * linkers that scan source text directly do not require an indexed graph.
     */
    List<Edge> link(Path repoRoot, GraphQuery graph);
}
