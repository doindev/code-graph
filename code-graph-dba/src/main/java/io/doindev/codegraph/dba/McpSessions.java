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
    synchronized void remove(String id) { sessions.remove(id); }
    synchronized void clear() { sessions.clear(); }
    private void reap() { sessions.values().removeIf(s -> s.expires <= clock.getAsLong()); }
}
