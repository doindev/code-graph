package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.*;
import org.bson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeMongoTransactionTest {
    @TempDir Path root;
    private static NativeTarget target(String topology){return new NativeTarget(DatabaseTransport.MONGODB,UUID.randomUUID().toString(),"Fixture","fixture","items",topology);}
    private static JsonNode json(String value)throws Exception{return Profiles.JSON.readTree(value);}
    private static ObjectNode batch(String collection,String key){
        var command=Profiles.JSON.createObjectNode();
        command.putArray("transaction").addObject().put("insert",collection).putArray("documents").addObject().put("_id",key).put("value",1);return command;
    }
    @Test void strictScopeBoundsAndClassification()throws Exception{
        var selected=target("replica_set");
        for(String invalid:List.of("{\"transaction\":[]}","{\"transaction\":null}","{\"transaction\":[[\"SET\",\"a\",\"b\"]]}",
                "{\"transaction\":[{\"find\":\"items\"}]}","{\"transaction\":[{\"drop\":\"items\"}]}",
                "{\"transaction\":[{\"insert\":\"elsewhere\",\"documents\":[{\"x\":1}]}]}",
                "{\"transaction\":[{\"insert\":\"items\",\"documents\":[{\"x\":1}],\"ordered\":false}]}",
                "{\"transaction\":[{\"insert\":\"items\",\"documents\":[{\"x\":1}],\"lsid\":{}}]}",
                "{\"transaction\":[{\"delete\":\"items\",\"deletes\":[{\"q\":{},\"limit\":1}]}]}",
                "{\"transaction\":[{\"delete\":\"items\",\"deletes\":[{\"q\":{\"$where\":\"return true\"},\"limit\":1}]}]}",
                "{\"transaction\":[{\"update\":\"items\",\"updates\":[{\"q\":{\"_id\":1},\"u\":[{\"$set\":{\"x\":{\"$function\":{\"body\":\"return 1\",\"args\":[],\"lang\":\"js\"}}}}]}]}]}",
                "{\"transaction\":[{\"update\":\"items\",\"updates\":[{\"q\":{\"x\":1},\"u\":{\"$set\":{\"x\":2}},\"multi\":true}]}]}",
                "{\"transaction\":[{\"transaction\":[]}]}","{\"transaction\":[{\"create\":\"items\"}],\"commit\":true}"))
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,json(invalid)),invalid);
        var command=batch("items","1");assertEquals("mongo.transaction",NativeCommand.classify(selected,command).category());
        assertFalse(NativeCommand.classify(selected,command).reusableRead());
        command.withArray("transaction").addObject().put("delete","items").putArray("deletes").addObject().put("limit",1).putObject("q").put("_id","1");
        assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(selected,command).effect());
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target("standalone"),command));
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target("srv"),command));
        var maximum=batch("items","0");for(int i=1;i<32;i++)maximum.withArray("transaction").add(batch("items",""+i).path("transaction").get(0));
        NativeMutations.validate(selected,maximum);maximum.withArray("transaction").add(maximum.path("transaction").get(0).deepCopy());assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,maximum));
        var writes=batch("items","0");var documents=(com.fasterxml.jackson.databind.node.ArrayNode)writes.path("transaction").get(0).path("documents");for(int i=1;i<100;i++)documents.addObject().put("_id",""+i);
        NativeMutations.validate(selected,writes);writes.withArray("transaction").add(batch("items","101").path("transaction").get(0));assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,writes));
    }
    @Test void topologyCollectionAndReviewGuards()throws Exception{
        var replica=RawBsonDocument.parse("{setName:'fixture',isWritablePrimary:true,maxWireVersion:25,logicalSessionTimeoutMinutes:30}");
        NativeMongoTransactions.checkTopology(target("replica_set"),replica);
        assertThrows(IllegalArgumentException.class,()->NativeMongoTransactions.checkTopology(target("sharded"),replica));
        for(String source:List.of("{type:'view'}","{type:'collection',options:{capped:true}}","{type:'collection',options:{timeseries:{timeField:'at'}}}"))assertThrows(IllegalArgumentException.class,()->NativeMongoTransactions.checkCollection(RawBsonDocument.parse(source)));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var draft=Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://localhost:1").put("readOnly",false);
            draft.putObject("nativeOptions").put("database","fixture").put("topology","replica_set").put("replicaSet","fixture");
            var profile=profiles.put(null,draft);var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Mongo").put("database","fixture").put("collection","items");input.set("command",batch("items","1"));
            var review=ops.prepareBrowser("human",input);assertTrue(review.path("transactionNotice").asText().contains("atomic"));assertFalse(review.path("eligiblePersistentRead").asBoolean());
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            input.set("command",batch("items","2"));assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));
            ops.discardBrowser("human",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            var limited=jobs.new Job("human",profile.path("id").asText());limited.rowLimit=0;
            assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(null,target("replica_set"),batch("items","1"),limited,()->fail("No writes before admission")));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));assertThrows(IllegalArgumentException.class,()->ops.prepare(input));
        }
    }
    @Test @Timeout(150) void ownedReplicaOrShardedAtomicCrudRollbackAndUnknownCommit()throws Exception{
        String port=System.getenv("MONGO_TOPOLOGY_PORT"),topology=Objects.toString(System.getenv("MONGO_TOPOLOGY_KIND"),"");
        Assumptions.assumeTrue(port!=null&&Set.of("replica_set","sharded").contains(topology),"Owned MongoDB topology required");
        assertTrue(Objects.toString(System.getenv("MONGO_TOPOLOGY_OWNER"),"").startsWith("cgraph-mongo-topology-"));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,20),_->true);var clients=new NativeConnections(profiles);var ops=new NativeOperations(profiles,jobs)){
            var draft=Profiles.JSON.createObjectNode().put("name","Transactions").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("database","transactions").put("topology",topology).put("readPreference","secondary").put("maximumPoolSize",2).put("connectTimeoutMS",3000).put("socketTimeoutMS",5000);if(topology.equals("replica_set"))options.put("replicaSet","cgraph");
            var profile=profiles.put(null,draft);var selected=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Transactions").put("database","transactions").put("collection","items"));
            try(var lease=clients.acquire(selected.connectionId())){
                var db=lease.mongo.getDatabase("transactions").withReadPreference(com.mongodb.ReadPreference.primary());db.createCollection("items");var collection=db.getCollection("items");
                if(topology.equals("sharded"))lease.mongo.getDatabase("admin").runCommand(new Document("shardCollection","transactions.items").append("key",new Document("_id",1)));
                NativeMutations.execute(lease,selected,batch("items","driver-probe"),jobs.new Job("human",selected.connectionId()),()->{});
                collection.deleteOne(new Document("_id","driver-probe"));
                var command=batch("items","first");command.withArray("transaction").add(json("{\"update\":\"items\",\"updates\":[{\"q\":{\"_id\":\"first\",\"value\":1},\"u\":{\"$set\":{\"value\":2}}}]}"));
                var input=selected.json();input.set("command",command);var review=ops.prepareBrowser("human",input);
                var completed=ConnectionSetupTest.await(jobs,"human",ops.applyBrowser("human",review.path("id").asText()));assertEquals("complete",completed.path("state").asText(),completed.toPrettyString());assertEquals("commit_acknowledged",completed.path("result").path("outcome").asText());assertEquals(2,collection.find().first().getInteger("value"));jobs.remove("human",completed.path("id").asText());
                var duplicate=batch("items","rollback");duplicate.withArray("transaction").add(batch("items","first").path("transaction").get(0));var failed=jobs.new Job("human",selected.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,selected,duplicate,failed,()->{}));assertEquals("rollback_acknowledged",failed.outcome);assertNull(collection.find(new Document("_id","rollback")).first());
                var conflict=batch("items","conflict");conflict.withArray("transaction").add(json("{\"delete\":\"items\",\"deletes\":[{\"q\":{\"_id\":\"missing\"},\"limit\":1}]}"));var missed=jobs.new Job("human",selected.connectionId());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,selected,conflict,missed,()->{}));assertEquals("rollback_acknowledged",missed.outcome);assertNull(collection.find(new Document("_id","conflict")).first());
                for(boolean revoke:List.of(false,true)){
                    var interrupted=jobs.new Job("human",selected.connectionId());var checks=new AtomicInteger();
                    assertThrows(RuntimeException.class,()->NativeMutations.execute(lease,selected,batch("items","cancel"),interrupted,()->{if(checks.incrementAndGet()==3){if(revoke)throw new SecurityException("revoked");interrupted.cancelled=true;}}));
                    assertEquals("rollback_acknowledged",interrupted.outcome);assertNull(collection.find(new Document("_id","cancel")).first());
                }
                var commits=new AtomicInteger();MongoClient fault=faultClient(lease.mongo,commits);
                try(var lost=new NativeConnections.Lease(fault,null,null,new byte[32],lease.revision,()->{})){
                    var unknown=jobs.new Job("human",selected.connectionId());var error=assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lost,selected,batch("items","uncertain"),unknown,()->{}));
                    assertEquals(1,commits.get());assertEquals("commit_unknown",unknown.outcome);assertFalse(error.getMessage().contains("secret-sentinel"));assertNotNull(collection.find(new Document("_id","uncertain")).first());
                }
                var maximum=batch("items","max0");for(int i=1;i<32;i++)maximum.withArray("transaction").add(batch("items","max"+i).path("transaction").get(0));
                assertEquals(32,NativeMutations.execute(lease,selected,maximum,jobs.new Job("human",selected.connectionId()),()->{}).path("entries").size());
                var deleted=Profiles.JSON.createObjectNode();deleted.putArray("transaction").add(json("{\"delete\":\"items\",\"deletes\":[{\"q\":{\"_id\":\"first\"},\"limit\":1}]}"));
                NativeMutations.execute(lease,selected,deleted,jobs.new Job("human",selected.connectionId()),()->{});assertNull(collection.find(new Document("_id","first")).first());
                db.createView("view","items",List.of());var viewTarget=new NativeTarget(selected.transport(),selected.connectionId(),selected.connectionName(),selected.database(),"view",selected.topology());
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,viewTarget,batch("view","x"),jobs.new Job("human",selected.connectionId()),()->fail("Preflight before writes")));
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @Timeout(90) void ownedAgentReviewAndAutomaticAuthorization(boolean automatic)throws Exception{
        String port=System.getenv("MONGO_TOPOLOGY_PORT"),topology=Objects.toString(System.getenv("MONGO_TOPOLOGY_KIND"),"");
        Assumptions.assumeTrue(port!=null&&Set.of("replica_set","sharded").contains(topology),"Owned MongoDB topology required");
        assertTrue(Objects.toString(System.getenv("MONGO_TOPOLOGY_OWNER"),"").startsWith("cgraph-mongo-topology-"));
        var authorization=new AgentAuthorization(automatic,root);var sessions=new McpSessions(System::currentTimeMillis);authorization.sessions(sessions);
        var agents=new AgentAccess(root,authorization);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var contexts=new ProjectContexts(profiles,jdbc,agents,System::currentTimeMillis,false);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,20),_->true);var clients=new NativeConnections(profiles);
            var ops=new NativeOperations(profiles,jobs,contexts);var requests=new AgentRequests(profiles,jdbc,new ConnectionSetup(profiles,jobs),contexts,agents,jobs)){
            requests.nativeOperations(ops);requests.sessions(sessions);String principal=agents.trustedLocal(),session=UUID.randomUUID().toString();sessions.register(session,principal,System.currentTimeMillis()+300000);
            var draft=Profiles.JSON.createObjectNode().put("name","Agent transaction").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("database","agent_transactions").put("topology",topology);if(topology.equals("replica_set"))options.put("replicaSet","cgraph");
            var profile=profiles.put(null,draft);String id=profile.path("id").asText(),collection="items_"+automatic;
            try(var observer=clients.acquire(id)){
                var db=observer.mongo.getDatabase("agent_transactions");db.createCollection(collection);
                var input=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Agent transaction").put("database","agent_transactions").put("collection",collection).put("requestId",UUID.randomUUID().toString()).put("purpose","Owned transaction verification");input.set("command",batch(collection,"reviewed"));
                var notifications=new AtomicInteger();requests.onPending(_->notifications.incrementAndGet());
                var submitted=requests.request(principal,session,"native_command",input);
                if(automatic){assertEquals("automatic",submitted.path("approvalChannel").asText());assertEquals(0,notifications.get());}
                else{
                    assertEquals("awaiting_approval",submitted.path("state").asText());assertEquals(0,db.getCollection(collection).countDocuments());
                    String requestId=submitted.path("id").asText();assertEquals(1,notifications.get());
                    assertThrows(IllegalArgumentException.class,()->requests.decide("human",requestId,"always_allow",true,Profiles.JSON.createObjectNode()));
                    submitted=requests.decide("human",requestId,"approve_once",true,Profiles.JSON.createObjectNode());
                }
                String jobId=submitted.path("jobId").asText();assertFalse(jobId.isBlank(),submitted.toPrettyString());
                var done=ConnectionSetupTest.await(jobs,"agent:"+principal,Profiles.JSON.createObjectNode().put("id",jobId));assertEquals("complete",done.path("state").asText(),done.toPrettyString());assertEquals("commit_acknowledged",done.path("result").path("outcome").asText());
                assertEquals(1,db.getCollection(collection).countDocuments());jobs.remove("agent:"+principal,jobId);
                if(topology.equals("replica_set")){
                    var selected=NativeTarget.resolve(profile,input);
                    var definition=MongoCollectionMetadata.load(observer,selected,jobs.new Job("human",id));
                    String uuid=Base64.getEncoder().encodeToString(definition.getDocument("info").getBinary("uuid").getData());
                    BsonDocument original=db.getCollection(collection,BsonDocument.class).find().first();
                    var replacement=original.clone().append("edited",BsonBoolean.TRUE);
                    var edit=input.deepCopy().put("requestId",UUID.randomUUID().toString());
                    edit.set("command",NativeMongoDocumentTest.guarded(collection,NativeMongoDocumentTest.canonical(original),NativeMongoDocumentTest.canonical(replacement),uuid));
                    var proposed=requests.request(principal,session,"native_command",edit);
                    if(!automatic){
                        assertEquals("awaiting_approval",proposed.path("state").asText());
                        assertFalse(db.getCollection(collection,BsonDocument.class).find().first().containsKey("edited"));
                        String approvalId=proposed.path("id").asText();
                        assertThrows(IllegalArgumentException.class,()->requests.decide("human",approvalId,"always_allow",true,Profiles.JSON.createObjectNode()));
                        proposed=requests.decide("human",approvalId,"approve_once",true,Profiles.JSON.createObjectNode());
                    }else assertEquals("automatic",proposed.path("approvalChannel").asText());
                    var edited=ConnectionSetupTest.await(jobs,"agent:"+principal,Profiles.JSON.createObjectNode().put("id",proposed.path("jobId").asText()));
                    assertEquals("complete",edited.path("state").asText(),edited.toPrettyString());
                    assertTrue(edited.path("result").path("documentGuard").asBoolean());
                    assertTrue(NativeMongoDocuments.same(replacement,db.getCollection(collection,BsonDocument.class).find().first()));
                    jobs.remove("agent:"+principal,edited.path("id").asText());
                }
                sessions.remove(session);var expired=input.deepCopy().put("requestId",UUID.randomUUID().toString());
                if(automatic)assertThrows(SecurityException.class,()->requests.request(principal,session,"native_command",expired));
                assertTrue(agents.list().get(0).path("readPolicies").isEmpty());assertTrue(agents.list().get(0).path("grants").isEmpty());
            }
        }
    }
    // A test-only transport boundary loses the response AFTER the real server committed.
    // No second commit or CRUD replay is allowed, even though reconciliation proves success.
    static MongoClient faultClient(MongoClient delegate,AtomicInteger commits){
        return (MongoClient)Proxy.newProxyInstance(MongoClient.class.getClassLoader(),new Class[]{MongoClient.class},(_,method,args)->{
            Object result=invoke(delegate,method,args);return result instanceof MongoDatabase db?faultDatabase(db,commits):result;
        });
    }
    private static MongoDatabase faultDatabase(MongoDatabase delegate,AtomicInteger commits){
        return (MongoDatabase)Proxy.newProxyInstance(MongoDatabase.class.getClassLoader(),new Class[]{MongoDatabase.class},(_,method,args)->{
            boolean commit=method.getName().equals("runCommand")&&args.length>1&&args[1] instanceof BsonDocument command&&command.containsKey("commitTransaction");
            if(commit)commits.incrementAndGet();Object result=invoke(delegate,method,args);
            if(commit)throw new IllegalArgumentException("secret-sentinel transport loss after commit");
            return result instanceof MongoDatabase db?faultDatabase(db,commits):result;
        });
    }
    private static Object invoke(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException failure){throw failure.getCause();}}
}
