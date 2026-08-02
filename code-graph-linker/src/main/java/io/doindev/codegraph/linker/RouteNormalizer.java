package io.doindev.codegraph.linker;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Route-string normalization and matching rules for {@link HttpRouteLinker}.
 *
 * <p>Normalization invariants:
 * <ul>
 *   <li>Full URLs lose scheme and host; only the path part survives.</li>
 *   <li>Every parameter notation — {@code {id}}, {@code :id}, {@code <id>}, {@code ${expr}},
 *       {@code %s}/{@code %d}, f-string {@code {expr}} — collapses to the single token {@code {}}.</li>
 *   <li>Trailing slashes are stripped; the result always starts with {@code /} and has
 *       length &gt;= 2.</li>
 *   <li>Literals ending in a file-extension segment (e.g. {@code .png}, {@code .css},
 *       {@code .js}) are rejected — they are assets, not API routes.</li>
 * </ul>
 *
 * <p>Matching rules (client path vs server path, both normalized):
 * <ul>
 *   <li>equal &rarr; confidence 0.7</li>
 *   <li>equal after stripping one leading base segment ({@code /api} or {@code /v<digits>})
 *       from exactly ONE side &rarr; confidence 0.5</li>
 *   <li>both sides know the HTTP method and it matches &rarr; +0.1, capped at 0.8</li>
 *   <li>both sides know the HTTP method and it differs &rarr; no match at all</li>
 * </ul>
 */
final class RouteNormalizer {

    /** Full URL: scheme://host[:port] with optional path — group 1 is the path (may be null). */
    private static final Pattern FULL_URL =
            Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*://[^/\\s]*(/\\S*)?$");
    /** JS template-literal / shell-style interpolation: {@code ${...}}. */
    private static final Pattern DOLLAR_BRACE = Pattern.compile("\\$\\{[^}]*}");
    /** Brace params: {@code {id}}, FastAPI {@code {user_id}}, f-string {@code {expr}}. */
    private static final Pattern BRACE = Pattern.compile("\\{[^}]*}");
    /** Angle params: Flask {@code <id>}, {@code <int:id>}. */
    private static final Pattern ANGLE = Pattern.compile("<[^>]*>");
    /** Colon params as a path segment: Express/Rails {@code /:id}. */
    private static final Pattern COLON = Pattern.compile("/:([A-Za-z_][A-Za-z0-9_]*)");
    /** printf-style placeholders: {@code %s}, {@code %d}. */
    private static final Pattern PERCENT = Pattern.compile("%[sd]");
    /** File-extension final segment, e.g. {@code .png .css .js .html}. */
    private static final Pattern FILE_EXT = Pattern.compile("\\.[A-Za-z0-9]{1,6}$");
    /** Leading base segment tolerated on one side: {@code /api} or {@code /v<digits>}. */
    private static final Pattern BASE_SEGMENT = Pattern.compile("^/(?:api|v\\d+)(/.+)$");

    private RouteNormalizer() {
    }

    /**
     * Normalize a route/URL literal; empty when the literal is not a plausible API route
     * (relative path, too short, asset file, URL without a path).
     */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String path = raw.trim();
        Matcher url = FULL_URL.matcher(path);
        if (url.matches()) {
            path = url.group(1) == null ? "" : url.group(1);
        }
        if (!path.startsWith("/") || path.length() < 2) {
            return Optional.empty();
        }
        path = DOLLAR_BRACE.matcher(path).replaceAll("{}");
        path = BRACE.matcher(path).replaceAll("{}");
        path = ANGLE.matcher(path).replaceAll("{}");
        path = COLON.matcher(path).replaceAll("/{}");
        path = PERCENT.matcher(path).replaceAll("{}");
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.length() < 2) {
            return Optional.empty();
        }
        if (FILE_EXT.matcher(path).find()) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    /**
     * Confidence that a client call reaches a server route; {@code 0} means no match.
     * Methods are upper-case or {@code null} (= unknown).
     */
    static float matchConfidence(String clientPath, String clientMethod,
                                 String serverPath, String serverMethod) {
        float base;
        if (clientPath.equals(serverPath)) {
            base = 0.7f;
        } else {
            String clientStripped = stripBaseSegment(clientPath);
            String serverStripped = stripBaseSegment(serverPath);
            if (serverPath.equals(clientStripped) || clientPath.equals(serverStripped)) {
                base = 0.5f;
            } else {
                return 0f;
            }
        }
        if (clientMethod != null && serverMethod != null) {
            if (!clientMethod.equals(serverMethod)) {
                return 0f; // method mismatch kills the match
            }
            base = Math.min(0.8f, base + 0.1f);
        }
        return base;
    }

    /** Path with one leading {@code /api} or {@code /v<digits>} segment removed, or null. */
    static String stripBaseSegment(String path) {
        Matcher m = BASE_SEGMENT.matcher(path);
        return m.matches() ? m.group(1) : null;
    }
}
