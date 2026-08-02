package io.doindev.codegraph.model;

/** Identity of a source file, repo-relative path with '/' separators. */
public record FileId(String relPath) implements NodeId {
    public FileId {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("relPath must not be blank");
        }
        if (relPath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relPath must use '/' separators: " + relPath);
        }
    }

    @Override
    public String value() {
        return "file:" + relPath;
    }
}
