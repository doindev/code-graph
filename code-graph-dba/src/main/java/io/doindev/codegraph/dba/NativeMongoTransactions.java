package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.*;
import com.mongodb.client.ClientSession;
import org.bson.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** A bounded, exact-collection transaction, never a long-lived agent-controlled session. */
final class NativeMongoTransactions {
    static final int MAX_COMMANDS=32, MAX_WRITES=100;
    static final String NOTICE="One atomic MongoDB CRUD transaction on the exact existing collection. Every update/delete entry must match one document; use original values in its filter for optimistic concurrency. Replica-set or sharded topology, primary reads, snapshot read concern and majority commit are required. No DDL, capped/time-series/view targets or cross-collection commands. No write or commit retry. A lost commit acknowledgement remains uncertain; reconcile before retry.";

    static NativeCommand.Classification classify(NativeTarget target,JsonNode command){
        if(!Set.of("replica_set","sharded").contains(target.topology()))throw new IllegalArgumentException("MongoDB transactions require an explicitly configured replica_set or sharded profile; standalone/SRV transaction certification is unavailable");
        if(target.collection().isEmpty()||target.collection().startsWith("system.")||target.collection().contains("$")||Set.of("admin","local","config").contains(target.database()))throw new IllegalArgumentException("Transactions require an exact non-system database and collection");
        if(!command.isObject()||!command.has("transaction")||command.size()!=(command.has("documentGuard")?2:1))throw new IllegalArgumentException("MongoDB transaction accepts transaction and optional documentGuard only; session and concern settings are application-managed");
        var commands=command.path("transaction");
        if(!commands.isArray()||commands.isEmpty()||commands.size()>MAX_COMMANDS)throw new IllegalArgumentException("MongoDB transaction requires 1..32 CRUD command objects");
        int writes=0;boolean destructive=false;
        for(JsonNode entry:commands){
            if(!entry.isObject()||entry.isEmpty())throw new IllegalArgumentException("Transaction entries must be CRUD command objects");
            String name=entry.fieldNames().next();
            if(!Set.of("insert","update","delete").contains(name)||entry.has("transaction"))throw new IllegalArgumentException("MongoDB transactions support insert/update/delete only, not reads, DDL, scripts or nested transactions");
            NativeMutations.validate(target,entry);
            if(!name.equals("insert"))rejectExecutableExpressions(entry);
            if(entry.has("ordered")&&!entry.path("ordered").asBoolean())throw new IllegalArgumentException("Transaction batches require ordered: true (or omission)");
            writes+=entry.path(items(name)).size();
            if(writes>MAX_WRITES)throw new IllegalArgumentException("MongoDB transaction exceeds 100 document-write entries");
            destructive|=name.equals("delete");
        }
        if(command.has("documentGuard"))NativeMongoDocuments.validate(target,command);
        return new NativeCommand.Classification("mongo.transaction",destructive?NativeCommand.Effect.DESTRUCTIVE:NativeCommand.Effect.WRITE,false,NOTICE+(command.has("documentGuard")?" "+NativeMongoDocuments.NOTICE:""));
    }

    static JsonNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job,Runnable authority){
        var commands=command.path("transaction");
        if(commands.size()>job.rowLimit||job.byteLimit<2048+commands.size()*256)throw new IllegalArgumentException("Transaction details exceed the configured result allowance; submit a smaller reviewed batch");
        check(job);job.outcome="not_started";
        var database=lease.mongo.getDatabase(target.database()).withReadPreference(ReadPreference.primary());
        var hello=database.withTimeout(job.remainingSeconds(),TimeUnit.SECONDS).runCommand(new BsonDocument("hello",new BsonInt32(1)),RawBsonDocument.class);
        checkTopology(target,hello);
        RawBsonDocument source=MongoCollectionMetadata.load(database,target,job);checkCollection(source);
        BsonValue originalId=source.getDocument("info",new BsonDocument()).get("uuid");
        if(originalId==null)throw new IllegalArgumentException("Collection identity unavailable; transaction cannot be validated");
        if(command.has("documentGuard"))NativeMongoDocuments.collection(command.path("documentGuard"),originalId);
        ObjectNode result=Profiles.JSON.createObjectNode().put("kind","transaction").put("atomic",true).put("outcome","not_started");
        if(command.has("documentGuard"))result.put("documentGuard",true);
        var entries=result.putArray("entries");
        try(ClientSession session=lease.mongo.startSession(ClientSessionOptions.builder().causallyConsistent(false).build())){
            boolean began=false,commitIssued=false;
            try{
                authority.run();check(job);
                session.startTransaction(TransactionOptions.builder().readPreference(ReadPreference.primary()).readConcern(ReadConcern.SNAPSHOT)
                        .writeConcern(WriteConcern.MAJORITY).timeout((long)job.remainingSeconds(),TimeUnit.SECONDS).build());
                began=true;job.outcome="uncommitted";
                if(command.has("documentGuard")){NativeMongoDocuments.check(database,session,target,command.path("documentGuard"));authority.run();check(job);}
                int index=0;
                for(JsonNode entry:commands){
                    authority.run();check(job);
                    BsonDocument request=BsonDocument.parse(entry.toString());
                    if(command.has("documentGuard")&&!entry.has("insert"))request.getArray(entry.has("delete")?"deletes":"updates").get(0).asDocument().put("collation",new BsonDocument("locale",new BsonString("simple")));
                    // Timeout belongs to the whole transaction. Per-command overrides are forbidden by
                    // the driver; manual maxTimeMS plus client timeoutMS is also undefined.
                    var reply=database.runCommand(session,request,ReadPreference.primary(),RawBsonDocument.class);
                    requireReply(reply);
                    String name=entry.fieldNames().next();long matched=reply.getNumber("n",new BsonInt32(-1)).longValue();
                    if(matched!=entry.path(items(name)).size())throw new Rejected("MongoDB transaction conflict: not every write entry matched/inserted exactly one document; no commit attempted");
                    entries.addObject().put("index",index++).put("operation",name).put("matchedOrInserted",matched)
                            .put("modified",reply.getNumber("nModified",new BsonInt32(0)).longValue()).put("state","uncommitted");
                }
                // Collection replacement between preflight and the first write must not be accepted.
                var current=MongoCollectionMetadata.load(database,target,job);checkCollection(current);
                if(!originalId.equals(current.getDocument("info",new BsonDocument()).get("uuid")))throw new Rejected("Collection identity changed during transaction preparation; no commit attempted");
                checkedBytes(job,result);authority.run();check(job);
                job.outcome="commit_unknown";commitIssued=true;
                // runCommand is the public non-retrying command API. The convenience commitTransaction()
                // retries independently of retryWrites(false). Keep the driver's session/router pinning,
                // include its recovery token, but send our single commit command exactly once.
                var commit=control("commitTransaction",session);
                commit.put("writeConcern",WriteConcern.MAJORITY.asDocument());
                var reply=lease.mongo.getDatabase("admin")
                        .runCommand(session,commit,ReadPreference.primary(),RawBsonDocument.class);
                requireReply(reply);
                job.outcome="commit_acknowledged";
                entries.forEach(value->((ObjectNode)value).put("state","committed"));
                result.put("outcome",job.outcome).put("notice","All transaction writes committed. No automatic retry was performed.");retain(job,result);return result;
            }catch(RuntimeException failure){
                if(commitIssued){job.outcome="commit_unknown";result.put("notice","Commit acknowledgement was not confirmed. Do not resubmit; inspect current data before any new operation.");}
                else if(began){
                    job.outcome=abort(lease,session)?"rollback_acknowledged":"rollback_unconfirmed";
                    result.put("notice",job.outcome.equals("rollback_acknowledged")?"Transaction aborted; no transaction writes committed.":"No commit was sent. Abort could not be confirmed; the server transaction may remain until its timeout.");
                }
                result.put("outcome",job.outcome);
                entries.forEach(value->((ObjectNode)value).put("state",job.outcome.equals("rollback_acknowledged")?"rolled_back":"unconfirmed"));
                if(failure instanceof MongoException mongo)result.put("vendorCode",mongo.getCode());
                retain(job,result);
                if(failure instanceof CancellationException||failure instanceof SecurityException)throw failure;
                throw new IllegalArgumentException(failure instanceof Rejected||failure instanceof NativeMongoDocuments.Conflict?failure.getMessage():"MongoDB transaction failed; inspect its outcome and vendor code. Raw driver details are withheld.",failure);
            }
            // Session close performs driver best-effort abort/cleanup because raw commit does not change
            // its client-side transaction state. It cannot undo an acknowledged commit or replay CRUD.
        }
    }

    static void checkTopology(NativeTarget target,RawBsonDocument hello){
        if(hello.getByteBuffer().remaining()>256*1024)throw new IllegalArgumentException("Topology response exceeds the validation allowance");
        boolean router=hello.getString("msg",new BsonString("")).getValue().equals("isdbgrid");
        boolean replica=hello.containsKey("setName")&&hello.getBoolean("isWritablePrimary",BsonBoolean.FALSE).getValue();
        if(!hello.containsKey("logicalSessionTimeoutMinutes")||hello.getNumber("maxWireVersion",new BsonInt32(0)).intValue()<8
                ||!(target.topology().equals("sharded")?router:replica&&!router))throw new IllegalArgumentException("Observed MongoDB topology does not support the selected transaction mode; no writes attempted");
    }
    static void checkCollection(RawBsonDocument source){
        var options=source.getDocument("options",new BsonDocument());
        if(!source.getString("type",new BsonString("")).getValue().equals("collection")||options.getBoolean("capped",BsonBoolean.FALSE).getValue()||options.containsKey("timeseries"))throw new Rejected("Transactions require an existing ordinary collection; views, capped and time-series collections are unavailable");
    }
    private static boolean abort(NativeConnections.Lease lease,ClientSession session){
        boolean interrupted=Thread.interrupted();
        try{
            requireReply(lease.mongo.getDatabase("admin")
                    .runCommand(session,control("abortTransaction",session),ReadPreference.primary(),RawBsonDocument.class));return true;
        }catch(MongoCommandException failure){return failure.getErrorCode()==251;/* NoSuchTransaction: no commit was attempted. */}
        catch(RuntimeException failure){return false;}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static BsonDocument control(String name,ClientSession session){
        var request=new BsonDocument(name,new BsonInt32(1));
        if(session.getRecoveryToken()!=null)request.put("recoveryToken",session.getRecoveryToken());return request;
    }
    private static void requireReply(RawBsonDocument reply){
        if(reply.getByteBuffer().remaining()>256*1024)throw new Rejected("MongoDB transaction reply exceeds the bounded allowance");
        if(reply.containsKey("writeErrors")||reply.containsKey("writeConcernError"))throw new Rejected("MongoDB rejected a transaction write or acknowledgement; check document constraints and permissions. Raw reply details are withheld.");
    }
    private static String items(String name){return name.equals("insert")?"documents":name.equals("update")?"updates":"deletes";}
    private static void rejectExecutableExpressions(JsonNode node){
        if(node.isObject())node.properties().forEach(entry->{
            if(Set.of("$where","$function","$accumulator").contains(entry.getKey()))throw new IllegalArgumentException("Executable MongoDB expressions are not supported in transaction filters/updates");
            rejectExecutableExpressions(entry.getValue());
        });
        else if(node.isArray())node.forEach(NativeMongoTransactions::rejectExecutableExpressions);
    }
    private static void check(QueryJobs.Job job){if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static void retain(QueryJobs.Job job,ObjectNode result){
        job.bytes=checkedBytes(job,result);job.result=result;
    }
    private static int checkedBytes(QueryJobs.Job job,ObjectNode result){
        int bytes=result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(bytes>job.byteLimit)throw new Rejected("Transaction details exceed result allowance; reconcile before retry");return bytes;
    }
    private static final class Rejected extends IllegalArgumentException{Rejected(String message){super(message);}}
    private NativeMongoTransactions(){}
}
