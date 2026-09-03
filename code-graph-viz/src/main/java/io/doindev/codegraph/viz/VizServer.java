package io.doindev.codegraph.viz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The visualization HTTP server: static UI assets (vendored — no CDN, nothing leaves the
 * machine) plus a JSON API over the workspace graphs. Read endpoints are always served; the
 * action endpoints (reindex/add/remove/browse) are served only when the backing
 * {@link VizControl} is {@link VizControl#mutable() mutable}. Binds to loopback by default.
 */
public final class VizServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8");

    private final HttpServer server;
    private final VizControl control;

    private VizServer(HttpServer server, VizControl control) {
        this.server = server;
        this.control = control;
    }

    /** Start on loopback over a live workspace. Port 0 picks an ephemeral port (see {@link #port()}). */
    public static VizServer start(VizControl control, int port) {
        return start(control, InetAddress.getLoopbackAddress(), port);
    }

    public static VizServer start(VizControl control, InetAddress bind, int port) {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(bind, port), 0);
            VizServer viz = new VizServer(httpServer, control);
            httpServer.createContext("/", viz::handle);
            httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            httpServer.start();
            return viz;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start viz server on port " + port, e);
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- routing ----

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (method.equals("GET") && isStatic(path)) {
                serveStatic(exchange, path);
                return;
            }
            if (path.equals("/api/server") && method.equals("GET")) {
                respondJson(exchange, 200, serverInfo());
                return;
            }
            if (path.equals("/api/projects") && method.equals("GET")) {
                respondJson(exchange, 200, VizApi.projects(control.projects(), control.lifecycle()));
                return;
            }
            if (path.equals("/api/settings") && method.equals("PUT")) {
                requireMutable();
                byte[] body = exchange.getRequestBody().readNBytes(1025);
                if (body.length > 1024) throw new IllegalArgumentException("settings payload too large");
                com.fasterxml.jackson.databind.JsonNode json;
                try { json = JSON.readTree(body); }
                catch (IOException e) { throw new IllegalArgumentException("invalid settings JSON"); }
                var value = json == null ? null : json.get("projectTtl");
                var memory = json == null ? null : json.get("graphMemory");
                if ((value == null) == (memory == null)) throw new IllegalArgumentException("set exactly one of projectTtl or graphMemory");
                if (memory != null) {
                    if (!memory.isTextual()) throw new IllegalArgumentException("graphMemory must be text, e.g. 1g");
                    control.graphMemory(memory.asText());
                } else {
                    if (!value.isTextual() || control.lifecycle() == null) throw new IllegalArgumentException("projectTtl is unavailable or invalid");
                    control.lifecycle().setTtl(io.doindev.codegraph.lifecycle.ProjectLifecycle.parseTtl(value.asText()));
                }
                respondJson(exchange, 200, serverInfo());
                return;
            }
            if (path.equals("/api/browse") && method.equals("GET")) {
                requireMutable();
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                respondJson(exchange, 200, browse(query.get("path")));
                return;
            }
            if (path.startsWith("/api/p/")) {
                handleProjectApi(exchange, method, path);
                return;
            }
            if (path.equals("/api/project") && method.equals("POST")) {
                requireMutable();
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                respondJson(exchange, 202, addJobJson(control.startAdd(required(query, "path"))));
                return;
            }
            if (path.equals("/api/project/status") && method.equals("GET")) {
                requireMutable();
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                VizControl.AddJob job = control.addStatus(required(query, "job"));
                if (job == null) {
                    respondJson(exchange, 404, "{\"error\":\"unknown job\"}");
                } else {
                    respondJson(exchange, 200, addJobJson(job));
                }
                return;
            }
            if (path.equals("/api/project/cancel") && method.equals("POST")) {
                requireMutable();
                Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
                boolean cancelled = control.cancelAdd(required(query, "job"));
                respondJson(exchange, cancelled ? 200 : 404,
                        cancelled ? "{\"cancelled\":true}" : "{\"error\":\"unknown job\"}");
                return;
            }
            respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
        } catch (Forbidden e) {
            respondJson(exchange, 403, "{\"error\":" + quote(e.getMessage()) + "}");
        } catch (IllegalArgumentException e) {
            respondJson(exchange, 400, "{\"error\":" + quote(e.getMessage()) + "}");
        } catch (RuntimeException e) {
            respondJson(exchange, 500, "{\"error\":" + quote(String.valueOf(e)) + "}");
        } finally {
            exchange.close();
        }
    }

    private void handleProjectApi(HttpExchange exchange, String method, String path) throws IOException {
        // /api/p/{project}[/{action}]
        String rest = path.substring("/api/p/".length());
        int slash = rest.indexOf('/');
        String project = URLDecoder.decode(slash < 0 ? rest : rest.substring(0, slash),
                StandardCharsets.UTF_8);
        String action = slash < 0 ? "" : rest.substring(slash + 1);

        if (method.equals("DELETE") || (method.equals("POST") && action.equals("reindex"))) {
            requireMutable();
        }
        long expectedInstance = 0;
        String instanceHeader = exchange.getRequestHeaders().getFirst("X-Project-Instance");
        if (instanceHeader != null) {
            expectedInstance = Long.parseLong(instanceHeader);
            if (expectedInstance <= 0) throw new IllegalArgumentException("invalid project instance");
        }
        var lifecycle = control.lifecycle();
        try (var use = lifecycle == null ? null : lifecycle.use(project, expectedInstance)) {
            if (lifecycle != null && use == null) {
                respondJson(exchange, 404, "{\"error\":\"unknown or expired project; onboard it again\"}");
                return;
            }
            if (method.equals("POST") && action.equals("activity")) {
                boolean known = lifecycle != null || control.projects().stream().anyMatch(p -> p.name().equals(project));
                respondJson(exchange, known ? 200 : 404, known ? "{\"active\":true}" : "{\"error\":\"unknown project\"}");
                return;
            }
            handleLiveProjectApi(exchange, method, project, action);
        }
    }

    private void handleLiveProjectApi(HttpExchange exchange, String method, String project, String action)
            throws IOException {
        if (method.equals("DELETE") && action.isEmpty()) {
            requireMutable();
            boolean removed = control.remove(project);
            respondJson(exchange, removed ? 200 : 404,
                    removed ? "{\"removed\":" + quote(project) + "}"
                            : "{\"error\":\"unknown project: " + project + "\"}");
            return;
        }
        if (method.equals("POST") && action.equals("reindex")) {
            requireMutable();
            VizControl.AddJob job = control.startReindex(project);
            if (job == null) {
                respondJson(exchange, 404, "{\"error\":\"unknown project: " + project + "\"}");
            } else {
                respondJson(exchange, 202, addJobJson(job));
            }
            return;
        }
        if (!method.equals("GET")) {
            respond(exchange, 405, "text/plain", "method not allowed".getBytes(StandardCharsets.UTF_8));
            return;
        }
        // Never retain graphs between requests: expiration releases all server-owned references.
        VizApi api = control.projects().stream().filter(p -> p.name().equals(project))
                .findFirst().map(VizApi::new).orElse(null);
        if (api == null) {
            respondJson(exchange, 404, "{\"error\":\"unknown project: " + project + "\"}");
            return;
        }
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        String json = api.read(() -> switch (action) {
            case "overview" -> api.overview();
            case "module" -> api.module(required(query, "name"));
            case "file" -> api.file(required(query, "path"));
            case "ego" -> api.ego(required(query, "id"),
                    intParam(query, "depth", 2, 1, 5), intParam(query, "cap", 300, 10, 1000));
            case "galaxy" -> api.galaxy(intParam(query, "cap", 2000, 50, 10000),
                    query.get("lang"), query.get("module"),
                    Float.parseFloat(query.getOrDefault("minConfidence", "0")));
            case "node" -> api.node(required(query, "id"));
            case "search" -> api.search(required(query, "q"), intParam(query, "limit", 20, 1, 100));
            case "status" -> api.status();
            default -> null;
        });
        if (json == null) {
            respondJson(exchange, 404, "{\"error\":\"unknown action: " + action + "\"}");
        } else {
            respondJson(exchange, 200, json);
        }
    }

    // ---- action helpers ----

    private String serverInfo() {
        ObjectNode out = JSON.createObjectNode();
        out.put("mcpEndpoint", control.mcpEndpoint());
        out.put("mutable", control.mutable());
        out.put("vizPort", port());
        out.set("graphStorage", JSON.valueToTree(control.storageStatus()));
        if (control.lifecycle() != null) {
            out.put("projectTtlSeconds", control.lifecycle().ttl().toSeconds());
        }
        return out.toString();
    }

    private String browse(String path) {
        VizControl.DirListing listing = control.browse(path);
        ObjectNode out = JSON.createObjectNode();
        out.put("path", listing.path());
        if (listing.parent() != null) {
            out.put("parent", listing.parent());
        }
        ArrayNode entries = out.putArray("entries");
        for (VizControl.DirListing.Entry entry : listing.entries()) {
            ObjectNode row = entries.addObject();
            row.put("name", entry.name());
            row.put("path", entry.path());
            row.put("looksLikeRepo", entry.looksLikeRepo());
        }
        return out.toString();
    }

    private static String addJobJson(VizControl.AddJob job) {
        ObjectNode out = JSON.createObjectNode();
        out.put("job", job.id());
        out.put("name", job.name());
        out.put("state", job.state());
        out.put("elapsedMs", job.elapsedMs());
        if (job.error() != null) {
            out.put("error", job.error());
        }
        return out.toString();
    }

    private void requireMutable() {
        if (!control.mutable()) {
            throw new Forbidden("actions are disabled on this server (start with --viz-admin to enable)");
        }
    }

    private static final class Forbidden extends RuntimeException {
        Forbidden(String message) {
            super(message);
        }
    }

    // ---- static assets ----

    private static boolean isStatic(String path) {
        return path.equals("/") || path.equals("/index.html") || path.equals("/app.js")
                || path.equals("/style.css") || path.startsWith("/vendor/");
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String name = path.equals("/") ? "index.html" : path.substring(1);
        if (name.contains("..")) {
            respond(exchange, 400, "text/plain", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream in = VizServer.class.getResourceAsStream("/codegraph/viz/" + name)) {
            if (in == null) {
                respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String extension = name.substring(name.lastIndexOf('.') + 1);
            respond(exchange, 200, CONTENT_TYPES.getOrDefault(extension, "application/octet-stream"),
                    in.readAllBytes());
        }
    }

    // ---- low-level ----

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json; charset=utf-8",
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                query.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static String required(Map<String, String> query, String name) {
        String value = query.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("query parameter '" + name + "' is required");
        }
        return value;
    }

    private static int intParam(Map<String, String> query, String name, int fallback, int min, int max) {
        String value = query.get(name);
        int result = value == null ? fallback : Integer.parseInt(value);
        return Math.max(min, Math.min(max, result));
    }

    private static String quote(String value) {
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
