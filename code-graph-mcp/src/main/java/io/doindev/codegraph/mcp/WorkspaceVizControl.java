package io.doindev.codegraph.mcp;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.tools.CodeGraphTools;
import io.doindev.codegraph.tools.WorkspaceTools;
import io.doindev.codegraph.viz.VizControl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

/**
 * The live {@link VizControl} backing the viz UI: reflects the running {@link Workspace} and
 * mutates it (reindex / add / remove) through the same {@link WorkspaceTools} registry the MCP
 * tools use, so a change in the browser is instantly visible to agents and vice versa.
 * Filesystem browsing and mutation are gated by {@code mutable} — enabled for the loopback
 * stdio server, disabled by default on the shared HTTP server.
 */
public final class WorkspaceVizControl implements VizControl {

    private static final int MAX_BROWSE_ENTRIES = 500;

    private final Workspace workspace;
    private final WorkspaceTools registry;
    private final Analyzers analyzers;
    private final String mcpEndpoint;
    private final boolean mutable;

    public WorkspaceVizControl(Workspace workspace, WorkspaceTools registry, Analyzers analyzers,
                               String mcpEndpoint, boolean mutable) {
        this.workspace = workspace;
        this.registry = registry;
        this.analyzers = analyzers;
        this.mcpEndpoint = mcpEndpoint;
        this.mutable = mutable;
    }

    @Override
    public List<VizProject> projects() {
        List<VizProject> out = new ArrayList<>();
        for (Workspace.Project project : workspace.projects()) {
            out.add(new VizProject(project.name(), project.graph(), project.config()));
        }
        return out;
    }

    @Override
    public String mcpEndpoint() {
        return mcpEndpoint;
    }

    @Override
    public boolean mutable() {
        return mutable;
    }

    @Override
    public boolean reindex(String projectName) {
        Workspace.Project project = workspace.project(projectName);
        if (project == null) {
            return false;
        }
        // run off the request thread; the UI polls /status for the new generation
        Thread.ofVirtual().name("code-graph-viz-reindex-" + projectName)
                .start(() -> project.indexer().fullIndex());
        return true;
    }

    @Override
    public boolean remove(String projectName) {
        // remove from the tool registry first (stops routing), which the workspace mirrors
        boolean removed = registry.removeProject(projectName);
        if (removed) {
            workspace.remove(projectName);
        }
        return removed;
    }

    @Override
    public String add(String path) {
        register(indexNewProject(path));
        return lastAddedName;
    }

    private volatile String lastAddedName;

    /** In-flight and recently-finished add jobs, keyed by id. */
    private final java.util.concurrent.ConcurrentHashMap<String, Job> jobs =
            new java.util.concurrent.ConcurrentHashMap<>();

    private enum Kind { ADD, REINDEX }

    private static final class Job {
        final String id;
        final Kind kind;
        volatile String name;
        volatile String state = "indexing"; // indexing | ready | cancelled | error
        volatile String error;
        volatile boolean cancelRequested;
        final long startNanos = System.nanoTime();
        volatile long finishedNanos;

        Job(String id, Kind kind, String name) {
            this.id = id;
            this.kind = kind;
            this.name = name;
        }

        long elapsedMs() {
            long end = finishedNanos != 0 ? finishedNanos : System.nanoTime();
            return (end - startNanos) / 1_000_000;
        }

        VizControl.AddJob snapshot() {
            return new VizControl.AddJob(id, name, state, elapsedMs(), error);
        }
    }

    @Override
    public AddJob startAdd(String path) {
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + path);
        }
        String requested = root.getFileName() == null ? "project" : root.getFileName().toString();
        String id = java.util.UUID.randomUUID().toString();
        Job job = new Job(id, Kind.ADD, requested);
        jobs.put(id, job);
        Thread.ofVirtual().name("code-graph-viz-add-" + requested).start(() -> {
            try {
                // the scan runs to completion here (it is not cooperatively interruptible)
                Workspace.Project project = workspace.add(requested, root, analyzers, ConfigLoader::load);
                job.name = project.name();
                if (job.cancelRequested) {
                    // cancelled during the scan: discard the result, don't expose the project
                    workspace.remove(project.name());
                    job.state = "cancelled";
                } else {
                    register(project);
                    job.state = "ready";
                }
            } catch (RuntimeException e) {
                job.error = e.getMessage() == null ? e.toString() : e.getMessage();
                job.state = "error";
            } finally {
                job.finishedNanos = System.nanoTime();
            }
        });
        return job.snapshot();
    }

    @Override
    public AddJob startReindex(String projectName) {
        Workspace.Project project = workspace.project(projectName);
        if (project == null) {
            return null;
        }
        String id = java.util.UUID.randomUUID().toString();
        Job job = new Job(id, Kind.REINDEX, projectName);
        jobs.put(id, job);
        Thread.ofVirtual().name("code-graph-viz-reindex-" + projectName).start(() -> {
            try {
                // not interruptible; on cancel we simply mark the job — the fresh graph still lands
                project.indexer().fullIndex();
                job.state = job.cancelRequested ? "cancelled" : "ready";
            } catch (RuntimeException e) {
                job.error = e.getMessage() == null ? e.toString() : e.getMessage();
                job.state = "error";
            } finally {
                job.finishedNanos = System.nanoTime();
            }
        });
        return job.snapshot();
    }

    @Override
    public AddJob addStatus(String id) {
        Job job = jobs.get(id);
        return job == null ? null : job.snapshot();
    }

    @Override
    public boolean cancelAdd(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return false;
        }
        job.cancelRequested = true;
        // ADD only: if the scan already finished and registered the project, retract it.
        // REINDEX: nothing to retract — the reindex refreshes the same live graph, which is fine.
        if (job.kind == Kind.ADD && job.state.equals("ready")) {
            remove(job.name);
            job.state = "cancelled";
        }
        return true;
    }

    /** Index a directory synchronously into a new workspace project (no registry/watcher wiring). */
    private Workspace.Project indexNewProject(String path) {
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + path);
        }
        String requested = root.getFileName() == null ? "project" : root.getFileName().toString();
        Workspace.Project project = workspace.add(requested, root, analyzers, ConfigLoader::load);
        lastAddedName = project.name();
        return project;
    }

    /** Wire a scanned project's tools into the live registry so agents and the viz can query it. */
    private void register(Workspace.Project project) {
        Semaphore reindexing = new Semaphore(1);
        registry.addProject(new CodeGraphTools.ProjectTools(project.name(), project.graph(),
                project.config(), project.root(), scope -> {
                    if (!reindexing.tryAcquire()) {
                        throw new IllegalStateException(
                                "a reindex of '" + project.name() + "' is already running");
                    }
                    Thread.ofVirtual().start(() -> {
                        try {
                            project.indexer().fullIndex();
                        } finally {
                            reindexing.release();
                        }
                    });
                }));
    }

    @Override
    public DirListing browse(String path) {
        Path dir = (path == null || path.isBlank()) ? defaultBrowseRoot() : Path.of(path);
        dir = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("not a directory: " + dir);
        }
        List<DirListing.Entry> entries = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory)
                    .filter(child -> {
                        String name = child.getFileName().toString();
                        return !name.startsWith(".") || looksLikeRepo(child); // hide dotdirs unless a repo
                    })
                    .sorted(Comparator.comparing(c -> c.getFileName().toString().toLowerCase()))
                    .limit(MAX_BROWSE_ENTRIES)
                    .forEach(child -> entries.add(new DirListing.Entry(
                            child.getFileName().toString(), child.toString(), looksLikeRepo(child))));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list " + dir, e);
        }
        Path parent = dir.getParent();
        return new DirListing(dir.toString(), parent == null ? null : parent.toString(), entries);
    }

    private static boolean looksLikeRepo(Path dir) {
        return Files.exists(dir.resolve(".git")) || Files.exists(dir.resolve("pom.xml"))
                || Files.exists(dir.resolve("package.json")) || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("go.mod")) || Files.exists(dir.resolve("Cargo.toml"))
                || Files.exists(dir.resolve("pyproject.toml")) || Files.exists(dir.resolve("build.gradle.kts"));
    }

    /** Start browsing at the first project's parent — a familiar neighborhood of repos. */
    private Path defaultBrowseRoot() {
        List<Workspace.Project> projects = workspace.projects();
        if (!projects.isEmpty()) {
            Path parent = projects.get(0).root().getParent();
            if (parent != null) {
                return parent;
            }
        }
        return Path.of(System.getProperty("user.home", "."));
    }
}
