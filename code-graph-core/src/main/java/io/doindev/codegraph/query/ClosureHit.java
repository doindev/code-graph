package io.doindev.codegraph.query;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.Node;

/** One node reached by a closure traversal, with its BFS depth and the edge it was first reached through. */
public record ClosureHit(Node node, int depth, Edge via) {
}
