package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.query.GraphQuery;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code get_call_graph} — callers (up) and callees (down) in compressed adjacency form:
 * a node array plus {@code [fromIdx, toIdx]} edge pairs, so each symbol ID string appears once.
 */
final class GetCallGraphTool implements GraphTool {

    private static final int NODE_CAP = 100;
    private static final int FAN_CAP = 20;
    private static final int EDGE_VISIT_CAP = 100_000;

    private final GraphQuery graph;
    private final CodeGraphConfig config;

    GetCallGraphTool(GraphQuery graph, CodeGraphConfig config) {
        this.graph = graph;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("get_call_graph",
                "Callers (up) and callees (down) of a function, as a compact adjacency graph.",
                """
                { "type": "object",
                  "properties": {
                    "function":  { "type": "string", "description": "Symbol ID of the function" },
                    "direction": { "type": "string", "enum": ["up", "down", "both"], "default": "both" },
                    "depth":     { "type": "integer", "minimum": 1, "maximum": 6, "default": 2 } },
                  "required": ["function"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        try { return graph.read(() -> query(args)); }
        catch (IllegalArgumentException e) { return ToolResponse.fail(e.getMessage()); }
    }

    private ToolResponse query(JsonNode args) {
        String rawId = ToolSupport.stringArg(args, "function", null);
        if (rawId == null || rawId.isBlank()) {
            return ToolResponse.fail("function is required");
        }
        NodeId root;
        try {
            root = ToolSupport.target(rawId);
        } catch (IllegalArgumentException e) {
            return ToolResponse.fail("invalid function id: " + e.getMessage());
        }
        Node rootNode = graph.node(root).orElse(null);
        if (rootNode == null) {
            return ToolResponse.fail("unknown function: " + rawId + " (use search_symbols to find valid IDs)");
        }
        String direction = ToolSupport.stringArg(args, "direction", "both");
        if (!Set.of("up", "down", "both").contains(direction)) return ToolResponse.fail("Unknown direction");
        int depth = ToolSupport.intArg(args, "depth", 2, 1, 6);

        Map<NodeId, Integer> index = new LinkedHashMap<>();
        index.put(root, 0);
        Map<Pair, Integer> up = new LinkedHashMap<>();
        Map<Pair, Integer> down = new LinkedHashMap<>();
        Work work = new Work();
        if (direction.equals("up") || direction.equals("both")) {
            bfs(root, Direction.IN, depth, index, up, work);
        }
        if (direction.equals("down") || direction.equals("both")) {
            bfs(root, Direction.OUT, depth, index, down, work);
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("root", 0);
        out.put("direction", direction);
        out.put("depth", depth);
        ArrayNode nodes = out.putArray("nodes");
        ArrayNode sigs = out.putArray("sigs");
        for (NodeId id : index.keySet()) {
            nodes.add(id.value());
            sigs.add(graph.node(id).map(Node::displaySignature).orElse(""));
        }
        writeEdges(out, "up", up, !work.exhausted);
        writeEdges(out, "down", down, !work.exhausted);
        out.put("truncated", work.omitted || work.exhausted);
        out.put("omittedNodes", work.omittedNode ? 1 : 0);
        out.put("omittedNodesCountComplete", !work.omittedNode && !work.exhausted);
        out.put("aggregation", "distinct_caller_callee");
        out.put("countCompleteness", work.exhausted ? "lower_bound" : "complete_for_returned_relationships");
        if(config.gating().attachRiskReport()) {
            try {
                var scored=new io.doindev.codegraph.analysis.BlastScore(graph,config).computeBounded(root,()->visit(work));
                if(scored.score()>=config.gating().threshold()) {
                    var risk=out.putObject("risk");GetBlastScoreTool.write(risk,scored,config);
                    risk.put("note","MANDATORY RISK REVIEW: blast score "+scored.score()
                            +" >= gating threshold "+config.gating().threshold()+". Review before modifying this code.");
                }
            }catch(WorkLimit exhausted) {
                out.putObject("risk").put("gate","review_required").put("completeness","unavailable_work_limit")
                        .put("note","MANDATORY RISK REVIEW: the shared inspection limit prevented complete risk scoring. Do not interpret an unavailable score as low risk.");
            }
        }
        out.put("edgeVisits", work.visits).put("edgeVisitLimit", EDGE_VISIT_CAP);
        out.put("workLimitReached", work.exhausted).put("generation", graph.status().generation());
        out.put("referenceCompleteness", "not_guaranteed");
        out.put("coverage", "Indexed call bindings; Java virtual calls identify declared targets, not all runtime overrides. Dynamic, external and unresolved references may be absent. Use find_references for occurrence-level confidence and resolution evidence.");
        return ToolSupport.finish(out, config);
    }

    private record Pair(int from, int to) {}
    private static final class Work {
        int visits; boolean omitted, omittedNode, exhausted;
    }
    private static final class WorkLimit extends RuntimeException {
        WorkLimit() { super(null, null, false, false); }
    }
    private static void visit(Work work) {
        if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Call graph cancelled");
        if(work.visits==EDGE_VISIT_CAP){work.exhausted=true;throw new WorkLimit();}
        work.visits++;
    }
    /** One bounded streaming pass per visited adjacency; repeated sites consume no fan capacity. */
    private void bfs(NodeId root, Direction direction, int maxDepth,
                     Map<NodeId, Integer> index, Map<Pair, Integer> edges, Work work) {
        record Frontier(NodeId id, int depth) {}
        ArrayDeque<Frontier> queue = new ArrayDeque<>();
        queue.add(new Frontier(root, 0));
        Set<NodeId> visited = new java.util.HashSet<>();
        visited.add(root);

        while (!queue.isEmpty() && !work.exhausted) {
            Frontier cur = queue.poll();
            if (cur.depth() == maxDepth) {
                continue;
            }
            Map<NodeId, Pair> admitted = new LinkedHashMap<>();
            try { graph.scanEdges(cur.id(), direction, Set.of(EdgeKind.CALLS), e -> {
                visit(work);
                NodeId next = direction == Direction.IN ? e.from() : e.to();
                Pair existing = admitted.get(next);
                if (existing != null) { edges.merge(existing, 1, Integer::sum); return; }
                if (admitted.size() >= FAN_CAP || !index.containsKey(next) && index.size() >= NODE_CAP) {
                    work.omitted = true;
                    if (!index.containsKey(next)) work.omittedNode = true;
                    return;
                }
                Integer nextIdx = index.get(next);
                if (nextIdx == null) {
                    if (graph.node(next).isEmpty()) {
                        return;
                    }
                    nextIdx = index.size();
                    index.put(next, nextIdx);
                }
                int curIdx = index.get(cur.id());
                // edge pair is always [caller, callee]
                Pair pair = direction == Direction.IN ? new Pair(nextIdx, curIdx) : new Pair(curIdx, nextIdx);
                admitted.put(next, pair);
                edges.merge(pair, 1, Integer::sum);
                if (visited.add(next)) {
                    queue.add(new Frontier(next, cur.depth() + 1));
                }
            }); } catch (WorkLimit limit) { /* Return explicitly incomplete occurrence counts. */ }
        }
    }

    private static void writeEdges(ObjectNode out, String name, Map<Pair, Integer> edges, boolean complete) {
        ArrayNode into = out.putArray(name), counts = out.putArray(name + "Occurrences"),
                completeness = out.putArray(name + "CountsComplete");
        for (var entry : edges.entrySet()) {
            Pair pair = entry.getKey();
            ArrayNode edge = into.addArray();
            edge.add(pair.from());
            edge.add(pair.to());
            counts.add(entry.getValue()); completeness.add(complete);
        }
    }
}
