package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.analysis.BlastFormula;
import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.NodeId;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/** {@code get_blast_score} — the fully itemized risk score, auditable factor by factor. */
final class GetBlastScoreTool implements GraphTool {

    private final BlastScore blastScore;
    private final CodeGraphConfig config;

    GetBlastScoreTool(BlastScore blastScore, CodeGraphConfig config) {
        this.blastScore = blastScore;
        this.config = config;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec("get_blast_score",
                "Auditable change-risk score (0-100) for a symbol or file: weighted factors "
                        + "(transitive reach, fan-in, module spread, cross-language reach, test coverage), "
                        + "each itemized.",
                """
                { "type": "object",
                  "properties": {
                    "target": { "type": "string", "description": "Symbol ID or repo-relative file path" } },
                  "required": ["target"] }
                """);
    }

    @Override
    public ToolResponse call(JsonNode args) {
        String rawTarget = ToolSupport.stringArg(args, "target", null);
        if (rawTarget == null || rawTarget.isBlank()) {
            return ToolResponse.fail("target is required");
        }
        BlastScore.Scored scored;
        try {
            NodeId target = ToolSupport.target(rawTarget);
            scored = blastScore.compute(target);
        } catch (IllegalArgumentException e) {
            return ToolResponse.fail(e.getMessage() + " (use search_symbols to find valid IDs)");
        }
        ObjectNode out = JSON.createObjectNode();
        write(out, scored, config);
        return ToolSupport.finish(out, config);
    }

    /** Shared serialization — the RiskGate emits the same shape. */
    static void write(ObjectNode out, BlastScore.Scored scored, CodeGraphConfig config) {
        out.put("target", scored.target().value());
        out.put("score", scored.score());
        out.put("band", scored.band());
        int threshold = config.gating().threshold();
        out.put("threshold", threshold);
        out.put("gate", scored.score() >= threshold ? "fail" : "pass");
        ArrayNode factors = out.putArray("factors");
        for (BlastFormula.Factor factor : scored.factors()) {
            ObjectNode f = factors.addObject();
            f.put("name", factor.name());
            f.put("raw", factor.raw());
            f.put("normalized", factor.normalized());
            f.put("weight", factor.weight());
            f.put("points", factor.points());
        }
        ArrayNode top = out.putArray("topDependents");
        scored.topDependents().forEach(top::add);
        out.put("explanation", scored.explanation());
        if (scored.truncated()) {
            out.put("reachTruncated", true);
        }
    }
}
