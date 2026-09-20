package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.codec.ByteArrayCodec;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: use only ownership-labelled disposable servers, never saved application profiles. */
class NativeVendorTest {
    @TempDir Path root;

    @Test void nativeSetupReadCapsTypedValuesAndClientCleanup()throws Exception {
        String engine=System.getenv("NATIVE_TEST_ENGINE"),port=System.getenv("NATIVE_TEST_PORT");
        Assumptions.assumeTrue(Set.of("mongodb","redis").contains(Objects.toString(engine,""))&&port!=null,"Disposable native fixture not configured");
        String database=engine.equals("mongodb")?"cg_"+UUID.randomUUID().toString().replace("-",""):"0";
        var vault=new DbaTest.MemoryVault();
        try(var profiles=new Profiles(root,vault);var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),owner->true);
            var clients=new NativeConnections(profiles)) {
            ObjectNode draft=Profiles.JSON.createObjectNode().put("name","Native fixture").put("templateId",engine+"-native")
                    .put("url",engine+"://127.0.0.1:"+port).put("readOnly",false);
            draft.putObject("nativeOptions").put("database",database).put("maximumPoolSize",2).put("socketTimeoutMS",10000).put("connectTimeoutMS",5000);
            ConnectionSetup setup=new ConnectionSetup(profiles,jobs);String before=Files.readString(root.resolve("profiles.json"));
            JsonNode tested=ConnectionSetupTest.await(jobs,"human",setup.operation("human","draft-test",draft));
            assertEquals("complete",tested.path("state").asText(),tested.toString());
            assertTrue(tested.path("result").path("connected").asBoolean());
            assertFalse(tested.path("result").path("version").asText().isBlank());
            assertEquals(before,Files.readString(root.resolve("profiles.json")));assertTrue(vault.secrets.isEmpty());
            assertEquals(0,jdbc.count());assertEquals(0,clients.telemetry().path("clients").asInt());
            draft.put("receipt",tested.path("result").path("receipt").asText());
            assertThrows(IllegalArgumentException.class,()->setup.save("another-browser",null,draft));
            ObjectNode saved=setup.save("human",null,draft);String id=saved.path("id").asText();
            ObjectNode request=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Native fixture").put("database",database);
            if(engine.equals("mongodb"))request.put("collection","users");
            NativeTarget target=NativeTarget.resolve(profiles.get(id),request);
            try(var lease=clients.acquire(id)) {
                if(engine.equals("mongodb"))mongo(lease,target);
                else redis(lease,target);
                assertEquals(1,clients.telemetry().path("activeLeases").asInt());
                clients.remove(id);assertEquals(0,clients.telemetry().path("clients").asInt());
            }
            profiles.remove(id);assertTrue(profiles.list().isEmpty());
        }
    }

    @Test @Timeout(120) void yoloNativeOperationsRemainExactAndDoNotCreatePolicies()throws Exception{
        String engine=System.getenv("NATIVE_TEST_ENGINE"),port=System.getenv("NATIVE_TEST_PORT");
        Assumptions.assumeTrue(Set.of("mongodb","redis").contains(Objects.toString(engine,""))&&port!=null,"Disposable native fixture not configured");
        Assumptions.assumeTrue(Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"").startsWith("cgraph-native-"),"Ownership-labelled fixture required");
        try(var runtime=new DbaRuntime(new DbaConfig(root,256L<<20,2,1000,100,20,60,"none",true),new DbaTest.MemoryVault(),false)){
            String principal=runtime.trustedLocalAgent(),session=UUID.randomUUID().toString(),database=engine.equals("mongodb")?"native_workflow":"0";
            runtime.registerMcpSession(session,principal,System.currentTimeMillis()+300000);
            ObjectNode profile=Profiles.JSON.createObjectNode().put("name","Native live")
                    .put("templateId",engine+"-native").put("url",engine+"://127.0.0.1:"+port).put("readOnly",false);
            profile.putObject("nativeOptions").put("database",database);
            ObjectNode create=Profiles.JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","Owned native test profile").put("saveUntested",true);
            create.set("profile",profile);
            JsonNode created=finish(runtime,principal,session,runtime.agentCall(principal,session,"dba_request_connection_create",create));
            String id=created.path("job").path("result").path("id").asText();assertFalse(id.isBlank(),created.toPrettyString());
            ObjectNode target=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Native live").put("database",database);
            if(engine.equals("mongodb"))target.put("collection","items");
            JsonNode capabilities=runtime.agentCall(principal,session,"dba_get_capabilities",target);
            assertEquals(engine,capabilities.path("engine").asText());assertFalse(capabilities.path("operations").path("sql").path("available").asBoolean());
            var live=runtime.agentCall(principal,session,"dba_get_capabilities",target.deepCopy().put("live",true));
            long liveUntil=System.nanoTime()+15_000_000_000L;JsonNode observed=live;
            while(!Set.of("complete","failed","cancelled").contains(observed.path("state").asText())&&System.nanoTime()<liveUntil){
                Thread.sleep(25);observed=runtime.agentCall(principal,session,"dba_job_status",Profiles.JSON.createObjectNode().put("jobId",live.path("id").asText()));
            }
            assertEquals("complete",observed.path("state").asText(),observed.toPrettyString());assertFalse(observed.path("result").path("version").path("server").asText().isBlank());
            runtime.agentCall(principal,session,"dba_release_job",Profiles.JSON.createObjectNode().put("jobId",live.path("id").asText()));
            String[] commands=engine.equals("mongodb")?new String[]{
                    "{\"create\":\"items\"}",
                    "{\"insert\":\"items\",\"documents\":[{\"_id\":1,\"name\":\"first\"}]}",
                    "{\"update\":\"items\",\"updates\":[{\"q\":{\"_id\":1},\"u\":{\"$set\":{\"name\":\"updated\"}}}]}",
                    "{\"createIndexes\":\"items\",\"indexes\":[{\"key\":{\"name\":1},\"name\":\"name_idx\"}]}",
                    "{\"find\":\"items\",\"filter\":{\"_id\":1}}",
                    "{\"dropIndexes\":\"items\",\"index\":\"name_idx\"}",
                    "{\"delete\":\"items\",\"deletes\":[{\"q\":{\"_id\":1},\"limit\":1}]}",
                    "{\"drop\":\"items\"}"}:new String[]{
                    "[\"SET\",\"cg-write\",\"first\"]","[\"SET\",\"cg-write\",\"updated\"]",
                    "[\"EXPIRE\",\"cg-write\",\"60\"]","[\"GET\",\"cg-write\"]",
                    "[\"RENAME\",\"cg-write\",\"cg-renamed\"]","[\"DEL\",\"cg-renamed\"]",
                    "[\"HSET\",\"cg-hash\",\"one\",\"value\"]","[\"HDEL\",\"cg-hash\",\"one\"]",
                    "[\"LPUSH\",\"cg-list\",\"value\"]","[\"DEL\",\"cg-list\"]",
                    "[\"ZADD\",\"cg-zset\",\"1.5\",\"member\"]","[\"ZREM\",\"cg-zset\",\"member\"]",
                    "[\"SADD\",\"cg-set\",\"member\"]","[\"SREM\",\"cg-set\",\"member\"]",
                    "{\"transaction\":[[\"SET\",\"{cg-txn}:one\",\"value\"],[\"DEL\",\"{cg-txn}:one\"]],\"watch\":[{\"key\":\"{cg-txn}:one\",\"expected\":null}]}",
                    "[\"XGROUP\",\"CREATE\",\"cg-stream\",\"workers\",\"0-0\",\"MKSTREAM\"]",
                    "[\"XADD\",\"cg-stream\",\"1-0\",\"field\",\"value\"]",
                    "[\"XREADGROUP\",\"GROUP\",\"workers\",\"one\",\"COUNT\",\"1\",\"STREAMS\",\"cg-stream\",\">\"]",
                    "[\"XPENDING\",\"cg-stream\",\"workers\",\"-\",\"+\",\"10\"]",
                    "[\"XACK\",\"cg-stream\",\"workers\",\"1-0\"]","[\"DEL\",\"cg-stream\"]"};
            for(String command:commands){
                var input=target.deepCopy().put("requestId",UUID.randomUUID().toString()).put("purpose","Owned native command verification");
                input.set("command",Profiles.JSON.readTree(command));
                JsonNode submitted=runtime.agentCall(principal,session,"dba_request_native_command",input);
                assertEquals("automatic",submitted.path("approvalChannel").asText(),submitted.toPrettyString());
                JsonNode done=finish(runtime,principal,session,submitted);
                if(command.contains("\"find\"")||command.contains("\"GET\"")){
                    assertTrue(done.toString().contains("updated"),done.toPrettyString());
                    verifyCatalogWorkflow(runtime,principal,session,target);
                }
                else assertEquals(command.contains("\"XPENDING\"")?"read":"acknowledged",done.path("job").path("result").path("outcome").asText(),done.toPrettyString());
            }
        }
    }
    private void verifyCatalogWorkflow(DbaRuntime runtime,String principal,String session,ObjectNode target)throws Exception{
        var scope=target.deepCopy();scope.remove("collection");
        var bad=scope.deepCopy().put("connectionName","wrong");
        assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,session,"dba_capture_schema",bad));
        var snapshot=awaitJob(runtime,principal,session,runtime.agentCall(principal,session,"dba_capture_schema",scope));
        String snapshotId=snapshot.path("id").asText();
        try{
            assertEquals("codegraph-schema-v1",snapshot.path("result").path("format").asText());
            assertFalse(snapshot.path("result").path("objects").isEmpty(),snapshot.toPrettyString());
            assertFalse(snapshot.path("result").toString().contains("updated"),"Snapshot must not retain document/key values");
            var compared=awaitJob(runtime,principal,session,runtime.agentCall(principal,session,"dba_compare_schemas",
                    Profiles.JSON.createObjectNode().put("leftSnapshotId",snapshotId).put("rightSnapshotId",snapshotId)));
            assertEquals(0,compared.path("result").path("totalDifferences").asInt(-1),compared.toPrettyString());
            runtime.agentCall(principal,session,"dba_release_job",Profiles.JSON.createObjectNode().put("jobId",compared.path("id").asText()));
            runtime.agentCall(principal,session,"dba_refresh_catalog",scope);
            var status=runtime.agentCall(principal,session,"dba_scan_status",scope.deepCopy().put("afterGeneration",0).put("waitMillis",5000));
            assertTrue(status.path("generation").asLong()>0,status.toPrettyString());
            var objects=runtime.agentCall(principal,session,"dba_search_objects",scope.deepCopy().put("limit",10));
            assertFalse(objects.path("objects").isEmpty(),objects.toPrettyString());
            String objectId=objects.path("objects").get(0).path("id").asText();
            var properties=runtime.agentCall(principal,session,"dba_get_indexed_properties",scope.deepCopy().put("objectId",objectId));
            assertTrue(properties.has("properties"),properties.toPrettyString());
            assertFalse(properties.toString().contains("updated"));
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,session,"dba_search_objects",bad));
        }finally{runtime.agentCall(principal,session,"dba_release_job",Profiles.JSON.createObjectNode().put("jobId",snapshotId));}
    }
    private JsonNode awaitJob(DbaRuntime runtime,String principal,String session,JsonNode initial)throws Exception{
        JsonNode job=initial;long until=System.nanoTime()+20_000_000_000L;
        while(!Set.of("complete","failed","cancelled").contains(job.path("state").asText())&&System.nanoTime()<until){
            job=runtime.agentCall(principal,session,"dba_job_status",Profiles.JSON.createObjectNode()
                    .put("jobId",initial.path("id").asText()).put("afterRevision",job.path("revision").asLong()).put("waitMillis",1000));
        }
        assertEquals("complete",job.path("state").asText(),job.toPrettyString());return job;
    }
    private JsonNode finish(DbaRuntime runtime,String principal,String session,JsonNode initial)throws Exception{
        JsonNode result=initial;long until=System.nanoTime()+30_000_000_000L;
        while(!Set.of("complete","failed","cancelled").contains(result.path("state").asText())&&System.nanoTime()<until){
            Thread.sleep(25);result=runtime.agentCall(principal,session,"dba_request_status",Profiles.JSON.createObjectNode().put("requestId",initial.path("id").asText()));
        }
        assertEquals("complete",result.path("state").asText(),result.toPrettyString());
        if(result.has("jobId"))runtime.agentCall(principal,session,"dba_release_job",Profiles.JSON.createObjectNode().put("jobId",result.path("jobId").asText()));
        return result;
    }

    @Test @Timeout(120) void normalNativeApprovalsGateWritesAndKeepReadsIndependent()throws Exception{
        String engine=System.getenv("NATIVE_TEST_ENGINE"),port=System.getenv("NATIVE_TEST_PORT");
        Assumptions.assumeTrue(Set.of("mongodb","redis").contains(Objects.toString(engine,""))&&port!=null,"Disposable native fixture not configured");
        Assumptions.assumeTrue(Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"").startsWith("cgraph-native-"),"Ownership-labelled fixture required");
        var agents=new AgentAccess(root);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var contexts=new ProjectContexts(profiles,jdbc,agents,System::currentTimeMillis,false);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,256L<<20,2,1000,100,20),owner->true);
            var nativeOps=new NativeOperations(profiles,jobs,contexts);
            var requests=new AgentRequests(profiles,jdbc,new ConnectionSetup(profiles,jobs),contexts,agents,jobs)){
            requests.nativeOperations(nativeOps);String principal=agents.trustedLocal();
            String database=engine.equals("mongodb")?"review_"+UUID.randomUUID().toString().replace("-",""):"0";
            var profile=Profiles.JSON.createObjectNode().put("name","Reviewed native").put("templateId",engine+"-native").put("url",engine+"://127.0.0.1:"+port).put("readOnly",false);
            profile.putObject("nativeOptions").put("database",database);
            String id=profiles.put(null,profile).path("id").asText();
            var target=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Reviewed native").put("database",database);
            String key="review:"+UUID.randomUUID();
            String binaryKey=Base64.getEncoder().encodeToString((key+"\0").getBytes(StandardCharsets.UTF_8));
            String binaryHash=Base64.getEncoder().encodeToString((key+"\0hash").getBytes(StandardCharsets.UTF_8));
            String[] commands=engine.equals("mongodb")?new String[]{
                "{\"insert\":\"items\",\"documents\":[{\"_id\":1,\"active\":true}]}",
                "{\"create\":\"active_items\",\"viewOn\":\"items\",\"pipeline\":[{\"$match\":{\"active\":true}}]}",
                "{\"find\":\"active_items\",\"filter\":{}}",
                "{\"collMod\":\"active_items\",\"viewOn\":\"items\",\"pipeline\":[]}",
                "{\"drop\":\"active_items\"}","{\"drop\":\"items\"}"
            }:new String[]{
                "[\"SET\",\""+key+"\",\"first\",\"NX\",\"EX\",\"60\"]",
                "[\"SET\",\""+key+"\",\"must-not-replace\",\"NX\"]",
                "[\"GET\",\""+key+"\"]","[\"DEL\",\""+key+"\"]",
                "[\"SET\",{\"base64\":\""+binaryKey+"\"},{\"base64\":\"AP8B\"}]",
                "[\"GET\",{\"base64\":\""+binaryKey+"\"}]",
                "[\"HSET\",{\"base64\":\""+binaryHash+"\"},{\"base64\":\"/wA=\"},{\"base64\":\"AP8B\"}]",
                "[\"HGET\",{\"base64\":\""+binaryHash+"\"},{\"base64\":\"/wA=\"}]",
                "[\"DEL\",{\"base64\":\""+binaryKey+"\"},{\"base64\":\""+binaryHash+"\"}]",
                "{\"transaction\":[[\"SET\",\""+key+"\",\"reviewed-transaction\"],[\"DEL\",\""+key+"\"]],\"watch\":[{\"key\":\""+key+"\",\"expected\":null}]}",
                "[\"XGROUP\",\"CREATE\",\""+key+"\",\"workers\",\"0-0\",\"MKSTREAM\"]",
                "[\"XADD\",\""+key+"\",\"1-0\",\"field\",\"value\"]",
                "[\"XREADGROUP\",\"GROUP\",\"workers\",\"one\",\"COUNT\",\"1\",\"STREAMS\",\""+key+"\",\">\"]",
                "[\"XPENDING\",\""+key+"\",\"workers\",\"-\",\"+\",\"10\"]",
                "[\"XACK\",\""+key+"\",\"workers\",\"1-0\"]","[\"DEL\",\""+key+"\"]"
            };
            int index=0;
            for(String text:commands){
                var command=Profiles.JSON.readTree(text);
                var input=target.deepCopy().put("requestId",UUID.randomUUID().toString()).put("purpose","Review owned native fixture");
                input.set("command",command);
                if(engine.equals("mongodb"))input.put("collection",command.elements().next().asText());
                var pending=requests.request(principal,"native_command",input);
                assertEquals("awaiting_approval",pending.path("state").asText());assertFalse(pending.has("jobId"));
                if(index==0)assertEquals(0,nativeOps.telemetry().path("clients").asInt());
                String requestId=pending.path("id").asText();
                assertThrows(IllegalArgumentException.class,()->requests.decide("human",requestId,"always_allow",true,Profiles.JSON.createObjectNode()));
                var submitted=requests.decide("human",requestId,"approve_once",true,Profiles.JSON.createObjectNode());
                String jobId=submitted.path("jobId").asText();assertFalse(jobId.isEmpty(),submitted.toString());
                var done=ConnectionSetupTest.await(jobs,"agent:"+principal,Profiles.JSON.createObjectNode().put("id",jobId));
                assertEquals("complete",done.path("state").asText(),done.toPrettyString());
                if(engine.equals("redis")&&index==1)assertFalse(done.path("result").path("applied").asBoolean(true));
                if(engine.equals("redis")&&index==2)assertTrue(done.path("result").toString().contains("first"));
                if(engine.equals("redis")&&(index==5||index==7)){
                    assertEquals("AP8B",done.path("result").path("entries").get(0).path("base64").asText());
                    assertEquals("binary",done.path("result").path("entries").get(0).path("encoding").asText());
                }
                if(engine.equals("mongodb")&&index==2)assertEquals(1,done.path("result").path("rowCount").asInt());
                assertEquals("complete",requests.get(principal,requestId).path("state").asText());
                jobs.remove("agent:"+principal,jobId);index++;
            }
        }
    }

    private void mongo(NativeConnections.Lease lease,NativeTarget target)throws Exception {
        var db=lease.mongo.getDatabase(target.database());
        try {
            var collection=db.getCollection("users");
            collection.insertMany(List.of(new Document("n",9007199254740993L).append("name","alpha").append("at",new Date(0)),
                    new Document("n",2).append("name","beta"),new Document("n",3).append("name","gamma")));
            JsonNode command=Profiles.JSON.readTree("{\"find\":\"users\",\"filter\":{},\"sort\":{\"name\":1}}");
            ObjectNode result=NativeReadExecutor.execute(lease,target,command,new NativeReadExecutor.Limits(2,65536,10),()->false);
            assertEquals(2,result.path("rowCount").asInt());assertTrue(result.path("truncated").asBoolean());
            assertEquals("9007199254740993",result.path("entries").get(0).path("n").path("$numberLong").asText());
            assertTrue(result.path("entries").get(0).path("_id").has("$oid"));
            assertTrue(result.path("entries").get(0).path("at").has("$date"));
            JsonNode pipeline=Profiles.JSON.readTree("{\"aggregate\":\"users\",\"pipeline\":[{\"$match\":{\"name\":\"beta\"}}]}");
            assertEquals(1,NativeReadExecutor.execute(lease,target,pipeline,new NativeReadExecutor.Limits(100,65536,10),()->false).path("rowCount").asInt());
            JsonNode plan=Profiles.JSON.readTree("{\"explain\":{\"find\":\"users\",\"filter\":{\"name\":\"alpha\"}},\"verbosity\":\"queryPlanner\"}");
            assertTrue(NativeReadExecutor.execute(lease,target,plan,new NativeReadExecutor.Limits(100,65536,10),()->false).path("estimated").asBoolean());
            JsonNode names=NativeReadExecutor.execute(lease,target,Profiles.JSON.readTree("{\"listCollections\":1,\"filter\":{\"name\":\"users\"}}"),new NativeReadExecutor.Limits(100,65536,10),()->false);
            assertEquals("users",names.path("entries").get(0).path("name").asText());
            JsonNode indexes=NativeReadExecutor.execute(lease,target,Profiles.JSON.readTree("{\"listIndexes\":\"users\"}"),new NativeReadExecutor.Limits(100,65536,10),()->false);
            assertEquals("_id_",indexes.path("entries").get(0).path("name").asText());
            var snapshot=NativeSchemaObservations.capture(lease,target,"owned-snapshot",100,1<<20,10,2,()->false);
            assertEquals("codegraph-schema-v1",snapshot.path("format").asText());assertEquals(1,snapshot.path("objects").size());
            var observed=snapshot.path("objects").get(0);assertEquals("users",observed.path("name").asText());
            assertTrue(observed.path("columns").isEmpty());assertEquals(2,observed.path("sampledDocuments").asInt());
            assertTrue(observed.path("fieldObservations").size()>=3);assertFalse(snapshot.toString().contains("alpha"));
            assertFalse(snapshot.path("inventoryComplete").asBoolean());
            assertThrows(java.util.concurrent.CancellationException.class,()->NativeReadExecutor.execute(lease,target,command,new NativeReadExecutor.Limits(100,65536,10),()->true));
            assertThrows(IllegalArgumentException.class,()->NativeReadExecutor.execute(lease,target,Profiles.JSON.readTree("{\"dropDatabase\":1}"),new NativeReadExecutor.Limits(100,65536,10),()->false));
            // Exercise more than two raw batches; large documents must never be
            // expanded into a retained Java graph merely because batching is enabled.
            String large="x".repeat(300000);
            for(int i=0;i<40;i++)collection.insertOne(new Document("large",true).append("payload",large));
            JsonNode oversized=NativeReadExecutor.execute(lease,target,Profiles.JSON.readTree("{\"find\":\"users\",\"filter\":{\"large\":true}}"),new NativeReadExecutor.Limits(100,65536,10),()->false);
            assertEquals(40,oversized.path("rowCount").asInt());assertTrue(oversized.path("truncated").asBoolean());
            for(JsonNode entry:oversized.path("entries")){assertTrue(entry.path("documentOmitted").asBoolean());assertFalse(entry.has("payload"));}
        } finally { db.drop(); }
    }

    private void redis(NativeConnections.Lease lease,NativeTarget target)throws Exception {
        String prefix="cg-native:"+UUID.randomUUID()+":";
        byte[] key=(prefix+"large").getBytes(StandardCharsets.UTF_8),binary=(prefix+"binary").getBytes(StandardCharsets.UTF_8);
        try(var connection=lease.redis.connect(ByteArrayCodec.INSTANCE)) {
            connection.sync().set(key,"x".repeat(16384).getBytes(StandardCharsets.UTF_8));
            connection.sync().set(binary,new byte[]{(byte)0xff,0,1});
            try {
                var snapshot=NativeSchemaObservations.capture(lease,target,"owned-snapshot",100,1<<20,10,0,()->false);
                assertTrue(snapshot.path("objects").size()>=2);assertFalse(snapshot.toString().contains("AP8B"));
                assertTrue(snapshot.path("objects").findValuesAsText("nativeType").contains("string"));
                assertFalse(snapshot.path("inventoryComplete").asBoolean());
                var limits=new NativeReadExecutor.Limits(100,65536,10);
                ObjectNode result=NativeReadExecutor.execute(lease,target,Profiles.JSON.createArrayNode().add("GET").add(prefix+"large"),limits,()->false);
                JsonNode value=result.path("entries").get(0);assertTrue(value.path("truncated").asBoolean());assertEquals(8192,value.path("previewBytes").asInt());
                result=NativeReadExecutor.execute(lease,target,Profiles.JSON.createArrayNode().add("GET").add(prefix+"binary"),limits,()->false);
                assertEquals("binary",result.path("entries").get(0).path("encoding").asText());
                assertEquals("/wAB",result.path("entries").get(0).path("base64").asText());
                result=NativeReadExecutor.execute(lease,target,Profiles.JSON.createArrayNode().add("SCAN").add("0").add("MATCH").add(prefix+"*"),limits,()->false);
                assertTrue(result.has("nextCursor"));
                byte[] hash=(prefix+"hash").getBytes(StandardCharsets.UTF_8),list=(prefix+"list").getBytes(StandardCharsets.UTF_8),
                        set=(prefix+"set").getBytes(StandardCharsets.UTF_8),sorted=(prefix+"sorted").getBytes(StandardCharsets.UTF_8),stream=(prefix+"stream").getBytes(StandardCharsets.UTF_8);
                byte[] field="field".getBytes(StandardCharsets.UTF_8),item="item".getBytes(StandardCharsets.UTF_8);
                try{
                    var redis=connection.sync();redis.hset(hash,field,item);redis.rpush(list,item);redis.sadd(set,item);redis.zadd(sorted,1.5,item);redis.xadd(stream,Map.of(field,item));
                    for(String[] vector:new String[][]{
                            {"HSCAN",prefix+"hash","0"},{"SSCAN",prefix+"set","0"},{"ZSCAN",prefix+"sorted","0"},
                            {"HGET",prefix+"hash","field"},{"LRANGE",prefix+"list","0","0"},{"ZRANGE",prefix+"sorted","0","0"},
                            {"XRANGE",prefix+"stream","-","+"},{"XREVRANGE",prefix+"stream","+","-"}}){
                        var read=NativeReadExecutor.execute(lease,target,Profiles.JSON.valueToTree(vector),limits,()->false);
                        assertEquals(1,read.path("rowCount").asInt(),Arrays.toString(vector)+read);
                        assertTrue(read.toString().contains("item"),read.toString());
                    }
                    for(String[] vector:new String[][]{{"HLEN",prefix+"hash"},{"LLEN",prefix+"list"},{"SCARD",prefix+"set"},{"ZCARD",prefix+"sorted"},{"XLEN",prefix+"stream"}})
                        assertEquals(1,NativeReadExecutor.execute(lease,target,Profiles.JSON.valueToTree(vector),limits,()->false).path("entries").get(0).asInt());
                    assertThrows(IllegalArgumentException.class,()->NativeReadExecutor.execute(lease,target,Profiles.JSON.valueToTree(new String[]{"LRANGE",prefix+"list","0","-1"}),limits,()->false));
                }finally{connection.sync().del(hash,list,set,sorted,stream);}
                assertThrows(IllegalArgumentException.class,()->NativeReadExecutor.execute(lease,target,Profiles.JSON.createArrayNode().add("SET").add(prefix+"wrong").add("bad"),limits,()->false));
            } finally { connection.sync().del(key,binary); }
        }
    }
}
