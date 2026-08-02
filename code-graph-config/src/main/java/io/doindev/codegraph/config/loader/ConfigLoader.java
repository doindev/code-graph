package io.doindev.codegraph.config.loader;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.doindev.codegraph.config.CodeGraphConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads {@link CodeGraphConfig} from the repo root. {@code code-graph.json} is canonical
 * (Java-style comments and trailing commas allowed); {@code code-graph.yaml} is accepted as an
 * alternative. Having both files is an error — teams must not maintain two sources of truth.
 * Unknown properties fail fast with the offending name so config typos never silently no-op.
 *
 * <p>Env overrides (applied after file load): {@code CODE_GRAPH_GATING_THRESHOLD},
 * {@code CODE_GRAPH_LIMITS_MAX_RESULTS}, {@code CODE_GRAPH_LIMITS_MAX_RESPONSE_BYTES}.
 */
public final class ConfigLoader {

    public static final String JSON_NAME = "code-graph.json";
    public static final String YAML_NAME = "code-graph.yaml";

    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private ConfigLoader() {
    }

    /** Load from {@code repoRoot}, falling back to defaults when no config file exists. */
    public static CodeGraphConfig load(Path repoRoot) {
        return load(repoRoot, System.getenv());
    }

    static CodeGraphConfig load(Path repoRoot, Map<String, String> env) {
        Path json = repoRoot.resolve(JSON_NAME);
        Path yaml = repoRoot.resolve(YAML_NAME);
        boolean hasJson = Files.isRegularFile(json);
        boolean hasYaml = Files.isRegularFile(yaml);
        if (hasJson && hasYaml) {
            throw new IllegalStateException("Both " + JSON_NAME + " and " + YAML_NAME
                    + " exist in " + repoRoot + " — keep exactly one config file");
        }
        CodeGraphConfig config;
        if (hasJson) {
            config = read(JSON, json);
        } else if (hasYaml) {
            config = read(YAML, yaml);
        } else {
            config = CodeGraphConfig.defaults();
        }
        return applyEnv(config.withDefaults(), env);
    }

    private static CodeGraphConfig read(ObjectMapper mapper, Path file) {
        try {
            return mapper.readValue(file.toFile(), CodeGraphConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + file + ": " + e.getMessage(), e);
        }
    }

    private static CodeGraphConfig applyEnv(CodeGraphConfig config, Map<String, String> env) {
        Integer threshold = intEnv(env, "CODE_GRAPH_GATING_THRESHOLD");
        Integer maxResults = intEnv(env, "CODE_GRAPH_LIMITS_MAX_RESULTS");
        Integer maxBytes = intEnv(env, "CODE_GRAPH_LIMITS_MAX_RESPONSE_BYTES");
        if (threshold == null && maxResults == null && maxBytes == null) {
            return config;
        }
        CodeGraphConfig.Gating gating = threshold == null ? config.gating()
                : new CodeGraphConfig.Gating(threshold, config.gating().attachRiskReport(),
                        config.gating().failCiOn());
        CodeGraphConfig.Limits limits = maxResults == null && maxBytes == null ? config.limits()
                : new CodeGraphConfig.Limits(
                        maxResults != null ? maxResults : config.limits().maxResults(),
                        maxBytes != null ? maxBytes : config.limits().maxResponseBytes());
        return new CodeGraphConfig(config.paths(), config.tests(), limits, config.scoring(),
                gating, config.deadCode(), config.smells(), config.architecture());
    }

    private static Integer intEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer, got: " + value, e);
        }
    }
}
