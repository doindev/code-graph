package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.query.GenerationCursor;

/** Public, bounded catalog discovery. Cannot access profiles, vaults, drivers or databases. */
final class TemplateDiscovery {
    private final GenerationCursor cursors = new GenerationCursor();

    ObjectNode list(JsonNode args) {
        String template = args.path("templateId").asText("");
        if (template.length() > 80) throw new IllegalArgumentException("templateId is too long");
        int limit = args.path("limit").asInt(10);
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be 1..50");
        if (!template.isEmpty()&&!NativeCatalog.IDS.contains(template)) DatabaseCatalog.get(template);
        var templates = DatabaseCatalog.json();
        String fingerprint = CatalogScanner.hash(templates.toString());
        long generation = Long.parseUnsignedLong(fingerprint.substring(0,16),16);
        var position = cursors.read(args.path("cursor").asText(""), template, generation);
        ObjectNode result = Profiles.JSON.createObjectNode().put("generation", fingerprint)
                .put("state", "complete").put("freshness", "bundled_recipes")
                .put("verification", "Recipes and descriptors only; JDBC connectivity does not certify advanced operations")
                .put("inventoryComplete", true);
        var rows = result.putArray("templates");
        long total = 0, seen = position.seen();
        for (JsonNode entry : templates) {
            if (!template.isEmpty() && !entry.path("id").asText().equals(template)) continue;
            if (total++ < position.seen() || rows.size() >= limit) continue;
            rows.add(entry); seen++;
        }
        result.put("total", total).put("truncated", seen < total);
        if (seen < total) result.put("nextCursor", cursors.issue(template, generation,
                new GenerationCursor.Position("", seen, position.expiresAt())));
        return result;
    }
}
