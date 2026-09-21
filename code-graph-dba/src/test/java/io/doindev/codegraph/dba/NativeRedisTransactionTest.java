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
        var field=transaction("a","b");field.putArray("watch").addObject().put("key","a").put("field","").putNull("expected");
        NativeMutations.validate(target("standalone"),field);
        for(JsonNode invalid:List.of(json("null"),json("42"),json("{\"base64\":\"!\"}"),Profiles.JSON.getNodeFactory().textNode("x".repeat(8193)))){
            field.withArray("watch").get(0).withObject("").set("field",invalid);
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target("standalone"),field));
        }
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
            var deletion=Profiles.JSON.createObjectNode();deletion.putArray("transaction").addArray().add("HDEL").add("hash").add("field");
            deletion.putArray("watch").addObject().put("key","hash").put("field","field").put("expected","original");
            input.set("command",deletion);var deletionReview=ops.prepareBrowser("owner",input);
            assertTrue(deletionReview.path("destructive").asBoolean());assertTrue(deletionReview.path("transactionNotice").asText().contains("last field"));
            assertEquals(deletion,deletionReview.path("after").path("nativeCommand"));
            ops.discardBrowser("owner",deletionReview.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            var stringDelete=Profiles.JSON.createObjectNode();stringDelete.putArray("transaction").addArray().add("DEL").add("string");stringDelete.putArray("watch").addObject().put("key","string").put("expected","");
            input.set("command",stringDelete);var stringReview=ops.prepareBrowser("owner",input);
            assertTrue(stringReview.path("destructive").asBoolean());assertFalse(stringReview.path("eligiblePersistentRead").asBoolean());assertEquals(stringDelete,stringReview.path("after").path("nativeCommand"));
            ops.discardBrowser("owner",stringReview.path("id").asText());
            var stringCreate=transaction("string","");((com.fasterxml.jackson.databind.node.ArrayNode)stringCreate.path("transaction").get(0)).add("NX");stringCreate.putArray("watch").addObject().put("key","string").putNull("expected");
            input.set("command",stringCreate);var createReview=ops.prepareBrowser("owner",input);assertTrue(createReview.path("mutation").asBoolean());assertFalse(createReview.path("eligiblePersistentRead").asBoolean());
            assertEquals(stringCreate,createReview.path("after").path("nativeCommand"));ops.discardBrowser("owner",createReview.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
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
                // Explicit New string uses absence WATCH plus NX; empty content is not absence.
                var stringCreate=Profiles.JSON.createObjectNode();stringCreate.putArray("transaction").addArray().add("SET").add(encodedKey).add(Profiles.JSON.createObjectNode().put("base64","")).add("NX");
                stringCreate.putArray("watch").addObject().set("key",encodedKey);stringCreate.withArray("watch").get(0).withObject("").putNull("expected");
                var absentString=Profiles.JSON.createObjectNode();absentString.putArray("pipeline").addArray().add("TYPE").add(encodedKey);
                assertEquals("none",NativeRedisPipelines.execute(lease,target,absentString,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asText());
                assertEquals("OK",NativeMutations.execute(lease,target,stringCreate,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asText());assertArrayEquals(new byte[0],redis.get(binaryKey));assertEquals(-1L,redis.pttl(binaryKey));
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringCreate,jobs.new Job("human",target.connectionId()),()->{}),"Existing empty values must not be overwritten by New");
                // Deletion requires the complete loaded value and leaves concurrent writes intact.
                var stringDelete=Profiles.JSON.createObjectNode();stringDelete.putArray("transaction").addArray().add("DEL").add(encodedKey);
                stringDelete.putArray("watch").addObject().set("key",encodedKey);stringDelete.withArray("watch").get(0).withObject("").put("expected","");
                assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target,stringDelete).effect());
                redis.set(binaryKey,bytes("changed"));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringDelete,jobs.new Job("human",target.connectionId()),()->{}));assertArrayEquals(bytes("changed"),redis.get(binaryKey));redis.set(binaryKey,new byte[0]);
                checks.set(0);var stringRace=jobs.new Job("human",target.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringDelete,stringRace,()->{if(checks.incrementAndGet()==2)redis.set(binaryKey,bytes("external"));}));assertEquals("watched_key_changed",stringRace.result.path("reason").asText());assertArrayEquals(bytes("external"),redis.get(binaryKey));
                redis.set(binaryKey,new byte[0]);redis.pexpire(binaryKey,60000);checks.set(0);var stringCancel=jobs.new Job("human",target.connectionId());
                assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,stringDelete,stringCancel,()->{if(checks.incrementAndGet()==2)stringCancel.cancelled=true;}));assertEquals(1L,redis.exists(binaryKey));assertTrue(redis.pttl(binaryKey)>0);
                assertEquals(1,NativeMutations.execute(lease,target,stringDelete,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asInt(-1));assertEquals(-2L,redis.pttl(binaryKey));assertEquals(0L,redis.exists(binaryKey));
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringDelete,jobs.new Job("human",target.connectionId()),()->{}),"Confirmed deletion must not replay");
                checks.set(0);var creationRace=jobs.new Job("human",target.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringCreate,creationRace,()->{if(checks.incrementAndGet()==2)redis.set(binaryKey,bytes("competing insert"));}));assertEquals("watched_key_changed",creationRace.result.path("reason").asText());assertArrayEquals(bytes("competing insert"),redis.get(binaryKey));
                redis.del(binaryKey);redis.hset(binaryKey,bytes("field"),bytes("unrelated"));
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringDelete,jobs.new Job("human",target.connectionId()),()->{}));assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,stringCreate,jobs.new Job("human",target.connectionId()),()->{}));assertEquals("hash",redis.type(binaryKey));redis.del(binaryKey);
                // Hash edits use one exact binary field expectation; HSET preserves key TTL, not field TTL.
                byte[] hashField={0,(byte)255};redis.hset(binaryKey,hashField,new byte[]{0,(byte)255});redis.pexpire(binaryKey,60000);
                var hashEdit=Profiles.JSON.createObjectNode();var hset=hashEdit.putArray("transaction").addArray().add("HSET").add(encodedKey);
                hset.add(Profiles.JSON.createObjectNode().put("base64","AP8=")).add(Profiles.JSON.createObjectNode().put("base64","AQI="));
                var hashWatch=hashEdit.putArray("watch").addObject();hashWatch.set("key",encodedKey);hashWatch.set("field",Profiles.JSON.createObjectNode().put("base64","AP8="));
                hashWatch.set("expected",Profiles.JSON.createObjectNode().put("base64","AP8="));
                ttlBefore=redis.pttl(binaryKey);
                saved=NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{});
                assertEquals(0,saved.path("entries").get(0).path("value").asInt(-1));assertArrayEquals(new byte[]{1,2},redis.hget(binaryKey,hashField));
                assertTrue(redis.pttl(binaryKey)>0&&redis.pttl(binaryKey)<=ttlBefore);
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}));
                hashWatch.set("expected",Profiles.JSON.createObjectNode().put("base64","AQI="));
                redis.hpexpire(binaryKey,60000,hashField);
                var expiryFailure=assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}));
                assertTrue(expiryFailure.getMessage().contains("Expiring hash fields"));assertTrue(redis.hpttl(binaryKey,hashField).getFirst()>0);
                redis.hset(binaryKey,hashField,new byte[]{1,2});checks.set(0);
                var hashRace=jobs.new Job("human",target.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,hashRace,()->{if(checks.incrementAndGet()==2)redis.hset(binaryKey,bytes("other"),bytes("changed"));}));
                assertEquals("watched_key_changed",hashRace.result.path("reason").asText());
                redis.hdel(binaryKey,hashField);
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}));
                hashWatch.putNull("expected");
                assertEquals(1,NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asInt(-1));
                redis.hset(binaryKey,hashField,new byte[65537]);hashWatch.put("expected","");
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}));
                redis.del(binaryKey);hashWatch.putNull("expected");
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}));
                assertEquals(0L,redis.exists(binaryKey));
                // UI insertion/deletion uses exactly one field, never a whole-hash replacement.
                redis.hset(binaryKey,bytes("keep"),bytes("untouched"));redis.pexpire(binaryKey,60000);
                hset.set(3,Profiles.JSON.createObjectNode().put("base64","")); // Empty values are not absence.
                ttlBefore=redis.pttl(binaryKey);
                var absence=Profiles.JSON.createObjectNode();absence.putArray("pipeline").addArray().add("TYPE").add(encodedKey);
                absence.withArray("pipeline").addArray().add("HEXISTS").add(encodedKey).add(Profiles.JSON.createObjectNode().put("base64","AP8="));
                var absent=NativeRedisPipelines.execute(lease,target,absence,jobs.new Job("human",target.connectionId()),()->{});
                assertEquals("hash",absent.path("entries").get(0).path("value").asText());assertTrue(absent.path("entries").get(1).path("value").isBoolean());assertFalse(absent.path("entries").get(1).path("value").asBoolean());
                saved=NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{});
                assertEquals(1,saved.path("entries").get(0).path("value").asInt(-1));assertArrayEquals(new byte[0],redis.hget(binaryKey,hashField));
                assertTrue(redis.pttl(binaryKey)>0&&redis.pttl(binaryKey)<=ttlBefore);
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}),"Insert must not overwrite an existing empty field");
                var hashDelete=hashEdit.deepCopy();hashDelete.putArray("transaction").addArray().add("HDEL").add(encodedKey).add(Profiles.JSON.createObjectNode().put("base64","AP8="));
                hashDelete.withArray("watch").get(0).withObject("").put("expected","");
                assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target,hashDelete).effect());
                redis.hset(binaryKey,hashField,bytes("concurrent"));
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashDelete,jobs.new Job("human",target.connectionId()),()->{}));
                assertArrayEquals(bytes("concurrent"),redis.hget(binaryKey,hashField));redis.hset(binaryKey,hashField,new byte[0]);
                checks.set(0);var deleteRace=jobs.new Job("human",target.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashDelete,deleteRace,()->{if(checks.incrementAndGet()==2)redis.hset(binaryKey,bytes("keep"),bytes("race"));}));
                assertEquals("watched_key_changed",deleteRace.result.path("reason").asText());assertTrue(redis.hexists(binaryKey,hashField));
                checks.set(0);var deleteCancel=jobs.new Job("human",target.connectionId());
                assertThrows(java.util.concurrent.CancellationException.class,()->NativeMutations.execute(lease,target,hashDelete,deleteCancel,()->{if(checks.incrementAndGet()==2)deleteCancel.cancelled=true;}));assertTrue(redis.hexists(binaryKey,hashField));
                saved=NativeMutations.execute(lease,target,hashDelete,jobs.new Job("human",target.connectionId()),()->{});
                assertEquals(1,saved.path("entries").get(0).path("value").asInt(-1));assertFalse(redis.hexists(binaryKey,hashField));assertArrayEquals(bytes("race"),redis.hget(binaryKey,bytes("keep")));assertTrue(redis.pttl(binaryKey)>0);
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashDelete,jobs.new Job("human",target.connectionId()),()->{}),"Deletion must not replay after success");
                checks.set(0);var insertRace=jobs.new Job("human",target.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,insertRace,()->{if(checks.incrementAndGet()==2)redis.hset(binaryKey,hashField,bytes("inserted elsewhere"));}));
                assertEquals("watched_key_changed",insertRace.result.path("reason").asText());assertArrayEquals(bytes("inserted elsewhere"),redis.hget(binaryKey,hashField));
                hashDelete.withArray("watch").get(0).withObject("").put("expected","inserted elsewhere");redis.hdel(binaryKey,bytes("keep"));
                assertEquals(1,NativeMutations.execute(lease,target,hashDelete,jobs.new Job("human",target.connectionId()),()->{}).path("entries").get(0).path("value").asInt(-1));
                assertEquals(0L,redis.exists(binaryKey),"Deleting the last field removes the hash key");
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,hashEdit,jobs.new Job("human",target.connectionId()),()->{}),"Insert must not recreate a removed hash");
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
