package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMongoRenameTest {
    @TempDir Path root;
    private final NativeTarget target=new NativeTarget(DatabaseTransport.MONGODB,UUID.randomUUID().toString(),"Mongo","app","items","standalone");
    private ObjectNode command(String source,String destination){return Profiles.JSON.createObjectNode().put("renameCollection",source).put("to",destination).put("dropTarget",false);}

    @Test void sameDatabaseRenameIsDestructiveAndNeverReusable(){
        var command=command("app.items","app.items.v2");
        var classification=NativeCommand.classify(target,command);
        assertEquals(NativeCommand.Effect.DESTRUCTIVE,classification.effect());assertFalse(classification.reusableRead());
        NativeMutations.validate(target,command);
        command.remove("dropTarget");NativeMutations.validate(target,command);
    }

    @Test void classifierRejectsNamespaceEscapesReplacementAndInvalidTypes(){
        var bad=new ArrayList<ObjectNode>();
        for(String source:List.of("items","other.items","app.other","app.items.extra"))bad.add(command(source,"app.next"));
        for(String destination:List.of("other.items","app2.next","app.","app.items","app.system.users","app.$cmd","app.next\n","app."+"é".repeat(120)))bad.add(command("app.items",destination));
        bad.add(command("app.items","app.next").put("dropTarget",true));
        bad.add(command("app.items","app.next").put("dropTarget","false"));
        bad.add(command("app.items","app.next").putNull("to"));
        bad.add(command("app.items","app.next").put("writeConcern","majority"));
        bad.add(command("app.items","app.next").put("$db","admin"));
        bad.add(command("app.items","app.next").put("comment","unreviewed option"));
        for(var command:bad){
            assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,command),command.toString());
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,command),command.toString());
        }
        for(String database:List.of("admin","config","local")){
            var system=new NativeTarget(target.transport(),target.connectionId(),target.connectionName(),database,"items",target.topology());
            assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(system,command(database+".items",database+".next")));
        }
        var noCollection=new NativeTarget(target.transport(),target.connectionId(),target.connectionName(),"app","","standalone");
        assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(noCollection,command("app.items","app.next")));
    }

    @Test void reviewShowsBothNamespacesAndKeepsOwnershipRevisionAndReadOnlyChecks()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,5),owner->true);
            var operations=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Mongo").put("database","app").put("collection","items");
            input.set("command",command("app.items","app.next"));
            var review=operations.prepareBrowser("human",input);String id=review.path("id").asText();
            assertTrue(review.path("destructive").asBoolean());assertFalse(review.path("eligiblePersistentRead").asBoolean());
            assertEquals("app.items",review.path("affectedNamespaces").get(0).asText());assertEquals("app.next",review.path("affectedNamespaces").get(1).asText());
            assertTrue(review.path("transactionNotice").asText().contains("cursors"));
            assertEquals(0,operations.telemetry().path("clients").asInt(),"Preparing review must not connect or rename");
            assertThrows(SecurityException.class,()->operations.applyBrowser("other",id));
            var changed=input.deepCopy();changed.withObject("command").put("to","app.different");
            assertThrows(IllegalArgumentException.class,()->operations.validate(review,changed));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("name","Mongo").put("readOnly",true));
            assertThrows(IllegalArgumentException.class,()->operations.applyBrowser("human",id));
            assertThrows(IllegalArgumentException.class,()->operations.prepareBrowser("human",input));
            operations.discardBrowser("human",id);assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            assertThrows(SecurityException.class,()->operations.applyBrowser("human",id));
        }
    }

    private String fixturePort(){
        Assumptions.assumeTrue("mongodb".equals(System.getenv("NATIVE_TEST_ENGINE")),"Disposable MongoDB fixture not configured");
        assertTrue(Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"").startsWith("cgraph-native-"));
        return Objects.requireNonNull(System.getenv("NATIVE_TEST_PORT"));
    }

    @Test @Timeout(90) void liveBrowserRenamePreservesDocumentsIndexesAndCappedOptionsWithoutReplacing()throws Exception{
        String port=fixturePort(),database="rename_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,256L<<20,2,100,100,10),owner->true);
            var operations=new NativeOperations(profiles,jobs);var clients=new NativeConnections(profiles)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false));
            try(var lease=clients.acquire(profile.path("id").asText())){
                var db=lease.mongo.getDatabase(database);
                try{
                    for(boolean capped:new boolean[]{false,true}){
                        String source=capped?"capped":"items",destination=source+".renamed";
                        var options=new com.mongodb.client.model.CreateCollectionOptions().capped(capped);if(capped)options.sizeInBytes(1048576);
                        db.createCollection(source,options);
                        var collection=db.getCollection(source);collection.insertOne(new org.bson.Document("_id",1).append("value",9007199254740993L));
                        collection.createIndex(new org.bson.Document("value",1));
                        var before=db.listCollections().filter(new org.bson.Document("name",source)).first();
                        var input=profileTarget(profile,database,source);input.set("command",command(database+"."+source,database+"."+destination));
                        var review=operations.prepareBrowser("human",input);String reviewId=review.path("id").asText();
                        assertEquals(1,collection.countDocuments(),"Review must not perform the rename");
                        assertThrows(SecurityException.class,()->operations.applyBrowser("stranger",reviewId));
                        var done=ConnectionSetupTest.await(jobs,"human",operations.applyBrowser("human",reviewId));
                        assertEquals("complete",done.path("state").asText(),done.toPrettyString());
                        assertEquals(destination,done.path("result").path("renamed").path("to").asText());
                        assertThrows(SecurityException.class,()->operations.applyBrowser("human",reviewId));
                        jobs.remove("human",done.path("id").asText());
                        assertEquals(0,collection.countDocuments());assertEquals(9007199254740993L,db.getCollection(destination).find().first().getLong("value"));
                        var after=db.listCollections().filter(new org.bson.Document("name",destination)).first();
                        assertEquals(before.get("info"),after.get("info"),"Collection UUID must remain unchanged");
                        assertEquals(before.get("options"),after.get("options"));
                        assertEquals(2,db.getCollection(destination).listIndexes().into(new ArrayList<>()).size());
                    }
                    db.getCollection("taken").insertOne(new org.bson.Document("_id","destination-must-survive"));
                    var input=profileTarget(profile,database,"items.renamed");input.set("command",command(database+".items.renamed",database+".taken"));
                    var failed=ConnectionSetupTest.await(jobs,"human",operations.applyBrowser("human",operations.prepareBrowser("human",input).path("id").asText()));
                    assertEquals("failed",failed.path("state").asText(),failed.toPrettyString());
                    assertEquals(1,db.getCollection("items.renamed").countDocuments());assertEquals("destination-must-survive",db.getCollection("taken").find().first().getString("_id"));
                    jobs.remove("human",failed.path("id").asText());
                    db.createView("view","items.renamed",List.of());
                    db.runCommand(new org.bson.Document("create","series").append("timeseries",new org.bson.Document("timeField","at")));
                    for(String source:List.of("view","series","missing")){
                        var unsupported=profileTarget(profile,database,source);unsupported.set("command",command(database+"."+source,database+".must_not_exist"));
                        var result=ConnectionSetupTest.await(jobs,"human",operations.applyBrowser("human",operations.prepareBrowser("human",unsupported).path("id").asText()));
                        assertEquals("failed",result.path("state").asText(),result.toPrettyString());
                        assertEquals("not_started",result.path("outcome").asText());jobs.remove("human",result.path("id").asText());
                    }
                    assertFalse(db.listCollectionNames().into(new ArrayList<>()).contains("must_not_exist"));
                    var source=new NativeTarget(DatabaseTransport.MONGODB,profile.path("id").asText(),"Mongo",database,"items.renamed","standalone");
                    var revoked=ConnectionSetupTest.await(jobs,"human",jobs.local("human",source.connectionId(),job->
                            NativeMutations.execute(lease,source,command(database+".items.renamed",database+".revoked"),job,()->{throw new SecurityException("Session revoked after metadata lookup");}),()->{}));
                    assertEquals("failed",revoked.path("state").asText());assertEquals("not_started",revoked.path("outcome").asText());
                    assertEquals(1,db.getCollection("items.renamed").countDocuments());jobs.remove("human",revoked.path("id").asText());
                }finally{db.drop();}
            }
        }
    }

    @Test @Timeout(90) void liveAgentApprovalAndYoloUseTheSameExactRenameAdapter()throws Exception{
        String port=fixturePort(),database="rename_agent_"+UUID.randomUUID().toString().replace("-","");
        for(boolean automatic:new boolean[]{false,true}){
            Path state=root.resolve(automatic?"automatic":"reviewed");var authorization=new AgentAuthorization(automatic,state);var agents=new AgentAccess(state,authorization);
            var sessions=new McpSessions(System::currentTimeMillis);authorization.sessions(sessions);
            try(var profiles=new Profiles(state,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
                var contexts=new ProjectContexts(profiles,jdbc,agents,System::currentTimeMillis,false);
                var jobs=new QueryJobs(jdbc,new DbaConfig(state,256L<<20,2,100,100,10),owner->true);
                var operations=new NativeOperations(profiles,jobs,contexts);var clients=new NativeConnections(profiles);
                var requests=new AgentRequests(profiles,jdbc,new ConnectionSetup(profiles,jobs),contexts,agents,jobs)){
                requests.nativeOperations(operations);requests.sessions(sessions);String principal=agents.trustedLocal(),session=UUID.randomUUID().toString();
                sessions.register(session,principal,System.currentTimeMillis()+300000);
                var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false));
                try(var lease=clients.acquire(profile.path("id").asText())){
                    var db=lease.mongo.getDatabase(database);
                    try{
                        db.getCollection("before").insertOne(new org.bson.Document("_id",1));
                        var input=profileTarget(profile,database,"before").put("requestId",UUID.randomUUID().toString()).put("purpose","Owned rename test");
                        input.set("command",command(database+".before",database+".after"));
                        var pending=requests.request(principal,session,"native_command",input);
                        if(!automatic){
                            assertEquals("awaiting_approval",pending.path("state").asText());assertEquals(1,db.getCollection("before").countDocuments());
                            String requestId=pending.path("id").asText();
                            assertThrows(IllegalArgumentException.class,()->requests.decide("human",requestId,"always_allow",true,Profiles.JSON.createObjectNode()));
                            pending=requests.decide("human",requestId,"approve_once",true,Profiles.JSON.createObjectNode());
                        }else assertEquals("automatic",pending.path("approvalChannel").asText());
                        var done=ConnectionSetupTest.await(jobs,"agent:"+principal,Profiles.JSON.createObjectNode().put("id",pending.path("jobId").asText()));
                        assertEquals("complete",done.path("state").asText(),done.toPrettyString());
                        assertEquals(1,db.getCollection("after").countDocuments());assertEquals(0,db.getCollection("before").countDocuments());
                        jobs.remove("agent:"+principal,done.path("id").asText());
                    }finally{db.drop();}
                }
            }
        }
    }

    private ObjectNode profileTarget(ObjectNode profile,String database,String collection){
        return Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName",profile.path("name").asText()).put("database",database).put("collection",collection);
    }
}
