package io.doindev.codegraph.model;

/**
 * Identity of a declared symbol (type, function, variable).
 * String form {@code <lang>:<relPath>#<qualifiedName>/<arity>[~<hash4>]}.
 *
 * <p>{@code arity} is the declared parameter count for functions, {@code 0} for types and
 * variables. {@code collisionHash} is empty except when two same-arity overloads collide, in
 * which case it carries 4 hex chars of SHA-256 over the normalized parameter types.
 */
public record SymbolId(String lang, String relPath, String qualifiedName, int arity, String collisionHash)
        implements NodeId {

    public SymbolId {
        if (lang == null || lang.isBlank()) {
            throw new IllegalArgumentException("lang must not be blank");
        }
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("relPath must not be blank");
        }
        if (relPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relPath must use '/' separators: " + relPath);
        }
        if (qualifiedName == null || qualifiedName.isBlank()) {
            throw new IllegalArgumentException("qualifiedName must not be blank");
        }
        if (arity < 0) {
            throw new IllegalArgumentException("arity must be >= 0");
        }
        if (collisionHash == null) {
            collisionHash = "";
        }
    }

    public SymbolId(String lang, String relPath, String qualifiedName, int arity) {
        this(lang, relPath, qualifiedName, arity, "");
    }

    @Override
    public String value() {
        String base = lang + ":" + relPath + "#" + qualifiedName + "/" + arity;
        return collisionHash.isEmpty() ? base : base + "~" + collisionHash;
    }

    static SymbolId parse(String value) {
        int hash = value.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("not a symbol id (missing '#'): " + value);
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon > hash) {
            throw new IllegalArgumentException("not a symbol id (missing '<lang>:'): " + value);
        }
        String lang = value.substring(0, colon);
        String relPath = value.substring(colon + 1, hash);
        String rest = value.substring(hash + 1);
        int slash = rest.lastIndexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("not a symbol id (missing '/<arity>'): " + value);
        }
        String qualifiedName = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        String collisionHash = "";
        int tilde = tail.indexOf('~');
        if (tilde >= 0) {
            collisionHash = tail.substring(tilde + 1);
            tail = tail.substring(0, tilde);
        }
        int arity;
        try {
            arity = Integer.parseInt(tail);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a symbol id (bad arity): " + value, e);
        }
        return new SymbolId(lang, relPath, qualifiedName, arity, collisionHash);
    }
}
