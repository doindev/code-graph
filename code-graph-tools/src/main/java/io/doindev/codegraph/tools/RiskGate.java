package io.doindev.codegraph.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.analysis.BlastScore;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.NodeId;

import static io.doindev.codegraph.tools.ToolSupport.JSON;

/**
 * The threshold gate: decorates the mutation-adjacent tools (the ones agents call right before
 * editing) so that whenever the resolved target's blast score reaches the configured threshold,
 * a mandatory {@code risk} block is appended to the response. Implemented once here so it
 * cannot be forgotten per-tool.
 */
final class RiskGate implements GraphTool {

    private final GraphTool delegate;
    private final BlastScore blastScore;
    private final CodeGraphConfig config;
    private final String targetArg;

    RiskGate(GraphTool delegate, BlastScore blastScore, CodeGraphConfig config, String targetArg) {
        this.delegate = delegate;
        this.blastScore = blastScore;
        this.config = config;
        this.targetArg = targetArg;
    }

    @Override
    public ToolSpec spec() {
        return delegate.spec();
    }

    @Override
    public ToolResponse call(JsonNode args) {
        ToolResponse response = delegate.call(args);
        if (response.error() || !config.gating().attachRiskReport()) {
            return response;
        }
        String rawTarget = ToolSupport.stringArg(args, targetArg, null);
        if (rawTarget == null || rawTarget.isBlank()) {
            return response;
        }
        try {
            NodeId target = ToolSupport.target(rawTarget);
            BlastScore.Scored scored = blastScore.compute(target);
            if (scored.score() < config.gating().threshold()) {
                return response;
            }
            JsonNode body = JSON.readTree(response.json());
            if (!(body instanceof ObjectNode object)) {
                return response;
            }
            ObjectNode risk = object.putObject("risk");
            GetBlastScoreTool.write(risk, scored, config);
            risk.put("note", "MANDATORY RISK REVIEW: blast score " + scored.score()
                    + " >= gating threshold " + config.gating().threshold()
                    + ". Review the factor table and top dependents before modifying this code.");
            return ToolResponse.ok(object.toString());
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return response; // gating must never break the underlying tool
        }
    }
}
