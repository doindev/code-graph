package io.doindev.codegraph.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;

import java.util.List;
import java.util.Map;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * Multi-project routing: wraps one tool instance per project and dispatches on the optional
 * {@code project} argument (default: the workspace's first project). The wrapped tool's input
 * schema is republished with the {@code project} property injected, so agents discover the
 * parameter without any per-tool code.
 */
final class RouterTool implements GraphTool {

    private final Map<String, GraphTool> byProject; // live view owned by WorkspaceTools
    private final java.util.function.Supplier<String> defaultProject;
    private final ToolSpec spec;
    private final ProjectLifecycle lifecycle;

    RouterTool(Map<String, GraphTool> byProject, java.util.function.Supplier<String> defaultProject,
               ToolSpec delegateSpec, ProjectLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.byProject = byProject;
        this.defaultProject = defaultProject;
        this.spec = injectProjectParameter(delegateSpec);
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String project = ToolSupport.stringArg(args, "project", defaultProject.get());
        if (project == null || project.isBlank()) {
            return ToolResponse.fail("no projects onboarded; use add_project or the admin UI first");
        }
        try (var use = lifecycle.use(project)) {
            GraphTool delegate = use == null ? null : byProject.get(project);
            if (delegate == null) {
                List<String> known;
                synchronized (byProject) { known = List.copyOf(byProject.keySet()); }
                return ToolResponse.fail("unknown or expired project: " + project
                        + " (known: " + String.join(", ", known) + "); use add_project to onboard it");
            }
            return delegate.call(args);
        }
    }

    private static ToolSpec injectProjectParameter(ToolSpec delegate) {
        try {
            ObjectNode schema = (ObjectNode) JSON.readTree(delegate.inputSchemaJson());
            ObjectNode properties = schema.withObject("properties");
            ObjectNode project = properties.putObject("project");
            project.put("type", "string");
            project.put("description", "Project to query. If omitted, the first onboarded project "
                    + "is used; call list_projects to see available names.");
            return new ToolSpec(delegate.name(), delegate.description(), schema.toString());
        } catch (JsonProcessingException | ClassCastException e) {
            return delegate; // unparseable schema: serve it unchanged rather than fail startup
        }
    }
}
