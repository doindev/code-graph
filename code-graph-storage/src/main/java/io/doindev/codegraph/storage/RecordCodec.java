package io.doindev.codegraph.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/** Explicit portable DTOs: no Java serialization or polymorphic class loading. */
public final class RecordCodec {
    public static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public record N(String id, NodeKind kind, String name, String signature, SourceSpan span, Metrics metrics, Map<String,String> attrs) {
        N(Node n) { this(n.id().value(), n.kind(), n.name(), n.displaySignature(), n.span(), n.metrics(), n.attrs()); }
        Node node() { return new Node(NodeId.parse(id), kind, name, signature, span, metrics, attrs); }
    }
    public record E(String from, String to, EdgeKind kind, float confidence, Map<String,String> attrs) {
        E(Edge e) { this(e.from().value(), e.to().value(), e.kind(), e.confidence(), e.attrs()); }
        Edge edge() { return new Edge(NodeId.parse(from), NodeId.parse(to), kind, confidence, attrs); }
    }
    public record R(String from, RefKind kind, String name, String receiverHint, int arity, SourceSpan site, String locationPrecision, CallContext context, String resolutionStatus) {
        R(RawRef r) { this(r.from().value(), r.kind(), r.name(), r.receiverHint(), r.arity(), r.site(),r.locationPrecision(),r.context(),r.resolutionStatus()); }
        RawRef ref() { return new RawRef(NodeId.parse(from), kind, name, receiverHint, arity, site,locationPrecision,context,resolutionStatus); }
    }
    public record F(String path, String lang, String hash, List<N> nodes, List<E> edges, List<R> refs, List<String> imports, ModuleEvidence modules) {
        public F(FileFragment f) { this(f.file().relPath(), f.lang(), f.contentHash(), f.declarations().stream().map(N::new).toList(),
                f.localEdges().stream().map(E::new).toList(), f.rawRefs().stream().map(R::new).toList(), f.imports(), f.modules()); }
        public FileFragment fragment() { return new FileFragment(new FileId(path), lang, hash, nodes.stream().map(N::node).toList(),
                edges.stream().map(E::edge).toList(), refs.stream().map(R::ref).toList(), imports, modules); }
    }
    public record M(String path, ModuleEvidence evidence, List<N> declarations) {
        public M(FileFragment fragment) { this(fragment.file().relPath(),fragment.modules(),
                fragment.declarations().stream().filter(n->n.id() instanceof SymbolId && n.kind()!=NodeKind.DATABASE_MAPPING).map(N::new).toList()); }
        public ModuleFile module() { return new ModuleFile(path,evidence,declarations.stream().map(N::node).map(SymbolTable.Entry::of).toList()); }
    }
    public static byte[] encode(Object value) {
        try { return JSON.writeValueAsBytes(value); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static <T> T decode(byte[] bytes, Class<T> type) {
        try { return JSON.readValue(bytes, type); } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    public static byte[] node(Node n) { return encode(new N(n)); }
    public static Node node(byte[] b) { return decode(b, N.class).node(); }
    public static byte[] edge(Edge e) { return encode(new E(e)); }
    public static Edge edge(byte[] b) { return decode(b, E.class).edge(); }
}
