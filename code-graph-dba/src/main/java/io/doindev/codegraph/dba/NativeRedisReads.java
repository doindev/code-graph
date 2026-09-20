package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.lettuce.core.*;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Type-specific reads. Bounded ranges/cursors plus the transport's wire budget. */
final class NativeRedisReads {
    static ObjectNode execute(RedisClusterCommands<byte[],byte[]> redis,JsonNode command,
            NativeReadExecutor.Limits limits,BooleanSupplier cancelled){
        String name=command.get(0).asText().toUpperCase(Locale.ROOT);
        var out=new NativeResults("values",limits.rows(),limits.bytes());
        byte[] key=command.size()>1?bytes(command,1):null;
        switch(name){
            case "EXISTS" -> {arity(command,2);scalar(out,redis.exists(key));}
            case "EXPIRETIME" -> {arity(command,2);scalar(out,redis.expiretime(key));}
            case "PEXPIRETIME" -> {arity(command,2);scalar(out,redis.pexpiretime(key));}
            case "HLEN" -> {arity(command,2);scalar(out,redis.hlen(key));}
            case "LLEN" -> {arity(command,2);scalar(out,redis.llen(key));}
            case "SCARD" -> {arity(command,2);scalar(out,redis.scard(key));}
            case "ZCARD" -> {arity(command,2);scalar(out,redis.zcard(key));}
            case "XLEN" -> {arity(command,2);scalar(out,redis.xlen(key));}
            case "HEXISTS" -> {arity(command,3);scalar(out,redis.hexists(key,bytes(command,2)));}
            case "SISMEMBER" -> {arity(command,3);scalar(out,redis.sismember(key,bytes(command,2)));}
            case "ZSCORE" -> {arity(command,3);scalar(out,redis.zscore(key,bytes(command,2)));}
            case "GETBIT" -> {arity(command,3);scalar(out,redis.getbit(key,index(command,2)));}
            case "GETRANGE" -> {
                arity(command,4);long start=index(command,2),end=index(command,3);
                if(start<0||end<start||end-start>8191)throw new IllegalArgumentException("GETRANGE requires an ordered, nonnegative range of at most 8192 bytes");
                out.add(NativeResults.binary(redis.getrange(key,start,end),8192));
            }
            case "HGET" -> {arity(command,3);out.add(NativeResults.binary(redis.hget(key,bytes(command,2)),8192));}
            case "LINDEX" -> {arity(command,3);out.add(NativeResults.binary(redis.lindex(key,index(command,2)),8192));}
            case "LRANGE","ZRANGE","ZREVRANGE" -> {
                arity(command,4);long start=index(command,2),end=index(command,3);
                if(start<0||end<start||end-start>=limits.rows())throw new IllegalArgumentException("Use a nonnegative range with at most "+limits.rows()+" entries");
                if(name.equals("LRANGE")){
                    for(byte[] value:redis.lrange(key,start,end)){check(cancelled);if(!out.add(NativeResults.binary(value,8192)))break;}
                }else{
                    var values=name.equals("ZRANGE")?redis.zrangeWithScores(key,start,end):redis.zrevrangeWithScores(key,start,end);
                    for(var value:values){check(cancelled);if(!out.add(scored(value)))break;}
                }
            }
            case "HSCAN","SSCAN","ZSCAN" -> {
                if(command.size()!=3&&command.size()!=5)throw new IllegalArgumentException(name+" accepts key, cursor and optional MATCH pattern; COUNT is application-managed");
                String cursor=command.get(2).asText();if(!cursor.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid cursor");
                ScanArgs args=ScanArgs.Builder.limit(Math.min(64,limits.rows()));
                if(command.size()==5){if(!command.get(3).asText().equalsIgnoreCase("MATCH"))throw new IllegalArgumentException("Expected MATCH");args.match(command.get(4).asText());}
                ScanCursor page;
                if(name.equals("HSCAN")){
                    var found=redis.hscan(key,ScanCursor.of(cursor),args);page=found;
                    for(var entry:found.getMap().entrySet()){
                        check(cancelled);var value=Profiles.JSON.createObjectNode();
                        value.set("field",NativeResults.binary(entry.getKey(),8192));value.set("value",NativeResults.binary(entry.getValue(),8192));
                        if(!out.add(value))break;
                    }
                }else if(name.equals("SSCAN")){
                    var found=redis.sscan(key,ScanCursor.of(cursor),args);page=found;
                    for(byte[] value:found.getValues()){check(cancelled);if(!out.add(NativeResults.binary(value,8192)))break;}
                }else{
                    var found=redis.zscan(key,ScanCursor.of(cursor),args);page=found;
                    for(var value:found.getValues()){check(cancelled);if(!out.add(scored(value)))break;}
                }
                ObjectNode result=out.finish();result.put("scanComplete",page.isFinished()&&!result.path("truncated").asBoolean());
                if(!result.path("truncated").asBoolean())result.put("nextCursor",page.getCursor());
                else result.put("requiresRefinement",true).put("warning","Page exceeded allowance; refine MATCH. No cursor is returned because entries would be skipped.");
                return result;
            }
            case "XRANGE","XREVRANGE" -> {
                arity(command,4);String start=command.get(2).asText(),end=command.get(3).asText();
                for(String boundary:List.of(start,end))if(!boundary.matches("[-+]|\\(?[0-9]{1,20}(?:-[0-9]{1,20})?"))throw new IllegalArgumentException("Use explicit stream IDs, - or +");
                // Lettuce Range always uses lower/upper order, even for Redis' reversed syntax.
                var range=name.equals("XRANGE")?Range.create(start,end):Range.create(end,start);
                var values=name.equals("XRANGE")?redis.xrange(key,range,Limit.from(limits.rows()+1)):redis.xrevrange(key,range,Limit.from(limits.rows()+1));
                for(var message:values){
                    check(cancelled);var entry=Profiles.JSON.createObjectNode().put("id",message.getId());var fields=entry.putArray("fields");
                    int count=0,bytes=0;
                    for(var field:message.getBody().entrySet()){
                        if(++count>100||bytes>16384){entry.put("fieldsTruncated",true);break;}
                        var value=fields.addObject();value.set("field",NativeResults.binary(field.getKey(),8192));value.set("value",NativeResults.binary(field.getValue(),8192));
                        bytes+=Math.min(field.getKey().length,8192)+Math.min(field.getValue().length,8192);
                    }
                    if(!out.add(entry))break;
                }
            }
            default -> throw new IllegalArgumentException("This Redis command needs a bounded reply adapter. Use HSCAN/SSCAN/ZSCAN or bounded LRANGE/XRANGE, not whole-container reads.");
        }
        check(cancelled);return out.finish();
    }
    private static ObjectNode scored(ScoredValue<byte[]> value){var out=Profiles.JSON.createObjectNode().put("score",Double.toString(value.getScore()));out.set("value",NativeResults.binary(value.getValue(),8192));return out;}
    private static void scalar(NativeResults result,Object value){result.add(Profiles.JSON.valueToTree(value));}
    private static byte[] bytes(JsonNode command,int i){byte[] out=NativeRedisArguments.bytes(command,i);if(out.length>8192)throw new IllegalArgumentException("Key/field exceeds 8192-byte interactive allowance");return out;}
    private static long index(JsonNode command,int i){String value=command.get(i).asText();if(!value.matches("-?[0-9]{1,15}"))throw new IllegalArgumentException("Bounded integer index required");return Long.parseLong(value);}
    private static void arity(JsonNode command,int count){if(command.size()!=count)throw new IllegalArgumentException("Unexpected Redis argument count");}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private NativeRedisReads(){}
}
