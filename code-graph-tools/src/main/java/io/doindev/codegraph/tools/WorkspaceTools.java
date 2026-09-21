package io.doindev.codegraph.tools;

import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.lifecycle.ProjectLifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Live multi-project tool registry: the routers consult these maps on every call, so removing
 * a project takes effect immediately across all tools. Removal drops the project's tool
 * instances and graph reference and notifies the listener (which stops the watcher and updates
 * the viz layer); indexed source on disk is never touched.
 */
public final class WorkspaceTools implements AutoCloseable {

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
    private final ProjectLifecycle lifecycle;
    private volatile Supplier<List<Map<String,Object>>> onboardingStatus = List::of;
    public void onboardingStatus(Supplier<List<Map<String,Object>>> source) { onboardingStatus=java.util.Objects.requireNonNull(source); }

    WorkspaceTools(List<CodeGraphTools.ProjectTools> projects, RemovalListener listener) {
        this(projects, listener, System::nanoTime, java.time.Instant::now);
    }

    WorkspaceTools(List<CodeGraphTools.ProjectTools> projects, RemovalListener listener,
                   java.util.function.LongSupplier ticker, Supplier<java.time.Instant> clock) {
        this.listener = listener;
        this.lifecycle = new ProjectLifecycle(this::removeData, ticker, clock);
        this.defaultProject = projects.isEmpty() ? null : projects.get(0).name();

        // Build the stable router catalog even for an empty workspace. MCP clients can discover
        // the full tool set once, then start using it as soon as a project is onboarded.
        List<GraphTool> templates = projects.isEmpty()
                ? CodeGraphTools.standard(new InMemoryCodeGraph(), CodeGraphConfig.defaults(),
                        null, scope -> { })
                : projectTools(projects.get(0));
        for (GraphTool tool : templates) {
            byToolThenProject.put(tool.spec().name(),
                    Collections.synchronizedMap(new LinkedHashMap<>()));
        }
        for (CodeGraphTools.ProjectTools project : projects) {
            addProject(project);
        }
        Supplier<String> defaultName = () -> defaultProject;
        List<GraphTool> built = new ArrayList<>();
        for (GraphTool template : templates) {
            built.add(new RouterTool(byToolThenProject.get(template.spec().name()),
                    defaultName, template.spec(), lifecycle, () -> onboardingStatus.get()));
        }
        built.add(new ListProjectsTool(graphs, defaultName, lifecycle, () -> onboardingStatus.get()));
        built.add(new WorkspaceContextTool(graphs));
        built.add(new RemoveProjectTool(this));
        this.tools = List.copyOf(built);
    }

    /** The full tool set (routers + {@code list_projects} + {@code remove_project}). */
    public List<GraphTool> tools() {
        return tools;
    }

    /** Full catalog including onboarding, wired by a serving layer with filesystem access. */
    public List<GraphTool> tools(Function<String, String> onboard) {
        List<GraphTool> withOnboarding = new ArrayList<>(tools);
        withOnboarding.add(new AddProjectTool(onboard));
        return List.copyOf(withOnboarding);
    }

    /**
     * Register a project's tools into every router at runtime (after the workspace has indexed
     * it). Safe to call while the server is serving; subsequent tool calls can route to it.
     */
    public long addProject(CodeGraphTools.ProjectTools project) {
        List<GraphTool> additions = projectTools(project);
        return lifecycle.register(project.name(), () -> {
            synchronized (lock) {
                graphs.put(project.name(), project.graph());
                if (defaultProject == null) {
                    defaultProject = project.name();
                }
                for (GraphTool tool : additions) {
                    Map<String, GraphTool> perProject = byToolThenProject.get(tool.spec().name());
                    if (perProject != null) {
                        perProject.put(project.name(), tool);
                    }
                }
            }
        });
    }

    public ProjectLifecycle lifecycle() { return lifecycle; }

    @Override public void close() { lifecycle.close(); }

    public List<String> projectNames() {
        synchronized (graphs) {
            return List.copyOf(graphs.keySet());
        }
    }

    /**
     * Remove a project from every router.
     *
     * @return {@code false} when the name is unknown
     */
    public boolean removeProject(String name) {
        return lifecycle.remove(name);
    }

    private void removeData(String name) {
        synchronized (lock) {
            if (!graphs.containsKey(name)) {
                return;
            }
            graphs.remove(name);
            byToolThenProject.values().forEach(perProject -> perProject.remove(name));
            if (name.equals(defaultProject)) {
                List<String> remaining = projectNames();
                defaultProject = remaining.isEmpty() ? null : remaining.get(0);
            }
        }
        listener.removed(name);
    }

    private static List<GraphTool> projectTools(CodeGraphTools.ProjectTools project) {
        return CodeGraphTools.standard(project.graph(), project.config(), project.root(),
                project.reindexer()).stream().map(tool -> (GraphTool) new GraphTool() {
                    public ToolSpec spec() { return tool.spec(); }
                    public ToolResponse call(com.fasterxml.jackson.databind.JsonNode args) {
                        // Reindex mutates the graph and must not attempt a read-to-write lock upgrade.
                        if (tool.spec().name().equals("reindex") || tool.spec().name().equals("index_status")) return tool.call(args);
                        try { return project.graph().read(() -> tool.call(args)); }
                        catch (RuntimeException e) { return ToolResponse.fail(e.getMessage()); }
                    }
                }).toList();
    }
}
