package io.doindev.codegraph.mcp;

import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.tools.CodeGraphTools;
import io.doindev.codegraph.tools.WorkspaceTools;

import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/** Shared onboarding pipeline for MCP and UI callers, independent of UI administration flags. */
public final class ProjectOnboarding {
    private final Workspace workspace;
    private final WorkspaceTools registry;
    private final Analyzers analyzers;

    public ProjectOnboarding(Workspace workspace, WorkspaceTools registry, Analyzers analyzers) {
        this.workspace = workspace;
        this.registry = registry;
        this.analyzers = analyzers;
    }

    /** Blocks through initial indexing and tool registration, returning the assigned project name. */
    public String add(String path) {
        Workspace.Project project = index(path);
        try {
            register(project);
            return project.name();
        } catch (RuntimeException | Error e) {
            if (!registry.removeProject(project.name())) {
                workspace.remove(project.name());
            }
            throw e;
        }
    }

    /** Split from publication so the UI can cancel an in-flight scan before registering tools. */
    Workspace.Project index(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must be a non-blank directory path string");
        }
        Path root = Path.of(path).toAbsolutePath().normalize();
        String requested = root.getFileName() == null ? "project" : root.getFileName().toString();
        // Preserve the raw path for real-path validation (a symlink followed by '..' is significant).
        return workspace.add(requested, Path.of(path), analyzers, ConfigLoader::load);
    }

    public long register(Workspace.Project project) {
        Semaphore reindexing = new Semaphore(1);
        return registry.addProject(new CodeGraphTools.ProjectTools(project.name(), project.graph(),
                project.config(), project.root(), scope -> {
                    var use = registry.lifecycle().use(project.name());
                    if (use == null) throw new IllegalStateException("unknown or expired project: " + project.name());
                    if (workspace.project(project.name()) != project) {
                        use.close();
                        throw new IllegalStateException("project was removed or replaced: " + project.name());
                    }
                    if (!reindexing.tryAcquire()) {
                        use.close();
                        throw new IllegalStateException(
                                "a reindex of '" + project.name() + "' is already running");
                    }
                    try {
                        Thread.ofVirtual().name("code-graph-reindex-" + project.name()).start(() -> {
                            try {
                                project.indexer().fullIndex();
                            } finally {
                                reindexing.release();
                                use.close();
                            }
                        });
                    } catch (RuntimeException | Error e) {
                        reindexing.release();
                        use.close();
                        throw e;
                    }
                }));
    }
}
