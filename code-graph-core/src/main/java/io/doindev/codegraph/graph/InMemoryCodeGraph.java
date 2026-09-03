package io.doindev.codegraph.graph;

import io.doindev.codegraph.model.Direction;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.query.ClosureHit;
import io.doindev.codegraph.query.ClosureResult;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.query.IndexStatus;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.store.GraphSink;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The default in-memory code property graph. Reads are lock-free: all state
 * lives in one immutable {@link State} published through a volatile reference; the single
 * writer builds a new state per {@link #apply(GraphDelta)} and swaps it, so readers never see
 * a half-patched graph.
 *
 * <p>Write cost: the top-level maps are shallow-copied per delta and only the entries a delta
 * touches are rebuilt (inner values are immutable and shared between generations). Bulk
 * indexing should therefore batch many files into one delta; per-file deltas are meant for
 * incremental watching, where they are rare.
 */
public final class InMemoryCodeGraph implements io.doindev.codegraph.store.ManagedGraph {

    public static final String ENGINE_VERSION = "0.0.1";

    /** Inner values (kind-maps, edge lists, owned sets) are immutable and shared across generations. */
    private record State(long generation,
                         Map<NodeId, Node> nodes,
                         Map<NodeId, Map<EdgeKind, List<Edge>>> out,
                         Map<NodeId, Map<EdgeKind, List<Edge>>> in,
                         Map<String, Set<NodeId>> ownedByFile,
                         long edgeCount,
                         Instant lastAppliedAt) {

        static final State EMPTY = new State(0L, Map.of(), Map.of(), Map.of(), Map.of(), 0L, null);
    }

    private volatile State state = State.EMPTY;
    private volatile int dirtyPending;
    private volatile Map<String, Integer> filesPerLang = Map.of();

    // ---- write path (single writer: the indexer) ----

    /** Apply one delta atomically; readers switch to the new graph in one volatile swap. */
    public synchronized void apply(GraphDelta delta) {
        State s = state;
        Map<NodeId, Node> nodes = new HashMap<>(s.nodes());
        Map<NodeId, Map<EdgeKind, List<Edge>>> out = new HashMap<>(s.out());
        Map<NodeId, Map<EdgeKind, List<Edge>>> in = new HashMap<>(s.in());
        Map<String, Set<NodeId>> owned = new HashMap<>(s.ownedByFile());
        long edgeCount = s.edgeCount();

        for (FileId file : delta.removedFiles()) {
            Set<NodeId> removed = owned.remove(file.relPath());
            if (removed == null) {
                continue;
            }
            for (NodeId id : removed) {
                nodes.remove(id);
                edgeCount -= dropAllEdges(id, out, in);
            }
        }
        for (Edge e : delta.removeEdges()) {
            if (removeEdge(e.from(), e, out) ) {
                removeEdge(e.to(), e, in);
                edgeCount--;
            }
        }
        for (Node n : delta.addNodes()) {
            nodes.put(n.id(), n);
            String relPath = n.relPath();
            if (relPath != null) {
                Set<NodeId> set = owned.get(relPath);
                Set<NodeId> grown = set == null ? new HashSet<>() : new HashSet<>(set);
                grown.add(n.id());
                owned.put(relPath, Set.copyOf(grown));
            }
        }
        for (Edge e : delta.addEdges()) {
            appendEdge(e.from(), e, out);
            appendEdge(e.to(), e, in);
            edgeCount++;
        }
        state = new State(delta.generation(), nodes, out, in, owned, edgeCount, Instant.now());
    }

    /** Stream the current graph, e.g. into a snapshot writer or a rehydrating mirror. */
    public void export(GraphSink sink) {
        State s = state;
        for (Node n : s.nodes().values()) {
            sink.node(n);
        }
        for (Map<EdgeKind, List<Edge>> byKind : s.out().values()) {
            for (List<Edge> edges : byKind.values()) {
                for (Edge e : edges) {
                    sink.edge(e);
                }
            }
        }
    }

    /** Generation of the currently published graph. */
    public long generation() {
        return state.generation();
    }

    /** Set by the incremental indexer: watched files changed but not yet re-indexed. */
    public void dirtyPending(int count) {
        this.dirtyPending = count;
    }

    /** Set by the indexer after each pass: indexed file count per language code. */
    public void filesPerLang(Map<String, Integer> counts) {
        this.filesPerLang = Map.copyOf(counts);
    }

    // ---- read path (lock-free) ----

    @Override
    public Optional<Node> node(NodeId id) {
        return Optional.ofNullable(state.nodes().get(id));
    }

    @Override
    public List<Node> findSymbols(String pattern, Set<NodeKind> kinds, String lang, int limit) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        String needle = pattern.toLowerCase();
        return state.nodes().values().stream()
                .filter(n -> kinds == null || kinds.isEmpty() || kinds.contains(n.kind()))
                .filter(n -> lang == null || lang.equals(n.lang()))
                .filter(n -> matches(n, needle))
                .sorted(Comparator.comparing(n -> n.id().value()))
                .limit(limit)
                .toList();
    }

    private static boolean matches(Node n, String needle) {
        if (n.name().toLowerCase().contains(needle)) {
            return true;
        }
        return n.id() instanceof SymbolId s && s.qualifiedName().toLowerCase().contains(needle);
    }

    @Override
    public List<Edge> edges(NodeId id, Direction direction, Set<EdgeKind> kinds) {
        State s = state;
        List<Edge> result = new ArrayList<>();
        if (direction == Direction.OUT || direction == Direction.BOTH) {
            collect(s.out().get(id), kinds, result);
        }
        if (direction == Direction.IN || direction == Direction.BOTH) {
            collect(s.in().get(id), kinds, result);
        }
        return List.copyOf(result);
    }

    @Override
    public ClosureResult closure(NodeId start, Direction direction, Set<EdgeKind> kinds,
                                 int maxDepth, int nodeLimit, float minConfidence) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        if (nodeLimit < 1) {
            throw new IllegalArgumentException("nodeLimit must be >= 1");
        }
        State s = state;
        List<ClosureHit> hits = new ArrayList<>();
        Set<NodeId> visited = new HashSet<>();
        visited.add(start);
        record Frontier(NodeId id, int depth) {}
        ArrayDeque<Frontier> queue = new ArrayDeque<>();
        queue.add(new Frontier(start, 0));

        while (!queue.isEmpty()) {
            Frontier cur = queue.poll();
            if (cur.depth() == maxDepth) {
                continue;
            }
            List<Edge> step = new ArrayList<>();
            if (direction == Direction.OUT || direction == Direction.BOTH) {
                collect(s.out().get(cur.id()), kinds, step);
            }
            if (direction == Direction.IN || direction == Direction.BOTH) {
                collect(s.in().get(cur.id()), kinds, step);
            }
            for (Edge e : step) {
                if (e.confidence() < minConfidence) {
                    continue;
                }
                NodeId next = e.from().equals(cur.id()) ? e.to() : e.from();
                if (!visited.add(next)) {
                    continue;
                }
                Node node = s.nodes().get(next);
                if (node == null) {
                    continue;
                }
                if (hits.size() >= nodeLimit) {
                    return new ClosureResult(hits, true);
                }
                hits.add(new ClosureHit(node, cur.depth() + 1, e));
                queue.add(new Frontier(next, cur.depth() + 1));
            }
        }
        return new ClosureResult(hits, false);
    }

    @Override
    public List<Node> allNodes(Set<NodeKind> kinds) {
        return state.nodes().values().stream()
                .filter(n -> kinds == null || kinds.isEmpty() || kinds.contains(n.kind()))
                .toList();
    }

    @Override
    public IndexStatus status() {
        State s = state;
        int symbols = (int) s.nodes().keySet().stream().filter(id -> id instanceof SymbolId).count();
        String stateName = s.generation() == 0 ? "empty" : "ready";
        return new IndexStatus(stateName, s.generation(), s.ownedByFile().size(), symbols,
                s.edgeCount(), dirtyPending, s.lastAppliedAt(), filesPerLang, ENGINE_VERSION);
    }

    // ---- copy-on-write helpers (inner values are immutable; touched entries are rebuilt) ----

    private static void collect(Map<EdgeKind, List<Edge>> byKind, Set<EdgeKind> kinds, List<Edge> into) {
        if (byKind == null) {
            return;
        }
        if (kinds == null || kinds.isEmpty()) {
            byKind.values().forEach(into::addAll);
        } else {
            for (EdgeKind kind : kinds) {
                List<Edge> edges = byKind.get(kind);
                if (edges != null) {
                    into.addAll(edges);
                }
            }
        }
    }

    private static void appendEdge(NodeId key, Edge e, Map<NodeId, Map<EdgeKind, List<Edge>>> adjacency) {
        Map<EdgeKind, List<Edge>> byKind = adjacency.get(key);
        EnumMap<EdgeKind, List<Edge>> copy =
                byKind == null ? new EnumMap<>(EdgeKind.class) : new EnumMap<>(byKind);
        List<Edge> existing = copy.get(e.kind());
        List<Edge> grown;
        if (existing == null) {
            grown = List.of(e);
        } else {
            List<Edge> tmp = new ArrayList<>(existing);
            tmp.add(e);
            grown = List.copyOf(tmp);
        }
        copy.put(e.kind(), grown);
        adjacency.put(key, copy);
    }

    /** Remove {@code e} (matched by from/to/kind) from {@code adjacency[key]}; returns whether it was present. */
    private static boolean removeEdge(NodeId key, Edge e, Map<NodeId, Map<EdgeKind, List<Edge>>> adjacency) {
        Map<EdgeKind, List<Edge>> byKind = adjacency.get(key);
        if (byKind == null) {
            return false;
        }
        List<Edge> edges = byKind.get(e.kind());
        if (edges == null) {
            return false;
        }
        List<Edge> shrunk = edges.stream()
                .filter(x -> !(x.from().equals(e.from()) && x.to().equals(e.to())))
                .toList();
        if (shrunk.size() == edges.size()) {
            return false;
        }
        EnumMap<EdgeKind, List<Edge>> copy = new EnumMap<>(byKind);
        if (shrunk.isEmpty()) {
            copy.remove(e.kind());
        } else {
            copy.put(e.kind(), shrunk);
        }
        if (copy.isEmpty()) {
            adjacency.remove(key);
        } else {
            adjacency.put(key, copy);
        }
        return true;
    }

    /** Remove every edge touching {@code id} in either direction; returns the number of distinct edges dropped. */
    private static long dropAllEdges(NodeId id,
                                     Map<NodeId, Map<EdgeKind, List<Edge>>> out,
                                     Map<NodeId, Map<EdgeKind, List<Edge>>> in) {
        long dropped = 0;
        Map<EdgeKind, List<Edge>> outgoing = out.remove(id);
        if (outgoing != null) {
            for (List<Edge> edges : outgoing.values()) {
                dropped += edges.size();
                for (Edge e : edges) {
                    removeEdge(e.to(), e, in);
                }
            }
        }
        Map<EdgeKind, List<Edge>> incoming = in.remove(id);
        if (incoming != null) {
            for (List<Edge> edges : incoming.values()) {
                dropped += edges.size();
                for (Edge e : edges) {
                    removeEdge(e.from(), e, out);
                }
            }
        }
        return dropped;
    }
}
