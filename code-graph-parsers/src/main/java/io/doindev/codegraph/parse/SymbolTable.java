package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable pass-2 lookup structure over every declaration extracted in pass 1.
 * Frozen once (single-threaded build), then read concurrently by resolving threads.
 */
public final class SymbolTable {

    /** One declared symbol as seen by the resolver. */
    public record Entry(SymbolId id, NodeKind kind, int arity) {
    }

    private final Map<String, List<Entry>> bySimpleName;
    private final Map<String, List<Entry>> byQualifiedName;

    private SymbolTable(Map<String, List<Entry>> bySimpleName, Map<String, List<Entry>> byQualifiedName) {
        this.bySimpleName = bySimpleName;
        this.byQualifiedName = byQualifiedName;
    }

    public static SymbolTable of(Collection<FileFragment> fragments) {
        Map<String, List<Entry>> bySimple = new HashMap<>();
        Map<String, List<Entry>> byQualified = new HashMap<>();
        for (FileFragment fragment : fragments) {
            for (Node node : fragment.declarations()) {
                if (!(node.id() instanceof SymbolId id)) {
                    continue;
                }
                Entry entry = new Entry(id, node.kind(), id.arity());
                bySimple.computeIfAbsent(node.name(), k -> new ArrayList<>()).add(entry);
                byQualified.computeIfAbsent(id.qualifiedName(), k -> new ArrayList<>()).add(entry);
            }
        }
        bySimple.replaceAll((k, v) -> List.copyOf(v));
        byQualified.replaceAll((k, v) -> List.copyOf(v));
        return new SymbolTable(Map.copyOf(bySimple), Map.copyOf(byQualified));
    }

    public List<Entry> bySimpleName(String name) {
        return bySimpleName.getOrDefault(name, List.of());
    }

    public List<Entry> byQualifiedName(String qualifiedName) {
        return byQualifiedName.getOrDefault(qualifiedName, List.of());
    }

    public int size() {
        return byQualifiedName.size();
    }
}
