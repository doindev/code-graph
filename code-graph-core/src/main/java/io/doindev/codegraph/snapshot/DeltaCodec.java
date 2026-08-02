package io.doindev.codegraph.snapshot;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.EdgeKind;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeId;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.store.GraphDelta;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal encoding of a single {@link GraphDelta}. Deltas are small (one file's worth of
 * changes), so strings are written inline without an intern table.
 */
final class DeltaCodec {

    private DeltaCodec() {
    }

    static void write(DataOutputStream out, GraphDelta delta) throws IOException {
        Varint.writeLong(out, delta.generation());
        Varint.write(out, delta.removedFiles().size());
        for (FileId file : delta.removedFiles()) {
            out.writeUTF(file.relPath());
        }
        Varint.write(out, delta.addNodes().size());
        for (Node n : delta.addNodes()) {
            writeNode(out, n);
        }
        Varint.write(out, delta.addEdges().size());
        for (Edge e : delta.addEdges()) {
            writeEdge(out, e);
        }
        Varint.write(out, delta.removeEdges().size());
        for (Edge e : delta.removeEdges()) {
            writeEdge(out, e);
        }
    }

    static GraphDelta read(DataInputStream in) throws IOException {
        long generation = Varint.readLong(in);
        int removedCount = Varint.read(in);
        List<FileId> removed = new ArrayList<>(removedCount);
        for (int i = 0; i < removedCount; i++) {
            removed.add(new FileId(in.readUTF()));
        }
        int nodeCount = Varint.read(in);
        List<Node> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(readNode(in));
        }
        int addEdgeCount = Varint.read(in);
        List<Edge> addEdges = new ArrayList<>(addEdgeCount);
        for (int i = 0; i < addEdgeCount; i++) {
            addEdges.add(readEdge(in));
        }
        int removeEdgeCount = Varint.read(in);
        List<Edge> removeEdges = new ArrayList<>(removeEdgeCount);
        for (int i = 0; i < removeEdgeCount; i++) {
            removeEdges.add(readEdge(in));
        }
        return new GraphDelta(generation, removed, nodes, addEdges, removeEdges);
    }

    private static void writeNode(DataOutputStream out, Node n) throws IOException {
        out.writeUTF(n.id().value());
        out.writeUTF(n.kind().name());
        out.writeUTF(n.name());
        out.writeUTF(n.displaySignature());
        SourceSpan span = n.span();
        out.writeBoolean(span != null);
        if (span != null) {
            out.writeUTF(span.relPath());
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
        writeAttrs(out, n.attrs());
    }

    private static Node readNode(DataInputStream in) throws IOException {
        NodeId id = NodeId.parse(in.readUTF());
        NodeKind kind = NodeKind.valueOf(in.readUTF());
        String name = in.readUTF();
        String sig = in.readUTF();
        SourceSpan span = null;
        if (in.readBoolean()) {
            span = new SourceSpan(in.readUTF(), Varint.read(in), Varint.read(in),
                    Varint.read(in), Varint.read(in));
        }
        Metrics metrics = new Metrics(Varint.read(in), Varint.read(in), Varint.read(in),
                Varint.read(in), Varint.read(in), Varint.read(in), in.readFloat());
        return new Node(id, kind, name, sig, span, metrics, readAttrs(in));
    }

    private static void writeEdge(DataOutputStream out, Edge e) throws IOException {
        out.writeUTF(e.from().value());
        out.writeUTF(e.to().value());
        out.writeUTF(e.kind().name());
        out.writeFloat(e.confidence());
        writeAttrs(out, e.attrs());
    }

    private static Edge readEdge(DataInputStream in) throws IOException {
        return new Edge(NodeId.parse(in.readUTF()), NodeId.parse(in.readUTF()),
                EdgeKind.valueOf(in.readUTF()), in.readFloat(), readAttrs(in));
    }

    private static void writeAttrs(DataOutputStream out, Map<String, String> attrs) throws IOException {
        Varint.write(out, attrs.size());
        for (Map.Entry<String, String> entry : attrs.entrySet()) {
            out.writeUTF(entry.getKey());
            out.writeUTF(entry.getValue());
        }
    }

    private static Map<String, String> readAttrs(DataInputStream in) throws IOException {
        int count = Varint.read(in);
        if (count == 0) {
            return Map.of();
        }
        Map<String, String> attrs = new HashMap<>(count * 2);
        for (int i = 0; i < count; i++) {
            attrs.put(in.readUTF(), in.readUTF());
        }
        return attrs;
    }
}
