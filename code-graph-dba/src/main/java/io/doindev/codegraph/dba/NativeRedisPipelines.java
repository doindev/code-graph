package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.*;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.*;
import io.lettuce.core.protocol.*;
import java.util.*;
import java.util.concurrent.*;

/** A finite, single-socket pipeline. No transaction, retained socket, script or automatic replay. */
final class NativeRedisPipelines {
    static final int MAX_COMMANDS=32, MAX_KEYS=100;
    static final String NOTICE="Pipelines batch network writes, not transactions: commands run in order on one connection but other clients may interleave. An error does not stop commands already sent or undo writes. Cancellation/disconnection after dispatch can leave unknown outcomes. Inspect every receipt and reconcile before retry; no automatic retries. Cluster batches require one hash slot.";
    private static final Set<String> SIMPLE_READS=Set.of("TYPE","TTL","PTTL","STRLEN","EXISTS","EXPIRETIME","PEXPIRETIME","HLEN","LLEN","SCARD","ZCARD","XLEN");
    private static final Set<String> MEMBER_READS=Set.of("HEXISTS","SISMEMBER","ZSCORE","GETBIT");
    private static final Set<String> BOOLEAN_REPLIES=Set.of("RENAMENX","EXPIRE","PEXPIRE","PERSIST","HEXISTS","SISMEMBER");

    static NativeCommand.Classification classify(NativeTarget target,JsonNode command){
        if(!command.isObject()||command.size()!=1||!command.has("pipeline"))throw new IllegalArgumentException("Redis pipeline accepts only the pipeline field; no WATCH, transactions or connection overrides");
        JsonNode entries=command.path("pipeline");
        if(!entries.isArray()||entries.isEmpty()||entries.size()>MAX_COMMANDS)throw new IllegalArgumentException("Redis pipeline requires 1..32 supported argument arrays");
        NativeCommand.Effect effect=NativeCommand.Effect.READ;
        int references=0,slot=-1;
        for(JsonNode entry:entries){
            if(!entry.isArray()||entry.size()<2||NativeRedisStreams.handles(entry)||NativeRedisValues.handles(entry))throw new IllegalArgumentException("Pipeline entries require verified scalar/range commands with explicit keys; stream, bitmap, cardinality and geo workflows execute separately");
            var classification=NativeCommand.classify(target,entry);
            String name=name(entry);
            if(classification.effect()==NativeCommand.Effect.READ)validateRead(entry,name);
            else{
                NativeMutations.validate(target,entry);
                if(classification.effect()==NativeCommand.Effect.DESTRUCTIVE)effect=NativeCommand.Effect.DESTRUCTIVE;
                else if(effect==NativeCommand.Effect.READ)effect=NativeCommand.Effect.WRITE;
            }
            int end=Set.of("DEL","UNLINK").contains(name)?entry.size():Set.of("RENAME","RENAMENX").contains(name)?3:2;
            for(int i=1;i<end;i++){
                if(++references>MAX_KEYS)throw new IllegalArgumentException("Redis pipeline exceeds 100 key references");
                if(target.topology().equals("cluster")){
                    int found=SlotHash.getSlot(NativeRedisArguments.bytes(entry,i));
                    if(slot>=0&&slot!=found)throw new IllegalArgumentException("Redis Cluster pipelines require all keys in one hash slot; use a shared hash tag");
                    slot=found;
                }
            }
        }
        return new NativeCommand.Classification("redis.pipeline",effect,effect==NativeCommand.Effect.READ,NOTICE);
    }

    private static void validateRead(JsonNode entry,String name){
        if(name.equals("LINDEX")&&entry.size()==3){NativeRedisTransactions.listIndex(entry.get(2));return;}
        if(SIMPLE_READS.contains(name)&&entry.size()==2)return;
        if(MEMBER_READS.contains(name)&&entry.size()==3){
            if(name.equals("GETBIT"))number(entry,2,0,4294967295L);
            return;
        }
        if(name.equals("GETRANGE")&&entry.size()==4){
            long start=number(entry,2,0,Long.MAX_VALUE),end=number(entry,3,0,Long.MAX_VALUE);
            if(end>=start&&end-start<=8191)return;
        }
        throw new IllegalArgumentException("Pipeline reads support scalar key metadata/membership, LINDEX 0..9999 and nonnegative GETRANGE of at most 8192 bytes; no unbounded GET, scans or aggregate replies");
    }
    private static long number(JsonNode entry,int i,long min,long max){
        String value=NativeRedisArguments.text(entry,i);
        try{if(!value.matches("[0-9]{1,19}"))throw new NumberFormatException();long n=Long.parseLong(value);if(n<min||n>max)throw new NumberFormatException();return n;}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Redis pipeline numeric argument is outside its supported range");}
    }

    static ObjectNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job,Runnable authority)throws Exception{
        var classification=classify(target,command);
        JsonNode batch=command.path("pipeline");boolean mutation=classification.effect()!=NativeCommand.Effect.READ;
        if(batch.size()>job.rowLimit)throw new IllegalArgumentException("Pipeline receipt count exceeds the result-row allowance; submit a smaller reviewed batch");
        if(job.byteLimit<16384)throw new IllegalArgumentException("Pipeline requires at least 16 KiB for complete command receipts");
        ObjectNode result=Profiles.JSON.createObjectNode().put("kind","pipeline").put("atomic",false).put("rollbackSupported",false).put("truncated",false).put("notice",NOTICE);
        var receipts=result.putArray("entries");
        for(int i=0;i<batch.size();i++)receipts.addObject().put("index",i).put("command",name(batch.get(i))).put("state","not_sent");
        List<RedisFuture<?>> futures=new ArrayList<>();boolean sent=false;
        job.outcome="not_started";retain(job,result);
        try{
            check(job);authority.run();
            try(var session=NativeRedisSession.open(lease,target,job.remainingSeconds())){
                var connection=session.transactionConnection(NativeRedisArguments.bytes(batch.get(0),1));
                connection.setAutoFlushCommands(false);
                // Never restore auto-flush on this disposable socket: failed validation must not flush queued writes.
                for(JsonNode entry:batch){
                    check(job);String name=name(entry);
                    var args=new CommandArgs<byte[],byte[]>(ByteArrayCodec.INSTANCE);
                    for(int i=1;i<entry.size();i++)args.add(NativeRedisArguments.bytes(entry,i));
                    futures.add(connection.async().dispatch(CommandType.valueOf(name),output(name),args));
                }
                authority.run();check(job);
                sent=true;job.outcome=mutation?"partial_or_unknown":"read_incomplete";
                for(JsonNode receipt:receipts)((ObjectNode)receipt).put("state","unknown");
                retain(job,result);
                connection.flushCommands();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(job.remainingSeconds());
                for(RedisFuture<?> future:futures){
                    while(!future.isDone()){
                        check(job);authority.run();
                        if(System.nanoTime()>=deadline)throw new TimeoutException("Redis pipeline deadline exceeded");
                        try{future.get(100,TimeUnit.MILLISECONDS);}catch(TimeoutException pending){}catch(ExecutionException completed){break;}
                    }
                    check(job);authority.run();
                }
            }
        }finally{
            if(sent){
                int failures=0,unknown=0;
                for(int i=0;i<futures.size();i++){
                    var future=futures.get(i);var receipt=(ObjectNode)receipts.get(i);
                    if(!future.isDone()){unknown++;continue;}
                    try{
                        Object value=future.toCompletableFuture().join();receipt.put("state","acknowledged");
                        appendValue(result,receipt,value,job.byteLimit);
                    }catch(CompletionException|CancellationException failed){
                        Throwable cause=failed;while(cause.getCause()!=null)cause=cause.getCause();
                        if(cause instanceof RedisCommandExecutionException){failures++;receipt.put("state","rejected").put("error","Redis rejected this command; check key types, arguments and database permissions. Later commands may have succeeded.");}
                        else unknown++;
                    }
                }
                job.outcome=unknown>0?(mutation?"partial_or_unknown":"read_incomplete"):failures>0?(mutation?"partial":"read_failed"):mutation?"acknowledged":"read";
                result.put("unknownCount",unknown).put("rejectedCount",failures).put("dispatchedCount",batch.size());
            }else result.put("dispatchedCount",0);
            retain(job,result);
        }
        if(result.path("rejectedCount").asInt()>0||result.path("unknownCount").asInt()>0)throw new IllegalArgumentException("Pipeline did not fully succeed; inspect per-command receipts. No rollback or automatic retry was performed");
        return result;
    }

    private static CommandOutput<byte[],byte[],?> output(String name){
        if(Set.of("SET","RENAME","TYPE","LSET").contains(name))return new StatusOutput<>(ByteArrayCodec.INSTANCE);
        if(BOOLEAN_REPLIES.contains(name))return new BooleanOutput<>(ByteArrayCodec.INSTANCE);
        if(name.equals("GETRANGE")||name.equals("LINDEX"))return new ByteArrayOutput<>(ByteArrayCodec.INSTANCE);
        if(name.equals("ZSCORE"))return new DoubleOutput<>(ByteArrayCodec.INSTANCE);
        return new IntegerOutput<>(ByteArrayCodec.INSTANCE);
    }
    private static String name(JsonNode command){return NativeRedisArguments.text(command,0).toUpperCase(Locale.ROOT);}
    static void appendValue(ObjectNode result,ObjectNode receipt,Object value,int byteLimit){
        JsonNode encoded=value instanceof byte[] bytes?NativeResults.binary(bytes,8192):value instanceof Long n&&Math.abs((double)n)>9007199254740991d?Profiles.JSON.createObjectNode().put("$numberLong",n.toString()):Profiles.JSON.valueToTree(value);
        receipt.set("value",encoded);
        if(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>byteLimit-8192){receipt.remove("value");receipt.put("valueOmitted",true);result.put("truncated",true).put("truncationReason","byte_limit");}
    }
    private static void check(QueryJobs.Job job){if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static void retain(QueryJobs.Job job,ObjectNode result){result.put("outcome",job.outcome);job.result=result;job.bytes=result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    private NativeRedisPipelines(){}
}
