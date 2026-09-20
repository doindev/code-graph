package io.doindev.codegraph.mcp;

import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;
import io.doindev.codegraph.tools.WorkspaceTools;
import io.doindev.codegraph.viz.VizControl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The live {@link VizControl} backing the viz UI: reflects the running {@link Workspace} and
 * mutates it (reindex / add / remove) through the same {@link WorkspaceTools} registry the MCP
 * tools use, so a change in the browser is instantly visible to agents and vice versa.
 * Filesystem browsing and mutation are gated by {@code mutable} — enabled for the loopback
 * stdio server, disabled by default on the shared HTTP server.
 */
public final class WorkspaceVizControl implements VizControl {

    @Override public java.util.Map<String, Object> storageStatus() { return workspace.storageStatus(); }
    @Override public void graphMemory(String value) {
        workspace.graphMemory(io.doindev.codegraph.storage.GraphStorage.parseBudget(value));
    }

    private static final int MAX_BROWSE_ENTRIES = 500;

    private final Workspace workspace;
    private final WorkspaceTools registry;
    private final ProjectOnboarding onboarding;
    private final String mcpEndpoint;
    private final boolean mutable;

    public WorkspaceVizControl(Workspace workspace, WorkspaceTools registry, Analyzers analyzers,
                               String mcpEndpoint, boolean mutable) {
        this.workspace = workspace;
        this.registry = registry;
        this.onboarding = new ProjectOnboarding(workspace, registry, analyzers);
        this.mcpEndpoint = mcpEndpoint;
        this.mutable = mutable;
    }

    @Override
    public List<VizProject> projects() {
        List<VizProject> out = new ArrayList<>();
        for (String name : registry.projectNames()) {
            Workspace.Project project = workspace.project(name);
            if (project != null) out.add(new VizProject(project.name(), project.graph(), project.config()));
        }
        return out;
    }

    @Override
    public String mcpEndpoint() {
        return mcpEndpoint;
    }

    @Override public ProjectLifecycle lifecycle() { return registry.lifecycle(); }

    @Override
    public boolean mutable() {
        return mutable;
    }

    @Override
    public boolean reindex(String projectName) {
        return startReindex(projectName) != null;
    }

    @Override
    public boolean remove(String projectName) {
        // The registry's removal listener mirrors the change into the workspace and stops its watcher.
        return registry.removeProject(projectName);
    }

    @Override
    public String add(String path) {
        return onboarding.add(path);
    }

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
        volatile long instanceId;
        volatile java.util.function.Supplier<java.util.Map<String,Object>> progress = () -> java.util.Map.of("phase","configuration");

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
            return new VizControl.AddJob(id, name, state, elapsedMs(), error, progress.get());
        }

        void finishProgress() {
            var terminal = java.util.Map.copyOf(progress.get());
            progress = () -> terminal; // A retained job must never pin its indexer/graph after TTL removal.
        }
    }

    @Override
    public AddJob startAdd(String path) {
        pruneJobs();
        Path root = Path.of(path);
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("not a directory: " + path);
        }
        String requested = root.getFileName() == null ? "project" : root.getFileName().toString();
        String id = java.util.UUID.randomUUID().toString();
        Job job = new Job(id, Kind.ADD, requested);
        jobs.put(id, job);
        Thread.ofVirtual().name("code-graph-viz-add-" + requested).start(() -> {
            Workspace.Project project = null;
            try {
                // the scan runs to completion here (it is not cooperatively interruptible)
                project = onboarding.index(path, pending -> {
                    job.name=pending.name();
                    job.progress=pending.indexer()::fullIndexProgress;
                });
                job.name = project.name();
                // Serialize publication with cancellation: cancel must also work for the first
                // project and cannot race between the cancellation check and registration.
                synchronized (job) {
                    if (job.cancelRequested) {
                        workspace.remove(project.name());
                        job.state = "cancelled";
                    } else {
                        job.instanceId = onboarding.register(project);
                        job.state = "ready";
                    }
                }
            } catch (RuntimeException e) {
                if (project != null && !registry.removeProject(project.name())) {
                    workspace.remove(project.name());
                }
                job.error = e.getMessage() == null ? e.toString() : e.getMessage();
                job.state = "error";
            } finally {
                job.finishProgress();
                job.finishedNanos = System.nanoTime();
            }
        });
        return job.snapshot();
    }

    @Override
    public AddJob startReindex(String projectName) {
        pruneJobs();
        var use = lifecycle().use(projectName);
        if (use == null) return null;
        Workspace.Project project = workspace.project(projectName);
        if (project == null) {
            use.close();
            return null;
        }
        String id = java.util.UUID.randomUUID().toString();
        Job job = new Job(id, Kind.REINDEX, projectName);
        job.progress=project.indexer()::fullIndexProgress;
        job.instanceId = use.instanceId();
        jobs.put(id, job);
        try {
        Thread.ofVirtual().name("code-graph-viz-reindex-" + projectName).start(() -> {
            try {
                // not interruptible; on cancel we simply mark the job — the fresh graph still lands
                project.indexer().fullIndex();
                job.state = job.cancelRequested ? "cancelled" : "ready";
            } catch (RuntimeException e) {
                job.error = e.getMessage() == null ? e.toString() : e.getMessage();
                job.state = "error";
            } finally {
                job.finishProgress();
                job.finishedNanos = System.nanoTime();
                use.close();
            }
        });
        } catch (RuntimeException | Error e) {
            jobs.remove(id);
            use.close();
            throw e;
        }
        return job.snapshot();
    }

    @Override
    public AddJob addStatus(String id) {
        Job job = jobs.get(id);
        if (job == null) return null;
        if (job.instanceId != 0) {
            try (var use = lifecycle().use(job.name, job.instanceId)) {
                if (use == null && (job.state.equals("cancelled") || job.state.equals("error"))) return job.snapshot();
                if (use == null) return new AddJob(job.id, job.name, "error", job.elapsedMs(),
                        "project expired or was removed; onboard it again");
                return job.snapshot();
            }
        }
        return job.snapshot();
    }

    @Override
    public boolean cancelAdd(String id) {
        Job job = jobs.get(id);
        if (job == null) {
            return false;
        }
        synchronized (job) {
            job.cancelRequested = true;
            if (job.kind == Kind.REINDEX && job.instanceId != 0) {
                try (var use = lifecycle().use(job.name, job.instanceId)) { /* cancellation is project activity */ }
            }
            // ADD only: if the scan already finished and registered the project, retract it.
            // REINDEX: nothing to retract — it refreshes the same live graph.
            if (job.kind == Kind.ADD && job.state.equals("ready")) {
                lifecycle().remove(job.name, job.instanceId);
                job.state = "cancelled";
            }
        }
        return true;
    }

    /** Jobs hold metadata only; bound terminal history so repeated onboarding cannot leak it. */
    private void pruneJobs() {
        jobs.values().stream().filter(job -> job.finishedNanos != 0)
                .sorted(Comparator.comparingLong(job -> -job.finishedNanos))
                .skip(128).forEach(job -> jobs.remove(job.id, job));
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
