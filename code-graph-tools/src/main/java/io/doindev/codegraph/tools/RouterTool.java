package io.doindev.codegraph.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    RouterTool(Map<String, GraphTool> byProject, java.util.function.Supplier<String> defaultProject) {
        this.byProject = byProject;
        this.defaultProject = defaultProject;
        this.spec = injectProjectParameter(byProject.get(defaultProject.get()).spec(),
                Set.copyOf(byProject.keySet()));
    }

    @Override
    public ToolSpec spec() {
        return spec;
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String project = ToolSupport.stringArg(args, "project", defaultProject.get());
        GraphTool delegate = byProject.get(project);
        if (delegate == null) {
            return ToolResponse.fail("unknown project: " + project
                    + " (known: " + String.join(", ", List.copyOf(byProject.keySet())) + ")");
        }
        return delegate.call(args);
    }

    private static ToolSpec injectProjectParameter(ToolSpec delegate, Set<String> projects) {
        try {
            ObjectNode schema = (ObjectNode) JSON.readTree(delegate.inputSchemaJson());
            ObjectNode properties = schema.withObject("properties");
            ObjectNode project = properties.putObject("project");
            project.put("type", "string");
            project.put("description", "Project to query (default: " + projects.iterator().next()
                    + "). Known projects: " + String.join(", ", projects));
            return new ToolSpec(delegate.name(), delegate.description(), schema.toString());
        } catch (JsonProcessingException | ClassCastException e) {
            return delegate; // unparseable schema: serve it unchanged rather than fail startup
        }
    }
}
