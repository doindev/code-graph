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

class NativeRedisTransactionTest {
    @TempDir Path root;
    private static NativeTarget target(String topology){return new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Fixture","0","",topology);}
    private static JsonNode json(String value)throws Exception{return Profiles.JSON.readTree(value);}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static ObjectNode transaction(String key,String value){
        var command=Profiles.JSON.createObjectNode();command.putArray("transaction").addArray().add("SET").add(key).add(value);return command;
    }
    @Test void boundedStructuralValidationAndClassification()throws Exception{
        var target=target("standalone");
        for(String invalid:List.of("{}","{\"transaction\":[]}","{\"transaction\":[[\"GET\",\"a\"]]}",
                "{\"transaction\":[[\"MULTI\"]]}","{\"transaction\":[[\"FLUSHDB\"]]}",
                "{\"transaction\":[[\"EVAL\",\"return 1\",\"0\"]]}","{\"transaction\":[[\"SET\",\"a\"]]}",
                "{\"transaction\":[[\"SET\",\"a\",\"b\"]],\"database\":\"1\"}",
                "{\"transaction\":[{\"transaction\":[[\"SET\",\"a\",\"b\"]]}]}")){
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,json(invalid)),invalid);
        }
        var command=transaction("a","b");var classified=NativeCommand.classify(target,command);
        assertEquals("redis.transaction",classified.category());assertEquals(NativeCommand.Effect.WRITE,classified.effect());assertFalse(classified.reusableRead());
        command.withArray("transaction").addArray().add("DEL").add("a");
        assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target,command).effect());
        var maximum=transaction("a","b");for(int i=1;i<32;i++)maximum.withArray("transaction").addArray().add("SET").add("a").add("b");
        NativeMutations.validate(target,maximum);maximum.withArray("transaction").addArray().add("SET").add("a").add("b");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,maximum));
        var keys=transaction("a","b");var delete=keys.withArray("transaction").addArray().add("DEL");for(int i=0;i<100;i++)delete.add("key"+i);
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,keys));
    }
    @Test void watchIsTypedBoundedAndClusterScopeIncludesEveryKey()throws Exception{
        var target=target("cluster");
        var command=transaction("{a}:1","value");command.withArray("transaction").addArray().add("RENAME").add("{a}:1").add("{a}:2");
        command.putArray("watch").addObject().put("key","{a}:2").putNull("expected");NativeMutations.validate(target,command);
        command.withArray("watch").get(0).withObject("").put("key","{b}:2");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,command));
        for(String watch:List.of("null","{}","[{}]","[{\"key\":\"a\"}]","[{\"key\":\"a\",\"expected\":42}]",
                "[{\"key\":\"a\",\"expected\":{\"base64\":\"!\"}}]","[{\"key\":\"a\",\"expected\":null,\"extra\":true}]",
                "[{\"key\":\"a\",\"expected\":null},{\"key\":{\"base64\":\"YQ==\"},\"expected\":null}]")){
            var bad=transaction("a","b");bad.set("watch",json(watch));assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target("standalone"),bad),watch);
        }
        var huge=transaction("a","b");huge.putArray("watch").addObject().put("key","a").put("expected","x".repeat(65537));
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target("standalone"),huge));
    }
    @Test void reviewIsExactOwnedReadOnlyAndNonReusable()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Redis").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Redis").put("database","0");input.set("command",transaction("a","b"));
            var review=ops.prepareBrowser("owner",input);assertFalse(review.path("eligiblePersistentRead").asBoolean());assertTrue(review.path("transactionNotice").asText().contains("does not roll back"));
            input.put("expectedTargetRevision",review.path("targetRevision").asText());ops.prepare(input);
            input.put("expectedTargetRevision","wrong-revision");assertThrows(IllegalArgumentException.class,()->ops.prepareBrowser("owner",input));
            input.putNull("expectedTargetRevision");assertThrows(IllegalArgumentException.class,()->ops.prepare(input));
            input.put("expectedTargetRevision",review.path("targetRevision").asText());
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            input.set("command",transaction("a","changed"));assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));
            ops.discardBrowser("owner",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            var limited=jobs.new Job("agent:fixture",profile.path("id").asText());limited.rowLimit=1;
            var batch=transaction("a","b");batch.withArray("transaction").addArray().add("SET").add("a").add("c");
            assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(null,target("standalone"),batch,limited,()->fail("No authority callback before admission")));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));assertThrows(IllegalArgumentException.class,()->ops.prepare(input));
            input.remove("expectedTargetRevision");assertThrows(IllegalArgumentException.class,()->ops.prepare(input));
        }
    }
    @Test @Timeout(120) void disposableTransactionsConflictsCancellationPartialErrorsAndCleanup()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","Transactions").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){
                options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");
                if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}
            }
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Transactions").put("database",options.path("database").asText()));
            String key="{txn-"+UUID.randomUUID()+"}:value";
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,10)){
                var redis=observer.sync();var command=transaction(key,"new");command.putArray("watch").addObject().put("key",key).putNull("expected");
                var job=jobs.new Job("human",target.connectionId());assertEquals("acknowledged",NativeMutations.execute(lease,target,command,job,()->{}).path("outcome").asText());assertArrayEquals(bytes("new"),redis.get(bytes(key)));
                var mismatch=jobs.new Job("human",target.connectionId());assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,command,mismatch,()->{}));assertEquals("conflict",mismatch.outcome);assertEquals("expected_value_changed",mismatch.result.path("reason").asText());assertTrue(mismatch.bytes>0);
                command.withArray("watch").get(0).withObject("").put("expected","new");
                var race=jobs.new Job("human",target.connectionId());var checks=new AtomicInteger();
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,command,race,()->{if(checks.incrementAndGet()==2)redis.set(bytes(key),bytes("concurrent"));}));assertEquals("watched_key_changed",race.result.path("reason").asText());assertArrayEquals(bytes("concurrent"),redis.get(bytes(key)));
                command.remove("watch");
                var cancelled=jobs.new Job("human",target.connectionId());checks.set(0);
                assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,command,cancelled,()->{if(checks.incrementAndGet()==2)cancelled.cancelled=true;}));assertEquals("not_started",cancelled.outcome);assertArrayEquals(bytes("concurrent"),redis.get(bytes(key)));
                var revoked=jobs.new Job("human",target.connectionId());checks.set(0);
                assertThrows(SecurityException.class,()->NativeMutations.execute(lease,target,command,revoked,()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));assertArrayEquals(bytes("concurrent"),redis.get(bytes(key)));
                var partial=transaction(key,"first");partial.withArray("transaction").addArray().add("LPUSH").add(key).add("wrong-type");partial.withArray("transaction").addArray().add("SET").add(key).add("last");
                var failed=jobs.new Job("human",target.connectionId());assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,partial,failed,()->{}));assertEquals("partial",failed.outcome);assertEquals("failed",failed.result.path("entries").get(1).path("state").asText());assertArrayEquals(bytes("last"),redis.get(bytes(key)));
                var maximum=transaction(key,"0");for(int i=1;i<32;i++)maximum.withArray("transaction").addArray().add("SET").add(key).add(Integer.toString(i));
                for(int run=0;run<3;run++){var complete=jobs.new Job("human",target.connectionId());assertEquals(32,NativeMutations.execute(lease,target,maximum,complete,()->{}).path("entries").size());}
                assertArrayEquals(bytes("31"),redis.get(bytes(key)));redis.del(bytes(key));
                // Binary expectations and values are never interpreted as UTF-8 SQL/control syntax.
                var binary=transaction(key,"");((com.fasterxml.jackson.databind.node.ArrayNode)binary.path("transaction").get(0)).set(2,json("{\"base64\":\"AP8=\"}"));
                NativeMutations.execute(lease,target,binary,jobs.new Job("human",target.connectionId()),()->{});
                binary.putArray("watch").addObject().put("key",key).set("expected",json("{\"base64\":\"AP8=\"}"));
                assertEquals("acknowledged",NativeMutations.execute(lease,target,binary,jobs.new Job("human",target.connectionId()),()->{}).path("outcome").asText());redis.del(bytes(key));
                // The graphical string editor uses this exact binary-safe optimistic command.
                byte[] binaryKey=Arrays.copyOf(bytes(key),bytes(key).length+2);binaryKey[binaryKey.length-1]=(byte)255;
                redis.set(binaryKey,new byte[]{0,(byte)255});redis.pexpire(binaryKey,60000);
                var edit=Profiles.JSON.createObjectNode();var set=edit.putArray("transaction").addArray().add("SET");
                var encodedKey=Profiles.JSON.createObjectNode().put("base64",Base64.getEncoder().encodeToString(binaryKey));
                set.add(encodedKey).add(Profiles.JSON.createObjectNode().put("base64","AQI=")).add("XX").add("KEEPTTL");
                var watch=edit.putArray("watch").addObject();watch.set("key",encodedKey);watch.set("expected",Profiles.JSON.createObjectNode().put("base64","AP8="));
                long ttlBefore=redis.pttl(binaryKey);
                var saved=NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{});
                assertEquals("OK",saved.path("entries").get(0).path("value").asText());
                assertArrayEquals(new byte[]{1,2},redis.get(binaryKey));assertTrue(redis.pttl(binaryKey)>0);assertTrue(redis.pttl(binaryKey)<=ttlBefore);
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));
                assertArrayEquals(new byte[]{1,2},redis.get(binaryKey));
                watch.set("expected",Profiles.JSON.createObjectNode().put("base64","AQI="));redis.persist(binaryKey);
                NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{});assertEquals(-1L,redis.pttl(binaryKey));
                redis.del(binaryKey);
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,edit,jobs.new Job("human",target.connectionId()),()->{}));
                assertEquals(0L,redis.exists(binaryKey),"Expired/deleted keys must not be recreated by the editor");
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
