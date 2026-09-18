package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.Edge;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pass 2: language-agnostic resolution ladder over the global {@link SymbolTable}. Each rung
 * tags its confidence so consumers can filter — heuristic edges are never presented as exact:
 * <ol>
 *   <li>import-driven exact qualified-name match — 1.0</li>
 *   <li>same file — 0.95; same directory — 0.9</li>
 *   <li>globally unique simple name (+arity compatibility) in the same language — 0.8</li>
 *   <li>ambiguous: one edge per candidate (max {@value #MAX_CANDIDATES}), 0.5/k each,
 *       {@code resolution=heuristic}</li>
 *   <li>no match: retained by the caller in the pending-refs index so later file additions
 *       can heal the reference</li>
 * </ol>
 */
public final class NameResolver {

    static final int MAX_CANDIDATES = 5;

    private NameResolver() {
    }

    /** Resolve one fragment's refs; unresolved refs are appended to {@code pendingOut}. */
    public static List<Edge> resolve(FileFragment fragment, SymbolTable table, List<RawRef> pendingOut) {
        List<Edge> edges = new ArrayList<>();
        for (RawRef ref : fragment.rawRefs()) {
            List<Edge> resolved = resolveOne(fragment, ref, table);
            if (resolved.isEmpty()) {
                pendingOut.add(ref);
            } else {
                edges.addAll(resolved);
            }
        }
        return edges;
    }

    private static List<Edge> resolveOne(FileFragment fragment, RawRef ref, SymbolTable table) {
        // rung 1: an import that ends in this name pins the qualified name exactly
        for (String imported : fragment.imports()) {
            if (imported.equals(ref.name()) || imported.endsWith("." + ref.name())
                    || imported.endsWith("::" + ref.name()) || imported.endsWith("/" + ref.name())) {
                var importedCandidates=table.byQualifiedName(normalizeImport(imported)).stream()
                        .filter(e->kindCompatible(ref,e)&&arityCompatible(ref,e)).toList();
                if(!importedCandidates.isEmpty())return candidates(ref,importedCandidates,1.0f,"import");
            }
        }

        List<SymbolTable.Entry> candidates = table.bySimpleName(ref.name()).stream()
                .filter(e -> kindCompatible(ref, e))
                .filter(e -> arityCompatible(ref, e))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        // rung 2: same file, then same directory
        String relPath = fragment.file().relPath();
        String dir = relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
        List<SymbolTable.Entry> sameFile = candidates.stream()
                .filter(e -> e.id().relPath().equals(relPath)).toList();
        if (!sameFile.isEmpty()) {
            return candidates(ref,sameFile,0.95f,"same-file");
        }
        List<SymbolTable.Entry> sameDir = candidates.stream()
                .filter(e -> dirOf(e.id().relPath()).equals(dir)).toList();
        if (!sameDir.isEmpty()) {
            return candidates(ref,sameDir,0.9f,"same-dir");
        }

        // rung 3: globally unique within the language
        List<SymbolTable.Entry> sameLang = candidates.stream()
                .filter(e -> e.id().lang().equals(fragment.lang())).toList();
        if (sameLang.size() == 1) {
            return List.of(edge(ref, sameLang.get(0), 0.8f, "unique-name"));
        }

        // rung 4: ambiguous — emit each candidate at reduced confidence
        List<SymbolTable.Entry> pool = sameLang.isEmpty() ? candidates : sameLang;
        List<SymbolTable.Entry> top = pool.stream().limit(MAX_CANDIDATES).toList();
        float confidence = 0.5f / top.size();
        List<Edge> edges = new ArrayList<>(top.size());
        for (SymbolTable.Entry entry : top) {
            edges.add(edge(ref, entry, confidence, "heuristic"));
        }
        return edges;
    }

    private static boolean kindCompatible(RawRef ref, SymbolTable.Entry entry) {
        return switch (ref.kind()) {
            case CALL -> entry.kind() == NodeKind.FUNCTION
                    || entry.kind() == NodeKind.TYPE; // constructor call via type name
            case TYPE_REF, EXTENDS, IMPLEMENTS -> entry.kind() == NodeKind.TYPE;
        };
    }

    private static boolean arityCompatible(RawRef ref, SymbolTable.Entry entry) {
        if (ref.arity() < 0 || entry.kind() != NodeKind.FUNCTION) {
            return true;
        }
        return entry.arity() == ref.arity();
    }

    private static List<Edge> candidates(RawRef ref,List<SymbolTable.Entry> entries,float uniqueConfidence,String scope) {
        if(entries.size()==1)return List.of(edge(ref,entries.getFirst(),uniqueConfidence,scope));
        var top=entries.stream().limit(MAX_CANDIDATES).toList();
        return top.stream().map(entry->edge(ref,entry,0.5f/top.size(),scope+"-ambiguous")).toList();
    }

    private static String dirOf(String relPath) {
        return relPath.contains("/") ? relPath.substring(0, relPath.lastIndexOf('/')) : "";
    }

    /** Imports written with {@code ::} or {@code /} separators are normalized to dots. */
    private static String normalizeImport(String imported) {
        return imported.replace("::", ".").replace('/', '.');
    }

    private static Edge edge(RawRef ref, SymbolTable.Entry target, float confidence, String resolution) {
        SymbolId from = ref.from() instanceof SymbolId s ? s : null;
        var attrs = new java.util.HashMap<String,String>();
        attrs.put("resolution", resolution);
        if (ref.site() != null) {
            var site = ref.site();
            attrs.put("site", String.valueOf(site.startLine())); // compatibility with existing consumers
            attrs.put("referencePath", site.relPath());
            attrs.put("referenceStartLine", String.valueOf(site.startLine()));
            attrs.put("referenceStartColumn", String.valueOf(site.startCol()));
            attrs.put("referenceEndLine", String.valueOf(site.endLine()));
            attrs.put("referenceEndColumn", String.valueOf(site.endCol()));
            // Calls carry the parsed expression; inheritance currently carries its declaration.
            // Do not describe either as a resolved identifier token.
            attrs.put("referencePrecision", ref.locationPrecision());
        }
        return new Edge(from != null ? from : ref.from(), target.id(), ref.kind().edgeKind(),
                confidence, attrs);
    }
}
