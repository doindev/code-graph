package io.doindev.codegraph.store.arango;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.entity.EdgeDefinition;
import com.arangodb.model.AqlQueryOptions;
import com.arangodb.model.StreamTransactionOptions;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.store.GraphSink;
import io.doindev.codegraph.store.GraphStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ArangoDB write-through mirror for the code property graph: vertex collection {@code nodes} and
 * edge collection {@code edges} inside the named graph {@code codegraph}, plus a {@code meta}
 * collection holding the applied generation.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link #apply} is called in generation order by a single writer and runs inside one
 *       stream transaction over {@code nodes}/{@code edges}/{@code meta} — removals, upserts and
 *       the generation bump commit atomically (aborted wholesale on failure).</li>
 *   <li>Arango document keys forbid many characters that appear in canonical node ids, so keys
 *       are SHA-256 hex digests of the id; the full canonical {@link NodeId#value()} string is
 *       stored in the {@code id} field and is what {@link #replay} parses back.</li>
 *   <li>Kind names are stored as enum <em>name strings</em> (never ordinals); metrics/attrs as
 *       compact {@link Codecs} strings (JSON cannot carry {@code Float.NaN} natively), so replay
 *       reconstructs {@link Node}/{@link Edge} records with exact record equality.</li>
 * </ul>
 */
public final class ArangoGraphStore implements GraphStore {

    private static final String GRAPH = "codegraph";
    private static final String NODES = "nodes";
    private static final String EDGES = "edges";
    private static final String META = "meta";

    private final ArangoDatabase db;
    private final ArangoDB ownedClient;
    private volatile long appliedGeneration;

    /** Wraps a caller-owned database handle; {@link #close()} does <em>not</em> shut down its client. */
    public ArangoGraphStore(ArangoDatabase db) {
        this(db, null);
    }

    /** Connects with a store-owned client (no auth); {@link #close()} shuts it down. */
    public static ArangoGraphStore connect(String host, int port, String database) {
        ArangoDB client = new ArangoDB.Builder().host(host, port).build();
        return new ArangoGraphStore(client.db(database), client);
    }

    private ArangoGraphStore(ArangoDatabase db, ArangoDB ownedClient) {
        this.db = db;
        this.ownedClient = ownedClient;
        if (!db.exists()) {
            db.create();
        }
        if (!db.graph(GRAPH).exists()) {
            db.createGraph(GRAPH, List.of(new EdgeDefinition().collection(EDGES).from(NODES).to(NODES)));
        }
        if (!db.collection(META).exists()) {
            db.createCollection(META);
        }
        Map<?, ?> meta = db.collection(META).getDocument(META, Map.class);
        this.appliedGeneration = meta == null ? 0L : ((Number) meta.get("appliedGeneration")).longValue();
    }

    @Override
    public synchronized void apply(GraphDelta delta) {
        String tx = db.beginStreamTransaction(
                new StreamTransactionOptions().writeCollections(NODES, EDGES, META)).getId();
        try {
            if (!delta.removedFiles().isEmpty()) {
                List<String> removed = delta.removedFiles().stream().map(FileId::relPath).toList();
                List<String> removedDocIds = query(tx, String.class,
                        "FOR n IN nodes FILTER n.relPath IN @removed REMOVE n IN nodes RETURN OLD._id",
                        Map.of("removed", removed));
                if (!removedDocIds.isEmpty()) {
                    query(tx, Void.class,
                            "FOR e IN edges FILTER e._from IN @ids OR e._to IN @ids REMOVE e IN edges",
                            Map.of("ids", removedDocIds));
                }
            }
            if (!delta.removeEdges().isEmpty()) {
                List<String> keys = delta.removeEdges().stream().map(ArangoGraphStore::edgeKey).toList();
                query(tx, Void.class,
                        "FOR k IN @keys REMOVE k IN edges OPTIONS { ignoreErrors: true }",
                        Map.of("keys", keys));
            }
            if (!delta.addNodes().isEmpty()) {
                List<Map<String, Object>> docs = delta.addNodes().stream()
                        .map(ArangoGraphStore::nodeDoc).toList();
                query(tx, Void.class,
                        "FOR d IN @docs INSERT d INTO nodes OPTIONS { overwriteMode: 'replace' }",
                        Map.of("docs", docs));
            }
            if (!delta.addEdges().isEmpty()) {
                List<Map<String, Object>> docs = delta.addEdges().stream()
                        .map(ArangoGraphStore::edgeDoc).toList();
                query(tx, Void.class,
                        "FOR d IN @docs INSERT d INTO edges OPTIONS { overwriteMode: 'replace' }",
                        Map.of("docs", docs));
            }
            query(tx, Void.class,
                    "INSERT { _key: 'meta', appliedGeneration: @gen } INTO meta"
                            + " OPTIONS { overwriteMode: 'replace' }",
                    Map.of("gen", delta.generation()));
            db.commitStreamTransaction(tx);
            appliedGeneration = delta.generation();
        } catch (RuntimeException e) {
            try {
                db.abortStreamTransaction(tx);
            } catch (RuntimeException ignored) {
                // the caller's exception is the interesting one
            }
            throw new IllegalStateException("failed to apply delta " + delta.generation(), e);
        }
    }

    @Override
    public long appliedGeneration() {
        return appliedGeneration;
    }

    @Override
    public synchronized void replay(GraphSink sink) {
        for (Map<String, Object> doc : query(null, mapClass(), "FOR n IN nodes RETURN n", Map.of())) {
            sink.node(toNode(doc));
        }
        for (Map<String, Object> doc : query(null, mapClass(), "FOR e IN edges RETURN e", Map.of())) {
            sink.edge(toEdge(doc));
        }
    }

    @Override
    public synchronized void close() {
        if (ownedClient != null) {
            ownedClient.shutdown();
        }
    }

    // ---- document mapping ----

    private static Map<String, Object> nodeDoc(Node n) {
        SourceSpan span = n.span();
        Map<String, Object> doc = new HashMap<>();
        doc.put("_key", key(n.id().value()));
        doc.put("id", n.id().value());
        doc.put("kind", n.kind().name());
        doc.put("name", n.name());
        doc.put("sig", n.displaySignature());
        doc.put("relPath", n.relPath());
        doc.put("spanPath", span == null ? null : span.relPath());
        doc.put("startLine", span == null ? null : span.startLine());
        doc.put("startCol", span == null ? null : span.startCol());
        doc.put("endLine", span == null ? null : span.endLine());
        doc.put("endCol", span == null ? null : span.endCol());
        doc.put("metrics", Codecs.metrics(n.metrics()));
        doc.put("attrs", Codecs.attrs(n.attrs()));
        return doc;
    }

    private static Map<String, Object> edgeDoc(Edge e) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("_key", edgeKey(e));
        doc.put("_from", NODES + "/" + key(e.from().value()));
        doc.put("_to", NODES + "/" + key(e.to().value()));
        doc.put("fromId", e.from().value());
        doc.put("toId", e.to().value());
        doc.put("kind", e.kind().name());
        doc.put("confidence", (double) e.confidence());
        doc.put("attrs", Codecs.attrs(e.attrs()));
        return doc;
    }

    private static Node toNode(Map<String, Object> doc) {
        Object spanPath = doc.get("spanPath");
        SourceSpan span = spanPath == null ? null : new SourceSpan((String) spanPath,
                asInt(doc.get("startLine")), asInt(doc.get("startCol")),
                asInt(doc.get("endLine")), asInt(doc.get("endCol")));
        return new Node(NodeId.parse((String) doc.get("id")),
                NodeKind.valueOf((String) doc.get("kind")),
                (String) doc.get("name"),
                (String) doc.get("sig"),
                span,
                Codecs.parseMetrics((String) doc.get("metrics")),
                Codecs.parseAttrs((String) doc.get("attrs")));
    }

    private static Edge toEdge(Map<String, Object> doc) {
        return new Edge(NodeId.parse((String) doc.get("fromId")),
                NodeId.parse((String) doc.get("toId")),
                EdgeKind.valueOf((String) doc.get("kind")),
                ((Number) doc.get("confidence")).floatValue(),
                Codecs.parseAttrs((String) doc.get("attrs")));
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }

    /** Exact from/to/kind identity — matches {@code removeEdges} semantics and dedupes merges. */
    private static String edgeKey(Edge e) {
        return key(e.from().value() + " " + e.to().value() + " " + e.kind().name());
    }

    /** Arango keys forbid many id characters; use a SHA-256 hex digest, full id kept as a field. */
    private static String key(String id) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(id.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- query helper ----

    @SuppressWarnings("unchecked")
    private static Class<Map<String, Object>> mapClass() {
        return (Class<Map<String, Object>>) (Class<?>) Map.class;
    }

    private <T> List<T> query(String tx, Class<T> type, String aql, Map<String, Object> bindVars) {
        AqlQueryOptions options = new AqlQueryOptions();
        if (tx != null) {
            options.streamTransactionId(tx);
        }
        try (ArangoCursor<T> cursor = db.query(aql, type, bindVars, options)) {
            List<T> result = new ArrayList<>();
            cursor.forEachRemaining(result::add);
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("query failed: " + aql, e);
        }
    }
}
