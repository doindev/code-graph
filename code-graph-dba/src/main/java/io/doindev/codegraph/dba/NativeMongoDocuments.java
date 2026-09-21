package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact BSON guard for one reviewed replacement, inside the existing transaction lifecycle. */
final class NativeMongoDocuments {
    static final int MAX_JSON_BYTES=32768;
    static final String NOTICE="Replaces one existing document after a byte-exact BSON comparison in an atomic transaction; fields omitted from the replacement are removed. Requires a replica-set profile, ordinary collection UUID and unchanged _id. Field order and numeric types are significant. No upsert, sharded routing, projection, bulk editing or automatic retry. A lost commit acknowledgement requires reconciliation; matching data is not historical identity.";

    static void validate(NativeTarget target,JsonNode command){
        if(!target.topology().equals("replica_set"))throw new IllegalArgumentException("Document guards require an explicit replica_set profile; standalone/SRV/sharded document editing is not verified");
        JsonNode guard=command.path("documentGuard");
        fields(guard,Set.of("collectionUuid","expected"));
        uuid(guard.path("collectionUuid"));
        BsonDocument original=document(guard.path("expected"));
        if(command.path("transaction").size()!=1)throw new IllegalArgumentException("Document guard requires exactly one replacement update");
        JsonNode entry=command.path("transaction").get(0);
        fields(entry,Set.of("update","updates","ordered"));
        if(!entry.path("update").asText().equals(target.collection())||!entry.path("updates").isArray()||entry.path("updates").size()!=1)
            throw new IllegalArgumentException("Document guard requires one update on the selected collection");
        JsonNode update=entry.path("updates").get(0);
        fields(update,Set.of("q","u","multi","upsert"));
        if(update.path("multi").asBoolean()||update.path("upsert").asBoolean())throw new IllegalArgumentException("Guarded replacement forbids multi and upsert");
        fields(update.path("q"),Set.of("_id"));
        BsonDocument filter=document(update.path("q")),replacement=document(update.path("u"));
        if(!same(new BsonDocument("_id",original.get("_id")),filter)
                ||!same(new BsonDocument("_id",original.get("_id")),new BsonDocument("_id",replacement.get("_id"))))
            throw new IllegalArgumentException("Document guard requires the same immutable, typed _id in original, filter and replacement");
    }

    static BsonDocument document(JsonNode input){
        if(!input.isObject()||!input.has("_id")||input.toString().getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES)
            throw new IllegalArgumentException("Complete document with _id and at most 32 KiB canonical Extended JSON required");
        BsonDocument bson;
        try{bson=BsonDocument.parse(input.toString());}
        catch(RuntimeException error){throw new IllegalArgumentException("Invalid canonical Extended JSON; driver details withheld");}
        BsonValue id=bson.get("_id");
        if(id==null||!Set.of(BsonType.STRING,BsonType.OBJECT_ID,BsonType.INT32,BsonType.INT64).contains(id.getBsonType()))
            throw new IllegalArgumentException("Document editor _id supports string, ObjectId, Int32 or Int64 only");
        if(bson.keySet().stream().anyMatch(key->key.startsWith("$")))
            throw new IllegalArgumentException("Replacement documents cannot contain top-level operator fields");
        // Canonical output keeps integer widths, Decimal128 and other BSON values out of JS numbers.
        try{
            var canonical=Profiles.JSON.readTree(bson.toJson(org.bson.json.JsonWriterSettings.builder().outputMode(org.bson.json.JsonMode.EXTENDED).build()));
            if(!canonical.equals(input))throw new IllegalArgumentException("Use canonical Extended JSON type wrappers; relaxed or ambiguous values are not editable");
        }catch(java.io.IOException error){throw new IllegalArgumentException("Cannot encode document for validation");}
        return bson;
    }
    static BsonBinary uuid(JsonNode value){
        try{
            if(!value.isTextual())throw new IllegalArgumentException();
            byte[] bytes=Base64.getDecoder().decode(value.asText());
            if(bytes.length!=16||!Base64.getEncoder().encodeToString(bytes).equals(value.asText()))throw new IllegalArgumentException();
            return new BsonBinary((byte)4,bytes);
        }catch(IllegalArgumentException error){throw new IllegalArgumentException("Document guard requires a canonical base64 collection UUID");}
    }
    static void collection(JsonNode guard,BsonValue observed){
        if(!uuid(guard.path("collectionUuid")).equals(observed))throw new IllegalArgumentException("Collection identity changed; reload before reviewing another document replacement");
    }
    static void check(MongoDatabase database,ClientSession session,NativeTarget target,JsonNode guard){
        BsonDocument expected=document(guard.path("expected"));
        try(var cursor=database.getCollection(target.collection(),RawBsonDocument.class)
                .find(session,new BsonDocument("_id",expected.get("_id")))
                .collation(Collation.builder().locale("simple").build()).limit(2).batchSize(1).iterator()){
            if(!cursor.hasNext())throw new Conflict("Document conflict: original no longer exists; no commit attempted");
            RawBsonDocument current=cursor.next();
            if(current.getByteBuffer().remaining()>MAX_JSON_BYTES||!same(current,expected)||cursor.hasNext())
                throw new Conflict("Document conflict: complete original BSON changed; no commit attempted");
        }
    }
    static boolean same(BsonDocument left,BsonDocument right){
        RawBsonDocument a=left instanceof RawBsonDocument raw?raw:new RawBsonDocument(left,new BsonDocumentCodec());
        RawBsonDocument b=right instanceof RawBsonDocument raw?raw:new RawBsonDocument(right,new BsonDocumentCodec());
        return a.getByteBuffer().asNIO().equals(b.getByteBuffer().asNIO());
    }
    private static void fields(JsonNode value,Set<String> allowed){
        if(!value.isObject())throw new IllegalArgumentException("Guarded document fields must be objects");
        value.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unsupported guarded document field");});
    }
    static final class Conflict extends IllegalArgumentException {Conflict(String message){super(message);}}
    private NativeMongoDocuments(){}
}
