package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable startup authority. This supplies consent, never identity or target validity. */
final class AgentAuthorization {
    static final String WARNING = "DANGER: --yolo is active; validated local MCP agents are automatically authorized for database reads, writes, DDL, migrations, and administration.";
    final boolean automatic;
    private final Path audit;
    private McpSessions sessions;
    AgentAuthorization(boolean automatic, Path directory) { this.automatic=automatic; audit=directory.resolve("agent-automatic-authorization.jsonl"); }
    void sessions(McpSessions sessions) { this.sessions=sessions; }
    void requireSession(String principal,String session) {
        if(automatic && (session==null || sessions==null || !sessions.alive(session,principal))) throw new SecurityException("A validated, live local MCP session is required for --yolo");
    }
    ObjectNode scope(JsonNode profile,JsonNode binding,JsonNode input) {
        if(automatic && !binding.has("id")) {
            Profiles.text(input,"connectionId",36);
            if(!profile.path("name").asText().equals(Profiles.text(input,"connectionName",120))) throw new IllegalArgumentException("Exact connectionName must match connectionId");
            Profiles.text(input,"database",128);
            String vendor=profile.path("templateId").asText();
            if(!Set.of("mysql","mariadb","sqlite","clickhouse").contains(vendor)) Profiles.text(input,"schema",128);
        }
        return ApprovalScope.resolve(profile,binding,input);
    }
    ObjectNode describe(ObjectNode out) {
        out.put("yolo",automatic).put("effectiveApprovalBehavior",automatic?"automatic":"review_or_existing_policy");
        if(automatic) out.put("warning",WARNING);
        return out;
    }
    static ObjectNode approved(ObjectNode out) {
        if(out.path("classification") instanceof ObjectNode classification)classification.put("approvalRequired",false);
        if(out.has("approvalChoices"))out.set("approvalChoices",Profiles.JSON.createArrayNode());
        return out.put("authorizationOutcome","auto_approved").put("authorizationReason","startup_yolo").put("approvalChannel","automatic").put("reviewAvailable",false).put("deliveryStatus","not_required");
    }
    synchronized void audit(String principal,String session,String operation,JsonNode target) {
        if(!automatic) return;
        requireSession(principal,session);
        try {
            if(Files.exists(audit) && Files.size(audit)>4L<<20) Files.move(audit,audit.resolveSibling("agent-automatic-authorization.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);
            if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}
            ObjectNode entry=Profiles.JSON.createObjectNode().put("at",System.currentTimeMillis()).put("agent",principal)
                .put("sessionHash",CatalogScanner.hash(session)).put("operation",operation).put("authorizationReason","startup_yolo")
                .put("targetHash",CatalogScanner.hash(target.toString()));
            Files.writeString(audit,entry+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        } catch(Exception failure) { throw new IllegalStateException("Automatic authorization audit unavailable; operation not authorized",failure); }
    }
}
