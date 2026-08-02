package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.query.IndexStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** {@code list_projects} — the workspace roster: names to pass as the {@code project} parameter. */
final class ListProjectsTool implements GraphTool {

    private final Map<String, GraphQuery> graphs; // live view owned by WorkspaceTools
    private final java.util.function.Supplier<String> defaultProject;

    ListProjectsTool(Map<String, GraphQuery> graphs, java.util.function.Supplier<String> defaultProject) {
        this.graphs = graphs;
        this.defaultProject = defaultProject;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("list_projects",
                "Projects indexed by this server; pass a name as the 'project' parameter of any other tool.",
                "{ \"type\": \"object\", \"properties\": {} }");
    }

    @Override
    public ToolResponse call(JsonNode args) {
        ObjectNode out = JSON.createObjectNode();
        ArrayNode rows = out.putArray("projects");
        Map<String, GraphQuery> snapshot;
        synchronized (graphs) {
            snapshot = new LinkedHashMap<>(graphs);
        }
        String defaultName = defaultProject.get();
        snapshot.forEach((name, graph) -> {
            IndexStatus status = graph.status();
            ObjectNode row = rows.addObject();
            row.put("name", name);
            row.put("default", name.equals(defaultName));
            row.put("state", status.state());
            row.put("files", status.filesIndexed());
            row.put("symbols", status.symbolCount());
            row.put("edges", status.edgeCount());
        });
        return ToolResponse.ok(out.toString());
    }
}
