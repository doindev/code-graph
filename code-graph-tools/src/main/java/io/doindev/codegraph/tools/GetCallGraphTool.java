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
        int depth = ToolSupport.intArg(args, "depth", 2, 1, 6);

        Map<NodeId, Integer> index = new LinkedHashMap<>();
        index.put(root, 0);
        List<int[]> up = new ArrayList<>();
        List<int[]> down = new ArrayList<>();
        int omitted = 0;
        if (direction.equals("up") || direction.equals("both")) {
            omitted += bfs(root, Direction.IN, depth, index, up);
        }
        if (direction.equals("down") || direction.equals("both")) {
            omitted += bfs(root, Direction.OUT, depth, index, down);
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
        writeEdges(out.putArray("up"), up);
        writeEdges(out.putArray("down"), down);
        out.put("truncated", omitted > 0);
        out.put("omittedNodes", omitted);
        return ToolSupport.finish(out, config);
    }

    /** BFS collecting CALLS edges; returns count of nodes omitted by caps. */
    private int bfs(NodeId root, Direction direction, int maxDepth,
                    Map<NodeId, Integer> index, List<int[]> edges) {
        int omitted = 0;
        record Frontier(NodeId id, int depth) {}
        ArrayDeque<Frontier> queue = new ArrayDeque<>();
        queue.add(new Frontier(root, 0));
        Set<NodeId> visited = new java.util.HashSet<>();
        visited.add(root);

        while (!queue.isEmpty()) {
            Frontier cur = queue.poll();
            if (cur.depth() == maxDepth) {
                continue;
            }
            List<Edge> step = graph.edges(cur.id(), direction, Set.of(EdgeKind.CALLS));
            int taken = 0;
            for (Edge e : step) {
                NodeId next = direction == Direction.IN ? e.from() : e.to();
                if (taken >= FAN_CAP || index.size() >= NODE_CAP) {
                    omitted++;
                    continue;
                }
                Integer nextIdx = index.get(next);
                if (nextIdx == null) {
                    if (graph.node(next).isEmpty()) {
                        continue;
                    }
                    nextIdx = index.size();
                    index.put(next, nextIdx);
                }
                int curIdx = index.get(cur.id());
                // edge pair is always [caller, callee]
                if (direction == Direction.IN) {
                    edges.add(new int[] {nextIdx, curIdx});
                } else {
                    edges.add(new int[] {curIdx, nextIdx});
                }
                taken++;
                if (visited.add(next)) {
                    queue.add(new Frontier(next, cur.depth() + 1));
                }
            }
        }
        return omitted;
    }

    private static void writeEdges(ArrayNode into, List<int[]> edges) {
        for (int[] pair : edges) {
            ArrayNode edge = into.addArray();
            edge.add(pair[0]);
            edge.add(pair[1]);
        }
    }
}
