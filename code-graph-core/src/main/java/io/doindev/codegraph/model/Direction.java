package io.doindev.codegraph.model;

/** Traversal direction. {@code OUT} follows edges from → to (dependencies); {@code IN} follows reverse edges (dependents). */
public enum Direction {
    OUT,
    IN,
    BOTH
}
