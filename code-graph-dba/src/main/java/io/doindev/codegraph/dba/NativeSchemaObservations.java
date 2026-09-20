package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.lettuce.core.*;
import io.lettuce.core.codec.ByteArrayCodec;
import org.bson.*;
import org.bson.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Bounded native definitions/observations. Samples contain types only, never application values. */
final class NativeSchemaObservations {
    private static final JsonWriterSettings JSON=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    static ObjectNode capture(NativeConnections.Lease lease,NativeTarget target,String id,int rows,int bytes,int seconds,int samples,BooleanSupplier cancelled)throws Exception{
        if(samples<0||samples>32)throw new IllegalArgumentException("sampleLimit must be 0..32");
        Builder output=new Builder(target,id,Math.min(100,rows),Math.min(512<<10,bytes/2));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        Runnable check=()->{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();if(System.nanoTime()>=deadline)throw new IllegalArgumentException("Native observation deadline exceeded");};
        if(lease.mongo!=null)mongo(lease,target,output,samples,seconds,check);else redis(lease,target,output,seconds,check);
        check.run();return output.finish();
    }
    private static void mongo(NativeConnections.Lease lease,NativeTarget target,Builder out,int samples,int seconds,Runnable check)throws Exception{
        var database=lease.mongo.getDatabase(target.database());
        var version=database.runCommand(new BsonDocument("buildInfo",new BsonInt32(1)).append("maxTimeMS",new BsonInt64(seconds*1000L)),RawBsonDocument.class);
        out.version(version.getString("version").getValue(),"MongoDB");
        var query=database.listCollections(RawBsonDocument.class).batchSize(8).maxTime(seconds,TimeUnit.SECONDS);
        if(!target.collection().isEmpty())query.filter(new BsonDocument("name",new BsonString(target.collection())));
        try(var cursor=query.iterator()){
            while(true){check.run();if(!cursor.hasNext())break;var raw=cursor.next();
                if(raw.getByteBuffer().remaining()>256*1024){out.incomplete("oversized_collection_definition");continue;}
                JsonNode definition=Profiles.JSON.readTree(raw.toJson(JSON));String name=definition.path("name").asText(),kind=definition.path("type").asText("collection");
                if(name.startsWith("system."))continue;
                ObjectNode object=Profiles.JSON.createObjectNode().put("name",name).put("kind",kind.equals("view")?"view":"collection").put("schema","").put("definitionLanguage","mongodb_extended_json");
                JsonNode options=definition.path("options");object.set("nativeDeclared",options.deepCopy());
                object.set("columns",declaredFields(options.path("validator")));object.put("columnCoverage","validator_declared_only").put("columnsComplete",options.path("validator").path("$jsonSchema").path("properties").size()<=128);
                if(!object.path("columnsComplete").asBoolean())out.incomplete("validator_field_limit");
                ArrayNode indexes=object.putArray("indexes");
                if(!kind.equals("view")){
                    try(var entries=database.getCollection(name).listIndexes(RawBsonDocument.class).batchSize(8).maxTime(seconds,TimeUnit.SECONDS).iterator()){
                        int indexBytes=0;
                        while(true){check.run();if(!entries.hasNext())break;var entry=entries.next();int size=entry.getByteBuffer().remaining();
                            if(indexes.size()>=64||size>65536||indexBytes+size>128*1024){out.incomplete("index_definition_limit");object.put("indexesComplete",false);break;}
                            JsonNode value=Profiles.JSON.readTree(entry.toJson(JSON));indexes.add(value);indexBytes+=size;
                        }
                    }
                    if(samples>0){
                        var observed=new TreeMap<String,Set<String>>();int sampled=0;
                        try(var documents=database.getCollection(name,RawBsonDocument.class).find().limit(samples).batchSize(8).maxTime(seconds,TimeUnit.SECONDS).iterator()){
                            while(true){check.run();if(!documents.hasNext())break;var document=documents.next();
                                if(document.getByteBuffer().remaining()>256*1024){out.incomplete("oversized_sample_omitted");continue;}
                                sampled++;int fields=0;
                                for(var field:document.entrySet()){
                                    if(++fields>128||!observed.containsKey(field.getKey())&&observed.size()>=128){out.incomplete("sample_field_limit");break;}
                                    observed.computeIfAbsent(field.getKey(),k->new TreeSet<>()).add(field.getValue().getBsonType().name().toLowerCase(Locale.ROOT));
                                }
                            }
                        }
                        ArrayNode values=object.putArray("fieldObservations");observed.forEach((field,types)->{var row=values.addObject().put("name",field).put("evidence","sampled_not_schema");var kinds=row.putArray("types");types.forEach(kinds::add);});
                        object.put("sampledDocuments",sampled).put("sampleComplete",false);
                    }
                }
                object.put("ddl",Profiles.JSON.createObjectNode().put("collection",name).set("options",options).toString());
                if(!out.add(object))break;
            }
        }
    }
    static ArrayNode declaredFields(JsonNode validator){
        var result=Profiles.JSON.createArrayNode();JsonNode schema=validator.path("$jsonSchema");var required=new HashSet<String>();schema.path("required").forEach(v->required.add(v.asText()));
        schema.path("properties").fields().forEachRemaining(field->{
            if(result.size()>=128)return;JsonNode type=field.getValue().path("bsonType");
            var row=result.addObject().put("name",field.getKey()).put("evidence","declared_validator").put("required",required.contains(field.getKey()));
            if(type.isTextual())row.put("type",type.asText());else if(type.isArray())row.set("types",type.deepCopy());
        });return result;
    }
    private static void redis(NativeConnections.Lease lease,NativeTarget target,Builder out,int seconds,Runnable check){
        try(var connection=NativeRedisSession.open(lease,target,seconds)){
            var commands=connection.sync();
            String info=connection.serverInfo();out.version(info.lines().filter(s->s.startsWith("redis_version:")).map(s->s.substring(14).strip()).findFirst().orElse("unknown"),"Redis");
            String cursor="0";Set<String> seen=new HashSet<>();boolean stopped=false;
            for(int page=0;page<16;page++){
                check.run();NativeRedisSession.Page next=connection.scan(cursor,ScanArgs.Builder.limit(32));
                for(byte[] key:next.keys()){
                    check.run();if(key.length>8192){out.incomplete("oversized_key_omitted");continue;}
                    String encoded=Base64.getEncoder().encodeToString(key);if(!seen.add(encoded))continue;
                    if(seen.size()>out.rows+1){stopped=true;break;}
                    String type=commands.type(key);if(type.equals("none")){out.incomplete("key_changed_during_observation");continue;}
                    long ttl=commands.pttl(key);String ttlClass=ttl==-2?"missing":ttl==-1?"persistent":"expiring";
                    ObjectNode object=Profiles.JSON.createObjectNode().put("kind","key").put("schema","").put("name",encoded).put("nameEncoding","base64").put("nativeType",type)
                        .put("ttlClass",ttlClass).put("observedTtlMillis",ttl).put("evidence","live_observation_not_schema").put("ddl","");
                    try{String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(key)).toString();object.put("displayName",text);int colon=text.indexOf(':');object.put("namespace",colon<0?"":text.substring(0,colon));}catch(java.nio.charset.CharacterCodingException ignored){object.put("namespace","");}
                    if(!out.add(object)){stopped=true;break;}
                }
                if(stopped||next.complete())break;cursor=next.cursor();if(page==15)out.incomplete("scan_work_limit");
            }
        }
    }
    static final class Builder {
        final ObjectNode value;final ArrayNode objects;final int rows,maximum;int bytes;final TreeMap<String,String> hashes=new TreeMap<>();
        Builder(NativeTarget target,String id,int rows,int maximum){this.rows=rows;this.maximum=maximum;value=Profiles.JSON.createObjectNode().put("format","codegraph-schema-v1").put("snapshotId",id).put("engine",target.transport().id).put("capturedAt",System.currentTimeMillis()).put("inventoryComplete",false).put("absenceProvesRemoval",false).put("truncated",false);value.set("target",target.json());objects=value.putArray("objects");value.putObject("coverage").put("inventoryComplete",false).put("kind","native_bounded_observation");}
        void version(String version,String product){if(version.length()>128)throw new IllegalArgumentException("Invalid native version response");value.putObject("version").put("product",product).put("server",version);}
        void incomplete(String reason){value.put("truncated",true);value.withObject("coverage").put("reason",reason);}
        boolean add(ObjectNode object){
            String id=CatalogScanner.hash(value.path("target").path("database").asText()+"\0"+object.path("kind").asText()+"\0"+object.path("name").asText());object.put("id",id);
            ObjectNode stable=object.deepCopy();stable.remove(List.of("observedTtlMillis","fieldObservations","sampledDocuments","sampleComplete"));String hash=CatalogScanner.hash(CatalogScanner.stable(stable));object.put("objectHash",hash);
            int size=object.toString().getBytes(StandardCharsets.UTF_8).length;
            if(objects.size()>=rows||bytes+size>maximum-8192){incomplete(objects.size()>=rows?"object_limit":"byte_limit");return false;}
            objects.add(object);bytes+=size;hashes.put(id,hash);return true;
        }
        ObjectNode finish(){value.put("bytes",bytes).put("fingerprint",CatalogScanner.hash(hashes.toString())).put("consistency","Live native observation, not a schema lock or complete inventory; sampled types and TTLs do not establish constraints");return value;}
    }
    private NativeSchemaObservations(){}
}
