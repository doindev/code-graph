package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.SymbolId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live filesystem round-trip: save a file, watcher debounces and patches the graph. */
class WatcherTest {

    @TempDir
    Path repo;

    @Test
    void savedFileAppearsInGraphWithinSeconds() throws IOException, InterruptedException {
        Files.createDirectories(repo.resolve("src"));
        Files.writeString(repo.resolve("src/A.java"), "public class A { public void a() { } }");
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        IncrementalIndexer indexer = new IncrementalIndexer(repo, Analyzers.discover(),
                CodeGraphConfig.defaults(), graph);
        indexer.fullIndex();

        try (Watcher watcher = new Watcher(repo, indexer)) {
            watcher.awaitStarted();
            Thread.sleep(300); // let the OS watch registration settle
            Files.writeString(repo.resolve("src/New.java"),
                    "public class New { public void fresh() { } }");

            SymbolId fresh = new SymbolId("java", "src/New.java", "New.fresh", 0);
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (graph.node(fresh).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(graph.node(fresh).isPresent(),
                    "watcher should have indexed src/New.java within 15s");
        }
    }
}
