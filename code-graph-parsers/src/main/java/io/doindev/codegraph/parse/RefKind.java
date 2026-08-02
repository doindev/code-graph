package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.EdgeKind;

/** Kind of an unresolved reference collected in pass 1, mapped to an {@link EdgeKind} once resolved. */
public enum RefKind {
    CALL(EdgeKind.CALLS),
    TYPE_REF(EdgeKind.REFERENCES),
    EXTENDS(EdgeKind.EXTENDS),
    IMPLEMENTS(EdgeKind.IMPLEMENTS);

    private final EdgeKind edgeKind;

    RefKind(EdgeKind edgeKind) {
        this.edgeKind = edgeKind;
    }

    public EdgeKind edgeKind() {
        return edgeKind;
    }
}
