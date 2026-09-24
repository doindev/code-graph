package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.store.DocumentStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Shared observation storage; cache identity never conveys authorization or invents a binding. */
final class CatalogCache implements AutoCloseable {
    static final long STANDALONE_IDLE_MILLIS=30*60_000L;
    private static final int MAX_SCOPES=128;
    private static final long MAX_RETAINED=128L<<20, OVERHEAD=1L<<20;
    record Target(String connection,String database,String schema,String revision) {
        String key(){return CatalogScanner.hash(Profiles.JSON.createArrayNode().add(connection).add(database).add(schema).add(revision).toString());}
        ObjectNode selector(){return Profiles.JSON.createObjectNode().put("connectionId",connection).put("database",database).put("schema",schema);}
    }
    static final class Publication {
        final DocumentStore store;
        final ObjectNode metadata;
        final Runnable release;
        int readers;
        boolean retired;
        Publication(DocumentStore store,ObjectNode metadata,Runnable release){this.store=store;this.metadata=metadata;this.release=release;}
        void close(){try{store.close();}finally{release.run();}}
    }
    static final class Scope {
        final Target target;
        final String instanceId=UUID.randomUUID().toString();
        volatile String state="never_scanned",error="";
        volatile long nextScan,generation,standaloneExpires;
        volatile boolean cancelled,requested;
        volatile long cadence;
        volatile CatalogScanner scanner;
        volatile Connection connection;
        CatalogScanRun currentRun,lastRun;
        long scanRevision;
        private Publication published;
        Scope(Target target){this.target=target;}
        synchronized ObjectNode metadata(){return published==null?Profiles.JSON.createObjectNode():published.metadata.deepCopy();}
        <T>T read(Function<Publication,T> read){
            Publication current;
            synchronized(this){current=published;if(current==null)throw new IllegalArgumentException("Catalog has not been scanned");current.readers++;}
            try{return current.store.read(()->read.apply(current));}
            finally{boolean close;synchronized(this){current.readers--;close=current.retired&&current.readers==0;}if(close)current.close();}
        }
        void publish(Publication next){
            Publication previous;boolean close;
            synchronized(this){previous=published;published=next;generation=next.metadata.path("generation").asLong();
                close=previous!=null&&previous.readers==0;if(previous!=null)previous.retired=true;}
            if(close)previous.close();
        }
        void retire(){
            cancelled=true;CatalogScanner active=scanner;
            if(active!=null)Thread.startVirtualThread(active::cancel);
            Connection owned=connection;if(owned!=null)Thread.startVirtualThread(()->{try{owned.abort(command->Thread.startVirtualThread(command));}catch(Exception ignored){}});
            Publication previous;boolean close;
            synchronized(this){previous=published;published=null;close=previous!=null&&previous.readers==0;if(previous!=null)previous.retired=true;}
            if(close)previous.close();
        }
    }
    private final Profiles profiles;
    private final Connections connections;
    private final Supplier<DocumentStore> stores;
    private final LongSupplier clock;
    private final Map<String,Scope> scopes=new LinkedHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("catalog-scan").factory());
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("catalog-deadline").factory());
    private volatile QueryJobs accounting;
    private volatile NativeOperations nativeCatalogs;
    void nativeCatalogs(NativeOperations operations){nativeCatalogs=operations;}
    boolean supports(JsonNode profile){return DatabaseTransport.of(profile)==DatabaseTransport.JDBC||nativeCatalogs!=null;}
    private volatile boolean closed,scanning;
    CatalogCache(Profiles profiles,Connections connections,Supplier<DocumentStore> stores,LongSupplier clock){
        this.profiles=profiles;this.connections=connections;this.stores=stores;this.clock=clock;
    }
    void accounting(QueryJobs jobs){accounting=jobs;}
    Target target(JsonNode selector){
        ObjectNode profile=profiles.get(Profiles.text(selector,"connectionId",36));
        // Legacy bindings may explicitly use their saved connection's default catalog.
        ObjectNode scope=selector.has("id")?ApprovalScope.resolve(profile,selector,Profiles.JSON.createObjectNode()):(ObjectNode)selector;
        return new Target(profile.path("id").asText(),scope.path("database").asText(),scope.path("schema").asText(),
                ProjectContexts.profileRevision(profile));
    }
    synchronized Scope find(Target target){return scopes.get(target.key());}
    boolean scanning(){return scanning;}
    synchronized List<Scope> scopes(){return List.copyOf(scopes.values());}
    private synchronized Scope ensure(Target target){
        if(closed)throw new IllegalStateException("Catalog cache closed");
        Scope scope=scopes.get(target.key());
        if(scope!=null)return scope;
        if(scopes.size()>=MAX_SCOPES)throw new IllegalArgumentException("Catalog scope limit reached; close idle scopes before scanning more targets");
        scope=new Scope(target);scopes.put(target.key(),scope);return scope;
    }
    void touchStandalone(Target target){ensure(target).standaloneExpires=clock.getAsLong()+STANDALONE_IDLE_MILLIS;}
    synchronized void request(Target target,long cadence){
        requireCurrent(target);
        Scope scope=ensure(target);
        if(scope.currentRun!=null)return;enqueue(scope,cadence);startNext();
    }
    synchronized void due(Target target,long cadence){
        Scope scope=ensure(target);
        if(scope.nextScan<=clock.getAsLong()&&scope.currentRun==null)enqueue(scope,cadence);
        startNext();
    }
    private void enqueue(Scope scope,long cadence){
        scope.currentRun=new CatalogScanRun(clock.getAsLong());scope.scanRevision++;
        scope.requested=true;scope.cadence=cadence;scope.nextScan=0;
    }
    private synchronized void progress(Scope scope,String phase,long objects,long dependencies,long bytes){
        var run=scope.currentRun;if(run==null||run.timeoutRequested)return;
        run.phase=phase;run.objects=objects;run.dependencies=dependencies;run.bytes=bytes;
        run.updatedAt=clock.getAsLong();scope.scanRevision++;
    }
    void timeout(Scope scope,CatalogScanRun expected){
        Connection connection;CatalogScanner scanner;
        synchronized(this){
            if(scope.currentRun!=expected)return;
            scope.currentRun.timeoutRequested=true;scope.currentRun.phase="cancelling";
            scope.currentRun.updatedAt=clock.getAsLong();scope.scanRevision++;
            connection=scope.connection;scanner=scope.scanner;
        }
        // Do not block the deadline thread on a driver that ignores cancellation.
        if(scanner!=null)Thread.startVirtualThread(scanner::cancel);
        if(connection!=null)Thread.startVirtualThread(()->{try{connection.abort(command->Thread.startVirtualThread(command));}catch(Exception ignored){}});
    }
    private synchronized boolean stopped(Scope scope){return closed||scope.cancelled||scope.currentRun==null||scope.currentRun.timeoutRequested;}
    private synchronized void startNext(){
        if(closed||scanning)return;
        for(Scope scope:scopes.values())if(scope.requested&&!scope.cancelled){
            scope.requested=false;scope.state="scanning";scope.error="";scanning=true;
            scope.currentRun.state="running";scope.currentRun.startedAt=clock.getAsLong();scope.scanRevision++;
            worker.execute(()->scan(scope));break;
        }
    }
    void cleanup(Set<String> retainedBindings){
        long now=clock.getAsLong();
        for(Scope scope:scopes()){
            boolean valid;
            try{requireCurrent(scope.target);valid=true;}catch(IllegalArgumentException gone){valid=false;}
            if(valid&&(retainedBindings.contains(scope.target.key())||scope.standaloneExpires>now))continue;
            boolean removed;synchronized(this){removed=scopes.remove(scope.target.key(),scope);}if(removed)scope.retire();
        }
    }
    void invalidate(String connection){
        for(Scope scope:scopes())if(scope.target.connection.equals(connection)){
            boolean removed;synchronized(this){removed=scopes.remove(scope.target.key(),scope);}if(removed)scope.retire();
        }
    }
    void changed(String connection){for(Scope scope:scopes())if(scope.target.connection.equals(connection))scope.nextScan=0;}
    private void requireCurrent(Target target){
        if(!target.revision.equals(ProjectContexts.profileRevision(profiles.get(target.connection))))
            throw new IllegalArgumentException("Connection changed; resolve a fresh catalog target");
    }
    private void scan(Scope scope){
        DocumentStore nextStore=null;QueryJobs.RetainedReservation reservation=null;
        CatalogScanRun runAtStart=scope.currentRun;
        ScheduledFuture<?> deadline=deadlines.schedule(()->timeout(scope,runAtStart),5,TimeUnit.MINUTES);
        String outcome="failed",errorCode="",failurePhase="";
        progress(scope,"connecting",0,0,0);
        try {
            requireCurrent(scope.target);
            ObjectNode profile=profiles.get(scope.target.connection);
            boolean nativeTransport=DatabaseTransport.of(profile)!=DatabaseTransport.JDBC;
            if(!supports(profile))throw new IllegalArgumentException("Native catalog service unavailable in this runtime");
            // Account old publications, staging, replacement and decoding through the shared DBA allowance.
            // The driver/native heap remains separately reported overhead, not a hard process cap.
            progress(scope,"reserving_memory",0,0,0);
            QueryJobs budget=accounting;
            long nativeOverhead=nativeTransport?64L<<20:0;
            long scanLimit=nativeTransport?1L<<20:CatalogScanner.MAX_BYTES;
            long maximum=budget==null?scanLimit:Math.min(scanLimit,(budget.availableRetainedBytes()-nativeOverhead)/3-OVERHEAD);
            if(maximum<OVERHEAD)throw new IllegalArgumentException("DBA allowance has insufficient space for a catalog scan");
            if(budget!=null)reservation=budget.retainAllowance(3*(maximum+OVERHEAD)+nativeOverhead);
            try(DocumentStore staged=DocumentStore.memory(maximum+OVERHEAD)){
                ObjectNode[] scanned={null};
                if(nativeTransport){
                    progress(scope,"native_inventory",0,0,0);
                    ObjectNode observed=nativeCatalogs.catalog(scope.target,()->stopped(scope));
                    long[] encoded={0};
                    staged.replace(writer->{
                        for(JsonNode object:observed.path("objects")){
                            String id=object.path("id").asText();byte[] full=object.toString().getBytes(StandardCharsets.UTF_8);
                            ObjectNode summary=Profiles.JSON.createObjectNode();
                            for(String key:List.of("id","name","schema","kind","objectHash","displayName","nameEncoding","nativeType"))if(object.has(key))summary.set(key,object.get(key));
                            byte[] compact=summary.toString().getBytes(StandardCharsets.UTF_8);encoded[0]+=full.length+compact.length;
                            if(encoded[0]>maximum)throw new IllegalArgumentException("Native catalog byte allowance exceeded");
                            writer.put("o/"+id,full);writer.put("i/"+id,compact);
                        }
                    });
                    int objectCount=observed.path("objects").size();observed.remove("objects");observed.put("objects",objectCount).put("bytes",encoded[0]).put("finishedAt",clock.getAsLong()).put("coverage","bounded native definitions and observations; not a complete inventory");scanned[0]=observed;
                }else {
                    progress(scope,"connecting",0,0,0);
                    try(Connections.Target target=connections.target(scope.target.connection,scope.target.database)){
                    Connection connection=target.connection();scope.connection=connection;
                    try{connection.setNetworkTimeout(command->Thread.startVirtualThread(command),30_000);}catch(SQLException|UnsupportedOperationException ignored){/* Deadline also requests abort. */}
                    try{connection.setReadOnly(true);}catch(SQLException unsupported){/* fixed catalog reads only */}
                    boolean transactional=connection.getMetaData().supportsTransactions();if(transactional)connection.setAutoCommit(false);
                    try{staged.replace(writer->{
                        try{
                            scope.scanner=new CatalogScanner(connection,profile,scope.target.selector(),writer,()->stopped(scope),
                                    new CatalogScanner.Limits(CatalogScanner.MAX_OBJECTS,maximum,10_000,CatalogScanner.MAX_DDL),_ -> {});
                            scope.scanner.onProgress((phase,objects,dependencies,bytes)->progress(scope,phase,objects,dependencies,bytes));
                            scanned[0]=scope.scanner.scan();
                        }catch(Exception error){throw new CompletionException(error);}
                    });}finally{if(transactional)connection.rollback();scope.scanner=null;}
                    }
                }
                requireCurrent(scope.target);if(stopped(scope))throw new CancellationException();
                ObjectNode next=scanned[0];ObjectNode before=scope.metadata();
                progress(scope,"comparing_snapshot",next.path("objects").asLong(),next.path("dependencies").asLong(),next.path("bytes").asLong());
                int[] changes={0,0,0};MessageDigest digest=MessageDigest.getInstance("SHA-256");
                digest.update(ProjectContexts.versionIdentity(next.path("version")).getBytes(StandardCharsets.UTF_8));
                Consumer<Publication> compare=previous->{
                    staged.scan("i/",(key,value)->{
                        JsonNode row=ProjectContexts.json(value),old=previous==null?null:ProjectContexts.json(previous.store.get(key));
                        if(old==null)changes[0]++;else if(!row.path("objectHash").equals(old.path("objectHash")))changes[1]++;
                        digest.update(key.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
                        digest.update(row.path("objectHash").asText().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
                    });
                    if(previous!=null&&next.path("inventoryComplete").asBoolean())
                        previous.store.scan("i/",(key,value)->{if(staged.get(key)==null)changes[2]++;});
                };
                if(scope.generation==0)compare.accept(null);else scope.read(previous->{compare.accept(previous);return null;});
                ObjectNode diff=next.putObject("changes").put("added",changes[0]).put("modified",changes[1]).put("removed",changes[2])
                        .put("removalsVerified",next.path("inventoryComplete").asBoolean());
                next.put("generation",scope.generation+1).put("fingerprint",HexFormat.of().formatHex(digest.digest()))
                        .put("versionChanged",before.has("version")&&!ProjectContexts.versionIdentity(before.path("version")).equals(ProjectContexts.versionIdentity(next.path("version"))));
                ArrayNode history=next.putArray("recentScans");JsonNode prior=before.path("recentScans");
                for(int i=Math.max(0,prior.size()-4);i<prior.size();i++)history.add(prior.get(i));
                history.addObject().put("generation",scope.generation+1).put("finishedAt",next.path("finishedAt").asLong())
                        .set("version",next.path("version"));
                ((ObjectNode)history.get(history.size()-1)).set("changes",diff);
                long retained=next.path("bytes").asLong()+OVERHEAD;
                for(Scope other:scopes())if(other!=scope)retained+=other.metadata().path("bytes").asLong()+OVERHEAD;
                if(retained>MAX_RETAINED)throw new IllegalArgumentException("Combined catalog snapshot limit of 128 MiB exceeded");
                progress(scope,"publishing",next.path("objects").asLong(),next.path("dependencies").asLong(),next.path("bytes").asLong());
                nextStore=stores.get();DocumentStore destination=nextStore;
                destination.replace(writer->{staged.scan("",writer::put);writer.put("metadata",next.toString().getBytes(StandardCharsets.UTF_8));});
                requireCurrent(scope.target);
                synchronized(this){
                    if(stopped(scope)||scopes.get(scope.target.key())!=scope)throw new CancellationException();
                    if(reservation!=null)reservation.resize(next.path("bytes").asLong()+OVERHEAD);
                    var lease=reservation;
                    scope.publish(new Publication(nextStore,next,lease==null?()->{}:lease::close));nextStore=null;reservation=null;
                    scope.state=next.path("inventoryComplete").asBoolean()?"ready":"partial";scope.error="";
                    outcome=next.path("inventoryComplete").asBoolean()?"succeeded":"partial";
                }
            }
        }catch(Exception error){
            synchronized(this){failurePhase=scope.currentRun.phase;}
            Throwable cause=error;for(int depth=0;depth<6&&cause.getCause()!=null&&cause.getCause()!=cause;depth++)cause=cause.getCause();
            errorCode=cause instanceof CatalogScanner.CaptureLimit?"capture_limit":cause instanceof SQLException?"database_error":"scan_failed";
            if(cause instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().matches("[A-Za-z0-9]{5}"))errorCode+="_"+sql.getSQLState();
            if(!scope.cancelled){scope.state=scope.generation>0?"stale":"failed";scope.error="Scan failed during "+failurePhase+"; check the target, privileges, driver and DBA memory. "+(scope.generation>0?"Previous snapshot retained.":"No snapshot published.");}
        }finally{
            try{if(nextStore!=null)nextStore.close();}finally{
                if(reservation!=null)reservation.close();
                synchronized(this){
                    deadline.cancel(false);var run=scope.currentRun;
                    if(run.timeoutRequested){outcome="timed_out";errorCode="scan_timeout";scope.state=scope.generation>0?"stale":"failed";scope.error="Catalog scan exceeded five minutes; cancellation requested. Narrow the scope or check the driver and server.";}
                    else if(closed||scope.cancelled)outcome="cancelled";
                    run.state=outcome;run.finishedAt=run.updatedAt=clock.getAsLong();
                    run.errorCode=errorCode;run.error=scope.error;
                    scope.lastRun=run;scope.currentRun=null;scope.scanRevision++;
                    scope.scanner=null;scope.connection=null;scope.nextScan=scope.cadence==Long.MAX_VALUE?Long.MAX_VALUE:clock.getAsLong()+scope.cadence;
                    scanning=false;startNext();
                }
            }
        }
    }
    synchronized ObjectNode status(Target target){
        Scope scope=find(target);ObjectNode out=target.selector().put("state",scope==null?"never_scanned":scope.state)
                .put("generation",scope==null?0:scope.generation).put("scanInProgress",scope!=null&&scope.currentRun!=null)
                .put("scanRevision",scope==null?0:scope.scanRevision).put("scanTimeoutMillis",300_000)
                .put("standaloneIdleTimeoutSeconds",STANDALONE_IDLE_MILLIS/1000);
        if(scope!=null){
            out.put("error",scope.error).put("nextScanAt",scope.nextScan==Long.MAX_VALUE?0:scope.nextScan)
                .put("expiresAt",scope.standaloneExpires).set("snapshot",scope.metadata());
            if(scope.currentRun!=null)out.set("currentRun",scope.currentRun.json(clock.getAsLong()));
            if(scope.lastRun!=null)out.set("lastRun",scope.lastRun.json(clock.getAsLong()));
        }
        return out;
    }
    public void close(){
        List<Scope> retired;synchronized(this){closed=true;retired=List.copyOf(scopes.values());scopes.clear();}
        retired.forEach(Scope::retire);deadlines.shutdownNow();worker.shutdownNow();
    }
}
