package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;
import java.util.function.Function;

/** Transport-independent adapter for the serving layer's shared onboarding operation. */
final class AddProjectTool implements GraphTool {
    private final Function<String, String> onboard;

    AddProjectTool(Function<String, String> onboard) {
        this.onboard = Objects.requireNonNull(onboard);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("add_project",
                "Onboard a directory on the server: index it and start watching for changes. "
                        + "Returns the project name when the initial scan is ready (may take time). "
                        + "Rejects duplicate roots and children of onboarded or in-flight roots. "
                        + "Does not require the UI or UI admin mode; projects are session-local.",
                """
                { "type": "object",
                  "properties": {
                    "path": { "type": "string", "minLength": 1,
                      "description": "Existing directory on the server machine. Prefer an absolute path; relative paths resolve against the server working directory." } },
                  "required": ["path"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        JsonNode path = args == null ? null : args.get("path");
        if (path == null || !path.isTextual() || path.asText().isBlank()) {
            return ToolResponse.fail("path must be a non-blank directory path string");
        }
        try {
            String project = onboard.apply(path.asText());
            ObjectNode out = ToolSupport.JSON.createObjectNode();
            out.put("project", project);
            out.put("state", "ready");
            return ToolResponse.ok(out.toString());
        } catch (RuntimeException e) {
            return ToolResponse.fail(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }
}
