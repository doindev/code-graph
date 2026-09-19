package io.doindev.codegraph.index;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.storage.PagedGraph;
import io.doindev.codegraph.storage.RecordCodec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Disk-owned fragments and copy-on-write updates; no project-wide resolver state in heap. */
final class HybridIndexer {
    static final int MAX_BATCH_PATHS = 1024;
    static final int MAX_BATCH_PATH_BYTES = 1 << 20;
    private static final String FORMAT = "incremental-format";
    private static final byte[] FORMAT_VALUE = {1};
    private final Path root;
    private final Analyzers analyzers;
    private final FullIndexer scanner;
    private final PagedGraph graph;
    private volatile IncrementalIndexer.HybridUpdate lastUpdate;

    HybridIndexer(Path root, Analyzers analyzers, CodeGraphConfig config, PagedGraph graph) {
        this.root = root; this.analyzers = analyzers; this.scanner = new FullIndexer(analyzers, config); this.graph = graph;
    }

    IncrementalIndexer.HybridUpdate lastUpdate() { return lastUpdate; }

    FullIndexer.Result index() { return index("full"); }

    private FullIndexer.Result index(String mode) {
        long start = System.nanoTime();
        int[] files = {0}, nodes = {0}, edges = {0};
        var result = graph.rebuild(builder -> {
            builder.auxiliary(FORMAT, FORMAT_VALUE);
            scanner.scanConfigurations(root, path -> replaceConfiguration(builder,
                    FullIndexer.relativize(root,path), ModuleConfigurations.read(path)));
            scanner.scan(root, path -> {
                FileFragment fragment = extract(path, null);
                if (fragment == null) return;
                addFragment(builder, fragment);
                files[0]++; nodes[0] += fragment.declarations().size();
            });
            builder.scanAuxiliary("fragment/", (key, value) ->
                    edges[0] += resolve(builder, decode(value)));
            return new FullIndexer.Result(files[0], nodes[0], edges[0], List.of(), List.of());
        });
        record(mode, files[0], files[0], files[0], start);
        return result;
    }

    void applyChanges(Collection<String> paths) {
        if (paths.isEmpty()) return;
        long start = System.nanoTime();
        var bounded = new TreeSet<String>();
        long bytes = 0;
        for (String path : paths) {
            if ("*".equals(path)) { index("full-fallback"); return; }
            String normalized = normalize(path);
            if (bounded.add(normalized)) bytes += normalized.getBytes(StandardCharsets.UTF_8).length;
            if (bounded.size() > MAX_BATCH_PATHS || bytes > MAX_BATCH_PATH_BYTES) {
                index("full-fallback"); return;
            }
        }
        if (!Arrays.equals(graph.document(FORMAT), FORMAT_VALUE)) { index("full-fallback"); return; }
        // Directory events, ignore changes and watcher overflow require discovering the affected
        // inventory. Ordinary file updates use direct paths and never scan the source tree.
        for (String path : bounded) {
            if (path.equals(".gitignore") || path.endsWith("/.gitignore")
                    || Files.isDirectory(root.resolve(path), LinkOption.NOFOLLOW_LINKS)
                    || hasDescendants(path)) {
                index("full-fallback"); return;
            }
        }
        int[] parsed = {0}, changed = {0}, resolved = {0};
        boolean[] all = {false};
        graph.update(builder -> {
            var affected = new ArrayList<String>();
            for (String path : bounded) {
                if (ModuleConfigurations.candidate(path)) {
                    JsonNode configuration = eligible(path,true) ? ModuleConfigurations.read(root.resolve(path)) : null;
                    byte[] next = configuration == null ? null : RecordCodec.encode(configuration);
                    if (!Arrays.equals(builder.auxiliary("config/"+path),next)) {
                        replaceConfiguration(builder,path,configuration);
                        all[0] = true; changed[0]++;
                    }
                }
                byte[] previous = builder.auxiliary("fragment/"+path);
                FileFragment old = previous == null ? null : decode(previous);
                if (old == null && analyzers.forPath(path) == null) continue;
                FileFragment next = eligible(path,false) ? extract(root.resolve(path),old) : null;
                if (next == old) continue; // unchanged hash: no parsing, writes or generation churn
                if (next != null) parsed[0]++;
                if (old == null && next == null) continue;
                if (old == null || next == null || !shape(old).equals(shape(next))
                        || !old.modules().equals(next.modules())) all[0] = true;
                if (old != null) removeFragment(builder,old);
                if (next != null) { addFragment(builder,next); affected.add(path); }
                changed[0]++;
            }
            if (all[0]) {
                builder.scanAuxiliary("fragment/", (key,value) -> {
                    resolve(builder,decode(value)); resolved[0]++;
                });
            } else {
                for (String path : affected) {
                    resolve(builder,decode(builder.auxiliary("fragment/"+path))); resolved[0]++;
                }
            }
            return null;
        });
        record(changed[0] == 0 ? "unchanged" : all[0] ? "incremental-reresolve" : "incremental",
                parsed[0],resolved[0],changed[0],start);
    }

    private String normalize(String path) {
        if (path == null || path.isBlank() || path.length() > 32_768)
            throw new IllegalArgumentException("invalid incremental file path");
        Path relative = Path.of(path.replace('\\','/'));
        Path absolute = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !absolute.startsWith(root) || absolute.equals(root))
            throw new IllegalArgumentException("incremental path must be inside the project");
        return FullIndexer.relativize(root,absolute);
    }

    private boolean hasDescendants(String path) {
        class Found extends RuntimeException { }
        try {
            for (String prefix : List.of("fragment/","config/"))
                graph.documents(prefix+path+"/",(key,value)->{throw new Found();});
            return false;
        } catch (Found found) { return true; }
    }

    private boolean eligible(String path, boolean configuration) {
        try { return scanner.eligible(root,root.resolve(path),configuration); }
        catch (IOException e) { throw new UncheckedIOException("cannot check incremental file "+path,e); }
    }

    private FileFragment extract(Path path, FileFragment previous) {
        try {
            if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath()))
                throw new IOException("source target is outside the indexed root or is a symbolic link");
            byte[] bytes;
            try (var input = Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(2 * 1024 * 1024 + 1); }
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("file grew beyond 2 MiB during scan");
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("indexing interrupted");
            String relative = FullIndexer.relativize(root,path);
            var analyzer = analyzers.forPath(relative);
            if (analyzer == null) return null;
            String content = new String(bytes,StandardCharsets.UTF_8);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
            if (previous != null && previous.contentHash().equals(hash)) return previous;
            return analyzer.extract(new SourceFile(relative,analyzer.languageId(),content));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("hybrid extraction failed for "+path,e);
        }
    }

    private static FileFragment decode(byte[] value) { return RecordCodec.decode(value,RecordCodec.F.class).fragment(); }

    private static List<String> shape(FileFragment fragment) {
        return fragment.declarations().stream().filter(n->n.id() instanceof SymbolId)
                .map(n->n.id().value()+"|"+n.kind()+"|"+new TreeMap<>(n.attrs())).sorted().toList();
    }

    private static void symbolEntries(PagedGraph.Builder builder, Node node, boolean add) {
        if (!(node.id() instanceof SymbolId id) || node.kind()==NodeKind.DATABASE_MAPPING) return;
        String family = PagedGraph.encodeKey(ResolutionLanguages.family(id.lang()))+"/";
        String suffix = "/"+PagedGraph.encodeKey(id.value());
        String simple = "simple/"+family+PagedGraph.encodeKey(node.name())+suffix;
        String qualified = "qualified/"+family+PagedGraph.encodeKey(id.qualifiedName())+suffix;
        if (add) {
            byte[] encoded = RecordCodec.node(node);
            builder.auxiliary(simple,encoded); builder.auxiliary(qualified,encoded);
        } else {
            builder.removeAuxiliary(simple); builder.removeAuxiliary(qualified);
        }
    }

    private static void addFragment(PagedGraph.Builder builder, FileFragment fragment) {
        builder.auxiliary("fragment/"+fragment.file().relPath(),RecordCodec.encode(new RecordCodec.F(fragment)));
        if (ResolutionLanguages.compatible(fragment.lang(),"js"))
            builder.auxiliary("module/"+PagedGraph.encodeKey(fragment.file().relPath()),RecordCodec.encode(new RecordCodec.M(fragment)));
        builder.file(fragment.lang());
        for (Node node : fragment.declarations()) { builder.node(node); symbolEntries(builder,node,true); }
    }

    private static void removeOutgoing(PagedGraph.Builder builder, FileFragment fragment) {
        builder.removeOutgoing(fragment.file());
        for (Node node : fragment.declarations())
            if (!node.id().equals(fragment.file())) builder.removeOutgoing(node.id());
    }

    private static void removeFragment(PagedGraph.Builder builder, FileFragment fragment) {
        removeOutgoing(builder,fragment);
        for (Node node : fragment.declarations()) { builder.removeNode(node.id()); symbolEntries(builder,node,false); }
        builder.removeAuxiliary("fragment/"+fragment.file().relPath());
        builder.removeAuxiliary("module/"+PagedGraph.encodeKey(fragment.file().relPath()));
        builder.removeFile(fragment.lang());
    }

    private static int resolve(PagedGraph.Builder builder, FileFragment fragment) {
        removeOutgoing(builder,fragment);
        fragment.localEdges().forEach(builder::edge);
        var pending = new ArrayList<RawRef>();
        var edges = NameResolver.resolve(fragment,new HybridSymbolLookup(builder,fragment.file().relPath()),pending);
        Node evidence = NameResolver.resolutionNode(fragment,pending);
        if (evidence != null) builder.node(evidence);
        edges.forEach(builder::edge);
        return fragment.localEdges().size()+edges.size();
    }

    private static void replaceConfiguration(PagedGraph.Builder builder, String path, JsonNode next) {
        String key = "config/"+path;
        byte[] old = builder.auxiliary(key);
        boolean pkg = path.equals("package.json") || path.endsWith("/package.json");
        if (old != null && pkg) {
            String name = RecordCodec.decode(old,JsonNode.class).path("name").asText("");
            if (!name.isBlank()) builder.removeAuxiliary("package/"+PagedGraph.encodeKey(name)+"/"+PagedGraph.encodeKey(path));
        }
        if (next == null) builder.removeAuxiliary(key);
        else {
            builder.auxiliary(key,RecordCodec.encode(next));
            String name = next.path("name").asText("");
            if (pkg && !name.isBlank()) builder.auxiliary("package/"+PagedGraph.encodeKey(name)+"/"+PagedGraph.encodeKey(path),
                    path.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void record(String mode, int parsed, int resolved, int changed, long start) {
        lastUpdate = new IncrementalIndexer.HybridUpdate(mode,parsed,resolved,changed,
                (System.nanoTime()-start)/1_000_000,graph.generation());
    }
}
