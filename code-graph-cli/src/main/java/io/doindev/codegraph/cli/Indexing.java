package io.doindev.codegraph.cli;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.FullIndexer;
import io.doindev.codegraph.index.IncrementalIndexer;

import java.nio.file.Path;

/** One-shot indexing of a repo root — the shared front half of every CLI command. */
final class Indexing {

    record Indexed(InMemoryCodeGraph graph, CodeGraphConfig config, FullIndexer.Result result) {
    }

    private Indexing() {
    }

    static Indexed index(Path root) {
        CodeGraphConfig config = ConfigLoader.load(root);
        Analyzers analyzers = Analyzers.discover();
        if (analyzers.isEmpty()) {
            throw new IllegalStateException("no language analyzers on classpath");
        }
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        FullIndexer.Result result = new IncrementalIndexer(root, analyzers, config, graph).fullIndex();
        return new Indexed(graph, config, result);
    }
}
