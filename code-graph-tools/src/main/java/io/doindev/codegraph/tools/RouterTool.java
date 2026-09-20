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
        if (spec.name().equals("get_symbol_context") && (!args.path("project").isTextual() || args.path("project").asText().isBlank()))
            return ToolResponse.fail("get_symbol_context requires an explicit project; use list_projects for names");
        String project = ToolSupport.stringArg(args, "project", defaultProject.get());
        if (project == null || project.isBlank()) {
            return ToolResponse.fail("no projects onboarded; use add_project or the admin UI first");
        }
        if (spec.name().equals("index_status")) {
            var before = lifecycle.status(project);
            GraphTool delegate = before == null ? null : byProject.get(project);
            if (delegate == null) return ToolResponse.fail("unknown or expired project: " + project);
            ToolResponse response = delegate.call(args);
            var after = lifecycle.status(project);
            if (after == null || after.instanceId() != before.instanceId())
                return ToolResponse.fail("stale_index_instance: project was removed or re-onboarded during the wait");
            return response;
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
            if (delegate.name().equals("get_symbol_context")) {
                project.put("minLength", 1).put("description", "Exact onboarded project name; required for bundled evidence.");
                schema.withArray("required").add("project");
            }
            return new ToolSpec(delegate.name(), delegate.description(), schema.toString());
        } catch (JsonProcessingException | ClassCastException e) {
            return delegate; // unparseable schema: serve it unchanged rather than fail startup
        }
    }
}
