package io.doindev.codegraph.index;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Capture configuration once per publication, without executing code or following symlinks. */
final class ModuleConfigurations {
    static final int MAX_BYTES=1024*1024;
    private static final ObjectMapper JSON=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS).enable(JsonReadFeature.ALLOW_TRAILING_COMMA).build());
    static boolean candidate(String path) { return path.endsWith(".json"); }
    static JsonNode read(Path path) {
        try(var stream=Files.newInputStream(path)) {
            byte[] data=stream.readNBytes(MAX_BYTES+1);
            if(data.length>MAX_BYTES)return invalid();
            JsonNode document=JSON.readTree(data);
            if(document==null||!document.isObject())return invalid();
            var selected=JSON.createObjectNode();
            for(String key:List.of("extends","compilerOptions","name","main","type","exports","imports","workspaces"))
                if(document.has(key))selected.set(key,document.get(key));
            return selected;
        } catch(IOException|RuntimeException e) { return invalid(); }
    }
    private static JsonNode invalid() {return JSON.createObjectNode().put("invalidModuleConfiguration",true);}
    static Map<String,JsonNode> capture(FullIndexer scanner,Path root) {
        var result=new HashMap<String,JsonNode>();
        scanner.scanConfigurations(root,path->result.put(FullIndexer.relativize(root,path),read(path)));
        return Map.copyOf(result);
    }
}
