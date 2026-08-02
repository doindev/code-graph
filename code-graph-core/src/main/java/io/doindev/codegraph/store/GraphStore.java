package io.doindev.codegraph.store;

/**
 * Durable graph persistence SPI. The in-memory graph is the sole query engine; store
 * implementations (snapshot file, H2, Neo4j, ArangoDB) are write-through mirrors that consume
 * the same ordered {@link GraphDelta} stream — typically drained from a bounded queue on one
 * virtual thread per store so a slow mirror never stalls indexing.
 */
public interface GraphStore extends AutoCloseable {

    /** Apply one delta. Called in generation order by a single writer; must be atomic per delta. */
    void apply(GraphDelta delta);

    /** Generation of the last durably applied delta (mirror lag = indexer generation − this). */
    long appliedGeneration();

    /** Stream the full persisted graph into {@code sink} — the cold-start rehydration path. */
    void replay(GraphSink sink);

    @Override
    void close();
}
