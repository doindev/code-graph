package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.ClosureHit;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.query.GraphQuery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code get_impact_radius} — the blast-radius tool. Direct dependents are listed (capped);
 * the transitive closure is compressed to per-depth counts + module/language spread + a
 * highest-fan-in sample. The full closure is never returned — that is the key token saving.
 */
final class GetImpactRadiusTool implements GraphTool {

    private static final int DIRECT_CAP = 25;
    private static final int TOP_CAP = 5;

    private final GraphQuery graph;
    private final CodeGraphConfig config;

    GetImpactRadiusTool(GraphQuery graph, CodeGraphConfig config) {
        this.graph = graph;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("get_impact_radius",
                "What breaks if this changes: direct and transitive dependents (or dependencies) "
                        + "of a symbol or file, compressed to counts and spread.",
                """
                { "type": "object",
                  "properties": {
                    "target":    { "type": "string", "description": "Symbol ID or repo-relative file path" },
                    "direction": { "type": "string", "enum": ["dependents", "dependencies", "both"], "default": "dependents" },
                    "depth":     { "type": "integer", "minimum": 1, "maximum": 10, "default": 3 } },
                  "required": ["target"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String rawTarget = ToolSupport.stringArg(args, "target", null);
        if (rawTarget == null || rawTarget.isBlank()) {
            return ToolResponse.fail("target is required");
        }
        NodeId target;
        try {
            target = ToolSupport.target(rawTarget);
        } catch (IllegalArgumentException e) {
            return ToolResponse.fail("invalid target: " + e.getMessage());
        }
        if (graph.node(target).isEmpty()) {
            return ToolResponse.fail("unknown target: " + rawTarget + " (use search_symbols to find valid IDs)");
        }
        String direction = ToolSupport.stringArg(args, "direction", "dependents");
        int depth = ToolSupport.intArg(args, "depth", 3, 1, 10);

        ObjectNode out = JSON.createObjectNode();
        out.put("target", target.value());
        out.put("direction", direction);
        out.put("depth", depth);
        switch (direction) {
            case "dependents" -> out.set("dependents", radius(target, Direction.IN, depth));
            case "dependencies" -> out.set("dependencies", radius(target, Direction.OUT, depth));
            case "both" -> {
                out.set("dependents", radius(target, Direction.IN, depth));
                out.set("dependencies", radius(target, Direction.OUT, depth));
            }
            default -> {
                return ToolResponse.fail("unknown direction: " + direction);
            }
        }
        return ToolSupport.finish(out, config);
    }

    private ObjectNode radius(NodeId target, Direction direction, int depth) {
        // A file or type's impact lives on its members: CALL edges land on methods, not on the
        // containing node. Seed the traversal with the target plus its CONTAINS-descendants.
        Set<NodeId> seeds = seedsFor(target);
        Map<NodeId, ClosureHit> best = new LinkedHashMap<>();
        boolean truncated = false;
        for (NodeId seed : seeds) {
            ClosureResult perSeed = graph.closure(seed, direction, GetSymbolTool.IMPACT_KINDS,
                    depth, 5000, 0f);
            truncated |= perSeed.truncated();
            for (ClosureHit hit : perSeed.hits()) {
                NodeId id = hit.node().id();
                if (seeds.contains(id)) {
                    continue; // members of the target are not its blast radius
                }
                ClosureHit existing = best.get(id);
                if (existing == null || hit.depth() < existing.depth()) {
                    best.put(id, hit);
                }
            }
        }
        List<ClosureHit> hits = new ArrayList<>(best.values());
        ClosureResult closure = new ClosureResult(hits, truncated);

        ObjectNode out = JSON.createObjectNode();
        List<ClosureHit> direct = hits.stream().filter(h -> h.depth() == 1).toList();
        ArrayNode directIds = out.putArray("direct");
        direct.stream().limit(DIRECT_CAP).forEach(h -> directIds.add(h.node().id().value()));
        out.put("directTruncated", direct.size() > DIRECT_CAP);
        out.put("directOmitted", Math.max(0, direct.size() - DIRECT_CAP));

        int[] byDepth = new int[depth];
        Set<String> modules = new TreeSet<>();
        Set<String> langs = new TreeSet<>();
        for (ClosureHit hit : hits) {
            byDepth[hit.depth() - 1]++;
            String relPath = hit.node().relPath();
            if (relPath != null) {
                modules.add(relPath.contains("/") ? relPath.substring(0, relPath.indexOf('/')) : "(root)");
            }
            if (hit.node().lang() != null) {
                langs.add(hit.node().lang());
            }
        }
        ArrayNode depthCounts = out.putArray("byDepth");
        for (int count : byDepth) {
            depthCounts.add(count);
        }
        out.put("total", hits.size());
        out.put("totalTruncated", closure.truncated());
        ArrayNode moduleArray = out.putArray("modules");
        modules.forEach(moduleArray::add);
        ArrayNode langArray = out.putArray("langs");
        langs.forEach(langArray::add);

        // highest direct fan-in among reached symbols — the likely break points
        List<NodeId> unique = new ArrayList<>(new LinkedHashSet<>(
                hits.stream().filter(h -> h.node().id() instanceof SymbolId)
                        .map(h -> h.node().id()).toList()));
        ArrayNode top = out.putArray("topByFanIn");
        unique.stream()
                .sorted(Comparator.comparingInt(
                        (NodeId id) -> graph.edges(id, Direction.IN, GetSymbolTool.IMPACT_KINDS).size())
                        .reversed())
                .limit(TOP_CAP)
                .forEach(id -> top.add(id.value()));
        return out;
    }

    /** The target itself, plus its CONTAINS-descendants when it is a file or type. */
    private Set<NodeId> seedsFor(NodeId target) {
        Node node = graph.node(target).orElse(null);
        if (node == null || (node.kind() != NodeKind.FILE && node.kind() != NodeKind.TYPE)) {
            return Set.of(target);
        }
        Set<NodeId> seeds = new LinkedHashSet<>();
        seeds.add(target);
        ClosureResult members = graph.closure(target, Direction.OUT, Set.of(EdgeKind.CONTAINS), 3, 500, 0f);
        members.hits().forEach(h -> seeds.add(h.node().id()));
        return seeds;
    }
}
