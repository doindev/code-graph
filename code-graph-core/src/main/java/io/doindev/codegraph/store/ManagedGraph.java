package io.doindev.codegraph.store;

import io.doindev.codegraph.query.GraphQuery;
import java.util.Map;

/** Graph lifecycle owned by a workspace, independent of its storage backend. */
public interface ManagedGraph extends GraphQuery, AutoCloseable {
    long generation();
    void dirtyPending(int count);
    void filesPerLang(Map<String, Integer> counts);
    void apply(GraphDelta delta);
    @Override default void close() { }
}
