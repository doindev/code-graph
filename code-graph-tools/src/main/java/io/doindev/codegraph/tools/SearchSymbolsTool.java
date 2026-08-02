package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.query.GraphQuery;

import java.util.List;
import java.util.Set;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code search_symbols} — the entry-point tool: agents call it first to obtain valid symbol
 * IDs. Rows carry only {id, kind, sig, line}; file/lang/name are recoverable from the ID,
 * which roughly halves tokens on full result pages.
 */
final class SearchSymbolsTool implements GraphTool {

    private static final int SCAN_CAP = 10_000;

    private final GraphQuery graph;
    private final CodeGraphConfig config;

    SearchSymbolsTool(GraphQuery graph, CodeGraphConfig config) {
        this.graph = graph;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("search_symbols",
                "Find symbols by name to obtain valid symbol IDs (required by every other tool). "
                        + "Case-insensitive substring match over simple and qualified names.",
                """
                { "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "Name or qualified-name substring, e.g. 'Service.validate'" },
                    "kind":  { "type": "string", "enum": ["function", "class", "file", "variable"] },
                    "lang":  { "type": "string", "description": "Language id, e.g. java, ts, js, py" },
                    "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 20 } },
                  "required": ["query"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String query = ToolSupport.stringArg(args, "query", null);
        if (query == null || query.isBlank()) {
            return ToolResponse.fail("query is required");
        }
        String kind = ToolSupport.stringArg(args, "kind", null);
        String lang = ToolSupport.stringArg(args, "lang", null);
        int limit = ToolSupport.intArg(args, "limit", 20, 1, Math.min(100, config.limits().maxResults()));

        Set<NodeKind> kinds = switch (kind == null ? "" : kind) {
            case "function" -> Set.of(NodeKind.FUNCTION);
            case "class" -> Set.of(NodeKind.TYPE);
            case "file" -> Set.of(NodeKind.FILE);
            case "variable" -> Set.of(NodeKind.VARIABLE);
            case "" -> Set.of(NodeKind.FUNCTION, NodeKind.TYPE, NodeKind.FILE, NodeKind.VARIABLE);
            default -> null;
        };
        if (kinds == null) {
            return ToolResponse.fail("unknown kind: " + kind);
        }

        List<Node> all = graph.findSymbols(query, kinds, lang, SCAN_CAP);
        List<Node> page = all.size() > limit ? all.subList(0, limit) : all;

        ObjectNode out = JSON.createObjectNode();
        ArrayNode symbols = out.putArray("symbols");
        for (Node node : page) {
            ObjectNode row = symbols.addObject();
            row.put("id", node.id().value());
            row.put("kind", node.kind().name().toLowerCase());
            row.put("sig", node.displaySignature());
            if (node.span() != null) {
                row.put("line", node.span().startLine());
            }
        }
        out.put("total", all.size());
        out.put("truncated", all.size() > limit);
        out.put("omitted", Math.max(0, all.size() - limit));
        return ToolSupport.finish(out, config);
    }
}
