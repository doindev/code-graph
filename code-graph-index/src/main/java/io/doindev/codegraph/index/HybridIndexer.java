package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.storage.PagedGraph;
import io.doindev.codegraph.storage.RecordCodec;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded two-pass indexing. No project-wide fragments, symbol table or reverse maps in heap. */
final class HybridIndexer {
    private final Path root;
    private final Analyzers analyzers;
    private final FullIndexer scanner;
    private final PagedGraph graph;

    HybridIndexer(Path root, Analyzers analyzers, CodeGraphConfig config, PagedGraph graph) {
        this.root = root; this.analyzers = analyzers; this.scanner = new FullIndexer(analyzers, config); this.graph = graph;
    }
    FullIndexer.Result index() {
        // GraphStorage serializes builds across projects: at most one parsed fragment/resolver set at a time.
        return graph.rebuild(builder -> {
            int[] files = {0}, nodes = {0}, edges = {0};
            scanner.scan(root, file -> {
                FileFragment fragment;
                try {
                    byte[] bytes;
                    try (var input = Files.newInputStream(file)) { bytes = input.readNBytes(2 * 1024 * 1024 + 1); }
                    if (bytes.length > 2 * 1024 * 1024) throw new IOException("file grew beyond 2 MiB during scan");
                    String relative = FullIndexer.relativize(root, file);
                    var analyzer = analyzers.forPath(relative);
                    fragment = analyzer == null ? null : analyzer.extract(new SourceFile(relative, analyzer.languageId(),
                            new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException e) {
                    // An incomplete rebuild must not silently replace a valid generation.
                    throw new IllegalStateException("hybrid extraction failed for " + file, e);
                }
                if (fragment == null) return;
                builder.auxiliary("fragment/" + PagedGraph.sequence(files[0]), RecordCodec.encode(new RecordCodec.F(fragment)));
                builder.file(fragment.lang()); files[0]++;
                for (Node node : fragment.declarations()) {
                    builder.node(node);
                    if (node.id() instanceof SymbolId id && node.kind()!=NodeKind.DATABASE_MAPPING) {
                        byte[] encoded = RecordCodec.node(node);
                        String ordinal = PagedGraph.sequence(nodes[0]);
                        builder.auxiliary("simple/" + PagedGraph.encodeKey(node.name()) + "/" + ordinal, encoded);
                        builder.auxiliary("qualified/" + PagedGraph.encodeKey(id.qualifiedName()) + "/" + ordinal, encoded);
                    }
                    nodes[0]++;
                }
                fragment.localEdges().forEach(builder::edge); edges[0] += fragment.localEdges().size();
            });
            for (int i = 0; i < files[0]; i++) {
                FileFragment fragment = RecordCodec.decode(builder.auxiliary("fragment/" + PagedGraph.sequence(i)), RecordCodec.F.class).fragment();
                Set<String> names = new HashSet<>();
                fragment.rawRefs().forEach(ref -> names.add("simple/" + PagedGraph.encodeKey(ref.name()) + "/"));
                fragment.imports().forEach(name -> names.add("qualified/" + PagedGraph.encodeKey(name.replace("::", ".").replace('/', '.')) + "/"));
                TreeMap<String, Node> candidates = new TreeMap<>();
                long[] candidateBytes = {0};
                for (String name : names) builder.scanAuxiliary(name, (key, value) -> {
                    String ordinal = key.substring(key.lastIndexOf('/') + 1);
                    if (!candidates.containsKey(ordinal)) {
                        candidateBytes[0] += value.length;
                        if (candidates.size() >= 10_000 || candidateBytes[0] > PagedGraph.MAX_RECORD_BYTES)
                            throw new IllegalArgumentException("resolver candidate set exceeds hybrid bound for " + fragment.file().relPath());
                        candidates.put(ordinal, RecordCodec.node(value));
                    }
                });
                var lookup = new FileFragment(fragment.file(), fragment.lang(), "", List.copyOf(candidates.values()), List.of(), List.of(), List.of());
                List<Edge> resolved = NameResolver.resolve(fragment, SymbolTable.of(List.of(lookup)), new ArrayList<>());
                resolved.forEach(builder::edge); edges[0] += resolved.size();
            }
            return new FullIndexer.Result(files[0], nodes[0], edges[0], List.of(), List.of());
        });
    }
}
