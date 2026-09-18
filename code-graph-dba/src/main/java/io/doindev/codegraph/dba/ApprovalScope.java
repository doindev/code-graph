package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.*;

/** Exact authorization boundary, deliberately not an environment-wide grant. */
final class ApprovalScope {
    private ApprovalScope() {}
    static ObjectNode resolve(JsonNode profile, JsonNode binding, JsonNode input) {
        boolean bound = binding.has("id");
        if (bound && (input.has("database") || input.has("schema")))
            throw new IllegalArgumentException("Database/schema cannot override a project binding");
        String vendor = profile.path("templateId").asText();
        String database = bound ? binding.path("database").asText() : input.path("database").asText("");
        String schema = bound ? binding.path("schema").asText() : input.path("schema").asText("");
        if (database.isBlank()) database = defaultDatabase(profile, vendor);
        if (schema.isBlank()) schema = switch (vendor) {
            case "mysql", "mariadb" -> database;
            case "postgresql" -> "public";
            case "h2" -> "PUBLIC";
            default -> "";
        };
        for (String value : List.of(database, schema))
            if (value.length() > 128 || value.indexOf('\0') >= 0 || value.contains("${"))
                throw new IllegalArgumentException("Use an explicit database/schema name, not environment interpolation");
        if (Set.of("mysql", "mariadb").contains(vendor) && !schema.equals(database))
            throw new IllegalArgumentException("MySQL/MariaDB schema must equal the selected database");
        ObjectNode scope = Profiles.JSON.createObjectNode().put("connectionId", profile.path("id").asText())
                .put("database", database).put("schema", schema).put("vendor", vendor)
                .put("profileRevision", ProjectContexts.profileRevision(profile));
        if (bound) {
            for (String key : List.of("projectId", "environment", "role")) scope.put(key, binding.path(key).asText());
            scope.put("bindingId", binding.path("id").asText());
            // Includes enabled/scanning revisions as well: changes never silently revive an old grant.
            scope.put("bindingRevision", CatalogScanner.hash(binding.toString()));
        }
        return scope;
    }
    static ObjectNode display(JsonNode scope) {
        ObjectNode out = scope.deepCopy(); out.remove(List.of("profileRevision", "bindingRevision")); return out;
    }
    private static String defaultDatabase(JsonNode profile, String vendor) {
        String url = profile.path("url").asText();
        if (url.contains("${")) return ""; // Never expand environment secrets into public scope.
        try {
            if (Set.of("mysql", "mariadb", "postgresql").contains(vendor)) {
                String path = URI.create(url.substring(5)).getPath();
                return path == null || path.length() < 2 ? "" : path.substring(1);
            }
            if (vendor.equals("h2") && url.startsWith("jdbc:h2:mem:"))
                return url.substring(12).split(";", 2)[0].toUpperCase(Locale.ROOT);
            if(vendor.equals("h2")&&url.startsWith("jdbc:h2:")&&!url.startsWith("jdbc:h2:tcp:")&&!url.startsWith("jdbc:h2:ssl:")){
                String path=url.substring(8).split(";",2)[0];if(path.startsWith("file:"))path=path.substring(5);
                path=path.replace('\\','/');return path.substring(path.lastIndexOf('/')+1).toUpperCase(Locale.ROOT);
            }
        } catch (IllegalArgumentException ignored) { }
        return "";
    }
}
