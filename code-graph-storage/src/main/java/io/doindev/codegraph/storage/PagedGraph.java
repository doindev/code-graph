package io.doindev.codegraph.storage;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.query.*;
import io.doindev.codegraph.store.*;
import org.h2.mvstore.*;
import org.h2.mvstore.type.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.*;

/** Disk-first immutable generations. Only explicitly admitted hot records enter the shared cache. */
public final class PagedGraph implements ManagedGraph {
    public static final int MAX_RECORD_BYTES = 8 << 20;
    public static final int MAX_RESULTS = 20_000;
    // A split target, not a maximum record size. With the cancellation-safe async
    // channel, 4 KiB pages cause excessive small reads during streaming searches.
    // 32 KiB amortizes those reads without adding a page cache or changing the
    // shared allowance / 1 MiB dirty-write threshold. Keep large-record bounds.
    static final int PAGE_SPLIT_BYTES = 32 << 10;
    private final GraphStorage owner;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private Generation active;
    private volatile boolean closed;
    private volatile int dirty;
    private volatile boolean updateRecoveryFailed;

    PagedGraph(GraphStorage owner) { this.owner = owner; }
    @Override public int materializationLimit() { return MAX_RESULTS; }
    public static String encodeKey(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    public static String sequence(long n) { return String.format(Locale.ROOT, "%020d", n); }
    private static String nodeKey(NodeId id) { return "n/" + encodeKey(id.value()); }
    private static String edgePrefix(NodeId id, boolean out) { return (out ? "o/" : "i/") + encodeKey(id.value()) + "/"; }

    @Override public <T> T read(Supplier<T> query) {
        return owner.query(() -> {
            lock.readLock().lock();
            try {
                if (closed) throw new IllegalStateException("project graph was removed");
                if (updateRecoveryFailed) throw new IllegalStateException("hybrid update recovery failed; reindex this project");
                return query.get();
            }
            finally { lock.readLock().unlock(); }
        });
    }

    /** Build with bounded writes, then swap the entire generation. Failure leaves the old one readable. */
    public <T> T rebuild(Function<Builder, T> producer) {
        if (lock.getReadHoldCount() > 0) throw new IllegalStateException("cannot rebuild inside a graph read");
        return owner.write(() -> {
            if (closed) throw new IllegalStateException("project graph was removed");
            Generation staged = new Generation(active == null ? 1 : active.number + 1);
            Builder builder = new Builder(staged);
            boolean published = false;
            try {
                T result = producer.apply(builder);
                builder.open = false;
                staged.freeze();
                lock.writeLock().lock();
                try {
                    Generation old = active;
                    active = staged; published = true; updateRecoveryFailed = false;
                    if (old != null) old.close();
                } finally { lock.writeLock().unlock(); }
                return result;
            } finally { builder.open = false; if (!published) staged.close(); owner.unsaved(0); }
        });
    }

    /**
     * Stage copy-on-write pages in the existing file. Queries retain the old
     * committed snapshot until the short publication barrier. Intermediate
     * flushes bound dirty memory; they never publish a partial graph.
     */
    public <T> T update(Function<Builder,T> producer) {
        if (lock.getReadHoldCount() > 0) throw new IllegalStateException("cannot update inside a graph read");
        return owner.write(() -> {
            if (closed) throw new IllegalStateException("project graph was removed");
            if (updateRecoveryFailed) throw new IllegalStateException("hybrid update recovery failed; reindex this project");
            if (active == null) throw new IllegalStateException("initial graph generation is required");
            Generation previous = active;
            Generation staged = new Generation(previous);
            Builder builder = new Builder(staged);
            boolean published = false;
            try {
                T result = producer.apply(builder);
                builder.checkOpen();
                if (!builder.changed) return result;
                builder.open = false;
                staged.freeze();
                lock.writeLock().lock();
                try {
                    checkInterrupted();
                    active = staged;
                    published = true;
                    previous.releaseSnapshot();
                } finally { lock.writeLock().unlock(); }
                return result;
            } finally {
                builder.open = false;
                if (!published && builder.changed) {
                    // Finish recovery even when the requester cancelled. Restore
                    // the signal afterward; never interrupt rollback halfway.
                    boolean interrupted = Thread.interrupted();
                    try {
                        staged.releaseSnapshot();
                        previous.store.rollbackTo(previous.committedVersion);
                    }
                    catch (RuntimeException recovery) {
                        updateRecoveryFailed = true;
                        throw new IllegalStateException("hybrid update rollback failed; reindex this project", recovery);
                    } finally {
                        owner.unsaved(0);
                        if (interrupted) Thread.currentThread().interrupt();
                    }
                }
                owner.unsaved(0);
            }
        });
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException("graph indexing was interrupted");
    }

    public byte[] document(String key) { return read(() -> active==null?null:active.map.get("a/"+key)); }
    public void documents(String prefix, BiConsumer<String,byte[]> visitor) {
        read(() -> { if(active!=null)owner.scan(() -> active.scan("a/"+prefix,(key,value)->visitor.accept(key.substring(2),value)));return null; });
    }

    public final class Builder {
        private final Generation generation;
        private boolean open = true;
        private boolean changed;
        private void checkOpen() {
            if (!open) throw new IllegalStateException("staged builder is no longer active");
            checkInterrupted();
        }
        private Builder(Generation generation) { this.generation = generation; }
        public void node(Node node) {
            checkOpen();
            byte[] previous = generation.map.get(nodeKey(node.id()));
            changed = true;
            generation.put(nodeKey(node.id()), RecordCodec.node(node));
            if (previous == null && node.id() instanceof SymbolId && node.kind()!=NodeKind.DATABASE_MAPPING) generation.symbols++;
        }
        public void edge(Edge edge) {
            checkOpen();
            changed = true;
            String suffix = String.format(Locale.ROOT, "%02d/", edge.kind().ordinal()) + sequence(generation.nextEdge++);
            byte[] bytes = RecordCodec.edge(edge);
            generation.put(edgePrefix(edge.from(), true) + suffix, bytes);
            generation.put(edgePrefix(edge.to(), false) + suffix, bytes);
            generation.edges++;
        }
        public void file(String lang) { checkOpen(); changed = true; generation.files++; generation.languages.merge(lang, 1, Integer::sum); }
        public void removeFile(String lang) {
            checkOpen(); changed = true; generation.files--;
            generation.languages.compute(lang,(key,count)->count==null||count<=1?null:count-1);
        }
        public void removeNode(NodeId id) {
            checkOpen();
            byte[] bytes = generation.map.get(nodeKey(id));
            if (bytes == null) return;
            Node node = RecordCodec.node(bytes);
            changed = true; generation.remove(nodeKey(id));
            if (id instanceof SymbolId && node.kind()!=NodeKind.DATABASE_MAPPING) generation.symbols--;
        }
        /** Streaming removal retains both adjacency indexes and duplicate occurrence counts. */
        public void removeOutgoing(NodeId id) {
            checkOpen();
            String prefix = edgePrefix(id,true);
            generation.scan(prefix,(key,value)->{
                checkOpen();
                Edge edge = RecordCodec.edge(value);
                changed = true;
                generation.remove(edgePrefix(edge.to(),false)+key.substring(prefix.length()));
                generation.remove(key);
                generation.edges--;
            });
        }
        public void auxiliary(String key, byte[] bytes) {
            checkOpen(); changed = true; generation.put("a/" + key, bytes);
        }
        public void removeAuxiliary(String key) {
            checkOpen();
            if (generation.map.containsKey("a/"+key)) { changed = true; generation.remove("a/"+key); }
        }
        public byte[] auxiliary(String key) { checkOpen(); return generation.map.get("a/" + key); }
        public void scanAuxiliary(String prefix, BiConsumer<String, byte[]> visitor) {
            checkOpen();
            generation.scan("a/" + prefix, visitor);
        }
    }

    private final class Generation implements AutoCloseable {
        final Path path;
        final String cachePrefix;
        final MVStore store;
        final MVMap<String, byte[]> writerMap;
        MVMap<String, byte[]> map;
        final long number;
        final Map<String, Integer> languages = new HashMap<>();
        int files, symbols;
        long edges;
        long nextEdge, committedVersion;
        MVStore.TxCounter versionLease;
        Instant indexed;
        Generation(long number) {
            this.number = number;
            path = owner.newFile(); cachePrefix = path.getFileName() + "/g" + number + "/";
            MVStore opened = null;
            try {
                // H2's async channel waits through Java interrupts without
                // closing the shared file. Cancellation is checked between
                // bounded operations, never by destroying an active snapshot.
                opened = new MVStore.Builder().fileName("async:" + path).cacheSize(0)
                        .compress().autoCommitDisabled().pageSplitSize(PAGE_SPLIT_BYTES).open();
                if (opened.getCacheSize() != 0) throw new IllegalStateException("engine page cache must be disabled");
                opened.setRetentionTime(0); // Explicit version leases protect published snapshots.
                opened.setVersionsToKeep(1);
                store = opened;
                writerMap = store.openMap("records", new MVMap.Builder<String, byte[]>()
                        .keyType(StringDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
                map = writerMap;
            } catch (RuntimeException | Error e) {
                if (opened != null) opened.closeImmediately();
                owner.deleteFile(path); throw e;
            }
        }
        Generation(Generation previous) {
            number = previous.number + 1;
            path = previous.path; store = previous.store; writerMap = previous.writerMap; map = writerMap;
            cachePrefix = path.getFileName() + "/g" + number + "/";
            files = previous.files; symbols = previous.symbols; edges = previous.edges;
            nextEdge = previous.nextEdge; languages.putAll(previous.languages);
        }
        void freeze() {
            checkInterrupted();
            // Pin the version BEFORE committing; openVersion refers to that
            // version's immutable data, not the next writable head.
            versionLease = store.registerVersionUsage();
            store.commit();
            committedVersion = store.getCurrentVersion();
            map = writerMap.openVersion(versionLease.version);
            indexed = Instant.now();
            owner.unsaved(0);
        }
        void put(String key, byte[] bytes) {
            if (key.length() > 32_768 || bytes.length > MAX_RECORD_BYTES)
                throw new IllegalArgumentException("graph record exceeds hybrid safety bound (8 MiB value / 32K key)");
            writerMap.put(key, bytes);
            flushIfNeeded();
        }
        void remove(String key) {
            writerMap.remove(key);
            flushIfNeeded();
        }
        void flushIfNeeded() {
            long unsaved = store.getUnsavedMemory(); owner.unsaved(unsaved);
            if (unsaved >= (1 << 20)) { store.commit(); owner.unsaved(0); }
        }
        byte[] get(String key) {
            byte[] bytes = owner.cached(cachePrefix + key);
            if (bytes == null) {
                bytes = map.get(key);
                if (bytes != null) owner.cache(cachePrefix + key, bytes);
            }
            return bytes;
        }
        void scan(String prefix, BiConsumer<String, byte[]> visitor) {
            // A staged scan may modify other records and flush repeatedly.
            // Pin its cursor root too, including during an initial full build
            // where there is not yet a published-generation lease.
            var scanLease = map == writerMap ? store.registerVersionUsage() : null;
            try {
                var cursor = map.cursor(prefix);
                while (cursor.hasNext()) {
                    checkInterrupted();
                    String key = cursor.next();
                    if (!key.startsWith(prefix)) break;
                    visitor.accept(key, cursor.getValue());
                }
            } finally { if (scanLease != null) store.deregisterVersionUsage(scanLease); }
        }
        @Override public void close() {
            releaseSnapshot(); store.closeImmediately(); owner.deleteFile(path);
        }
        void releaseSnapshot() {
            if (versionLease != null) { store.deregisterVersionUsage(versionLease); versionLease = null; }
            owner.invalidate(cachePrefix);
        }
    }

    @Override public Optional<Node> node(NodeId id) {
        return read(() -> {
            byte[] bytes = active == null ? null : active.get(nodeKey(id));
            return bytes == null ? Optional.empty() : Optional.of(RecordCodec.node(bytes));
        });
    }

    @Override public List<Node> findSymbols(String pattern, Set<NodeKind> kinds, String lang, int limit) {
        if (pattern == null || pattern.isBlank() || limit < 1 || limit > MAX_RESULTS)
            throw new IllegalArgumentException("nonblank pattern and limit 1.." + MAX_RESULTS + " required");
        return read(() -> {
            if (active == null) return List.of();
            String key = active.cachePrefix + "search/" + Base64.getUrlEncoder().encodeToString(
                    RecordCodec.encode(Arrays.asList(pattern, kinds, lang, limit)));
            byte[] cached = owner.cached(key);
            if (cached != null) return Arrays.stream(RecordCodec.decode(cached, RecordCodec.N[].class)).map(RecordCodec.N::node).toList();
            String needle = pattern.toLowerCase();
            Comparator<Node> order = Comparator.comparing(n -> n.id().value());
            record Weighted(Node node, int bytes) { }
            var best = new PriorityQueue<Weighted>(Comparator.comparing(Weighted::node, order).reversed());
            long[] resultBytes = {0};
            scanNodes(kinds, n -> {
                if ((lang == null || lang.equals(n.lang())) && (n.name().toLowerCase().contains(needle)
                        || n.id() instanceof SymbolId s && s.qualifiedName().toLowerCase().contains(needle))) {
                    int bytes = RecordCodec.node(n).length;
                    best.add(new Weighted(n, bytes)); resultBytes[0] += bytes;
                    if (best.size() > limit) resultBytes[0] -= best.poll().bytes();
                    if (resultBytes[0] > MAX_RECORD_BYTES) throw new IllegalArgumentException("search results exceed hybrid byte bound; lower limit");
                }
            });
            List<Node> result = best.stream().map(Weighted::node).sorted(order).toList();
            byte[] encoded = RecordCodec.encode(result.stream().map(RecordCodec.N::new).toList());
            if (encoded.length <= MAX_RECORD_BYTES) owner.cache(key, encoded);
            return result;
        });
    }
    @Override public List<Edge> edges(NodeId id, Direction direction, Set<EdgeKind> kinds) {
        return read(() -> {
            if (active == null) return List.of();
            var result = new ArrayList<Edge>();
            if (direction != Direction.IN) collect(id, true, kinds, result);
            if (direction != Direction.OUT) collect(id, false, kinds, result);
            return List.copyOf(result);
        });
    }
    @Override public void scanEdges(NodeId id, Direction direction, Set<EdgeKind> kinds, Consumer<Edge> visitor) {
        read(() -> {
            if (active != null) owner.scan(() -> {
                for (boolean out : new boolean[]{true,false}) {
                    if (out && direction == Direction.IN || !out && direction == Direction.OUT) continue;
                    for (EdgeKind kind : EdgeKind.values()) {
                        if (kinds != null && !kinds.isEmpty() && !kinds.contains(kind)) continue;
                        String prefix = edgePrefix(id,out) + String.format(Locale.ROOT,"%02d/",kind.ordinal());
                        active.scan(prefix,(key,value)->visitor.accept(RecordCodec.edge(value)));
                    }
                }
            });
            return null;
        });
    }
    private void collect(NodeId id, boolean out, Set<EdgeKind> kinds, List<Edge> result) {
        Collection<EdgeKind> selected = kinds == null || kinds.isEmpty() ? List.of(EdgeKind.values()) : kinds;
        for (EdgeKind kind : selected) {
            String prefix = edgePrefix(id, out) + String.format(Locale.ROOT, "%02d/", kind.ordinal());
            String key = active.cachePrefix + "adj/" + prefix;
            byte[] cached = owner.cached(key);
            List<Edge> edges = new ArrayList<>();
            if (cached != null) Arrays.stream(RecordCodec.decode(cached, RecordCodec.E[].class)).map(RecordCodec.E::edge).forEach(edges::add);
            else {
                long[] bytes = {0};
                active.scan(prefix, (k, v) -> {
                    bytes[0] += v.length;
                    if (edges.size() >= MAX_RESULTS || bytes[0] > MAX_RECORD_BYTES)
                        throw new IllegalArgumentException("adjacency exceeds hybrid query bound; narrow edge kinds");
                    edges.add(RecordCodec.edge(v));
                });
                owner.cache(key, RecordCodec.encode(edges.stream().map(RecordCodec.E::new).toList()));
            }
            if (result.size() + edges.size() > MAX_RESULTS) throw new IllegalArgumentException("adjacency exceeds hybrid query bound");
            result.addAll(edges);
        }
    }

    @Override public ClosureResult closure(NodeId start, Direction direction, Set<EdgeKind> kinds, int depth, int limit, float confidence) {
        if (depth < 1 || limit < 1 || limit > MAX_RESULTS) throw new IllegalArgumentException("positive depth and limit <= " + MAX_RESULTS + " required");
        return read(() -> {
            record Step(NodeId id, int depth) { }
            var queue = new ArrayDeque<Step>(); var visited = new HashSet<NodeId>(); var hits = new ArrayList<ClosureHit>();
            long resultBytes = 0;
            queue.add(new Step(start, 0)); visited.add(start);
            while (!queue.isEmpty()) {
                Step cur = queue.remove(); if (cur.depth() == depth) continue;
                for (Edge edge : edges(cur.id(), direction, kinds)) {
                    if (edge.confidence() < confidence) continue;
                    NodeId next = edge.from().equals(cur.id()) ? edge.to() : edge.from();
                    if (visited.contains(next)) continue;
                    Node node = node(next).orElse(null); if (node == null) continue;
                    if (hits.size() >= limit) return new ClosureResult(hits, true);
                    resultBytes += RecordCodec.node(node).length + RecordCodec.edge(edge).length;
                    if (resultBytes > MAX_RECORD_BYTES) throw new IllegalArgumentException("traversal exceeds hybrid byte bound; lower node limit");
                    visited.add(next); hits.add(new ClosureHit(node, cur.depth() + 1, edge)); queue.add(new Step(next, cur.depth() + 1));
                }
            }
            return new ClosureResult(hits, false);
        });
    }
    @Override public void scanNodes(Set<NodeKind> kinds, Consumer<Node> visitor) {
        read(() -> { if (active != null) owner.scan(() -> active.scan("n/", (k, v) -> {
            Node node = RecordCodec.node(v);
            if (kinds == null || kinds.isEmpty() || kinds.contains(node.kind())) visitor.accept(node);
        })); return null; });
    }
    @Override public List<Node> allNodes(Set<NodeKind> kinds) {
        var nodes = new ArrayList<Node>();
        long[] bytes = {0};
        scanNodes(kinds, node -> {
            if (nodes.size() >= MAX_RESULTS) throw new IllegalArgumentException("analysis needs more than " + MAX_RESULTS + " materialized nodes; use a scoped or streaming query in hybrid mode");
            bytes[0] += RecordCodec.node(node).length;
            if (bytes[0] > MAX_RECORD_BYTES) throw new IllegalArgumentException("analysis exceeds hybrid byte bound; use a scoped or streaming query");
            nodes.add(node);
        });
        return List.copyOf(nodes);
    }
    @Override public IndexStatus status() {
        return read(() -> active == null ? IndexStatus.empty("hybrid-mvstore-1") :
                new IndexStatus("ready", active.number, active.files, active.symbols, active.edges, dirty,
                        active.indexed, active.languages, "hybrid-mvstore-1"));
    }
    @Override public long generation() { return status().generation(); }
    @Override public void dirtyPending(int count) { dirty = count; }
    @Override public void filesPerLang(Map<String, Integer> counts) { throw new UnsupportedOperationException("publish metadata with the generation"); }
    @Override public void apply(GraphDelta delta) { throw new UnsupportedOperationException("hybrid writes require a staged builder"); }
    @Override public void close() {
        if (closed) return;
        if (lock.getReadHoldCount() > 0) throw new IllegalStateException("cannot close inside a graph read");
        owner.write(() -> {
            lock.writeLock().lock();
            try { if (!closed) { if (active != null) active.close(); active = null; closed = true; owner.removed(this); } }
            finally { lock.writeLock().unlock(); }
            return null;
        });
    }
}
