package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.*;
import com.mongodb.client.*;
import org.bson.*;
import org.bson.json.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Finite pull batches. Every poll owns/closes its cursor; the caller owns backpressure. */
final class NativeMongoStreams implements AutoCloseable {
    static final String NOTICE="Bounded change-stream batch on one exact existing collection. No initial snapshot, update lookup, preimages or background subscription. Fetch the next batch explicitly within five minutes; history retention may expire earlier. Invalidations/gaps never restart silently. The driver may resume a resumable read error within the job deadline; no writes are executed.";
    private static final JsonWriterSettings EXTENDED=JsonWriterSettings.builder().outputMode(JsonMode.EXTENDED).build();
    private final MongoStreamCursor cursors=new MongoStreamCursor();
    private final AtomicInteger active=new AtomicInteger();
    static NativeCommand.Classification classify(NativeTarget target,JsonNode command){
        if(!Set.of("replica_set","sharded").contains(target.topology()))throw new IllegalArgumentException("Change streams require an explicit replica_set or sharded profile; standalone/SRV certification is unavailable");
        if(!command.isObject()||!command.path("watch").isTextual()||!command.path("watch").asText().equals(target.collection())||target.collection().isEmpty()||target.collection().startsWith("system.")||target.collection().contains("$")||Set.of("admin","local","config").contains(target.database()))throw new IllegalArgumentException("Watch must exactly match one non-system collection/database");
        command.fieldNames().forEachRemaining(name->{if(!Set.of("watch","cursor","waitMillis","limit").contains(name))throw new IllegalArgumentException("Unsupported watch option: "+name+"; pipelines, sessions and raw resume tokens are application-managed");});
        if(command.has("cursor")&&(!command.path("cursor").isTextual()||!command.path("cursor").asText().startsWith("mcs1.")||command.path("cursor").asText().length()>MongoStreamCursor.MAX_TOKEN))throw new IllegalArgumentException("cursor must be an opaque server-returned stream cursor");
        number(command,"waitMillis",1000,0,10000);number(command,"limit",100,1,100);
        return new NativeCommand.Classification("mongo.watch",NativeCommand.Effect.READ,true,NOTICE);
    }
    ObjectNode execute(NativeConnections.Lease lease,NativeTarget target,JsonNode command,QueryJobs.Job job,JsonNode review,Runnable authority)throws Exception{
        classify(target,command);check(job,authority);
        String scope=CatalogScanner.hash(job.owner+"\n"+review.path("target")+"\n"+review.path("targetRevision")+"\n"+review.path("bindingRevision"));
        MongoStreamCursor.Position position=command.has("cursor")?cursors.decode(command.path("cursor").asText(),scope):null;
        BsonDocument resume=position==null?null:position.resume();
        var database=lease.mongo.getDatabase(target.database()).withReadPreference(ReadPreference.primary()).withTimeout(job.remainingSeconds(),TimeUnit.SECONDS);
        NativeMongoTransactions.checkTopology(target,database.runCommand(new BsonDocument("hello",new BsonInt32(1)),RawBsonDocument.class));
        var metadata=MongoCollectionMetadata.load(database,target,job);NativeMongoTransactions.checkCollection(metadata);
        var uuid=metadata.getDocument("info",new BsonDocument()).get("uuid");if(uuid==null)throw new IllegalArgumentException("Collection identity unavailable; watch cannot start safely");
        String collectionId=uuid.toString();
        if(position!=null&&!position.collection().equals(collectionId))throw new IllegalArgumentException("Watched collection was replaced; explicitly start a new watch after reviewing the gap");
        int limit=Math.min(job.rowLimit,number(command,"limit",100,1,100));
        var values=new NativeResults("change_stream",limit,job.byteLimit);String stop="wait_elapsed";
        long started=System.nanoTime(),wait=TimeUnit.MILLISECONDS.toNanos(number(command,"waitMillis",1000,0,10000));
        boolean tracked=false;job.progress="Opening bounded MongoDB change batch";
        try{
            var watch=database.getCollection(target.collection(),RawBsonDocument.class).watch().batchSize(1).maxAwaitTime(100,TimeUnit.MILLISECONDS);
            if(resume!=null)watch.resumeAfter(resume);
            // The pinned driver's public withDocumentClass API returns whole raw events, not
            // decoded ChangeStreamDocument update maps. Its cursor still exposes resume positions.
            var raw=watch.withDocumentClass(RawBsonDocument.class).cursor();
            try(AutoCloseable cleanup=()->closeCursor(raw)){
                if(!(raw instanceof MongoChangeStreamCursor<?> stream))throw new IllegalArgumentException("Driver lacks raw resumable cursor support");
                var current=MongoCollectionMetadata.load(database,target,job);
                if(!uuid.equals(current.getDocument("info",new BsonDocument()).get("uuid")))throw new IllegalArgumentException("Collection changed while the stream opened; review the gap before restarting");
                active.incrementAndGet();tracked=true;job.progress="Reading bounded MongoDB change batch";
                if(resume==null)resume=stream.getResumeToken();
                for(;;){
                    check(job,authority);
                    if(values.finish().path("rowCount").asInt()>=limit){stop="event_limit";break;}
                    RawBsonDocument event=raw.tryNext();check(job,authority);
                    if(event==null){
                        if(stream.getResumeToken()!=null)resume=stream.getResumeToken();
                        if(raw.getServerCursor()==null){stop="invalidated";resume=null;break;}
                        if(System.nanoTime()-started>=wait)break;
                        continue;
                    }
                    // Never advance over an omitted event, even when the driver has read ahead.
                    if(event.getByteBuffer().remaining()>256*1024){values.incomplete("event_too_large");stop="event_too_large";break;}
                    if(!event.containsKey("_id")||!event.get("_id").isDocument())throw new IllegalArgumentException("Change event has no resume position; stream continuity is unavailable");
                    if(!values.add(Profiles.JSON.readTree(event.toJson(EXTENDED)))){stop="byte_limit";break;}
                    resume=event.getDocument("_id");
                    if(event.getString("operationType",new BsonString("")).getValue().equals("invalidate")){stop="invalidated";resume=null;break;}
                    if(System.nanoTime()-started>=wait)break;
                }
            }
            check(job,authority);
            return finish(job,values,target,scope,collectionId,resume,stop,started);
        }catch(SecurityException denied){job.result=null;throw denied;}
        catch(Exception failure){
            String reason=failureReason(failure,job.cancelled||Thread.currentThread().isInterrupted());
            // No continuation after lost history; no silent now/startAfter fallback.
            if(reason.equals("history_lost"))resume=null;
            finish(job,values,target,scope,collectionId,resume,reason,started);
            if(reason.equals("cancelled"))throw new CancellationException();
            if(failure instanceof MongoException mongo)((ObjectNode)job.result).put("vendorCode",mongo.getCode());
            throw new IllegalArgumentException("MongoDB change-stream "+reason+"; inspect the retained batch and continuity notice. No automatic new watch was started.");
        }finally{if(tracked)active.decrementAndGet();}
    }
    private ObjectNode finish(QueryJobs.Job job,NativeResults values,NativeTarget target,String scope,String collectionId,BsonDocument resume,String stop,long started)throws Exception{
        ObjectNode result=values.finish().put("stopReason",stop).put("streamComplete",false).put("snapshot",false).put("encoding","bson_extended_json")
                .put("requiresRestart",Set.of("history_lost","invalidated").contains(stop)).put("elapsedMillis",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started))
                .put("notice",NOTICE).put("cursorLifetimeSeconds",300).put("retainedSubscription",false).put("hardMemoryLimit",false)
                .put("cursorCleanup","driver_close_requested; server cleanup is best-effort if the connection fails");
        result.set("target",target.json());result.set("classification",new NativeCommand.Classification("mongo.watch",NativeCommand.Effect.READ,true,NOTICE).json());
        if(resume!=null)result.put("nextCursor",cursors.encode(scope,collectionId,resume));
        else if(!result.path("requiresRestart").asBoolean())result.put("requiresRestart",true).put("continuityWarning","No resumable position was established; starting again may miss history.");
        if(Set.of("byte_limit","event_too_large").contains(stop))result.put("backpressure","No events after the last returned event were acknowledged. An oversized event may block further progress; refine external consumption or explicitly start a new watch, acknowledging the gap.");
        int bytes=Profiles.JSON.writeValueAsBytes(result).length;if(bytes>job.byteLimit)throw new IllegalArgumentException("Stream response exceeds its allowance; last acknowledged cursor remains authoritative");
        job.bytes=bytes;job.result=result;return result;
    }
    private static int number(JsonNode command,String field,int fallback,int min,int max){if(!command.has(field))return fallback;JsonNode value=command.path(field);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<min||value.asInt()>max)throw new IllegalArgumentException(field+" must be "+min+".."+max);return value.asInt();}
    static String failureReason(Exception failure,boolean cancelled){return cancelled||failure instanceof CancellationException?"cancelled":failure instanceof MongoException mongo&&Set.of(136,237,260,280,286).contains(mongo.getCode())?"history_lost":"read_failed";}
    private static void closeCursor(MongoCursor<?> cursor){boolean interrupted=Thread.interrupted();try{cursor.close();}finally{if(interrupted)Thread.currentThread().interrupt();}}
    private static void check(QueryJobs.Job job,Runnable authority){if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();authority.run();}
    int activeCursors(){return active.get();}
    public void close(){cursors.close();}
}
