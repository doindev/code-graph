package io.doindev.codegraph.model;

/**
 * Stable identifier of a graph node. Canonical string forms:
 * <ul>
 *   <li>{@code repo:<name>} — repository root</li>
 *   <li>{@code mod:<path>} — module / top-level directory</li>
 *   <li>{@code file:<relPath>} — source file, repo-relative, '/' separators</li>
 *   <li>{@code <lang>:<relPath>#<qualifiedName>/<arity>[~<hash4>]} — symbol; the {@code ~hash4}
 *       suffix appears only when two same-arity overloads collide</li>
 * </ul>
 * IDs are derived only from content-position facts (never internal counters) so they are stable
 * across re-index runs — drift fingerprints and agent-held references depend on this.
 */
public sealed interface NodeId permits RepoId, ModuleId, FileId, SymbolId {

    /** Canonical string form; {@link #parse(String)} round-trips it. */
    String value();

    static NodeId parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (value.startsWith("repo:")) {
            return new RepoId(value.substring(5));
        }
        if (value.startsWith("mod:")) {
            return new ModuleId(value.substring(4));
        }
        if (value.startsWith("file:")) {
            return new FileId(value.substring(5));
        }
        return SymbolId.parse(value);
    }
}
