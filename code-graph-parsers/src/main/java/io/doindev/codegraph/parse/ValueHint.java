package io.doindev.codegraph.parse;

import java.util.List;

/** Bounded syntactic type evidence. No AST, source text or project graph is retained. */
public record ValueHint(String kind, String name, ValueHint receiver, List<ValueHint> arguments) {
    public ValueHint { arguments = arguments == null ? List.of() : List.copyOf(arguments); }
    public static ValueHint of(String kind, String name) { return new ValueHint(kind, name, null, List.of()); }
    public static ValueHint unknown() { return of("unknown", ""); }
}
