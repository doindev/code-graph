package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MongoTopologyTest {
    @TempDir Path root;
    @Test @Timeout(90) void explicitReplicaAndRouterTargetsUseTheNativePipeline()throws Exception{
        String port=System.getenv("MONGO_TOPOLOGY_PORT"),topology=System.getenv("MONGO_TOPOLOGY_KIND");
        Assumptions.assumeTrue(port!=null&&Set.of("replica_set","sharded").contains(Objects.toString(topology,"")),"Owned MongoDB topology not configured");
        assertTrue(Objects.toString(System.getenv("MONGO_TOPOLOGY_OWNER"),"").startsWith("cgraph-mongo-topology-"));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var clients=new NativeConnections(profiles);
            var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,256L<<20,2,100,100,15),owner->true);
            var operations=new NativeOperations(profiles,jobs)){
            var draft=Profiles.JSON.createObjectNode().put("name","Topology").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("database","fixture").put("topology",topology).put("maximumPoolSize",1).put("connectTimeoutMS",3000).put("socketTimeoutMS",5000);
            if(topology.equals("replica_set"))options.put("replicaSet","cgraph");
            var tested=NativeProfile.create(draft,Profiles.JSON.createObjectNode());try{assertTrue(NativeConnections.testDraft(tested).path("connected").asBoolean());}finally{tested.clear();}
            var profile=profiles.put(null,draft);String id=profile.path("id").asText();
            var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Topology").put("database","fixture").put("collection","items"));
            try(var lease=clients.acquire(id)){
                var db=lease.mongo.getDatabase("fixture");
                db.runCommand(org.bson.Document.parse("{create:'items',validator:{$jsonSchema:{bsonType:'object',required:['name'],properties:{name:{bsonType:'string'}}}}}"));
                db.getCollection("items").insertOne(new org.bson.Document("name","fixture-only"));
                var result=NativeReadExecutor.execute(lease,target,Profiles.JSON.readTree("{\"find\":\"items\",\"filter\":{},\"limit\":1}"),new NativeReadExecutor.Limits(10,1<<20,5),()->false);
                assertTrue(result.toString().contains("fixture-only"));
                var snapshot=NativeSchemaObservations.capture(lease,target,"snapshot",100,1<<20,10,0,()->false);
                assertTrue(snapshot.path("objects").size()>0);assertFalse(snapshot.toString().contains("fixture-only"));
                assertTrue(snapshot.toString().contains("declared_validator"));
                assertThrows(java.util.concurrent.CancellationException.class,()->NativeSchemaObservations.capture(lease,target,"cancelled",100,1<<20,10,0,()->true));
                if(topology.equals("sharded"))lease.mongo.getDatabase("admin").runCommand(new org.bson.Document("shardCollection","fixture.items").append("key",new org.bson.Document("_id",1)));
                var input=target.json();input.putObject("command").put("renameCollection","fixture.items").put("to","fixture.renamed").put("dropTarget",false);
                var review=operations.prepareBrowser("human",input);
                var renamed=ConnectionSetupTest.await(jobs,"human",operations.applyBrowser("human",review.path("id").asText()));
                assertEquals("complete",renamed.path("state").asText(),renamed.toPrettyString());
                assertEquals("fixture-only",db.getCollection("renamed").find().first().getString("name"));
                assertFalse(db.listCollectionNames().into(new ArrayList<>()).contains("items"));
                jobs.remove("human",renamed.path("id").asText());
            }
            // A wrong topology must fail selection, not fall back to a reachable server.
            var wrong=draft.deepCopy();wrong.withObject("nativeOptions").put("topology",topology.equals("sharded")?"replica_set":"sharded").remove("replicaSet");
            var wrongDraft=NativeProfile.create(wrong,Profiles.JSON.createObjectNode());try{assertThrows(RuntimeException.class,()->NativeConnections.testDraft(wrongDraft));}finally{wrongDraft.clear();}
            clients.remove(id);assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
