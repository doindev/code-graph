package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.analysis.DeadCode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.NodeKind;

import java.util.List;
import java.util.Set;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** {@code find_dead_code} — unreferenced symbols with confidence tiers and explicit caveats. */
final class FindDeadCodeTool implements GraphTool {

    private final DeadCode deadCode;
    private final CodeGraphConfig config;

    FindDeadCodeTool(DeadCode deadCode, CodeGraphConfig config) {
        this.deadCode = deadCode;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("find_dead_code",
                "Symbols with no inbound references, with confidence tiers (reflection/DI are "
                        + "invisible to static analysis — read the caveats).",
                """
                { "type": "object",
                  "properties": {
                    "scope": { "type": "string", "description": "Glob narrowing the search, e.g. 'src/main/**'" },
                    "kind":  { "type": "string", "enum": ["function", "class", "variable"] },
                    "limit": { "type": "integer", "minimum": 1, "maximum": 200, "default": 50 } } }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String scope = ToolSupport.stringArg(args, "scope", null);
        String kind = ToolSupport.stringArg(args, "kind", null);
        int limit = ToolSupport.intArg(args, "limit", 50, 1, 200);
        Set<NodeKind> kinds = switch (kind == null ? "" : kind) {
            case "function" -> Set.of(NodeKind.FUNCTION);
            case "class" -> Set.of(NodeKind.TYPE);
            case "variable" -> Set.of(NodeKind.VARIABLE);
            case "" -> Set.of();
            default -> null;
        };
        if (kinds == null) {
            return ToolResponse.fail("unknown kind: " + kind);
        }

        List<DeadCode.Candidate> candidates = deadCode.find(scope, kinds, limit + 1);
        boolean truncated = candidates.size() > limit;
        List<DeadCode.Candidate> page = truncated ? candidates.subList(0, limit) : candidates;

        ObjectNode out = JSON.createObjectNode();
        ArrayNode rows = out.putArray("candidates");
        for (DeadCode.Candidate candidate : page) {
            ObjectNode row = rows.addObject();
            row.put("id", candidate.id());
            row.put("kind", candidate.kind());
            row.put("confidence", candidate.confidence());
            row.put("reason", candidate.reason());
        }
        out.put("returned", page.size());
        out.put("truncated", truncated);
        ArrayNode caveats = out.putArray("caveats");
        DeadCode.CAVEATS.forEach(caveats::add);
        return ToolSupport.finish(out, config);
    }
}
