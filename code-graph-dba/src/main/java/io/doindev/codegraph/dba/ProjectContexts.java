package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.store.DocumentStore;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** Persistent bindings, independent catalog generations and MCP-driven activity leases. */
final class ProjectContexts implements AutoCloseable {
    static final List<String> ENVIRONMENTS=List.of("local","dev","test","stage","prod");
    private final Profiles profiles;private final Connections connections;private final AgentAccess agents;
    private final Path file;private ObjectNode configuration;
    private java.util.function.Consumer<String> authorizationChanged=id->{};
    synchronized void onAuthorizationChange(java.util.function.Consumer<String> listener){authorizationChanged=listener;}
    private volatile ProjectContextHost host=ProjectContextHost.detached();
    private final LongSupplier clock;
    private final Map<String,Long> activity=new HashMap<>();
    private final Map<String,Integer> holds=new HashMap<>();
    private final CatalogCache cache;
    private final CatalogQueries queries;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("catalog-activity").factory());
    private volatile boolean closed;
    private final Semaphore statusWaiters=new Semaphore(4);
    ProjectContexts(Profiles p,Connections c,AgentAccess a)throws java.io.IOException{this(p,c,a,System::currentTimeMillis,true);}
    ProjectContexts(Profiles p,Connections c,AgentAccess a,LongSupplier clock,boolean schedule)throws java.io.IOException{
        profiles=p;connections=c;agents=a;this.clock=clock;file=p.directory().resolve("project-contexts.json");
        if(Files.exists(file)){if(Files.size(file)>1<<20)throw new java.io.IOException("Project binding configuration too large");configuration=(ObjectNode)Profiles.JSON.readTree(file.toFile());}
        else{configuration=Profiles.JSON.createObjectNode().put("version",2);configuration.putArray("bindings");}
        if(!configuration.path("bindings").isArray()||configuration.path("bindings").size()>128)throw new java.io.IOException("Invalid project bindings");
        migrate();
        cache=new CatalogCache(profiles,connections,()->host.documents(),clock);queries=new CatalogQueries(clock);
        if(schedule)timer.scheduleWithFixedDelay(()->{try{tick();}catch(Exception ignored){}},1,1,TimeUnit.SECONDS);
    }
    synchronized void attach(ProjectContextHost host){this.host=Objects.requireNonNull(host);}
    void accounting(QueryJobs jobs){cache.accounting(jobs);queries.accounting(jobs);}
    void nativeCatalogs(NativeOperations operations){cache.nativeCatalogs(operations);}
    static String projectId(String root){return UUID.nameUUIDFromBytes(root.getBytes(StandardCharsets.UTF_8)).toString();}
    synchronized JsonNode projects(){return host.projects();}
    synchronized JsonNode mappings(String project){for(JsonNode row:host.projects())if(row.path("id").asText().equals(project))return host.mappings(project);throw new IllegalArgumentException("Unknown or unloaded application project");}
    synchronized ObjectNode save(JsonNode request)throws Exception{
        String project=Profiles.text(request,"projectId",36),connection=Profiles.text(request,"connectionId",36);
        JsonNode selected=null;for(JsonNode p:host.projects())if(p.path("id").asText().equals(project))selected=p;
        if(selected==null)throw new IllegalArgumentException("Select an onboarded project");profiles.get(connection);
        String database=bounded(request,"database",256),schema=bounded(request,"schema",256);
        if(database.isBlank()&&schema.isBlank())throw new IllegalArgumentException("A database/catalog or schema is required");
        ObjectNode selectedProfile=profiles.get(connection);
        if(DatabaseTransport.of(selectedProfile)!=DatabaseTransport.JDBC){
            if(!schema.isBlank())throw new IllegalArgumentException("Native database bindings do not have a SQL schema");
            NativeTarget.resolve(selectedProfile,Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName",selectedProfile.path("name").asText()).put("database",database));
        }
        String environment=environment(Profiles.text(request,"environment",32));
        String role=role(Profiles.text(request,"role",80));
        String purpose=Profiles.text(request,"purpose",500).strip();
        int interval=number(request,"scanIntervalSeconds",900,10,86400),idle=number(request,"idleTimeoutSeconds",1800,10,604800);
        String id=request.path("id").asText(UUID.randomUUID().toString());UUID.fromString(id);
        ObjectNode binding=Profiles.JSON.createObjectNode().put("id",id).put("projectId",project).put("projectName",selected.path("name").asText()).put("connectionId",connection)
            .put("database",database).put("schema",schema).put("role",role).put("roleKey",roleKey(role)).put("purpose",purpose).put("label",role).put("environment",environment).put("legacyEnvironment",false).put("reviewRequired",false)
            .put("scanIntervalSeconds",interval).put("idleTimeoutSeconds",idle).put("enabled",request.path("enabled").asBoolean(true));
        ObjectNode next=configuration.deepCopy();ArrayNode bindings=(ArrayNode)next.path("bindings");int found=-1;
        for(int i=0;i<bindings.size();i++)if(bindings.get(i).path("id").asText().equals(id))found=i;
        for(int i=0;i<bindings.size();i++){JsonNode other=bindings.get(i);if(i!=found&&other.path("projectId").asText().equals(project)&&other.path("environment").asText().equals(environment)&&other.path("roleKey").asText(roleKey(other.path("role").asText(other.path("label").asText()))).equals(roleKey(role)))throw new IllegalArgumentException("Logical role must be unique within the application and environment");}
        if(found<0){if(bindings.size()>=128)throw new IllegalArgumentException("Maximum 128 bindings");bindings.add(binding);}else{JsonNode old=bindings.get(found);if(!old.path("projectId").equals(binding.path("projectId"))||!old.path("connectionId").equals(binding.path("connectionId"))||!old.path("database").equals(binding.path("database"))||!old.path("schema").equals(binding.path("schema")))throw new IllegalArgumentException("Binding scope is immutable; remove it and add a new binding so agent grants cannot change targets");bindings.set(found,binding);}
        if(found>=0)authorizationChanged.accept(id);binding.put("authorizationRevision",UUID.randomUUID().toString());persist(next);configuration=next;return binding.deepCopy();
    }
    synchronized void remove(String id)throws Exception{ObjectNode next=configuration.deepCopy();ArrayNode list=(ArrayNode)next.path("bindings");boolean found=false;for(int i=list.size()-1;i>=0;i--)if(list.get(i).path("id").asText().equals(id)){list.remove(i);found=true;}if(!found)throw new IllegalArgumentException("Unknown binding");authorizationChanged.accept(id);persist(next);configuration=next;cleanupScopes();}
    synchronized void removeConnection(String connection)throws Exception{ObjectNode next=configuration.deepCopy();ArrayNode list=(ArrayNode)next.path("bindings");boolean changed=false;for(int i=list.size()-1;i>=0;i--)if(list.get(i).path("connectionId").asText().equals(connection)){list.remove(i);changed=true;}if(changed){persist(next);configuration=next;}cache.invalidate(connection);cleanupScopes();}
    synchronized ArrayNode bindingsForConnection(String connection){ArrayNode out=Profiles.JSON.createArrayNode();for(JsonNode b:configuration.path("bindings"))if(b.path("connectionId").asText().equals(connection))out.add(b.deepCopy());return out;}
    synchronized ObjectNode binding(String id){for(JsonNode b:configuration.path("bindings"))if(b.path("id").asText().equals(id))return b.deepCopy();throw new IllegalArgumentException("Unknown project database binding");}
    private String key(JsonNode b){return cache.target(b).key();}
    static String profileRevision(JsonNode profile){return CatalogScanner.hash(profile.toString());}
    synchronized void touch(String project){for(JsonNode p:host.projects())if(p.path("id").asText().equals(project)){activity.put(project,clock.getAsLong());try(AutoCloseable lease=host.hold(project)){/* Database activity also renews the project unload lease. */}catch(Exception e){throw new IllegalStateException("Project activity lease failed",e);}return;}}
    synchronized void databaseQueried(String connection,String schema){Set<String> matched=new HashSet<>();for(JsonNode b:configuration.path("bindings"))if(b.path("connectionId").asText().equals(connection)&&(schema==null||b.path("schema").asText().isBlank()||b.path("schema").asText().equals(schema)))matched.add(b.path("projectId").asText());if(matched.size()==1)touch(matched.iterator().next());}
    synchronized void touchName(String name){for(JsonNode p:host.projects())if(p.path("name").asText().equals(name)){touch(p.path("id").asText());return;}}
    synchronized AutoCloseable hold(String bindingId){String project=binding(bindingId).path("projectId").asText();touch(project);holds.merge(project,1,Integer::sum);AutoCloseable projectLease=host.hold(project);return ()->{synchronized(this){holds.computeIfPresent(project,(k,v)->v<=1?null:v-1);touch(project);}projectLease.close();};}
    private Set<String> onboarded(){Set<String> ids=new HashSet<>();for(JsonNode p:host.projects())ids.add(p.path("id").asText());return ids;}
    private boolean active(JsonNode b,Set<String> onboarded){String p=b.path("projectId").asText();return b.path("enabled").asBoolean()&&onboarded.contains(p)&&(holds.getOrDefault(p,0)>0||activity.containsKey(p)&&clock.getAsLong()-activity.get(p)<b.path("idleTimeoutSeconds").asLong()*1000);}
    synchronized ObjectNode state(){cleanupScopes();ObjectNode result=Profiles.JSON.createObjectNode().put("scanIntervalDefaultSeconds",900).put("idleTimeoutDefaultSeconds",1800).put("scanInProgress",cache.scanning());result.set("projects",host.projects());ArrayNode list=result.putArray("bindings");Set<String> live=onboarded();for(JsonNode b:configuration.path("bindings")){ObjectNode row=b.deepCopy();CatalogCache.Scope scope=null;try{scope=cache.find(cache.target(b));ObjectNode profile=profiles.get(b.path("connectionId").asText());row.put("connectionName",profile.path("name").asText()).put("scanSupported",cache.supports(profile));}catch(IllegalArgumentException e){row.put("connectionName","Removed connection");}row.put("active",active(b,live)).put("onboarded",live.contains(b.path("projectId").asText())).put("lastActivityAt",activity.getOrDefault(b.path("projectId").asText(),0L));if(scope!=null){row.put("state",scope.state).put("error",scope.error).put("generation",scope.generation).put("nextScanAt",active(b,live)?scope.nextScan:0).set("snapshot",scope.metadata());}else row.put("state",row.path("scanSupported").asBoolean(true)?"never_scanned":"native_live_only");list.add(row);}return result;}
    synchronized void scanNow(String id){
        ObjectNode b=binding(id);
        if(!cache.supports(profiles.get(b.path("connectionId").asText())))
            throw new IllegalArgumentException("Native catalog service unavailable in this runtime");
        if(!b.path("enabled").asBoolean())throw new IllegalArgumentException("Enable this binding first");
        if(!onboarded().contains(b.path("projectId").asText()))throw new IllegalArgumentException("Onboard the related project first");
        touch(b.path("projectId").asText());cache.request(cache.target(b),b.path("scanIntervalSeconds").asLong()*1000);
    }
    synchronized String fingerprint(String id){var scope=cache.find(cache.target(binding(id)));return scope==null?"":scope.metadata().path("fingerprint").asText();}
    synchronized void changed(String connection){cache.changed(connection);}
    synchronized void tick(){
        if(closed)return;cleanupScopes();Set<String> live=onboarded();
        var activeTargets=new LinkedHashMap<CatalogCache.Target,Long>();
        for(JsonNode b:configuration.path("bindings"))if(active(b,live)){
            try{
                if(!cache.supports(profiles.get(b.path("connectionId").asText())))continue;
                activeTargets.merge(cache.target(b),b.path("scanIntervalSeconds").asLong()*1000,Math::min);
            }catch(IllegalArgumentException removed){}
        }
        activeTargets.forEach(cache::due);
    }
    static String versionIdentity(JsonNode version){ObjectNode v=version.deepCopy();v.remove("detectedAt");return v.toString();}
    synchronized ObjectNode authorized(String principal,String bindingId,boolean live){ObjectNode b=binding(bindingId);agents.requireContext(principal,b,live);if(!b.path("enabled").asBoolean())throw new SecurityException("Binding is paused");if(!onboarded().contains(b.path("projectId").asText()))throw new IllegalArgumentException("Related project is unloaded; onboard it first");profiles.get(b.path("connectionId").asText());return b;}
    JsonNode agent(String principal,String operation,JsonNode args){
        return agent(principal,operation,args,null);
    }
    JsonNode agent(String principal,String operation,JsonNode args,java.util.function.Consumer<ObjectNode> supplemental){
        if(operation.equals("dba_list_project_databases")){ObjectNode out=Profiles.JSON.createObjectNode();ArrayNode rows=out.putArray("bindings");for(JsonNode b:state().path("bindings"))try{if(!agents.isTrustedLocal(principal))agents.requireContext(principal,(ObjectNode)b,false);ObjectNode row=b.deepCopy();row.remove("projectRoot");row.set("effectivePermissions",agents.effectiveForBinding(principal,row));rows.add(row);}catch(SecurityException ignored){}return out;}
        String id=Profiles.text(args,"bindingId",36);ObjectNode b;
        try{b=authorized(principal,id,false);}catch(SecurityException denied){if(supplemental==null)throw denied;b=authorized(principal,id,true);supplemental.accept(b);}
        final ObjectNode selectedBinding=b;if(!operation.equals("dba_scan_status"))touch(b.path("projectId").asText());
        if(operation.equals("dba_refresh_catalog")){scanNow(id);return scanStatus(principal,id,args,supplemental);}
        if(operation.equals("dba_scan_status"))return scanStatus(principal,id,args,supplemental);
        CatalogCache.Target target=cache.target(b);CatalogCache.Scope scope=cache.find(target);
        if(scope==null||scope.generation==0)return Profiles.JSON.createObjectNode().put("state","scan_pending").put("message","Catalog scan will start while this project is active; retry after it completes");
        JsonNode result=queries.read(scope,principal,target.key(),profileRevision(b),operation,args,
                object->host.references(selectedBinding.path("projectId").asText(),object.path("schema").asText(),object.path("name").asText()));
        ObjectNode current;
        try{current=authorized(principal,id,false);}catch(SecurityException denied){if(supplemental==null)throw denied;current=authorized(principal,id,true);supplemental.accept(current);}
        if(!profileRevision(current).equals(profileRevision(b))||!cache.target(current).equals(target))
            throw new SecurityException("Catalog authorization target changed during query");
        return result;
    }
    JsonNode standalone(String principal,String operation,JsonNode args,java.util.function.Supplier<WorkflowTargets.Target> authorize){
        WorkflowTargets.Target selected=authorize.get();CatalogCache.Target target=cache.target(selected.scope());
        cleanupScopes();
        var previous=cache.find(target);
        if(args.has("cursor")&&(previous==null||previous.generation==0))throw new IllegalArgumentException("stale_cursor: catalog scope expired or changed; restart without a cursor");
        if(!operation.equals("dba_scan_status")){
            cache.touchStandalone(target);
            if(operation.equals("dba_refresh_catalog")||cache.find(target).generation==0)cache.request(target,Long.MAX_VALUE);
        }
        if(operation.equals("dba_scan_status")||operation.equals("dba_refresh_catalog")){
            int wait=number(args,"waitMillis",0,0,5000);long after=args.path("afterGeneration").asLong(-1);
            if(args.has("afterGeneration")&&(!args.path("afterGeneration").isIntegralNumber()||!args.path("afterGeneration").canConvertToLong()||after<0))throw new IllegalArgumentException("afterGeneration must be a nonnegative integer");
            if(wait>0&&after<0)throw new IllegalArgumentException("afterGeneration is required when waiting");
            if(wait>0&&!statusWaiters.tryAcquire())throw new IllegalArgumentException("Catalog status wait capacity reached");
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(wait);
            try{
                while(true){
                    revalidateTarget(selected,authorize.get());cleanupScopes();
                    ObjectNode status=cache.status(target);boolean expired=System.nanoTime()>=deadline;
                    if(wait==0||status.path("generation").asLong()>after||expired||closed)
                        return status.put("waitTimedOut",wait>0&&status.path("generation").asLong()<=after&&expired)
                                .put("scope","standalone").put("authorizationReason",selected.authorization());
                    if(Thread.currentThread().isInterrupted())throw new CancellationException("Catalog wait cancelled");
                    java.util.concurrent.locks.LockSupport.parkNanos(Math.min(100_000_000L,Math.max(0,deadline-System.nanoTime())));
                }
            }finally{if(wait>0)statusWaiters.release();}
        }
        CatalogCache.Scope scope=cache.find(target);
        if(scope==null||scope.generation==0)return cache.status(target).put("state","scan_pending").put("message","Exact standalone catalog requested; use dba_scan_status with bounded waiting");
        JsonNode result=queries.read(scope,principal,target.key(),selected.scope().toString(),operation,args,object->{
            String project=Profiles.text(args,"projectId",36);
            if(!onboarded().contains(project))throw new IllegalArgumentException("Standalone code references require an explicit onboarded projectId");
            return host.references(project,object.path("schema").asText(),object.path("name").asText());
        });
        revalidateTarget(selected,authorize.get());
        if(result instanceof ObjectNode out){out.set("target",selected.request());out.put("scope","standalone").put("authorizationReason",selected.authorization());}
        return result;
    }
    private static void revalidateTarget(WorkflowTargets.Target before,WorkflowTargets.Target after){
        if(!before.scope().equals(after.scope()))throw new SecurityException("Catalog target or authorization revision changed; request a fresh snapshot");
    }
    private JsonNode scanStatus(String principal,String id,JsonNode args,java.util.function.Consumer<ObjectNode> supplemental){
        int wait=number(args,"waitMillis",0,0,5000);
        long after=args.path("afterGeneration").asLong(-1);
        if(args.has("afterGeneration")&&(!args.path("afterGeneration").isIntegralNumber()||after<0))throw new IllegalArgumentException("afterGeneration must be a nonnegative integer");
        if(wait>0&&after<0)throw new IllegalArgumentException("afterGeneration is required when waiting");
        if(wait>0&&!statusWaiters.tryAcquire())throw new IllegalArgumentException("Catalog status wait capacity reached; poll without waiting");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(wait);
        try{
            while(true){
                ObjectNode current;try{current=authorized(principal,id,false);}catch(SecurityException denied){if(supplemental==null)throw denied;current=authorized(principal,id,true);supplemental.accept(current);}
                long generation;synchronized(this){CatalogCache.Scope scope=cache.find(cache.target(current));generation=scope==null?0:scope.generation;}
                boolean timeout=System.nanoTime()>=deadline;
                if(wait==0||generation>after||timeout||closed){
                    for(JsonNode row:state().path("bindings"))if(row.path("id").asText().equals(id))return ((ObjectNode)row).put("waitTimedOut",wait>0&&generation<=after&&timeout);
                    throw new IllegalArgumentException("Binding removed while waiting");
                }
                try{Thread.sleep(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));}
                catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException("Catalog status wait cancelled");}
            }
        }finally{if(wait>0)statusWaiters.release();}
    }
    static JsonNode json(byte[] bytes){if(bytes==null)return null;try{return Profiles.JSON.readTree(bytes);}catch(Exception e){throw new IllegalStateException("Invalid snapshot record",e);}}
    private synchronized void cleanupScopes(){
        Set<String> used=new HashSet<>(),live=onboarded();
        for(JsonNode b:configuration.path("bindings"))if(live.contains(b.path("projectId").asText()))
            try{used.add(key(b));}catch(IllegalArgumentException ignored){}
        cache.cleanup(used);
    }
    static String bounded(JsonNode input,String field,int max){String value=input.path(field).asText("").strip();if(value.length()>max||value.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid "+field);return value;}
    static int number(JsonNode n,String key,int fallback,int min,int max){if(!n.has(key))return fallback;if(!n.path(key).canConvertToInt()||!n.path(key).isIntegralNumber())throw new IllegalArgumentException(key+" must be an integer");int value=n.path(key).asInt();if(value<min||value>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);return value;}
    static String environment(String value){String key=value.strip().toLowerCase(Locale.ROOT).replaceAll("[ _-]+","");return switch(key){case "local","localhost"->"local";case "dev","development"->"dev";case "test","testing","qa"->"test";case "stage","staging","preprod","preproduction"->"stage";case "prod","production"->"prod";default->throw new IllegalArgumentException("Environment must be local, dev, test, stage, or prod");};}
    static String role(String value){String role=value.strip();if(!role.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,79}"))throw new IllegalArgumentException("Role must start with a letter or number and contain at most 80 ordinary name characters");return role;}
    static String roleKey(String value){return Profiles.nameKey(value).replaceAll("[ _-]+","-");}
    private void migrate()throws java.io.IOException{
        if(configuration.path("version").asInt(1)>=2)return;
        ObjectNode next=configuration.deepCopy().put("version",2);for(JsonNode raw:next.path("bindings")){ObjectNode b=(ObjectNode)raw;String original=b.path("environment").asText("");try{b.put("environment",environment(original.isBlank()?"local":original)).put("legacyEnvironment",false);}catch(IllegalArgumentException unknown){b.put("legacyEnvironment",true).put("reviewRequired",true);}
            String originalRole=b.path("role").asText(b.path("label").asText("database")).strip();if(originalRole.isBlank()||!originalRole.matches("[A-Za-z0-9][A-Za-z0-9._ -]{0,60}"))originalRole="database";String candidate=originalRole+"-legacy-"+b.path("id").asText().substring(0,8);b.put("role",candidate).put("roleKey",roleKey(candidate)).put("label",candidate).put("reviewRequired",true);if(b.path("purpose").asText().isBlank())b.put("purpose","Migrated legacy project database binding; review its role and purpose");}
        try{persist(next);}catch(Exception e){throw new java.io.IOException("Cannot migrate project database bindings",e);}configuration=next;
    }
    private void persist(ObjectNode next)throws Exception{Path temp=Files.createTempFile(file.getParent(),"bindings-",".tmp");try{Profiles.protect(temp);Files.writeString(temp,next.toString(),StandardCharsets.UTF_8);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    public synchronized void close(){closed=true;timer.shutdownNow();cache.close();}
}
