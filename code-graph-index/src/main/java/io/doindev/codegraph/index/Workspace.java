package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.store.ManagedGraph;
import io.doindev.codegraph.storage.GraphStorage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                          ManagedGraph graph, IncrementalIndexer indexer) {
    }

    private final Map<String, Project> projects;
    private final Map<String, Watcher> watchers = new LinkedHashMap<>();
    private final Map<String, Path> reservedRoots = new LinkedHashMap<>();
    private final Map<String,Project> indexing = new LinkedHashMap<>();
    private final Map<String,Long> onboardingStarted = new LinkedHashMap<>();
    private static final int MAX_ONBOARDING = 4;
    private boolean watching;
    private boolean closed;
    private final GraphStorage storage;

    private Workspace(Map<String, Project> projects, GraphStorage storage) {
        this.projects = new LinkedHashMap<>(projects);
        this.storage = storage;
    }

    /**
     * Open a workspace over the given roots. {@code configLoader} supplies each project's
     * config (normally {@code ConfigLoader::load}); injected to keep this module's
     * dependencies unchanged.
     */
    public static Workspace open(List<Path> roots, Analyzers analyzers,
                                 Function<Path, CodeGraphConfig> configLoader) {
        return open(roots, analyzers, configLoader, false, GraphStorage.DEFAULT_BUDGET);
    }

    public static Workspace open(List<Path> roots, Analyzers analyzers,
                                 Function<Path, CodeGraphConfig> configLoader, boolean hybrid, long budget) {
        Map<String, Path> named = new LinkedHashMap<>();
        for (Path raw : roots) {
            Path root = canonicalRoot(raw);
            String base = root.getFileName() == null ? "root" : root.getFileName().toString();
            String name = base;
            int ordinal = 2;
            while (named.containsKey(name)) {
                name = base + "-" + ordinal++;
            }
            named.put(name, root);
        }
        return openNamed(named, analyzers, configLoader, hybrid, budget);
    }

    /** Open with explicit project names (workspace-file mode). Iteration order defines the default project. */
    public static Workspace openNamed(Map<String, Path> namedRoots, Analyzers analyzers,
                                      Function<Path, CodeGraphConfig> configLoader) {
        return openNamed(namedRoots, analyzers, configLoader, false, GraphStorage.DEFAULT_BUDGET);
    }

    public static Workspace openNamed(Map<String, Path> namedRoots, Analyzers analyzers,
                                      Function<Path, CodeGraphConfig> configLoader, boolean hybrid, long budget) {
        Map<String, Path> roots = new LinkedHashMap<>();
        namedRoots.forEach((name, raw) -> {
            Path root = canonicalRoot(raw);
            roots.forEach((existingName, existingRoot) ->
                    checkNotCovered(root, existingName, existingRoot));
            roots.put(name, root);
        });
        Map<String, Project> projects = new LinkedHashMap<>();
        GraphStorage storage = new GraphStorage(hybrid, budget);
        try {
            roots.forEach((name, root) -> {
                storage.checkRoot(root);
                CodeGraphConfig config = configLoader.apply(root);
                ManagedGraph graph = storage.create();
                IncrementalIndexer indexer = new IncrementalIndexer(root, analyzers, config, graph);
                projects.put(name, new Project(name, root, config, graph, indexer));
            });
            return new Workspace(projects, storage);
        } catch (RuntimeException | Error e) {
            try { storage.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
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
        if (closed) {
            throw new IllegalStateException("workspace is closed");
        }
        watching = true;
        for (Project project : projects.values()) {
            watchers.computeIfAbsent(project.name(),
                    name -> new Watcher(project.root(), project.indexer()));
        }
    }

    /**
     * Add a project at runtime: index its root and (if the workspace is already watching) start
     * a watcher for it. The name is deduplicated against existing projects. Blocks until the
     * initial index completes. Duplicate roots and children of existing or in-flight roots
     * are rejected using real filesystem paths (including symlink resolution).
     *
     * @return the created project (its {@link Project#name()} is the deduplicated name)
     */
    public Project add(String requestedName, Path rawRoot, Analyzers analyzers,
                       java.util.function.Function<Path, CodeGraphConfig> configLoader) {
        return add(requestedName,rawRoot,analyzers,configLoader,p -> {});
    }

    public Project add(String requestedName, Path rawRoot, Analyzers analyzers,
                       Function<Path,CodeGraphConfig> configLoader, java.util.function.Consumer<Project> started) {
        Path root = canonicalRoot(rawRoot);
        storage.checkRoot(root);
        String name;
        synchronized (this) {
            checkPrivateDirectories(root);
            if (closed) {
                throw new IllegalStateException("workspace is closed");
            }
            projects.values().forEach(existing ->
                    checkNotCovered(root, existing.name(), existing.root()));
            reservedRoots.forEach((existingName, existingRoot) ->
                    checkNotCovered(root, existingName, existingRoot));
            name = requestedName;
            int ordinal = 2;
            while (projects.containsKey(name) || reservedRoots.containsKey(name)) {
                name = requestedName + "-" + ordinal++;
            }
            if(reservedRoots.size()>=MAX_ONBOARDING)throw new IllegalStateException("onboarding_busy: at most four initial scans may run at once; retry after one finishes");
            reservedRoots.put(name, root);
            onboardingStarted.put(name,System.nanoTime());
        }

        Project project;
        ManagedGraph createdGraph = null;
        try {
            CodeGraphConfig config = configLoader.apply(root);
            ManagedGraph graph = storage.create();
            createdGraph = graph;
            IncrementalIndexer indexer = new IncrementalIndexer(root, analyzers, config, graph);
            project = new Project(name, root, config, graph, indexer);
            synchronized(this) { indexing.put(name,project); }
            started.accept(project);
            project.indexer().fullIndex();
        } catch (RuntimeException | Error e) {
            if (createdGraph != null) {
                try { createdGraph.close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            synchronized (this) {
                reservedRoots.remove(name);
                indexing.remove(name); onboardingStarted.remove(name);
            }
            throw e;
        }

        synchronized (this) {
            reservedRoots.remove(name);
            indexing.remove(name); onboardingStarted.remove(name);
            if (closed) {
                project.graph().close();
                throw new IllegalStateException("workspace is closed");
            }
            if (watching) {
                try {
                    watchers.computeIfAbsent(project.name(), n -> new Watcher(project.root(), project.indexer()));
                } catch (RuntimeException | Error e) {
                    try { project.graph().close(); } catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
                    throw e;
                }
            }
            projects.put(name, project);
        }
        return project;
    }

    /** Initial scans only: separate from ready projects and never a graph read or TTL touch. */
    public synchronized List<Map<String,Object>> onboardingStatus() {
        var out=new java.util.ArrayList<Map<String,Object>>();
        for(String name:reservedRoots.keySet()) {
            var row=new LinkedHashMap<String,Object>(); var project=indexing.get(name);
            row.put("name",name); row.put("state","indexing"); row.put("queryable",false);
            row.put("progress",project==null?Map.of("phase","configuration"):project.indexer().fullIndexProgress());
            row.put("elapsedMs",(System.nanoTime()-onboardingStarted.get(name))/1_000_000);
            out.add(row);
        }
        return List.copyOf(out);
    }

    private final java.util.Set<Path> privateDirectories = new java.util.HashSet<>();

    /** Prevent graph indexing from reading application-owned credentials/configuration. */
    public synchronized void protectDirectory(Path directory) {
        Path real;
        try {
            Path existing = directory.toAbsolutePath().normalize();
            java.util.ArrayDeque<Path> suffix = new java.util.ArrayDeque<>();
            while (!Files.exists(existing)) { suffix.addFirst(existing.getFileName()); existing = existing.getParent(); }
            real = existing.toRealPath();
            for (Path part : suffix) real = real.resolve(part);
        } catch (IOException e) { throw new IllegalArgumentException("Cannot resolve protected directory", e); }
        for (Project project : projects.values())
            if (real.startsWith(project.root()) || project.root().startsWith(real))
                throw new IllegalArgumentException("Application data directory must not overlap an indexed root");
        for (Path root : reservedRoots.values())
            if (real.startsWith(root) || root.startsWith(real))
                throw new IllegalArgumentException("Application data directory overlaps an onboarding root");
        privateDirectories.add(real);
    }

    private void checkPrivateDirectories(Path root) {
        for (Path directory : privateDirectories)
            if (directory.startsWith(root) || root.startsWith(directory))
                throw new IllegalArgumentException("Project root overlaps protected application data");
    }

    private static Path canonicalRoot(Path rawRoot) {
        try {
            Path root = rawRoot.toRealPath();
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("not a directory: " + rawRoot);
            }
            return root;
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot access project directory: " + rawRoot, e);
        }
    }

    private static void checkNotCovered(Path root, String existingName, Path existingRoot) {
        if (root.equals(existingRoot)) {
            throw new IllegalArgumentException("directory is already onboarded or being onboarded "
                    + "as project '" + existingName + "': " + existingRoot);
        }
        if (root.startsWith(existingRoot)) {
            throw new IllegalArgumentException("cannot onboard '" + root + "': it is a child path "
                    + "of project '" + existingName + "' (onboarded or being onboarded at "
                    + existingRoot + ")");
        }
    }

    /**
     * Remove a project from this workspace at runtime: its watcher stops and its in-memory
     * graph is dropped for collection. Source on disk is never touched (nor are CLI snapshot
     * caches or database mirrors).
     *
     * @return {@code false} if the name is unknown
     */
    public synchronized boolean remove(String name) {
        if (!projects.containsKey(name)) {
            return false;
        }
        Watcher watcher = watchers.remove(name);
        if (watcher != null) {
            watcher.close();
        }
        projects.remove(name).graph().close();
        return true;
    }

    /** The project registered under {@code name}, or {@code null}. */
    public synchronized Project project(String name) {
        return projects.get(name);
    }

    /** First-registered project, or {@code null} when the workspace is empty. */
    public synchronized Project defaultProject() {
        return projects.values().stream().findFirst().orElse(null);
    }

    public synchronized List<Project> projects() {
        return List.copyOf(projects.values());
    }

    public synchronized List<String> names() {
        return List.copyOf(projects.keySet());
    }

    /** Visible to package tests: watchers must follow empty/add/remove transitions. */
    synchronized int activeWatcherCount() {
        return watchers.size();
    }

    public io.doindev.codegraph.store.DocumentStore contextDocuments() { return storage.documents(); }

    public Map<String, Object> storageStatus() { return storage.status(); }
    public void graphMemory(long bytes) { storage.resize(bytes); }

    @Override
    public synchronized void close() {
        closed = true;
        watching = false;
        watchers.values().forEach(Watcher::close);
        watchers.clear();
        reservedRoots.clear(); indexing.clear(); onboardingStarted.clear();
        storage.close();
        projects.clear();
    }
}
