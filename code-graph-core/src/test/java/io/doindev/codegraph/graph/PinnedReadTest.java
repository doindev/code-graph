package io.doindev.codegraph.graph;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PinnedReadTest {
    @Test void nestedReadsSeeOneGenerationAndReleaseItAfterFailure() {
        var graph=new InMemoryCodeGraph();
        var a=new Node(new FileId("a.js"),NodeKind.FILE,"a","a",new SourceSpan("a.js",1,1,1,1),Metrics.NONE,Map.of());
        var b=new Node(new FileId("b.js"),NodeKind.FILE,"b","b",new SourceSpan("b.js",1,1,1,1),Metrics.NONE,Map.of());
        graph.apply(new GraphDelta(1,List.of(),List.of(a),List.of(),List.of()));
        assertThrows(IllegalStateException.class,()->graph.read(()->{
            graph.apply(new GraphDelta(2,List.of(),List.of(b),List.of(new Edge(a.id(),b.id(),EdgeKind.CALLS)),List.of()));
            assertEquals(1,graph.generation());
            assertTrue(graph.node(b.id()).isEmpty());
            assertTrue(graph.closure(a.id(),Direction.OUT,Set.of(EdgeKind.CALLS),2,10,0).hits().isEmpty());
            assertEquals(1L,graph.read(graph::generation));
            throw new IllegalStateException("release scope");
        }));
        assertEquals(2,graph.generation());assertTrue(graph.node(b.id()).isPresent());
    }
}
