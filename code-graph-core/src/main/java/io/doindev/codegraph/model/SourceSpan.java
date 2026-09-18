package io.doindev.codegraph.model;

/** Location of a node in its source file. Lines and UTF-16 columns are 1-based; end is inclusive. */
public record SourceSpan(String relPath, int startLine, int startCol, int endLine, int endCol) {
    public SourceSpan {
        if (relPath == null || relPath.isBlank()) {
            throw new IllegalArgumentException("relPath must not be blank");
        }
    }
}
