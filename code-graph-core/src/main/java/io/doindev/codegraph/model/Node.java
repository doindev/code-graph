package io.doindev.codegraph.model;

import java.util.Map;

/**
 * A node in the code property graph. Carries name, signature, location, metrics and string
 * attributes — <strong>never source text</strong>: anything returning snippets must go through
 * a {@code SnippetPolicy}, which is off by default (local-first, no-exfiltration invariant).
 *
 * @param id               stable identity, see {@link NodeId}
 * @param kind             node kind
 * @param name             simple name (last segment of the qualified name)
 * @param displaySignature human-readable one-line signature, e.g. {@code boolean validateToken(String jwt)}
 * @param span             source location; {@code null} for REPOSITORY/MODULE nodes
 * @param metrics          AST metrics; {@link Metrics#NONE} when not applicable
 * @param attrs            extensible string attributes (e.g. {@code visibility}, {@code doc})
 */
public record Node(NodeId id, NodeKind kind, String name, String displaySignature,
                   SourceSpan span, Metrics metrics, Map<String, String> attrs) {

    public Node {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (displaySignature == null) {
            displaySignature = name;
        }
        if (metrics == null) {
            metrics = Metrics.NONE;
        }
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    /** Language from a symbol's identity or an indexed file's analyzer metadata.
     * Unknown file languages stay unknown; extensions are not a language oracle. */
    public String lang() {
        if (id instanceof SymbolId s) return s.lang();
        return kind == NodeKind.FILE && id instanceof FileId ? attrs.get("lang") : null;
    }

    /** Repo-relative path of the owning file, {@code null} for repository/module nodes. */
    public String relPath() {
        return switch (id) {
            case FileId f -> f.relPath();
            case SymbolId s -> s.relPath();
            default -> span == null ? null : span.relPath();
        };
    }
}
