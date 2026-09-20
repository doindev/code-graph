package io.doindev.codegraph.tools;

import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.analysis.DeadCode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;

import java.util.List;

/** Factory for the standard tool set served over MCP. */
public final class CodeGraphTools {

    /** Hook the serving layer uses to trigger re-indexing; implementations must be async. */
    @FunctionalInterface
    public interface Reindexer {
        void reindex(String scope);
    }

    /** Everything the tool layer needs to serve one project. */
    public record ProjectTools(String name, GraphQuery graph, CodeGraphConfig config,
                               java.nio.file.Path root, Reindexer reindexer) {
    }

    private CodeGraphTools() {
    }

    /**
     * Multi-project tool set: one instance of every standard tool per project behind a
     * {@code project}-parameter router, plus {@code list_projects} and {@code remove_project}.
     * The first entry is the default project.
     */
    public static List<GraphTool> workspace(List<ProjectTools> projects) {
        return workspace(projects, removed -> { }).tools();
    }

    /** As above, returning the live registry so callers can react to (or trigger) project removal. */
    public static WorkspaceTools workspace(List<ProjectTools> projects,
                                           WorkspaceTools.RemovalListener onRemove) {
        return new WorkspaceTools(projects, onRemove);
    }

    public static List<GraphTool> standard(GraphQuery graph, CodeGraphConfig config, Reindexer reindexer) {
        return standard(graph, config, null, reindexer);
    }

    /** With a repo root, the history-based smell detectors (git co-change, clones) run too. */
    public static List<GraphTool> standard(GraphQuery graph, CodeGraphConfig config,
                                           java.nio.file.Path repoRoot, Reindexer reindexer) {
        CodeGraphConfig effective = config.withDefaults();
        BlastScore blastScore = new BlastScore(graph, effective);
        DeadCode deadCode = new DeadCode(graph, effective);
        // the mutation-adjacent tools go through the RiskGate so a high-score target always
        // carries its mandatory risk report
        String indexInstance = java.util.UUID.randomUUID().toString();
        return List.<GraphTool>of(
                new SearchSymbolsTool(graph, effective),
                new SymbolContextTool(graph, effective),
                new RiskGate(new GetSymbolTool(graph, effective), blastScore, effective, "symbol_id"),
                new RiskGate(new GetImpactRadiusTool(graph, effective), blastScore, effective, "target"),
                new GetCallGraphTool(graph, effective),
                new GetBlastScoreTool(blastScore, effective),
                new FindDeadCodeTool(deadCode, effective),
                new FindCodeSmellsTool(graph, effective, repoRoot),
                new CompareDriftTool(graph, effective),
                new IndexStatusTool(graph, repoRoot, effective, indexInstance),
                new ReindexTool(reindexer),
                new CodeNavigationTool(graph,effective,CodeNavigationTool.Operation.OUTLINE),
                new CodeNavigationTool(graph,effective,CodeNavigationTool.Operation.POSITION),
                new CodeNavigationTool(graph,effective,CodeNavigationTool.Operation.REFERENCES),
                new CodeNavigationTool(graph,effective,CodeNavigationTool.Operation.IMPLEMENTATIONS),
                new ChangeAnalysisTool(graph,effective,repoRoot,false),
                new ChangeAnalysisTool(graph,effective,repoRoot,true)).stream()
                .map(tool -> tool instanceof IndexStatusTool || tool instanceof ReindexTool ? tool
                        : (GraphTool)new GenerationGuard(tool, graph, indexInstance)).toList();
    }
}
