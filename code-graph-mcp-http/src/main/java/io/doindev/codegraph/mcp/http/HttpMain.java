package io.doindev.codegraph.mcp.http;

import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.mcp.Main;

/**
 * HTTP entry point:
 * {@code java -cp ... io.doindev.codegraph.mcp.http.HttpMain [--root DIR]... [--workspace FILE] [--port N] [--viz PORT]}.
 * The workspace starts empty unless roots are supplied through {@code --root},
 * {@code --workspace}, or {@code CODE_GRAPH_ROOT}. The HTTP port defaults to 3000.
 * Multiple {@code --root} flags (or a workspace file) serve as independent projects; agents
 * pick one via the tools' {@code project} parameter. {@code --viz} also serves the 3D
 * visualization UI. Runs until the process is killed.
 */
public final class HttpMain {

    private HttpMain() {
    }

    public static void main(String[] args) throws Exception {
        java.time.Duration projectTtl = Main.projectTtl(args);
        int port = Integer.parseInt(argValue(args, "--port",
                System.getenv().getOrDefault("CODE_GRAPH_PORT", "3000")));
        String viz = argValue(args, "--viz", null);
        int vizPort = viz == null ? -1 : Integer.parseInt(viz);

        boolean vizAdmin = hasFlag(args, "--viz-admin");
        Analyzers analyzers = Analyzers.discover();
        Workspace workspace = Main.openWorkspace(args, analyzers);
        try (HttpServer server = HttpServer.start(workspace, port, vizPort, vizAdmin, projectTtl,
                io.doindev.codegraph.dba.DbaConfig.parse(args).orElse(null))) {
            System.err.println("code-graph-http: watching onboarded projects for changes; Ctrl-C to stop");
            server.join();
        }
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static String argValue(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
