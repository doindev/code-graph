package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;

/** Resolved native namespace. This object describes scope; it does not grant access. */
record NativeTarget(DatabaseTransport transport, String connectionId, String connectionName,
                    String database, String collection, String topology) {
    static NativeTarget resolve(JsonNode profile, JsonNode input) {
        DatabaseTransport transport = DatabaseTransport.of(profile);
        if (transport == DatabaseTransport.JDBC) throw new IllegalArgumentException("A native connection is required");
        String id = text(input, "connectionId", 36);
        try { UUID.fromString(id); } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid connectionId");
        }
        String name = text(input, "connectionName", 120);
        if (!id.equals(profile.path("id").asText()) || !name.equals(profile.path("name").asText())) {
            throw new IllegalArgumentException("Connection UUID and exact name must match the selected native profile");
        }
        if (input.has("schema")) throw new IllegalArgumentException("Native targets have no SQL schema; select database and collection/key scope");
        String database = text(input, "database", 128);
        String collection = input.has("collection") ? text(input, "collection", 255) : "";
        String topology = profile.path("nativeOptions").path("topology").asText("standalone");
        if (transport == DatabaseTransport.MONGODB) {
            if (database.chars().anyMatch(ch -> "\\/.\"$ *<>:|?".indexOf(ch) >= 0) || database.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 63) {
                throw new IllegalArgumentException("Invalid MongoDB database name");
            }
            if (!collection.isEmpty() && (collection.startsWith("system.") || collection.contains("$"))) {
                throw new IllegalArgumentException("System collections require a dedicated administrative operation");
            }
        } else {
            if (!database.matches("0|[1-9][0-9]{0,4}") || Integer.parseInt(database) > 65535) {
                throw new IllegalArgumentException("Redis database must be an explicit integer from 0 to 65535");
            }
            if (topology.equals("cluster") && !database.equals("0")) {
                throw new IllegalArgumentException("Redis Cluster supports database 0 only");
            }
            if (!collection.isEmpty()) throw new IllegalArgumentException("Redis uses keys, not collections");
        }
        return new NativeTarget(transport, id, name, database, collection, topology);
    }

    ObjectNode json() {
        ObjectNode out = Profiles.JSON.createObjectNode().put("transport", transport.id)
                .put("connectionId", connectionId).put("connectionName", connectionName)
                .put("database", database).put("topology", topology);
        if (!collection.isEmpty()) out.put("collection", collection);
        return out;
    }

    static String text(JsonNode input, String field, int max) {
        JsonNode n = input.path(field);
        if (!n.isTextual() || n.asText().isBlank() || n.asText().length() > max
                || n.asText().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return n.asText();
    }
}
