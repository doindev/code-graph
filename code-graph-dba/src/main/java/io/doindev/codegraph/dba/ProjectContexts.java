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
    private final Map<String,Scope> scopes=new HashMap<>();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("catalog-activity").factory());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("catalog-scan").factory());
    private volatile boolean closed;private boolean scanning;
    private final io.doindev.codegraph.query.GenerationCursor catalogCursors = new io.doindev.codegraph.query.GenerationCursor();
    private final Semaphore statusWaiters = new Semaphore(4);
    static final class Scope{
        final DocumentStore store;volatile ObjectNode metadata=Profiles.JSON.createObjectNode();
        volatile String state="never_scanned",error="";long nextScan,generation;volatile boolean cancelled;
        volatile CatalogScanner scanner;Scope(DocumentStore store){this.store=store;}
    }
    ProjectContexts(Profiles p,Connections c,AgentAccess a)throws java.io.IOException{this(p,c,a,System::currentTimeMillis,true);}
    ProjectContexts(Profiles p,Connections c,AgentAccess a,LongSupplier clock,boolean schedule)throws java.io.IOException{
        profiles=p;connections=c;agents=a;this.clock=clock;file=p.directory().resolve("project-contexts.json");
        if(Files.exists(file)){if(Files.size(file)>1<<20)throw new java.io.IOException("Project binding configuration too large");configuration=(ObjectNode)Profiles.JSON.readTree(file.toFile());}
        else{configuration=Profiles.JSON.createObjectNode().put("version",2);configuration.putArray("bindings");}
        if(!configuration.path("bindings").isArray()||configuration.path("bindings").size()>128)throw new java.io.IOException("Invalid project bindings");
        migrate();
        if(schedule)timer.scheduleWithFixedDelay(()->{try{tick();}catch(Exception ignored){}},1,1,TimeUnit.SECONDS);
    }
    synchronized void attach(ProjectContextHost host){this.host=Objects.requireNonNull(host);}
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
    synchronized void removeConnection(String connection)throws Exception{ObjectNode next=configuration.deepCopy();ArrayNode list=(ArrayNode)next.path("bindings");boolean changed=false;for(int i=list.size()-1;i>=0;i--)if(list.get(i).path("connectionId").asText().equals(connection)){list.remove(i);changed=true;}if(changed){persist(next);configuration=next;cleanupScopes();}}
    synchronized ArrayNode bindingsForConnection(String connection){ArrayNode out=Profiles.JSON.createArrayNode();for(JsonNode b:configuration.path("bindings"))if(b.path("connectionId").asText().equals(connection))out.add(b.deepCopy());return out;}
    synchronized ObjectNode binding(String id){for(JsonNode b:configuration.path("bindings"))if(b.path("id").asText().equals(id))return b.deepCopy();throw new IllegalArgumentException("Unknown project database binding");}
    private String key(JsonNode b){ObjectNode p=profiles.get(b.path("connectionId").asText());return CatalogScanner.hash(b.path("connectionId").asText()+"\n"+b.path("database").asText()+"\n"+b.path("schema").asText()+"\n"+profileRevision(p));}
    static String profileRevision(JsonNode profile){return CatalogScanner.hash(profile.toString());}
    synchronized void touch(String project){for(JsonNode p:host.projects())if(p.path("id").asText().equals(project)){activity.put(project,clock.getAsLong());try(AutoCloseable lease=host.hold(project)){/* Database activity also renews the project unload lease. */}catch(Exception e){throw new IllegalStateException("Project activity lease failed",e);}return;}}
    synchronized void databaseQueried(String connection,String schema){Set<String> matched=new HashSet<>();for(JsonNode b:configuration.path("bindings"))if(b.path("connectionId").asText().equals(connection)&&(schema==null||b.path("schema").asText().isBlank()||b.path("schema").asText().equals(schema)))matched.add(b.path("projectId").asText());if(matched.size()==1)touch(matched.iterator().next());}
    synchronized void touchName(String name){for(JsonNode p:host.projects())if(p.path("name").asText().equals(name)){touch(p.path("id").asText());return;}}
    synchronized AutoCloseable hold(String bindingId){String project=binding(bindingId).path("projectId").asText();touch(project);holds.merge(project,1,Integer::sum);AutoCloseable projectLease=host.hold(project);return ()->{synchronized(this){holds.computeIfPresent(project,(k,v)->v<=1?null:v-1);touch(project);}projectLease.close();};}
    private Set<String> onboarded(){Set<String> ids=new HashSet<>();for(JsonNode p:host.projects())ids.add(p.path("id").asText());return ids;}
    private boolean active(JsonNode b,Set<String> onboarded){String p=b.path("projectId").asText();return b.path("enabled").asBoolean()&&onboarded.contains(p)&&(holds.getOrDefault(p,0)>0||activity.containsKey(p)&&clock.getAsLong()-activity.get(p)<b.path("idleTimeoutSeconds").asLong()*1000);}
    synchronized ObjectNode state(){cleanupScopes();ObjectNode result=Profiles.JSON.createObjectNode().put("scanIntervalDefaultSeconds",900).put("idleTimeoutDefaultSeconds",1800).put("scanInProgress",scanning);result.set("projects",host.projects());ArrayNode list=result.putArray("bindings");Set<String> live=onboarded();for(JsonNode b:configuration.path("bindings")){ObjectNode row=b.deepCopy();Scope scope=null;try{scope=scopes.get(key(b));ObjectNode profile=profiles.get(b.path("connectionId").asText());row.put("connectionName",profile.path("name").asText()).put("scanSupported",DatabaseTransport.of(profile)==DatabaseTransport.JDBC);}catch(IllegalArgumentException e){row.put("connectionName","Removed connection");}row.put("active",active(b,live)).put("onboarded",live.contains(b.path("projectId").asText())).put("lastActivityAt",activity.getOrDefault(b.path("projectId").asText(),0L));if(scope!=null){row.put("state",scope.state).put("error",scope.error).put("generation",scope.generation).put("nextScanAt",active(b,live)?scope.nextScan:0).set("snapshot",scope.metadata.deepCopy());}else row.put("state",row.path("scanSupported").asBoolean(true)?"never_scanned":"native_live_only");list.add(row);}return result;}
    synchronized void scanNow(String id){ObjectNode b=binding(id);if(DatabaseTransport.of(profiles.get(b.path("connectionId").asText()))!=DatabaseTransport.JDBC)throw new IllegalArgumentException("Native cached-catalog scanning is not enabled; inspect this exact target through native metadata commands");if(!b.path("enabled").asBoolean())throw new IllegalArgumentException("Enable this binding first");if(!onboarded().contains(b.path("projectId").asText()))throw new IllegalArgumentException("Onboard the related project first");touch(b.path("projectId").asText());Scope s=scopes.computeIfAbsent(key(b),k->new Scope(host.documents()));s.nextScan=0;tick();}
    synchronized String fingerprint(String id){Scope scope=scopes.get(key(binding(id)));return scope==null?"":scope.metadata.path("fingerprint").asText();}
    synchronized void changed(String connection){for(JsonNode b:configuration.path("bindings"))if(b.path("connectionId").asText().equals(connection))try{Scope s=scopes.get(key(b));if(s!=null)s.nextScan=0;}catch(IllegalArgumentException ignored){}}
    synchronized void tick(){
        if(closed||scanning)return;cleanupScopes();Set<String> live=onboarded();long now=clock.getAsLong();
        List<JsonNode> ordered=new ArrayList<>();configuration.path("bindings").forEach(ordered::add);ordered.sort(Comparator.comparingLong(b->{try{Scope s=scopes.get(key(b));return s==null?0:s.nextScan;}catch(Exception e){return Long.MAX_VALUE;}}));
        for(JsonNode raw:ordered){
            ObjectNode b=(ObjectNode)raw;if(!active(b,live))continue;String key;try{if(DatabaseTransport.of(profiles.get(b.path("connectionId").asText()))!=DatabaseTransport.JDBC)continue;key=key(b);}catch(IllegalArgumentException removed){continue;}
            Scope scope=scopes.computeIfAbsent(key,k->new Scope(host.documents()));
            long interval=Long.MAX_VALUE;for(JsonNode candidate:configuration.path("bindings"))if(active(candidate,live)){try{if(key(candidate).equals(key))interval=Math.min(interval,candidate.path("scanIntervalSeconds").asLong()*1000);}catch(IllegalArgumentException ignored){}}
            final long cadence=interval;
            if(scope.nextScan>now)continue;
            scanning=true;scope.state="scanning";scope.cancelled=false;ObjectNode copy=b.deepCopy();
            worker.execute(()->scan(copy,scope,cadence));break;
        }
    }
    private void scan(ObjectNode binding,Scope scope,long interval){
        try(DocumentStore staged=DocumentStore.memory(CatalogScanner.MAX_BYTES+1_048_576)){
            ObjectNode profile=profiles.get(binding.path("connectionId").asText());String expected=profileRevision(profile);ObjectNode[] metadata={null};
            try(Connections.Target target=connections.target(binding.path("connectionId").asText(),binding.path("database").asText())){
                Connection c=target.connection();
                try{c.setReadOnly(true);}catch(SQLException unsupported){/* Fixed metadata reads remain safe when a driver cannot set read-only mode. */}
                boolean transactions=c.getMetaData().supportsTransactions();if(transactions)c.setAutoCommit(false);
                try{staged.replace(writer->{try{scope.scanner=new CatalogScanner(c,profile,binding,writer,()->closed||scope.cancelled);metadata[0]=scope.scanner.scan();}catch(Exception e){throw new CompletionException(e);}});}finally{if(transactions)c.rollback();scope.scanner=null;}
            }
            synchronized(this){if(closed||scope.cancelled||!expected.equals(profileRevision(profiles.get(binding.path("connectionId").asText()))))throw new CancellationException();}
            ObjectNode next=metadata[0];ObjectNode changes=next.putObject("changes");int[] added={0},modified={0},removed={0};
            staged.scan("i/",(k,v)->{JsonNode n=json(v),old=json(scope.store.get(k));if(old==null)added[0]++;else if(!n.path("objectHash").equals(old.path("objectHash")))modified[0]++;});
            if(next.path("inventoryComplete").asBoolean())scope.store.scan("i/",(k,v)->{if(staged.get(k)==null)removed[0]++;});
            changes.put("added",added[0]).put("modified",modified[0]).put("removed",removed[0]).put("removalsVerified",next.path("inventoryComplete").asBoolean());
            next.put("generation",scope.generation+1).put("versionChanged",scope.metadata.has("version")&&!versionIdentity(scope.metadata.path("version")).equals(versionIdentity(next.path("version"))));
            StringBuilder identities=new StringBuilder(versionIdentity(next.path("version")));staged.scan("i/",(k,v)->identities.append(k).append(json(v).path("objectHash").asText()));next.put("fingerprint",CatalogScanner.hash(identities.toString()));
            ArrayNode history=next.putArray("recentScans");JsonNode previous=scope.metadata.path("recentScans");for(int i=Math.max(0,previous.size()-4);i<previous.size();i++)history.add(previous.get(i));ObjectNode event=history.addObject().put("generation",scope.generation+1).put("finishedAt",next.path("finishedAt").asLong());event.set("version",next.path("version"));event.set("changes",changes);
            synchronized(this){long retained=next.path("bytes").asLong();for(Scope other:scopes.values())if(other!=scope)retained+=other.metadata.path("bytes").asLong();if(retained>128L<<20)throw new IllegalArgumentException("Combined database snapshot limit of 128 MiB exceeded");}
            scope.store.replace(writer->{staged.scan("",writer::put);writer.put("metadata",next.toString().getBytes(StandardCharsets.UTF_8));});scope.metadata=next;scope.generation++;scope.error="";scope.state=next.path("inventoryComplete").asBoolean()?"ready":"partial";
        }catch(Exception error){scope.state=scope.generation>0?"stale":"failed";scope.error="Scan failed; previous snapshot retained. Verify permissions, selected catalog, provider capabilities and scan limits.";}
        finally{synchronized(this){scope.nextScan=clock.getAsLong()+interval;scanning=false;}}
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
        Scope scope;synchronized(this){scope=scopes.get(key(b));}if(scope==null||scope.generation==0)return Profiles.JSON.createObjectNode().put("state","scan_pending").put("message","Catalog scan will start while this project is active; retry after it completes");
        final Scope found=scope;return scope.store.read(()->{
            JsonNode snapshotMetadata=json(found.store.get("metadata"));ObjectNode out=Profiles.JSON.createObjectNode().put("generation",snapshotMetadata.path("generation").asLong()).put("scannedAt",snapshotMetadata.path("finishedAt").asLong()).put("state",found.state).put("stale",clock.getAsLong()>found.nextScan||found.state.equals("stale"));out.set("version",snapshotMetadata.path("version"));
            if(operation.equals("dba_search_objects")){
                String query=args.path("query").asText("").toLowerCase(Locale.ROOT),kind=args.path("kind").asText("");
                String cursorScope=Profiles.JSON.createArrayNode().add(principal).add(id).add(profileRevision(selectedBinding)).add(query).add(kind).add(args.path("searchDefinitions").asBoolean()).toString();
                if(args.has("cursor")&&args.has("offset"))throw new IllegalArgumentException("Use cursor or legacy offset, not both");
                var position=catalogCursors.read(args.path("cursor").asText(""),cursorScope,out.path("generation").asLong());
                int offset=args.has("cursor")?Math.toIntExact(position.seen()):number(args,"offset",0,0,50000),limit=number(args,"limit",30,1,100);int[] matched={0};ArrayNode objects=out.putArray("objects");
                found.store.scan("i/",(key,value)->{JsonNode row=json(value);boolean matches=(kind.isBlank()||kind.equals(row.path("kind").asText()))&&(row.path("name").asText().toLowerCase(Locale.ROOT).contains(query)||row.path("schema").asText().toLowerCase(Locale.ROOT).contains(query));if(!matches&&args.path("searchDefinitions").asBoolean()&&query.length()>=3&&(kind.isBlank()||kind.equals(row.path("kind").asText())))matches=json(found.store.get("o/"+row.path("id").asText())).path("ddl").asText().toLowerCase(Locale.ROOT).contains(query);if(matches){if(matched[0]++>=offset&&objects.size()<limit)objects.add(row);}});
                int next=offset+objects.size();out.put("total",matched[0]).put("nextOffset",next).put("truncated",matched[0]>next);
                out.put("inventoryComplete",snapshotMetadata.path("inventoryComplete").asBoolean(false)).put("coverage",snapshotMetadata.path("coverage").asText("unknown"));
                if(matched[0]>next)out.put("nextCursor",catalogCursors.issue(cursorScope,out.path("generation").asLong(),new io.doindev.codegraph.query.GenerationCursor.Position("",next,position.expiresAt())));
                return out;
            }
            String objectId=Profiles.text(args,"objectId",64);JsonNode object=json(found.store.get("o/"+objectId));if(object==null)throw new IllegalArgumentException("Unknown object in this snapshot");
            if(operation.equals("dba_get_indexed_properties")){String section=args.path("section").asText("columns");if(!Set.of("columns","indexes","primaryKeys","foreignKeys","privileges").contains(section))throw new IllegalArgumentException("Unknown metadata section");int offset=number(args,"offset",0,0,10000),limit=number(args,"limit",50,1,100);JsonNode rows=object.path(section);ArrayNode values=out.putArray("properties");for(int i=offset;i<Math.min(rows.size(),offset+limit);i++)values.add(rows.get(i));out.put("section",section).put("nextOffset",offset+values.size()).put("truncated",offset+values.size()<rows.size());return out;}
            if(operation.equals("dba_get_indexed_ddl")){String ddl=object.path("ddl").asText();int offset=number(args,"offset",0,0,MAX_OFFSET),length=number(args,"length",32000,1,64000);if(offset>ddl.length())throw new IllegalArgumentException("DDL offset exceeds its length");int end=Math.min(ddl.length(),offset+length);out.set("object",json(found.store.get("i/"+objectId)));out.put("ddl",ddl.substring(offset,end)).put("nextOffset",end).put("truncated",end<ddl.length());return out;}
            if(operation.equals("dba_get_database_dependencies")){ArrayNode edges=out.putArray("dependencies");int offset=number(args,"offset",0,0,50000),limit=number(args,"limit",100,1,100);int[] total={0};found.store.scan("e/",(k,v)->{JsonNode e=json(v);if(e.path("schema").equals(object.path("schema"))&&e.path("name").equals(object.path("name"))||e.path("targetSchema").equals(object.path("schema"))&&e.path("target").equals(object.path("name"))){if(total[0]++>=offset&&edges.size()<limit)edges.add(e);}});out.put("truncated",total[0]>offset+edges.size()).put("nextOffset",offset+edges.size());return out;}
            if(operation.equals("dba_find_code_references")){out.set("references",host.references(selectedBinding.path("projectId").asText(),object.path("schema").asText(),object.path("name").asText()));out.put("resolution","Indexed static SQL and ORM evidence; candidates require review. Runtime SQL and naming strategies may remain unresolved.").put("inventoryComplete",false).put("truncated",out.path("references").size()>=100);return out;}
            throw new IllegalArgumentException("Unknown catalog operation");
        });
    }
    private static final int MAX_OFFSET=4<<20;
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
                long generation;synchronized(this){Scope scope=scopes.get(key(current));generation=scope==null?0:scope.generation;}
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
    private synchronized void cleanupScopes(){Set<String> used=new HashSet<>(),live=onboarded();for(JsonNode b:configuration.path("bindings"))if(live.contains(b.path("projectId").asText()))try{used.add(key(b));}catch(IllegalArgumentException ignored){}var iterator=scopes.entrySet().iterator();while(iterator.hasNext()){var e=iterator.next();if(!used.contains(e.getKey())){e.getValue().cancelled=true;if(e.getValue().scanner!=null)e.getValue().scanner.cancel();if(!e.getValue().state.equals("scanning")){e.getValue().store.close();iterator.remove();}}}}
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
    public synchronized void close(){closed=true;timer.shutdownNow();worker.shutdownNow();for(Scope s:scopes.values()){s.cancelled=true;if(s.scanner!=null)s.scanner.cancel();s.store.close();}scopes.clear();}
}
