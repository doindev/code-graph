package io.doindev.codegraph.model;

/**
 * Kind of a graph edge. {@code CFG_NEXT}, {@code DATA_FLOW} and {@code TAINT} are reserved for
 * later control/data-flow analysis and are not emitted in v1; persistence stores enum names,
 * not ordinals, so adding kinds stays format-compatible.
 */
public enum EdgeKind {
    CONTAINS,
    IMPORTS,
    CALLS,
    REFERENCES,
    EXTENDS,
    IMPLEMENTS,
    /** Verified or explicitly qualified method-level override evidence; never a call site. */
    OVERRIDES,
    READS,
    WRITES,
    /** Cross-language linker output (e.g. HTTP route literal in a TS client matching a Python route). */
    INVOKES_REMOTE,
    CFG_NEXT,
    DATA_FLOW,
    TAINT
}
