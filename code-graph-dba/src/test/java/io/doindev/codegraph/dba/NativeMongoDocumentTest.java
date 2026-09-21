package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.*;
import org.bson.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeMongoDocumentTest {
    @TempDir Path root;
    static final String UUID64="AAAAAAAAAAAAAAAAAAAAAA==";
    static JsonNode json(String text)throws Exception{return Profiles.JSON.readTree(text);}
    static JsonNode canonical(BsonDocument document)throws Exception{return json(document.toJson(JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build()));}
    static NativeTarget target(String topology){return new NativeTarget(DatabaseTransport.MONGODB,UUID.randomUUID().toString(),"Fixture","documents","items",topology);}
    static ObjectNode guarded(String collection,JsonNode original,JsonNode replacement,String uuid){
        var command=Profiles.JSON.createObjectNode();
        var update=command.putArray("transaction").addObject().put("update",collection).putArray("updates").addObject().put("multi",false).put("upsert",false);
        update.putObject("q").set("_id",original.path("_id"));update.set("u",replacement);
        command.putObject("documentGuard").put("collectionUuid",uuid).set("expected",original);return command;
    }
    @Test void exactTypedOriginalAndImmutableIdentity()throws Exception{
        var original=canonical(BsonDocument.parse("{_id:{$oid:'0123456789abcdef01234567'},n:{$numberLong:'9007199254740993'},price:{$numberDecimal:'1.2300'},at:{$date:{$numberLong:'1'}},bin:{$binary:{base64:'AP8=',subType:'00'}},nested:[null,{x:{$numberInt:'2'}}]}"));
        var replacement=original.deepCopy();((ObjectNode)replacement).put("name","changed");
        var command=guarded("items",original,replacement,UUID64);
        NativeMutations.validate(target("replica_set"),command);
        assertTrue(NativeCommand.classify(target("replica_set"),command).reason().contains("byte-exact"));
        assertFalse(NativeCommand.classify(target("replica_set"),command).reusableRead());
        for(String topology:List.of("standalone","srv","sharded"))assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target(topology),command));
        for(String bad:List.of("{\"_id\":1}","{\"_id\":null}","{\"_id\":{}}","{\"_id\":\"a\",\"$set\":{}}","{\"_id\":\"a\",\"x\":2}","{\"_id\":\"a\",\"x\":{\"$numberLong\":\"bad\"}}"))
            assertThrows(IllegalArgumentException.class,()->NativeMongoDocuments.document(json(bad)));
        assertThrows(IllegalArgumentException.class,()->NativeMongoDocuments.document(json("{\"_id\":\"a\",\"x\":\""+"x".repeat(32768)+"\"}")));
        var changed=command.deepCopy();((ObjectNode)changed.path("transaction").get(0).path("updates").get(0).path("u")).put("_id","different");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target("replica_set"),changed));
        assertFalse(NativeMongoDocuments.same(BsonDocument.parse("{_id:'a',x:{$numberInt:'1'}}"),BsonDocument.parse("{_id:'a',x:{$numberLong:'1'}}")));
        assertFalse(NativeMongoDocuments.same(BsonDocument.parse("{_id:'a',x:null}"),BsonDocument.parse("{_id:'a'}")));
        assertFalse(NativeMongoDocuments.same(BsonDocument.parse("{_id:'a',x:'x',y:'y'}"),BsonDocument.parse("{_id:'a',y:'y',x:'x'}")));
    }
    @Test void guardCannotBecomeBulkOperatorUpsertOrCallerCollation()throws Exception{
        var original=json("{\"_id\":\"a\",\"name\":\"original\"}");var replacement=json("{\"_id\":\"a\",\"name\":\"new\"}");
        var selected=target("replica_set");
        for(String extra:List.of("upsert","multi","collation","arrayFilters")){
            var command=guarded("items",original,replacement,UUID64);
            ((ObjectNode)command.path("transaction").get(0).path("updates").get(0)).put(extra,true);
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,command));
        }
        var bulk=guarded("items",original,replacement,UUID64);bulk.withArray("transaction").add(bulk.path("transaction").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,bulk));
        var operator=guarded("items",original,json("{\"$set\":{\"name\":\"changed\"}}"),UUID64);
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,operator));
        var badFilter=guarded("items",original,replacement,UUID64);((ObjectNode)badFilter.path("transaction").get(0).path("updates").get(0).path("q")).put("name","original");
        assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(selected,badFilter));
        for(String invalid:List.of("","AAAA","!!!!!!!!!!!!!!!!!!!!!!!!"))assertThrows(IllegalArgumentException.class,()->NativeMongoDocuments.uuid(Profiles.JSON.getNodeFactory().textNode(invalid)));
        assertThrows(IllegalArgumentException.class,()->NativeMongoDocuments.collection(guarded("items",original,replacement,UUID64).path("documentGuard"),new BsonBinary(new byte[16])));
    }
    @Test void reviewOwnershipProfileRevisionAndReservation()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,profile("1","replica_set"));
            var input=targetInput(profile);input.set("command",guarded("items",json("{\"_id\":\"a\"}"),json("{\"_id\":\"a\",\"x\":\"new\"}"),UUID64));
            var review=ops.prepareBrowser("human",input);assertTrue(review.path("transactionNotice").asText().contains("byte-exact"));assertEquals(input.path("command"),review.path("after").path("nativeCommand"));
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));
            var changed=input.deepCopy();((ObjectNode)changed.path("command").path("documentGuard")).put("collectionUuid","AQAAAAAAAAAAAAAAAAAAAA==");
            assertThrows(IllegalArgumentException.class,()->ops.validate(review,changed));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("name","Changed"));
            assertThrows(IllegalArgumentException.class,()->ops.applyBrowser("human",review.path("id").asText()));
            ops.discardBrowser("human",review.path("id").asText());assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
    static ObjectNode profile(String port,String topology){
        var profile=Profiles.JSON.createObjectNode().put("name","Document fixture").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:"+port).put("readOnly",false);
        var options=profile.putObject("nativeOptions").put("database","documents").put("topology",topology).put("maximumPoolSize",2).put("connectTimeoutMS",3000).put("socketTimeoutMS",5000);
        if(topology.equals("replica_set"))options.put("replicaSet","cgraph");return profile;
    }
    static ObjectNode targetInput(ObjectNode profile){return Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Document fixture").put("database","documents").put("collection","items");}

    @Test @Timeout(150) void liveGuardedReplacementConflictRollbackAndUnknownCommit()throws Exception{
        String port=System.getenv("MONGO_TOPOLOGY_PORT"),topology=Objects.toString(System.getenv("MONGO_TOPOLOGY_KIND"),"");
        Assumptions.assumeTrue(port!=null&&Set.of("replica_set","sharded").contains(topology),"Owned Mongo topology required");
        assertTrue(Objects.toString(System.getenv("MONGO_TOPOLOGY_OWNER"),"").startsWith("cgraph-mongo-topology-"));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,20),_->true);var clients=new NativeConnections(profiles);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,profile(port,topology));var input=targetInput(profile);var selected=NativeTarget.resolve(profile,input);
            try(var lease=clients.acquire(selected.connectionId())){
                var db=lease.mongo.getDatabase("documents");db.createCollection("items");var collection=db.getCollection("items",BsonDocument.class);
                var original=BsonDocument.parse("{_id:'exact',n:{$numberLong:'9007199254740993'},price:{$numberDecimal:'1.2300'},bin:{$binary:{base64:'AP8=',subType:'00'}},nested:[null,{name:'original'}]}");
                collection.insertOne(original);
                var metadata=MongoCollectionMetadata.load(lease,selected,jobs.new Job("human",selected.connectionId()));
                String uuid=Base64.getEncoder().encodeToString(metadata.getDocument("info").getBinary("uuid").getData());
                var replacement=original.clone().append("name",new BsonString("saved"));var command=guarded("items",canonical(original),canonical(replacement),uuid);
                if(topology.equals("sharded")){assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,selected,command,jobs.new Job("human",selected.connectionId()),()->fail("Unsupported guard cannot dispatch")));return;}
                input.set("command",command);var review=ops.prepareBrowser("human",input);
                var complete=ConnectionSetupTest.await(jobs,"human",ops.applyBrowser("human",review.path("id").asText()));
                assertEquals("complete",complete.path("state").asText(),complete.toPrettyString());assertTrue(complete.path("result").path("documentGuard").asBoolean());assertEquals("commit_acknowledged",complete.path("result").path("outcome").asText());
                assertTrue(NativeMongoDocuments.same(replacement,collection.find().first()));jobs.remove("human",complete.path("id").asText());
                var stale=jobs.new Job("human",selected.connectionId());assertTrue(assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,selected,command,stale,()->{})).getMessage().contains("Document conflict"));assertEquals("rollback_acknowledged",stale.outcome);
                var next=replacement.clone().append("next",new BsonString("next"));var pending=guarded("items",canonical(replacement),canonical(next),uuid);
                for(boolean revoke:List.of(false,true)){
                    var stopped=jobs.new Job("human",selected.connectionId());var checks=new AtomicInteger();
                    assertThrows(RuntimeException.class,()->NativeMutations.execute(lease,selected,pending,stopped,()->{if(checks.incrementAndGet()==3){if(revoke)throw new SecurityException("revoked");stopped.cancelled=true;}}));
                    assertEquals("rollback_acknowledged",stopped.outcome);assertTrue(NativeMongoDocuments.same(replacement,collection.find().first()));
                }
                // Concurrent update after snapshot guard must abort rather than overwrite.
                var concurrent=jobs.new Job("human",selected.connectionId());var checks=new AtomicInteger();
                assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,selected,pending,concurrent,()->{if(checks.incrementAndGet()==2)collection.updateOne(new BsonDocument("_id",new BsonString("exact")),new BsonDocument("$set",new BsonDocument("other",BsonBoolean.TRUE)));}));
                assertEquals("rollback_acknowledged",concurrent.outcome);assertTrue(collection.find().first().getBoolean("other").getValue());assertFalse(collection.find().first().containsKey("next"));
                var observed=collection.find().first();var finalReplacement=observed.clone().append("next",new BsonString("next"));var finalPending=guarded("items",canonical(observed),canonical(finalReplacement),uuid);
                var commits=new AtomicInteger();
                try(var lost=new NativeConnections.Lease(NativeMongoTransactionTest.faultClient(lease.mongo,commits),null,null,new byte[32],lease.revision,()->{})){
                    var unknown=jobs.new Job("human",selected.connectionId());var exact=finalPending;
                    var error=assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lost,selected,exact,unknown,()->{}));
                    assertEquals(1,commits.get());assertEquals("commit_unknown",unknown.outcome);assertFalse(error.getMessage().contains("secret-sentinel"));assertTrue(collection.find().first().containsKey("next"));
                }
                collection.drop();db.createCollection("items");var recreated=db.getCollection("items",BsonDocument.class);recreated.insertOne(original);
                var replaced=jobs.new Job("human",selected.connectionId());assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,selected,command,replaced,()->fail("UUID check before writes")));assertTrue(NativeMongoDocuments.same(original,recreated.find().first()));
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
