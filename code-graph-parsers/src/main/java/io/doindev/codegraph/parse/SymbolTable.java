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
public final class SymbolTable implements SymbolLookup {

    /** One declared symbol as seen by the resolver. */
    public record Entry(SymbolId id, NodeKind kind, int arity, Map<String,String> attrs) {
        public Entry(SymbolId id, NodeKind kind, int arity) { this(id,kind,arity,Map.of()); }
        public static Entry of(Node node) { return new Entry((SymbolId)node.id(),node.kind(),((SymbolId)node.id()).arity(),node.attrs()); }
    }

    private record Key(String family, String name) {
        static Key of(String language, String name) { return new Key(ResolutionLanguages.family(language), name); }
    }
    private final Map<Key, List<Entry>> bySimpleName;
    private final Map<Key, List<Entry>> byQualifiedName;
    private final Map<String, ModuleFile> modules;
    private Map<String,com.fasterxml.jackson.databind.JsonNode> configurations = Map.of();
    private Map<String,List<String>> packageFiles = Map.of();

    private SymbolTable(Map<Key, List<Entry>> bySimpleName, Map<Key, List<Entry>> byQualifiedName, Map<String, ModuleFile> modules) {
        this.bySimpleName = bySimpleName;
        this.byQualifiedName = byQualifiedName;
        this.modules = Map.copyOf(modules);
    }

    public static SymbolTable of(Collection<FileFragment> fragments) {
        Map<Key, List<Entry>> bySimple = new HashMap<>();
        Map<Key, List<Entry>> byQualified = new HashMap<>();
        Map<String, ModuleFile> modules = new HashMap<>();
        for (FileFragment fragment : fragments) {
            if (ResolutionLanguages.family(fragment.lang()).equals(ResolutionLanguages.family("js")))
                modules.put(fragment.file().relPath(), ModuleFile.of(fragment));
            for (Node node : fragment.declarations()) {
                if (!(node.id() instanceof SymbolId id) || node.kind() == NodeKind.DATABASE_MAPPING) {
                    continue;
                }
                Entry entry = Entry.of(node);
                bySimple.computeIfAbsent(Key.of(id.lang(), node.name()), k -> new ArrayList<>()).add(entry);
                byQualified.computeIfAbsent(Key.of(id.lang(), id.qualifiedName()), k -> new ArrayList<>()).add(entry);
            }
        }
        bySimple.replaceAll((k, v) -> List.copyOf(v));
        byQualified.replaceAll((k, v) -> List.copyOf(v));
        return new SymbolTable(Map.copyOf(bySimple), Map.copyOf(byQualified), modules);
    }

    @Override public ModuleFile module(String path) { return modules.get(path); }
    public static SymbolTable of(Collection<FileFragment> fragments,Map<String,com.fasterxml.jackson.databind.JsonNode> configurations) {
        SymbolTable table=of(fragments);table.configurations=Map.copyOf(configurations);
        Map<String,List<String>> packages=new HashMap<>();
        configurations.forEach((path,value)->{
            if(path.equals("package.json")||path.endsWith("/package.json")) {
                String name=value.path("name").asText("");
                if(!name.isBlank()) {
                    var files=packages.computeIfAbsent(name,k->new ArrayList<>());
                    if(files.size()<2)files.add(path); // Two entries suffice to report ambiguity.
                }
            }
        });
        table.packageFiles=Map.copyOf(packages);return table;
    }
    @Override public com.fasterxml.jackson.databind.JsonNode configuration(String path) {return configurations.get(path);}
    @Override public List<String> packageConfigurations(String name) {return packageFiles.getOrDefault(name,List.of());}
    @Override public String resolveModule(String source,String specifier,String kind,java.util.function.Function<String,ModuleFile> probe) {
        return ProjectModulePaths.resolve(this,source,specifier,kind,probe);
    }

    public List<Entry> bySimpleName(String name, String sourceLanguage) {
        return bySimpleName.getOrDefault(Key.of(sourceLanguage, name), List.of());
    }

    public List<Entry> byQualifiedName(String qualifiedName, String sourceLanguage) {
        return byQualifiedName.getOrDefault(Key.of(sourceLanguage, qualifiedName), List.of());
    }

    public int size() {
        return byQualifiedName.size();
    }
}
