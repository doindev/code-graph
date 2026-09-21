package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Transport-independent review and execution service; never creates permissions. */
final class NativeOperations implements AutoCloseable {
    private final Profiles profiles;
    private final NativeConnections connections;
    private final QueryJobs jobs;
    private final NativeMongoStreams streams=new NativeMongoStreams();
    private final ProjectContexts contexts;
    private record Resolved(ObjectNode profile,NativeTarget target,ObjectNode binding){}
    private record Review(String owner,ObjectNode input,ObjectNode value,long expires,Runnable release){}
    private final Map<String,Review> reviews=new LinkedHashMap<>();

    NativeOperations(Profiles profiles,QueryJobs jobs) { this(profiles,jobs,null); }
    NativeOperations(Profiles profiles,QueryJobs jobs,ProjectContexts contexts) { this.profiles=profiles;this.jobs=jobs;this.contexts=contexts;connections=new NativeConnections(profiles); }

    private Resolved resolve(JsonNode input){
        ObjectNode binding=Profiles.JSON.createObjectNode(),effective=input.deepCopy();
        if(input.has("bindingId")){
            if(contexts==null)throw new IllegalArgumentException("Project bindings unavailable");
            for(String key:List.of("connectionId","connectionName","database","schema"))if(input.has(key))throw new IllegalArgumentException("Binding fixes the target; do not supply "+key);
            binding=contexts.binding(NativeTarget.text(input,"bindingId",36));
            if(!binding.path("enabled").asBoolean())throw new SecurityException("Binding is disabled");
            boolean loaded=false;for(JsonNode project:contexts.projects())if(project.path("id").equals(binding.path("projectId")))loaded=true;
            if(!loaded)throw new IllegalArgumentException("Onboard the bound project before requesting this operation");
            if(!binding.path("schema").asText().isBlank())throw new IllegalArgumentException("Native bindings cannot have a SQL schema");
            ObjectNode profile=profiles.get(binding.path("connectionId").asText());
            effective.put("connectionId",profile.path("id").asText()).put("connectionName",profile.path("name").asText()).put("database",binding.path("database").asText());
        }
        ObjectNode profile=profiles.get(NativeTarget.text(effective,"connectionId",36));
        if(input.has("expectedTargetRevision")&&!ProjectContexts.profileRevision(profile).equals(NativeTarget.text(input,"expectedTargetRevision",128)))
            throw new IllegalArgumentException("Native connection changed since loading the value; reopen and reconcile before saving");
        return new Resolved(profile,NativeTarget.resolve(profile,effective),binding);
    }
    private static ObjectNode scope(Resolved resolved){
        ObjectNode scope=resolved.target.json();
        if(resolved.binding.has("id")){
            scope.put("bindingId",resolved.binding.path("id").asText());
            for(String key:List.of("projectId","projectName","environment","role","purpose"))scope.set(key,resolved.binding.path(key).deepCopy());
        }
        return scope;
    }

    ObjectNode observationScope(JsonNode input){
        Resolved resolved=resolve(input);
        ObjectNode scope=scope(resolved).put("profileRevision",ProjectContexts.profileRevision(resolved.profile));
        if(resolved.binding.has("id"))scope.put("bindingRevision",ProjectContexts.profileRevision(resolved.binding));
        return scope;
    }

    ObjectNode prepare(JsonNode input) {
        Resolved resolved=resolve(input);ObjectNode profile=resolved.profile;NativeTarget target=resolved.target;
        JsonNode command=input.path("command");
        NativeCommand.Classification classification=NativeCommand.classify(target,command);
        boolean mutation=classification.effect()!=NativeCommand.Effect.READ;
        if(mutation){
            if(profile.path("readOnly").asBoolean(true))throw new IllegalArgumentException("This native profile is read-only; a user must enable reviewed writes in its connection settings");
            NativeMutations.validate(target,command);
        }else if(!classification.reusableRead())throw new IllegalArgumentException("No verified native read adapter");
        NativeConnections.validateSupported(profile);
        ObjectNode review=Profiles.JSON.createObjectNode().put("connectionId",target.connectionId())
                .put("connectionName",target.connectionName()).put("database",target.database())
                .put("targetRevision",ProjectContexts.profileRevision(profile)).put("mutation",mutation)
                .put("destructive",classification.effect()==NativeCommand.Effect.DESTRUCTIVE)
                .put("eligiblePersistentRead",false).put("scopeNotice","Exact native target; no implicit browser selection or database fallback")
                .put("transactionNotice",mutation?"Native operations are not an atomic script. Successful writes may remain after errors or cancellation; reconcile uncertain outcomes before any retry. Values may be replaced or removed.":"Bounded live read, not a snapshot. Cancellation is best-effort; retained results are capped.");
        review.set("target",scope(resolved));review.set("classification",classification.json());
        if(resolved.binding.has("id"))review.put("bindingRevision",ProjectContexts.profileRevision(resolved.binding));
        review.set("after",Profiles.JSON.createObjectNode().set("nativeCommand",command.deepCopy()));
        review.put("commandHash",CatalogScanner.hash(command.toString()));
        if(classification.category().equals("redis.transaction"))review.put("transactionNotice",classification.reason());
        if(classification.category().equals("redis.pipeline"))review.put("transactionNotice",NativeRedisPipelines.NOTICE);
        if(classification.category().startsWith("redis.value."))review.put("transactionNotice",NativeRedisValues.NOTICE);
        if(classification.category().equals("mongo.transaction"))review.put("transactionNotice",NativeMongoTransactions.NOTICE);
        if(classification.category().equals("mongo.watch"))review.put("transactionNotice",NativeMongoStreams.NOTICE);
        if(classification.category().startsWith("redis.stream.")){review.put("transactionNotice",classification.reason());review.set("streamScope",NativeRedisStreams.scope(command));}
        if(target.transport()==DatabaseTransport.MONGODB&&command.has("renameCollection"))MongoCollectionRename.describe(review,command);
        if(target.transport()==DatabaseTransport.MONGODB&&MongoCollectionSettings.handles(command))MongoCollectionSettings.describe(review,target,command);
        return review;
    }
    synchronized ObjectNode prepareBrowser(String owner,JsonNode input){
        reap();ObjectNode value=prepare(input);if(!value.path("mutation").asBoolean())return value;
        if(reviews.size()>=8)throw new IllegalArgumentException("Native review allowance full; close or finish other reviews");
        Runnable release=jobs.reserveRetained(2L<<20);
        String id=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+300000;
        value.put("id",id).put("expiresAt",expires);
        reviews.put(id,new Review(owner,input.deepCopy(),value.deepCopy(),expires,release));return value;
    }
    synchronized ObjectNode applyBrowser(String owner,String id){
        reap();Review review=reviews.get(id);if(review==null||!review.owner.equals(owner))throw new SecurityException("Native review expired or belongs to another browser session");
        validate(review.value,review.input);reviews.remove(id);review.release.run();
        return submit(owner,review.value,review.input,()->{});
    }
    synchronized void discardBrowser(String owner,String id){Review review=reviews.get(id);if(review!=null){if(!review.owner.equals(owner))throw new SecurityException("Native review belongs to another session");reviews.remove(id);review.release.run();}}

    void validate(JsonNode review,JsonNode input) {
        Resolved resolved=resolve(input);ObjectNode profile=resolved.profile;
        if(!ProjectContexts.profileRevision(profile).equals(review.path("targetRevision").asText()))throw new IllegalArgumentException("Native connection changed after review; prepare a new request");
        if(!scope(resolved).equals(review.path("target"))||!CatalogScanner.hash(input.path("command").toString()).equals(review.path("commandHash").asText()))throw new IllegalArgumentException("Native target or command changed after review");
        if(review.has("bindingRevision")&&!ProjectContexts.profileRevision(resolved.binding).equals(review.path("bindingRevision").asText()))throw new IllegalArgumentException("Native binding changed after review");
        NativeCommand.classify(resolved.target,input.path("command"));
    }

    ObjectNode submit(String owner,JsonNode review,JsonNode input,Runnable authorityCheck) {
        ObjectNode snapshot=input.deepCopy(),reviewed=review.deepCopy();
        return jobs.local(owner,review.path("connectionId").asText(),job->execute(job,reviewed,snapshot,authorityCheck),snapshot::removeAll);
    }

    JsonNode execute(QueryJobs.Job job,JsonNode review,JsonNode input,Runnable authorityCheck)throws Exception {
        authorityCheck.run();validate(review,input);
        if(job.cancelled)throw new CancellationException();
        NativeTarget target=resolve(input).target;
        try(AutoCloseable projectLease=input.has("bindingId")?contexts.hold(input.path("bindingId").asText()):()->{};
            var lease=connections.acquire(target.connectionId())) {
            authorityCheck.run();validate(review,input);
            if(!lease.revision.equals(review.path("targetRevision").asText()))throw new IllegalArgumentException("Native client revision changed; review again");
            job.progress="Executing bounded native read";
            if(target.transport()==DatabaseTransport.REDIS&&input.path("command").has("pipeline")){
                job.progress=review.path("mutation").asBoolean()?"Executing reviewed Redis pipeline":"Executing bounded Redis read pipeline";
                return NativeRedisPipelines.execute(lease,target,input.path("command"),job,()->{authorityCheck.run();validate(review,input);});
            }
            if(target.transport()==DatabaseTransport.REDIS&&NativeRedisStreams.handles(input.path("command")))return NativeRedisStreams.execute(lease,target,input.path("command"),job,()->{authorityCheck.run();validate(review,input);});
            if(target.transport()==DatabaseTransport.REDIS&&NativeRedisValues.handles(input.path("command")))return NativeRedisValues.execute(lease,target,input.path("command"),job,()->{authorityCheck.run();validate(review,input);});
            if(review.path("classification").path("category").asText().equals("mongo.watch"))return streams.execute(lease,target,input.path("command"),job,review,()->{authorityCheck.run();validate(review,input);});
            if(review.path("mutation").asBoolean()){
                job.progress="Executing reviewed native mutation";
                return NativeMutations.execute(lease,target,input.path("command"),job,()->{authorityCheck.run();validate(review,input);});
            }
            return NativeReadExecutor.execute(lease,target,input.path("command"),
                    new NativeReadExecutor.Limits(job.rowLimit,job.byteLimit,job.remainingSeconds()),()->job.cancelled);
        }
    }

    ObjectNode observe(String owner,NativeTarget target,ObjectNode scope,boolean approvalsEnabled,Runnable authorityCheck){
        String revision=ProjectContexts.profileRevision(profiles.get(target.connectionId()));
        return jobs.local(owner,target.connectionId(),job->{
            authorityCheck.run();ObjectNode profile=profiles.get(target.connectionId());
            if(!revision.equals(ProjectContexts.profileRevision(profile)))throw new IllegalArgumentException("Native profile changed before version observation");
            try(AutoCloseable projectLease=scope.has("bindingId")?contexts.hold(scope.path("bindingId").asText()):()->{};
                var lease=connections.acquire(target.connectionId())){
                authorityCheck.run();if(!revision.equals(lease.revision))throw new IllegalArgumentException("Native client changed before version observation");
                String version;
                if(lease.mongo!=null){
                    var info=lease.mongo.getDatabase(target.database()).runCommand(new org.bson.BsonDocument("buildInfo",new org.bson.BsonInt32(1)).append("maxTimeMS",new org.bson.BsonInt64(job.remainingSeconds()*1000L)),org.bson.RawBsonDocument.class);
                    version=info.getString("version").getValue();
                }else try(var connection=NativeRedisSession.open(lease,target,job.remainingSeconds())){
                    String info=connection.serverInfo();
                    version=info.lines().filter(line->line.startsWith("redis_version:")).map(line->line.substring(14).strip()).findFirst().orElse("unknown");
                }
                if(version.length()>128)throw new IllegalArgumentException("Invalid native server version response");
                var result=NativeCatalog.describe(profile,target,approvalsEnabled).put("freshness","live").put("verificationStatus","live_native_observation").put("observedAt",System.currentTimeMillis());
                result.set("target",scope.deepCopy());result.putObject("version").put("server",version);return result;
            }
        },()->{});
    }
    ObjectNode captureSchema(String owner,WorkflowTargets.Target observed,Runnable authorityCheck){
        ObjectNode scope=observed.scope().deepCopy(),input=observed.request().deepCopy();
        return jobs.local(owner,scope.path("connectionId").asText(),job->{
            authorityCheck.run();job.schemaScope=scope;job.schemaRequest=input;
            try(AutoCloseable projectLease=scope.has("bindingId")?contexts.hold(scope.path("bindingId").asText()):()->{};
                var lease=connections.acquire(scope.path("connectionId").asText())){
                authorityCheck.run();
                if(!lease.revision.equals(scope.path("profileRevision").asText()))throw new IllegalArgumentException("Native profile changed before snapshot");
                var target=NativeTarget.resolve(observed.profile(),scope);
                ObjectNode value=NativeSchemaObservations.capture(lease,target,job.id,job.rowLimit,job.byteLimit,job.remainingSeconds(),ProjectContexts.number(input,"sampleLimit",0,0,32),()->job.cancelled);
                authorityCheck.run();value.set("target",ApprovalScope.display(scope));return value.put("authorizationReason",observed.authorization());
            }
        },()->{});
    }
    ObjectNode catalog(CatalogCache.Target selected,java.util.function.BooleanSupplier cancelled)throws Exception{
        ObjectNode profile=profiles.get(selected.connection());
        var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",selected.connection()).put("connectionName",profile.path("name").asText()).put("database",selected.database()));
        try(var lease=connections.acquire(selected.connection())){
            if(!selected.revision().equals(lease.revision))throw new IllegalArgumentException("Native profile changed before catalog scan");
            return NativeSchemaObservations.capture(lease,target,UUID.randomUUID().toString(),100,1<<20,30,0,cancelled);
        }
    }
    ObjectNode tree(String owner,JsonNode input){
        ObjectNode profile=profiles.get(NativeTarget.text(input,"connectionId",36)),selected=input.deepCopy();
        selected.put("connectionName",profile.path("name").asText());if(selected.path("collection").asText().isEmpty())selected.remove("collection");
        if(!selected.has("database"))selected.put("database",profile.path("nativeOptions").path("database").asText());
        NativeTarget target=NativeTarget.resolve(profile,selected);String revision=ProjectContexts.profileRevision(profile);
        return jobs.local(owner,target.connectionId(),job->{
            if(!revision.equals(ProjectContexts.profileRevision(profiles.get(target.connectionId()))))throw new IllegalArgumentException("Native connection changed before metadata load");
            try(var lease=connections.acquire(target.connectionId())){
                if(!revision.equals(lease.revision))throw new IllegalArgumentException("Native connection changed before metadata load");
                return NativeMetadataTree.load(lease,target,selected,job);
            }
        },selected::removeAll);
    }
    synchronized void forgetOwner(String owner){var iterator=reviews.values().iterator();while(iterator.hasNext()){var review=iterator.next();if(review.owner.equals(owner)){iterator.remove();review.release.run();}}}
    void remove(String id) { connections.remove(id); }
    void invalidate(String id){connections.invalidate(id);}
    synchronized void reap() {long now=System.currentTimeMillis();var entries=reviews.values().iterator();while(entries.hasNext()){Review review=entries.next();if(now>=review.expires){entries.remove();review.release.run();}}connections.reap(); }
    synchronized ObjectNode telemetry() { return connections.telemetry().put("retainedReviews",reviews.size()).put("reviewReservationBytes",reviews.size()*(2L<<20)).put("activeChangeStreamCursors",streams.activeCursors()).put("retainedChangeSubscriptions",0); }
    public synchronized void close() {reviews.values().forEach(review->review.release.run());reviews.clear();streams.close();connections.close(); }
}
