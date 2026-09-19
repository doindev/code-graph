package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** A canonical fingerprint of exactly the advertised catalog, independent of registration order. */
final class ToolCatalogIdentity {
    private static final ObjectMapper JSON = new ObjectMapper();
    static ObjectNode describe(List<McpSchema.Tool> tools) {
        return JSON.createObjectNode().put("version", BuildIdentity.version()).put("builtAt", BuildIdentity.builtAt())
                .put("toolCatalogFingerprint", fingerprint(tools)).put("toolCount", tools.size());
    }
    static String fingerprint(List<McpSchema.Tool> tools) {
        var catalog = JSON.createArrayNode();
        tools.stream().sorted(Comparator.comparing(McpSchema.Tool::name))
                .forEach(tool -> catalog.add(canonical(JSON.valueToTree(tool))));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(catalog.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = JSON.createObjectNode();
            var keys = new TreeSet<String>(); value.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> sorted.set(key, canonical(value.get(key))));
            return sorted;
        }
        if (value.isArray()) {
            var array = JSON.createArrayNode(); value.forEach(item -> array.add(canonical(item))); return array;
        }
        return value;
    }
}
