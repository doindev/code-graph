package io.doindev.codegraph.tools;

/** Result of a tool call: a JSON payload and whether it represents an error. */
public record ToolResponse(String json, boolean error) {

    public static ToolResponse ok(String json) {
        return new ToolResponse(json, false);
    }

    public static ToolResponse fail(String message) {
        return new ToolResponse("{\"error\":" + quote(message) + "}", true);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
