package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.SourceSpan;
import org.treesitter.TSNode;

import java.nio.charset.StandardCharsets;

/** Byte-offset text and span extraction for one parsed file (tree-sitter offsets are UTF-8 bytes). */
public final class Src {

    private final String relPath;
    private final byte[] utf8;

    public Src(String relPath, String content) {
        this.relPath = relPath;
        this.utf8 = content.getBytes(StandardCharsets.UTF_8);
    }

    public String relPath() {
        return relPath;
    }

    public String text(TSNode node) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        return new String(utf8, start, end - start, StandardCharsets.UTF_8);
    }

    /** 1-based line/column span. */
    public SourceSpan span(TSNode node) {
        return new SourceSpan(relPath,
                node.getStartPoint().getRow() + 1, node.getStartPoint().getColumn() + 1,
                node.getEndPoint().getRow() + 1, node.getEndPoint().getColumn() + 1);
    }

    public int lineCount(TSNode node) {
        return node.getEndPoint().getRow() - node.getStartPoint().getRow() + 1;
    }
}
