package io.doindev.codegraph.index;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PendingChangesTest {
    @Test void debouncesAndDoesNotLoseSamePathAfterDrain() {
        var queue=new PendingChanges();queue.offer("a",0);queue.offer("a",200_000_000L);
        assertTrue(queue.drain(300_000_000L).isEmpty());
        assertEquals(List.of("a"),queue.drain(500_000_000L));
        queue.offer("a",500_000_001L);assertEquals(1,queue.size());
        assertEquals(List.of("a"),queue.drain(800_000_000L));
    }
    @Test void maximumCoalesceAndOverflowStayBounded() {
        var queue=new PendingChanges();
        for(int i=0;i<11;i++)queue.offer("a",i*100_000_000L);
        assertEquals(List.of("a"),queue.drain(1_000_000_000L));
        for(int i=0;i<HybridIndexer.MAX_BATCH_PATHS+1;i++)queue.offer("p"+i,0);
        assertEquals(1,queue.size());queue.offer("after",1);
        assertEquals(List.of("*"),queue.drain(300_000_000L));
        queue.offer("a",0);queue.offer("*",1);assertEquals(List.of("*"),queue.drain(300_000_000L));
        queue.offer("x".repeat(HybridIndexer.MAX_BATCH_PATH_BYTES+1),0);
        assertEquals(List.of("*"),queue.drain(300_000_000L));
    }
}
