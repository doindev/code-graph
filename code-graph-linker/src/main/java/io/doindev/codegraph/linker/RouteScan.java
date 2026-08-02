package io.doindev.codegraph.linker;

import java.util.List;

/**
 * Intermediate result of scanning a repository for HTTP route facts: server-side route
 * declarations and client-side call sites, both already normalized (see {@link RouteNormalizer}).
 * Package-visible so tests can assert on the raw scan before matching.
 *
 * @param serverRoutes route declarations (framework annotations / registrations)
 * @param clientCalls  outbound HTTP call sites with a path-like literal
 */
record RouteScan(List<ServerRoute> serverRoutes, List<ClientCall> clientCalls) {

    RouteScan {
        serverRoutes = List.copyOf(serverRoutes);
        clientCalls = List.copyOf(clientCalls);
    }

    /**
     * A server-side route declaration.
     *
     * @param relPath        repo-relative file path, '/' separators
     * @param line           1-based line of the declaration
     * @param method         upper-case HTTP method, or {@code null} when the framework construct
     *                       does not pin one (e.g. {@code @RequestMapping}, {@code @app.route})
     * @param rawPath        literal as written in source
     * @param normalizedPath params collapsed to {@code {}}, trailing slash stripped
     */
    record ServerRoute(String relPath, int line, String method, String rawPath, String normalizedPath) {
    }

    /**
     * A client-side HTTP call site.
     *
     * @param relPath        repo-relative file path, '/' separators
     * @param line           1-based line of the call
     * @param method         upper-case HTTP method, or {@code null} when unknown (e.g. bare
     *                       {@code fetch(...)}, {@code URI.create(...)})
     * @param rawPath        literal as written in source (host already part of it if a full URL)
     * @param normalizedPath scheme/host stripped, params collapsed to {@code {}}, trailing slash stripped
     */
    record ClientCall(String relPath, int line, String method, String rawPath, String normalizedPath) {
    }
}
