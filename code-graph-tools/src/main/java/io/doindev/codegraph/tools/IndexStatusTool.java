package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.query.IndexStatus;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** {@code index_status} — freshness and size of the index, so agents can judge staleness. */
final class IndexStatusTool implements GraphTool {

    private final GraphQuery graph;

    IndexStatusTool(GraphQuery graph) {
        this.graph = graph;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("index_status",
                "Index freshness and size: state, file/symbol/edge counts, pending changes.",
                "{ \"type\": \"object\", \"properties\": {} }");
    }

    @Override
    public ToolResponse call(JsonNode args) {
        IndexStatus status = graph.status();
        ObjectNode out = JSON.createObjectNode();
        out.put("state", status.state());
        out.put("generation", status.generation());
        out.put("files", status.filesIndexed());
        out.put("symbols", status.symbolCount());
        out.put("edges", status.edgeCount());
        out.put("dirtyPending", status.dirtyPending());
        if (status.lastIndexedAt() != null) {
            out.put("lastIndexedAt", status.lastIndexedAt().toString());
        }
        ObjectNode langs = out.putObject("langs");
        status.filesPerLang().forEach(langs::put);
        out.put("engineVersion", status.engineVersion());
        return ToolResponse.ok(out.toString());
    }
}
