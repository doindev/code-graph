package io.doindev.codegraph.model;

/** Identity of a module (blueprint module or top-level directory), repo-relative path with '/' separators. */
public record ModuleId(String path) implements NodeId {
    public ModuleId {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }

    @Override
    public String value() {
        return "mod:" + path;
    }
}
