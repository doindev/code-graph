package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.TransactionResult;
import io.lettuce.core.cluster.SlotHash;
import java.util.*;
import java.util.concurrent.CancellationException;

/** One bounded, reviewed transaction on an operation-owned socket. No persistent transaction or retry. */
final class NativeRedisTransactions {
    static final int MAX_COMMANDS=32, MAX_WATCH=16, MAX_KEYS=100;
    static final String NOTICE="Redis queues this batch on one connection and executes it without interleaving, but does not roll back execution-time errors: other commands can still succeed. WATCH expectations are checked immediately before queueing, not held while awaiting approval. Conflicts are not retried. Cancellation or connection loss after EXEC can leave an uncertain outcome; reconcile before retry.";

    static NativeCommand.Classification classify(NativeTarget target,JsonNode command){
        fields(command,Set.of("transaction","watch"));
        JsonNode commands=command.path("transaction");
        if(!commands.isArray()||commands.isEmpty()||commands.size()>MAX_COMMANDS)throw new IllegalArgumentException("Redis transaction requires 1..32 supported mutation argument arrays");
        boolean destructive=false;
        List<byte[]> keys=new ArrayList<>();
        for(JsonNode entry:commands){
            if(!entry.isArray())throw new IllegalArgumentException("Nested transactions and non-array commands are not supported");
            if(NativeRedisStreams.handles(entry))throw new IllegalArgumentException("Stream operations require their dedicated finite workflow, not a scalar transaction");
            NativeMutations.validate(target,entry);
            var classification=NativeCommand.classify(target,entry);
            if(classification.effect()==NativeCommand.Effect.DESTRUCTIVE)destructive=true;
            String name=NativeRedisArguments.text(entry,0).toUpperCase(Locale.ROOT);
            int end=Set.of("DEL","UNLINK").contains(name)?entry.size():Set.of("RENAME","RENAMENX").contains(name)?3:2;
            for(int i=1;i<end;i++)keys.add(NativeRedisArguments.bytes(entry,i));
            if(keys.size()>MAX_KEYS)throw new IllegalArgumentException("Redis transaction exceeds the 100-key reference allowance");
        }
        if(command.has("watch")){
            JsonNode watch=command.path("watch");
            if(!watch.isArray()||watch.size()>MAX_WATCH)throw new IllegalArgumentException("Redis watch must be an array of at most 16 key/expected pairs");
            Set<String> distinct=new HashSet<>();
            for(JsonNode item:watch){
                fields(item,Set.of("key","expected"));
                if(!item.has("key")||!item.has("expected"))throw new IllegalArgumentException("Each watched key requires an explicit expected string/base64 value or null for an absent key");
                byte[] key=argument(item.path("key"),8192);
                if(!item.path("expected").isNull())argument(item.path("expected"),65536);
                if(!distinct.add(Base64.getEncoder().encodeToString(key)))throw new IllegalArgumentException("Duplicate watched key");
                keys.add(key);
            }
        }
        if(keys.size()>MAX_KEYS)throw new IllegalArgumentException("Redis transaction exceeds the 100-key reference allowance");
        if(target.topology().equals("cluster")){
            int slot=SlotHash.getSlot(keys.getFirst());
            for(byte[] key:keys)if(SlotHash.getSlot(key)!=slot)throw new IllegalArgumentException("Redis Cluster transactions require every command and watched key in one hash slot; use a shared hash tag");
        }
        return new NativeCommand.Classification("redis.transaction",destructive?NativeCommand.Effect.DESTRUCTIVE:NativeCommand.Effect.WRITE,false,NOTICE);
    }

    static JsonNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job,Runnable beforeWrite){
        if(command.path("transaction").size()>job.rowLimit)throw new IllegalArgumentException("Transaction reply count exceeds the configured result-row allowance; submit a smaller reviewed batch");
        try(var session=NativeRedisSession.open(lease,target,job.remainingSeconds())){
            var connection=session.transactionConnection(NativeRedisArguments.bytes(command.path("transaction").get(0),1));
            var redis=connection.sync();
            Runnable check=()->{if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();connection.setTimeout(java.time.Duration.ofSeconds(job.remainingSeconds()));};
            check.run();
            JsonNode watch=command.path("watch");
            if(!watch.isMissingNode()&&!watch.isEmpty()){
                byte[][] watched=new byte[watch.size()][];
                for(int i=0;i<watch.size();i++)watched[i]=argument(watch.get(i).path("key"),8192);
                redis.watch(watched);
                for(int i=0;i<watch.size();i++){
                    check.run();JsonNode expected=watch.get(i).path("expected");String type=redis.type(watched[i]);
                    boolean matches=expected.isNull()?type.equals("none"):type.equals("string")
                            &&redis.strlen(watched[i])<=65536
                            &&Arrays.equals(argument(expected,65536),redis.getrange(watched[i],0,65536));
                    if(!matches)return conflict(job,"expected_value_changed",i);
                }
            }
            beforeWrite.run();check.run();redis.multi();
            for(JsonNode entry:command.path("transaction")){check.run();NativeMutations.redisMutation(redis,entry);}
            // Last authority/revision check occurs after preparation and immediately before the only execution point.
            beforeWrite.run();check.run();job.outcome="partial_or_unknown";
            TransactionResult executed=redis.exec();
            if(executed.wasDiscarded())return conflict(job,"watched_key_changed",-1);
            ObjectNode result=Profiles.JSON.createObjectNode().put("kind","transaction").put("rollbackSupported",false);
            var entries=result.putArray("entries");boolean errors=false;
            for(int i=0;i<executed.size();i++){
                Object value=executed.get(i);ObjectNode entry=entries.addObject().put("index",i);
                if(value instanceof Throwable){
                    errors=true;entry.put("state","failed").put("error","Redis rejected this command during EXEC; other commands may have succeeded. Check key types and database permissions.");
                }else{entry.put("state","acknowledged");entry.set("value",Profiles.JSON.valueToTree(value));}
            }
            if(executed.size()!=command.path("transaction").size())throw new IllegalStateException("Incomplete Redis transaction response; reconcile before retry");
            job.outcome=errors?"partial":"acknowledged";result.put("outcome",job.outcome);
            result.put("notice",errors?"Execution-time errors do not roll back successful Redis commands. No automatic retry was performed.":"All command replies received; conditional commands may report no change. Redis does not support rollback.");
            retain(job,result);
            if(errors)throw new IllegalArgumentException("Redis transaction partially failed; inspect per-command results and reconcile before retry");
            return result;
        } // Closing the operation-owned socket abandons queued work/WATCH without returning it to another caller.
    }

    private static ObjectNode conflict(QueryJobs.Job job,String reason,int index){
        job.outcome="conflict";var result=Profiles.JSON.createObjectNode().put("kind","transaction").put("outcome","conflict")
                .put("reason",reason).put("executed",false).put("notice","No transaction commands executed. Read current values and prepare a new reviewed request; no retry was attempted.");
        if(index>=0)result.put("watchIndex",index);retain(job,result);
        throw new IllegalArgumentException("Redis transaction conflict; no commands executed");
    }
    private static void retain(QueryJobs.Job job,ObjectNode result){
        int bytes=result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(bytes>job.byteLimit)throw new IllegalArgumentException("Transaction details exceed the result allowance; reconcile before retry");
        job.bytes=bytes;job.result=result;
    }
    private static byte[] argument(JsonNode value,int maximum){
        if(!value.isTextual()&&!value.isObject())throw new IllegalArgumentException("Redis watched keys/values require text or canonical base64; null is only an absent-value expectation");
        var wrapper=Profiles.JSON.createArrayNode().add(value);byte[] bytes=NativeRedisArguments.bytes(wrapper,0);
        if(bytes.length>maximum)throw new IllegalArgumentException("Redis watched key/value exceeds its byte allowance");return bytes;
    }
    private static void fields(JsonNode value,Set<String> allowed){
        if(!value.isObject())throw new IllegalArgumentException("Redis transaction and watch entries must be objects");
        value.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw new IllegalArgumentException("Unsupported Redis transaction field: "+name);});
    }
    private NativeRedisTransactions(){}
}
