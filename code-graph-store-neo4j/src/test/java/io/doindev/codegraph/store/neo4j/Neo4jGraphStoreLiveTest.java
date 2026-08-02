package io.doindev.codegraph.store.neo4j;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.store.GraphSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Neo4jContainer;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Live test against a real Neo4j 5 community container. Self-skips (JUnit assumption, not
 * failure) when Docker is unavailable or the container cannot start after two attempts.
 */
class Neo4jGraphStoreLiveTest {

    private static Neo4jContainer<?> container;
    private static Driver driver;

    @BeforeAll
    static void startContainer() {
        boolean dockerAvailable;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker unavailable - skipping Neo4j live test");

        Throwable failure = null;
        for (int attempt = 1; attempt <= 2 && container == null; attempt++) {
            Neo4jContainer<?> candidate = new Neo4jContainer<>("neo4j:5-community")
                    .withoutAuthentication();
            try {
                candidate.start();
                container = candidate;
            } catch (Throwable t) {
                failure = t;
                try {
                    candidate.stop();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
        Assumptions.assumeTrue(container != null,
                "Neo4j container failed to start after 2 attempts - skipping: " + failure);
        driver = GraphDatabase.driver(container.getBoltUrl());
    }

    @AfterAll
    static void stopContainer() {
        if (driver != null) {
            driver.close();
        }
        if (container != null) {
            container.stop();
        }
    }

    @BeforeEach
    void wipeDatabase() {
        try (Session session = driver.session()) {
            session.run("MATCH (n) DETACH DELETE n").consume();
        }
    }

    // ---- fixture (mirrors FileSnapshotStoreTest shapes) ----

    private static SymbolId sym(String path, String qname) {
        return new SymbolId("java", path, qname, 1);
    }

    private static Node fn(SymbolId id) {
        return new Node(id, NodeKind.FUNCTION, "m", "void m(int)",
                new SourceSpan(id.relPath(), 3, 1, 9, 2),
                new Metrics(7, 0, 0, 1, 2, 3, Float.NaN),
                Map.of("visibility", "public", "doc", "a;b=c\\d"));
    }

    private static Node fileNode(String path) {
        return new Node(new FileId(path), NodeKind.FILE,
                path.substring(path.lastIndexOf('/') + 1), null, null, Metrics.NONE, Map.of());
    }

    private static final Node FILE_A = fileNode("src/A.java");
    private static final Node FILE_B = fileNode("src/B.java");
    private static final Node SYM_A = fn(sym("src/A.java", "A.m"));
    private static final Node SYM_B = fn(sym("src/B.java", "B.m"));
    private static final Edge CONTAINS_A = new Edge(FILE_A.id(), SYM_A.id(), EdgeKind.CONTAINS);
    private static final Edge CONTAINS_B = new Edge(FILE_B.id(), SYM_B.id(), EdgeKind.CONTAINS);
    private static final Edge CALLS_B_TO_A = new Edge(SYM_B.id(), SYM_A.id(), EdgeKind.CALLS,
            0.8f, Map.of("resolution", "heuristic"));

    private static GraphDelta gen1() {
        return new GraphDelta(1, List.of(), List.of(FILE_A, SYM_A), List.of(CONTAINS_A), List.of());
    }

    private static GraphDelta gen2() {
        return new GraphDelta(2, List.of(), List.of(FILE_B, SYM_B),
                List.of(CONTAINS_B, CALLS_B_TO_A), List.of());
    }

    private static final class Collector implements GraphSink {
        final Set<Node> nodes = new HashSet<>();
        final Set<Edge> edges = new HashSet<>();

        @Override
        public void node(Node node) {
            nodes.add(node);
        }

        @Override
        public void edge(Edge edge) {
            edges.add(edge);
        }
    }

    @Test
    void replayRoundTripsRecordsExactly() {
        try (Neo4jGraphStore store = new Neo4jGraphStore(driver)) {
            store.apply(gen1());
            store.apply(gen2());
            assertEquals(2, store.appliedGeneration());

            Collector collector = new Collector();
            store.replay(collector);
            assertEquals(Set.of(FILE_A, FILE_B, SYM_A, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_A, CONTAINS_B, CALLS_B_TO_A), collector.edges);
        }
    }

    @Test
    void removedFileDropsItsNodesAndEveryTouchingEdge() {
        try (Neo4jGraphStore store = new Neo4jGraphStore(driver)) {
            store.apply(gen1());
            store.apply(gen2());
            store.apply(new GraphDelta(3, List.of(new FileId("src/A.java")),
                    List.of(), List.of(), List.of()));
            assertEquals(3, store.appliedGeneration());

            Collector collector = new Collector();
            store.replay(collector);
            // survivor keeps its nodes; the CALLS edge from surviving B into removed A is gone too
            assertEquals(Set.of(FILE_B, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_B), collector.edges);
        }
    }

    @Test
    void removeEdgesDeletesExactFromToKindMatchesAndGenerationIsReadBack() {
        try (Neo4jGraphStore store = new Neo4jGraphStore(driver)) {
            store.apply(gen1());
            store.apply(gen2());
            store.apply(new GraphDelta(3, List.of(), List.of(), List.of(),
                    List.of(new Edge(SYM_B.id(), SYM_A.id(), EdgeKind.CALLS))));

            Collector collector = new Collector();
            store.replay(collector);
            assertEquals(Set.of(FILE_A, FILE_B, SYM_A, SYM_B), collector.nodes);
            assertEquals(Set.of(CONTAINS_A, CONTAINS_B), collector.edges);
        }
        // a fresh store over the same database rehydrates the generation from the :Meta node
        try (Neo4jGraphStore reopened = new Neo4jGraphStore(driver)) {
            assertEquals(3, reopened.appliedGeneration());
        }
    }
}
