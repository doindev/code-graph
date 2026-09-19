package io.doindev.codegraph.parse;

import org.treesitter.TSNode;
import java.util.Map;

/** Per-file, short-lived extraction hooks. The default preserves non-Java analyzers. */
public interface SemanticHints {
    SemanticHints NONE = new SemanticHints() {};
    default Map<String,String> declaration(TSNode node) { return Map.of(); }
    default CallContext call(TSNode node) { return null; }
}
