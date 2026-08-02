package io.doindev.codegraph.linker;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.query.GraphQuery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic cross-language HTTP route linker (id {@code "http-routes"}): scans source text for
 * server route declarations (Spring, JAX-RS, FastAPI/Flask, Express/Nest, Go net/http, ASP.NET,
 * Rails, Laravel) and client call sites (fetch, axios, java.net.http, requests/httpx, HttpClient,
 * Go http.Get/Post), normalizes the path literals ({@link RouteNormalizer}) and emits one
 * {@link EdgeKind#INVOKES_REMOTE} edge per (clientFile, serverFile, normalizedRoute) triple.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Everything is line-based regex, best effort — confidence is always &lt; 1 and
 *       {@code attrs["resolution"]="heuristic"}.</li>
 *   <li>No edge ever points from a file to itself.</li>
 *   <li>Only path-like literals participate: absolute paths (or the path part of a full URL),
 *       length &gt;= 2, not ending in an asset file extension.</li>
 *   <li>Output is deterministic: edges sorted by (clientFile, serverFile, route).</li>
 * </ul>
 */
public final class HttpRouteLinker implements Linker {

    /** Directories never worth scanning — mirrors FullIndexer.ALWAYS_IGNORED_DIRS (copied by design). */
    static final Set<String> ALWAYS_IGNORED_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out",
            "__pycache__", ".venv", "venv", ".idea", ".vscode", ".code-graph", "vendor", "bin", "obj");

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private static final Set<String> EXTENSIONS =
            Set.of("ts", "tsx", "js", "jsx", "py", "java", "cs", "go", "rb", "php", "kt");

    /**
     * One line-based extraction rule.
     *
     * @param regex       pattern applied per line
     * @param methodGroup capture group holding the HTTP method token, 0 = method unknown
     * @param pathGroup   capture group holding the path literal
     * @param concatAware for JS client literals: a trailing {@code + expr} after the closing
     *                    quote appends a {@code {}} parameter segment
     */
    private record Rule(Pattern regex, int methodGroup, int pathGroup, boolean concatAware) {
        Rule(String regex, int methodGroup, int pathGroup) {
            this(Pattern.compile(regex), methodGroup, pathGroup, false);
        }

        Rule(String regex, int methodGroup, int pathGroup, boolean concatAware) {
            this(Pattern.compile(regex), methodGroup, pathGroup, concatAware);
        }
    }

    // ---- server route declaration rules, keyed by file extension ----

    private static final List<Rule> JS_SERVER = List.of(
            // Express / Nest style registration; lookbehind keeps axios client calls out
            new Rule("(?<!axios)\\.(get|post|put|delete|patch)\\(\\s*['\"`]([^'\"`]+)", 1, 2));

    private static final List<Rule> PY_SERVER = List.of(
            // FastAPI / Flask decorators; "route" pins no method
            new Rule("@(?:app|router)\\.(get|post|put|delete|patch|route)\\(\\s*[fr]?['\"]([^'\"]+)", 1, 2));

    private static final List<Rule> JAVA_SERVER = List.of(
            // Spring; @RequestMapping pins no method
            new Rule("@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"", 1, 2),
            // JAX-RS
            new Rule("@Path\\(\\s*\"([^\"]+)\"\\s*\\)", 0, 1));

    private static final List<Rule> CS_SERVER = List.of(
            // attribute routing
            new Rule("\\[Http(Get|Post|Put|Delete|Patch)\\(\\s*\"([^\"]+)\"\\s*\\)?\\s*]", 1, 2),
            // minimal APIs
            new Rule("\\bMap(Get|Post|Put|Delete|Patch)\\(\\s*\"([^\"]+)\"", 1, 2));

    private static final List<Rule> GO_SERVER = List.of(
            new Rule("\\bHandleFunc\\(\\s*\"([^\"]+)\"", 0, 1),
            new Rule("\\.Handle\\(\\s*\"([^\"]+)\"", 0, 1));

    private static final List<Rule> RB_SERVER = List.of(
            // Rails routes.rb DSL only (rule applied only to files named routes.rb)
            new Rule("^\\s*(get|post|put|delete|patch)\\s+['\"]([^'\"]+)", 1, 2));

    private static final List<Rule> PHP_SERVER = List.of(
            // Laravel; "any" pins no method
            new Rule("Route::(get|post|put|delete|patch|any)\\(\\s*['\"]([^'\"]+)", 1, 2));

    // ---- client call-site rules, keyed by file extension ----

    private static final List<Rule> JS_CLIENT = List.of(
            new Rule("\\bfetch\\(\\s*(['\"`])([^'\"`]+)\\1", 0, 2, true),
            new Rule("\\baxios\\.(get|post|put|delete|patch)\\(\\s*(['\"`])([^'\"`]+)\\2", 1, 3, true));

    private static final List<Rule> PY_CLIENT = List.of(
            new Rule("\\brequests\\.(get|post|put|delete|patch|head)\\(\\s*[fr]?(['\"])([^'\"]+)\\2", 1, 3),
            new Rule("\\bhttpx\\.(get|post|put|delete|patch)\\(\\s*[fr]?(['\"])([^'\"]+)\\2", 1, 3));

    private static final List<Rule> JAVA_CLIENT = List.of(
            new Rule("URI\\.create\\(\\s*\"([^\"]+)\"", 0, 1),
            new Rule("HttpRequest\\.newBuilder\\(\\s*(?:URI\\.create\\(\\s*)?\"([^\"]+)\"", 0, 1));

    private static final List<Rule> CS_CLIENT = List.of(
            new Rule("\\.(Get|Post|Put|Delete|Patch)Async\\(\\s*\\$?\"([^\"]+)\"", 1, 2));

    private static final List<Rule> GO_CLIENT = List.of(
            new Rule("\\bhttp\\.(Get|Post|Head)\\(\\s*\"([^\"]+)\"", 1, 2));

    private static final Map<String, List<Rule>> SERVER_RULES = Map.ofEntries(
            Map.entry("ts", JS_SERVER), Map.entry("tsx", JS_SERVER),
            Map.entry("js", JS_SERVER), Map.entry("jsx", JS_SERVER),
            Map.entry("py", PY_SERVER),
            Map.entry("java", JAVA_SERVER), Map.entry("kt", JAVA_SERVER),
            Map.entry("cs", CS_SERVER),
            Map.entry("go", GO_SERVER),
            Map.entry("rb", RB_SERVER),
            Map.entry("php", PHP_SERVER));

    private static final Map<String, List<Rule>> CLIENT_RULES = Map.ofEntries(
            Map.entry("ts", JS_CLIENT), Map.entry("tsx", JS_CLIENT),
            Map.entry("js", JS_CLIENT), Map.entry("jsx", JS_CLIENT),
            Map.entry("py", PY_CLIENT),
            Map.entry("java", JAVA_CLIENT), Map.entry("kt", JAVA_CLIENT),
            Map.entry("cs", CS_CLIENT),
            Map.entry("go", GO_CLIENT));

    /** Method tokens that name a registration style rather than a single HTTP verb. */
    private static final Set<String> UNPINNED_METHOD_TOKENS = Set.of("REQUEST", "ROUTE", "ANY");

    private static final Pattern TRAILING_CONCAT = Pattern.compile("^\\s*\\+.*", Pattern.DOTALL);

    @Override
    public String id() {
        return "http-routes";
    }

    @Override
    public List<Edge> link(Path repoRoot, GraphQuery graph) {
        RouteScan scan = scan(repoRoot);
        record Best(float confidence, Map<String, String> attrs) {
        }
        Map<String, Best> best = new LinkedHashMap<>();
        Map<String, String[]> endpoints = new HashMap<>();
        for (RouteScan.ClientCall client : scan.clientCalls()) {
            for (RouteScan.ServerRoute server : scan.serverRoutes()) {
                if (client.relPath().equals(server.relPath())) {
                    continue; // never self-link
                }
                float confidence = RouteNormalizer.matchConfidence(
                        client.normalizedPath(), client.method(),
                        server.normalizedPath(), server.method());
                if (confidence <= 0f) {
                    continue;
                }
                String key = client.relPath() + "|" + server.relPath() + "|" + server.normalizedPath();
                Best current = best.get(key);
                if (current != null && current.confidence() >= confidence) {
                    continue;
                }
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("route", server.normalizedPath());
                attrs.put("clientLine", Integer.toString(client.line()));
                attrs.put("serverLine", Integer.toString(server.line()));
                String method = server.method() != null ? server.method() : client.method();
                if (method != null) {
                    attrs.put("method", method);
                }
                attrs.put("resolution", "heuristic");
                best.put(key, new Best(confidence, attrs));
                endpoints.put(key, new String[]{client.relPath(), server.relPath()});
            }
        }
        List<Edge> edges = new ArrayList<>(best.size());
        best.forEach((key, b) -> {
            String[] files = endpoints.get(key);
            edges.add(new Edge(new FileId(files[0]), new FileId(files[1]),
                    EdgeKind.INVOKES_REMOTE, b.confidence(), b.attrs()));
        });
        edges.sort(Comparator
                .comparing((Edge e) -> e.from().value())
                .thenComparing(e -> e.to().value())
                .thenComparing(e -> e.attrs().get("route")));
        return List.copyOf(edges);
    }

    // ---- scanning (package-visible for tests) ----

    /** Walk {@code root} and extract all server route declarations and client call sites. */
    static RouteScan scan(Path root) {
        List<RouteScan.ServerRoute> servers = new ArrayList<>();
        List<RouteScan.ClientCall> clients = new ArrayList<>();
        for (Path file : listSourceFiles(root)) {
            String relPath = root.relativize(file).toString().replace('\\', '/');
            String content;
            try {
                content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue; // best effort — unreadable file contributes nothing
            }
            String ext = extension(relPath);
            List<Rule> serverRules = SERVER_RULES.getOrDefault(ext, List.of());
            if (ext.equals("rb") && !relPath.endsWith("routes.rb")) {
                serverRules = List.of(); // Rails DSL only applies to routes.rb
            }
            List<Rule> clientRules = CLIENT_RULES.getOrDefault(ext, List.of());
            String[] lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int lineNo = i + 1;
                for (Rule rule : serverRules) {
                    Matcher m = rule.regex().matcher(line);
                    while (m.find()) {
                        String raw = m.group(rule.pathGroup());
                        Optional<String> normalized = RouteNormalizer.normalize(raw);
                        if (normalized.isPresent()) {
                            servers.add(new RouteScan.ServerRoute(
                                    relPath, lineNo, method(rule, m), raw, normalized.get()));
                        }
                    }
                }
                for (Rule rule : clientRules) {
                    Matcher m = rule.regex().matcher(line);
                    while (m.find()) {
                        String raw = m.group(rule.pathGroup());
                        if (rule.concatAware() && TRAILING_CONCAT.matcher(line.substring(m.end())).matches()) {
                            raw = raw.endsWith("/") ? raw + "{}" : raw + "/{}";
                        }
                        Optional<String> normalized = RouteNormalizer.normalize(raw);
                        if (normalized.isPresent()) {
                            clients.add(new RouteScan.ClientCall(
                                    relPath, lineNo, method(rule, m), raw, normalized.get()));
                        }
                    }
                }
            }
        }
        return new RouteScan(servers, clients);
    }

    private static String method(Rule rule, Matcher m) {
        if (rule.methodGroup() == 0) {
            return null;
        }
        String token = m.group(rule.methodGroup()).toUpperCase(Locale.ROOT);
        return UNPINNED_METHOD_TOKENS.contains(token) ? null : token;
    }

    private static String extension(String relPath) {
        int dot = relPath.lastIndexOf('.');
        return dot < 0 ? "" : relPath.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static List<Path> listSourceFiles(Path root) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && ALWAYS_IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.size() <= MAX_FILE_BYTES
                            && EXTENSIONS.contains(extension(file.getFileName().toString()))) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best effort — return what was collected before the failure
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }
}
