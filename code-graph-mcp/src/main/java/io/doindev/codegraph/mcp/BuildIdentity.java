package io.doindev.codegraph.mcp;

import java.io.IOException;
import java.util.Properties;

/** Build-generated identity; safe in exploded IDE classes as well as packaged transports. */
public final class BuildIdentity {
    private static final Properties BUILD = load();
    private BuildIdentity() {}
    private static Properties load() {
        var result = new Properties();
        try (var input = BuildIdentity.class.getResourceAsStream("/code-graph-build.properties")) {
            if (input != null) result.load(input);
        } catch (IOException ignored) { /* An unfiltered developer build is explicitly identified. */ }
        return result;
    }
    public static String version() { return value("version", "development-unpackaged"); }
    public static String builtAt() { return value("builtAt", "unknown"); }
    private static String value(String name, String fallback) {
        String value = BUILD.getProperty(name, fallback);
        return value.contains("${") ? fallback : value;
    }
}
