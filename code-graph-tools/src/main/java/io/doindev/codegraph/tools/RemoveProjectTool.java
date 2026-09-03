package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code remove_project} — drops a project's in-memory graph and stops watching it. Source on
 * disk (and any snapshot caches or database mirrors) are untouched; projects can be re-added
 * through {@code add_project} or the admin UI.
 */
final class RemoveProjectTool implements GraphTool {

    private final WorkspaceTools workspace;

    RemoveProjectTool(WorkspaceTools workspace) {
        this.workspace = workspace;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("remove_project",
                "Remove a project from this server: drops its in-memory graph and stops its "
                        + "file watcher. Never touches source on disk.",
                """
                { "type": "object",
                  "properties": {
                    "project": { "type": "string", "description": "Project name (see list_projects)" } },
                  "required": ["project"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String project = ToolSupport.stringArg(args, "project", null);
        if (project == null || project.isBlank()) {
            return ToolResponse.fail("project is required");
        }
        try {
            if (!workspace.removeProject(project)) {
                return ToolResponse.fail("unknown project: " + project
                        + " (known: " + String.join(", ", workspace.projectNames()) + ")");
            }
        } catch (IllegalStateException e) {
            return ToolResponse.fail(e.getMessage());
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("removed", project);
        ArrayNode remaining = out.putArray("remaining");
        workspace.projectNames().forEach(remaining::add);
        return ToolResponse.ok(out.toString());
    }
}
