package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLTimeoutException;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CompareJobTest {
    @TempDir Path root;
    @Test void comparisonAndGenerationOutliveQueryBudgetOnBoundedVirtualWorkers()throws Exception {
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,1),s->true)){
            String connection=profiles.put(null,new DbaTest().input()).path("id").asText();AtomicInteger cleaned=new AtomicInteger();
            for(String operation:new String[]{"compare","generate"}){
                JsonNode result=TableDesignerTest.waitRetained(jobs,"human",jobs.comparison("human",Set.of(connection),operation,job->{
                    assertTrue(Thread.currentThread().isVirtual());job.comparisonProgress("Inspecting definitions","source","SRC.ITEM",1,2);
                    Thread.sleep(1200);assertEquals(1,job.remainingSeconds());return Profiles.JSON.createObjectNode().put("ready",true);
                },cleaned::incrementAndGet));
                assertEquals("complete",result.path("state").asText(),result.toPrettyString());assertEquals(900,result.path("timeoutSeconds").asInt());
                assertEquals("SRC.ITEM",result.path("comparisonProgress").path("object").asText());jobs.remove("human",result.path("id").asText());
            }
            assertEquals(2,cleaned.get());
        }
    }
    @Test void deadlineAndUserCancellationAreDistinctAndCleanupCompletes()throws Exception {
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,5),s->true)){
            String connection=profiles.put(null,new DbaTest().input()).path("id").asText();AtomicInteger cleaned=new AtomicInteger();
            for(String operation:new String[]{"compare","generate"}){
                JsonNode deadline=TableDesignerTest.waitRetained(jobs,"human",jobs.comparison("human",Set.of(connection),operation,job->{
                    job.comparisonProgress("Reading metadata","destination","DST.ITEM",0,1);job.beginActiveBudget(1);
                    new CountDownLatch(1).await();return Profiles.JSON.createObjectNode();
                },cleaned::incrementAndGet));
                assertEquals("cancelled",deadline.path("state").asText());assertEquals("deadline_exceeded",deadline.path("errorCode").asText());assertTrue(deadline.path("error").asText().contains("DST.ITEM"));
                jobs.remove("human",deadline.path("id").asText());
                CountDownLatch entered=new CountDownLatch(1);
                var submitted=jobs.comparison("human",Set.of(connection),operation,job->{entered.countDown();new CountDownLatch(1).await();return Profiles.JSON.createObjectNode();},cleaned::incrementAndGet);
                assertTrue(entered.await(5,TimeUnit.SECONDS));jobs.cancel(jobs.require("human",submitted.path("id").asText()));
                JsonNode cancelled=TableDesignerTest.waitRetained(jobs,"human",submitted);assertEquals("user_cancelled",cancelled.path("errorCode").asText());jobs.remove("human",submitted.path("id").asText());
            }
            assertEquals(4,cleaned.get());
        }
    }
    @Test void statementFailureIsNotReportedAsConnectionSetupFailure()throws Exception {
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,1,100,100,1),s->true)){
            String connection=profiles.put(null,new DbaTest().input()).path("id").asText();
            JsonNode result=TableDesignerTest.waitRetained(jobs,"human",jobs.comparison("human",Set.of(connection),"generate",job->{throw new SQLTimeoutException("private driver detail");},()->{}));
            assertEquals("failed",result.path("state").asText());assertEquals("statement_timeout",result.path("errorCode").asText());assertFalse(result.path("error").asText().contains("private driver detail"));
        }
    }
    @Test void independentTargetsOverlapButSameConnectionStaysSerialAndCancellationJoinsBoth()throws Exception {
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,5),s->true)){
            String a=profiles.put(null,new DbaTest().input().put("name","Source")).path("id").asText(),b=profiles.put(null,new DbaTest().input().put("name","Destination")).path("id").asText();
            CountDownLatch both=new CountDownLatch(2);
            var success=jobs.comparison("human",Set.of(a,b),"compare",parent->{
                var pair=jobs.comparisonReads(parent,a,b,child->{assertTrue(Thread.currentThread().isVirtual());both.countDown();assertTrue(both.await(5,TimeUnit.SECONDS));return 1;},child->{both.countDown();assertTrue(both.await(5,TimeUnit.SECONDS));return 2;});
                assertEquals(1,pair.source());assertEquals(2,pair.destination());return Profiles.JSON.createObjectNode();
            },()->{});
            assertEquals("complete",TableDesignerTest.waitRetained(jobs,"human",success).path("state").asText());jobs.remove("human",success.path("id").asText());
            AtomicInteger order=new AtomicInteger();
            var serial=jobs.comparison("human",Set.of(a),"generate",parent->{jobs.comparisonReads(parent,a,a,child->{assertSame(parent,child);assertEquals(0,order.getAndIncrement());return 1;},child->{assertEquals(1,order.getAndIncrement());return 2;});return Profiles.JSON.createObjectNode();},()->{});
            assertEquals("complete",TableDesignerTest.waitRetained(jobs,"human",serial).path("state").asText());jobs.remove("human",serial.path("id").asText());
            CountDownLatch started=new CountDownLatch(2);AtomicInteger released=new AtomicInteger();
            QueryJobs.ComparisonRead<Integer> blocked=child->{started.countDown();try{new CountDownLatch(1).await();return 1;}finally{released.incrementAndGet();}};
            var cancelled=jobs.comparison("human",Set.of(a,b),"generate",parent->{jobs.comparisonReads(parent,a,b,blocked,blocked);return Profiles.JSON.createObjectNode();},()->{});
            assertTrue(started.await(5,TimeUnit.SECONDS));jobs.cancel(jobs.require("human",cancelled.path("id").asText()));
            JsonNode terminal=TableDesignerTest.waitRetained(jobs,"human",cancelled);assertEquals("user_cancelled",terminal.path("errorCode").asText());assertEquals(2,released.get());
            jobs.remove("human",cancelled.path("id").asText());CountDownLatch siblingStarted=new CountDownLatch(1);AtomicInteger siblingReleased=new AtomicInteger();
            var failed=jobs.comparison("human",Set.of(a,b),"compare",parent->{jobs.comparisonReads(parent,a,b,
                child->{assertTrue(siblingStarted.await(5,TimeUnit.SECONDS));throw new SQLTimeoutException("fixture statement timeout");},
                child->{siblingStarted.countDown();try{new CountDownLatch(1).await();return 2;}finally{siblingReleased.incrementAndGet();}});return Profiles.JSON.createObjectNode();},()->{});
            JsonNode failure=TableDesignerTest.waitRetained(jobs,"human",failed);assertEquals("statement_timeout",failure.path("errorCode").asText());assertEquals(1,siblingReleased.get());

        }
    }
    @Test void jdbcCancellationPrecedesInterruptAndUnresponsiveDriverHasBoundedFallback()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,10),s->true)){
            String connection=profiles.put(null,new DbaTest().input()).path("id").asText();
            for(boolean cooperative:new boolean[]{true,false}){
                var entered=new CountDownLatch(1);var returned=new CountDownLatch(1);var driverStarted=new CountDownLatch(1);var releaseDriver=new CountDownLatch(1);var interruptedFirst=new java.util.concurrent.atomic.AtomicBoolean();var cleaned=new AtomicInteger();
                var submitted=jobs.local("human",connection,job->{Thread worker=Thread.currentThread();
                    job.statement=(java.sql.Statement)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{java.sql.Statement.class},(proxy,method,args)->{
                        if(method.getName().equals("cancel")){interruptedFirst.set(worker.isInterrupted());driverStarted.countDown();if(cooperative)returned.countDown();else releaseDriver.await();return null;}throw new UnsupportedOperationException(method.getName());
                    });entered.countDown();try{returned.await();return Profiles.JSON.createObjectNode();}finally{cleaned.incrementAndGet();}
                },()->{});
                try{assertTrue(entered.await(5,TimeUnit.SECONDS));jobs.cancel(jobs.require("human",submitted.path("id").asText()));assertTrue(driverStarted.await(5,TimeUnit.SECONDS));
                    var result=TableDesignerTest.waitRetained(jobs,"human",submitted);assertEquals("cancelled",result.path("state").asText());assertFalse(interruptedFirst.get());assertEquals(1,cleaned.get());jobs.remove("human",submitted.path("id").asText());
                }finally{releaseDriver.countDown();}
            }
        }
    }
    @Test void comparisonTimeoutConfigurationHasIndependentBounds(){
        assertEquals(900,DbaConfig.parse(new String[]{"--dba"}).orElseThrow().compareTimeoutSeconds());
        var configured=DbaConfig.parse(new String[]{"--dba","--dba-timeout","2","--dba-compare-timeout","60"}).orElseThrow();assertEquals(2,configured.timeoutSeconds());assertEquals(60,configured.compareTimeoutSeconds());
        assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-compare-timeout","29"}));
        assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-compare-timeout","3601"}));
    }
}
