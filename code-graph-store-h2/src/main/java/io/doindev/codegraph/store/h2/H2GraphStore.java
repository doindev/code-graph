package io.doindev.codegraph.store.h2;

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

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * H2 embedded-database write-through mirror for the code property graph.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@link #apply} is called in generation order by a single writer and runs as one
 *       transaction — file-scoped removals, exact edge removals, node/edge merges and the
 *       generation bump commit together or roll back together.</li>
 *   <li>Kind names are persisted as enum <em>name strings</em> (never ordinals) and node/edge
 *       endpoints as canonical {@link NodeId#value()} strings, so {@link #replay} reconstructs
 *       {@link Node}/{@link Edge} records with exact record equality.</li>
 *   <li>No Jackson: metrics/attrs are serialized with the tiny hand-rolled {@link Codecs}.</li>
 * </ul>
 *
 * <p>Schema: {@code nodes(id PK, kind, name, sig, rel_path, span_path, start_line, start_col,
 * end_line, end_col, metrics, attrs)}, {@code edges(from_id, to_id, kind, confidence, attrs,
 * PK(from_id, to_id, kind))} and {@code meta(k PK, v)} holding {@code applied_generation}.
 * {@code rel_path} is the node's <em>ownership</em> path ({@link Node#relPath()}, drives
 * file-scoped removal); the {@code span_*} columns carry the full {@link SourceSpan} so spans
 * round-trip exactly (including columns, which ownership alone cannot reconstruct).
 */
public final class H2GraphStore implements GraphStore {

    private static final String GENERATION_KEY = "applied_generation";

    private final Connection connection;
    private volatile long appliedGeneration;

    /** Opens (or creates) a file database at {@code databaseFile} (H2 appends {@code .mv.db}). */
    public H2GraphStore(Path databaseFile) {
        this("jdbc:h2:file:" + databaseFile.toAbsolutePath().toString().replace('\\', '/'));
    }

    /** Opens the store on any H2 JDBC url, e.g. {@code jdbc:h2:file:...} or {@code jdbc:h2:mem:...}. */
    public H2GraphStore(String jdbcUrl) {
        try {
            this.connection = DriverManager.getConnection(jdbcUrl);
            connection.setAutoCommit(false);
            createSchema();
            this.appliedGeneration = readGeneration();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open H2 graph store at " + jdbcUrl, e);
        }
    }

    @Override
    public synchronized void apply(GraphDelta delta) {
        try {
            removeFiles(delta.removedFiles());
            removeEdges(delta.removeEdges());
            mergeNodes(delta.addNodes());
            mergeEdges(delta.addEdges());
            try (PreparedStatement meta = connection.prepareStatement(
                    "MERGE INTO meta (k, v) KEY(k) VALUES (?, ?)")) {
                meta.setString(1, GENERATION_KEY);
                meta.setString(2, Long.toString(delta.generation()));
                meta.executeUpdate();
            }
            connection.commit();
            appliedGeneration = delta.generation();
        } catch (SQLException e) {
            rollbackQuietly();
            throw new IllegalStateException("failed to apply delta " + delta.generation(), e);
        }
    }

    @Override
    public long appliedGeneration() {
        return appliedGeneration;
    }

    @Override
    public synchronized void replay(GraphSink sink) {
        try (Statement stmt = connection.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT id, kind, name, sig, span_path, start_line, start_col, end_line, end_col,"
                            + " metrics, attrs FROM nodes")) {
                while (rs.next()) {
                    sink.node(readNode(rs));
                }
            }
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT from_id, to_id, kind, confidence, attrs FROM edges")) {
                while (rs.next()) {
                    sink.edge(new Edge(NodeId.parse(rs.getString("from_id")),
                            NodeId.parse(rs.getString("to_id")),
                            EdgeKind.valueOf(rs.getString("kind")),
                            rs.getFloat("confidence"),
                            Codecs.parseAttrs(rs.getString("attrs"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to replay H2 graph store", e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to close H2 graph store", e);
        }
    }

    // ---- write helpers ----

    private void removeFiles(List<FileId> removedFiles) throws SQLException {
        if (removedFiles.isEmpty()) {
            return;
        }
        // Edges touching owned nodes go first: the subquery still sees the doomed node rows.
        try (PreparedStatement edges = connection.prepareStatement(
                "DELETE FROM edges WHERE from_id IN (SELECT id FROM nodes WHERE rel_path = ?)"
                        + " OR to_id IN (SELECT id FROM nodes WHERE rel_path = ?)");
             PreparedStatement nodes = connection.prepareStatement(
                     "DELETE FROM nodes WHERE rel_path = ?")) {
            for (FileId file : removedFiles) {
                edges.setString(1, file.relPath());
                edges.setString(2, file.relPath());
                edges.executeUpdate();
                nodes.setString(1, file.relPath());
                nodes.executeUpdate();
            }
        }
    }

    private void removeEdges(List<Edge> removeEdges) throws SQLException {
        if (removeEdges.isEmpty()) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM edges WHERE from_id = ? AND to_id = ? AND kind = ?")) {
            for (Edge e : removeEdges) {
                stmt.setString(1, e.from().value());
                stmt.setString(2, e.to().value());
                stmt.setString(3, e.kind().name());
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void mergeNodes(List<Node> addNodes) throws SQLException {
        if (addNodes.isEmpty()) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "MERGE INTO nodes (id, kind, name, sig, rel_path, span_path, start_line, start_col,"
                        + " end_line, end_col, metrics, attrs) KEY(id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (Node n : addNodes) {
                SourceSpan span = n.span();
                stmt.setString(1, n.id().value());
                stmt.setString(2, n.kind().name());
                stmt.setString(3, n.name());
                stmt.setString(4, n.displaySignature());
                stmt.setString(5, n.relPath());
                stmt.setString(6, span == null ? null : span.relPath());
                stmt.setObject(7, span == null ? null : span.startLine());
                stmt.setObject(8, span == null ? null : span.startCol());
                stmt.setObject(9, span == null ? null : span.endLine());
                stmt.setObject(10, span == null ? null : span.endCol());
                stmt.setString(11, Codecs.metrics(n.metrics()));
                stmt.setString(12, Codecs.attrs(n.attrs()));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    private void mergeEdges(List<Edge> addEdges) throws SQLException {
        if (addEdges.isEmpty()) {
            return;
        }
        try (PreparedStatement stmt = connection.prepareStatement(
                "MERGE INTO edges (from_id, to_id, kind, confidence, attrs)"
                        + " KEY(from_id, to_id, kind) VALUES (?,?,?,?,?)")) {
            for (Edge e : addEdges) {
                stmt.setString(1, e.from().value());
                stmt.setString(2, e.to().value());
                stmt.setString(3, e.kind().name());
                stmt.setFloat(4, e.confidence());
                stmt.setString(5, Codecs.attrs(e.attrs()));
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    // ---- read helpers ----

    private static Node readNode(ResultSet rs) throws SQLException {
        String spanPath = rs.getString("span_path");
        SourceSpan span = spanPath == null ? null : new SourceSpan(spanPath,
                rs.getInt("start_line"), rs.getInt("start_col"),
                rs.getInt("end_line"), rs.getInt("end_col"));
        return new Node(NodeId.parse(rs.getString("id")),
                NodeKind.valueOf(rs.getString("kind")),
                rs.getString("name"),
                rs.getString("sig"),
                span,
                Codecs.parseMetrics(rs.getString("metrics")),
                Codecs.parseAttrs(rs.getString("attrs")));
    }

    // ---- lifecycle helpers ----

    private void createSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS nodes ("
                    + "id VARCHAR PRIMARY KEY, kind VARCHAR NOT NULL, name VARCHAR NOT NULL,"
                    + " sig VARCHAR NOT NULL, rel_path VARCHAR, span_path VARCHAR,"
                    + " start_line INT, start_col INT, end_line INT, end_col INT,"
                    + " metrics VARCHAR, attrs VARCHAR)");
            stmt.execute("CREATE TABLE IF NOT EXISTS edges ("
                    + "from_id VARCHAR NOT NULL, to_id VARCHAR NOT NULL, kind VARCHAR NOT NULL,"
                    + " confidence REAL NOT NULL, attrs VARCHAR,"
                    + " PRIMARY KEY (from_id, to_id, kind))");
            stmt.execute("CREATE TABLE IF NOT EXISTS meta (k VARCHAR PRIMARY KEY, v VARCHAR)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_edges_from ON edges(from_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_edges_to ON edges(to_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_nodes_rel_path ON nodes(rel_path)");
        }
        connection.commit();
    }

    private long readGeneration() throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT v FROM meta WHERE k = ?")) {
            stmt.setString(1, GENERATION_KEY);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Long.parseLong(rs.getString(1)) : 0L;
            }
        }
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // the caller's exception is the interesting one
        }
    }
}
