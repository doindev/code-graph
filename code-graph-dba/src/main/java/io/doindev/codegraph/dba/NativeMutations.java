package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.*;
import java.util.*;

/** Reviewed, single-target CRUD. No scripts, server administration, implicit retries or w:0. */
final class NativeMutations {
    static void validate(NativeTarget target,JsonNode command){
        NativeCommand.classify(target,command);
        if(target.transport()==DatabaseTransport.MONGODB){
            if(command.has("transaction"))return; // Managed CRUD batch already structurally validated.
            String name=command.fieldNames().next();
            switch(name){
                case "insert" -> {
                    fields(command,Set.of("insert","documents","ordered"));
                    array(command.path("documents"),"documents");
                    for(JsonNode item:command.path("documents"))if(!item.isObject()||item.isEmpty())throw new IllegalArgumentException("Insert documents must be nonempty objects");
                }
                case "update" -> {
                    fields(command,Set.of("update","updates","ordered"));array(command.path("updates"),"updates");
                    for(JsonNode item:command.path("updates")){
                        fields(item,Set.of("q","u","multi","upsert","arrayFilters","collation"));
                        if(!item.path("q").isObject()||item.path("q").isEmpty())throw new IllegalArgumentException("Single-document updates require a nonempty filter");
                        if(!item.path("u").isObject()&&!item.path("u").isArray())throw new IllegalArgumentException("Update must be a document or pipeline");
                        if(item.has("multi")&&(!item.path("multi").isBoolean()||item.path("multi").asBoolean()))throw new IllegalArgumentException("Multi-document writes require a dedicated bounded bulk workflow");
                        if(item.has("upsert")&&!item.path("upsert").isBoolean())throw new IllegalArgumentException("upsert must be boolean");
                    }
                }
                case "delete" -> {
                    fields(command,Set.of("delete","deletes","ordered"));array(command.path("deletes"),"deletes");
                    for(JsonNode item:command.path("deletes")){
                        fields(item,Set.of("q","limit","collation"));
                        if(!item.path("q").isObject()||item.path("q").isEmpty()||!item.path("limit").isIntegralNumber()||item.path("limit").asInt()!=1)
                            throw new IllegalArgumentException("Deletion requires a nonempty filter and limit: 1; unbounded deletion is not enabled");
                    }
                }
                case "create" -> {
                    fields(command,Set.of("create","validator","validationLevel","validationAction","collation","capped","size","max","timeseries","expireAfterSeconds","viewOn","pipeline"));
                    if(command.has("viewOn")||command.has("pipeline")){
                        fields(command,Set.of("create","viewOn","pipeline","collation"));
                        view(target,command);
                    }
                }
                case "collMod" -> {
                    if(MongoCollectionSettings.handles(command)){MongoCollectionSettings.validate(target,command);break;}
                    fields(command,Set.of("collMod","validator","validationLevel","validationAction","viewOn","pipeline"));
                    if(command.has("viewOn")||command.has("pipeline")){
                        fields(command,Set.of("collMod","viewOn","pipeline"));
                        view(target,command);
                    }else if(command.size()==1)throw new IllegalArgumentException("Supply an explicit validator or validation setting");
                }
                case "createIndexes" -> {fields(command,Set.of("createIndexes","indexes"));array(command.path("indexes"),"indexes");}
                case "renameCollection" -> MongoCollectionRename.validate(target,command);
                case "drop" -> fields(command,Set.of("drop"));
                case "dropIndexes" -> {fields(command,Set.of("dropIndexes","index"));if(!command.path("index").isTextual()||command.path("index").asText().isBlank())throw new IllegalArgumentException("An exact index name or explicitly reviewed * is required");}
                default -> throw new IllegalArgumentException("No verified native mutation adapter for this MongoDB command");
            }
            if(command.has("ordered")&&!command.path("ordered").isBoolean())throw new IllegalArgumentException("ordered must be boolean");
        }else{
            if(command.isObject())return; // Managed transactions/pipelines were fully validated by classification above.
            if(NativeRedisStreams.handles(command)||NativeRedisValues.handles(command))return; // Reviewed separately on a pinned operation connection.
            String name=command.get(0).asText().toUpperCase(Locale.ROOT);int size=command.size();
            switch(name){
                case "SET" -> {arity(size,3,6);setOptions(command);}
                case "RENAME","RENAMENX" -> arity(size,3,3);
                case "EXPIRE","PEXPIRE" -> {arity(size,3,3);integer(command.get(2).asText());}
                case "PERSIST" -> arity(size,2,2);
                case "HSET" -> {arity(size,4,202);if(size%2!=0)throw new IllegalArgumentException("HSET requires field/value pairs");}
                case "LPUSH","RPUSH","SADD","HDEL","SREM","ZREM" -> arity(size,3,102);
                case "LSET" -> {arity(size,4,4);NativeRedisTransactions.listIndex(command.get(2));if(bytes(command,3).length>65536)throw new IllegalArgumentException("LSET value exceeds 64 KiB");}
                case "DEL","UNLINK" -> arity(size,2,101);
                case "ZADD" -> {arity(size,4,202);if(size%2!=0)throw new IllegalArgumentException("ZADD requires score/member pairs");for(int i=2;i<size;i+=2)if(!Double.isFinite(Double.parseDouble(command.get(i).asText())))throw new IllegalArgumentException("Finite sorted-set scores required");}
                default -> throw new IllegalArgumentException("No verified native mutation adapter for this Redis command");
            }
        }
    }
    static JsonNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job,Runnable beforeWrite)throws Exception {
        validate(target,command);job.outcome="not_started";
        try{
            if(job.cancelled)throw new java.util.concurrent.CancellationException();
            if(target.transport()==DatabaseTransport.MONGODB){
                if(command.has("transaction"))return NativeMongoTransactions.execute(lease,target,command,job,beforeWrite);
                boolean rename=command.has("renameCollection");
                if(rename)MongoCollectionRename.checkSource(lease,target,job);
                if(MongoCollectionSettings.handles(command))MongoCollectionSettings.checkSource(lease,target,command,job);
                BsonDocument request=BsonDocument.parse(command.toString());
                request.put("maxTimeMS",new BsonInt64(job.remainingSeconds()*1000L));
                beforeWrite.run();
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                job.outcome="partial_or_unknown";
                RawBsonDocument reply=lease.mongo.getDatabase(rename?"admin":target.database()).runCommand(request,RawBsonDocument.class);
                var bounded=new NativeResults("mutation",1,job.byteLimit);NativeReadExecutor.addDocument(bounded,reply);
                boolean errors=reply.containsKey("writeErrors")||reply.containsKey("writeConcernError");
                ObjectNode result=bounded.finish().put("outcome",errors?"partial_or_unknown":"acknowledged");
                if(errors){job.result=result;throw new IllegalArgumentException("MongoDB reported write or write-concern errors; inspect the bounded reply and reconcile before retry");}
                if(rename)result.putObject("renamed").put("database",target.database()).put("from",target.collection()).put("to",MongoCollectionRename.validate(target,command));
                job.outcome="acknowledged";return result;
            }
            if(command.has("pipeline"))return NativeRedisPipelines.execute(lease,target,command,job,beforeWrite);
            if(command.isObject())return NativeRedisTransactions.execute(lease,target,command,job,beforeWrite);
            if(NativeRedisStreams.handles(command))return NativeRedisStreams.execute(lease,target,command,job,beforeWrite);
            if(NativeRedisValues.handles(command))return NativeRedisValues.execute(lease,target,command,job,beforeWrite);
            try(var connection=NativeRedisSession.open(lease,target,job.remainingSeconds())){
                var redis=connection.sync();if(job.cancelled)throw new java.util.concurrent.CancellationException();
                String name=command.get(0).asText().toUpperCase(Locale.ROOT);byte[] key=bytes(command,1);Object value;
                if(target.topology().equals("cluster")&&Set.of("DEL","UNLINK","RENAME","RENAMENX").contains(name)){
                    int slot=io.lettuce.core.cluster.SlotHash.getSlot(key);for(int i=2;i<command.size();i++)if(io.lettuce.core.cluster.SlotHash.getSlot(bytes(command,i))!=slot)throw new IllegalArgumentException("Cluster multi-key mutations must use one hash slot; no cross-shard partial execution is attempted");
                }
                beforeWrite.run();if(job.cancelled)throw new java.util.concurrent.CancellationException();
                job.outcome="partial_or_unknown";
                value=redisMutation(redis,command);
                job.outcome="acknowledged";ObjectNode result=Profiles.JSON.createObjectNode().put("kind","mutation").put("outcome",job.outcome);
                result.set("value",Profiles.JSON.valueToTree(value));if(name.equals("SET"))result.put("applied",value!=null);return result;
            }
        }finally{ /* Any transport loss/cancellation after submission remains partial_or_unknown. */ }
    }

    /** The same validated scalar-reply adapters serve individual writes and queued transactions. */
    static Object redisMutation(io.lettuce.core.cluster.api.sync.RedisClusterCommands<byte[],byte[]> redis,JsonNode command){
        String name=command.get(0).asText().toUpperCase(Locale.ROOT);byte[] key=bytes(command,1);
        return switch(name){
            case "SET" -> redis.set(key,bytes(command,2),setOptions(command));
            case "DEL" -> redis.del(tail(command,1));
            case "UNLINK" -> redis.unlink(tail(command,1));
            case "RENAME" -> redis.rename(key,bytes(command,2));
            case "RENAMENX" -> redis.renamenx(key,bytes(command,2));
            case "EXPIRE" -> redis.expire(key,integer(command.get(2).asText()));
            case "PEXPIRE" -> redis.pexpire(key,integer(command.get(2).asText()));
            case "PERSIST" -> redis.persist(key);
            case "HSET" -> {
                Map<byte[],byte[]> fields=new LinkedHashMap<>();
                for(int i=2;i<command.size();i+=2)fields.put(bytes(command,i),bytes(command,i+1));
                yield redis.hset(key,fields);
            }
            case "HDEL" -> redis.hdel(key,tail(command,2));
            case "LPUSH" -> redis.lpush(key,tail(command,2));
            case "RPUSH" -> redis.rpush(key,tail(command,2));
            case "LSET" -> redis.lset(key,NativeRedisTransactions.listIndex(command.get(2)),bytes(command,3));
            case "SADD" -> redis.sadd(key,tail(command,2));
            case "SREM" -> redis.srem(key,tail(command,2));
            case "ZREM" -> redis.zrem(key,tail(command,2));
            case "ZADD" -> {
                List<io.lettuce.core.ScoredValue<byte[]>> members=new ArrayList<>();
                for(int i=2;i<command.size();i+=2)members.add(io.lettuce.core.ScoredValue.just(Double.parseDouble(command.get(i).asText()),bytes(command,i+1)));
                yield redis.zadd(key,members.toArray(io.lettuce.core.ScoredValue[]::new));
            }
            default -> throw new IllegalArgumentException("Unsupported native mutation");
        };
    }
    private static void fields(JsonNode value,Set<String> allowed){if(!value.isObject())throw new IllegalArgumentException("Native operation entry must be an object");value.fieldNames().forEachRemaining(key->{if(!allowed.contains(key))throw new IllegalArgumentException("Unsupported native operation field: "+key);});}
    private static void view(NativeTarget target,JsonNode command){
        String source=NativeTarget.text(command,"viewOn",255);
        if(source.startsWith("system.")||source.contains("$"))throw new IllegalArgumentException("System collections cannot be view sources");
        if(!command.path("pipeline").isArray())throw new IllegalArgumentException("Supply viewOn and an explicit pipeline array together");
        var query=Profiles.JSON.createObjectNode().put("aggregate",source);query.set("pipeline",command.path("pipeline"));
        var sourceTarget=new NativeTarget(target.transport(),target.connectionId(),target.connectionName(),target.database(),source,target.topology());
        if(!NativeCommand.classify(sourceTarget,query).reusableRead())throw new IllegalArgumentException("Views require a verified, read-only same-database pipeline; executable expressions, output stages and cross-namespace joins are not enabled");
    }
    private static void array(JsonNode value,String name){if(!value.isArray()||value.isEmpty()||value.size()>100)throw new IllegalArgumentException(name+" must contain 1..100 entries");}
    private static void arity(int count,int min,int max){if(count<min||count>max)throw new IllegalArgumentException("Unexpected native command argument count");}
    private static long integer(String value){if(!value.matches("-?[0-9]{1,18}"))throw new IllegalArgumentException("Bounded integer argument required");return Long.parseLong(value);}
    private static io.lettuce.core.SetArgs setOptions(JsonNode command){
        var args=new io.lettuce.core.SetArgs();boolean condition=false,expiry=false;
        for(int i=3;i<command.size();i++){
            String option=command.get(i).asText().toUpperCase(Locale.ROOT);
            switch(option){
                case "NX","XX" -> {
                    if(condition)throw new IllegalArgumentException("SET accepts only one of NX or XX");
                    condition=true;if(option.equals("NX"))args.nx();else args.xx();
                }
                case "KEEPTTL","EX","PX" -> {
                    if(expiry)throw new IllegalArgumentException("SET accepts only one expiry option");
                    expiry=true;
                    if(option.equals("KEEPTTL"))args.keepttl();
                    else{
                        if(++i>=command.size())throw new IllegalArgumentException("SET expiry requires a positive duration");
                        long duration=integer(command.get(i).asText());
                        if(duration<=0)throw new IllegalArgumentException("SET expiry requires a positive duration");
                        if(option.equals("EX"))args.ex(duration);else args.px(duration);
                    }
                }
                default -> throw new IllegalArgumentException("Supported SET options: NX or XX, plus EX/PX duration or KEEPTTL. GET and unknown options require another bounded adapter.");
            }
        }
        return args;
    }
    private static byte[] bytes(JsonNode command,int i){return NativeRedisArguments.bytes(command,i);}
    private static byte[][] tail(JsonNode command,int start){byte[][] values=new byte[command.size()-start][];for(int i=start;i<command.size();i++)values[i-start]=bytes(command,i);return values;}
    private NativeMutations(){}
}
