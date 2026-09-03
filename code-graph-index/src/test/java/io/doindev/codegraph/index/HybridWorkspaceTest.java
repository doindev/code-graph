package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.storage.PagedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridWorkspaceTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "hybrid.repository", matches = ".+")
    void realRepositoryCanBeIndexedWithoutRetainingASecondGraph() {
        Path source = Path.of(System.getProperty("hybrid.repository"));
        long start = System.nanoTime();
        try (var workspace = Workspace.open(List.of(source), Analyzers.discover(), p -> CodeGraphConfig.defaults(), true, 32L << 20)) {
            workspace.fullIndexAll();
            var graph = workspace.defaultProject().graph();
            assertTrue(graph.status().symbolCount() > 0);
            assertFalse(graph.findSymbols("Workspace", null, "java", 10).isEmpty());
            System.out.println("HYBRID_REPOSITORY indexingMs=" + (System.nanoTime() - start) / 1_000_000
                    + " index=" + graph.status() + " storage=" + workspace.storageStatus());
        }
    }
    @TempDir Path root;
    @Test void onboardingQueriesRebuildDeletionAndRemoval() throws Exception {
        var analyzers = Analyzers.discover();
        Files.writeString(root.resolve("A.java"), "class A { void one() { two(); } void two() {} }");
        try (var hybrid = Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults(), true, 32L << 20);
             var memory = Workspace.open(List.of(root), analyzers, p -> CodeGraphConfig.defaults())) {
            memory.fullIndexAll();
            var project = hybrid.add("test", root, analyzers, p -> CodeGraphConfig.defaults());
            assertInstanceOf(PagedGraph.class, project.graph());
            var expected = memory.defaultProject().graph(); var actual = project.graph();
            assertEquals(expected.findSymbols("one", null, null, 10), actual.findSymbols("one", null, null, 10));
            for (var node : expected.allNodes(null)) {
                assertEquals(expected.node(node.id()), actual.node(node.id()));
                assertEquals(new HashSet<>(expected.edges(node.id(), Direction.OUT, null)), new HashSet<>(actual.edges(node.id(), Direction.OUT, null)));
            }
            Files.writeString(root.resolve("A.java"), "class A { void three() {} }");
            project.indexer().applyChanges(List.of("A.java"));
            assertTrue(actual.findSymbols("one", null, null, 10).isEmpty());
            assertEquals(1, actual.findSymbols("three", null, null, 10).size()); assertEquals(2, actual.generation());
            Files.delete(root.resolve("A.java")); project.indexer().applyChanges(List.of("A.java"));
            assertEquals(0, actual.status().symbolCount()); assertEquals(3, actual.generation());
            hybrid.remove("test"); assertEquals(0, hybrid.storageStatus().get("activeAndStagedStores"));
            assertTrue(Files.isDirectory(root));
        }
    }
}
