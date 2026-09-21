package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeRedisListTest {
    @TempDir Path root;
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static ObjectNode edit(String key,String expected,String value){
        var input=Profiles.JSON.createObjectNode();
        input.putArray("transaction").addArray().add("LSET").add(key).add("1").add(value);
        input.putArray("watch").addObject().put("key",key).put("index",1).put("length",3).put("expected",expected);
        return input;
    }
    private static NativeTarget target(){return new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","","standalone");}
    private static ObjectNode trim(String key,int index,int length,JsonNode expected,boolean head){
        var input=Profiles.JSON.createObjectNode();input.putArray("transaction").addArray().add("LTRIM").add(key).add(head?"1":"0").add(head?"-1":"-2");
        input.putArray("watch").addObject().put("key",key).put("index",index).put("length",length).set("expected",expected);return input;
    }
    @Test void endDeletionRequiresASingleExactBoundedEndGuard()throws Exception{
        var target=target();var value=Profiles.JSON.getNodeFactory().textNode("old");
        for(boolean head:List.of(true,false)){
            var valid=trim("list",head?0:2,3,value,head);NativeMutations.validate(target,valid);
            assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target,valid).effect());
            assertTrue(NativeCommand.classify(target,valid).reason().contains("last item"));
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,valid.path("transaction").get(0)),"Raw trim is not admitted");
            var pipeline=Profiles.JSON.createObjectNode();pipeline.set("pipeline",valid.path("transaction").deepCopy());
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,pipeline));
            var noGuard=valid.deepCopy();noGuard.remove("watch");assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,noGuard));
            var mixed=valid.deepCopy();mixed.withArray("transaction").addArray().add("LPUSH").add("list").add("changed");assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,mixed));
            for(String change:List.of("interior","key","length","expected","kind")){
                var bad=valid.deepCopy();var guard=bad.withArray("watch").get(0).withObject("");
                switch(change){case "interior"->guard.put("index",1);case "key"->guard.put("key","another");case "length"->guard.put("length",10001);case "expected"->guard.putNull("expected");case "kind"->guard.remove(List.of("index","length"));}
                assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,bad),change);
            }
            for(String start:List.of("-1","00","10","10000","0.0")){
                var bad=valid.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)bad.path("transaction").get(0)).set(2,Profiles.JSON.getNodeFactory().textNode(start));assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,bad));
            }
            NativeMutations.validate(target,trim("list",0,1,value,head));
        }
        var binary=trim("list",0,1,value,true);((com.fasterxml.jackson.databind.node.ArrayNode)binary.path("transaction").get(0)).set(1,Profiles.JSON.createObjectNode().put("base64","bGlzdA=="));NativeMutations.validate(target,binary);
        for(String name:List.of("LPUSH","RPUSH")){var addition=edit("list","old","new");addition.putArray("transaction").addArray().add(name).add("list").add("new");assertTrue(NativeCommand.classify(target,addition).reason().contains("not stable identities"));}
        ((com.fasterxml.jackson.databind.node.ArrayNode)binary.path("transaction").get(0)).set(2,Profiles.JSON.getNodeFactory().numberNode(1));assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,binary));
    }
    @Test void typedPositionsAndCompleteExpectationsAreRequired()throws Exception{
        var target=target();var valid=edit("list","old","new");NativeMutations.validate(target,valid);
        assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target,valid).effect());
        assertFalse(NativeCommand.classify(target,valid).reusableRead());
        assertTrue(NativeCommand.classify(target,valid).reason().contains("not stable identities"));
        for(String watch:List.of(
                "{\"key\":\"list\",\"index\":1,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"length\":3,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"index\":1,\"length\":3,\"expected\":null}",
                "{\"key\":\"list\",\"field\":\"a\",\"index\":1,\"length\":3,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"index\":-1,\"length\":3,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"index\":3,\"length\":3,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"index\":1,\"length\":10001,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"index\":1.0,\"length\":3,\"expected\":\"old\"}",
                "{\"key\":\"list\",\"index\":\"1\",\"length\":3,\"expected\":\"old\"}")){
            var invalid=valid.deepCopy();invalid.putArray("watch").add(Profiles.JSON.readTree(watch));
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid),watch);
        }
        for(String index:List.of("-1","10000","1.0"," 1","1e2")){
            var invalid=valid.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)invalid.path("transaction").get(0)).set(2,Profiles.JSON.getNodeFactory().textNode(index));
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid));
        }
        var oversized=edit("list","x".repeat(65537),"next");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,oversized));
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,edit("list","old","x".repeat(65537))));
        var pipeline=Profiles.JSON.createObjectNode();pipeline.putArray("pipeline").addArray().add("LINDEX").add("list").add("9999");
        assertEquals(NativeCommand.Effect.READ,NativeCommand.classify(target,pipeline).effect());
        ((com.fasterxml.jackson.databind.node.ArrayNode)pipeline.path("pipeline").get(0)).set(2,Profiles.JSON.getNodeFactory().numberNode(1));
        assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,pipeline));
        var cluster=new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","","cluster");
        var crossSlot=edit("{one}:list","old","new");crossSlot.withArray("watch").get(0).withObject("").put("key","{two}:list");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(cluster,crossSlot));
    }
    @Test void browserReviewRetainsExactPositionAndRejectsRevisionOwnershipAndReadOnlyChanges()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","List").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","List").put("database","0");
            input.set("command",edit("key","old","new"));var review=ops.prepareBrowser("owner",input);
            assertEquals(input.path("command"),review.path("after").path("nativeCommand"));
            assertFalse(review.path("eligiblePersistentRead").asBoolean());
            assertTrue(review.path("transactionNotice").asText().contains("not stable identities"));
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            input.put("expectedTargetRevision","stale");assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));input.remove("expectedTargetRevision");
            input.set("command",edit("key","changed","new"));assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));
            ops.discardBrowser("owner",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            input.set("command",trim("key",0,1,Profiles.JSON.getNodeFactory().textNode("old"),true));var deletion=ops.prepareBrowser("owner",input);
            assertTrue(deletion.path("destructive").asBoolean());assertFalse(deletion.path("eligiblePersistentRead").asBoolean());
            assertEquals(input.path("command"),deletion.path("after").path("nativeCommand"));
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",deletion.path("id").asText()));
            var tampered=input.deepCopy();tampered.withObject("/command").withArray("watch").get(0).withObject("").put("expected","changed");assertThrows(IllegalArgumentException.class,()->ops.validate(deletion,tampered));
            ops.discardBrowser("owner",deletion.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));
            assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));
        }
    }
    @Test @Timeout(120) void ownedListFixturePreservesNeighboursTtlAndRejectsConflicts()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","Lists").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){
                options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");
                if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}
            }
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Lists").put("database",options.path("database").asText()));
            String name="{list-"+UUID.randomUUID()+"}:items";byte[] key=bytes(name);
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,10)){
                var redis=observer.sync();
                try{
                    redis.rpush(key,bytes("first"),new byte[]{0,(byte)255},bytes("last"));redis.pexpire(key,120000);
                    var read=Profiles.JSON.createObjectNode();var commands=read.putArray("pipeline");
                    commands.addArray().add("TYPE").add(name);commands.addArray().add("LLEN").add(name);commands.addArray().add("LINDEX").add(name).add("1");commands.addArray().add("PTTL").add(name);
                    var loaded=NativeRedisPipelines.execute(lease,target,read,jobs.new Job("human",target.connectionId()),()->{});
                    assertEquals("list",loaded.path("entries").get(0).path("value").asText());assertEquals(3,loaded.path("entries").get(1).path("value").asInt());
                    assertEquals("AP8=",loaded.path("entries").get(2).path("value").path("base64").asText());assertTrue(loaded.path("entries").get(3).path("value").asLong()>0);
                    var edit=edit(name,"","new");edit.withArray("watch").get(0).withObject("").putObject("expected").put("base64","AP8=");
                    long ttl=redis.pttl(key);
                    assertEquals("acknowledged",NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}).path("outcome").asText());
                    assertEquals(3,redis.llen(key));assertArrayEquals(bytes("first"),redis.lindex(key,0));assertArrayEquals(bytes("new"),redis.lindex(key,1));assertArrayEquals(bytes("last"),redis.lindex(key,2));assertTrue(redis.pttl(key)>0&&redis.pttl(key)<=ttl);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));
                    edit.withArray("watch").get(0).withObject("").put("expected","new");redis.rpush(key,bytes("extra"));
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));redis.rpop(key);
                    var checks=new AtomicInteger();var race=jobs.new Job("human",target.connectionId());
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,race,()->{if(checks.incrementAndGet()==2)redis.lset(key,0,bytes("concurrent"));}));
                    assertEquals("watched_key_changed",race.result.path("reason").asText());assertArrayEquals(bytes("new"),redis.lindex(key,1));
                    var cancelled=jobs.new Job("human",target.connectionId());checks.set(0);
                    assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,edit,cancelled,()->{if(checks.incrementAndGet()==2)cancelled.cancelled=true;}));
                    checks.set(0);assertThrows(SecurityException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));
                    // These are current-position expectations, not a historical version of the whole list.
                    assertEquals("acknowledged",NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}).path("outcome").asText());
                    redis.lset(key,1,new byte[0]);edit.withArray("watch").get(0).withObject("").put("expected","");
                    ((com.fasterxml.jackson.databind.node.ArrayNode)edit.path("transaction").get(0)).set(3,Profiles.JSON.createObjectNode().put("base64","AP8="));
                    NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{});assertArrayEquals(new byte[]{0,(byte)255},redis.lindex(key,1));
                    redis.lset(key,1,bytes("x".repeat(8193)));
                    var truncated=NativeRedisPipelines.execute(lease,target,read,jobs.new Job("human",target.connectionId()),()->{});
                    assertTrue(truncated.path("entries").get(2).path("value").path("truncated").asBoolean());
                    edit.withArray("watch").get(0).withObject("").put("expected","x".repeat(8192));
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));
                    redis.del(key);assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));assertEquals(0,redis.exists(key));
                    redis.set(key,bytes("wrong type"));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));assertArrayEquals(bytes("wrong type"),redis.get(key));
                    redis.del(key);redis.rpush(key,bytes("first"),bytes("new"),bytes("last"));
                    var pipeline=Profiles.JSON.createObjectNode();pipeline.putArray("pipeline").addArray().add("LSET").add(name).add("1").add("pipeline");
                    assertEquals("OK",NativeRedisPipelines.execute(lease,target,pipeline,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asText());
                    assertArrayEquals(bytes("pipeline"),redis.lindex(key,1));
                   redis.pexpire(key,1);while(redis.exists(key)>0)Thread.sleep(2);
                   assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));
                    // One guarded prepend/append; never load or rewrite the complete list.
                    redis.rpush(key,bytes("same"),bytes("middle"),bytes("same"));redis.pexpire(key,120000);
                    for(String operation:List.of("LPUSH","RPUSH")){
                        int length=redis.llen(key).intValue();var addition=Profiles.JSON.createObjectNode();
                        addition.putArray("transaction").addArray().add(operation).add(name).add(Profiles.JSON.createObjectNode().put("base64","AP8="));
                        addition.putArray("watch").addObject().put("key",name).put("index",0).put("length",length).putObject("expected").put("base64",Base64.getEncoder().encodeToString(redis.lindex(key,0)));
                        ttl=redis.pttl(key);assertEquals(length+1,NativeMutations.execute(lease,target,addition,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asInt());
                        assertArrayEquals(new byte[]{0,(byte)255},redis.lindex(key,operation.equals("LPUSH")?0:-1));assertTrue(redis.pttl(key)>0&&redis.pttl(key)<=ttl);
                        assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,addition,jobs.new Job("human",target.connectionId()),()->{}),"Old length must not permit a repeated addition");
                    }
                    for(boolean head:List.of(true,false)){
                        int length=redis.llen(key).intValue();var removal=trim(name,head?0:length-1,length,Profiles.JSON.createObjectNode().put("base64","AP8="),head);
                        checks.set(0);var cancelledEnd=jobs.new Job("human",target.connectionId());
                        assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,removal,cancelledEnd,()->{if(checks.incrementAndGet()==2)cancelledEnd.cancelled=true;}));assertEquals(length,redis.llen(key));
                        checks.set(0);assertThrows(SecurityException.class,()->NativeMutations.execute(lease,target,removal,jobs.new Job("human",target.connectionId()),()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));assertEquals(length,redis.llen(key));
                        checks.set(0);var racedEnd=jobs.new Job("human",target.connectionId());
                        assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,removal,racedEnd,()->{if(checks.incrementAndGet()==2)redis.pexpire(key,110000);}));assertEquals("watched_key_changed",racedEnd.result.path("reason").asText());assertEquals(length,redis.llen(key));
                        ttl=redis.pttl(key);assertEquals("OK",NativeMutations.execute(lease,target,removal,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asText());
                        assertEquals(length-1,redis.llen(key));assertTrue(redis.pttl(key)>0&&redis.pttl(key)<=ttl);
                        assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,removal,jobs.new Job("human",target.connectionId()),()->{}));
                    }
                    assertEquals(3,redis.llen(key));assertArrayEquals(bytes("same"),redis.lindex(key,0));assertArrayEquals(bytes("middle"),redis.lindex(key,1));assertArrayEquals(bytes("same"),redis.lindex(key,2));
                    var first=trim(name,0,3,Profiles.JSON.getNodeFactory().textNode("same"),true);
                    redis.lset(key,0,bytes("changed"));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,first,jobs.new Job("human",target.connectionId()),()->{}));redis.lset(key,0,bytes("same"));
                    NativeMutations.execute(lease,target,first,jobs.new Job("human",target.connectionId()),()->{});assertArrayEquals(bytes("same"),redis.lindex(key,-1));
                    NativeMutations.execute(lease,target,trim(name,1,2,Profiles.JSON.getNodeFactory().textNode("same"),false),jobs.new Job("human",target.connectionId()),()->{});assertArrayEquals(bytes("middle"),redis.lindex(key,0));
                    var last=trim(name,0,1,Profiles.JSON.getNodeFactory().textNode("middle"),false);
                    NativeMutations.execute(lease,target,last,jobs.new Job("human",target.connectionId()),()->{});assertEquals(0,redis.exists(key));assertEquals(-2,redis.pttl(key));
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,last,jobs.new Job("human",target.connectionId()),()->{}));
                    redis.rpush(key,new byte[0]);var empty=trim(name,0,1,Profiles.JSON.getNodeFactory().textNode(""),true);NativeMutations.execute(lease,target,empty,jobs.new Job("human",target.connectionId()),()->{});assertEquals(0,redis.exists(key));
                    redis.rpush(key,new byte[0]);redis.pexpire(key,1);while(redis.exists(key)>0)Thread.sleep(2);assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,empty,jobs.new Job("human",target.connectionId()),()->{}));
                    redis.set(key,bytes("wrong type"));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,empty,jobs.new Job("human",target.connectionId()),()->{}));assertArrayEquals(bytes("wrong type"),redis.get(key));
                }finally{redis.del(key);}
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
