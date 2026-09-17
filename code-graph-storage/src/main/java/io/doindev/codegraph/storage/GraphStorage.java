package io.doindev.codegraph.storage;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.store.ManagedGraph;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** One session and one cache allowance shared by every project. Never restores previous sessions. */
public final class GraphStorage implements AutoCloseable {
    public static final long DEFAULT_BUDGET = 1L << 30;
    public static final long MIN_BUDGET = 32L << 20;
    private static final long RESERVE = 16L << 20;
    private final boolean hybrid;
    private final Path directory;
    private final Object writer = new Object();
    private final Set<PagedGraph> graphs = new HashSet<>();
    private final Set<Path> files = new HashSet<>();
    private final LinkedHashMap<String, byte[]> cache = new LinkedHashMap<>(16, .75f, true);
    private final Semaphore readers = new Semaphore(4, true);
    private final ThreadLocal<Integer> readDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Integer> scanDepth = ThreadLocal.withInitial(() -> 0);
    private long budget, capacity, used, peak, hits, misses, evictions, peakUnsaved, currentUnsaved;
    private boolean closed;
    private final Thread shutdownHook;

    public GraphStorage(boolean hybrid, long budget) {
        validateBudget(budget);
        this.hybrid = hybrid;
        this.budget = budget;
        try { directory = hybrid ? Files.createTempDirectory("code-graph-session-").toRealPath() : null; }
        catch (IOException e) { throw new UncheckedIOException(e); }
        recalculate();
        shutdownHook = hybrid ? new Thread(() -> {
            try { close(); } catch (RuntimeException e) { System.err.println("code-graph: temporary graph cleanup failed: " + e.getMessage()); }
        }, "graph-storage-cleanup") : null;
        if (shutdownHook != null) Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static long parseBudget(String text) {
        if (text == null || !text.matches("(?i)[0-9]+(m|g|mib|gib)"))
            throw new IllegalArgumentException("graph memory must be whole MiB or GiB, e.g. 256m or 1g (minimum 32m)");
        String lower = text.toLowerCase(Locale.ROOT);
        try {
            long value = Math.multiplyExact(Long.parseLong(lower.replaceAll("[a-z]", "")),
                    lower.contains("g") ? 1L << 30 : 1L << 20);
            validateBudget(value);
            return value;
        } catch (ArithmeticException e) { throw new IllegalArgumentException("graph memory is too large", e); }
    }

    public static void validateBudget(long bytes) {
        if (bytes < MIN_BUDGET || bytes % (1L << 20) != 0)
            throw new IllegalArgumentException("graph memory must be whole MiB and at least 32 MiB");
    }

    public ManagedGraph create() {
        return write(() -> {
            if (!hybrid) return new InMemoryCodeGraph();
            var graph = new PagedGraph(this);
            synchronized (this) { graphs.add(graph); }
            return graph;
        });
    }

    /** Catalog partitions share this workspace's paging lifecycle and memory allowance. */
    public io.doindev.codegraph.store.DocumentStore documents() {
        if(!hybrid)return io.doindev.codegraph.store.DocumentStore.memory(Math.min(budget/4,64L<<20));
        PagedGraph graph=(PagedGraph)create();
        return new io.doindev.codegraph.store.DocumentStore() {
            public void replace(java.util.function.Consumer<Writer> producer){graph.rebuild(builder->{producer.accept(builder::auxiliary);return null;});}
            public byte[] get(String key){return graph.document(key);}
            public void scan(String prefix,java.util.function.BiConsumer<String,byte[]> visitor){graph.documents(prefix,visitor);}
            public <T>T read(java.util.function.Supplier<T> reader){return graph.read(reader);}
            public void close(){graph.close();}
        };
    }

    /** Reject indexing a root containing the scratch directory (including symlink aliases). */
    public void checkRoot(Path root) {
        if (directory != null && (directory.startsWith(root) || root.startsWith(directory)))
            throw new IllegalArgumentException("project root overlaps this session's temporary graph storage");
    }

    <T> T write(Supplier<T> action) {
        synchronized (writer) {
            if (closed) throw new IllegalStateException("graph storage is closed");
            return action.get();
        }
    }

    <T> T query(Supplier<T> action) {
        int depth = readDepth.get();
        if (depth == 0) readers.acquireUninterruptibly();
        readDepth.set(depth + 1);
        try { return action.get(); }
        finally { if (depth == 0) { readDepth.remove(); readers.release(); } else readDepth.set(depth); }
    }

    synchronized Path newFile() {
        // Reserve engine metadata for each active/staged store; refuse unbounded project overhead.
        if (RESERVE + (files.size() + 1L) * (1L << 20) > Math.min(budget, Runtime.getRuntime().maxMemory() / 2))
            throw new IllegalStateException("graph memory budget cannot reserve another store; remove a project or raise the budget");
        Path path = directory.resolve(UUID.randomUUID() + ".mv");
        files.add(path); recalculate();
        return path;
    }

    synchronized void deleteFile(Path path) {
        if (!files.contains(path) || !path.getParent().equals(directory))
            throw new IllegalArgumentException("not an owned graph file");
        try { Files.deleteIfExists(path); files.remove(path); recalculate(); }
        catch (IOException e) { throw new UncheckedIOException("cannot remove session graph file " + path, e); }
    }

    synchronized void removed(PagedGraph graph) { graphs.remove(graph); }
    synchronized void unsaved(long bytes) { currentUnsaved = bytes; peakUnsaved = Math.max(peakUnsaved, bytes); }

    private static long weight(String key, byte[] value) { return 192L + key.length() * 2L + value.length; }
    synchronized byte[] cached(String key) {
        if (scanDepth.get() > 0) return null;
        byte[] value = cache.get(key);
        if (value == null) misses++; else hits++;
        return value;
    }
    synchronized void cache(String key, byte[] value) {
        if (scanDepth.get() > 0) return;
        long weight = weight(key, value);
        if (weight > capacity) return;
        byte[] old = cache.remove(key);
        if (old != null) used -= weight(key, old);
        while (used + weight > capacity) evict();
        cache.put(key, value); used += weight; peak = Math.max(peak, used);
    }
    synchronized void invalidate(String prefix) {
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().startsWith(prefix)) { used -= weight(entry.getKey(), entry.getValue()); iterator.remove(); }
        }
    }
    private void evict() {
        var iterator = cache.entrySet().iterator();
        var entry = iterator.next(); used -= weight(entry.getKey(), entry.getValue()); iterator.remove(); evictions++;
    }
    private void recalculate() {
        // The allowance is an upper bound, not an instruction to consume the entire JVM heap.
        capacity = Math.max(0, Math.min(budget, Runtime.getRuntime().maxMemory() / 2)
                - RESERVE - files.size() * (1L << 20));
        while (used > capacity) evict();
    }
    public synchronized void resize(long bytes) {
        validateBudget(bytes);
        if (!hybrid) throw new IllegalArgumentException("live graph memory settings require --graph-storage hybrid");
        if (Math.min(bytes, Runtime.getRuntime().maxMemory() / 2) < RESERVE + files.size() * (1L << 20))
            throw new IllegalArgumentException("budget is below the current store metadata reserve");
        budget = bytes; recalculate();
    }
    public synchronized Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("mode", hybrid ? "hybrid" : "memory");
        result.put("budgetBytes", budget); result.put("cacheCapacityBytes", hybrid ? capacity : 0);
        result.put("cacheUsedBytesEstimate", used); result.put("peakCacheUsedBytesEstimate", peak);
        result.put("cacheHits", hits); result.put("cacheMisses", misses); result.put("cacheEvictions", evictions);
        result.put("enginePageCacheBytes", 0); result.put("activeAndStagedStores", files.size());
        result.put("metadataReserveBytes", hybrid ? files.size() * (1L << 20) : 0);
        result.put("temporaryReserveBytes", hybrid ? RESERVE : 0); result.put("peakUnsavedBytesEstimate", peakUnsaved);
        result.put("unsavedBytesEstimate", currentUnsaved);
        result.put("cacheOverCapacityBytesEstimate", Math.max(0, used - capacity));
        result.put("peakDirtyThresholdOvershootBytesEstimate", Math.max(0, peakUnsaved - (1L << 20)));
        result.put("accountingComplete", false); result.put("hardProcessMemoryLimit", false);
        result.put("heapMaxBytes", Runtime.getRuntime().maxMemory());
        result.put("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        long disk = 0;
        for (Path file : files) { try { if (Files.exists(file)) disk += Files.size(file); } catch (IOException ignored) { } }
        result.put("diskBytes", disk);
        result.put("note", "Cache accounting is estimated; parser objects, query results, engine metadata, JVM/native memory and OS file cache are not hard-capped. Hybrid updates use staged rebuilds.");
        return result;
    }
    public Path directory() { return directory; }

    void scan(Runnable action) {
        int depth = scanDepth.get(); scanDepth.set(depth + 1);
        try { action.run(); } finally { if (depth == 0) scanDepth.remove(); else scanDepth.set(depth); }
    }

    @Override public void close() {
        synchronized (writer) {
            if (closed) return;
            for (PagedGraph graph : List.copyOf(graphs)) graph.close();
            synchronized (this) {
                for (Path file : List.copyOf(files)) deleteFile(file);
                cache.clear(); used = 0;
                if (directory != null) {
                    try { Files.delete(directory); } // Deliberately NOT recursive: never delete unknown files.
                    catch (IOException e) { throw new UncheckedIOException("cannot remove session directory " + directory, e); }
                }
                closed = true;
                if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
                    try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { }
                }
            }
        }
    }
}
