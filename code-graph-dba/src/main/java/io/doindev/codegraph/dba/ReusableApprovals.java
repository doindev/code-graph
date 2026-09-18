package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Bounded, exact-target permissions. Full SQL, parameter values and session tokens are never persisted. */
final class ReusableApprovals implements AutoCloseable {
    static final String EXACT = "always_exact", SESSION = "session_similar", SIMILAR = "always_similar";
    static final Set<String> ACTIONS = Set.of(EXACT, SESSION, SIMILAR);
    private final Path file;
    private final byte[] key;
    private final LongSupplier clock;
    final McpSessions sessions;
    private ArrayNode persistent, temporary = Profiles.JSON.createArrayNode();
    ReusableApprovals(Path directory, LongSupplier clock) throws IOException {
        this.clock = clock; sessions = new McpSessions(clock); file = directory.resolve("reusable-approvals.json");
        Path keyFile = directory.resolve("reusable-approvals.key");
        if (!Files.exists(keyFile)) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            Path tmp = Files.createTempFile(directory, "approval-key-", ".tmp");
            try { Profiles.protect(tmp); Files.write(tmp, bytes); Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(tmp); }
        }
        key = Files.readAllBytes(keyFile); if (key.length != 32) throw new IOException("Invalid reusable approval key");
        if (Files.exists(file)) {
            if (Files.size(file) > 2_097_152) throw new IOException("Reusable policy storage exceeds limit");
            JsonNode n = Profiles.JSON.readTree(file.toFile());
            if (!n.isArray() || n.size() > 1024) throw new IOException("Invalid reusable policy storage");
            persistent = (ArrayNode)n;
        } else persistent = Profiles.JSON.createArrayNode();
    }
    String fingerprint(JsonNode request, JsonNode scope) {
        ObjectNode value = Profiles.JSON.createObjectNode();
        for (String key : List.of("sql", "parameters", "autoCommit")) value.set(key, request.path(key));
        value.set("scope", scope);
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical(value).toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot fingerprint approval", e); }
    }
    private static JsonNode canonical(JsonNode n) {
        if (n.isObject()) { ObjectNode out = Profiles.JSON.createObjectNode(); TreeSet<String> names = new TreeSet<>(); n.fieldNames().forEachRemaining(names::add); names.forEach(k -> out.set(k, canonical(n.path(k)))); return out; }
        if (n.isArray()) { ArrayNode out = Profiles.JSON.createArrayNode(); n.forEach(v -> out.add(canonical(v))); return out; }
        return n;
    }
    synchronized ObjectNode grant(String principal, String session, String action, JsonNode request, JsonNode scope, ReusableOperation.Result operation) {
        if (!ACTIONS.contains(action) || !operation.eligible()) throw new IllegalArgumentException("Reusable approval unavailable: " + operation.reason());
        if (session == null || !sessions.alive(session, principal)) throw new IllegalArgumentException("Requesting MCP session expired; request approval again");
        reap(); long count = 0; for (JsonNode p : all()) if (p.path("agentId").asText().equals(principal)) count++;
        if (count >= 128 || persistent.size() + temporary.size() >= 1024) throw new IllegalArgumentException("Reusable policy limit reached; revoke unused permissions");
        ObjectNode policy = Profiles.JSON.createObjectNode().put("id", UUID.randomUUID().toString()).put("agentId", principal)
                .put("identity", AgentAccess.TRUSTED_LOCAL_ID.equals(principal) ? "Trusted local agents" : principal)
                .put("match", action.equals(EXACT) ? "exact" : "category").put("category", operation.category())
                .put("lifetime", action.equals(SESSION) ? "mcp_session" : "until_revoked")
                .put("enabled", true).put("createdAt", clock.getAsLong()).put("lastUsedAt", 0L);
        policy.set("scope", scope.deepCopy());
        if (action.equals(EXACT)) policy.put("fingerprint", fingerprint(request, scope));
        if (action.equals(SESSION)) { policy.put("session", session); temporary.add(policy); }
        else { ArrayNode next = persistent.deepCopy(); next.add(policy); save(next); persistent = next; }
        return publicPolicy(policy);
    }
    synchronized ObjectNode match(String principal, String session, JsonNode request, JsonNode scope, ReusableOperation.Result operation) {
        reap(); if (!operation.eligible() || session == null || !sessions.alive(session, principal)) return null;
        for (JsonNode p : all()) if (matches(p, principal, session, request, scope, operation)) {
            ((ObjectNode)p).put("lastUsedAt", clock.getAsLong());if(!p.has("session"))save(persistent);return publicPolicy(p);
        }
        return null;
    }
    synchronized void require(String id, String principal, String session, JsonNode request, JsonNode scope, ReusableOperation.Result operation) {
        reap(); if (!sessions.alive(session, principal)) throw new SecurityException("MCP session expired before execution");
        for (JsonNode p : all()) if (p.path("id").asText().equals(id) && matches(p, principal, session, request, scope, operation)) return;
        throw new SecurityException("Reusable permission revoked, disabled, expired or out of scope");
    }
    private boolean matches(JsonNode p, String principal, String session, JsonNode request, JsonNode scope, ReusableOperation.Result operation) {
        return operation.eligible() && p.path("enabled").asBoolean() && p.path("agentId").asText().equals(principal)
                && p.path("category").asText().equals(operation.category()) && p.path("scope").equals(scope)
                && (!p.path("lifetime").asText().equals("mcp_session") || p.path("session").asText().equals(session))
                && (p.path("match").asText().equals("category") || p.path("match").asText().equals("exact") && p.path("fingerprint").asText().equals(fingerprint(request, scope)));
    }
    synchronized ArrayNode list(String principal) { reap(); ArrayNode out = Profiles.JSON.createArrayNode(); for (JsonNode p : all()) if (principal == null || p.path("agentId").asText().equals(principal)) out.add(publicPolicy(p)); return out; }
    synchronized void auditUse(String policy,String principal,String operation){
        Path audit=file.resolveSibling("reusable-access.jsonl");
        try{
            if(Files.exists(audit)&&Files.size(audit)>4L<<20)Files.move(audit,audit.resolveSibling("reusable-access.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);
            if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}
            ObjectNode row=Profiles.JSON.createObjectNode().put("at",clock.getAsLong()).put("policy",policy).put("agent",principal).put("operation",operation);
            Files.writeString(audit,row+"\n",StandardOpenOption.APPEND);
        }catch(IOException e){throw new IllegalStateException("Reusable access audit unavailable; access denied",e);}
    }
    synchronized boolean contains(String principal, String id) { for (JsonNode p : all()) if (p.path("agentId").asText().equals(principal) && p.path("id").asText().equals(id)) return true; return false; }
    synchronized ObjectNode change(String principal, String id, Boolean enabled) {
        reap();
        for (ArrayNode source : List.of(temporary, persistent)) {
            ArrayNode next = source.deepCopy();
            for (int i = 0; i < next.size(); i++) if (next.get(i).path("agentId").asText().equals(principal) && next.get(i).path("id").asText().equals(id)) {
                if (enabled == null) next.remove(i); else ((ObjectNode)next.get(i)).put("enabled", enabled);
                if (source == persistent) { save(next); persistent = next; } else temporary = next;
                return Profiles.JSON.createObjectNode().put("ok", true);
            }
        }
        throw new IllegalArgumentException("Unknown reusable permission");
    }
    synchronized void invalidateConnection(String id) { invalidate("connectionId", id); }
    synchronized void invalidateBinding(String id) { invalidate("bindingId", id); }
    private void invalidate(String field, String id) {
        ArrayNode next = Profiles.JSON.createArrayNode(); for (JsonNode p : persistent) if (!p.path("scope").path(field).asText().equals(id)) next.add(p);
        if (next.size() != persistent.size()) { save(next); persistent = next; }
        for (int i = temporary.size() - 1; i >= 0; i--) if (temporary.get(i).path("scope").path(field).asText().equals(id)) temporary.remove(i);
    }
    private Iterable<JsonNode> all() { List<JsonNode> out = new ArrayList<>(); temporary.forEach(out::add); persistent.forEach(out::add); return out; }
    private void reap() { for (int i = temporary.size() - 1; i >= 0; i--) { JsonNode p = temporary.get(i); if (!sessions.alive(p.path("session").asText(), p.path("agentId").asText())) temporary.remove(i); } }
    private static ObjectNode publicPolicy(JsonNode p) {
        ObjectNode out = p.deepCopy(); out.remove(List.of("fingerprint", "session")); out.set("scope", ApprovalScope.display(p.path("scope")));
        if (p.has("session")) out.put("sessionLabel", CatalogScanner.hash(p.path("session").asText()).substring(0, 12));
        return out;
    }
    private void save(ArrayNode next) {
        try {
            byte[] bytes = Profiles.JSON.writeValueAsBytes(next); if (bytes.length > 2_097_152) throw new IllegalArgumentException("Reusable policy storage limit reached");
            Path temp = Files.createTempFile(file.getParent(), "reusable-policies-", ".tmp");
            try { Profiles.protect(temp); Files.write(temp, bytes); Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temp); }
        } catch (IOException e) { throw new IllegalStateException("Cannot persist reusable permission; no operation was authorized", e); }
    }
    @Override public synchronized void close() { temporary.removeAll(); sessions.clear(); Arrays.fill(key, (byte)0); }
}
