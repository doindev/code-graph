package io.doindev.codegraph.index;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.storage.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Per-file disk lookup shared by full and incremental resolution; no project-wide heap index. */
final class HybridSymbolLookup implements SymbolLookup {
    private final PagedGraph.Builder builder;
    private final String source;
    private final LinkedHashMap<String,Object> cache = new LinkedHashMap<>(32,.75f,true);
    private final Map<String,Long> sizes = new HashMap<>();
    private long cacheBytes;

    HybridSymbolLookup(PagedGraph.Builder builder,String source) {this.builder=builder;this.source=source;}
    @SuppressWarnings("unchecked")
    private List<SymbolTable.Entry> read(String prefix,String name,String language) {
        String key=prefix+"/"+PagedGraph.encodeKey(ResolutionLanguages.family(language))+"/"+PagedGraph.encodeKey(name)+"/";
        if(cache.containsKey(key))return (List<SymbolTable.Entry>)cache.get(key);
        var found=new ArrayList<SymbolTable.Entry>();long[] bytes={0};
        builder.scanAuxiliary(key,(ignored,value)->{
            bytes[0]+=value.length;
            if(found.size()>=10_000||bytes[0]>PagedGraph.MAX_RECORD_BYTES)
                throw new IllegalArgumentException("resolver lookup exceeds hybrid bound for "+source);
            found.add(SymbolTable.Entry.of(RecordCodec.node(value)));
        });
        var result=List.copyOf(found);remember(key,result,bytes[0]+128);return result;
    }
    private void remember(String key,Object value,long bytes) {
        cache.put(key,value);sizes.put(key,bytes);cacheBytes+=bytes;
        while(cache.size()>256||cacheBytes>2L*1024*1024) {
            String oldest=cache.keySet().iterator().next();
            cache.remove(oldest);cacheBytes-=sizes.remove(oldest);
        }
    }
    @Override public ModuleFile module(String path) {
        String key="module/"+PagedGraph.encodeKey(path);
        if(cache.containsKey(key))return (ModuleFile)cache.get(key);
        byte[] bytes=builder.auxiliary(key);
        ModuleFile module=bytes==null?null:RecordCodec.decode(bytes,RecordCodec.M.class).module();
        remember(key,module,bytes==null?128:bytes.length+128L);return module;
    }
    @Override public JsonNode configuration(String path) {
        String key="config/"+path;
        if(cache.containsKey(key))return (JsonNode)cache.get(key);
        byte[] bytes=builder.auxiliary(key);
        JsonNode configuration=bytes==null?null:RecordCodec.decode(bytes,JsonNode.class);
        remember(key,configuration,bytes==null?128:bytes.length+128L);return configuration;
    }
    @Override public List<String> packageConfigurations(String name) {
        var paths=new ArrayList<String>();
        class Enough extends RuntimeException {}
        try {builder.scanAuxiliary("package/"+PagedGraph.encodeKey(name)+"/",(key,value)->{
            if(paths.size()==2)throw new Enough();
            paths.add(new String(value,StandardCharsets.UTF_8));
        });}catch(Enough complete){}
        return paths;
    }
    @Override public String resolveModule(String source,String specifier,String kind,
            java.util.function.Function<String,ModuleFile> probe) {
        return ProjectModulePaths.resolve(this,source,specifier,kind,probe);
    }
    @Override public List<SymbolTable.Entry> bySimpleName(String name,String language) {return read("simple",name,language);}
    @Override public List<SymbolTable.Entry> byQualifiedName(String name,String language) {return read("qualified",name,language);}
}
