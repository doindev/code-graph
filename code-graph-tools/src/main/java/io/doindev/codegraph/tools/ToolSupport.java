package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.NodeId;

import java.nio.charset.StandardCharsets;

/** Shared plumbing for tool implementations: JSON mapper, target parsing, response caps. */
final class ToolSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    private ToolSupport() {
    }

    /**
     * Parse a tool {@code target} argument: a full symbol id ({@code java:path#Qname/1}),
     * a {@code file:} id, or a bare repo-relative file path.
     */
    static NodeId target(String raw) {
        String value = raw.trim().replace('\\', '/');
        if (value.contains("#")) {
            return NodeId.parse(value);
        }
        if (value.startsWith("file:") || value.startsWith("repo:") || value.startsWith("mod:")) {
            return NodeId.parse(value);
        }
        return new FileId(value);
    }

    /**
     * Serialize a response, enforcing the byte cap: an oversized response is replaced by an
     * error asking the agent to narrow the query — never silently emitted.
     */
    static ToolResponse finish(ObjectNode node, CodeGraphConfig config) {
        String json = node.toString();
        int maxBytes = config.limits().maxResponseBytes();
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            return ToolResponse.fail("response too large (>" + maxBytes
                    + " bytes); narrow the query (lower depth/limit or a more specific target)");
        }
        return ToolResponse.ok(json);
    }

    static String stringArg(JsonNode args, String name, String fallback) {
        JsonNode value = args == null ? null : args.get(name);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    static int intArg(JsonNode args, String name, int fallback, int min, int max) {
        JsonNode value = args == null ? null : args.get(name);
        int result = value == null || value.isNull() ? fallback : value.asInt(fallback);
        return Math.max(min, Math.min(max, result));
    }
}
