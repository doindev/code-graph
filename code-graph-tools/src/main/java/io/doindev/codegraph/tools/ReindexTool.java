package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code reindex} — the only state-mutating tool, and it mutates only the index (never source
 * files). Asynchronous: poll {@code index_status} for completion.
 */
final class ReindexTool implements GraphTool {

    private final CodeGraphTools.Reindexer reindexer;

    ReindexTool(CodeGraphTools.Reindexer reindexer) {
        this.reindexer = reindexer;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("reindex",
                "Trigger re-indexing (async; poll index_status). Optional path narrows the scope.",
                """
                { "type": "object",
                  "properties": {
                    "path": { "type": "string", "description": "Repo-relative path to re-index; omit for full" } } }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String scope = ToolSupport.stringArg(args, "path", null);
        try {
            reindexer.reindex(scope);
        } catch (RuntimeException e) {
            return ToolResponse.fail("reindex failed to start: " + e.getMessage());
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("accepted", true);
        out.put("scope", scope == null ? "full" : scope);
        out.put("note", "async; poll index_status");
        return ToolResponse.ok(out.toString());
    }
}
