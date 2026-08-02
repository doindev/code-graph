package io.doindev.codegraph.tools;

import io.doindev.codegraph.query.GraphQuery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Live multi-project tool registry: the routers consult these maps on every call, so removing
 * a project takes effect immediately across all tools. Removal drops the project's tool
 * instances and graph reference and notifies the listener (which stops the watcher and updates
 * the viz layer); indexed source on disk is never touched.
 */
public final class WorkspaceTools {

    /** Notified after a project is removed from the registry. */
    @FunctionalInterface
    public interface RemovalListener {
        void removed(String project);
    }

    private final Map<String, Map<String, GraphTool>> byToolThenProject = new LinkedHashMap<>();
    private final Map<String, GraphQuery> graphs = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Object lock = new Object();
    private final RemovalListener listener;
    private volatile String defaultProject;
    private final List<GraphTool> tools;

    WorkspaceTools(List<CodeGraphTools.ProjectTools> projects, RemovalListener listener) {
        if (projects.isEmpty()) {
            throw new IllegalArgumentException("workspace needs at least one project");
        }
        this.listener = listener;
        this.defaultProject = projects.get(0).name();
        for (CodeGraphTools.ProjectTools project : projects) {
            graphs.put(project.name(), project.graph());
            for (GraphTool tool : CodeGraphTools.standard(project.graph(), project.config(),
                    project.root(), project.reindexer())) {
                byToolThenProject
                        .computeIfAbsent(tool.spec().name(),
                                k -> Collections.synchronizedMap(new LinkedHashMap<>()))
                        .put(project.name(), tool);
            }
        }
        Supplier<String> defaultName = () -> defaultProject;
        List<GraphTool> built = new ArrayList<>();
        byToolThenProject.values().forEach(perProject ->
                built.add(new RouterTool(perProject, defaultName)));
        built.add(new ListProjectsTool(graphs, defaultName));
        built.add(new RemoveProjectTool(this));
        this.tools = List.copyOf(built);
    }

    /** The full tool set (routers + {@code list_projects} + {@code remove_project}). */
    public List<GraphTool> tools() {
        return tools;
    }

    /**
     * Register a project's tools into every router at runtime (after the workspace has indexed
     * it). Safe to call while the server is serving; subsequent tool calls can route to it.
     */
    public void addProject(CodeGraphTools.ProjectTools project) {
        synchronized (lock) {
            graphs.put(project.name(), project.graph());
            for (GraphTool tool : CodeGraphTools.standard(project.graph(), project.config(),
                    project.root(), project.reindexer())) {
                Map<String, GraphTool> perProject = byToolThenProject.get(tool.spec().name());
                if (perProject != null) {
                    perProject.put(project.name(), tool);
                }
            }
        }
    }

    public List<String> projectNames() {
        synchronized (graphs) {
            return List.copyOf(graphs.keySet());
        }
    }

    /**
     * Remove a project from every router. The last remaining project cannot be removed.
     *
     * @return {@code false} when the name is unknown
     */
    public boolean removeProject(String name) {
        synchronized (lock) {
            if (!graphs.containsKey(name)) {
                return false;
            }
            if (graphs.size() == 1) {
                throw new IllegalStateException("cannot remove the last project: " + name);
            }
            graphs.remove(name);
            byToolThenProject.values().forEach(perProject -> perProject.remove(name));
            if (name.equals(defaultProject)) {
                defaultProject = projectNames().get(0);
            }
        }
        listener.removed(name);
        return true;
    }
}
