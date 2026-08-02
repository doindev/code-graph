package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.FullIndexer;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.tools.CodeGraphTools;
import io.doindev.codegraph.tools.GraphTool;
import io.doindev.codegraph.tools.WorkspaceTools;
import io.doindev.codegraph.viz.VizControl;
import io.doindev.codegraph.viz.VizServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * stdio entry point.
 *
 * <pre>
 * java ... io.doindev.codegraph.mcp.Main [--root DIR]... [--workspace FILE] [--viz PORT]
 * </pre>
 *
 * Multiple {@code --root} flags (or a workspace file listing {@code {"projects":[{"name","root"}]}})
 * index as independent projects served by one process; agents pick one via the tools'
 * {@code project} parameter. {@code --viz} additionally serves the 3D visualization UI on
 * localhost. Progress goes to stderr — stdout belongs to the MCP transport.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws InterruptedException {
        Analyzers analyzers = Analyzers.discover();
        if (analyzers.isEmpty()) {
            System.err.println("code-graph: no language analyzers on classpath");
            System.exit(1);
        }

        Workspace workspace = openWorkspace(args, analyzers);
        long start = System.nanoTime();
        Map<String, FullIndexer.Result> results = workspace.fullIndexAll();
        results.forEach((name, result) -> System.err.printf(
                "code-graph: [%s] indexed %d files, %d symbols, %d edges%n",
                name, result.filesIndexed(), result.symbolCount(), result.edgeCount()));
        System.err.printf("code-graph: %d project(s) ready in %d ms%n",
                results.size(), (System.nanoTime() - start) / 1_000_000);
        workspace.watchAll();

        List<CodeGraphTools.ProjectTools> projectTools = new ArrayList<>();
        for (Workspace.Project project : workspace.projects()) {
            projectTools.add(new CodeGraphTools.ProjectTools(project.name(), project.graph(),
                    project.config(), project.root(), reindexer(project)));
        }
        // the registry drives the routers; a viz-triggered removal must stop the watcher too
        WorkspaceTools registry = CodeGraphTools.workspace(projectTools, workspace::remove);
        List<GraphTool> tools = registry.tools();

        VizServer viz = null;
        int vizPort = intArg(args, "--viz", -1);
        if (vizPort >= 0) {
            // stdio viz binds to loopback, so actions (add/remove/reindex/browse) are safe on by default
            boolean admin = !hasFlag(args, "--viz-readonly");
            VizControl control = new WorkspaceVizControl(workspace, registry, analyzers, "stdio", admin);
            viz = VizServer.start(control, vizPort);
            System.err.println("code-graph: viz at http://localhost:" + viz.port() + "/"
                    + (admin ? " (actions enabled)" : " (read-only)"));
        }

        try (workspace;
             CodeGraphMcpServer ignored = CodeGraphMcpServer.serveStdio("code-graph", "0.0.1", tools)) {
            System.err.println("code-graph: watching for changes; serving MCP over stdio");
            Thread.currentThread().join();
        } finally {
            if (viz != null) {
                viz.close();
            }
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

    /** Shared by the stdio and HTTP entry points: resolve --workspace / repeated --root flags. */
    public static Workspace openWorkspace(String[] args, Analyzers analyzers) {
        String workspaceFile = argValue(args, "--workspace", null);
        if (workspaceFile != null) {
            return Workspace.openNamed(parseWorkspaceFile(Path.of(workspaceFile)), analyzers,
                    ConfigLoader::load);
        }
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--root")) {
                roots.add(Path.of(args[i + 1]));
            }
        }
        if (roots.isEmpty()) {
            roots.add(Path.of(System.getenv().getOrDefault("CODE_GRAPH_ROOT", ".")));
        }
        return Workspace.open(roots, analyzers, ConfigLoader::load);
    }

    /** Workspace file: {@code {"projects":[{"name":"api","root":"C:/repos/api"}, ...]}} (name optional). */
    static Map<String, Path> parseWorkspaceFile(Path file) {
        try {
            JsonNode json = new ObjectMapper().readTree(Files.readString(file));
            JsonNode projects = json.get("projects");
            if (projects == null || !projects.isArray() || projects.isEmpty()) {
                throw new IllegalArgumentException("workspace file needs a non-empty 'projects' array");
            }
            Map<String, Path> named = new LinkedHashMap<>();
            for (JsonNode project : projects) {
                JsonNode rootNode = project.get("root");
                if (rootNode == null) {
                    throw new IllegalArgumentException("workspace project entry missing 'root'");
                }
                Path root = Path.of(rootNode.asText());
                String name = project.has("name") ? project.get("name").asText()
                        : String.valueOf(root.getFileName());
                if (named.put(name, root) != null) {
                    throw new IllegalArgumentException("duplicate project name: " + name);
                }
            }
            return named;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read workspace file " + file, e);
        }
    }

    private static CodeGraphTools.Reindexer reindexer(Workspace.Project project) {
        Semaphore reindexing = new Semaphore(1);
        return scope -> {
            if (!reindexing.tryAcquire()) {
                throw new IllegalStateException("a reindex of '" + project.name() + "' is already running");
            }
            Thread.ofVirtual().name("code-graph-reindex-" + project.name()).start(() -> {
                try {
                    project.indexer().fullIndex();
                } finally {
                    reindexing.release();
                }
            });
        };
    }

    private static String argValue(String[] args, String flag, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(flag)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static int intArg(String[] args, String flag, int fallback) {
        String value = argValue(args, flag, null);
        return value == null ? fallback : Integer.parseInt(value);
    }
}
