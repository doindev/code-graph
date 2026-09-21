package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.ClientSession;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Collation;
import org.bson.*;
import org.bson.codecs.BsonDocumentCodec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact BSON/absence guard for one reviewed document change, inside the transaction lifecycle. */
final class NativeMongoDocuments {
    static final int MAX_JSON_BYTES=32768;
    static final String NOTICE="Creates one document after an exact _id absence check, or replaces/deletes one existing document after a byte-exact BSON comparison in an atomic transaction. Deletion removes the whole document and replacement removes omitted fields. Requires a replica-set profile and existing ordinary collection UUID. Field order and numeric types are significant; existing _id values are immutable. No upsert, implicit collection creation, sharded routing, projection, bulk editing or automatic retry. A lost commit acknowledgement requires reconciliation; matching data is not historical identity.";

    static void validate(NativeTarget target,JsonNode command){
        if(!target.topology().equals("replica_set"))throw new IllegalArgumentException("Document guards require an explicit replica_set profile; standalone/SRV/sharded document editing is not verified");
        JsonNode guard=command.path("documentGuard");
        boolean creating=guard.has("absentId");
        fields(guard,creating?Set.of("collectionUuid","absentId"):Set.of("collectionUuid","expected"));
        uuid(guard.path("collectionUuid"));
        BsonDocument original=creating?identity(guard.path("absentId")):document(guard.path("expected"));
        if(command.path("transaction").size()!=1)throw new IllegalArgumentException("Document guard requires exactly one insert, replacement update or single-document delete");
        JsonNode entry=command.path("transaction").get(0);
        if(creating){
            fields(entry,Set.of("insert","documents","ordered"));
            if(!entry.path("insert").asText().equals(target.collection())||!entry.path("documents").isArray()||entry.path("documents").size()!=1)
                throw new IllegalArgumentException("Absent document guard requires exactly one insert on the selected collection");
            BsonDocument inserted=document(entry.path("documents").get(0));
            if(!same(original,new BsonDocument("_id",inserted.get("_id"))))throw new IllegalArgumentException("Insert _id must exactly match the typed absentId guard");
            return;
        }
        if(entry.has("delete")){
            fields(entry,Set.of("delete","deletes","ordered"));
            if(!entry.path("delete").asText().equals(target.collection())||!entry.path("deletes").isArray()||entry.path("deletes").size()!=1)
                throw new IllegalArgumentException("Document guard requires one delete on the selected collection");
            JsonNode deletion=entry.path("deletes").get(0);
            fields(deletion,Set.of("q","limit"));fields(deletion.path("q"),Set.of("_id"));
            if(!deletion.path("limit").isIntegralNumber()||!deletion.path("limit").canConvertToInt()||deletion.path("limit").intValue()!=1
                    ||!same(new BsonDocument("_id",original.get("_id")),document(deletion.path("q"))))
                throw new IllegalArgumentException("Guarded deletion requires limit 1 and the exact original typed _id");
            return;
        }
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
        if(!uuid(guard.path("collectionUuid")).equals(observed))throw new IllegalArgumentException("Collection identity changed; reload before reviewing another document change");
    }
    static void check(MongoDatabase database,ClientSession session,NativeTarget target,JsonNode guard){
        if(guard.has("absentId")){
            // Inspect only _id: an existing large document is a conflict, not a reason
            // to decode or retain its full payload. The insert's unique _id constraint
            // also rejects a competing writer after this snapshot observation.
            try(var cursor=database.getCollection(target.collection(),RawBsonDocument.class)
                    .find(session,identity(guard.path("absentId"))).projection(new BsonDocument("_id",new BsonInt32(1)))
                    .collation(Collation.builder().locale("simple").build()).limit(1).batchSize(1).iterator()){
                if(cursor.hasNext())throw new Conflict("Document conflict: _id already exists; no commit attempted");
            }
            return;
        }
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
    private static BsonDocument identity(JsonNode id){
        var value=Profiles.JSON.createObjectNode();value.set("_id",id);return document(value);
    }
    private static void fields(JsonNode value,Set<String> allowed){
        if(!value.isObject())throw new IllegalArgumentException("Guarded document fields must be objects");
        value.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unsupported guarded document field");});
    }
    static final class Conflict extends IllegalArgumentException {Conflict(String message){super(message);}}
    private NativeMongoDocuments(){}
}
