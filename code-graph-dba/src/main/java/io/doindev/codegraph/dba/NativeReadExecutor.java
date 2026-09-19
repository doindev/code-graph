package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.*;
import io.lettuce.core.*;
import io.lettuce.core.codec.ByteArrayCodec;
import org.bson.*;
import org.bson.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Small verified read adapters. Callers must authorize before acquiring a lease. */
final class NativeReadExecutor {
    // MongoDB also caps a server cursor batch at 16 MiB. Keep its documents raw,
    // limit their count, and expand only size-checked documents into retained JSON.
    static final int MONGO_BATCH_DOCUMENTS=16;
    record Limits(int rows,int bytes,int timeoutSeconds) {
        Limits { if(rows<1||rows>10000||bytes<16384||bytes>4<<20||timeoutSeconds<1||timeoutSeconds>300)throw new IllegalArgumentException("Invalid native execution limits"); }
    }
    private static final JsonWriterSettings EXTENDED=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();

    static ObjectNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,Limits limits,BooleanSupplier cancelled)throws Exception {
        check(cancelled);
        NativeCommand.Classification classification=NativeCommand.classify(target,command);
        if(!classification.reusableRead())throw new IllegalArgumentException("This command has no verified native read adapter; request the appropriate reviewed operation");
        ObjectNode result=target.transport()==DatabaseTransport.MONGODB?mongo(lease.mongo,target,command,limits,cancelled):redis(lease.redis,target,command,limits,cancelled);
        result.set("target",target.json());result.set("classification",classification.json());
        result.put("consistency","live_non_snapshot").put("hardMemoryLimit",false);
        return result;
    }

    private static ObjectNode mongo(MongoClient client,NativeTarget target,JsonNode command,Limits limits,BooleanSupplier cancelled)throws Exception {
        var database=client.getDatabase(target.database());String operation=command.fieldNames().next();
        if(operation.equals("listCollections")){
            var query=database.listCollections(RawBsonDocument.class).batchSize(batch(limits)).maxTime(limits.timeoutSeconds(),TimeUnit.SECONDS);
            if(command.has("filter"))query.filter(document(command.path("filter")));
            try(var cursor=query.iterator()){return documents(cursor,limits,cancelled);}
        }
        var collection=database.getCollection(target.collection(),RawBsonDocument.class);
        if(operation.equals("listIndexes")){
            try(var cursor=collection.listIndexes(RawBsonDocument.class).batchSize(batch(limits)).maxTime(limits.timeoutSeconds(),TimeUnit.SECONDS).iterator()){
                return documents(cursor,limits,cancelled);
            }
        }
        if(operation.equals("find")) {
            var query=collection.find(document(command.path("filter")));
            if(command.has("projection"))query.projection(document(command.path("projection")));
            if(command.has("sort"))query.sort(document(command.path("sort")));
            if(command.has("skip"))query.skip(number(command,"skip",0,1000000));
            int requested=command.has("limit")?number(command,"limit",1,1000000):limits.rows()+1;
            query.limit(Math.min(requested,limits.rows()+1)).batchSize(batch(limits)).maxTime(limits.timeoutSeconds(),TimeUnit.SECONDS);
            // Hints/collations need typed, validated translation before they can be advertised.
            rejectOptions(command,Set.of("find","filter","projection","sort","skip","limit"));
            try(var cursor=query.iterator()) { return documents(cursor,limits,cancelled); }
        }
        if(operation.equals("aggregate")) {
            rejectOptions(command,Set.of("aggregate","pipeline","allowDiskUse"));
            if(command.has("allowDiskUse")&&!command.path("allowDiskUse").isBoolean())throw new IllegalArgumentException("allowDiskUse must be a boolean");
            List<BsonDocument> pipeline=new ArrayList<>();for(JsonNode stage:command.path("pipeline"))pipeline.add(document(stage));
            pipeline.add(new BsonDocument("$limit",new BsonInt32(limits.rows()+1)));
            try(var cursor=collection.aggregate(pipeline).allowDiskUse(command.path("allowDiskUse").asBoolean(false))
                    .batchSize(batch(limits)).maxTime(limits.timeoutSeconds(),TimeUnit.SECONDS).iterator()) {
                return documents(cursor,limits,cancelled);
            }
        }
        if(operation.equals("explain")) {
            rejectOptions(command,Set.of("explain","verbosity"));
            BsonDocument explain=document(command);explain.put("maxTimeMS",new BsonInt64(limits.timeoutSeconds()*1000L));
            RawBsonDocument plan=database.runCommand(explain,RawBsonDocument.class);
            var result=new NativeResults("plan",1,limits.bytes());addDocument(result,plan);
            return result.finish().put("estimated",true);
        }
        throw new IllegalArgumentException("Native MongoDB read operation is not implemented");
    }

    private static ObjectNode documents(MongoCursor<RawBsonDocument> cursor,Limits limits,BooleanSupplier cancelled)throws Exception {
        var result=new NativeResults("documents",limits.rows(),limits.bytes());
        while(true) {
            check(cancelled);if(!cursor.hasNext())break;check(cancelled);
            RawBsonDocument value=cursor.next();
            if(!addDocument(result,value))break;
        }
        return result.finish().put("encoding","bson_extended_json");
    }
    static boolean addDocument(NativeResults result,RawBsonDocument document)throws Exception {
        int bytes=document.getByteBuffer().remaining();
        // Keep the driver document raw until its size is known. Expanding a 16 MiB BSON
        // document into millions of Java/Jackson objects defeats bounded result admission.
        if(bytes>256*1024){
            result.incomplete("large_document_omitted");
            return result.add(Profiles.JSON.createObjectNode().put("documentOmitted",true).put("encodedBytes",bytes)
                    .put("guidance","Use a narrower projection; documents above 256 KiB are not expanded into the interactive result model."));
        }
        return result.add(Profiles.JSON.readTree(document.toJson(EXTENDED)));
    }

    private static ObjectNode redis(RedisClient client,NativeTarget target,JsonNode command,Limits limits,BooleanSupplier cancelled) {
        try(var connection=client.connect(ByteArrayCodec.INSTANCE)) {
            connection.setTimeout(java.time.Duration.ofSeconds(limits.timeoutSeconds()));
            var commands=connection.sync();commands.select(Integer.parseInt(target.database()));
            String operation=command.get(0).asText().toUpperCase(Locale.ROOT);
            var result=new NativeResults("values",limits.rows(),limits.bytes());
            switch(operation) {
                case "GET" -> {
                    arity(command,2);byte[] key=key(command,1);
                    String type=commands.type(key);
                    if(type.equals("none"))result.add(Profiles.JSON.createObjectNode().put("missing",true));
                    else {
                        if(!type.equals("string"))throw new IllegalArgumentException("Selected key is not a string; use its type-specific viewer");
                        byte[] preview=commands.getrange(key,0,8192);check(cancelled);
                        ObjectNode entry=NativeResults.binary(preview,8192);entry.put("previewOnly",true);
                        result.add(entry);
                    }
                }
                case "TYPE" -> { arity(command,2);result.add(Profiles.JSON.getNodeFactory().textNode(commands.type(key(command,1)))); }
                case "TTL" -> { arity(command,2);result.add(Profiles.JSON.getNodeFactory().numberNode(commands.ttl(key(command,1)))); }
                case "PTTL" -> { arity(command,2);result.add(Profiles.JSON.getNodeFactory().numberNode(commands.pttl(key(command,1)))); }
                case "STRLEN" -> { arity(command,2);result.add(Profiles.JSON.getNodeFactory().numberNode(commands.strlen(key(command,1)))); }
                case "DBSIZE" -> { arity(command,1);result.add(Profiles.JSON.getNodeFactory().numberNode(commands.dbsize())); }
                case "PING" -> { arity(command,1);result.add(Profiles.JSON.getNodeFactory().textNode(commands.ping())); }
                case "SCAN" -> {
                    if(command.size()!=2&&command.size()!=4)throw new IllegalArgumentException("Bounded SCAN accepts cursor and optional MATCH pattern; COUNT is application-managed");
                    String cursor=command.get(1).asText();if(!cursor.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid Redis scan cursor");
                    ScanArgs args=ScanArgs.Builder.limit(Math.min(64,limits.rows()));
                    if(command.size()==4) { if(!command.get(2).asText().equalsIgnoreCase("MATCH"))throw new IllegalArgumentException("Expected MATCH pattern");args.match(command.get(3).asText()); }
                    KeyScanCursor<byte[]> page=commands.scan(ScanCursor.of(cursor),args);
                    for(byte[] key:page.getKeys()){check(cancelled);if(!result.add(NativeResults.binary(key,8192)))break;}
                    ObjectNode out=result.finish();out.put("scanComplete",page.isFinished()&&!out.path("truncated").asBoolean());
                    if(!out.path("truncated").asBoolean())out.put("nextCursor",page.getCursor());
                    else out.put("requiresRefinement",true).put("warning","Scan page exceeded allowance; refine the pattern. No continuation is returned because entries would be skipped.");
                    return out;
                }
                default -> {return NativeRedisReads.execute(commands,command,limits,cancelled);}
            }
            check(cancelled);return result.finish();
        }
    }

    private static BsonDocument document(JsonNode value) {
        if(value.isMissingNode())return new BsonDocument();
        if(!value.isObject())throw new IllegalArgumentException("MongoDB filter/projection/stage must be an object");
        try{return BsonDocument.parse(value.toString());}catch(RuntimeException invalid){throw new IllegalArgumentException("Malformed BSON Extended JSON value");}
    }
    private static int number(JsonNode input,String field,int min,int max) {
        JsonNode value=input.path(field);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<min||value.intValue()>max)throw new IllegalArgumentException("Invalid "+field);return value.intValue();
    }
    private static int batch(Limits limits){return Math.min(MONGO_BATCH_DOCUMENTS,limits.rows()+1);}
    private static byte[] key(JsonNode command,int index) { byte[] value=NativeRedisArguments.bytes(command,index);if(value.length>8192)throw new IllegalArgumentException("Key exceeds interactive key allowance");return value; }
    private static void arity(JsonNode command,int count) { if(command.size()!=count)throw new IllegalArgumentException("Unexpected Redis arguments"); }
    private static void rejectOptions(JsonNode command,Set<String> allowed) { command.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new IllegalArgumentException("Option requires a verified adapter: "+name);}); }
    private static void check(BooleanSupplier cancelled) { if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException(); }
    private NativeReadExecutor() { }
}
