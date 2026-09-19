package io.doindev.codegraph.dba;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bson.RawBsonDocument;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMemoryTest {
    @TempDir Path root;
    @Test void oversizedRedisWireReplyClosesOnlyItsChannelAndReleasesTheChunk(){
        var channel=new EmbeddedChannel(new NativeWireBudget.Limit(16));
        try{
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[8])));
            io.netty.buffer.ByteBuf small=channel.readInbound();assertEquals(8,small.readableBytes());small.release();
            var tooLarge=Unpooled.wrappedBuffer(new byte[9]);
            assertFalse(channel.writeInbound(tooLarge));assertEquals(0,tooLarge.refCnt());assertFalse(channel.isActive());
        }finally{channel.finishAndReleaseAll();}
    }
    @Test void respLengthAndNestingGuardsRunBeforeDriverAllocations(){
        for(String reply:java.util.List.of("*1000000000\r\n","$1000000000\r\n","%32768\r\n",
                "*1\r\n".repeat(33),"+OK\r\n".repeat(65537),"*?\r\n","$-2\r\n")){
            var channel=new EmbeddedChannel(new NativeWireBudget.RespLimit());
            try{
                var bytes=Unpooled.copiedBuffer(reply,java.nio.charset.StandardCharsets.UTF_8);
                assertFalse(channel.writeInbound(bytes));assertFalse(channel.isActive());assertEquals(0,bytes.refCnt());
            }finally{channel.finishAndReleaseAll();}
        }
    }
    @Test void fragmentedResp2AndResp3RepliesRemainUnmodified(){
        String reply="%2\r\n+server\r\n$5\r\nredis\r\n+values\r\n*4\r\n:42\r\n$-1\r\n$4\r\nx\r\ny\r\n_\r\n";
        var channel=new EmbeddedChannel(new NativeWireBudget.RespLimit());
        try{
            for(byte value:reply.getBytes(java.nio.charset.StandardCharsets.UTF_8)){
                assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{value})));
                io.netty.buffer.ByteBuf next=channel.readInbound();assertEquals(value,next.readByte());next.release();
            }
            assertTrue(channel.isActive());
        }finally{channel.finishAndReleaseAll();}
    }
    @Test void largeMongoDocumentIsNotExpandedIntoJavaObjects()throws Exception{
        var raw=RawBsonDocument.parse("{\"payload\":\""+"x".repeat(300000)+"\"}");
        var results=new NativeResults("documents",100,1<<20);
        assertTrue(NativeReadExecutor.addDocument(results,raw));var out=results.finish();
        assertTrue(out.path("truncated").asBoolean());assertTrue(out.path("entries").get(0).path("documentOmitted").asBoolean());
        assertTrue(out.toString().length()<1000);assertFalse(out.toString().contains("xxxxxx"));
    }
    @Test void nativeTemporaryReservationSharesDbaAllowanceAndReleasesAfterCompletion()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,64L<<20,2,100,100,5),owner->true)){
            var input=Profiles.JSON.createObjectNode().put("name","Budget").put("templateId","redis-native").put("url","redis://127.0.0.1:1");
            String id=profiles.put(null,input).path("id").asText();
            var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
            var job=jobs.local("human",id,j->{started.countDown();finish.await(3,TimeUnit.SECONDS);return Profiles.JSON.createObjectNode();},()->{});
            try{
                assertTrue(started.await(2,TimeUnit.SECONDS));assertEquals(64L<<20,jobs.telemetry().path("reservedBytes").asLong());
                assertThrows(IllegalArgumentException.class,()->jobs.local("human",j->Profiles.JSON.createObjectNode(),()->{}));
            }finally{finish.countDown();}
            assertEquals("complete",ConnectionSetupTest.await(jobs,"human",job).path("state").asText());
            assertEquals(12L<<20,jobs.telemetry().path("reservedBytes").asLong());
            assertThrows(IllegalArgumentException.class,()->jdbc.open(id));
        }
    }
    @Test void unsavedNativeTestReservesBeforeConnectingAndReleasesAfterFailure()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,64L<<20,2,100,100,5),owner->true)){
            var held=jobs.local("human",j->Profiles.JSON.createObjectNode(),()->{});
            assertEquals("complete",ConnectionSetupTest.await(jobs,"human",held).path("state").asText());
            var setup=new ConnectionSetup(profiles,jobs);
            var draft=Profiles.JSON.createObjectNode().put("name","Unsaved").put("templateId","redis-native").put("url","redis://127.0.0.1:1");
            var result=ConnectionSetupTest.await(jobs,"human",setup.operation("human","draft-test",draft));
            assertEquals("failed",result.path("state").asText());
            assertTrue(result.path("error").asText().contains("require 64 MiB"),result.toString());
            assertFalse(result.path("progress").asText().contains("Connecting"));
            assertEquals(24L<<20,jobs.telemetry().path("reservedBytes").asLong());
            assertTrue(profiles.list().isEmpty());
        }
    }
    @Test void unsavedJobReservationUpgradeIsAccountedAndIdempotent()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,64L<<20,2,100,100,5),owner->true)){
            var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
            var job=jobs.local("human",j->{jobs.reserveNative(j);jobs.reserveNative(j);started.countDown();finish.await(3,TimeUnit.SECONDS);return Profiles.JSON.createObjectNode();},()->{});
            try{assertTrue(started.await(2,TimeUnit.SECONDS));assertEquals(64L<<20,jobs.telemetry().path("reservedBytes").asLong());}
            finally{finish.countDown();}
            assertEquals("complete",ConnectionSetupTest.await(jobs,"human",job).path("state").asText());
            assertEquals(12L<<20,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
}
