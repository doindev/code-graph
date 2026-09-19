package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.query.GraphQuery;

import java.util.Set;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code get_symbol} — signature, location and dependency counts for one symbol.
 * Never returns source bodies.
 */
final class GetSymbolTool implements GraphTool {

    static final Set<EdgeKind> IMPACT_KINDS = BlastScore.IMPACT_KINDS;

    private final GraphQuery graph;
    private final CodeGraphConfig config;

    GetSymbolTool(GraphQuery graph, CodeGraphConfig config) {
        this.graph = graph;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("get_symbol",
                "Signature, location and dependency counts for a symbol ID (from search_symbols).",
                """
                { "type": "object",
                  "properties": {
                    "symbol_id": { "type": "string", "description": "Full symbol ID, e.g. 'java:src/A.java#A.m/1'" } },
                  "required": ["symbol_id"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String rawId = ToolSupport.stringArg(args, "symbol_id", null);
        if (rawId == null || rawId.isBlank()) {
            return ToolResponse.fail("symbol_id is required");
        }
        NodeId id;
        try {
            id = ToolSupport.target(rawId);
        } catch (IllegalArgumentException e) {
            return ToolResponse.fail("invalid symbol_id: " + e.getMessage());
        }
        Node node = graph.node(id).orElse(null);
        if (node == null) {
            return ToolResponse.fail("unknown symbol: " + rawId + " (use search_symbols to find valid IDs)");
        }

        ObjectNode out = JSON.createObjectNode();
        out.put("id", node.id().value());
        out.put("kind", node.kind().name().toLowerCase());
        out.put("sig", node.displaySignature());
        if (node.span() != null) {
            out.put("file", node.span().relPath());
            out.put("line", node.span().startLine());
        }
        if (node.lang() != null) {
            out.put("lang", node.lang());
        }
        String doc = node.attrs().get("doc");
        if (doc != null) {
            out.put("doc", doc);
        }
        if(node.attrs().containsKey("moduleResolutionVersion")) {
            var evidence=out.putObject("moduleResolutionCoverage");
            node.attrs().entrySet().stream().filter(e->e.getKey().startsWith("module")).sorted(java.util.Map.Entry.comparingByKey())
                    .forEach(e->evidence.put(e.getKey(),e.getValue()));
        }

        ObjectNode counts = out.putObject("counts");
        counts.put("callers", graph.edges(id, Direction.IN, Set.of(EdgeKind.CALLS)).size());
        counts.put("callees", graph.edges(id, Direction.OUT, Set.of(EdgeKind.CALLS)).size());
        counts.put("refs", graph.edges(id, Direction.IN, Set.of(EdgeKind.REFERENCES)).size());
        ClosureResult reach = graph.closure(id, Direction.IN, IMPACT_KINDS, 10, 5000, 0f);
        counts.put("transitiveDependents", reach.hits().size());
        counts.put("transitiveTruncated", reach.truncated());

        var metrics = node.metrics();
        if (metrics.loc() > 0) {
            ObjectNode m = out.putObject("metrics");
            m.put("loc", metrics.loc());
            if (metrics.methodCount() > 0 || metrics.fieldCount() > 0) {
                m.put("methods", metrics.methodCount());
                m.put("fields", metrics.fieldCount());
            }
            if (metrics.cyclomaticApprox() > 0) {
                m.put("complexity", metrics.cyclomaticApprox());
            }
        }
        return ToolSupport.finish(out, config);
    }
}
