package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.rules.Drift;

import java.util.List;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * {@code compare_architectural_drift} — evaluates the golden blueprint against the live graph:
 * layer/boundary violations and module cycles, each with a stable fingerprint. Baseline diffs
 * against a git ref run in the CI CLI; over MCP the blueprint itself is the baseline.
 */
final class CompareDriftTool implements GraphTool {

    private final GraphQuery graph;
    private final CodeGraphConfig config;

    CompareDriftTool(GraphQuery graph, CodeGraphConfig config) {
        this.graph = graph;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("compare_architectural_drift",
                "Architecture violations vs the code-graph.json blueprint: forbidden module "
                        + "dependencies and module cycles, with witness edges and stable fingerprints.",
                """
                { "type": "object",
                  "properties": {
                    "include": { "type": "array", "items": { "type": "string", "enum": ["layers", "cycles"] },
                                 "description": "Violation types to report; default both" } } }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        List<String> include = new java.util.ArrayList<>();
        JsonNode includeNode = args == null ? null : args.get("include");
        if (includeNode != null && includeNode.isArray()) {
            includeNode.forEach(n -> include.add(n.asText()));
        }
        Drift.Report report = Drift.evaluate(graph, config);

        ObjectNode out = JSON.createObjectNode();
        boolean hasBlueprint = config.architecture() != null && !config.architecture().modules().isEmpty();
        out.put("blueprint", hasBlueprint ? "code-graph config: architecture" : "none (directory modules, cycle checks only)");
        ArrayNode rows = out.putArray("violations");
        int emitted = 0;
        for (Drift.Violation violation : report.violations()) {
            if (!include.isEmpty()
                    && !include.contains(violation.type().equals("layer") ? "layers" : "cycles")) {
                continue;
            }
            if (emitted++ >= config.limits().maxResults()) {
                continue;
            }
            ObjectNode row = rows.addObject();
            row.put("type", violation.type());
            row.put("rule", violation.rule());
            row.put("from", violation.from());
            row.put("to", violation.to());
            ArrayNode witnesses = row.putArray("witnesses");
            violation.witnesses().forEach(witnesses::add);
            row.put("fingerprint", violation.fingerprint());
        }
        out.put("total", emitted);
        out.put("truncated", emitted > config.limits().maxResults());
        if (report.unassignedFiles() > 0 && !report.unassignedPolicy().equals("ignore")) {
            out.put("unassignedFiles", report.unassignedFiles());
            out.put("unassignedPolicy", report.unassignedPolicy());
        }
        out.put("summary", emitted + " violation(s)"
                + (hasBlueprint ? " against the blueprint" : "; configure architecture rules for layer checks"));
        return ToolSupport.finish(out, config);
    }
}
