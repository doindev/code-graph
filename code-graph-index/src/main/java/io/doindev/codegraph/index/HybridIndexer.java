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
            scanner.scanConfigurations(root,path->{
                String relative=FullIndexer.relativize(root,path);
                var configuration=ModuleConfigurations.read(path);
                builder.auxiliary("config/"+PagedGraph.encodeKey(relative),RecordCodec.encode(configuration));
                if(relative.equals("package.json")||relative.endsWith("/package.json")) {
                    String name=configuration.path("name").asText("");
                    if(!name.isBlank())builder.auxiliary("package/"+PagedGraph.encodeKey(name)+"/"+PagedGraph.encodeKey(relative),
                            relative.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            });
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
                if(ResolutionLanguages.compatible(fragment.lang(),"js"))
                    builder.auxiliary("module/"+PagedGraph.encodeKey(fragment.file().relPath()),RecordCodec.encode(new RecordCodec.M(fragment)));
                builder.file(fragment.lang()); files[0]++;
                for (Node node : fragment.declarations()) {
                    builder.node(node);
                    if (node.id() instanceof SymbolId id && node.kind()!=NodeKind.DATABASE_MAPPING) {
                        byte[] encoded = RecordCodec.node(node);
                        String ordinal = PagedGraph.sequence(nodes[0]);
                        String family = PagedGraph.encodeKey(ResolutionLanguages.family(id.lang())) + "/";
                        builder.auxiliary("simple/" + family + PagedGraph.encodeKey(node.name()) + "/" + ordinal, encoded);
                        builder.auxiliary("qualified/" + family + PagedGraph.encodeKey(id.qualifiedName()) + "/" + ordinal, encoded);
                    }
                    nodes[0]++;
                }
                fragment.localEdges().forEach(builder::edge); edges[0] += fragment.localEdges().size();
            });
            for (int i = 0; i < files[0]; i++) {
                FileFragment fragment = RecordCodec.decode(builder.auxiliary("fragment/" + PagedGraph.sequence(i)), RecordCodec.F.class).fragment();
                // Qualified receiver/type lookup stays on disk. A bounded per-file cache avoids
                // repeatedly decoding popular types without materializing a project symbol table.
                SymbolLookup lookup = new SymbolLookup() {
                    final LinkedHashMap<String,Object> cache = new LinkedHashMap<>(32,.75f,true);
                    long cacheBytes;
                    final Map<String,Long> sizes = new HashMap<>();
                    @SuppressWarnings("unchecked") List<SymbolTable.Entry> read(String prefix, String name, String sourceLanguage) {
                        String key=prefix+"/"+PagedGraph.encodeKey(ResolutionLanguages.family(sourceLanguage))+"/"+PagedGraph.encodeKey(name)+"/";
                        if(cache.containsKey(key))return (List<SymbolTable.Entry>)cache.get(key);
                        var found=new ArrayList<SymbolTable.Entry>();long[] bytes={0};
                        builder.scanAuxiliary(key,(k,v)->{
                            bytes[0]+=v.length;
                            if(found.size()>=10_000||bytes[0]>PagedGraph.MAX_RECORD_BYTES)
                                throw new IllegalArgumentException("resolver lookup exceeds hybrid bound for "+fragment.file().relPath());
                            found.add(SymbolTable.Entry.of(RecordCodec.node(v)));
                        });
                        var result=List.copyOf(found);
                        remember(key,result,bytes[0]+128);
                        return result;
                    }
                    void remember(String key,Object value,long bytes) {
                        cache.put(key,value);sizes.put(key,bytes);cacheBytes+=bytes;
                        while(cache.size()>256||cacheBytes>2L*1024*1024) {
                            String oldest=cache.keySet().iterator().next();
                            cache.remove(oldest);cacheBytes-=sizes.remove(oldest);
                        }
                    }
                    public ModuleFile module(String path) {
                        String key="module/"+PagedGraph.encodeKey(path);
                        if(cache.containsKey(key))return (ModuleFile)cache.get(key);
                        byte[] bytes=builder.auxiliary(key);
                        ModuleFile module=bytes==null?null:RecordCodec.decode(bytes,RecordCodec.M.class).module();
                        remember(key,module,bytes==null?128:bytes.length+128L);
                        return module;
                    }
                    public com.fasterxml.jackson.databind.JsonNode configuration(String path) {
                        String key="config/"+PagedGraph.encodeKey(path);
                        if(cache.containsKey(key))return (com.fasterxml.jackson.databind.JsonNode)cache.get(key);
                        byte[] bytes=builder.auxiliary(key);
                        var configuration=bytes==null?null:RecordCodec.decode(bytes,com.fasterxml.jackson.databind.JsonNode.class);
                        remember(key,configuration,bytes==null?128:bytes.length+128L);return configuration;
                    }
                    public List<String> packageConfigurations(String name) {
                        var paths=new ArrayList<String>();
                        class Enough extends RuntimeException {}
                        try {builder.scanAuxiliary("package/"+PagedGraph.encodeKey(name)+"/",(key,value)->{
                            if(paths.size()==2)throw new Enough();
                            paths.add(new String(value,java.nio.charset.StandardCharsets.UTF_8));
                        });}catch(Enough complete){}
                        return paths;
                    }
                    public String resolveModule(String source,String specifier,String kind,java.util.function.Function<String,ModuleFile> probe) {
                        return ProjectModulePaths.resolve(this,source,specifier,kind,probe);
                    }
                    public List<SymbolTable.Entry> bySimpleName(String name,String language) {return read("simple",name,language);}
                    public List<SymbolTable.Entry> byQualifiedName(String name,String language) {return read("qualified",name,language);}
                };
                var pending=new ArrayList<RawRef>();
                List<Edge> resolved = NameResolver.resolve(fragment, lookup, pending);
                Node evidence=NameResolver.resolutionNode(fragment,pending);
                if(evidence!=null)builder.node(evidence);
                resolved.forEach(builder::edge); edges[0] += resolved.size();
            }
            return new FullIndexer.Result(files[0], nodes[0], edges[0], List.of(), List.of());
        });
    }
}
