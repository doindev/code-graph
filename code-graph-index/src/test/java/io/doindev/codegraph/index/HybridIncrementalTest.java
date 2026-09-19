package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.storage.*;
import io.doindev.codegraph.store.ManagedGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridIncrementalTest {
    @TempDir Path root;
    private final Analyzers analyzers = Analyzers.discover();
    private void write(String path,String source) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file,source);
    }
    private IncrementalIndexer indexer(ManagedGraph graph) {
        return new IncrementalIndexer(root,analyzers,CodeGraphConfig.defaults(),graph);
    }
    private static Map<Edge,Integer> occurrences(List<Edge> edges) {
        var result = new HashMap<Edge,Integer>(); for(var edge:edges)result.merge(edge,1,Integer::sum); return result;
    }
    private void assertFreshParity(ManagedGraph actual) {
        var expected = new InMemoryCodeGraph(); indexer(expected).fullIndex();
        var nodes = new HashMap<NodeId,Node>(); expected.scanNodes(null,n->nodes.put(n.id(),n));
        var found = new HashMap<NodeId,Node>(); actual.scanNodes(null,n->found.put(n.id(),n));
        assertEquals(nodes,found);
        for (var id:nodes.keySet()) {
            assertEquals(occurrences(expected.edges(id,Direction.OUT,null)),occurrences(actual.edges(id,Direction.OUT,null)),"out "+id);
            assertEquals(occurrences(expected.edges(id,Direction.IN,null)),occurrences(actual.edges(id,Direction.IN,null)),"in "+id);
        }
        assertEquals(expected.status().filesIndexed(),actual.status().filesIndexed());
        assertEquals(expected.status().symbolCount(),actual.status().symbolCount());
        assertEquals(expected.status().edgeCount(),actual.status().edgeCount());
    }

    @Test void bodyEditsParseAndResolveOneFileNoopsDoNeitherAndDeclarationsHealCallers() throws Exception {
        write("A.java","class A { void run(){ B.go(); B.go(); } }");
        write("B.java","class B { static void go(){} }");
        write("C.java","class C { void unchanged(){} }");
        try(var storage = new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=indexer(graph);indexer.fullIndex();assertFreshParity(graph);
            var time=graph.status().lastIndexedAt();long generation=graph.generation();
            indexer.applyChanges(List.of("A.java","A.java","readme.txt"));
            assertEquals("unchanged",indexer.lastHybridUpdate().mode());
            assertEquals(0,indexer.lastHybridUpdate().parsedFiles());assertEquals(generation,graph.generation());
            assertEquals(time,graph.status().lastIndexedAt());
            write("A.java","class A { void run(){ B.go(); B.go(); B.go(); } }");
            indexer.applyChanges(List.of("A.java"));
            assertEquals("incremental",indexer.lastHybridUpdate().mode());
            assertEquals(1,indexer.lastHybridUpdate().parsedFiles());assertEquals(1,indexer.lastHybridUpdate().resolvedFiles());
            assertEquals(1,storage.status().get("activeAndStagedStores"));assertFreshParity(graph);
            write("B.java","class B { static void changed(){} }");
            indexer.applyChanges(List.of("B.java"));
            assertEquals("incremental-reresolve",indexer.lastHybridUpdate().mode());
            assertEquals(1,indexer.lastHybridUpdate().parsedFiles());assertEquals(3,indexer.lastHybridUpdate().resolvedFiles());
            assertFreshParity(graph);
            Files.delete(root.resolve("B.java")); indexer.applyChanges(List.of("B.java"));assertFreshParity(graph);
            write("B.java","class B { static void go(){} }");indexer.applyChanges(List.of("B.java"));assertFreshParity(graph);
        }
    }

    @Test void configurationExportMovesDeletionAndUnchangedJsonPreserveParity() throws Exception {
        write("base.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":[\"./one.js\"]}}}");
        write("tsconfig.json","{\"extends\":\"./base.json\"}");
        write("one.js","export function helper(){}");write("two.js","export function helper(){}");
        write("main.js","import {helper as run} from '@api'; run(); run();");
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=indexer(graph);indexer.fullIndex();assertFreshParity(graph);
            write("base.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":[\"./two.js\"]}}}");
            indexer.applyChanges(List.of("base.json"));
            assertEquals(0,indexer.lastHybridUpdate().parsedFiles());assertEquals(3,indexer.lastHybridUpdate().resolvedFiles());
            assertFreshParity(graph);
            indexer.applyChanges(List.of("base.json"));
            assertEquals("unchanged",indexer.lastHybridUpdate().mode());
            write("two.js","export function gone(){}");indexer.applyChanges(List.of("two.js"));assertFreshParity(graph);
            Files.move(root.resolve("one.js"),root.resolve("moved.js"));
            indexer.applyChanges(List.of("one.js","moved.js"));assertFreshParity(graph);
            Files.delete(root.resolve("base.json"));indexer.applyChanges(List.of("base.json"));assertFreshParity(graph);
        }
    }

    @Test void directoryIgnoreAndOverflowFallbacksRemoveStaleNodesInBothStores() throws Exception {
        for(boolean hybrid:List.of(false,true)) {
            write("sub/A.java","class A {}");write("B.java","class B {}");
            try(var storage=new GraphStorage(hybrid,32L<<20)) {
                var graph=storage.create();var indexer=indexer(graph);indexer.fullIndex();
                Files.delete(root.resolve("sub/A.java"));Files.delete(root.resolve("sub"));
                indexer.applyChanges(List.of("sub"));assertFreshParity(graph);
                write(".gitignore","B.java\n");indexer.applyChanges(List.of(".gitignore"));assertFreshParity(graph);
                Files.delete(root.resolve(".gitignore"));indexer.applyChanges(List.of("*"));assertFreshParity(graph);
                if(hybrid)assertEquals("full-fallback",indexer.lastHybridUpdate().mode());
            }
        }
    }

    @Test void failedExtractionRetainsPreviousGenerationAndSubsequentUpdateWorks() throws Exception {
        write("A.java","class A {}");write("B.java","class B {}");
        var java=analyzers.forPath("A.java");var fail=new AtomicBoolean();
        var injected=Analyzers.of(List.of(new LanguageAnalyzer() {
            public String languageId(){return "java";}
            public Set<String> fileExtensions(){return Set.of("java");}
            public FileFragment extract(SourceFile source){
                if(fail.get()&&source.relPath().equals("B.java"))throw new IllegalStateException("injected extraction failure");
                return java.extract(source);
            }
        }));
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=new IncrementalIndexer(root,injected,CodeGraphConfig.defaults(),graph);
            indexer.fullIndex();var nodes=graph.allNodes(null);long generation=graph.generation();
            write("A.java","class Changed {}");write("B.java","class AlsoChanged {}");fail.set(true);
            assertThrows(IllegalStateException.class,()->indexer.applyChanges(List.of("A.java","B.java")));
            assertEquals(nodes,graph.allNodes(null));assertEquals(generation,graph.generation());
            fail.set(false);indexer.applyChanges(List.of("A.java","B.java"));assertFreshParity(graph);
            assertThrows(IllegalArgumentException.class,()->indexer.applyChanges(List.of("../outside.java")));
        }
    }

    @Test void knownPathsStillHonorExclusionsIgnoreRulesAndFileSize() throws Exception {
        write("src/A.java","class A {}");write("excluded/B.java","class B {}");
        write("ignored/.gitignore","*.java\n");write("ignored/C.java","class C {}");
        write("src/large.java"," ".repeat((2<<20)+1));
        var config=new CodeGraphConfig(new CodeGraphConfig.Paths(List.of("**"),List.of("excluded/**")),
                null,null,null,null,null,Map.of(),null);
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=new IncrementalIndexer(root,analyzers,config,graph);indexer.fullIndex();
            long generation=graph.generation();
            indexer.applyChanges(List.of("excluded/B.java","ignored/C.java","src/large.java","target/D.java"));
            assertEquals(generation,graph.generation());assertEquals(1,graph.status().filesIndexed());
            write("src/A.java"," ".repeat((2<<20)+1));indexer.applyChanges(List.of("src/A.java"));
            assertEquals(0,graph.status().filesIndexed());
        }
    }

    @Test void symbolicLinkReplacementNeverIndexesAnOutsideTarget() throws Exception {
        write("source/A.java","class Original {}");write("outside/B.java","class Outside {}");
        Path source=root.resolve("source"),target=source.resolve("A.java");
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=new IncrementalIndexer(source,analyzers,CodeGraphConfig.defaults(),graph);indexer.fullIndex();
            Files.delete(target);
            try {Files.createSymbolicLink(target,root.resolve("outside/B.java"));}
            catch(java.io.IOException|UnsupportedOperationException e){org.junit.jupiter.api.Assumptions.abort("symlink creation unavailable: "+e.getClass().getSimpleName());}
            indexer.applyChanges(List.of("A.java"));
            assertEquals(0,graph.status().filesIndexed());assertTrue(graph.allNodes(null).isEmpty());
        }
    }

    @Test void repeatedEditsPreserveCountsAndReleasedVersionsReuseDiskSpace() throws Exception {
        write("A.java","class A { void go(){ go(); go(); } }");
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=indexer(graph);indexer.fullIndex();
            for(int n=0;n<60;n++) {
                write("A.java","class A { void go(){ go(); "+"go(); ".repeat(n%5)+"} }");
                indexer.applyChanges(List.of("A.java"));assertFreshParity(graph);
            }
            assertEquals(1,storage.status().get("activeAndStagedStores"));
            assertTrue(((Number)storage.status().get("diskBytes")).longValue()<16L<<20,storage.status().toString());
        }
    }

    @Test void watcherRetainsEditsArrivingWhileAChangeIsBeingParsed() throws Exception {
        write("A.java","class A { void go(){} }");write("B.java","class B { void old(){} }");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var block=new AtomicBoolean();
        var java=analyzers.forPath("A.java");
        var injected=Analyzers.of(List.of(new LanguageAnalyzer() {
            public String languageId(){return "java";}public Set<String> fileExtensions(){return Set.of("java");}
            public FileFragment extract(SourceFile file){
                if(file.relPath().equals("A.java")&&block.compareAndSet(true,false)) {
                    entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancellationException();}
                }
                return java.extract(file);
            }
        }));
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=new IncrementalIndexer(root,injected,CodeGraphConfig.defaults(),graph);indexer.fullIndex();
            try(var watcher=new Watcher(root,indexer)) {
                watcher.awaitStarted();block.set(true);write("A.java","class A { void go(){ go(); } }");
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                write("B.java","class B { void fresh(){} }");release.countDown();
                long deadline=System.nanoTime()+10_000_000_000L;
                while(graph.findSymbols("fresh",null,null,10).isEmpty()&&System.nanoTime()<deadline)Thread.sleep(50);
                assertFalse(graph.findSymbols("fresh",null,null,10).isEmpty());assertFreshParity(graph);
            } finally {release.countDown();}
        }
    }

    @Test void watcherRecoversATransientFailureWithoutRequiringAnotherFileEvent() throws Exception {
        write("A.java","class Old {}");
        var fail=new AtomicBoolean();var java=analyzers.forPath("A.java");
        var injected=Analyzers.of(List.of(new LanguageAnalyzer() {
            public String languageId(){return "java";}public Set<String> fileExtensions(){return Set.of("java");}
            public FileFragment extract(SourceFile file){
                if(fail.compareAndSet(true,false))throw new IllegalStateException("transient test failure");
                return java.extract(file);
            }
        }));
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=storage.create();var indexer=new IncrementalIndexer(root,injected,CodeGraphConfig.defaults(),graph);indexer.fullIndex();
            try(var watcher=new Watcher(root,indexer)) {
                watcher.awaitStarted();fail.set(true);write("A.java","class Healed {}");
                long deadline=System.nanoTime()+15_000_000_000L;
                while(graph.findSymbols("Healed",null,null,10).isEmpty()&&System.nanoTime()<deadline)Thread.sleep(50);
                assertFalse(graph.findSymbols("Healed",null,null,10).isEmpty());
                assertEquals("full-fallback",indexer.lastHybridUpdate().mode());assertFreshParity(graph);
            }
        }
    }
}
