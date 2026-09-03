package io.doindev.codegraph.bench;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineTest {
    @TempDir Path directory;
    Engine open(String kind)throws Exception {return new Engine(kind,Files.createDirectory(directory.resolve(kind)),32L<<20);}
    private static List<Node> sorted(List<Node> nodes){return nodes.stream().sorted(Comparator.comparing(n->n.id().value())).toList();}
    private static void same(Object expected,Object actual){assertEquals(expected,actual);}
    @Test void graphQueriesMatchProductionIncludingDuplicateEdgesAndConfidence()throws Exception {
        try(Engine memory=open("memory");Engine mv=open("mvstore");Engine rocks=open("rocksdb")){
            List<Engine> engines=List.of(memory,mv,rocks);
            engines.forEach(e->e.load("project / with separators",l->{
                Ingestion.synthetic(l,100,500,42);
                l.edge(new Edge(Ingestion.syntheticNode(0).id(),Ingestion.syntheticNode(1).id(),EdgeKind.CALLS));
                l.edge(new Edge(Ingestion.syntheticNode(0).id(),Ingestion.syntheticNode(1).id(),EdgeKind.CALLS));
            }));
            String p="project / with separators";
            for(Engine disk:List.of(mv,rocks)){
                same(memory.query(p,q->sorted(q.allNodes(null))),disk.query(p,q->sorted(q.allNodes(null))));
                for(String search:List.of("m","M9","C1.","does-not-exist"))
                    same(memory.query(p,q->q.findSymbols(search,Set.of(NodeKind.FUNCTION),"java",7)),disk.query(p,q->q.findSymbols(search,Set.of(NodeKind.FUNCTION),"java",7)));
                for(int i=0;i<10;i++){NodeId id=Ingestion.syntheticNode(i).id();
                    for(Direction dir:Direction.values()){
                        same(memory.query(p,q->q.edges(id,dir,null)),disk.query(p,q->q.edges(id,dir,null)));
                        same(memory.query(p,q->q.closure(id,dir,null,3,13,.6f)),disk.query(p,q->q.closure(id,dir,null,3,13,.6f)));
                    }
                }
            }
            Node original=Ingestion.syntheticNode(0);Node changed=new Node(original.id(),original.kind(),"newName","newSignature",original.span(),original.metrics(),Map.of());
            var delta=new GraphDelta(2,List.of(new FileId(Ingestion.syntheticNode(5).relPath())),List.of(changed),List.of(),
                    List.of(new Edge(original.id(),Ingestion.syntheticNode(1).id(),EdgeKind.CALLS)));
            engines.forEach(e->e.apply(p,delta));
            for(Engine disk:List.of(mv,rocks)){
                same(memory.query(p,q->sorted(q.allNodes(null))),disk.query(p,q->sorted(q.allNodes(null))));
                for(int i=0;i<100;i++){NodeId id=Ingestion.syntheticNode(i).id();same(memory.query(p,q->q.edges(id,Direction.BOTH,null)),disk.query(p,q->q.edges(id,Direction.BOTH,null)));}
                long actual=disk.query(p,q->q.allNodes(null).stream().mapToLong(n->q.edges(n.id(),Direction.OUT,null).size()).sum());
                assertEquals(actual,disk.query(p,q->q.status().edgeCount()).longValue());
                // Existing engine removes all same-endpoint/kind edges but decrements its counter once.
                // Keep production untouched and make this baseline discrepancy explicit, not hidden.
                assertNotEquals(actual,memory.query(p,q->q.status().edgeCount()).longValue());
            }
        }
    }
    @Test void rollbackRemovalAndConcurrentGenerationViews()throws Exception {
        for(String kind:List.of("memory","mvstore","rocksdb"))try(Engine engine=open(kind)){
            engine.load("p0",l->Ingestion.synthetic(l,100,300,42));engine.load("p1",l->Ingestion.synthetic(l,100,300,42));
            Node node=Ingestion.syntheticNode(0);Node update=new Node(node.id(),node.kind(),"updated",null,node.span(),null,null);
            assertThrows(IllegalStateException.class,()->engine.apply("p0",new GraphDelta(2,List.of(),List.of(update),List.of(),List.of()),()->{throw new IllegalStateException("injected pre-commit write failure");}));
            assertEquals(node,engine.query("p0",q->q.node(node.id()).orElseThrow()));
            assertEquals(1L,engine.query("p0",q->q.status().generation()).longValue());
            BenchMain.concurrent(engine,node.id());
            engine.remove("p0");assertThrows(IllegalArgumentException.class,()->engine.query("p0",q->q.status()));
            assertEquals(node,engine.query("p1",q->q.node(node.id()).orElseThrow()));
            engine.load("p0",l->l.node(node));assertEquals(1L,engine.query("p0",q->q.status().generation()).longValue());
        }
    }
    @Test void sharedBudgetResizeAndExplicitUnsupportedResults()throws Exception {
        try(Engine mv=open("mvstore")){
            mv.load("a",l->Ingestion.synthetic(l,100,200,42));mv.load("b",l->Ingestion.synthetic(l,100,200,42));
            assertEquals(32L<<20,mv.stats().get("requestedBudgetBytes"));mv.resize(8L<<20);
            assertEquals(8L<<20,mv.stats().get("cacheCapacityBytes"));mv.resize(32L<<20);
            assertNotNull(mv.query("b",q->q.node(Ingestion.syntheticNode(1).id())));
            assertThrows(IllegalArgumentException.class,()->mv.resize(1));
        }
        try(Engine rocks=open("rocksdb")){
            long sum=((Number)rocks.stats().get("cacheCapacityBytes")).longValue()+((Number)rocks.stats().get("writeBufferAllowanceBytes")).longValue();
            assertEquals(32L<<20,sum);assertThrows(UnsupportedOperationException.class,()->rocks.resize(8L<<20));
        }
    }
    @Test void boundedRepositoryStagingPreservesProductionResolverChoices()throws Exception {
        Path root=Files.createDirectory(directory.resolve("repo"));
        Files.writeString(root.resolve("A.java"),"class A { static void target() {} public static void main(String[] args) { target(); B.call(); } }");
        Files.writeString(root.resolve("B.java"),"class B { static void call() { A.target(); } }");
        try(Engine memory=open("memory");Engine mv=open("mvstore");Engine rocks=open("rocksdb")){
            Ingestion m=new Ingestion();memory.load("real",l->m.repository(root,l,false));
            for(Engine disk:List.of(mv,rocks)){
                Ingestion d=new Ingestion();disk.load("real",l->d.repository(root,l,true));
                List<Node> nodes=memory.query("real",q->sorted(q.allNodes(null)));
                assertEquals(nodes,disk.query("real",q->sorted(q.allNodes(null))));
                for(Node n:nodes)same(memory.query("real",q->q.edges(n.id(),Direction.BOTH,null)),disk.query("real",q->q.edges(n.id(),Direction.BOTH,null)));
                assertEquals(0,d.retainedFragments.size());assertNull(d.retainedTable);assertTrue(d.fragmentBytes>0);
                assertTrue(((Number)disk.stats().get("maxSubmittedBatchBytes")).longValue()<=Engine.UPDATE_BYTES);
            }
        }
    }
    @Test void failedOnboardingDoesNotPublishAndCleanupIsScoped()throws Exception {
        try(Engine mv=open("mvstore")){
            assertThrows(IllegalStateException.class,()->mv.load("bad",l->{l.node(Ingestion.syntheticNode(0));l.flush();throw new IllegalStateException("fail");}));
            assertThrows(IllegalArgumentException.class,()->mv.query("bad",q->q.status()));
        }
        assertThrows(java.io.IOException.class,()->BenchMain.cleanup(directory));assertTrue(Files.exists(directory));
        Path owned=Files.createTempDirectory("code-graph-storage-bench-");Files.writeString(owned.resolve("owned.txt"),"temporary");BenchMain.cleanup(owned);assertFalse(Files.exists(owned));
    }
}
