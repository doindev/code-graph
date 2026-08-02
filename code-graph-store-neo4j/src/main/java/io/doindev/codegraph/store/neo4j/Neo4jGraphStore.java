package io.doindev.codegraph.store.neo4j;

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
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Neo4j (Bolt) write-through mirror for the code property graph.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link #apply} is called in generation order by a single writer and runs as one managed
 *       transaction — file-scoped {@code DETACH DELETE}, exact edge removals, batched
 *       {@code UNWIND} merges and the {@code :Meta} generation bump commit atomically.</li>
 *   <li>Every graph node carries the shared {@code :CodeNode} label (unique constraint on
 *       {@code id}) plus one label per {@link NodeKind}; relationship types are the
 *       {@link EdgeKind} names — enum <em>names</em>, never ordinals.</li>
 *   <li>Node ids are stored as canonical {@link NodeId#value()} strings; metrics/attrs as
 *       compact {@link Codecs} strings, so {@link #replay} reconstructs records exactly.</li>
 *   <li>Edges whose endpoints are not (yet) known nodes keep bare {@code :CodeNode} placeholders
 *       (id only); replay skips placeholders as nodes but still emits their edges, matching the
 *       in-memory engine's tolerance of dangling endpoints.</li>
 * </ul>
 */
public final class Neo4jGraphStore implements GraphStore {

    private final Driver driver;
    private final boolean ownsDriver;
    private volatile long appliedGeneration;

    /** Wraps a caller-owned driver; {@link #close()} does <em>not</em> close it. */
    public Neo4jGraphStore(Driver driver) {
        this(driver, false);
    }

    /** Connects with a store-owned driver; {@link #close()} closes it. */
    public static Neo4jGraphStore connect(String uri, AuthToken auth) {
        return new Neo4jGraphStore(GraphDatabase.driver(uri, auth), true);
    }

    private Neo4jGraphStore(Driver driver, boolean ownsDriver) {
        this.driver = driver;
        this.ownsDriver = ownsDriver;
        try (Session session = driver.session()) {
            session.run("CREATE CONSTRAINT code_node_id IF NOT EXISTS"
                    + " FOR (n:CodeNode) REQUIRE n.id IS UNIQUE").consume();
            this.appliedGeneration = session.executeRead(tx -> {
                Value g = tx.run("OPTIONAL MATCH (m:Meta {id: 'meta'})"
                        + " RETURN m.appliedGeneration AS g").single().get("g");
                return g.isNull() ? 0L : g.asLong();
            });
        }
    }

    @Override
    public synchronized void apply(GraphDelta delta) {
        try (Session session = driver.session()) {
            session.executeWrite(tx -> {
                if (!delta.removedFiles().isEmpty()) {
                    List<String> removed = delta.removedFiles().stream()
                            .map(FileId::relPath).toList();
                    tx.run("MATCH (n:CodeNode) WHERE n.relPath IN $removed DETACH DELETE n",
                            Map.of("removed", removed)).consume();
                }
                groupByKind(delta.removeEdges()).forEach((kind, rows) -> tx.run(
                        "UNWIND $rows AS row"
                                + " MATCH (a:CodeNode {id: row.fromId})-[r:`" + kind.name()
                                + "`]->(b:CodeNode {id: row.toId}) DELETE r",
                        Map.of("rows", rows)).consume());
                nodeRowsByKind(delta.addNodes()).forEach((kind, rows) -> tx.run(
                        "UNWIND $rows AS row MERGE (n:CodeNode {id: row.id})"
                                + " SET n = row.props SET n:`" + kind.name() + "`",
                        Map.of("rows", rows)).consume());
                groupByKind(delta.addEdges()).forEach((kind, rows) -> tx.run(
                        "UNWIND $rows AS row"
                                + " MERGE (a:CodeNode {id: row.fromId})"
                                + " MERGE (b:CodeNode {id: row.toId})"
                                + " MERGE (a)-[r:`" + kind.name() + "`]->(b)"
                                + " SET r.confidence = row.confidence, r.attrs = row.attrs",
                        Map.of("rows", rows)).consume());
                tx.run("MERGE (m:Meta {id: 'meta'}) SET m.appliedGeneration = $gen",
                        Map.of("gen", delta.generation())).consume();
                return null;
            });
        }
        appliedGeneration = delta.generation();
    }

    @Override
    public long appliedGeneration() {
        return appliedGeneration;
    }

    @Override
    public synchronized void replay(GraphSink sink) {
        try (Session session = driver.session()) {
            session.executeRead(tx -> {
                Result nodes = tx.run(
                        "MATCH (n:CodeNode) WHERE n.kind IS NOT NULL RETURN n {.*} AS props");
                while (nodes.hasNext()) {
                    sink.node(toNode(nodes.next().get("props").asMap()));
                }
                Result edges = tx.run("MATCH (a:CodeNode)-[r]->(b:CodeNode)"
                        + " RETURN a.id AS fromId, b.id AS toId, type(r) AS kind,"
                        + " r.confidence AS confidence, r.attrs AS attrs");
                while (edges.hasNext()) {
                    sink.edge(toEdge(edges.next()));
                }
                return null;
            });
        }
    }

    @Override
    public synchronized void close() {
        if (ownsDriver) {
            driver.close();
        }
    }

    // ---- row mapping ----

    private static Map<NodeKind, List<Map<String, Object>>> nodeRowsByKind(List<Node> nodes) {
        Map<NodeKind, List<Map<String, Object>>> byKind = new EnumMap<>(NodeKind.class);
        for (Node n : nodes) {
            SourceSpan span = n.span();
            Map<String, Object> props = new HashMap<>();
            props.put("id", n.id().value());
            props.put("kind", n.kind().name());
            props.put("name", n.name());
            props.put("sig", n.displaySignature());
            props.put("relPath", n.relPath());
            props.put("spanPath", span == null ? null : span.relPath());
            props.put("startLine", span == null ? null : span.startLine());
            props.put("startCol", span == null ? null : span.startCol());
            props.put("endLine", span == null ? null : span.endLine());
            props.put("endCol", span == null ? null : span.endCol());
            props.put("metrics", Codecs.metrics(n.metrics()));
            props.put("attrs", Codecs.attrs(n.attrs()));
            byKind.computeIfAbsent(n.kind(), k -> new ArrayList<>())
                    .add(Map.of("id", n.id().value(), "props", props));
        }
        return byKind;
    }

    private static Map<EdgeKind, List<Map<String, Object>>> groupByKind(List<Edge> edges) {
        Map<EdgeKind, List<Map<String, Object>>> byKind = new EnumMap<>(EdgeKind.class);
        for (Edge e : edges) {
            byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>())
                    .add(Map.of("fromId", e.from().value(), "toId", e.to().value(),
                            "confidence", (double) e.confidence(), "attrs", Codecs.attrs(e.attrs())));
        }
        return byKind;
    }

    private static Node toNode(Map<String, Object> props) {
        Object spanPath = props.get("spanPath");
        SourceSpan span = spanPath == null ? null : new SourceSpan((String) spanPath,
                asInt(props.get("startLine")), asInt(props.get("startCol")),
                asInt(props.get("endLine")), asInt(props.get("endCol")));
        return new Node(NodeId.parse((String) props.get("id")),
                NodeKind.valueOf((String) props.get("kind")),
                (String) props.get("name"),
                (String) props.get("sig"),
                span,
                Codecs.parseMetrics((String) props.get("metrics")),
                Codecs.parseAttrs((String) props.get("attrs")));
    }

    private static Edge toEdge(Record record) {
        return new Edge(NodeId.parse(record.get("fromId").asString()),
                NodeId.parse(record.get("toId").asString()),
                EdgeKind.valueOf(record.get("kind").asString()),
                (float) record.get("confidence").asDouble(),
                Codecs.parseAttrs(record.get("attrs").isNull() ? "" : record.get("attrs").asString()));
    }

    private static int asInt(Object value) {
        return ((Number) value).intValue();
    }
}
