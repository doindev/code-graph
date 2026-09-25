package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.store.DocumentStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CatalogScanStatusTest {
    @TempDir Path directory;
    ProjectContextsTest fixture;
    CatalogCache cache;
    final CountDownLatch first=new CountDownLatch(1),second=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),releaseSecond=new CountDownLatch(1);
    @BeforeEach void setup()throws Exception{
        fixture=new ProjectContextsTest();fixture.directory=directory;fixture.setup();
        AtomicInteger calls=new AtomicInteger();
        cache=new CatalogCache(fixture.profiles,fixture.connections,()->{
            int call=calls.incrementAndGet();CountDownLatch reached=call==1?first:second,release=call==1?releaseFirst:releaseSecond;
            reached.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timed out");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
            return DocumentStore.memory(64L<<20);
        },fixture.clock::get);
    }
    @AfterEach void close()throws Exception{releaseFirst.countDown();releaseSecond.countDown();if(cache!=null)cache.close();if(fixture!=null)fixture.cleanup();}
    CatalogCache.Target target(String schema){return cache.target(fixture.contexts.binding(fixture.binding).put("schema",schema));}
    JsonNode done(CatalogCache.Target target)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JsonNode status;
        do{status=cache.status(target);if(!status.path("scanInProgress").asBoolean())return status;Thread.sleep(10);}while(System.nanoTime()<until);
        throw new AssertionError(status);
    }
    @Test void scopesHaveIndependentQueuedRunningAndLastRuns()throws Exception{
        var a=target("PUBLIC");var b=target("OTHER");cache.request(a,10_000);assertTrue(first.await(5,TimeUnit.SECONDS));
        JsonNode running=cache.status(a);assertEquals("running",running.path("currentRun").path("state").asText());assertEquals("publishing",running.path("currentRun").path("phase").asText());assertTrue(running.path("currentRun").path("objects").asInt()>=2);
        assertFalse(cache.status(b).path("scanInProgress").asBoolean(),"Unrequested target must not inherit global activity");
        cache.request(b,10_000);JsonNode queued=cache.status(b);assertEquals("queued",queued.path("currentRun").path("state").asText());assertEquals(0,queued.path("currentRun").path("startedAt").asLong());
        cache.request(b,10_000);assertEquals(queued.path("currentRun").path("id"),cache.status(b).path("currentRun").path("id"),"Coalesce queued requests");
        fixture.clock.addAndGet(500);releaseFirst.countDown();assertTrue(second.await(5,TimeUnit.SECONDS));
        JsonNode finished=done(a);assertTrue(cache.scanning(),"Second target is still running");assertFalse(finished.path("scanInProgress").asBoolean());assertEquals("succeeded",finished.path("lastRun").path("state").asText());assertEquals(500,finished.path("lastRun").path("elapsedMillis").asLong());
        assertTrue(cache.status(b).path("currentRun").path("startedAt").asLong()>queued.path("currentRun").path("requestedAt").asLong());
        releaseSecond.countDown();done(b);cache.request(a,10_000);JsonNode refreshed=cache.status(a);assertEquals(finished.path("lastRun"),refreshed.path("lastRun"),"Previous run remains visible during refresh");done(a);
    }
    @Test void timeoutKeepsWorkerVisibleUntilCleanupAndDoesNotPublishOrAffectLaterRuns()throws Exception{
        var target=target("PUBLIC");cache.request(target,10_000);assertTrue(first.await(5,TimeUnit.SECONDS));
        var scope=cache.find(target);var original=scope.currentRun;cache.timeout(scope,original);
        JsonNode stopping=cache.status(target);assertTrue(stopping.path("scanInProgress").asBoolean());assertTrue(stopping.path("currentRun").path("timeoutRequested").asBoolean());assertEquals("cancelling",stopping.path("currentRun").path("phase").asText());
        releaseFirst.countDown();JsonNode failed=done(target);assertEquals("timed_out",failed.path("lastRun").path("state").asText());assertEquals(0,failed.path("generation").asInt());assertFalse(failed.has("currentRun"));
        cache.request(target,10_000);assertTrue(second.await(5,TimeUnit.SECONDS));cache.timeout(scope,original);
        assertFalse(cache.status(target).path("currentRun").path("timeoutRequested").asBoolean(),"An old deadline cannot cancel a later attempt");releaseSecond.countDown();assertEquals("succeeded",done(target).path("lastRun").path("state").asText());
    }
    @Test void scannerUsesCallerStatementBudgetAndClosesOnCancellation()throws Exception{
        var observed=new java.util.ArrayList<Integer>();var statements=new java.util.ArrayList<java.sql.PreparedStatement>();
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var seconds=new AtomicInteger(77);
        try(var real=fixture.connections.open(fixture.connection);var setup=real.createStatement()){
            setup.execute("CREATE VIEW SCAN_BUDGET_VIEW AS SELECT 1 AS VALUE_COLUMN");
            java.sql.Connection wrapped=(java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args)->{
                try{
                    Object value=method.invoke(real,args);
                    if(method.getName().equals("prepareStatement")){
                        var statement=(java.sql.PreparedStatement)value;statements.add(statement);
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{
                            if(m.getName().equals("setQueryTimeout")){observed.add((int)a[0]);seconds.set(3);}
                            try{return m.invoke(statement,a);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                        });
                    }
                    return value;
                }catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            var profile=fixture.profiles.get(fixture.connection);var scope=Profiles.JSON.createObjectNode().put("schema","PUBLIC");
            new CatalogScanner(wrapped,profile,scope,(key,value)->{},cancelled::get,CatalogScanner.Limits.DEFAULT,_->{},seconds::get).scan();
            assertEquals(77,observed.getFirst());assertTrue(observed.contains(3),observed.toString());
            for(var statement:statements)assertTrue(statement.isClosed());
            var scanner=new CatalogScanner(wrapped,profile,scope,(key,value)->{},cancelled::get,CatalogScanner.Limits.DEFAULT,statement->{if(statement!=null)cancelled.set(true);},seconds::get);
            assertTrue(assertThrows(CancellationException.class,scanner::scan).getMessage().contains("cancelled during"));
            for(var statement:statements)assertTrue(statement.isClosed(),"Cancellation must close the active statement");
        }
    }

}
