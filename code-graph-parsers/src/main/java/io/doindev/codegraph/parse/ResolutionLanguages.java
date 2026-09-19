package io.doindev.codegraph.parse;

import java.util.Objects;

/**
 * Eligibility for ordinary name-based references, not proof of a binding.
 * JSX uses js and TSX uses ts. Other interop requires an explicit adapter,
 * never a globally matching method name. Unknown IDs remain isolated.
 */
public final class ResolutionLanguages {
    private ResolutionLanguages() {}

    public static String family(String language) {
        Objects.requireNonNull(language, "resolution language");
        if (language.isBlank()) throw new IllegalArgumentException("resolution language must not be blank");
        return switch (language) {
            case "js", "ts" -> "shared:js-ts";
            default -> "language:" + language;
        };
    }

    public static boolean compatible(String source, String target) {
        return family(source).equals(family(target));
    }
}
