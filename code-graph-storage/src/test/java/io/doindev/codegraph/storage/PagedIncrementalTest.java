package io.doindev.codegraph.storage;

import io.doindev.codegraph.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PagedIncrementalTest {
    private static Node node(int id) { return PagedGraphTest.node(id); }
    private static void flushWrites(PagedGraph.Builder builder) {
        for(int i=0;i<600;i++)builder.auxiliary("scratch/"+i,new byte[4096]);
    }

    @Test @Timeout(60) void editsPublishTogetherAndDoNotAllocateAnotherStore() throws Exception {
        Path directory;
        try(var storage=new GraphStorage(true,32L<<20)) {
            directory=storage.directory();
            var graph=(PagedGraph)storage.create();var other=(PagedGraph)storage.create();
            PagedGraphTest.load(graph,10,30);PagedGraphTest.load(other,10,10);
            Path file;
            try(var paths=Files.list(directory)){file=paths.sorted().findFirst().orElseThrow();}
            var beforeFiles=storage.status().get("activeAndStagedStores");
            graph.node(node(1).id());graph.edges(node(1).id(),Direction.OUT,null);
            PagedGraph.Builder escaped=graph.update(builder->{
                builder.removeOutgoing(node(1).id());builder.removeNode(node(1).id());
                builder.node(node(5000));builder.edge(PagedGraphTest.edge(5000,0,1));
                assertTrue(graph.node(node(1).id()).isPresent(),"readers must see the prior snapshot");
                assertTrue(graph.node(node(5000).id()).isEmpty());
                return builder;
            });
            assertEquals(2,graph.generation());assertTrue(graph.node(node(1).id()).isEmpty());
            assertTrue(graph.node(node(5000).id()).isPresent());
            assertTrue(graph.edges(node(1).id(),Direction.OUT,null).isEmpty());
            assertEquals(10,graph.status().symbolCount());
            assertEquals(1,other.generation());assertTrue(other.node(node(1).id()).isPresent());
            assertEquals(beforeFiles,storage.status().get("activeAndStagedStores"));assertTrue(Files.exists(file));
            assertThrows(IllegalStateException.class,()->escaped.node(node(2)));
            long generation=graph.generation();var indexed=graph.status().lastIndexedAt();
            graph.update(builder->builder.auxiliary("absent"));
            assertEquals(generation,graph.generation());assertEquals(indexed,graph.status().lastIndexedAt());
        }
        assertFalse(Files.exists(directory));
    }

    @Test @Timeout(60) void checkpointedFailureAndCancellationRestoreTheWritableHead() {
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=(PagedGraph)storage.create();PagedGraphTest.load(graph,10,30);
            var expected=graph.edges(node(0).id(),Direction.BOTH,null);
            for(int repeat=0;repeat<3;repeat++) {
                assertThrows(IllegalStateException.class,()->graph.update(builder->{
                    builder.removeOutgoing(node(0).id());builder.removeNode(node(0).id());
                    builder.node(node(999));flushWrites(builder);
                    assertTrue(graph.node(node(0).id()).isPresent());
                    throw new IllegalStateException("injected after periodic commits");
                }));
                assertEquals(1,graph.generation());assertTrue(graph.node(node(999).id()).isEmpty());
                assertEquals(expected,graph.edges(node(0).id(),Direction.BOTH,null));
                assertNull(graph.document("scratch/1"));
            }
            try {
                assertThrows(CancellationException.class,()->graph.update(builder->{
                    builder.node(node(999));Thread.currentThread().interrupt();builder.node(node(1000));return null;
                }));
            } finally {Thread.interrupted();}
            assertEquals(1,graph.generation());
            graph.update(builder->{builder.node(node(77));return null;});
            assertEquals(2,graph.generation());assertTrue(graph.node(node(77).id()).isPresent());
            assertTrue(graph.node(node(999).id()).isEmpty());
            assertEquals(1,storage.status().get("activeAndStagedStores"));
            assertEquals(0L,storage.status().get("unsavedBytesEstimate"));
        }
    }

    @Test @Timeout(60) void snapshotReadersSurviveFlushesAndPublicationWaitsForTheirScope() throws Exception {
        try(var storage=new GraphStorage(true,32L<<20);var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var graph=(PagedGraph)storage.create();PagedGraphTest.load(graph,10,30);
            var reading=new CountDownLatch(1);var flushed=new CountDownLatch(1);var release=new CountDownLatch(1);
            var reader=executor.submit(()->graph.read(()->{
                reading.countDown();
                try {assertTrue(flushed.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                assertEquals(1,graph.generation());assertTrue(graph.node(node(1).id()).isPresent());
                assertTrue(graph.node(node(999).id()).isEmpty());release.countDown();return null;
            }));
            assertTrue(reading.await(15,TimeUnit.SECONDS));
            var writer=executor.submit(()->graph.update(builder->{
                builder.removeNode(node(1).id());builder.node(node(999));flushWrites(builder);
                flushed.countDown();
                try {assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}
                return null;
            }));
            reader.get();writer.get();
            assertEquals(2,graph.generation());assertTrue(graph.node(node(1).id()).isEmpty());
            assertTrue(graph.node(node(999).id()).isPresent());
            storage.resize(64L<<20);storage.resize(32L<<20);
            assertTrue(((Number)storage.status().get("cacheUsedBytesEstimate")).longValue()
                    <=((Number)storage.status().get("cacheCapacityBytes")).longValue());
        }
    }

    @Test void removalPreservesDuplicateIncomingAndSelfCallAccounting() {
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=(PagedGraph)storage.create();
            graph.rebuild(builder->{builder.node(node(0));builder.node(node(1));
                builder.edge(PagedGraphTest.edge(0,0,1));builder.edge(PagedGraphTest.edge(0,1,1));
                builder.edge(PagedGraphTest.edge(0,1,1));builder.edge(PagedGraphTest.edge(1,0,1));return null;});
            graph.update(builder->{builder.removeOutgoing(node(0).id());return null;});
            assertEquals(1,graph.status().edgeCount());
            assertEquals(1,graph.edges(node(0).id(),Direction.IN,null).size());
            assertTrue(graph.edges(node(1).id(),Direction.IN,null).isEmpty());
            graph.update(builder->{builder.edge(PagedGraphTest.edge(0,1,1));return null;});
            assertEquals(2,graph.status().edgeCount());
            assertEquals(1,graph.edges(node(1).id(),Direction.IN,null).size());
        }
    }

    @Test @Timeout(60) void stagedCursorsRemainValidWhileCallbacksReplaceAndFlushPages() {
        try(var storage=new GraphStorage(true,32L<<20)) {
            var graph=(PagedGraph)storage.create();
            graph.rebuild(builder->{
                flushWrites(builder);
                int[] seen={0};
                builder.scanAuxiliary("scratch/",(key,value)->{
                    seen[0]++;builder.removeAuxiliary(key.substring(2));
                    builder.auxiliary("replacement/"+seen[0],new byte[8192]);
                });
                assertEquals(600,seen[0]);return null;
            });
            int[] retained={0};graph.documents("replacement/",(key,value)->retained[0]++);
            assertEquals(600,retained[0]);assertNull(graph.document("scratch/1"));
            graph.update(builder->{
                int[] seen={0};
                builder.scanAuxiliary("replacement/",(key,value)->{
                    seen[0]++;builder.removeAuxiliary(key.substring(2));
                    builder.auxiliary("final/"+seen[0],new byte[8192]);
                });
                assertEquals(600,seen[0]);return null;
            });
            int[] count={0};graph.documents("final/",(key,value)->count[0]++);
            assertEquals(600,count[0]);assertNull(graph.document("replacement/1"));
        }
    }
}
