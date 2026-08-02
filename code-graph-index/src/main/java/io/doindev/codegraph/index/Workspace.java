package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A set of independently indexed projects served by one process: each project owns its own
 * graph, config, indexer and (optionally) watcher, so blast scores, drift and dead-code never
 * bleed across repos. Project names default to the root directory name (deduplicated with a
 * numeric suffix) and are the values agents pass in the tools' {@code project} parameter.
 */
public final class Workspace implements AutoCloseable {

    /** One indexed project. */
    public record Project(String name, Path root, CodeGraphConfig config,
                          InMemoryCodeGraph graph, IncrementalIndexer indexer) {
    }

    private final Map<String, Project> projects;
    private final Map<String, Watcher> watchers = new LinkedHashMap<>();

    private Workspace(Map<String, Project> projects) {
        this.projects = new LinkedHashMap<>(projects);
    }

    /**
     * Open a workspace over the given roots. {@code configLoader} supplies each project's
     * config (normally {@code ConfigLoader::load}); injected to keep this module's
     * dependencies unchanged.
     */
    public static Workspace open(List<Path> roots, Analyzers analyzers,
                                 Function<Path, CodeGraphConfig> configLoader) {
        Map<String, Path> named = new LinkedHashMap<>();
        for (Path raw : roots) {
            Path root = raw.toAbsolutePath().normalize();
            String base = root.getFileName() == null ? "root" : root.getFileName().toString();
            String name = base;
            int ordinal = 2;
            while (named.containsKey(name)) {
                name = base + "-" + ordinal++;
            }
            named.put(name, root);
        }
        return openNamed(named, analyzers, configLoader);
    }

    /** Open with explicit project names (workspace-file mode). Iteration order defines the default project. */
    public static Workspace openNamed(Map<String, Path> namedRoots, Analyzers analyzers,
                                      Function<Path, CodeGraphConfig> configLoader) {
        if (namedRoots.isEmpty()) {
            throw new IllegalArgumentException("workspace needs at least one project root");
        }
        Map<String, Project> projects = new LinkedHashMap<>();
        namedRoots.forEach((name, raw) -> {
            Path root = raw.toAbsolutePath().normalize();
            CodeGraphConfig config = configLoader.apply(root);
            InMemoryCodeGraph graph = new InMemoryCodeGraph();
            IncrementalIndexer indexer = new IncrementalIndexer(root, analyzers, config, graph);
            projects.put(name, new Project(name, root, config, graph, indexer));
        });
        return new Workspace(projects);
    }

    /** Fully index every project, projects in parallel. Returns per-project results in order. */
    public Map<String, FullIndexer.Result> fullIndexAll() {
        Map<String, CompletableFuture<FullIndexer.Result>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Project project : projects()) {
                futures.put(project.name(),
                        CompletableFuture.supplyAsync(() -> project.indexer().fullIndex(), executor));
            }
        }
        Map<String, FullIndexer.Result> results = new LinkedHashMap<>();
        futures.forEach((name, future) -> results.put(name, future.join()));
        return results;
    }

    /** Start a filesystem watcher per project. */
    public synchronized void watchAll() {
        for (Project project : projects.values()) {
            watchers.computeIfAbsent(project.name(),
                    name -> new Watcher(project.root(), project.indexer()));
        }
    }

    /**
     * Add a project at runtime: index its root and (if the workspace is already watching) start
     * a watcher for it. The name is deduplicated against existing projects. Blocks until the
     * initial index completes.
     *
     * @return the created project (its {@link Project#name()} is the deduplicated name)
     */
    public Project add(String requestedName, Path rawRoot, Analyzers analyzers,
                       java.util.function.Function<Path, CodeGraphConfig> configLoader) {
        Path root = rawRoot.toAbsolutePath().normalize();
        Project project;
        synchronized (this) {
            String name = requestedName;
            int ordinal = 2;
            while (projects.containsKey(name)) {
                name = requestedName + "-" + ordinal++;
            }
            CodeGraphConfig config = configLoader.apply(root);
            InMemoryCodeGraph graph = new InMemoryCodeGraph();
            IncrementalIndexer indexer = new IncrementalIndexer(root, analyzers, config, graph);
            project = new Project(name, root, config, graph, indexer);
            projects.put(name, project);
        }
        project.indexer().fullIndex();
        synchronized (this) {
            if (!watchers.isEmpty()) {
                watchers.computeIfAbsent(project.name(),
                        n -> new Watcher(project.root(), project.indexer()));
            }
        }
        return project;
    }

    /**
     * Remove a project from this workspace at runtime: its watcher stops and its in-memory
     * graph is dropped for collection. Source on disk is never touched (nor are CLI snapshot
     * caches or database mirrors). The last remaining project cannot be removed.
     *
     * @return {@code false} if the name is unknown
     */
    public synchronized boolean remove(String name) {
        if (!projects.containsKey(name)) {
            return false;
        }
        if (projects.size() == 1) {
            throw new IllegalStateException("cannot remove the last project: " + name);
        }
        Watcher watcher = watchers.remove(name);
        if (watcher != null) {
            watcher.close();
        }
        projects.remove(name);
        return true;
    }

    /** The project registered under {@code name}, or {@code null}. */
    public synchronized Project project(String name) {
        return projects.get(name);
    }

    /** First-registered project — the default when tools omit the {@code project} parameter. */
    public synchronized Project defaultProject() {
        return projects.values().iterator().next();
    }

    public synchronized List<Project> projects() {
        return List.copyOf(projects.values());
    }

    public synchronized List<String> names() {
        return List.copyOf(projects.keySet());
    }

    @Override
    public synchronized void close() {
        watchers.values().forEach(Watcher::close);
        watchers.clear();
    }
}
