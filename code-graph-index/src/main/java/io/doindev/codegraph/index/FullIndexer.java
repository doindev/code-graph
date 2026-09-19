package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.LanguageAnalyzer;
import io.doindev.codegraph.parse.NameResolver;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.SourceFile;
import io.doindev.codegraph.parse.SymbolTable;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.util.Globs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Full (from-scratch) index: walk the tree, extract every supported file in parallel on virtual
 * threads (bounded — parsing is CPU-bound), freeze the {@link SymbolTable}, resolve references
 * in parallel, and apply everything to the graph as ONE bulk delta (the engine's per-delta cost
 * makes many small deltas the wrong shape for bulk loading).
 */
public final class FullIndexer {

    /** Directories never worth indexing regardless of config. */
    static final Set<String> ALWAYS_IGNORED_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "dist", "out",
            "__pycache__", ".venv", "venv", ".idea", ".vscode", ".code-graph", "vendor", "bin", "obj");

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private final Analyzers analyzers;
    private final CodeGraphConfig config;

    public FullIndexer(Analyzers analyzers, CodeGraphConfig config) {
        this.analyzers = analyzers;
        this.config = config.withDefaults();
    }

    public record Result(int filesIndexed, int symbolCount, int edgeCount,
                         List<RawRef> pendingRefs, List<String> failedFiles) {
    }

    public Result index(Path root, InMemoryCodeGraph graph) {
        List<Path> files = scan(root);
        List<String> failed = new ArrayList<>();

        // pass 1 — parallel extraction
        List<FileFragment> fragments = extractAll(root, files, failed);

        // freeze the symbol table, then pass 2 — parallel resolution
        SymbolTable table = SymbolTable.of(fragments, ModuleConfigurations.capture(this,root));
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<RawRef> pending = new ArrayList<>();
        Map<String, Integer> filesPerLang = new HashMap<>();
        List<List<Edge>> resolvedPerFragment = resolveAll(fragments, table, pending);
        Map<String,List<RawRef>> pendingByFile=new HashMap<>();
        for(RawRef ref:pending)if(ref.site()!=null)pendingByFile.computeIfAbsent(ref.site().relPath(),p->new ArrayList<>()).add(ref);
        for (int i = 0; i < fragments.size(); i++) {
            FileFragment fragment = fragments.get(i);
            nodes.addAll(fragment.declarations());
            Node evidence=NameResolver.resolutionNode(fragment,pendingByFile.getOrDefault(fragment.file().relPath(),List.of()));
            if(evidence!=null)nodes.add(evidence);
            edges.addAll(fragment.localEdges());
            edges.addAll(resolvedPerFragment.get(i));
            filesPerLang.merge(fragment.lang(), 1, Integer::sum);
        }

        graph.apply(new GraphDelta(graph.generation() + 1, List.of(), nodes, edges, List.of()));
        graph.filesPerLang(filesPerLang);
        int symbols = (int) nodes.stream().filter(n -> n.kind() != NodeKind.FILE && n.kind()!=NodeKind.DATABASE_MAPPING).count();
        return new Result(fragments.size(), symbols, edges.size(), pending, failed);
    }

    // ---- scan ----

    List<Path> scan(Path root) {
        List<Path> files = new ArrayList<>();
        scan(root, files::add);
        return files;
    }

    void scan(Path root, java.util.function.Consumer<Path> visitor) {
        scan(root,visitor,false);
    }
    void scanConfigurations(Path root, java.util.function.Consumer<Path> visitor) {
        scan(root,visitor,true);
    }
    private void scan(Path root, java.util.function.Consumer<Path> visitor, boolean configurations) {
        List<String> include = config.paths().include();
        List<String> exclude = config.paths().exclude();
        List<List<GitIgnore.Rule>> ruleStack = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (ALWAYS_IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String relPath = relativize(root, dir);
                    if (!relPath.isEmpty() && GitIgnore.ignored(relPath, ruleStack)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    ruleStack.add(GitIgnore.load(dir, relPath.equals(".") ? "" : relPath));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) throw exc;
                    ruleStack.remove(ruleStack.size() - 1);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String relPath = relativize(root, file);
                    if (attrs.isSymbolicLink() || (configurations ? !ModuleConfigurations.candidate(relPath)
                            : analyzers.forPath(relPath) == null || attrs.size() > MAX_FILE_BYTES)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!configurations && !include.isEmpty() && !Globs.matchesAny(include, relPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (Globs.matchesAny(exclude, relPath) || GitIgnore.ignored(relPath, ruleStack)) {
                        return FileVisitResult.CONTINUE;
                    }
                    visitor.accept(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan " + root, e);
        }
    }

    /** Extract one file — shared with the incremental indexer. */
    static FileFragment extractFile(Path root, Path file, Analyzers analyzers) throws IOException {
        if(!file.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())
                ||Files.isSymbolicLink(file)||!file.toRealPath().startsWith(root.toRealPath()))
            throw new IOException("Source target is outside the indexed root or is a symbolic link");
        String relPath = relativize(root, file);
        LanguageAnalyzer analyzer = analyzers.forPath(relPath);
        if (analyzer == null) {
            return null;
        }
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return analyzer.extract(new SourceFile(relPath, analyzer.languageId(), content));
    }

    static String relativize(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    // ---- parallel passes ----

    private List<FileFragment> extractAll(Path root, List<Path> files, List<String> failed) {
        Semaphore permits = new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<FileFragment>> futures = new ArrayList<>(files.size());
            for (Path file : files) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        return extractFile(root, file, analyzers);
                    } catch (IOException | RuntimeException e) {
                        synchronized (failed) {
                            failed.add(relativize(root, file) + ": " + e.getMessage());
                        }
                        return null;
                    } finally {
                        permits.release();
                    }
                }, executor));
            }
            return futures.stream()
                    .map(CompletableFuture::join)
                    .filter(f -> f != null)
                    .toList();
        }
    }

    private List<List<Edge>> resolveAll(List<FileFragment> fragments, SymbolTable table,
                                        List<RawRef> pendingOut) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<Edge>>> futures = new ArrayList<>(fragments.size());
            List<List<RawRef>> pendingPerFragment = new ArrayList<>(fragments.size());
            for (FileFragment fragment : fragments) {
                List<RawRef> pending = new ArrayList<>();
                pendingPerFragment.add(pending);
                futures.add(CompletableFuture.supplyAsync(
                        () -> NameResolver.resolve(fragment, table, pending), executor));
            }
            List<List<Edge>> resolved = futures.stream().map(CompletableFuture::join).toList();
            pendingPerFragment.forEach(pendingOut::addAll);
            return resolved;
        }
    }
}
