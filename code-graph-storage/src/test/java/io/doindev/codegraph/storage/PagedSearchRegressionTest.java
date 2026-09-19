package io.doindev.codegraph.storage;

import io.doindev.codegraph.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

/** Search/paging safety guards; wall-clock performance is measured by the isolated MCP benchmark. */
class PagedSearchRegressionTest {
    private static Node node(int i) {
        String path="src/Service"+i+".java";
        return new Node(new SymbolId("java",path,"Service"+i+".Méthod",0),NodeKind.FUNCTION,
                "Méthod","void Méthod()",new SourceSpan(path,i+1,1,i+2,1),Metrics.NONE,
                Map.of("payload","x".repeat(i%20==0?96*1024:700)));
    }
    private static List<String> scan(PagedGraph graph) {
        var names=new ArrayList<String>();
        graph.scanNodes(Set.of(NodeKind.FUNCTION),n->names.add(n.id().value()));
        names.sort(String::compareTo);return names;
    }

    @Test @Timeout(60) void searchesSurviveLargeRecordsUpdatesRollbacksAndCacheResize() {
        try(var storage=new GraphStorage(true,32L<<20)){
            var graph=(PagedGraph)storage.create();var other=(PagedGraph)storage.create();
            graph.rebuild(b->{for(int i=0;i<240;i++)b.node(node(i));return null;});
            other.rebuild(b->{b.node(node(999));return null;});
            assertEquals(32<<10,PagedGraph.PAGE_SPLIT_BYTES);
            assertEquals(240,scan(graph).size());
            assertEquals(240,graph.findSymbols("MÉTHOD",Set.of(NodeKind.FUNCTION),"java",300).size());
            assertEquals(node(0),graph.findSymbols("Service0.M",null,null,10).getFirst());
            assertEquals("x".repeat(96*1024),graph.node(node(0).id()).orElseThrow().attrs().get("payload"));
            var before=scan(graph);var generation=graph.generation();
            assertThrows(IllegalStateException.class,()->graph.update(b->{
                b.removeNode(node(0).id());b.node(node(500));
                for(int i=0;i<300;i++)b.auxiliary("flush/"+i,new byte[8192]);
                assertEquals(before,scan(graph));
                throw new IllegalStateException("failure after large-page checkpoints");
            }));
            assertEquals(generation,graph.generation());assertEquals(before,scan(graph));
            graph.update(b->{b.removeNode(node(0).id());b.node(node(500));return null;});
            assertEquals(240,scan(graph).size());
            assertTrue(graph.findSymbols("Service0.M",null,null,10).isEmpty(),"cached results must not outlive publication");
            assertEquals(node(500),graph.findSymbols("Service500.M",null,null,10).getFirst());
            assertEquals(List.of(node(999).id().value()),scan(other));
            storage.resize(64L<<20);storage.resize(32L<<20);
            assertEquals(240,scan(graph).size());
            assertEquals(0,storage.status().get("enginePageCacheBytes"));
            assertTrue(((Number)storage.status().get("cacheUsedBytesEstimate")).longValue()
                    <=((Number)storage.status().get("cacheCapacityBytes")).longValue());
        }
    }

    @Test @Timeout(60) void cancelledStreamingSearchDoesNotCloseSharedStoreOrPolluteHotCache() {
        try(var storage=new GraphStorage(true,32L<<20)){
            var graph=(PagedGraph)storage.create();
            graph.rebuild(b->{for(int i=0;i<240;i++)b.node(node(i));return null;});
            graph.node(node(1).id());
            long cached=((Number)storage.status().get("cacheUsedBytesEstimate")).longValue();
            int[] seen={0};
            try {
                assertThrows(CancellationException.class,()->graph.scanNodes(null,n->{
                    if(++seen[0]==10)Thread.currentThread().interrupt();
                }));
                assertTrue(Thread.currentThread().isInterrupted(),"preserve cancellation signal");
            }finally{Thread.interrupted();}
            assertEquals(cached,((Number)storage.status().get("cacheUsedBytesEstimate")).longValue());
            long hits=((Number)storage.status().get("cacheHits")).longValue();
            assertEquals(node(1),graph.node(node(1).id()).orElseThrow());
            assertEquals(hits+1,((Number)storage.status().get("cacheHits")).longValue());
            assertEquals(240,scan(graph).size());
            graph.update(b->{b.node(node(500));return null;});
            assertEquals(241,scan(graph).size());
        }
    }
}
