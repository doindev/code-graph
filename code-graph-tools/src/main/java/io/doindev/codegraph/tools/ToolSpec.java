package io.doindev.codegraph.tools;

/** Tool metadata: name, agent-facing description, and the JSON Schema for inputs (passed through to MCP verbatim). */
public record ToolSpec(String name, String description, String inputSchemaJson) {
}
