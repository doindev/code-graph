package io.doindev.codegraph.snapshot;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.store.GraphSink;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact binary snapshot of a full graph. Layout: magic {@code CGPH}, format version,
 * generation, string-interning table, varint-encoded nodes then edges. Kind names are stored
 * as strings (not ordinals) so adding enum constants stays format-compatible. Zero
 * dependencies — plain {@link DataOutputStream} primitives.
 */
public final class SnapshotCodec {

    static final int MAGIC = 0x43475048; // "CGPH"
    static final int FORMAT_VERSION = 1;

    private SnapshotCodec() {
    }

    // ---- write ----

    /** Collects a graph via {@link GraphSink}, then writes it with an interned string table. */
    public static final class Writer implements GraphSink {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();

        @Override
        public void node(Node node) {
            nodes.add(node);
        }

        @Override
        public void edge(Edge edge) {
            edges.add(edge);
        }

        /** Hand the collected content to consumers — used when a snapshot is read back as one bulk delta. */
        public void drainTo(java.util.function.Consumer<Node> nodeConsumer,
                            java.util.function.Consumer<Edge> edgeConsumer) {
            nodes.forEach(nodeConsumer);
            edges.forEach(edgeConsumer);
        }

        public void writeTo(OutputStream stream, long generation, String engineVersion) throws IOException {
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(MAGIC);
            Varint.write(out, FORMAT_VERSION);
            out.writeUTF(engineVersion);
            Varint.writeLong(out, generation);

            StringTable strings = new StringTable();
            for (Node n : nodes) {
                strings.intern(n.id().value());
                strings.intern(n.kind().name());
                strings.intern(n.name());
                strings.intern(n.displaySignature());
                if (n.span() != null) {
                    strings.intern(n.span().relPath());
                }
                n.attrs().forEach((k, v) -> {
                    strings.intern(k);
                    strings.intern(v);
                });
            }
            for (Edge e : edges) {
                strings.intern(e.from().value());
                strings.intern(e.to().value());
                strings.intern(e.kind().name());
                e.attrs().forEach((k, v) -> {
                    strings.intern(k);
                    strings.intern(v);
                });
            }
            strings.writeTo(out);

            Varint.write(out, nodes.size());
            for (Node n : nodes) {
                Varint.write(out, strings.index(n.id().value()));
                Varint.write(out, strings.index(n.kind().name()));
                Varint.write(out, strings.index(n.name()));
                Varint.write(out, strings.index(n.displaySignature()));
                SourceSpan span = n.span();
                out.writeBoolean(span != null);
                if (span != null) {
                    Varint.write(out, strings.index(span.relPath()));
                    Varint.write(out, span.startLine());
                    Varint.write(out, span.startCol());
                    Varint.write(out, span.endLine());
                    Varint.write(out, span.endCol());
                }
                Metrics m = n.metrics();
                Varint.write(out, m.loc());
                Varint.write(out, m.methodCount());
                Varint.write(out, m.fieldCount());
                Varint.write(out, m.paramCount());
                Varint.write(out, m.maxNestingDepth());
                Varint.write(out, m.cyclomaticApprox());
                out.writeFloat(m.internalCallDensity());
                writeAttrs(out, strings, n.attrs());
            }

            Varint.write(out, edges.size());
            for (Edge e : edges) {
                Varint.write(out, strings.index(e.from().value()));
                Varint.write(out, strings.index(e.to().value()));
                Varint.write(out, strings.index(e.kind().name()));
                out.writeFloat(e.confidence());
                writeAttrs(out, strings, e.attrs());
            }
            out.flush();
        }

        private static void writeAttrs(DataOutputStream out, StringTable strings,
                                       Map<String, String> attrs) throws IOException {
            Varint.write(out, attrs.size());
            for (Map.Entry<String, String> entry : attrs.entrySet()) {
                Varint.write(out, strings.index(entry.getKey()));
                Varint.write(out, strings.index(entry.getValue()));
            }
        }
    }

    // ---- read ----

    /** Streams a snapshot into {@code sink}; returns the generation recorded in the header. */
    public static long read(InputStream stream, GraphSink sink) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC) {
            throw new IOException("not a code-graph snapshot (bad magic)");
        }
        int version = Varint.read(in);
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported snapshot format version " + version);
        }
        in.readUTF(); // engineVersion — informational
        long generation = Varint.readLong(in);
        String[] strings = StringTable.readFrom(in);

        int nodeCount = Varint.read(in);
        for (int i = 0; i < nodeCount; i++) {
            NodeId id = NodeId.parse(strings[Varint.read(in)]);
            NodeKind kind = NodeKind.valueOf(strings[Varint.read(in)]);
            String name = strings[Varint.read(in)];
            String sig = strings[Varint.read(in)];
            SourceSpan span = null;
            if (in.readBoolean()) {
                span = new SourceSpan(strings[Varint.read(in)], Varint.read(in), Varint.read(in),
                        Varint.read(in), Varint.read(in));
            }
            Metrics metrics = new Metrics(Varint.read(in), Varint.read(in), Varint.read(in),
                    Varint.read(in), Varint.read(in), Varint.read(in), in.readFloat());
            sink.node(new Node(id, kind, name, sig, span, metrics, readAttrs(in, strings)));
        }

        int edgeCount = Varint.read(in);
        for (int i = 0; i < edgeCount; i++) {
            NodeId from = NodeId.parse(strings[Varint.read(in)]);
            NodeId to = NodeId.parse(strings[Varint.read(in)]);
            EdgeKind kind = EdgeKind.valueOf(strings[Varint.read(in)]);
            float confidence = in.readFloat();
            sink.edge(new Edge(from, to, kind, confidence, readAttrs(in, strings)));
        }
        return generation;
    }

    private static Map<String, String> readAttrs(DataInputStream in, String[] strings) throws IOException {
        int count = Varint.read(in);
        if (count == 0) {
            return Map.of();
        }
        Map<String, String> attrs = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            attrs.put(strings[Varint.read(in)], strings[Varint.read(in)]);
        }
        return attrs;
    }

    /** Insertion-ordered intern table; index = insertion order. */
    private static final class StringTable {
        private final Map<String, Integer> indexByValue = new LinkedHashMap<>();

        void intern(String value) {
            indexByValue.putIfAbsent(value, indexByValue.size());
        }

        int index(String value) {
            return indexByValue.get(value);
        }

        void writeTo(DataOutputStream out) throws IOException {
            Varint.write(out, indexByValue.size());
            for (String value : indexByValue.keySet()) {
                out.writeUTF(value);
            }
        }

        static String[] readFrom(DataInputStream in) throws IOException {
            int count = Varint.read(in);
            String[] strings = new String[count];
            for (int i = 0; i < count; i++) {
                strings[i] = in.readUTF();
            }
            return strings;
        }
    }
}
