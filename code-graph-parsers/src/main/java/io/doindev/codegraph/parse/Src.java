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

    /** 1-based lines and UTF-16 columns; inclusive end, as required by SourceSpan. */
    public SourceSpan span(TSNode node) {
        int start=node.getStartByte(),end=node.getEndByte();
        int startColumn=new String(utf8,start-node.getStartPoint().getColumn(),node.getStartPoint().getColumn(),StandardCharsets.UTF_8).length()+1;
        int endLine=node.getEndPoint().getRow()+1,endStart=end-node.getEndPoint().getColumn();
        if(end>start&&node.getEndPoint().getColumn()==0){
            end--;if(end>start&&utf8[end-1]=='\r')end--;endLine--;
            endStart=end;while(endStart>0&&utf8[endStart-1]!='\n')endStart--;
        }
        int endColumn=new String(utf8,endStart,Math.max(0,end-endStart),StandardCharsets.UTF_8).length();
        return new SourceSpan(relPath,node.getStartPoint().getRow()+1,startColumn,endLine,Math.max(1,endColumn));
    }

    public int lineCount(TSNode node) {
        return node.getEndPoint().getRow() - node.getStartPoint().getRow() + 1;
    }
}
