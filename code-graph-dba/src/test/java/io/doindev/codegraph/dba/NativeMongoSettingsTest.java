package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeMongoSettingsTest {
    @TempDir Path root;
    private final NativeTarget target=new NativeTarget(DatabaseTransport.MONGODB,UUID.randomUUID().toString(),"Mongo","app","items","standalone");
    private ObjectNode command(String settings)throws Exception{return (ObjectNode)Profiles.JSON.readTree("{\"collMod\":\"items\","+settings+"}");}

    @Test void retentionAndCappedChangesAreDestructiveNotReusable()throws Exception{
        for(String settings:List.of("\"index\":{\"name\":\"expiry\",\"expireAfterSeconds\":60}",
                "\"expireAfterSeconds\":0","\"expireAfterSeconds\":\"off\"",
                "\"cappedSize\":4096,\"cappedMax\":100","\"cappedMax\":0")){
            var input=command(settings);var classification=NativeCommand.classify(target,input);
            assertEquals(NativeCommand.Effect.DESTRUCTIVE,classification.effect(),settings);
            assertFalse(classification.reusableRead());NativeMutations.validate(target,input);
        }
        var granularity=command("\"timeseries\":{\"granularity\":\"hours\"}");
        assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target,granularity).effect());
        NativeMutations.validate(target,granularity);
    }

    @Test void settingsRejectCoercionUnknownFieldsAndMixedOperations()throws Exception{
        for(String settings:List.of("\"index\":{\"name\":\"*\",\"expireAfterSeconds\":10}",
                "\"index\":{\"name\":\"expiry\",\"expireAfterSeconds\":\"10\"}",
                "\"index\":{\"name\":\"expiry\",\"expireAfterSeconds\":10,\"unique\":true}",
                "\"index\":{\"keyPattern\":{\"at\":1},\"expireAfterSeconds\":10}",
                "\"index\":{\"name\":\"expiry\",\"expireAfterSeconds\":-1}",
                "\"expireAfterSeconds\":true","\"expireAfterSeconds\":null","\"expireAfterSeconds\":1.5",
                "\"expireAfterSeconds\":2147483648","\"expireAfterSeconds\":9223372036854775808",
                "\"expireAfterSeconds\":\"OFF\"","\"expireAfterSeconds\":30,\"validator\":{}",
                "\"cappedSize\":0","\"cappedSize\":1000000000000000","\"cappedMax\":-1",
                "\"cappedMax\":2147483648","\"cappedMax\":5,\"expireAfterSeconds\":30",
                "\"timeseries\":{\"granularity\":\"days\"}","\"timeseries\":{\"timeField\":\"changed\"}",
                "\"timeseries\":{\"granularity\":\"hours\"},\"expireAfterSeconds\":30")){
            var input=command(settings);assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,input),settings);
        }
        for(String database:List.of("admin","config","local")){
            var system=new NativeTarget(target.transport(),target.connectionId(),target.connectionName(),database,"items",target.topology());
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(system,command("\"cappedMax\":5")));
        }
        var system=new NativeTarget(target.transport(),target.connectionId(),target.connectionName(),"app","system.buckets.items",target.topology());
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(system,command("\"cappedMax\":5").put("collMod",system.collection())));
    }

    @Test void reviewDescribesDeletionRisksAndRemainsExactOwnedAndOffline()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,5),owner->true);
            var operations=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:1").put("readOnly",false));
            var input=profileTarget(profile,"app","items");input.set("command",command("\"expireAfterSeconds\":30"));
            var review=operations.prepareBrowser("human",input);String id=review.path("id").asText();
            assertTrue(review.path("destructive").asBoolean());assertFalse(review.path("eligiblePersistentRead").asBoolean());
            assertEquals("app.items",review.path("affectedNamespaces").get(0).asText());
            assertTrue(review.path("transactionNotice").asText().contains("delete"));
            assertEquals(0,operations.telemetry().path("clients").asInt());
            assertThrows(SecurityException.class,()->operations.applyBrowser("other",id));
            var changed=input.deepCopy();changed.withObject("command").put("expireAfterSeconds",0);
            assertThrows(IllegalArgumentException.class,()->operations.validate(review,changed));
            operations.discardBrowser("human",id);assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            assertThrows(SecurityException.class,()->operations.applyBrowser("human",id));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));
            assertThrows(IllegalArgumentException.class,()->operations.prepareBrowser("human",input));
        }
    }

    private ObjectNode profileTarget(ObjectNode profile,String database,String collection){
        return Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName",profile.path("name").asText()).put("database",database).put("collection",collection);
    }

    @Test void definitionChecksRejectWrongKindsAndGranularityDowngrades()throws Exception{
        var ordinary=org.bson.BsonDocument.parse("{type:'collection',options:{}}");
        var capped=org.bson.BsonDocument.parse("{type:'collection',options:{capped:true}}");
        var series=org.bson.BsonDocument.parse("{type:'timeseries',options:{timeseries:{timeField:'at',granularity:'minutes',bucketMaxSpanSeconds:86400}}}");
        var view=org.bson.BsonDocument.parse("{type:'view',options:{viewOn:'items'}}");
        var ttl=command("\"index\":{\"name\":\"expiry\",\"expireAfterSeconds\":60}");
        MongoCollectionSettings.checkDefinition(ttl,ordinary);
        MongoCollectionSettings.checkDefinition(command("\"cappedMax\":0"),capped);
        MongoCollectionSettings.checkDefinition(command("\"expireAfterSeconds\":\"off\""),series);
        MongoCollectionSettings.checkDefinition(command("\"timeseries\":{\"granularity\":\"hours\"}"),series);
        assertThrows(IllegalArgumentException.class,()->MongoCollectionSettings.checkDefinition(command("\"timeseries\":{\"granularity\":\"seconds\"}"),series));
        for(var invalid:List.of(capped,series,view))assertThrows(IllegalArgumentException.class,()->MongoCollectionSettings.checkDefinition(ttl,invalid));
        assertThrows(IllegalArgumentException.class,()->MongoCollectionSettings.checkDefinition(command("\"cappedMax\":0"),ordinary));
        assertThrows(IllegalArgumentException.class,()->MongoCollectionSettings.checkDefinition(command("\"expireAfterSeconds\":1"),ordinary));
        series.getDocument("options").getDocument("timeseries").put("bucketRoundingSeconds",new org.bson.BsonInt32(60));
        assertThrows(IllegalArgumentException.class,()->MongoCollectionSettings.checkDefinition(command("\"timeseries\":{\"granularity\":\"hours\"}"),series));
    }

    private String fixturePort(){
        Assumptions.assumeTrue("mongodb".equals(System.getenv("NATIVE_TEST_ENGINE")),"Disposable MongoDB fixture not configured");
        assertTrue(Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"").startsWith("cgraph-native-"));
        return Objects.requireNonNull(System.getenv("NATIVE_TEST_PORT"));
    }
    private com.fasterxml.jackson.databind.JsonNode apply(NativeOperations operations,QueryJobs jobs,ObjectNode input)throws Exception{
        String id=operations.prepareBrowser("human",input).path("id").asText();
        var done=ConnectionSetupTest.await(jobs,"human",operations.applyBrowser("human",id));
        jobs.remove("human",done.path("id").asText());return done;
    }
    private ObjectNode setting(ObjectNode profile,String database,String collection,String settings)throws Exception{
        var input=profileTarget(profile,database,collection);input.set("command",command(settings).put("collMod",collection));return input;
    }

    @Test @Timeout(90) void liveSettingsPreserveIdentityAndRejectUnsafeKindsBeforeWriting()throws Exception{
        String port=fixturePort(),database="settings_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,256L<<20,2,100,100,10),owner->true);
            var operations=new NativeOperations(profiles,jobs);var clients=new NativeConnections(profiles)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false));
            try(var lease=clients.acquire(profile.path("id").asText())){
                var db=lease.mongo.getDatabase(database);var future=java.util.Date.from(java.time.Instant.parse("2100-01-01T00:00:00Z"));
                try{
                    db.getCollection("items").insertOne(new org.bson.Document("_id",1).append("at",future).append("value",9007199254740993L));
                    db.runCommand(org.bson.Document.parse("{createIndexes:'items',indexes:[{key:{at:1},name:'expiry',expireAfterSeconds:3600}]}"));
                    var before=db.listCollections().filter(new org.bson.Document("name","items")).first();
                    var input=setting(profile,database,"items","\"index\":{\"name\":\"expiry\",\"expireAfterSeconds\":7200}");
                    var prepared=operations.prepareBrowser("human",input);
                    assertEquals(3600,((Number)db.getCollection("items").listIndexes().into(new ArrayList<>()).stream().filter(i->i.getString("name").equals("expiry")).findFirst().orElseThrow().get("expireAfterSeconds")).intValue());
                    operations.discardBrowser("human",prepared.path("id").asText());
                    var done=apply(operations,jobs,input);assertEquals("complete",done.path("state").asText(),done.toPrettyString());
                    assertEquals(7200,((Number)db.getCollection("items").listIndexes().into(new ArrayList<>()).stream().filter(i->i.getString("name").equals("expiry")).findFirst().orElseThrow().get("expireAfterSeconds")).intValue());
                    assertEquals(before.get("info"),db.listCollections().filter(new org.bson.Document("name","items")).first().get("info"));
                    assertEquals(9007199254740993L,db.getCollection("items").find().first().getLong("value"));
                    db.runCommand(org.bson.Document.parse("{create:'capped',capped:true,size:1048576,max:100}"));
                    db.getCollection("capped").insertOne(new org.bson.Document("_id",1).append("value","retain"));
                    done=apply(operations,jobs,setting(profile,database,"capped","\"cappedSize\":2097152,\"cappedMax\":200"));
                    assertEquals("complete",done.path("state").asText(),done.toPrettyString());
                    var capped=db.listCollections().filter(new org.bson.Document("name","capped")).first().get("options",org.bson.Document.class);
                    assertEquals(2097152,((Number)capped.get("size")).intValue());assertEquals(200,((Number)capped.get("max")).intValue());
                    assertEquals("retain",db.getCollection("capped").find().first().getString("value"));
                    db.runCommand(org.bson.Document.parse("{create:'series',timeseries:{timeField:'at'},expireAfterSeconds:3600}"));
                    db.getCollection("series").insertOne(new org.bson.Document("at",future).append("value",7));
                    for(String settings:List.of("\"expireAfterSeconds\":7200","\"expireAfterSeconds\":\"off\"","\"timeseries\":{\"granularity\":\"minutes\"}")){
                        done=apply(operations,jobs,setting(profile,database,"series",settings));assertEquals("complete",done.path("state").asText(),done.toPrettyString());
                    }
                    var series=db.listCollections().filter(new org.bson.Document("name","series")).first().get("options",org.bson.Document.class);
                    assertFalse(series.containsKey("expireAfterSeconds"));assertEquals("minutes",series.get("timeseries",org.bson.Document.class).getString("granularity"));assertEquals(1,db.getCollection("series").countDocuments());
                    var allowed=new ArrayList<String>();for(int n=0;n<6000;n++)allowed.add(n+"x".repeat(80));
                    db.runCommand(new org.bson.Document("create","large_definition").append("validator",new org.bson.Document("payload",new org.bson.Document("$in",allowed))));
                    done=apply(operations,jobs,setting(profile,database,"large_definition","\"cappedMax\":10"));
                    assertEquals("failed",done.path("state").asText());assertEquals("not_started",done.path("outcome").asText());
                    assertTrue(done.toString().contains("bounded metadata validation allowance"),done.toPrettyString());
                    db.createView("view","items",List.of());db.getCollection("items").createIndex(new org.bson.Document("value",1));
                    for(var invalid:List.of(setting(profile,database,"items","\"cappedMax\":10"),
                            setting(profile,database,"items","\"expireAfterSeconds\":10"),setting(profile,database,"view","\"cappedMax\":10"),
                            setting(profile,database,"missing","\"cappedMax\":10"),setting(profile,database,"series","\"timeseries\":{\"granularity\":\"seconds\"}"),
                            setting(profile,database,"items","\"index\":{\"name\":\"value_1\",\"expireAfterSeconds\":10}"),
                            setting(profile,database,"items","\"index\":{\"name\":\"missing\",\"expireAfterSeconds\":10}"))){
                        done=apply(operations,jobs,invalid);assertEquals("failed",done.path("state").asText(),done.toPrettyString());assertEquals("not_started",done.path("outcome").asText());
                    }
                    var selected=NativeTarget.resolve(profile,input);
                    done=ConnectionSetupTest.await(jobs,"human",jobs.local("human",selected.connectionId(),job->NativeMutations.execute(lease,selected,input.path("command"),job,()->{throw new SecurityException("Revoked after metadata lookup");}),()->{}));
                    assertEquals("not_started",done.path("outcome").asText());jobs.remove("human",done.path("id").asText());
                    done=ConnectionSetupTest.await(jobs,"human",jobs.local("human",selected.connectionId(),job->{job.cancelled=true;return NativeMutations.execute(lease,selected,input.path("command"),job,()->fail("Cancelled command reached write"));},()->{}));
                    assertEquals("not_started",done.path("outcome").asText());jobs.remove("human",done.path("id").asText());
                    var tree=ConnectionSetupTest.await(jobs,"human",operations.tree("human",profileTarget(profile,database,"").put("kind","native_collections")));
                    assertEquals("complete",tree.path("state").asText(),tree.toPrettyString());
                    assertTrue(tree.path("result").path("nodes").toString().contains("\"name\":\"series\""));
                    assertFalse(tree.path("result").path("nodes").toString().contains("system.buckets"));jobs.remove("human",tree.path("id").asText());
                    assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
                }finally{db.drop();}
            }
        }
    }

    @Test @Timeout(90) void liveAgentSettingsRequireExactReviewOrExplicitYolo()throws Exception{
        String port=fixturePort();
        for(boolean automatic:new boolean[]{false,true}){
            Path state=root.resolve(automatic?"automatic":"reviewed");String database="settings_agent_"+UUID.randomUUID().toString().replace("-","");
            var authorization=new AgentAuthorization(automatic,state);var agents=new AgentAccess(state,authorization);
            var sessions=new McpSessions(System::currentTimeMillis);authorization.sessions(sessions);
            try(var profiles=new Profiles(state,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
                var contexts=new ProjectContexts(profiles,jdbc,agents,System::currentTimeMillis,false);
                var jobs=new QueryJobs(jdbc,new DbaConfig(state,256L<<20,2,100,100,10),owner->true);
                var operations=new NativeOperations(profiles,jobs,contexts);var clients=new NativeConnections(profiles);
                var requests=new AgentRequests(profiles,jdbc,new ConnectionSetup(profiles,jobs),contexts,agents,jobs)){
                requests.nativeOperations(operations);requests.sessions(sessions);String principal=agents.trustedLocal(),session=UUID.randomUUID().toString();sessions.register(session,principal,System.currentTimeMillis()+300000);
                var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Mongo").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false));
                try(var lease=clients.acquire(profile.path("id").asText())){
                    var db=lease.mongo.getDatabase(database);
                    try{
                        db.runCommand(org.bson.Document.parse("{create:'capped',capped:true,size:1048576,max:100}"));
                        var input=setting(profile,database,"capped","\"cappedMax\":200").put("requestId",UUID.randomUUID().toString()).put("purpose","Owned settings test");
                        var pending=requests.request(principal,session,"native_command",input);
                        if(!automatic){
                            assertEquals("awaiting_approval",pending.path("state").asText());String id=pending.path("id").asText();assertTrue(pending.path("destructive").asBoolean());
                            assertThrows(IllegalArgumentException.class,()->requests.decide("human",id,"always_allow",true,Profiles.JSON.createObjectNode()));
                            pending=requests.decide("human",id,"approve_once",true,Profiles.JSON.createObjectNode());
                        }else assertEquals("automatic",pending.path("approvalChannel").asText());
                        var done=ConnectionSetupTest.await(jobs,"agent:"+principal,Profiles.JSON.createObjectNode().put("id",pending.path("jobId").asText()));
                        assertEquals("complete",done.path("state").asText(),done.toPrettyString());jobs.remove("agent:"+principal,done.path("id").asText());
                        assertEquals(200,((Number)db.listCollections().filter(new org.bson.Document("name","capped")).first().get("options",org.bson.Document.class).get("max")).intValue());
                    }finally{db.drop();}
                }
            }
        }
    }
}
