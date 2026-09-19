package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class TemplateDiscoveryTest {
    @Test void discoversEveryRecipeInBoundedStablePagesWithoutRuntimeResources() {
        var discovery = new TemplateDiscovery();
        var args = Profiles.JSON.createObjectNode().put("limit", 3);
        var ids = new HashSet<String>();
        String generation = null;
        do {
            var page = discovery.list(args);
            assertTrue(page.path("templates").size() <= 3);
            if (generation == null) generation = page.path("generation").asText();
            assertEquals(generation, page.path("generation").asText());
            for (var template : page.path("templates")) {
                assertTrue(ids.add(template.path("id").asText()));
                assertTrue(template.has("properties"));
                if(template.path("transport").asText().equals("jdbc"))assertTrue(template.has("artifactId"));
                else {assertEquals("bundled_native",template.path("driverSource").asText());assertTrue(template.has("clientVersion"));assertFalse(template.path("capabilities").path("sql").asBoolean());}
            }
            args.put("cursor", page.path("nextCursor").asText(""));
        } while (!args.path("cursor").asText().isEmpty());
        assertEquals(DatabaseCatalog.ALL.size()+NativeCatalog.IDS.size(), ids.size());
    }

    @Test void filtersAndRejectsInvalidOrDifferentCursors() {
        var discovery = new TemplateDiscovery();
        var args = Profiles.JSON.createObjectNode().put("limit", 1);
        String cursor = discovery.list(args).path("nextCursor").asText();
        args.put("cursor", cursor).put("templateId", "postgresql");
        assertThrows(IllegalArgumentException.class, () -> discovery.list(args));
        args.remove("cursor");
        assertEquals("postgresql", discovery.list(args).path("templates").get(0).path("id").asText());
        args.put("limit", 51);
        assertThrows(IllegalArgumentException.class, () -> discovery.list(args));
    }
}
