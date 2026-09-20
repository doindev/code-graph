package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMongoStreamTest {
    @TempDir Path root;
    static NativeTarget target(String topology){return new NativeTarget(DatabaseTransport.MONGODB,UUID.randomUUID().toString(),"Fixture","fixture","items",topology);}
    static ObjectNode watch(){return Profiles.JSON.createObjectNode().put("watch","items").put("waitMillis",0).put("limit",100);}
    @Test void cursorIntegrityScopeExpiryRestartAndBounds(){
        var clock=new AtomicLong(1000);try(var codec=new MongoStreamCursor(clock::get);var other=new MongoStreamCursor(clock::get)){
            var position=new BsonDocument("_data",new BsonString("private-position"));String token=codec.encode("scope","collection-id",position);
            assertFalse(token.contains("private-position"));assertEquals(position,codec.decode(token,"scope").resume());assertEquals("collection-id",codec.decode(token,"scope").collection());
            assertThrows(IllegalArgumentException.class,()->codec.decode(token,"other-owner-or-binding"));assertThrows(IllegalArgumentException.class,()->other.decode(token,"scope"));
            String changed=token.substring(0,20)+(token.charAt(20)=='a'?'b':'a')+token.substring(21);assertThrows(IllegalArgumentException.class,()->codec.decode(changed,"scope"));
            assertThrows(IllegalArgumentException.class,()->codec.decode("mcs1."+"a".repeat(7000),"scope"));
            assertThrows(IllegalArgumentException.class,()->codec.encode("scope","collection-id",new BsonDocument("_data",new BsonString("x".repeat(4096)))));
            clock.addAndGet(300000);assertThrows(IllegalArgumentException.class,()->codec.decode(token,"scope"));
            other.close();assertThrows(IllegalStateException.class,()->other.encode("scope","collection-id",position));assertThrows(IllegalArgumentException.class,()->other.decode(token,"scope"));
        }
    }
    @Test void structuralValidationDoesNotPermitCrossNamespaceOrExecutableOptions()throws Exception{
        var target=target("replica_set");assertEquals("mongo.watch",NativeCommand.classify(target,watch()).category());assertTrue(NativeCommand.classify(target,watch()).reusableRead());
        for(String bad:List.of("{\"watch\":\"other\"}","{\"watch\":1}","{\"watch\":\"items\",\"pipeline\":[{\"$out\":\"other\"}]}","{\"watch\":\"items\",\"resumeAfter\":{}}","{\"watch\":\"items\",\"fullDocument\":\"updateLookup\"}","{\"watch\":\"items\",\"lsid\":{}}","{\"watch\":\"items\",\"waitMillis\":10001}","{\"watch\":\"items\",\"limit\":101}","{\"watch\":\"items\",\"limit\":0}","{\"watch\":\"items\",\"cursor\":null}"))assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,Profiles.JSON.readTree(bad)),bad);
        for(String topology:List.of("standalone","srv"))assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target(topology),watch()));
    }
    @Test void readPreparationIsLazyAndDoesNotAllocateSubscriptions()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,5),_->true);var ops=new NativeOperations(profiles,jobs)){
            var draft=Profiles.JSON.createObjectNode().put("name","Watch").put("templateId","mongodb-native").put("url","mongodb://localhost:1");draft.putObject("nativeOptions").put("database","fixture").put("topology","replica_set").put("replicaSet","fixture");
            var profile=profiles.put(null,draft);var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Watch").put("database","fixture").put("collection","items");input.set("command",watch());
            var review=ops.prepareBrowser("human",input);assertFalse(review.path("mutation").asBoolean());assertFalse(review.has("id"));assertFalse(review.path("eligiblePersistentRead").asBoolean());
            assertEquals(0,ops.telemetry().path("clients").asInt());assertEquals(0,ops.telemetry().path("activeChangeStreamCursors").asInt());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
    @Test void failureClassificationNeverDisguisesHistoryLossAsAnEmptyBatch(){
        for(int code:List.of(136,237,260,280,286))assertEquals("history_lost",NativeMongoStreams.failureReason(new com.mongodb.MongoException(code,"private-server-message"),false));
        assertEquals("read_failed",NativeMongoStreams.failureReason(new com.mongodb.MongoException(13,"permission denied"),false));
        assertEquals("cancelled",NativeMongoStreams.failureReason(new com.mongodb.MongoException(286,"interrupted"),true));
    }
    @Test @Timeout(150) void ownedTopologyResumeBackpressureCancellationAndInvalidation()throws Exception{
        String port=System.getenv("MONGO_TOPOLOGY_PORT"),topology=Objects.toString(System.getenv("MONGO_TOPOLOGY_KIND"),"");
        Assumptions.assumeTrue(port!=null&&Set.of("replica_set","sharded").contains(topology),"Owned MongoDB topology required");assertTrue(Objects.toString(System.getenv("MONGO_TOPOLOGY_OWNER"),"").startsWith("cgraph-mongo-topology-"));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,192L<<20,2,100,100,20),_->true);var clients=new NativeConnections(profiles);var ops=new NativeOperations(profiles,jobs)){
            var draft=Profiles.JSON.createObjectNode().put("name","Streams").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port);
            var options=draft.putObject("nativeOptions").put("database","change_streams").put("topology",topology).put("readPreference","secondary").put("maximumPoolSize",2);if(topology.equals("replica_set"))options.put("replicaSet","cgraph");
            var profile=profiles.put(null,draft);var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Streams").put("database","change_streams").put("collection","items");input.set("command",watch());
            try(var lease=clients.acquire(profile.path("id").asText())){
                var db=lease.mongo.getDatabase("change_streams").withReadPreference(com.mongodb.ReadPreference.primary());db.createCollection("items");var items=db.getCollection("items");
                var empty=page(jobs,ops,"human",input);assertEquals(0,empty.path("entries").size());assertTrue(empty.has("nextCursor"),empty.toString());assertFalse(empty.path("snapshot").asBoolean());
                for(int i=0;i<3;i++)items.insertOne(new Document("_id",i).append("largeInteger",9007199254740993L));
                next(input,empty,2,10000);var first=page(jobs,ops,"human",input);assertEquals(2,first.path("entries").size(),first.toPrettyString());assertEquals("event_limit",first.path("stopReason").asText());assertTrue(first.toString().contains("$numberLong"));
                // Router ordering can wait for cold-shard/config-server progress. Wait for
                // exactly the known event count, not an assumption of immediate delivery.
                next(input,first,1,10000);var last=page(jobs,ops,"human",input);assertEquals(1,last.path("entries").size(),last.toPrettyString());assertNotEquals(first.path("entries").get(1).path("_id"),last.path("entries").get(0).path("_id"));
                next(input,last,100,10000);var stolen=ConnectionSetupTest.await(jobs,"other",ops.submit("other",ops.prepare(input),input,()->{}));assertEquals("failed",stolen.path("state").asText());jobs.remove("other",stolen.path("id").asText());
                // A large event is not silently acknowledged. Repeating the returned continuation
                // encounters the same obstruction rather than skipping to the subsequent event.
                items.insertOne(new Document("_id",10).append("value","x".repeat(300000)));items.insertOne(new Document("_id",11));
                var large=page(jobs,ops,"human",input);assertEquals("event_too_large",large.path("stopReason").asText());assertEquals(0,large.path("entries").size());next(input,large,100,10000);assertEquals("event_too_large",page(jobs,ops,"human",input).path("stopReason").asText());
                // Explicit new watch abandons that history; there is no automatic fallback.
                input.set("command",watch());var fresh=page(jobs,ops,"agent:test",input);
                for(int i=20;i<28;i++)items.insertOne(new Document("_id",i).append("value","v".repeat(200000)));
                next(input,fresh,100,10000);var bounded=page(jobs,ops,"agent:test",input);assertEquals("byte_limit",bounded.path("stopReason").asText(),bounded.path("rowCount").toString());assertTrue(Profiles.JSON.writeValueAsBytes(bounded).length<=1<<20);
                next(input,bounded,8-bounded.path("rowCount").asInt(),10000);var remaining=page(jobs,ops,"agent:test",input);assertEquals(8,bounded.path("rowCount").asInt()+remaining.path("rowCount").asInt());
                input.set("command",watch().put("waitMillis",10000));var running=ops.submit("human",ops.prepare(input),input,()->{});awaitCursor(ops);jobs.cancel(jobs.require("human",running.path("id").asText()));var cancelled=ConnectionSetupTest.await(jobs,"human",running);assertEquals("cancelled",cancelled.path("state").asText());jobs.remove("human",cancelled.path("id").asText());assertEquals(0,ops.telemetry().path("activeChangeStreamCursors").asInt());
                var allowed=new AtomicBoolean(true);var revoked=ops.submit("human",ops.prepare(input),input,()->{if(!allowed.get())throw new SecurityException("Revoked");});awaitCursor(ops);allowed.set(false);var denied=ConnectionSetupTest.await(jobs,"human",revoked);assertEquals("failed",denied.path("state").asText());assertFalse(denied.has("result"));jobs.remove("human",denied.path("id").asText());
                var waiting=ops.submit("human",ops.prepare(input),input,()->{});awaitCursor(ops);Thread.sleep(300);items.drop();var invalid=ConnectionSetupTest.await(jobs,"human",waiting);assertEquals("complete",invalid.path("state").asText(),invalid.toPrettyString());assertEquals("invalidated",invalid.path("result").path("stopReason").asText());assertFalse(invalid.path("result").has("nextCursor"));jobs.remove("human",invalid.path("id").asText());
                db.createCollection("items");next(input,last,100,0);var replaced=ConnectionSetupTest.await(jobs,"human",ops.submit("human",ops.prepare(input),input,()->{}));assertEquals("failed",replaced.path("state").asText());jobs.remove("human",replaced.path("id").asText());
            }
            assertEquals(0,ops.telemetry().path("activeLeases").asInt());assertEquals(0,ops.telemetry().path("activeChangeStreamCursors").asInt());assertEquals(0,ops.telemetry().path("retainedChangeSubscriptions").asInt());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
    static ObjectNode page(QueryJobs jobs,NativeOperations ops,String owner,ObjectNode input)throws Exception{var done=ConnectionSetupTest.await(jobs,owner,ops.submit(owner,ops.prepare(input),input,()->{}));assertEquals("complete",done.path("state").asText(),done.toPrettyString());var value=(ObjectNode)done.path("result");jobs.remove(owner,done.path("id").asText());return value;}
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true}) @Timeout(90)
    void ownedAgentBatchesUseNormalApprovalsOrExplicitYolo(boolean automatic)throws Exception{
        String port=System.getenv("MONGO_TOPOLOGY_PORT"),topology=Objects.toString(System.getenv("MONGO_TOPOLOGY_KIND"),"");
        Assumptions.assumeTrue(port!=null&&Set.of("replica_set","sharded").contains(topology),"Owned MongoDB topology required");assertTrue(Objects.toString(System.getenv("MONGO_TOPOLOGY_OWNER"),"").startsWith("cgraph-mongo-topology-"));
        var authorization=new AgentAuthorization(automatic,root);var sessions=new McpSessions(System::currentTimeMillis);authorization.sessions(sessions);var agents=new AgentAccess(root,authorization);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var contexts=new ProjectContexts(profiles,jdbc,agents,System::currentTimeMillis,false);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,20),_->true);var clients=new NativeConnections(profiles);var ops=new NativeOperations(profiles,jobs,contexts);var requests=new AgentRequests(profiles,jdbc,new ConnectionSetup(profiles,jobs),contexts,agents,jobs)){
            requests.nativeOperations(ops);requests.sessions(sessions);String principal=agents.trustedLocal(),session=UUID.randomUUID().toString();sessions.register(session,principal,System.currentTimeMillis()+300000);
            var draft=Profiles.JSON.createObjectNode().put("name","Agent stream").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port);var options=draft.putObject("nativeOptions").put("database","agent_streams").put("topology",topology);if(topology.equals("replica_set"))options.put("replicaSet","cgraph");
            var profile=profiles.put(null,draft);String id=profile.path("id").asText(),collection="items_"+automatic;
            try(var observer=clients.acquire(id)){
                var db=observer.mongo.getDatabase("agent_streams");db.createCollection(collection);String cursor=null;
                for(int batch=0;batch<2;batch++){
                    var input=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Agent stream").put("database","agent_streams").put("collection",collection).put("requestId",UUID.randomUUID().toString()).put("purpose","Owned change-stream verification");
                    var command=input.putObject("command").put("watch",collection).put("limit",1).put("waitMillis",batch==0?0:10000);if(cursor!=null)command.put("cursor",cursor);
                    var notifications=new AtomicInteger();requests.onPending(_->notifications.incrementAndGet());var submitted=requests.request(principal,session,"native_command",input);
                    if(automatic){assertEquals("automatic",submitted.path("approvalChannel").asText());assertEquals(0,notifications.get());}
                    else{assertEquals("awaiting_approval",submitted.path("state").asText());assertEquals(1,notifications.get());submitted=requests.decide("human",submitted.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode());}
                    String jobId=submitted.path("jobId").asText();assertFalse(jobId.isBlank(),submitted.toString());var done=ConnectionSetupTest.await(jobs,"agent:"+principal,Profiles.JSON.createObjectNode().put("id",jobId));assertEquals("complete",done.path("state").asText(),done.toPrettyString());assertEquals(batch,done.path("result").path("rowCount").asInt());cursor=done.path("result").path("nextCursor").asText();assertFalse(cursor.isBlank());jobs.remove("agent:"+principal,jobId);
                    if(batch==0)db.getCollection(collection).insertOne(new Document("_id","observed"));
                }
                assertEquals(1,db.getCollection(collection).countDocuments());assertTrue(agents.list().get(0).path("grants").isEmpty());assertTrue(agents.list().get(0).path("readPolicies").isEmpty());assertEquals(0,ops.telemetry().path("activeChangeStreamCursors").asInt());
            }
        }
    }
    static void next(ObjectNode input,JsonNode page,int limit,int wait){input.set("command",watch().put("limit",limit).put("waitMillis",wait).put("cursor",page.path("nextCursor").asText()));}
    static void awaitCursor(NativeOperations ops)throws Exception{long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);while(ops.telemetry().path("activeChangeStreamCursors").asInt()==0){if(System.nanoTime()>deadline)fail("Stream did not become active");Thread.sleep(10);}}
}
