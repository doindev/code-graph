package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** Reviewed retention/size settings; no replacement, index conversion or system administration. */
final class MongoCollectionSettings {
    private static final List<String> GRANULARITIES=List.of("seconds","minutes","hours");
    static boolean handles(JsonNode command){
        return command.has("collMod")&&(command.has("index")||command.has("expireAfterSeconds")
                ||command.has("timeseries")||command.has("cappedSize")||command.has("cappedMax"));
    }
    static boolean destructive(JsonNode command){return !command.has("timeseries");}

    static void validate(NativeTarget target,JsonNode command){
        if(Set.of("admin","config","local").contains(target.database())||target.collection().isBlank()
                ||target.collection().startsWith("system.")||target.collection().contains("$"))
            throw new IllegalArgumentException("Collection settings require an exact non-system database and collection");
        if(!command.path("collMod").isTextual()||!command.path("collMod").asText().equals(target.collection()))
            throw new IllegalArgumentException("collMod must exactly match the selected collection");
        if(command.has("index")){
            fields(command,Set.of("collMod","index"));JsonNode index=command.path("index");
            fields(index,Set.of("name","expireAfterSeconds"));
            String name=NativeTarget.text(index,"name",255);
            if(name.equals("*")||name.equals("_id_"))throw new IllegalArgumentException("Supply the exact name of an existing non-primary TTL index");
            integer(index,"expireAfterSeconds",0,Integer.MAX_VALUE);
        }else if(command.has("expireAfterSeconds")){
            fields(command,Set.of("collMod","expireAfterSeconds"));JsonNode value=command.path("expireAfterSeconds");
            if(!(value.isTextual()&&value.asText().equals("off")))integer(command,"expireAfterSeconds",0,Integer.MAX_VALUE);
        }else if(command.has("timeseries")){
            fields(command,Set.of("collMod","timeseries"));JsonNode options=command.path("timeseries");
            fields(options,Set.of("granularity"));
            if(!options.path("granularity").isTextual()||!GRANULARITIES.contains(options.path("granularity").asText()))
                throw new IllegalArgumentException("Time-series granularity must be seconds, minutes or hours; custom bucket changes are not enabled");
        }else{
            fields(command,Set.of("collMod","cappedSize","cappedMax"));
            if(command.has("cappedSize"))integer(command,"cappedSize",1,999_999_999_999_999L);
            if(command.has("cappedMax"))integer(command,"cappedMax",0,Integer.MAX_VALUE);
        }
    }

    static void describe(ObjectNode review,NativeTarget target,JsonNode command){
        review.putArray("affectedNamespaces").add(target.database()+"."+target.collection());
        String risk=command.has("index")?"Changing an existing TTL index can permanently delete expired documents in the background. Non-TTL index conversion is not enabled. "
                :command.has("expireAfterSeconds")?"Time-series retention can permanently delete expired measurements in the background; off disables future automatic expiry, not past deletion. "
                :command.has("timeseries")?"Time-series granularity can only increase; custom bucket configurations are not supported. This may affect storage and query performance. "
                :"Reducing capped limits can permanently delete older documents; cappedMax 0 removes the document-count limit, not the byte limit. ";
        review.put("transactionNotice",risk+"The exact collection kind and settings are checked before execution. Collection locks may block other work. No automatic rollback or retry is provided; reconcile uncertain outcomes and refresh metadata after success. Concurrent external definition changes are still possible.");
    }

    static void checkSource(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job){
        checkDefinition(command,MongoCollectionMetadata.load(lease,target,job));
        if(command.has("index")){
            String name=command.path("index").path("name").asText();long bytes=0;int inspected=0;
            try(var cursor=lease.mongo.getDatabase(target.database()).getCollection(target.collection()).listIndexes(RawBsonDocument.class)
                    .batchSize(1).maxTime(job.remainingSeconds(),TimeUnit.SECONDS).iterator()){
                while(cursor.hasNext()){
                    if(job.cancelled)throw new CancellationException();
                    RawBsonDocument index=cursor.next();int size=index.getByteBuffer().remaining();bytes+=size;
                    if(++inspected>128||size>256*1024||bytes>1<<20)throw new IllegalArgumentException("Index metadata exceeds the bounded settings validation allowance");
                    if(index.getString("name",new BsonString("")).getValue().equals(name)){
                        if(!index.containsKey("expireAfterSeconds")||!index.get("expireAfterSeconds").isNumber()
                                ||index.getDocument("key",new BsonDocument()).size()!=1)
                            throw new IllegalArgumentException("Only an existing single-field TTL index can be changed; conversion is not enabled");
                        return;
                    }
                }
            }
            throw new IllegalArgumentException("TTL index no longer exists; refresh metadata before retrying");
        }
        if(job.cancelled)throw new CancellationException();
    }

    static void checkDefinition(JsonNode command,BsonDocument source){
        String type=source.getString("type",new BsonString("")).getValue();
        var options=source.getDocument("options",new BsonDocument());
        boolean series=type.equals("timeseries")||options.containsKey("timeseries");
        if(!Set.of("collection","timeseries").contains(type))throw new IllegalArgumentException("These settings do not apply to views");
        if(command.has("expireAfterSeconds")||command.has("timeseries")){
            if(!series)throw new IllegalArgumentException("Collection retention/granularity settings require a time-series collection");
            if(command.has("timeseries")){
                var current=options.getDocument("timeseries",new BsonDocument());
                if(current.containsKey("bucketRoundingSeconds"))throw new IllegalArgumentException("Custom time-series bucketing is not supported by this adapter");
                int previous=GRANULARITIES.indexOf(current.getString("granularity",new BsonString("seconds")).getValue());
                int next=GRANULARITIES.indexOf(command.path("timeseries").path("granularity").asText());
                if(previous<0||next<previous)throw new IllegalArgumentException("Time-series granularity cannot be decreased; refresh the current definition");
            }
        }else if(command.has("cappedSize")||command.has("cappedMax")){
            if(series||!options.getBoolean("capped",BsonBoolean.FALSE).getValue())throw new IllegalArgumentException("Capped limits require an existing capped collection");
        }else if(series||options.getBoolean("capped",BsonBoolean.FALSE).getValue())
            throw new IllegalArgumentException("TTL index changes require an ordinary, non-capped collection");
    }
    private static void integer(JsonNode input,String field,long min,long max){
        JsonNode value=input.path(field);
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.longValue()<min||value.longValue()>max)
            throw new IllegalArgumentException(field+" must be an integer from "+min+" to "+max);
    }
    private static void fields(JsonNode input,Set<String> allowed){
        if(!input.isObject())throw new IllegalArgumentException("Collection setting options must be objects");
        input.fieldNames().forEachRemaining(field->{if(!allowed.contains(field))throw new IllegalArgumentException("Unsupported or mixed collection setting: "+field);});
    }
    private MongoCollectionSettings(){}
}
