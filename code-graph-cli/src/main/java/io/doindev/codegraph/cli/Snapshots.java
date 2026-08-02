package io.doindev.codegraph.cli;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.snapshot.SnapshotCodec;
import io.doindev.codegraph.store.GraphDelta;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-file snapshot IO on top of {@link SnapshotCodec} — the CLI caches baseline graphs as
 * {@code .code-graph/snapshots/<merge-base-sha>.snap}, one self-contained file per commit
 * (unlike {@link io.doindev.codegraph.snapshot.FileSnapshotStore}, which owns a directory).
 */
final class Snapshots {

    private Snapshots() {
    }

    /** Write the full graph atomically (temp file + move) to {@code file}. */
    static void write(InMemoryCodeGraph graph, Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        SnapshotCodec.Writer writer = new SnapshotCodec.Writer();
        graph.export(writer);
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
            writer.writeTo(out, graph.generation(), InMemoryCodeGraph.ENGINE_VERSION);
        }
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Rehydrate a snapshot file into a fresh in-memory graph. */
    static InMemoryCodeGraph read(Path file) throws IOException {
        SnapshotCodec.Writer collector = new SnapshotCodec.Writer();
        long generation;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            generation = SnapshotCodec.read(in, collector);
        }
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        collector.drainTo(nodes::add, edges::add);
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        graph.apply(new GraphDelta(Math.max(1, generation), List.of(), nodes, edges, List.of()));
        return graph;
    }
}
