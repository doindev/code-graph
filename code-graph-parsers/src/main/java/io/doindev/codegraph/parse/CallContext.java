package io.doindev.codegraph.parse;

import java.util.List;

/** Language-specific evidence attached to a reference, serialized with its owning fragment. */
public record CallContext(String ownerType, ValueHint receiver, List<ValueHint> arguments, boolean constructor) {
    public CallContext { arguments = arguments == null ? List.of() : List.copyOf(arguments); }
}
