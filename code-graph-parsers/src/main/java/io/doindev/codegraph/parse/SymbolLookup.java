package io.doindev.codegraph.parse;

import java.util.List;

/**
 * Same resolver contract for memory and bounded, on-demand disk lookups.
 * Implementations select the source language's resolution family BEFORE
 * materialization/lookup limits; unrelated names must not exhaust that budget.
 */
public interface SymbolLookup {
    default ModuleFile module(String relativePath) { return null; }
    default com.fasterxml.jackson.databind.JsonNode configuration(String path) { return null; }
    default List<String> packageConfigurations(String name) { return List.of(); }
    default String resolveModule(String source, String specifier, String kind, java.util.function.Function<String,ModuleFile> probe) {
        String path = EcmaModuleResolver.relative(source, specifier);
        if(path == null)return null;
        if(probe.apply(path)!=null)return path;
        // Only static CommonJS has implicit Node file/directory lookup without a config.
        if(kind.startsWith("commonjs"))for(String suffix:List.of(".js",".cjs","/index.js","/index.cjs"))
            if(probe.apply(path+suffix)!=null)return path+suffix;
        return null;
    }
    List<SymbolTable.Entry> bySimpleName(String name, String sourceLanguage);
    List<SymbolTable.Entry> byQualifiedName(String name, String sourceLanguage);
}
