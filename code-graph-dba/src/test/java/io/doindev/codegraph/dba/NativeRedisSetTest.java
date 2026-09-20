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

class NativeRedisSetTest {
    @TempDir Path root;
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static ObjectNode edit(String key,JsonNode member,boolean present){
        var input=Profiles.JSON.createObjectNode();
        input.putArray("transaction").addArray().add(present?"SREM":"SADD").add(key).add(member);
        input.putArray("watch").addObject().put("key",key).put("expected",present).set("member",member);
        return input;
    }
    private static JsonNode member(String value){return Profiles.JSON.getNodeFactory().textNode(value);}
    private static NativeTarget target(){return new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","","standalone");}
    @Test void membershipIsBooleanBoundedAndCannotBeCombinedWithOtherExpectations()throws Exception{
        var target=target();var insert=edit("set",member(""),false);var delete=edit("set",member(""),true);
        NativeMutations.validate(target,insert);NativeMutations.validate(target,delete);
        assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target,insert).effect());
        assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target,delete).effect());
        assertTrue(NativeCommand.classify(target,delete).reason().contains("last member"));
        assertFalse(NativeCommand.classify(target,insert).reusableRead());
        for(String watch:List.of(
                "{\"key\":\"set\",\"member\":\"x\",\"expected\":null}",
                "{\"key\":\"set\",\"member\":\"x\",\"expected\":\"false\"}",
                "{\"key\":\"set\",\"member\":\"x\",\"expected\":0}",
                "{\"key\":\"set\",\"member\":\"x\",\"field\":\"y\",\"expected\":false}",
                "{\"key\":\"set\",\"member\":\"x\",\"index\":0,\"length\":1,\"expected\":false}",
                "{\"key\":\"set\",\"member\":null,\"expected\":false}",
                "{\"key\":\"set\",\"member\":{\"base64\":\"!\"},\"expected\":false}",
                "{\"key\":\"set\",\"expected\":false}")){
            var invalid=insert.deepCopy();invalid.putArray("watch").add(Profiles.JSON.readTree(watch));
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid),watch);
        }
        var large=insert.deepCopy();large.withArray("watch").get(0).withObject("").put("member","x".repeat(8193));
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,large));
        var unicode=insert.deepCopy();unicode.withArray("watch").get(0).withObject("").put("member","☃".repeat(3000));
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,unicode));
        var duplicate=insert.deepCopy();duplicate.withArray("watch").add(duplicate.path("watch").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,duplicate));
        var cluster=new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","","cluster");
        var cross=edit("{a}:set",member("x"),false);cross.withArray("watch").get(0).withObject("").put("key","{b}:set");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(cluster,cross));
    }
    @Test void reviewIsExactOwnedDestructiveAndReadOnlyProtected()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Set").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Set").put("database","0");input.set("command",edit("key",member("x"),true));
            var review=ops.prepareBrowser("owner",input);
            assertEquals(input.path("command"),review.path("after").path("nativeCommand"));assertTrue(review.path("destructive").asBoolean());
            assertFalse(review.path("eligiblePersistentRead").asBoolean());assertTrue(review.path("transactionNotice").asText().contains("last member"));
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            input.put("expectedTargetRevision","stale");assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));input.remove("expectedTargetRevision");
            input.set("command",edit("key",member("other"),true));assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));
            ops.discardBrowser("owner",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));
            assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));
        }
    }
    @Test @Timeout(120) void ownedFixtureChecksMembershipExpiryConflictsAndLastMemberDeletion()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","Sets").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){
                options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");
                if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}
            }
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Sets").put("database",options.path("database").asText()));
            String name="{set-"+UUID.randomUUID()+"}:members";byte[] key=bytes(name),binary=new byte[]{0,(byte)255};var binaryJson=Profiles.JSON.createObjectNode().put("base64","AP8=");
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,10)){
                var redis=observer.sync();
                try{
                    var insert=edit(name,binaryJson,false);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,insert,jobs.new Job("human",target.connectionId()),()->{}),"Absent key must not be recreated");
                    assertEquals(0,redis.exists(key));redis.sadd(key,bytes("keep"));redis.pexpire(key,120000);
                    var read=Profiles.JSON.createObjectNode();var batch=read.putArray("pipeline");
                    batch.addArray().add("TYPE").add(name);batch.addArray().add("SISMEMBER").add(name).add(binaryJson);batch.addArray().add("SCARD").add(name);batch.addArray().add("PTTL").add(name);
                    var loaded=NativeRedisPipelines.execute(lease,target,read,jobs.new Job("human",target.connectionId()),()->{});
                    assertEquals("set",loaded.path("entries").get(0).path("value").asText());assertEquals(Profiles.JSON.getNodeFactory().booleanNode(false),loaded.path("entries").get(1).path("value"));assertEquals(1,loaded.path("entries").get(2).path("value").asInt());
                    long ttl=redis.pttl(key);
                    assertEquals(1,NativeMutations.execute(lease,target,insert,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asInt());
                    assertTrue(redis.sismember(key,binary));assertTrue(redis.sismember(key,bytes("keep")));assertTrue(redis.pttl(key)>0&&redis.pttl(key)<=ttl);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,insert,jobs.new Job("human",target.connectionId()),()->{}));
                    var delete=edit(name,binaryJson,true);var checks=new AtomicInteger();var raced=jobs.new Job("human",target.connectionId());
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,delete,raced,()->{if(checks.incrementAndGet()==2)redis.sadd(key,bytes("concurrent"));}));
                    assertEquals("watched_key_changed",raced.result.path("reason").asText());assertTrue(redis.sismember(key,binary));
                    checks.set(0);var cancelled=jobs.new Job("human",target.connectionId());
                    assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,delete,cancelled,()->{if(checks.incrementAndGet()==2)cancelled.cancelled=true;}));
                    checks.set(0);assertThrows(SecurityException.class,()->NativeMutations.execute(lease,target,delete,jobs.new Job("human",target.connectionId()),()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));assertTrue(redis.sismember(key,binary));
                    NativeMutations.execute(lease,target,delete,jobs.new Job("human",target.connectionId()),()->{});assertFalse(redis.sismember(key,binary));assertTrue(redis.pttl(key)>0);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,delete,jobs.new Job("human",target.connectionId()),()->{}));
                    NativeMutations.execute(lease,target,edit(name,member(""),false),jobs.new Job("human",target.connectionId()),()->{});assertTrue(redis.sismember(key,new byte[0]));
                    redis.srem(key,bytes("keep"),bytes("concurrent"));
                    NativeMutations.execute(lease,target,edit(name,member(""),true),jobs.new Job("human",target.connectionId()),()->{});assertEquals(0,redis.exists(key));assertEquals(-2,redis.pttl(key));
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,insert,jobs.new Job("human",target.connectionId()),()->{}));
                    redis.set(key,bytes("wrong type"));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,insert,jobs.new Job("human",target.connectionId()),()->{}));assertArrayEquals(bytes("wrong type"),redis.get(key));redis.del(key);
                    redis.sadd(key,bytes("keep"));redis.pexpire(key,1);while(redis.exists(key)>0)Thread.sleep(2);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,insert,jobs.new Job("human",target.connectionId()),()->{}));
                }finally{redis.del(key);}
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
