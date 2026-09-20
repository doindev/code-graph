package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JobStatusWaitTest {
    @TempDir Path directory;
    QueryJobs jobs;
    Profiles profiles;
    Connections connections;
    final AtomicBoolean alive=new AtomicBoolean(true);
    @BeforeEach void setup()throws Exception {
        profiles=new Profiles(directory,new DbaTest.MemoryVault());connections=new Connections(profiles);
        jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,5),_ -> alive.get());
    }
    @AfterEach void close()throws Exception{jobs.close();connections.close();profiles.close();}
    ObjectNode args(long revision,int wait){return Profiles.JSON.createObjectNode().put("afterRevision",revision).put("waitMillis",wait);}
    @Test void revisionsDescribeTheCapturedPayloadDuringConcurrentProgress()throws Exception{
        var job=jobs.new Job("agent:test","fixture");var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var writer=executor.submit(()->{start.await();for(int i=0;i<20000;i++){job.progress="step "+i;job.bytes=i;Thread.yield();}return null;});
            start.countDown();ObjectNode previous=null;long revision=-1;
            for(int i=0;i<10000;i++){
                var next=job.json();long current=next.remove("revision").asLong();assertTrue(current>=revision);
                if(current==revision)assertEquals(previous,next,"Unchanged revision must describe unchanged captured fields");
                revision=current;previous=next;
            }
            writer.get(3,TimeUnit.SECONDS);
        }
    }
    @Test void timeoutDoesNotReserializeResultsAndCompletionWakesWait()throws Exception {
        var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);
        String id=jobs.local("agent:test",job->{entered.countDown();gate.await();return Profiles.JSON.createObjectNode().put("ok",true);},()->{}).path("id").asText();
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        long revision=jobs.status("agent:test",id).path("revision").asLong();
        var timeout=jobs.status("agent:test",id,args(revision,30),()->{});
        assertTrue(timeout.path("timedOut").asBoolean());assertFalse(timeout.path("terminal").asBoolean());
        assertEquals(revision,timeout.path("revision").asLong());
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var waiting=executor.submit(()->jobs.status("agent:test",id,args(revision,3000),()->{}));
            Thread.sleep(30);gate.countDown();
            var done=waiting.get(1,TimeUnit.SECONDS);assertTrue(done.path("revisionChanged").asBoolean());
            // A running->complete transition may precede finished publication; wait again when necessary.
            if(!done.path("terminal").asBoolean())done=jobs.status("agent:test",id,args(done.path("revision").asLong(),3000),()->{});
            assertTrue(done.path("terminal").asBoolean());assertTrue(done.path("result").path("ok").asBoolean());
            assertFalse(jobs.status("agent:test",id,args(done.path("revision").asLong(),5000),()->{}).path("timedOut").asBoolean());
        }
    }
    @Test void ownerSessionAndArgumentsAreRecheckedDuringWait()throws Exception{
        var gate=new CountDownLatch(1);var entered=new CountDownLatch(1);
        String id=jobs.local("agent:test",job->{entered.countDown();gate.await();return Profiles.JSON.createObjectNode();},()->{}).path("id").asText();
        assertTrue(entered.await(1,TimeUnit.SECONDS));long rev=jobs.status("agent:test",id).path("revision").asLong();
        assertThrows(IllegalArgumentException.class,()->jobs.status("agent:other",id,args(rev,30),()->{}));
        assertThrows(IllegalArgumentException.class,()->jobs.status("agent:test",id,args(rev,5001),()->{}));
        assertThrows(IllegalArgumentException.class,()->jobs.status("agent:test",id,args(rev+50,30),()->{}));
        assertThrows(IllegalArgumentException.class,()->jobs.status("agent:test",id,Profiles.JSON.createObjectNode().put("waitMillis",1),()->{}));
        var checks=new AtomicInteger();
        assertThrows(SecurityException.class,()->jobs.status("agent:test",id,args(rev,5000),()->{
            if(checks.incrementAndGet()>2)throw new SecurityException("session expired");
        }));
        gate.countDown();
    }
    @Test void cancellationIsVisibleBeforeCancellationIsCompleted()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        String id=jobs.local("agent:test",job->{entered.countDown();
            while(release.getCount()!=0)try{release.await();}catch(InterruptedException ignored){}
            return Profiles.JSON.createObjectNode();},()->{}).path("id").asText();
        assertTrue(entered.await(1,TimeUnit.SECONDS));long rev=jobs.status("agent:test",id).path("revision").asLong();
        jobs.cancel(jobs.require("agent:test",id));var pending=jobs.status("agent:test",id,args(rev,100),()->{});
        assertTrue(pending.path("cancellationRequested").asBoolean());assertFalse(pending.path("terminal").asBoolean());
        assertNotEquals("cancelled",pending.path("state").asText());release.countDown();
        long until=System.nanoTime()+1_000_000_000L;while(jobs.require("agent:test",id).finished==0&&System.nanoTime()<until)Thread.sleep(5);
        assertEquals("cancelled",jobs.status("agent:test",id).path("state").asText());
    }
    @Test void statusWaitAdmissionIsBoundedWithoutBlockingCancellation()throws Exception{
        var entered=new CountDownLatch(1);var gate=new CountDownLatch(1);
        String id=jobs.local("agent:test",job->{entered.countDown();gate.await();return Profiles.JSON.createObjectNode();},()->{}).path("id").asText();
        assertTrue(entered.await(1,TimeUnit.SECONDS));long rev=jobs.status("agent:test",id).path("revision").asLong();
        var checked=new CountDownLatch(4);var seen=ConcurrentHashMap.<Thread>newKeySet();
        Runnable validate=()->{if(seen.add(Thread.currentThread()))checked.countDown();};
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new java.util.ArrayList<Future<ObjectNode>>();
            for(int i=0;i<4;i++)futures.add(executor.submit(()->jobs.status("agent:test",id,args(rev,3000),validate)));
            assertTrue(checked.await(1,TimeUnit.SECONDS));Thread.sleep(30);
            assertThrows(IllegalArgumentException.class,()->jobs.status("agent:test",id,args(rev,10),()->{}));
            jobs.cancel(jobs.require("agent:test",id));
            for(var future:futures)assertTrue(future.get(1,TimeUnit.SECONDS).path("revisionChanged").asBoolean());
        }finally{gate.countDown();}
    }
}
