package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.smells.SmellEngine;
import io.doindev.codegraph.smells.SmellFinding;

import java.util.List;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** {@code find_code_smells} — god classes, long methods, hubs, cycles... every finding carries its metric evidence. */
final class FindCodeSmellsTool implements GraphTool {

    private final GraphQuery graph;
    private final CodeGraphConfig config;
    private final SmellEngine engine;

    FindCodeSmellsTool(GraphQuery graph, CodeGraphConfig config, java.nio.file.Path repoRoot) {
        this.graph = graph;
        this.config = config;
        this.engine = new SmellEngine(config, repoRoot);
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("find_code_smells",
                "Structural code smells (god class, long method, hub, cyclic files, unstable "
                        + "dependencies...) with the measured evidence behind every finding.",
                """
                { "type": "object",
                  "properties": {
                    "scope":    { "type": "string", "description": "Glob narrowing the search, e.g. 'src/main/**'" },
                    "smell":    { "type": "string", "description": "Single detector id, e.g. 'god-class'" },
                    "severity": { "type": "string", "enum": ["info", "warning", "error"] },
                    "limit":    { "type": "integer", "minimum": 1, "maximum": 200, "default": 50 } } }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String scope = ToolSupport.stringArg(args, "scope", null);
        String smell = ToolSupport.stringArg(args, "smell", null);
        String severity = ToolSupport.stringArg(args, "severity", null);
        int limit = ToolSupport.intArg(args, "limit", 50, 1, 200);

        List<SmellFinding> findings = engine.findAll(graph, scope, smell);
        if (severity != null) {
            findings = findings.stream().filter(f -> f.severity().equals(severity)).toList();
        }
        boolean truncated = findings.size() > limit;
        List<SmellFinding> page = truncated ? findings.subList(0, limit) : findings;

        ObjectNode out = JSON.createObjectNode();
        ArrayNode rows = out.putArray("findings");
        for (SmellFinding finding : page) {
            ObjectNode row = rows.addObject();
            row.put("smell", finding.smell());
            row.put("target", finding.targetId());
            row.put("severity", finding.severity());
            ObjectNode evidence = row.putObject("evidence");
            finding.evidence().forEach(evidence::put);
            row.put("rule", finding.rule());
        }
        out.put("total", findings.size());
        out.put("truncated", truncated);
        out.put("omitted", Math.max(0, findings.size() - limit));
        return ToolSupport.finish(out, config);
    }
}
