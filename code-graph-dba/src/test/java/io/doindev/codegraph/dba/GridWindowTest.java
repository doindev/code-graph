package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GridWindowTest {
    @TempDir Path root;
    @Test void adjacentWindowsAreBoundedOwnedCachedAndInvalidated()throws Exception{
        var config=new DbaConfig(root,128L<<20,2,1000,100,15);
        var alive=new AtomicBoolean(true);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->alive.get());var grids=new GridResults(jobs,connections,()->config,s->alive.get())){
            jobs.grids=grids;String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){
                c.setAutoCommit(true);s.execute("CREATE TABLE WINDOW_ROWS(ID INT PRIMARY KEY, LABEL VARCHAR(50))");
                s.execute("INSERT INTO WINDOW_ROWS SELECT X, 'row-'||X FROM SYSTEM_RANGE(1,751)");
                JsonNode job=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM PUBLIC.WINDOW_ROWS",Profiles.JSON.createArrayNode(),false));
                assertEquals("complete",job.path("state").asText(),job.toString());
                ObjectNode grid=(ObjectNode)job.path("result").path("results").get(0).path("grid");String gridId=grid.path("id").asText();
                assertEquals(1,grids.pages.telemetry().path("windows").asInt());
                var request=Profiles.JSON.createObjectNode().put("revision",1).put("direction","window").put("limit",200).put("offset",100);
                assertThrows(SecurityException.class,()->grids.operation("other",gridId,"page",request));
                assertThrows(IllegalArgumentException.class,()->grids.operation("human",gridId,"page",request.deepCopy().put("offset",999)));
                assertThrows(IllegalArgumentException.class,()->grids.operation("human",gridId,"page",request.deepCopy().put("limit",201)));
                JsonNode next=page(grids,jobs,grid,100);
                assertEquals(200,next.path("rows").size());assertEquals(101,next.path("rows").get(0).get(0).asInt());
                assertFalse(next.path("grid").path("page").path("cached").asBoolean());assertFalse(next.path("grid").path("page").has("total"));
                // A recently visited window is a read cache, not a long-running JDBC cursor.
                s.execute("UPDATE WINDOW_ROWS SET LABEL='external change' WHERE ID=1");
                next=page(grids,jobs,(ObjectNode)next.path("grid"),0);
                assertTrue(next.path("grid").path("page").path("cached").asBoolean());
                assertEquals("row-1",next.path("rows").get(0).get(1).asText());
                grid=(ObjectNode)next.path("grid");
                JsonNode fresh=finish(grids,jobs,grid,"page",Profiles.JSON.createObjectNode().put("direction","refresh").put("limit",200));
                assertEquals("external change",fresh.path("rows").get(0).get(1).asText());
                assertFalse(fresh.path("grid").path("page").path("cached").asBoolean());
                grid=(ObjectNode)fresh.path("grid");
                for(int offset=100;offset<=600;offset+=100){next=page(grids,jobs,grid,offset);grid=(ObjectNode)next.path("grid");assertTrue(next.path("rows").size()<=200);assertTrue(grids.pages.telemetry().path("windows").asInt()<=3);}
                assertEquals(151,next.path("rows").size());assertFalse(grid.path("page").path("hasMore").asBoolean());
                long revision=grid.path("revision").asLong();
                assertThrows(IllegalArgumentException.class,()->grids.operation("human",gridId,"page",request.deepCopy().put("revision",revision-1)));
                // Owner expiry releases both the current snapshot and all cached windows.
                alive.set(false);grids.reap();assertEquals(0,grids.pages.telemetry().path("windows").asInt());assertEquals(0,grids.telemetry().path("contexts").asInt());
            }
        }
    }
    @Test void cacheExpiryBudgetReductionAndReleaseAreAccounted()throws Exception{
        var config=new DbaConfig(root,128L<<20,2,1000,100,15);var now=new AtomicLong(System.currentTimeMillis());var budget=new AtomicLong(config.memoryBytes());
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var cache=new GridPageCache(jobs,budget::get,now::get)){
            var reservation=jobs.retainAllowance(4096);
            var context=new GridResults.Context("human","connection","revision","db","schema","SELECT 1",Profiles.JSON.createArrayNode(),"job",reservation);
            context.orderedSql="SELECT 1";context.limit=200;context.result=Profiles.JSON.createObjectNode();context.result.putArray("rows").addArray().add(1);context.capturedAt=now.get();
            long before=jobs.availableRetainedBytes();
            for(int offset=0;offset<4;offset++){context.offset=offset;cache.put(context);}
            assertEquals(3,cache.telemetry().path("windows").asInt());assertNull(cache.get(context.id,0,200));
            assertNotNull(cache.get(context.id,1,200));context.offset=4;cache.put(context);
            assertNotNull(cache.get(context.id,1,200));assertNull(cache.get(context.id,2,200),"Access ordering survives TTL checks");
            assertTrue(jobs.availableRetainedBytes()<before);
            now.addAndGet(GridPageCache.TTL_MILLIS+1);cache.reap();assertEquals(before,jobs.availableRetainedBytes());
            context.capturedAt=now.get();cache.put(context);budget.set(1);cache.reap();assertEquals(0,cache.telemetry().path("windows").asInt());assertEquals(before,jobs.availableRetainedBytes());
            budget.set(config.memoryBytes());cache.put(context);cache.removeContext(context.id);assertEquals(before,jobs.availableRetainedBytes());reservation.close();
        }
    }
    @Test void disposingQueuedWindowReleasesCacheAndPreventsLatePublication()throws Exception{
        var config=new DbaConfig(root,128L<<20,1,1000,100,15);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){
                c.setAutoCommit(true);s.execute("CREATE TABLE WINDOW_DISPOSE(ID INT PRIMARY KEY)");s.execute("INSERT INTO WINDOW_DISPOSE SELECT X FROM SYSTEM_RANGE(1,300)");
                var query=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM PUBLIC.WINDOW_DISPOSE",Profiles.JSON.createArrayNode(),false));
                String grid=query.path("result").path("results").get(0).path("grid").path("id").asText();
                var release=new java.util.concurrent.CountDownLatch(1);
                var blocked=jobs.local("human",job->{release.await();return Profiles.JSON.createObjectNode();},()->{});
                JsonNode queued;
                try{
                    queued=grids.operation("human",grid,"page",Profiles.JSON.createObjectNode().put("revision",1).put("direction","window").put("limit",200).put("offset",100));
                    jobs.cancel(jobs.require("human",queued.path("id").asText()));grids.release("human",grid);
                    assertEquals(0,grids.pages.telemetry().path("windows").asInt());
                }finally{release.countDown();}
                HumanSqlTest.finish(jobs,"human",blocked);
                assertEquals("cancelled",HumanSqlTest.finish(jobs,"human",queued).path("state").asText());
                assertEquals(0,grids.telemetry().path("contexts").asInt());assertEquals(config.memoryBytes(),jobs.availableRetainedBytes());
            }
        }
    }
    private static JsonNode page(GridResults grids,QueryJobs jobs,ObjectNode grid,int offset)throws Exception{
        return finish(grids,jobs,grid,"page",Profiles.JSON.createObjectNode().put("direction","window").put("limit",200).put("offset",offset));
    }
    private static JsonNode finish(GridResults grids,QueryJobs jobs,ObjectNode grid,String action,ObjectNode request)throws Exception{
        request.put("revision",grid.path("revision").asLong());
        var done=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),action,request));
        assertEquals("complete",done.path("state").asText(),done.toString());return done.path("result");
    }
}
