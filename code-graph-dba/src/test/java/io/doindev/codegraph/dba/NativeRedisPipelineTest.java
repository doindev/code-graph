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

class NativeRedisPipelineTest {
    @TempDir Path root;
    private static NativeTarget target(String topology){return new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","",topology);}
    private static JsonNode json(String value)throws Exception{return Profiles.JSON.readTree(value);}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static ObjectNode batch(String key){var command=Profiles.JSON.createObjectNode();command.putArray("pipeline").addArray().add("SET").add(key).add("value");return command;}

    @Test void allCommandsAreValidatedBeforeDispatchAndEffectIsNotReadByFirstCommand()throws Exception{
        var target=target("standalone");
        for(String invalid:List.of("{\"pipeline\":[]}","{\"pipeline\":null}","{\"pipeline\":[[\"GET\",\"a\"]]}",
                "{\"pipeline\":[[\"HGETALL\",\"a\"]]}","{\"pipeline\":[[\"EVAL\",\"a\",\"0\"]]}",
                "{\"pipeline\":[[\"SET\",\"a\",\"b\"],[\"SET\",\"a\"]]}","{\"pipeline\":[[\"FLUSHDB\",\"a\"]]}",
                "{\"pipeline\":[[\"SET\",\"a\",\"b\"]],\"watch\":[]}","{\"pipeline\":[[\"SELECT\",\"1\"]]}",
                "{\"pipeline\":[{\"pipeline\":[[\"SET\",\"a\",\"b\"]]}]}","{\"pipeline\":[[\"XADD\",\"a\",\"*\",\"f\",\"v\"]]}",
                "{\"pipeline\":[[\"GETRANGE\",\"a\",\"-1\",\"0\"]]}","{\"pipeline\":[[\"GETRANGE\",\"a\",\"0\",\"8192\"]]}",
                "{\"pipeline\":[[\"GETBIT\",\"a\",\"4294967296\"]]}","{\"pipeline\":[[\"TYPE\",\"a\",\"ignored\"]]}"))
            assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,json(invalid)),invalid);
        var mixed=(ObjectNode)json("{\"pipeline\":[[\"TYPE\",\"a\"],[\"GETRANGE\",\"a\",\"0\",\"8191\"]]}");
        assertEquals(NativeCommand.Effect.READ,NativeCommand.classify(target,mixed).effect());
        mixed.withArray("pipeline").addArray().add("SET").add("a").add("b");assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target,mixed).effect());
        mixed.withArray("pipeline").addArray().add("DEL").add("a");assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target,mixed).effect());
        var max=batch("a");for(int i=1;i<32;i++)max.withArray("pipeline").addArray().add("TTL").add("a");NativeCommand.classify(target,max);
        max.withArray("pipeline").addArray().add("TTL").add("a");assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,max));
        var keys=batch("a");var del=keys.withArray("pipeline").addArray().add("DEL");for(int i=0;i<100;i++)del.add("k"+i);assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,keys));
    }
    @Test void scopeBinaryAndResourceAdmission()throws Exception{
        var projection=Profiles.JSON.createObjectNode();var entries=projection.putArray("entries");
        for(int i=0;i<32;i++)NativeRedisPipelines.appendValue(projection,entries.addObject().put("index",i).put("state","acknowledged"),new byte[8192],16384);
        assertEquals(32,entries.size());assertTrue(projection.path("truncated").asBoolean());assertTrue(projection.toString().getBytes(StandardCharsets.UTF_8).length<=16384);
        var precise=Profiles.JSON.createObjectNode();NativeRedisPipelines.appendValue(precise,precise.putObject("entry"),Long.MAX_VALUE,16384);assertEquals(Long.toString(Long.MAX_VALUE),precise.path("entry").path("value").path("$numberLong").asText());
        var command=batch("{a}:1");command.withArray("pipeline").addArray().add("RENAME").add("{a}:1").add("{a}:2");
        NativeCommand.classify(target("cluster"),command);command.withArray("pipeline").addArray().add("TTL").add("{b}:1");
        assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target("cluster"),command));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Redis").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Redis").put("database","0");input.set("command",batch("a"));
            var review=ops.prepareBrowser("owner",input);assertFalse(review.path("eligiblePersistentRead").asBoolean());assertTrue(review.path("transactionNotice").asText().contains("not transactions"));
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            input.withObject("command").withArray("pipeline").addArray().add("TTL").add("a");assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));
            ops.discardBrowser("owner",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            var limited=jobs.new Job("agent:fixture",profile.path("id").asText());limited.rowLimit=1;
            assertThrows(IllegalArgumentException.class,()->NativeRedisPipelines.execute(null,target("standalone"),input.path("command"),limited,()->fail("No dispatch")));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));assertThrows(IllegalArgumentException.class,()->ops.prepare(input));
            input.set("command",json("{\"pipeline\":[[\"TTL\",\"a\"]]}"));assertFalse(ops.prepare(input).path("mutation").asBoolean());
        }
    }
    @Test @Timeout(120) void disposablePipelinesOrderingPartialFailuresCancellationBinaryAndBounds()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","Pipelines").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}}
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Pipelines").put("database",options.path("database").asText()));
            String key="{pipeline-"+UUID.randomUUID()+"}:key";
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,15)){
                var redis=observer.sync();var command=batch(key);command.withArray("pipeline").addArray().add("GETRANGE").add(key).add("0").add("10");
                var done=jobs.new Job("human",target.connectionId());var reply=NativeRedisPipelines.execute(lease,target,command,done,()->{});assertEquals("acknowledged",done.outcome);assertEquals("value",reply.path("entries").get(1).path("value").path("text").asText());assertFalse(reply.path("atomic").asBoolean());
                var checks=new AtomicInteger();var early=jobs.new Job("human",target.connectionId());var other=batch(key);((com.fasterxml.jackson.databind.node.ArrayNode)other.path("pipeline").get(0)).set(2,Profiles.JSON.getNodeFactory().textNode("unexpected"));
                assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisPipelines.execute(lease,target,other,early,()->{if(checks.incrementAndGet()==2)early.cancelled=true;}));assertEquals("not_started",early.outcome);assertEquals("not_sent",early.result.path("entries").get(0).path("state").asText());assertArrayEquals(bytes("value"),redis.get(bytes(key)));
                checks.set(0);var revoked=jobs.new Job("human",target.connectionId());assertThrows(SecurityException.class,()->NativeRedisPipelines.execute(lease,target,other,revoked,()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));assertArrayEquals(bytes("value"),redis.get(bytes(key)));
                var partial=batch(key);partial.withArray("pipeline").addArray().add("LPUSH").add(key).add("wrong-type");partial.withArray("pipeline").addArray().add("SET").add(key).add("last");
                var failed=jobs.new Job("human",target.connectionId());assertThrows(IllegalArgumentException.class,()->NativeRedisPipelines.execute(lease,target,partial,failed,()->{}));assertEquals("partial",failed.outcome);assertEquals("rejected",failed.result.path("entries").get(1).path("state").asText());assertEquals("acknowledged",failed.result.path("entries").get(2).path("state").asText());assertArrayEquals(bytes("last"),redis.get(bytes(key)));
                checks.set(0);var late=jobs.new Job("human",target.connectionId());assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisPipelines.execute(lease,target,command,late,()->{if(checks.incrementAndGet()==3)late.cancelled=true;}));assertEquals(2,late.result.path("entries").size());assertTrue(Set.of("acknowledged","partial_or_unknown").contains(late.outcome),late.result.toString());
                var binary=batch(key);((com.fasterxml.jackson.databind.node.ArrayNode)binary.path("pipeline").get(0)).set(2,json("{\"base64\":\"AP8=\"}"));binary.withArray("pipeline").addArray().add("GETRANGE").add(key).add("0").add("10");
                assertEquals("AP8=",NativeRedisPipelines.execute(lease,target,binary,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(1).path("value").path("base64").asText());
                redis.set(bytes(key),new byte[8192]);var large=Profiles.JSON.createObjectNode();for(int i=0;i<32;i++)large.withArray("pipeline").addArray().add("GETRANGE").add(key).add("0").add("8191");
                var capped=jobs.new Job("agent:fixture",target.connectionId());var result=NativeRedisPipelines.execute(lease,target,large,capped,()->{});assertEquals(32,result.path("entries").size());assertTrue(capped.bytes<=1<<20);for(JsonNode entry:result.path("entries"))assertEquals("acknowledged",entry.path("state").asText());
                redis.del(bytes(key));
                var coverage=Profiles.JSON.createObjectNode();var commands=coverage.putArray("pipeline");
                String hash=key+":hash",list=key+":list",set=key+":set",sorted=key+":sorted";
                for(String[] args:List.of(
                        new String[]{"SET",key,"ABC"},new String[]{"EXPIRE",key,"120"},new String[]{"TTL",key},new String[]{"PERSIST",key},
                        new String[]{"PEXPIRE",key,"120000"},new String[]{"PTTL",key},new String[]{"STRLEN",key},new String[]{"GETBIT",key,"0"},
                        new String[]{"EXPIRETIME",key},new String[]{"PEXPIRETIME",key},new String[]{"RENAME",key,key+":renamed"},new String[]{"RENAMENX",key+":renamed",key},
                        new String[]{"HSET",hash,"field","value"},new String[]{"HEXISTS",hash,"field"},new String[]{"HLEN",hash},new String[]{"HDEL",hash,"field"},
                        new String[]{"LPUSH",list,"left"},new String[]{"RPUSH",list,"right"},new String[]{"LLEN",list},new String[]{"SADD",set,"value"},
                        new String[]{"SISMEMBER",set,"value"},new String[]{"SCARD",set},new String[]{"SREM",set,"value"},new String[]{"ZADD",sorted,"1.5","member"},
                        new String[]{"ZSCORE",sorted,"member"},new String[]{"ZCARD",sorted},new String[]{"ZREM",sorted,"member"},new String[]{"EXISTS",key},
                        new String[]{"TYPE",key},new String[]{"UNLINK",key,hash,list,set,sorted},new String[]{"XLEN",key},new String[]{"DEL",key})){
                    var item=commands.addArray();for(String arg:args)item.add(arg);
                }
                var covered=NativeRedisPipelines.execute(lease,target,coverage,jobs.new Job("human",target.connectionId()),()->{});
                assertEquals(32,covered.path("entries").size());for(JsonNode entry:covered.path("entries"))assertEquals("acknowledged",entry.path("state").asText(),entry.toString());
                assertTrue(covered.path("entries").get(1).path("value").asBoolean());assertEquals(1.5,covered.path("entries").get(24).path("value").asDouble());assertEquals("string",covered.path("entries").get(28).path("value").asText());
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
