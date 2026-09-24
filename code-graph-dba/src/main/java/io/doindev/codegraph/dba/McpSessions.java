package io.doindev.codegraph.dba;

import java.util.*;
import java.util.function.LongSupplier;

/** Transport-owned logical sessions. A tool argument is never a session credential. */
final class McpSessions {
    private record Session(String principal, long expires) {}
    private final Map<String, Session> sessions = new HashMap<>();
    private final LongSupplier clock;
    McpSessions(LongSupplier clock) { this.clock = clock; }
    synchronized void register(String id, String principal, long expires) {
        reap();
        if (id == null || id.isBlank() || id.length() > 256 || expires <= clock.getAsLong())
            throw new IllegalArgumentException("Invalid MCP session");
        Session prior = sessions.get(id);
        if (prior != null && !prior.principal.equals(principal)) throw new SecurityException("Session identity changed");
        if (prior == null && sessions.size() >= 256) throw new IllegalStateException("MCP session limit reached");
        sessions.put(id, new Session(principal, expires));
    }
    synchronized boolean alive(String id, String principal) {
        reap(); Session s = sessions.get(id);
        return s != null && s.principal.equals(principal);
    }
    synchronized com.fasterxml.jackson.databind.node.ArrayNode list(String principal){
        reap();var out=Profiles.JSON.createArrayNode();sessions.forEach((id,s)->{if(s.principal.equals(principal))out.addObject().put("label",CatalogScanner.hash(id).substring(0,12)).put("expires",s.expires);});return out;
    }
    synchronized String resolve(String principal,String label){
        reap();for(var entry:sessions.entrySet())if(entry.getValue().principal.equals(principal)&&CatalogScanner.hash(entry.getKey()).substring(0,12).equals(label))return entry.getKey();
        throw new IllegalArgumentException("MCP session ended or belongs to another identity");
    }
    synchronized void remove(String id) { sessions.remove(id); }
    synchronized void clear() { sessions.clear(); }
    private void reap() { sessions.values().removeIf(s -> s.expires <= clock.getAsLong()); }
}
