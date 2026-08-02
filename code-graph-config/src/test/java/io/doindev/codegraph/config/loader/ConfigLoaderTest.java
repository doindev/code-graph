package io.doindev.codegraph.config.loader;

import io.doindev.codegraph.config.CodeGraphConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    @TempDir
    Path root;

    private void write(String name, String content) throws IOException {
        Files.writeString(root.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    void missingFileYieldsDefaults() {
        CodeGraphConfig config = ConfigLoader.load(root, Map.of());
        assertEquals(70, config.gating().threshold());
        assertEquals(50, config.limits().maxResults());
        assertEquals(0.40, config.scoring().weights().get("reach"));
        assertTrue(config.smells().isEmpty());
    }

    @Test
    void loadsCommentedJsonWithTrailingCommas() throws IOException {
        write("code-graph.json", """
                {
                  // gate hard in this repo
                  "gating": { "threshold": 85, "attachRiskReport": true, "failCiOn": ["blast",] },
                  "limits": { "maxResults": 25, "maxResponseBytes": 16384 },
                  "architecture": {
                    "modules": [ { "name": "api", "paths": ["src/api/**"] } ],
                    "allowedDependencies": { "api": ["domain"] },
                    "forbidCycles": true,
                    "unassigned": "warn"
                  },
                }
                """);
        CodeGraphConfig config = ConfigLoader.load(root, Map.of());
        assertEquals(85, config.gating().threshold());
        assertEquals(25, config.limits().maxResults());
        assertEquals("api", config.architecture().modules().get(0).name());
        // absent sections still get defaults
        assertEquals(1000, config.scoring().reachRef());
    }

    @Test
    void loadsYaml() throws IOException {
        write("code-graph.yaml", """
                gating:
                  threshold: 60
                  attachRiskReport: false
                  failCiOn: [drift]
                smells:
                  god-class:
                    enabled: true
                    severity: error
                    thresholds:
                      methodCount: 30
                """);
        CodeGraphConfig config = ConfigLoader.load(root, Map.of());
        assertEquals(60, config.gating().threshold());
        assertEquals("error", config.smells().get("god-class").severity());
        assertEquals(30.0, config.smells().get("god-class").thresholds().get("methodCount"));
    }

    @Test
    void bothFilesIsAnError() throws IOException {
        write("code-graph.json", "{}");
        write("code-graph.yaml", "{}");
        assertThrows(IllegalStateException.class, () -> ConfigLoader.load(root, Map.of()));
    }

    @Test
    void unknownPropertyFailsFast() throws IOException {
        write("code-graph.json", "{ \"gatting\": { \"threshold\": 85 } }");
        assertThrows(RuntimeException.class, () -> ConfigLoader.load(root, Map.of()));
    }

    @Test
    void envOverridesWin() throws IOException {
        write("code-graph.json", "{ \"gating\": { \"threshold\": 85 } }");
        CodeGraphConfig config = ConfigLoader.load(root, Map.of(
                "CODE_GRAPH_GATING_THRESHOLD", "40",
                "CODE_GRAPH_LIMITS_MAX_RESULTS", "10"));
        assertEquals(40, config.gating().threshold());
        assertEquals(10, config.limits().maxResults());
        assertEquals(32_768, config.limits().maxResponseBytes());
    }
}
