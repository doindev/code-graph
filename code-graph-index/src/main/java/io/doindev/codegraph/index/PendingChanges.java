package io.doindev.codegraph.index;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded, atomic debounce queue. A drain cannot erase events arriving during indexing. */
final class PendingChanges {
    private static final long QUIET_NANOS = 250_000_000L;
    private static final long MAX_NANOS = 1_000_000_000L;
    private record Window(long first, long last, int bytes) { }
    private final Map<String,Window> paths = new LinkedHashMap<>();
    private int bytes;

    synchronized void offer(String path, long now) {
        if (paths.containsKey("*")) { update("*",now,1); return; }
        if ("*".equals(path)) { collapse(now); return; }
        int size = path.getBytes(StandardCharsets.UTF_8).length;
        if (!paths.containsKey(path) && (paths.size() >= HybridIndexer.MAX_BATCH_PATHS
                || (long)bytes+size > HybridIndexer.MAX_BATCH_PATH_BYTES)) { collapse(now); return; }
        update(path,now,size);
    }

    private void update(String path, long now, int size) {
        Window old = paths.put(path,new Window(paths.containsKey(path)?paths.get(path).first():now,now,size));
        if (old == null) bytes += size;
    }

    private void collapse(long now) {
        long first = paths.values().stream().mapToLong(Window::first).min().orElse(now);
        paths.clear(); paths.put("*",new Window(first,now,1)); bytes = 1;
    }

    synchronized List<String> drain(long now) {
        var ready = new ArrayList<String>();
        var it = paths.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next(); var window = entry.getValue();
            if (now-window.last() >= QUIET_NANOS || now-window.first() >= MAX_NANOS) {
                ready.add(entry.getKey()); bytes -= window.bytes(); it.remove();
            }
        }
        return ready;
    }

    synchronized int size() { return paths.size(); }
}
