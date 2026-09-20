package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.lettuce.core.*;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Finite, single-key stream operations. Delivery changes are writes, never safe reads. */
final class NativeRedisStreams {
    static final String NOTICE="One exact stream on one primary connection; no subscription, blocking wait, NOACK or automatic retry. Reading a consumer group changes pending/delivery state even for previously pending messages. Acknowledge exact IDs separately only after processing complete values. Truncated/omitted payloads are not processed messages. Group resets/deletion, trimming and entry deletion can lose delivery history/data. Cancellation or connection loss does not roll back effects; reconcile pending entries before any retry.";
    private static final Set<String> COMMANDS=Set.of("XADD","XDEL","XTRIM","XREAD","XREADGROUP","XACK","XPENDING","XCLAIM","XAUTOCLAIM","XGROUP");
    record Spec(String name,String action,int key,int group,int consumer,int count,NativeCommand.Effect effect){}
    static boolean handles(JsonNode command){return command.isArray()&&!command.isEmpty()&&command.get(0).isTextual()&&COMMANDS.contains(command.get(0).asText().toUpperCase(Locale.ROOT));}
    static NativeCommand.Classification classify(JsonNode command){var spec=parse(command);boolean read=spec.effect==NativeCommand.Effect.READ;return new NativeCommand.Classification("redis.stream."+(read?"read":"mutation"),spec.effect,read,NOTICE);}
    static Spec parse(JsonNode command){
        if(!handles(command))throw new IllegalArgumentException("Unsupported Redis stream command");
        String name=text(command,0).toUpperCase(Locale.ROOT),action=name;int key=1,group=-1,consumer=-1,count=1;
        NativeCommand.Effect effect=NativeCommand.Effect.WRITE;
        Set<Integer> binary=new HashSet<>();
        switch(name){
            case "XADD" -> {arity(command,5,203);if(command.size()%2!=1)throw bad("XADD requires field/value pairs");id(command,2,Set.of("*"));for(int i=3;i<command.size();i++)binary.add(i);}
            case "XDEL","XACK" -> {int start=name.equals("XDEL")?2:3;arity(command,start+1,start+100);if(start==3)group=2;for(int i=start;i<command.size();i++)id(command,i,Set.of());count=command.size()-start;effect=name.equals("XDEL")?NativeCommand.Effect.DESTRUCTIVE:NativeCommand.Effect.WRITE;}
            case "XTRIM" -> {arity(command,4,4);String mode=text(command,2).toUpperCase(Locale.ROOT);if(mode.equals("MAXLEN"))number(command,3,0,1_000_000_000);else if(mode.equals("MINID"))id(command,3,Set.of());else throw bad("XTRIM accepts exact MAXLEN or MINID only; no approximate/extra options");effect=NativeCommand.Effect.DESTRUCTIVE;}
            case "XREAD" -> {arity(command,6,6);keyword(command,1,"COUNT");count=(int)number(command,2,1,100);keyword(command,3,"STREAMS");key=4;id(command,5,Set.of("$"));effect=NativeCommand.Effect.READ;}
            case "XREADGROUP" -> {arity(command,9,9);keyword(command,1,"GROUP");group=2;consumer=3;keyword(command,4,"COUNT");count=(int)number(command,5,1,100);keyword(command,6,"STREAMS");key=7;id(command,8,Set.of(">"));}
            case "XPENDING" -> {arity(command,6,7);group=2;boundary(command,3);boundary(command,4);count=(int)number(command,5,1,100);if(command.size()==7)consumer=6;effect=NativeCommand.Effect.READ;}
            case "XCLAIM" -> {arity(command,6,105);group=2;consumer=3;number(command,4,0,Long.MAX_VALUE);for(int i=5;i<command.size();i++)id(command,i,Set.of());count=command.size()-5;}
            case "XAUTOCLAIM" -> {arity(command,8,8);group=2;consumer=3;number(command,4,0,Long.MAX_VALUE);id(command,5,Set.of());keyword(command,6,"COUNT");count=(int)number(command,7,1,100);}
            case "XGROUP" -> {
                arity(command,4,6);String sub=text(command,1).toUpperCase(Locale.ROOT);action="XGROUP "+sub;key=2;group=3;
                switch(sub){
                    case "CREATE" -> {arity(command,5,6);id(command,4,Set.of("$"));if(command.size()==6)keyword(command,5,"MKSTREAM");}
                    case "CREATECONSUMER","DELCONSUMER" -> {arity(command,5,5);consumer=4;if(sub.equals("DELCONSUMER"))effect=NativeCommand.Effect.DESTRUCTIVE;}
                    case "SETID" -> {arity(command,5,5);id(command,4,Set.of("$"));effect=NativeCommand.Effect.DESTRUCTIVE;}
                    case "DESTROY" -> {arity(command,4,4);effect=NativeCommand.Effect.DESTRUCTIVE;}
                    default -> throw bad("Supported XGROUP actions: CREATE, CREATECONSUMER, SETID, DELCONSUMER, DESTROY");
                }
            }
            default -> throw bad("Unsupported stream operation");
        }
        binary.add(key);if(group>=0)binary.add(group);if(consumer>=0)binary.add(consumer);
        for(int i=1;i<command.size();i++){
            if(binary.contains(i)){
                byte[] value=NativeRedisArguments.bytes(command,i);int max=name.equals("XADD")&&i>=4&&i%2==0?65536:8192;
                if(value.length>max||(i==key||i==group||i==consumer)&&value.length==0)throw bad("Stream key/group/consumer/field exceeds its allowance or a target name is empty");
            }else text(command,i);
        }
        return new Spec(name,action,key,group,consumer,count,effect);
    }
    static ObjectNode scope(JsonNode command){var spec=parse(command);var out=Profiles.JSON.createObjectNode().put("operation",spec.action);out.set("key",command.get(spec.key).deepCopy());if(spec.group>=0)out.set("group",command.get(spec.group).deepCopy());if(spec.consumer>=0)out.set("consumer",command.get(spec.consumer).deepCopy());return out;}
    static ObjectNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job,Runnable authority){
        var spec=parse(command);boolean mutation=spec.effect!=NativeCommand.Effect.READ;
        job.outcome="not_started";
        if(spec.count>job.rowLimit)throw bad("Stream count exceeds the configured result-row allowance; submit a smaller request");
        int receiptReserve=8192+(spec.name.equals("XAUTOCLAIM")?spec.count*11:spec.count)*64;
        if(job.byteLimit-receiptReserve<16384)throw bad("Stream receipt cannot fit the result allowance; lower COUNT before execution");
        check(job,authority);
        try(var session=NativeRedisSession.open(lease,target,job.remainingSeconds())){
            // A pinned primary socket prevents cross-node command replay after uncertain writes.
            var socket=session.transactionConnection(NativeRedisArguments.bytes(command,spec.key));
            socket.setTimeout(java.time.Duration.ofSeconds(job.remainingSeconds()));
            var args=new CommandArgs<byte[],byte[]>(ByteArrayCodec.INSTANCE);for(int i=1;i<command.size();i++)args.add(NativeRedisArguments.bytes(command,i));
            check(job,authority);job.progress="Executing bounded Redis stream operation";
            if(mutation)job.outcome="partial_or_unknown";
            List<Object> raw=socket.sync().dispatch(CommandType.valueOf(spec.name),new NestedMultiOutput<>(ByteArrayCodec.INSTANCE),args);
            ObjectNode result=project(spec,raw,command,job,receiptReserve);result.set("target",target.json());
            result.set("classification",classify(command).json());result.put("consistency","live_non_snapshot").put("hardMemoryLimit",false);
            job.outcome=mutation?"acknowledged":"read";result.put("outcome",job.outcome);retain(job,result);
            check(job,authority);return result;
        }catch(SecurityException denied){job.result=null;throw denied;}
        catch(RedisCommandExecutionException rejected){
            job.outcome=mutation?"rejected":"not_started";
            retain(job,Profiles.JSON.createObjectNode().put("kind","stream").put("operation",spec.action).put("outcome",job.outcome).put("notice","Redis rejected this one command. No automatic retry or acknowledgement occurred."));
            throw rejected;
        }
    }
    static ObjectNode project(Spec spec,List<Object> raw,JsonNode command,QueryJobs.Job job,int reserve){
        var rows=new NativeResults("stream",job.rowLimit,job.byteLimit-reserve);
        ObjectNode out=rows.finish().put("operation",spec.action).put("mutation",spec.effect!=NativeCommand.Effect.READ).put("notice",NOTICE).put("subscriptionRetained",false).put("automaticAcknowledgement",false);
        List<?> messages=List.of();var delivered=out.putArray("deliveredIds");
        switch(spec.name){
            case "XREAD","XREADGROUP" -> {
                if(!(raw.size()==1&&raw.getFirst()==null)&&!raw.isEmpty()){
                    // RESP2 is [[key, entries]]; RESP3 is a flattened single-entry map.
                    List<?> stream;if(raw.size()==2&&raw.getFirst() instanceof byte[])stream=raw;
                    else if(raw.size()==1)stream=list(raw.getFirst());
                    else throw bad("Unexpected multi-stream reply; reconcile delivery state");
                    if(stream.size()!=2||!(stream.get(0) instanceof byte[] key)||!Arrays.equals(key,NativeRedisArguments.bytes(command,spec.key)))throw bad("Unexpected stream target in reply");messages=list(stream.get(1));
                }
            }
            case "XCLAIM" -> messages=raw;
            case "XAUTOCLAIM" -> {
                if(raw.size()!=2&&raw.size()!=3)throw bad("Unsupported XAUTOCLAIM reply; reconcile pending entries");String next=replyId(raw.getFirst());out.put("nextCursor",next).put("scanComplete",next.equals("0-0"));messages=list(raw.get(1));
                var deleted=out.putArray("deletedPendingIds");out.put("deletedPendingIdsReported",raw.size()==3);
                if(raw.size()==3){var removed=list(raw.get(2));if(removed.size()>spec.count*10)throw bad("Auto-claim removal receipt exceeds its inspected-entry allowance");for(Object value:removed)deleted.add(replyId(value));}
            }
            case "XPENDING" -> {
                if(raw.size()>spec.count)throw bad("Pending reply exceeds reviewed count");
                for(Object value:raw){var tuple=list(value);if(tuple.size()!=4)throw bad("Unsupported pending-entry reply");var entry=Profiles.JSON.createObjectNode().put("id",replyId(tuple.get(0))).put("idleMillis",integerReply(tuple.get(2))).put("deliveryCount",integerReply(tuple.get(3)));entry.set("consumer",NativeResults.binary(binary(tuple.get(1)),8192));if(!rows.add(entry))break;}
            }
            default -> {
                if(raw.size()!=1)throw bad("Unexpected stream mutation receipt; reconcile before retry");Object value=raw.getFirst();out.set("value",value instanceof Number?TextNode.valueOf(integerReply(value)):TextNode.valueOf(value instanceof byte[]?new String((byte[])value,StandardCharsets.UTF_8):Objects.toString(value)));
            }
        }
        if(messages.size()>spec.count)throw bad("Stream delivered more messages than reviewed; reconcile pending entries");
        // Receipt IDs are retained independently of payload projection; truncated bodies must not be ACKed automatically.
        for(Object value:messages){var tuple=list(value);if(tuple.size()!=2)throw bad("Unsupported stream entry");String id=replyId(tuple.getFirst());if(spec.effect!=NativeCommand.Effect.READ)delivered.add(id);}
        for(Object value:messages){var tuple=list(value);String id=replyId(tuple.getFirst());var entry=Profiles.JSON.createObjectNode().put("id",id);var fields=entry.putArray("fields");
            if(tuple.get(1)==null)entry.put("bodyMissing",true);
            else{var pairs=list(tuple.get(1));if(pairs.size()%2!=0)throw bad("Malformed stream field/value pairs");int bytes=0;
                for(int i=0;i<pairs.size();i+=2){byte[] field=binary(pairs.get(i)),data=binary(pairs.get(i+1));if(i>=200||bytes>=16384){entry.put("fieldsTruncated",true);break;}var pair=fields.addObject();pair.set("field",NativeResults.binary(field,8192));pair.set("value",NativeResults.binary(data,8192));bytes+=Math.min(field.length,8192)+Math.min(data.length,8192);if(field.length>8192||data.length>8192)entry.put("fieldsTruncated",true);}
            }
            if(entry.path("fieldsTruncated").asBoolean())rows.incomplete("stream_fields_previewed");
            if(!rows.add(entry))break;if(spec.name.equals("XREAD"))out.put("nextId",id);
        }
        if(spec.name.equals("XREADGROUP")||spec.name.equals("XCLAIM")||spec.name.equals("XAUTOCLAIM"))out.put("pendingDeliveryStateChanged",true).put("acknowledgementRequired",!delivered.isEmpty());
        return rows.finish();
    }
    private static List<?> list(Object value){if(!(value instanceof List<?> result))throw bad("Unsupported Redis stream reply shape");return result;}
    private static byte[] binary(Object value){if(!(value instanceof byte[] result))throw bad("Unsupported Redis stream value type");return result;}
    private static String replyId(Object value){String id=new String(binary(value),StandardCharsets.US_ASCII);validateId(id,Set.of());return id;}
    private static String integerReply(Object value){if(!(value instanceof Long))throw bad("Unsupported Redis integer receipt");return value.toString();}
    private static void retain(QueryJobs.Job job,ObjectNode result){try{int size=Profiles.JSON.writeValueAsBytes(result).length;if(size>job.byteLimit)throw bad("Stream result exceeds allowance; reconcile before retrying a mutation");job.result=result;job.bytes=size;}catch(java.io.IOException invalid){throw bad("Cannot encode stream receipt");}}
    private static void check(QueryJobs.Job job,Runnable authority){if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();authority.run();if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static String text(JsonNode command,int index){return NativeRedisArguments.text(command,index);}
    private static long number(JsonNode command,int index,long min,long max){String text=text(command,index);try{if(!text.matches("[0-9]{1,19}"))throw new NumberFormatException();long n=Long.parseLong(text);if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException invalid){throw bad("Numeric stream argument must be "+min+".."+max);}}
    private static void boundary(JsonNode command,int index){String value=text(command,index);if(value.startsWith("("))value=value.substring(1);validateId(value,Set.of("-","+"));}
    private static void id(JsonNode command,int index,Set<String> special){validateId(text(command,index),special);}
    private static void validateId(String value,Set<String> special){if(special.contains(value))return;if(!value.matches("[0-9]{1,20}-[0-9]{1,20}"))throw bad("Use a complete stream ID (milliseconds-sequence) or the permitted boundary marker");for(String part:value.split("-"))if(new BigInteger(part).bitLength()>64)throw bad("Stream ID part exceeds unsigned 64-bit range");}
    private static void keyword(JsonNode command,int index,String expected){if(!text(command,index).equalsIgnoreCase(expected))throw bad("Expected "+expected+"; blocking, NOACK, multiple streams and extra options are unsupported");}
    private static void arity(JsonNode command,int min,int max){if(command.size()<min||command.size()>max)throw bad("Unsupported stream argument count; use the documented finite single-stream form");}
    private static IllegalArgumentException bad(String text){return new IllegalArgumentException(text);}
    private NativeRedisStreams(){}
}
