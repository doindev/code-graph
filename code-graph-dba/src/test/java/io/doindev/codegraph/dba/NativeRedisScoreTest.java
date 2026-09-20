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

class NativeRedisScoreTest {
    @TempDir Path root;
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static JsonNode text(String value){return Profiles.JSON.getNodeFactory().textNode(value);}
    private static ObjectNode edit(String key,JsonNode member,String expected,String score){
        var input=Profiles.JSON.createObjectNode();
        input.putArray("transaction").addArray().add("ZADD").add(key).add(score).add(member);
        input.putArray("watch").addObject().put("key",key).put("expected",expected).set("scoreMember",member);
        return input;
    }
    @Test void scoresAreTypedFiniteBoundedAndSeparateFromOtherExpectations()throws Exception{
        var target=new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","","standalone");
        var update=edit("z",text(""),"1.25","2.5");
        NativeMutations.validate(target,update);
        assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target,update).effect());
        assertFalse(NativeCommand.classify(target,update).reusableRead());
        assertTrue(NativeCommand.classify(target,update).reason().contains("GEO"));
        for(String score:List.of("","NaN","Infinity","-Infinity","1e309","1e-999"," 1","1\n","1\r","\t1","1d","0x1p0","1".repeat(65))){
            var invalid=update.deepCopy();invalid.withArray("watch").get(0).withObject("").put("expected",score);
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid),score);
        }
        for(JsonNode value:List.of(Profiles.JSON.nullNode(),Profiles.JSON.getNodeFactory().numberNode(1.25),Profiles.JSON.getNodeFactory().booleanNode(true))){
            var invalid=update.deepCopy();invalid.withArray("watch").get(0).withObject("").set("expected",value);
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid));
        }
        for(String field:List.of("field","index","length","member")){
            var invalid=update.deepCopy();invalid.withArray("watch").get(0).withObject("").put(field,"x");
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid));
        }
        for(JsonNode member:List.of(text("☃".repeat(3000)),text("x".repeat(8193)),Profiles.JSON.createObjectNode().put("base64","!"))){
            var invalid=update.deepCopy();invalid.withArray("watch").get(0).withObject("").set("scoreMember",member);
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,invalid));
        }
        assertEquals(Double.MAX_VALUE,NativeRedisTransactions.score(text("1.7976931348623157e308")));
        assertEquals(Double.MIN_VALUE,NativeRedisTransactions.score(text("4.9e-324")));
        assertEquals(0d,NativeRedisTransactions.score(text("-0")),0d);
        assertEquals(.001,NativeRedisTransactions.score(text("+1e-3")));
        var duplicate=update.deepCopy();duplicate.withArray("watch").add(duplicate.path("watch").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,duplicate));
        var cluster=new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","","cluster");
        var cross=edit("{a}:z",text("x"),"1","2");cross.withArray("watch").get(0).withObject("").put("key","{b}:z");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(cluster,cross));
    }
    @Test void browserReviewIsExactOwnedRevisionBoundAndReadOnlyProtected()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Scores").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Scores").put("database","0");input.set("command",edit("z",text("x"),"1.25","2.5"));
            var review=ops.prepareBrowser("owner",input);
            assertEquals(input.path("command"),review.path("after").path("nativeCommand"));assertTrue(review.path("mutation").asBoolean());assertFalse(review.path("eligiblePersistentRead").asBoolean());
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            input.put("expectedTargetRevision","stale");assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));input.remove("expectedTargetRevision");
            input.set("command",edit("z",text("x"),"1.25","3"));assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));
            ops.discardBrowser("owner",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));
            assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));
        }
    }
    @Test @Timeout(120) void ownedFixtureGuardsScoresMembershipExpiryAndTtl()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","Scores").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){
                options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");
                if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}
            }
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Scores").put("database",options.path("database").asText()));
            String name="{score-"+UUID.randomUUID()+"}:members";byte[] key=bytes(name),binary=new byte[]{0,(byte)255};var member=Profiles.JSON.createObjectNode().put("base64","AP8=");
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,10)){
                var redis=observer.sync();
                try{
                    var update=edit(name,member,"1.25","2.5");
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));assertEquals(0,redis.exists(key));
                    redis.zadd(key,4,bytes("keep"));
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));assertNull(redis.zscore(key,binary));
                    redis.zadd(key,1.25,binary);redis.pexpire(key,120000);
                    var read=Profiles.JSON.createObjectNode();var batch=read.putArray("pipeline");
                    batch.addArray().add("TYPE").add(name);batch.addArray().add("ZSCORE").add(name).add(member);batch.addArray().add("ZCARD").add(name);batch.addArray().add("PTTL").add(name);
                    var result=NativeRedisPipelines.execute(lease,target,read,jobs.new Job("human",target.connectionId()),()->{});
                    assertEquals("zset",result.path("entries").get(0).path("value").asText());assertEquals(1.25,result.path("entries").get(1).path("value").asDouble());assertEquals(2,result.path("entries").get(2).path("value").asInt());
                    long ttl=redis.pttl(key);
                    assertEquals(0,NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asInt(),"ZADD reports additions, not changed scores");
                    assertEquals(2.5,redis.zscore(key,binary));assertEquals(4d,redis.zscore(key,bytes("keep")));assertTrue(redis.pttl(key)>0&&redis.pttl(key)<=ttl);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));
                    var next=edit(name,member,"2.5","3.75");var checks=new AtomicInteger();var raced=jobs.new Job("human",target.connectionId());
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,next,raced,()->{if(checks.incrementAndGet()==2)redis.zadd(key,5,bytes("keep"));}));
                    assertEquals("watched_key_changed",raced.result.path("reason").asText());assertEquals(2.5,redis.zscore(key,binary));
                    checks.set(0);var cancelled=jobs.new Job("human",target.connectionId());
                    assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,next,cancelled,()->{if(checks.incrementAndGet()==2)cancelled.cancelled=true;}));
                    checks.set(0);assertThrows(SecurityException.class,()->NativeMutations.execute(lease,target,next,jobs.new Job("human",target.connectionId()),()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));assertEquals(2.5,redis.zscore(key,binary));
                    for(String value:List.of("-12.75","1e100","1e-100")){
                        NativeMutations.execute(lease,target,edit(name,member,redis.zscore(key,binary).toString(),value),jobs.new Job("human",target.connectionId()),()->{});assertEquals(Double.parseDouble(value),redis.zscore(key,binary));
                    }
                    redis.zadd(key,0,new byte[0]);NativeMutations.execute(lease,target,edit(name,text(""),"-0","0.5"),jobs.new Job("human",target.connectionId()),()->{});assertEquals(.5,redis.zscore(key,new byte[0]));
                    redis.zrem(key,binary);assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));assertNull(redis.zscore(key,binary));
                    redis.zadd(key,Double.POSITIVE_INFINITY,binary);assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));
                    redis.del(key);redis.set(key,bytes("wrong type"));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));assertArrayEquals(bytes("wrong type"),redis.get(key));redis.del(key);
                    redis.zadd(key,1.25,binary);redis.pexpire(key,1);while(redis.exists(key)>0)Thread.sleep(2);
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,update,jobs.new Job("human",target.connectionId()),()->{}));assertEquals(0,redis.exists(key));
                }finally{redis.del(key);}
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
