package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.model.FileId;
import io.doindev.codegraph.storage.GraphStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileRevisionPublicationTest {
    @TempDir Path root;
    static String hash(String source)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}
    @Test void bothEnginesPublishHashesWithSymbolsAndPreserveThemAcrossReresolution()throws Exception{
        for(boolean hybrid:List.of(false,true)){
            String java="class A { void go(){} }",js="import {go} from './b.js'; go();",xml="<mapper namespace=\"A\"><select id=\"all\">select * from users</select></mapper>";
            Files.writeString(root.resolve("A.java"),java);Files.writeString(root.resolve("a.js"),js);
            Files.writeString(root.resolve("b.js"),"export function go(){}");Files.writeString(root.resolve("mapper.xml"),xml);
            try(var storage=new GraphStorage(hybrid,32L<<20)){
                var graph=storage.create();var indexer=new IncrementalIndexer(root,Analyzers.discover(),CodeGraphConfig.defaults(),graph);
                indexer.fullIndex();
                for(var entry:Map.of("A.java",java,"a.js",js,"mapper.xml",xml).entrySet())
                    assertEquals(hash(entry.getValue()),graph.node(new FileId(entry.getKey())).orElseThrow().attrs().get("indexedContentHash"));
                long generation=graph.generation();String changed="class A { void edited(){} }";Files.writeString(root.resolve("A.java"),changed);
                Files.writeString(root.resolve("b.js"),"export function gone(){}");indexer.applyChanges(List.of("A.java","b.js"));
                assertTrue(graph.generation()>generation);
                assertEquals(hash(changed),graph.node(new FileId("A.java")).orElseThrow().attrs().get("indexedContentHash"));
                assertEquals(hash(js),graph.node(new FileId("a.js")).orElseThrow().attrs().get("indexedContentHash"));
                Files.delete(root.resolve("A.java"));indexer.applyChanges(List.of("A.java"));
                assertTrue(graph.node(new FileId("A.java")).isEmpty());
            }
        }
    }
}

