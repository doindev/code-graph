package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.query.IndexStatus;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** {@code list_projects} — the workspace roster: names to pass as the {@code project} parameter. */
final class ListProjectsTool implements GraphTool {

    private final Map<String, GraphQuery> graphs; // live view owned by WorkspaceTools
    private final java.util.function.Supplier<String> defaultProject;
    private final ProjectLifecycle lifecycle;
    private final java.util.function.Supplier<java.util.List<Map<String,Object>>> onboarding;

    ListProjectsTool(Map<String, GraphQuery> graphs, java.util.function.Supplier<String> defaultProject,
                     ProjectLifecycle lifecycle, java.util.function.Supplier<java.util.List<Map<String,Object>>> onboarding) {
        this.onboarding=onboarding;
        this.lifecycle = lifecycle;
        this.graphs = graphs;
        this.defaultProject = defaultProject;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("list_projects",
                "Ready projects plus a separate onboarding array with initial-scan phase/counts. Only projects are queryable; polling does not renew TTL. Pass a ready name as the 'project' parameter of other tools.",
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
            ProjectLifecycle.Status idle = lifecycle.status(name);
            if (idle == null) return;
            IndexStatus status = graph.status();
            ObjectNode row = rows.addObject();
            row.put("name", name);
            row.put("default", name.equals(defaultName));
            row.put("state", status.state());
            row.put("files", status.filesIndexed());
            row.put("symbols", status.symbolCount());
            row.put("edges", status.edgeCount());
            row.put("instanceId", idle.instanceId());
            row.put("lastActivityAt", idle.lastActivityAt().toString());
            row.put("expiresAt", idle.expiresAt().toString());
            row.put("remainingSeconds", idle.remainingSeconds());
            row.put("activeOperations", idle.activeOperations());
        });
        out.set("onboarding",JSON.valueToTree(onboarding.get()));
        return ToolResponse.ok(out.toString());
    }
}
