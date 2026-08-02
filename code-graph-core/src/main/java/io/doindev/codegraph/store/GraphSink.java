package io.doindev.codegraph.store;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.Node;

/** Receiver for a full graph replay, used to rehydrate the in-memory engine from a durable store on cold start. */
public interface GraphSink {

    void node(Node node);

    void edge(Edge edge);
}
