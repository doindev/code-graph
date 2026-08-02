package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One agent-facing tool, transport-agnostic — the MCP servers (stdio, streamable HTTP) adapt
 * this to SDK types; nothing in this layer imports the MCP SDK. Implementations must be
 * thread-safe and must respect the token-frugality invariants: results capped, truncation
 * always marked, never any file contents in a response.
 */
public interface GraphTool {

    ToolSpec spec();

    /** Execute with validated arguments; must not throw — map failures to {@code ToolResponse.error}. */
    ToolResponse call(JsonNode args);
}
