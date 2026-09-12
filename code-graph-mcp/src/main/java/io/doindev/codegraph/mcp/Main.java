package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.FullIndexer;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;
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
import java.time.Duration;

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
        Duration projectTtl = projectTtl(args);
        Analyzers analyzers = Analyzers.discover();
        if (analyzers.isEmpty()) {
            System.err.println("code-graph: no language analyzers on classpath");
            System.exit(1);
        }

        Workspace workspace = openWorkspace(args, analyzers);
        io.doindev.codegraph.dba.DbaRuntime dba = openDba(args, workspace);
        long start = System.nanoTime();
        Map<String, FullIndexer.Result> results = workspace.fullIndexAll();
        results.forEach((name, result) -> System.err.printf(
                "code-graph: [%s] indexed %d files, %d symbols, %d edges%n",
                name, result.filesIndexed(), result.symbolCount(), result.edgeCount()));
        System.err.printf("code-graph: %d project(s) ready in %d ms%n",
                results.size(), (System.nanoTime() - start) / 1_000_000);
        workspace.watchAll();

        // the registry drives the routers; a viz-triggered removal must stop the watcher too
        WorkspaceTools registry = CodeGraphTools.workspace(List.of(), workspace::remove);
        registry.lifecycle().setTtl(projectTtl);
        ProjectOnboarding onboarding = new ProjectOnboarding(workspace, registry, analyzers);
        workspace.projects().forEach(onboarding::register);
        List<GraphTool> tools = new ArrayList<>(registry.tools(onboarding::add));
        if(dba!=null)tools.addAll(DbaMcpTools.tools(dba,()->dba.authenticateAgent(System.getenv("CODE_GRAPH_DBA_AGENT_TOKEN"))));

        VizServer viz = null;
        int vizPort = intArg(args, "--viz", -1);
        if (vizPort >= 0) {
            // stdio viz binds to loopback, so actions (add/remove/reindex/browse) are safe on by default
            boolean admin = !hasFlag(args, "--viz-readonly");
            VizControl control = new WorkspaceVizControl(workspace, registry, analyzers, "stdio", admin);
            viz = VizServer.start(control, java.net.InetAddress.getLoopbackAddress(), vizPort, dba);
            System.err.println("code-graph: viz at http://localhost:" + viz.port() + "/"
                    + (admin ? " (actions enabled)" : " (read-only)"));
        }

        registry.lifecycle().start();
        System.err.println("code-graph: project idle TTL " + projectTtl);
        try (workspace; registry;
             CodeGraphMcpServer ignored = CodeGraphMcpServer.serveStdio("code-graph", "0.0.1", tools)) {
            System.err.println("code-graph: watching onboarded projects for changes; serving MCP over stdio");
            Thread.currentThread().join();
        } finally {
            if (dba != null) dba.close();
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

    public static io.doindev.codegraph.dba.DbaRuntime openDba(String[] args, Workspace workspace) {
        var configuration = io.doindev.codegraph.dba.DbaConfig.parse(args);
        if (configuration.isEmpty()) return null;
        workspace.protectDirectory(configuration.get().directory());
        try {
            boolean ui=intArg(args,"--viz",-1)>=0;
            var runtime = new io.doindev.codegraph.dba.DbaRuntime(configuration.get(),ui);
            if(ui)System.err.println("code-graph: DBA direct local browser access enabled");
            return runtime;
        } catch (IOException e) { throw new UncheckedIOException("Cannot start DBA runtime", e); }
    }

    /** Shared by the stdio and HTTP entry points: resolve --workspace / repeated --root flags. */
    public static Workspace openWorkspace(String[] args, Analyzers analyzers) {
        String mode = argValue(args, "--graph-storage", "memory");
        if (!mode.equals("memory") && !mode.equals("hybrid"))
            throw new IllegalArgumentException("--graph-storage must be memory or hybrid");
        long budget = io.doindev.codegraph.storage.GraphStorage.parseBudget(argValue(args, "--graph-memory", "1g"));
        boolean hybrid = mode.equals("hybrid");
        String workspaceFile = argValue(args, "--workspace", null);
        if (workspaceFile != null) {
            return Workspace.openNamed(parseWorkspaceFile(Path.of(workspaceFile)), analyzers,
                    ConfigLoader::load, hybrid, budget);
        }
        List<Path> roots = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--root")) {
                roots.add(Path.of(args[i + 1]));
            }
        }
        if (roots.isEmpty()) {
            String envRoot = System.getenv("CODE_GRAPH_ROOT");
            if (envRoot != null && !envRoot.isBlank()) {
                roots.add(Path.of(envRoot));
            }
        }
        Workspace workspace = Workspace.open(roots, analyzers, ConfigLoader::load, hybrid, budget);
        System.err.println("code-graph: graph storage " + workspace.storageStatus());
        return workspace;
    }

    /** Workspace file: {@code {"projects":[{"name":"api","root":"C:/repos/api"}, ...]}} (name optional). */
    static Map<String, Path> parseWorkspaceFile(Path file) {
        try {
            JsonNode json = new ObjectMapper().readTree(Files.readString(file));
            JsonNode projects = json.get("projects");
            if (projects == null || !projects.isArray()) {
                throw new IllegalArgumentException("workspace file needs a 'projects' array");
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

    public static Duration projectTtl(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--project-ttl")) {
                if (i + 1 == args.length) throw new IllegalArgumentException("--project-ttl needs a duration");
                return ProjectLifecycle.parseTtl(args[i + 1]);
            }
        }
        return ProjectLifecycle.DEFAULT_TTL;
    }

    private static String argValue(String[] args, String flag, String fallback) {
        if (args.length > 0 && args[args.length - 1].equals(flag))
            throw new IllegalArgumentException(flag + " needs a value");
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
