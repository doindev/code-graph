package io.doindev.codegraph.snapshot;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.store.GraphSink;
import io.doindev.codegraph.store.GraphStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Local-file persistence: a binary snapshot ({@code graph.snapshot}) plus an append-only delta
 * journal ({@code graph.journal}) between snapshots. Snapshots are written to a temp file and
 * published with {@code ATOMIC_MOVE}; a torn journal tail (crash mid-append) is tolerated and
 * ignored on replay. Compaction ({@link #compact}) folds the journal into a fresh snapshot once
 * it outgrows {@link #compactionThresholdBytes}.
 */
public final class FileSnapshotStore implements GraphStore {

    private final Path snapshotFile;
    private final Path journalFile;
    private final long compactionThresholdBytes;
    private DataOutputStream journal;
    private volatile long appliedGeneration;

    public FileSnapshotStore(Path directory) {
        this(directory, 16L * 1024 * 1024);
    }

    public FileSnapshotStore(Path directory, long compactionThresholdBytes) {
        try {
            Files.createDirectories(directory);
            this.snapshotFile = directory.resolve("graph.snapshot");
            this.journalFile = directory.resolve("graph.journal");
            this.compactionThresholdBytes = compactionThresholdBytes;
            this.appliedGeneration = scanLastGeneration();
            this.journal = openJournal(true);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open snapshot store in " + directory, e);
        }
    }

    @Override
    public synchronized void apply(GraphDelta delta) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DeltaCodec.write(new DataOutputStream(buffer), delta);
            byte[] payload = buffer.toByteArray();
            journal.writeInt(payload.length);
            journal.write(payload);
            journal.flush();
            appliedGeneration = delta.generation();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to append delta " + delta.generation(), e);
        }
    }

    @Override
    public long appliedGeneration() {
        return appliedGeneration;
    }

    @Override
    public synchronized void replay(GraphSink sink) {
        InMemoryCodeGraph reduced = loadReduced();
        reduced.export(sink);
    }

    /** Load snapshot + journal into a fresh in-memory graph — the cold-start rehydration path. */
    public synchronized InMemoryCodeGraph loadReduced() {
        InMemoryCodeGraph graph = new InMemoryCodeGraph();
        try {
            long snapshotGeneration = 0;
            if (Files.isRegularFile(snapshotFile)) {
                SnapshotCodec.Writer collector = new SnapshotCodec.Writer();
                try (InputStream in = new BufferedInputStream(Files.newInputStream(snapshotFile))) {
                    snapshotGeneration = SnapshotCodec.read(in, collector);
                }
                GraphDelta base = collectorToDelta(collector, snapshotGeneration);
                graph.apply(base);
            }
            if (Files.isRegularFile(journalFile)) {
                try (DataInputStream in = new DataInputStream(
                        new BufferedInputStream(Files.newInputStream(journalFile)))) {
                    while (true) {
                        int length;
                        try {
                            length = in.readInt();
                        } catch (EOFException end) {
                            break;
                        }
                        byte[] payload = new byte[length];
                        try {
                            in.readFully(payload);
                        } catch (EOFException torn) {
                            break; // crash mid-append: ignore the torn tail
                        }
                        GraphDelta delta = DeltaCodec.read(new DataInputStream(
                                new ByteArrayInputStream(payload)));
                        if (delta.generation() > graph.generation()) {
                            graph.apply(delta);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to replay snapshot store", e);
        }
        return graph;
    }

    /**
     * Write a full snapshot of {@code graph} atomically and truncate the journal. Call after
     * bulk indexing, on clean shutdown, or when {@link #shouldCompact()} turns true.
     */
    public synchronized void compact(InMemoryCodeGraph graph) {
        try {
            SnapshotCodec.Writer writer = new SnapshotCodec.Writer();
            graph.export(writer);
            Path temp = snapshotFile.resolveSibling(snapshotFile.getFileName() + ".tmp");
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                writer.writeTo(out, graph.generation(), InMemoryCodeGraph.ENGINE_VERSION);
            }
            Files.move(temp, snapshotFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            journal.close();
            journal = openJournal(false);
            appliedGeneration = graph.generation();
        } catch (IOException e) {
            throw new UncheckedIOException("snapshot compaction failed", e);
        }
    }

    /** Whether the journal has outgrown the compaction threshold. */
    public boolean shouldCompact() {
        try {
            return Files.isRegularFile(journalFile) && Files.size(journalFile) >= compactionThresholdBytes;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        try {
            journal.close();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to close journal", e);
        }
    }

    // ---- helpers ----

    private DataOutputStream openJournal(boolean append) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(journalFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)));
    }

    private static GraphDelta collectorToDelta(SnapshotCodec.Writer collector, long generation) {
        // Writer doubles as a GraphSink collector; expose its content as one bulk delta.
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        collector.drainTo(nodes::add, edges::add);
        return new GraphDelta(generation, List.of(), nodes, edges, List.of());
    }

    /** Best-effort read of the last generation across snapshot header + journal records. */
    private long scanLastGeneration() throws IOException {
        long generation = 0;
        if (Files.isRegularFile(snapshotFile)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(snapshotFile)))) {
                if (in.readInt() == SnapshotCodec.MAGIC && Varint.read(in) == SnapshotCodec.FORMAT_VERSION) {
                    in.readUTF();
                    generation = Varint.readLong(in);
                }
            } catch (IOException corrupt) {
                generation = 0;
            }
        }
        if (Files.isRegularFile(journalFile)) {
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(journalFile)))) {
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException end) {
                        break;
                    }
                    byte[] payload = new byte[length];
                    try {
                        in.readFully(payload);
                    } catch (EOFException torn) {
                        break;
                    }
                    GraphDelta delta = DeltaCodec.read(new DataInputStream(new ByteArrayInputStream(payload)));
                    generation = Math.max(generation, delta.generation());
                }
            }
        }
        return generation;
    }
}
