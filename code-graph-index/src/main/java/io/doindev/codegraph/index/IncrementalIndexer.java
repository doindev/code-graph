package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.NameResolver;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.SymbolTable;
import io.doindev.codegraph.store.GraphDelta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateful indexer: a full index populates per-file bookkeeping, after which
 * {@link #applyChanges} patches the graph atomically per batch of changed files —
 * one {@link GraphDelta}, published by the engine in a single generation swap, so agents
 * never see a half-patched graph.
 *
 * <p>Healing semantics: a batch re-resolves (1) the changed files, (2) every file whose
 * pass-2 edges previously resolved <em>into</em> a changed file (their targets may have moved
 * or vanished), and (3) every file holding pending (unresolved) refs — a newly added
 * declaration may satisfy them.
 */
public final class IncrementalIndexer {

    private final Path root;
    private final Analyzers analyzers;
    private final CodeGraphConfig config;
    private final io.doindev.codegraph.store.ManagedGraph graph;
    private final HybridIndexer hybrid;
    private final FullIndexer fullIndexer;

    private final Object lock = new Object();
    private final Map<String, FileFragment> fragments = new HashMap<>();
    /** Pass-2 edges added on behalf of each source file (must be retracted before re-resolving it). */
    private final Map<String, List<Edge>> resolvedBySource = new HashMap<>();
    /** Reverse index: target file → source files that resolved edges into it. */
    private final Map<String, Set<String>> resolvedInto = new HashMap<>();
    /** Source files that still hold unresolved refs. */
    private final Set<String> withPending = new HashSet<>();

    public IncrementalIndexer(Path root, Analyzers analyzers, CodeGraphConfig config,
                              io.doindev.codegraph.store.ManagedGraph graph) {
        this.root = root.toAbsolutePath().normalize();
        this.analyzers = analyzers;
        this.config = config.withDefaults();
        this.graph = graph;
        this.fullIndexer = new FullIndexer(analyzers, config);
        this.hybrid = graph instanceof io.doindev.codegraph.storage.PagedGraph paged
                ? new HybridIndexer(this.root, analyzers, config, paged) : null;
    }

    public io.doindev.codegraph.store.ManagedGraph graph() {
        return graph;
    }

    /** Last successful hybrid operation; counters describe work, not total graph size. */
    public record HybridUpdate(String mode, int parsedFiles, int resolvedFiles, int changedFiles,
                               long elapsedMillis, long generation) { }
    public HybridUpdate lastHybridUpdate() { return hybrid == null ? null : hybrid.lastUpdate(); }

    /** Full (from-scratch) index; resets all bookkeeping. */
    public FullIndexer.Result fullIndex() {
        synchronized (lock) {
            if (hybrid != null) return hybrid.index();
            resolvedBySource.clear();
            resolvedInto.clear();
            withPending.clear();

            List<Path> files = fullIndexer.scan(root);
            Set<String> changed = new LinkedHashSet<>(fragments.keySet());
            for (Path file : files) {
                changed.add(FullIndexer.relativize(root, file));
            }
            return applyBatch(List.copyOf(changed), true);
        }
    }

    /** Re-index a batch of changed repo-relative paths (modified, created or deleted). */
    public void applyChanges(Collection<String> relPaths) {
        synchronized (lock) {
            if (hybrid != null) {
                hybrid.applyChanges(relPaths);
                return;
            }
            if (relPaths.contains("*") || relPaths.stream().anyMatch(p -> p.equals(".gitignore")
                    || p.endsWith("/.gitignore") || Files.isDirectory(root.resolve(p))
                    || fragments.keySet().stream().anyMatch(f -> f.startsWith(p+"/")))) {
                fullIndex();
                return;
            }
            applyBatch(List.copyOf(relPaths), false);
        }
    }

    private FullIndexer.Result applyBatch(List<String> candidatePaths, boolean initial) {
        // 1. re-extract candidates in parallel (deleted files simply lose their fragment)
        Set<String> removed = new LinkedHashSet<>();
        Set<String> reExtracted = new LinkedHashSet<>();
        boolean declarationsChanged = false;
        boolean configurationChanged=candidatePaths.stream().anyMatch(ModuleConfigurations::candidate);
        List<String> failed = new ArrayList<>();
        List<String> toExtract = new ArrayList<>();
        for (String relPath : candidatePaths) {
            boolean eligible;
            try { eligible = fullIndexer.eligible(root,root.resolve(relPath),false); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
            if (eligible) {
                toExtract.add(relPath);
            } else {
                FileFragment previous=fragments.remove(relPath);
                if(previous!=null) {removed.add(relPath);declarationsChanged=true;}
            }
        }
        java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(
                Math.max(2, Runtime.getRuntime().availableProcessors() * 2));
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, java.util.concurrent.CompletableFuture<FileFragment>> futures = new HashMap<>();
            for (String relPath : toExtract) {
                futures.put(relPath, java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        return FullIndexer.extractFile(root, root.resolve(relPath), analyzers);
                    } catch (IOException | RuntimeException e) {
                        synchronized (failed) {
                            failed.add(relPath + ": " + e.getMessage());
                        }
                        return null;
                    } finally {
                        permits.release();
                    }
                }, executor));
            }
            for (String relPath : toExtract) {
                FileFragment fragment = futures.get(relPath).join();
                if (fragment == null) {
                    // A failed/unsupported replacement cannot retain stale exact module bindings.
                    if(fragments.remove(relPath)!=null){removed.add(relPath);declarationsChanged=true;}
                    continue;
                }
                FileFragment previous = fragments.get(relPath);
                if (!initial && previous != null && previous.contentHash().equals(fragment.contentHash())) {
                    continue; // touch-only event
                }
                fragments.put(relPath, fragment);
                if(previous==null||!declarationShape(previous).equals(declarationShape(fragment))
                        ||!previous.modules().equals(fragment.modules())) declarationsChanged=true;
                reExtracted.add(relPath);
            }
        }
        if (removed.isEmpty() && reExtracted.isEmpty() && !configurationChanged) {
            return new FullIndexer.Result(0, 0, 0, List.of(), failed);
        }

        // 2. rebuild the symbol table and decide which files must re-resolve
        SymbolTable table = SymbolTable.of(fragments.values(),ModuleConfigurations.capture(fullIndexer,root));
        Set<String> affected = new LinkedHashSet<>(reExtracted);
        for (String changed : union(removed, reExtracted)) {
            affected.addAll(resolvedInto.getOrDefault(changed, Set.of()));
        }
        affected.addAll(withPending);
        // Return types, fields, inheritance and newly introduced overloads can change resolution
        // even when no old edge pointed at the edited file. Re-resolve without reparsing unchanged
        // files on declaration changes; ordinary method-body edits retain narrow invalidation.
        if(declarationsChanged||configurationChanged)affected.addAll(fragments.keySet());
        affected.retainAll(fragments.keySet());

        // 3. build ONE atomic delta
        List<FileId> removedFiles = new ArrayList<>();
        union(removed, reExtracted).forEach(p -> removedFiles.add(new FileId(p)));
        List<Node> addNodes = new ArrayList<>();
        List<Edge> addEdges = new ArrayList<>();
        List<Edge> removeEdges = new ArrayList<>();
        for (String relPath : reExtracted) {
            FileFragment fragment = fragments.get(relPath);
            addNodes.addAll(fragment.declarations());
            addEdges.addAll(fragment.localEdges());
        }
        for (String relPath : affected) {
            if (!reExtracted.contains(relPath)) {
                // this file's nodes survive; its stale pass-2 edges must be retracted explicitly
                removeEdges.addAll(resolvedBySource.getOrDefault(relPath, List.of()));
            }
            FileFragment fragment = fragments.get(relPath);
            List<RawRef> pending = new ArrayList<>();
            List<Edge> resolved = NameResolver.resolve(fragment, table, pending);
            Node evidence=NameResolver.resolutionNode(fragment,pending);
            if(evidence!=null)addNodes.add(evidence);
            addEdges.addAll(resolved);
            updateBookkeeping(relPath, resolved, pending);
        }
        for (String relPath : removed) {
            clearBookkeeping(relPath);
        }

        graph.apply(new GraphDelta(graph.generation() + 1, removedFiles, addNodes, addEdges, removeEdges));

        Map<String, Integer> filesPerLang = new HashMap<>();
        for (FileFragment fragment : fragments.values()) {
            filesPerLang.merge(fragment.lang(), 1, Integer::sum);
        }
        graph.filesPerLang(filesPerLang);

        int symbols = addNodes.size();
        return new FullIndexer.Result(initial ? fragments.size() : reExtracted.size() + removed.size(),
                symbols, addEdges.size(), List.of(), failed);
    }

    private void updateBookkeeping(String relPath, List<Edge> resolved, List<RawRef> pending) {
        List<Edge> previous = resolvedBySource.put(relPath, List.copyOf(resolved));
        if (previous != null) {
            for (Edge edge : previous) {
                String target = targetPath(edge);
                if (target != null) {
                    Set<String> sources = resolvedInto.get(target);
                    if (sources != null) {
                        sources.remove(relPath);
                    }
                }
            }
        }
        for (Edge edge : resolved) {
            String target = targetPath(edge);
            if (target != null && !target.equals(relPath)) {
                resolvedInto.computeIfAbsent(target, k -> new HashSet<>()).add(relPath);
            }
        }
        if (pending.isEmpty()) {
            withPending.remove(relPath);
        } else {
            withPending.add(relPath);
        }
    }

    private void clearBookkeeping(String relPath) {
        List<Edge> previous = resolvedBySource.remove(relPath);
        if (previous != null) {
            for (Edge edge : previous) {
                String target = targetPath(edge);
                if (target != null) {
                    Set<String> sources = resolvedInto.get(target);
                    if (sources != null) {
                        sources.remove(relPath);
                    }
                }
            }
        }
        withPending.remove(relPath);
        resolvedInto.remove(relPath);
    }

    private static String targetPath(Edge edge) {
        return edge.to() instanceof io.doindev.codegraph.model.SymbolId s ? s.relPath()
                : edge.to() instanceof FileId f ? f.relPath() : null;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union;
    }

    private static List<String> declarationShape(FileFragment fragment) {
        return fragment.declarations().stream().filter(n->n.id() instanceof io.doindev.codegraph.model.SymbolId)
                .map(n->n.id().value()+"|"+n.kind()+"|"+new java.util.TreeMap<>(n.attrs())).sorted().toList();
    }
}
