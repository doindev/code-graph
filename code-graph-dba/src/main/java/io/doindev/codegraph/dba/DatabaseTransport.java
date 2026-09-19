package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;

/** Profile transport identity is immutable in meaning, independent of display names. */
enum DatabaseTransport {
    JDBC("jdbc"), MONGODB("mongodb"), REDIS("redis");

    final String id;

    DatabaseTransport(String id) { this.id = id; }

    static DatabaseTransport of(JsonNode profile) {
        String template = profile.path("templateId").asText("custom");
        DatabaseTransport inferred = switch (template) {
            case "mongodb-native" -> MONGODB;
            case "redis-native" -> REDIS;
            default -> JDBC;
        };
        if (profile.has("transport") && (!profile.path("transport").isTextual()
                || !inferred.id.equals(profile.path("transport").asText()))) {
            throw new IllegalArgumentException("Connection transport does not match its template");
        }
        return inferred;
    }
}
