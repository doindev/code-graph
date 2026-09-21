package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independently owned DBA runtime. No graph, MCP transport or visualization dependencies. */
public final class DbaRuntime implements AutoCloseable {
    private static final int MAX_WORKSPACE_BYTES=16<<20,MAX_SCRIPT_BYTES=1<<20,MAX_SCRIPT_TABS=12;
    private volatile DbaConfig config;
    private final AgentAuthorization authorization;
    private final Profiles profiles;
    private final BrowserAuth auth;
    private final AgentAccess agents;
    private final Connections connections;
    private final QueryJobs jobs;
    private final GridResults grids;
    private final ConnectionSetup setup;
    private final NativeOperations nativeOperations;
    private final ProjectContexts contexts;
    private final ApprovalQueue approvals;
    private final MigrationPlans migrations;
    private final AgentRequests agentRequests;
    private final ApprovalBroker broker;
    private final ApprovalReviewServer reviewServer;
    private final EditorPairings editorPairings;
    private final EditorRequests editorRequests;
    private final boolean uiEnabled;
    private final java.util.concurrent.ScheduledExecutorService contextTimer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("approval-expiry").factory());
    private final Thread shutdownHook;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    public DbaRuntime(DbaConfig config)throws IOException {this(config,Vault.system(),true);}
    public DbaRuntime(DbaConfig config,boolean ui)throws IOException {this(config,Vault.system(),ui);}
    public DbaRuntime(DbaConfig config,Vault vault)throws IOException {this(config,vault,true);}
    public DbaRuntime(DbaConfig config,Vault vault,boolean ui)throws IOException {this(config,vault,ui,new DesktopApprovals());}
    DbaRuntime(DbaConfig config,Vault vault,boolean ui,ApprovalBroker.Desktop desktop)throws IOException {
        this.config=config;this.uiEnabled=ui;
        authorization=new AgentAuthorization(config.yolo(),config.directory());
        if(config.yolo())System.err.println(AgentAuthorization.WARNING);
        if(!config.yolo()&&config.approvalMode().equals("browser")&&!ui)throw new IllegalArgumentException("Browser approval mode requires --viz");
        if(!config.yolo()&&config.approvalMode().equals("desktop")&&!desktop.available())throw new IllegalArgumentException("Desktop approval mode requires a usable graphical session");
        profiles=new Profiles(config.directory(),vault);
        try{agents=new AgentAccess(profiles.directory(),authorization);agents.bindLegacyNames(profiles);auth=ui?new BrowserAuth(profiles.directory()):null;}catch(IOException e){profiles.close();throw e;}
        connections=new Connections(profiles);jobs=new QueryJobs(connections,config,this::ownerAlive);
        grids=new GridResults(jobs,connections,()->this.config,this::ownerAlive);jobs.grids=grids;
        setup=new ConnectionSetup(profiles,jobs,config.driverDownloads());
        contexts=new ProjectContexts(profiles,connections,agents);contexts.accounting(jobs);nativeOperations=new NativeOperations(profiles,jobs,contexts);contexts.nativeCatalogs(nativeOperations);
        migrations=new MigrationPlans();approvals=new ApprovalQueue(contexts,profiles,agents,jobs);approvals.migrations(migrations);agentRequests=new AgentRequests(profiles,connections,setup,contexts,agents,jobs);var approvalCapacity=new java.util.concurrent.Semaphore(32);approvals.capacity(approvalCapacity);agentRequests.capacity(approvalCapacity);approvals.otherCount(agentRequests::count);agentRequests.otherCount(approvals::count);
        authorization.sessions(approvals.reusable.sessions);
        agentRequests.sessions(approvals.reusable.sessions);
        agentRequests.nativeOperations(nativeOperations);
        editorPairings=ui?new EditorPairings(auth,(session,principal)->approvals.reusable.sessions.alive(session,principal)):null;
        editorRequests=ui?new EditorRequests(auth,editorPairings,(session,principal)->agentAlive(principal)&&approvals.reusable.sessions.alive(session,principal),desktop::available,desktop::browse,id->agents.agent(id).path("name").asText(),config.directory()):null;
        broker=new ApprovalBroker(config.yolo()?"none":config.approvalMode(),ui,new ApprovalBroker.Requests(){
            public JsonNode list(){return approvalList();}
            public JsonNode decide(String reviewer,String id,String action,boolean acknowledged,JsonNode options){return editorRequests!=null&&editorRequests.has(id)?editorRequests.decide(id,action):agentRequests.has(id)?agentRequests.decide(reviewer,id,action,acknowledged,options):approvals.decide(reviewer,id,action,acknowledged);}
        },desktop,this::ownerAlive);
        if(editorRequests!=null)editorRequests.configure(approvalCapacity,broker::wake);
        reviewServer=new ApprovalReviewServer(this,broker,config.directory());
        broker.detailed(id->{if(uiEnabled&&browserAddress!=null)return new ApprovalBroker.Handoff(browserAddress.resolve("/dba#approval="+id),"");return reviewServer.open(id);});
        approvals.onPending(broker::published);agentRequests.onPending(broker::published);
        profiles.onAuthorizationChange(id->{approvals.reusable.invalidateConnection(id);nativeOperations.invalidate(id);});contexts.onAuthorizationChange(approvals.reusable::invalidateBinding);
        contextTimer.scheduleWithFixedDelay(()->{try{approvals.tick();agentRequests.tick();nativeOperations.reap();grids.reap();if(editorPairings!=null)editorPairings.reap();if(editorRequests!=null)editorRequests.reap();}catch(Exception ignored){}},1,1,java.util.concurrent.TimeUnit.SECONDS);
        shutdownHook=new Thread(this::close,"dba-shutdown");Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
    private volatile java.net.URI browserAddress;
    public void browserAddress(java.net.URI address){browserAddress=address;broker.browserUri(address);if(editorRequests!=null)editorRequests.address(address);}
    private boolean ownerAlive(String owner){return agents.alive(owner)||(auth!=null&&auth.alive(owner))||(agentRequests!=null&&agentRequests.alive(owner))||(reviewServer!=null&&reviewServer.alive(owner));}
    private ArrayNode approvalList(){ArrayNode all=Profiles.JSON.createArrayNode();approvals.list().forEach(all::add);agentRequests.list().forEach(all::add);if(editorRequests!=null)editorRequests.pending().forEach(all::add);return all;}
    void handleReview(HttpExchange x,BrowserAuth reviewAuth,String scope)throws IOException {handle(x,reviewAuth,scope);}
    public void attachProjectContext(ProjectContextHost host){contexts.attach(host);}
    public void projectQueried(String project){contexts.touchName(project);}
    public static String projectContextId(String root){return ProjectContexts.projectId(root);}
    public static boolean matches(String path){return path.equals("/dba")||path.startsWith("/dba/")||path.equals("/api/dba")||path.startsWith("/api/dba/");}
    public void handle(HttpExchange x)throws IOException {handle(x,auth,null);}
    private void handle(HttpExchange x,BrowserAuth auth,String scope)throws IOException {
        try{
            if(auth==null){json(x,404,Map.of("error","DBA UI is disabled"));return;}
            BrowserAuth.local(x);
            x.getResponseHeaders().set("Cache-Control","no-store");
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            x.getResponseHeaders().set("Referrer-Policy","no-referrer");
            x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(method.equals("GET")&&(path.equals("/dba")||path.startsWith("/dba/"))){if(scope!=null&&!ApprovalReviewServer.assetAllowed(path))throw new SecurityException("Asset is outside this approval");asset(x,path);return;}
            if(scope==null&&path.equals("/api/dba/bootstrap")&&method.equals("POST")){
                body(x);var session=auth.bootstrap(x);
                x.getResponseHeaders().add("Set-Cookie","dba_session="+session.id()+"; Path=/api/dba/; HttpOnly; SameSite=Strict; Max-Age=3600");
                json(x,200,settings().put("csrf",session.csrf()).put("expires",session.expires()));return;
            }
            BrowserAuth.Session session=auth.require(x);
            if(scope!=null)ApprovalReviewServer.requireRoute(path,method,scope);
            if(path.equals("/api/dba/editor/register")&&method.equals("POST")){
                JsonNode b=body(x);String key=auth.registerTab(session.id(),Profiles.text(b,"workspaceId",36),Profiles.text(b,"documentId",36));
                json(x,200,Map.of("workspaceId",key.substring(key.indexOf(':')+1)));return;
            }
            String workspaceOwner=session.id();
            if(path.equals("/api/dba/workspace")||path.startsWith("/api/dba/editor/")){
                String tab=x.getRequestHeaders().getFirst("X-Dba-Workspace"),document=x.getRequestHeaders().getFirst("X-Dba-Document");
                if(path.equals("/api/dba/editor/events")){var q=query(x);tab=q.getOrDefault("workspaceId",tab);document=q.getOrDefault("documentId",document);}
                if(tab!=null||document!=null)workspaceOwner=auth.requireTab(session.id(),tab,document);
                else workspaceOwner=auth.legacyWorkspace(session.id());
            }
            if(path.equals("/api/dba/editor/presence")&&method.equals("POST")){body(x);auth.presence(workspaceOwner);json(x,200,editorRequests.offers(workspaceOwner));return;}
            if(path.equals("/api/dba/editor/leave")&&method.equals("POST")){JsonNode b=body(x,64<<10);try{if(b.has("workspace")){JsonNode state=b.path("workspace");auth.replaceWorkspace(workspaceOwner,state.path("expectedWorkspaceRevision").asLong(-1),validatedWorkspace(state));}}finally{auth.leaveTab(workspaceOwner);}json(x,200,Map.of("left",true));return;}
            if(path.equals("/api/dba/editor/claim")&&method.equals("POST")){JsonNode b=body(x);json(x,200,editorRequests.accept(workspaceOwner,b.path("approvalId").asText(),b.path("ticket").asText()));broker.wake();return;}
            if(path.equals("/api/dba/editor/reject")&&method.equals("POST")){JsonNode b=body(x);json(x,200,editorRequests.reject(workspaceOwner,Profiles.text(b,"approvalId",36)));broker.wake();return;}
            if(path.equals("/api/dba/grids/dispose")&&method.equals("POST")){
                JsonNode ids=body(x,8192).path("ids");
                if(!ids.isArray()||ids.size()>GridResults.MAX_CONTEXTS)throw new IllegalArgumentException("Supply a bounded grid ID list.");
                for(JsonNode id:ids){UUID.fromString(id.asText());grids.release(session.id(),id.asText());}
                json(x,200,Map.of("released",true));return;
            }
            if(path.startsWith("/api/dba/grids/exports/")){
                String id=path.substring("/api/dba/grids/exports/".length());UUID.fromString(id);
                if(method.equals("GET"))grids.exports.download(session.id(),id,x);
                else if(method.equals("DELETE")){grids.exports.remove(session.id(),id);json(x,200,Map.of("released",true));}
                else throw new IllegalArgumentException("Unsupported export method");
                return;
            }
            if(path.startsWith("/api/dba/grids/")){
                String[] parts=path.substring("/api/dba/grids/".length()).split("/",-1);UUID.fromString(parts[0]);
                if(parts.length==1&&method.equals("GET")){json(x,200,grids.status(session.id(),parts[0]));return;}
                if(parts.length==1&&method.equals("DELETE")){grids.release(session.id(),parts[0]);json(x,200,Map.of("released",true));return;}
                if(parts.length==2&&method.equals("POST")){json(x,202,grids.operation(session.id(),parts[0],parts[1],body(x,2<<20)));return;}
                throw new IllegalArgumentException("Unsupported grid endpoint");
            }
            if(path.equals("/api/dba/approvals/presence")&&method.equals("POST")){json(x,200,broker.poll(session.id(),body(x)));return;}
            if(path.equals("/api/dba/approvals/events")&&method.equals("GET")){
                String query=x.getRequestURI().getRawQuery();String tab=query!=null&&query.startsWith("tabId=")?java.net.URLDecoder.decode(query.substring(6),StandardCharsets.UTF_8):"";
                broker.events(x,session.id(),tab);return;
            }
            if(path.equals("/api/dba/catalog")&&method.equals("POST")){
                JsonNode input=body(x);String operation="dba_"+Profiles.text(input,"operation",40);
                if(!Set.of("dba_scan_status","dba_refresh_catalog","dba_search_objects","dba_get_indexed_ddl","dba_get_indexed_properties","dba_get_database_dependencies").contains(operation))throw new IllegalArgumentException("Unsupported catalog operation");
                json(x,200,contexts.standalone("browser:"+session.id(),operation,input,()->{
                    if(!auth.alive(session.id()))throw new SecurityException("Browser session expired");
                    ObjectNode profile=profiles.get(Profiles.text(input,"connectionId",36));
                    if(!profile.path("name").asText().equals(Profiles.text(input,"connectionName",120)))throw new IllegalArgumentException("Exact connection name required");
                    ObjectNode request=WorkflowTargets.explicitCacheTarget(profile,input),target=request.deepCopy();
                    target.put("profileRevision",ProjectContexts.profileRevision(profile));
                    return new WorkflowTargets.Target(profile,target,request,"Authenticated browser");
                }));return;
            }
            if(path.equals("/api/dba/project-context")){
                if(method.equals("GET"))json(x,200,contexts.state());
                else if(method.equals("POST"))json(x,201,contexts.save(body(x)));
                else throw new IllegalArgumentException("Unsupported context method");return;
            }
            if(path.startsWith("/api/dba/project-context/")){
                String id=path.substring("/api/dba/project-context/".length());
                if(id.endsWith("/scan")&&method.equals("POST")){contexts.scanNow(id.substring(0,id.length()-5));json(x,202,Map.of("scheduled",true));}
                else if(method.equals("DELETE")){contexts.remove(id);agents.removeBinding(id);json(x,200,Map.of("removed",true));}
                else throw new IllegalArgumentException("Unsupported binding action");return;
            }
            if(path.equals("/api/dba/approvals")&&method.equals("GET")){ArrayNode result=Profiles.JSON.createArrayNode();for(JsonNode r:approvalList())if(!r.path("type").asText().equals("editor_pairing")&&(scope==null||r.path("id").asText().equals(scope))){ObjectNode item=broker.decorate(r);item.put("reviewRevision",ApprovalBroker.revision(r));result.add(item);}json(x,200,result);return;}
            if(path.startsWith("/api/dba/approvals/")){
                String tail=path.substring("/api/dba/approvals/".length());String[] parts=tail.split("/",-1);String id=parts[0];UUID.fromString(id);
                if(parts.length>2)throw new IllegalArgumentException("Unknown approval action");
                String operation=parts.length==2?parts[1]:"",tab=x.getRequestHeaders().getFirst("X-Dba-Tab"),lease=x.getRequestHeaders().getFirst("X-Dba-Review");
                if(operation.equals("claim")&&method.equals("POST")){body(x);json(x,200,broker.claim(session.id(),tab,id));return;}
                if(operation.equals("renew")&&method.equals("POST")){body(x);json(x,200,broker.renew(session.id(),tab,id,lease));return;}
                if(operation.equals("release")&&method.equals("POST")){body(x);broker.release(session.id(),tab,id,lease);json(x,200,Map.of("released",true));return;}
                broker.requireLease(session.id(),tab,id,lease);
                if(operation.equals("draft")&&method.equals("GET")){json(x,200,broker.withLease(session.id(),tab,id,lease,()->agentRequests.reviewDraft(session.id(),id)));return;}
                if(operation.equals("draft")&&method.equals("PUT")){JsonNode draft=body(x);json(x,200,broker.withLease(session.id(),tab,id,lease,()->agentRequests.reviseDraft(session.id(),id,draft)));broker.release(session.id(),tab,id,lease);broker.wake();return;}
                if(operation.equals("test-draft")&&method.equals("POST")){JsonNode draft=body(x);json(x,202,broker.withLease(session.id(),tab,id,lease,()->agentRequests.testEditedDraft(session.id(),id,draft)));return;}
                if(operation.equals("test")&&method.equals("POST")){body(x);json(x,202,broker.withLease(session.id(),tab,id,lease,()->agentRequests.testDraft(session.id(),id)));return;}
                if(operation.isEmpty()&&method.equals("POST")){JsonNode b=body(x);String action=b.has("action")?Profiles.text(b,"action",40):b.path("approved").asBoolean()?"approve_once":"reject";json(x,200,broker.decide(session.id(),tab,id,lease,action,b.path("acknowledged").asBoolean(),b));return;}
                throw new IllegalArgumentException("Unsupported approval operation");
            }
            if(path.startsWith("/api/dba/agents/")&&path.endsWith("/context")&&method.equals("PUT")){String id=path.substring("/api/dba/agents/".length(),path.length()-"/context".length());json(x,200,agents.contextGrants(id,body(x).path("grants"),contexts));return;}
            if(path.startsWith("/api/dba/agents/")&&path.endsWith("/permissions")&&method.equals("GET")){String id=path.substring("/api/dba/agents/".length(),path.length()-"/permissions".length());json(x,200,permissions(id));return;}
            if(path.startsWith("/api/dba/agents/")&&path.contains("/policies/")&&Set.of("DELETE","PATCH").contains(method)){String tail=path.substring("/api/dba/agents/".length());String[] parts=tail.split("/policies/",2);if(approvals.reusable.contains(parts[0],parts[1])){Boolean enabled=null;if(method.equals("PATCH")){JsonNode b=body(x);if(b.size()!=1||!b.path("enabled").isBoolean())throw new IllegalArgumentException("Expected enabled boolean only");enabled=b.path("enabled").asBoolean();}json(x,200,approvals.reusable.change(parts[0],parts[1],enabled));}else{if(!method.equals("DELETE"))throw new IllegalArgumentException("Legacy permissions support revocation only");json(x,200,agents.removePolicy(parts[0],parts[1]));}return;}

            if(path.equals("/api/dba/drivers/import")&&method.equals("POST")){
                if(!"application/java-archive".equals(x.getRequestHeaders().getFirst("Content-Type")))throw new IllegalArgumentException("JAR content type required; key uploads are not supported");
                Path temp=Files.createTempFile(setup.bundles.root(),".upload-",".part"),destination=null;
                try{
                    try(OutputStream out=Files.newOutputStream(temp)){byte[] buffer=new byte[65536];long total=0;int n;while((n=x.getRequestBody().read(buffer))!=-1){total+=n;if(total>128L<<20)throw new IllegalArgumentException("JAR upload exceeds 128 MiB");out.write(buffer,0,n);}}
                    try(var jar=new java.util.jar.JarFile(temp.toFile())){if(jar.getManifest()==null)throw new IllegalArgumentException("A valid JAR manifest is required");}
                    destination=setup.bundles.root().resolve("manual-"+UUID.randomUUID()+".jar");Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);json(x,201,Map.of("path",destination.toString()));
                }finally{Files.deleteIfExists(temp);}return;
            }
            if(path.equals("/api/dba/templates")&&method.equals("GET")){json(x,200,DatabaseCatalog.json());return;}
            if(path.startsWith("/api/dba/setup/")&&method.equals("POST")){json(x,202,setup.operation(session.id(),path.substring("/api/dba/setup/".length()),body(x)));return;}
            if(path.equals("/api/dba/session")&&method.equals("GET")){json(x,200,settings().put("csrf",session.csrf()));return;}
            if(path.equals("/api/dba/logout")&&method.equals("POST")){jobs.cancelOwner(session.id());nativeOperations.forgetOwner(session.id());broker.forgetSession(session.id());auth.logout(session.id());json(x,200,Map.of("ok",true));return;}
            if(path.equals("/api/dba/workspace")){
                if(method.equals("GET")){
                    BrowserAuth.WorkspaceState current=auth.workspaceState(workspaceOwner);ObjectNode state=(ObjectNode)Profiles.JSON.readTree(current.state());state.put("workspaceRevision",current.revision());json(x,200,state);
                }
                else if(method.equals("PUT")){
                    JsonNode supplied=body(x,MAX_WORKSPACE_BYTES);byte[] state=workspace(supplied);long revision;
                    if(supplied.has("expectedWorkspaceRevision")){
                        if(!supplied.path("expectedWorkspaceRevision").isIntegralNumber())throw new IllegalArgumentException("expectedWorkspaceRevision must be an integer");
                        revision=auth.replaceWorkspace(workspaceOwner,supplied.path("expectedWorkspaceRevision").asLong(-1),state);
                    }else{auth.workspace(workspaceOwner,state);revision=auth.workspaceState(workspaceOwner).revision();}
                    Arrays.fill(state,(byte)0);json(x,200,Map.of("saved",true,"workspaceRevision",revision));
                }
                else throw new IllegalArgumentException("Unsupported workspace method");return;
            }
            if(path.equals("/api/dba/editor/pair")){
                if(method.equals("POST")){body(x);json(x,200,editorPairings.create(workspaceOwner));}
                else if(method.equals("GET"))json(x,200,editorPairings.status(workspaceOwner));
                else if(method.equals("DELETE")){body(x);json(x,200,editorPairings.revokeBrowser(workspaceOwner));}
                else throw new IllegalArgumentException("Unsupported editor pairing method");return;
            }
            if(path.equals("/api/dba/editor/ack")&&method.equals("POST")){json(x,200,editorPairings.acknowledge(workspaceOwner,body(x).path("eventId").asLong()));return;}
            if(path.equals("/api/dba/editor/events")&&method.equals("GET")){
                long after=0;String header=x.getRequestHeaders().getFirst("Last-Event-ID");if(header!=null&&!header.isBlank())try{after=Long.parseLong(header);}catch(NumberFormatException ignored){}
                if(workspaceOwner.contains(":"))auth.presence(workspaceOwner);
                ArrayNode events=editorPairings.events(workspaceOwner,after);ObjectNode offers=editorRequests.offers(workspaceOwner);
                x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");x.sendResponseHeaders(200,0);
                try(var writer=new OutputStreamWriter(x.getResponseBody(),StandardCharsets.UTF_8)){
                    writer.write("retry: 3000\nevent: collaboration\ndata: "+offers+"\n\n");
                    for(JsonNode event:events)writer.write("id: "+event.path("eventId").asLong()+"\nevent: editor\ndata: "+event+"\n\n");
                }return;
            }
            if(path.equals("/api/dba/settings")){
                if(method.equals("PUT")){JsonNode b=body(x);if(b.has("yolo")||b.has("approvalMode")||b.has("effectiveApprovalBehavior"))throw new IllegalArgumentException("Authorization mode is startup-only");DbaConfig next=new DbaConfig(config.directory(),b.has("memory")?DbaConfig.budget(b.path("memory").asText()):config.memoryBytes(),b.path("concurrency").asInt(config.concurrency()),b.path("uiRows").asInt(config.uiRows()),b.path("agentRows").asInt(config.agentRows()),b.path("timeoutSeconds").asInt(config.timeoutSeconds()),b.path("decisionTimeoutSeconds").asInt(config.decisionTimeoutSeconds()),config.approvalMode(),config.yolo());jobs.configure(next);config=next;}
                else if(!method.equals("GET"))throw new IllegalArgumentException("Unsupported settings method");
                json(x,200,settings());return;
            }
            if(path.equals("/api/dba/settings/driver-downloads")){
                if(method.equals("GET"))json(x,200,setup.bundles.settings.json());
                else if(method.equals("PUT"))json(x,200,setup.bundles.settings.save(body(x)));
                else throw new IllegalArgumentException("Unsupported driver settings method");
                return;
            }
            if(path.equals("/api/dba/agents")){
                if(method.equals("GET"))json(x,200,agents.list());
                else if(method.equals("POST"))json(x,201,agents.create(body(x),profiles));
                else throw new IllegalArgumentException("Unsupported agent method");return;
            }
            if(path.startsWith("/api/dba/agents/")&&method.equals("DELETE")){String id=path.substring("/api/dba/agents/".length());agents.remove(id);jobs.cancelOwner("agent:"+id);json(x,200,Map.of("revoked",true));return;}
            if(path.equals("/api/dba/connections")){
                if(method.equals("GET")){ArrayNode list=profiles.publicList();for(JsonNode p:list)((ObjectNode)p).set("connectionState",jobs.connectionState(p.path("id").asText()));json(x,200,list);}
                else if(method.equals("POST"))json(x,201,setup.save(session.id(),null,body(x)));
                else throw new IllegalArgumentException("Unsupported connections method");return;
            }
            if(path.equals("/api/dba/connections/order")&&method.equals("PUT")){json(x,200,profiles.reorder(body(x)));return;}
            if(path.startsWith("/api/dba/connections/")&&!path.equals("/api/dba/connections/test")){
                String id=path.substring("/api/dba/connections/".length());
                if(id.contains("/")){String[] parts=id.split("/",-1);if(parts.length!=2)throw new IllegalArgumentException("Unknown connection operation");id=parts[0];UUID.fromString(id);profiles.get(id);
                    if(parts[1].equals("state")&&method.equals("GET")){json(x,200,jobs.connectionState(id));return;}
                    if(parts[1].equals("appearance")&&method.equals("PUT")){json(x,200,profiles.appearance(id,body(x)));return;}
                    if(parts[1].equals("rename")&&method.equals("PUT")){if(jobs.activeConnection(id))throw new IllegalArgumentException("Wait for active connection jobs before renaming");json(x,200,profiles.rename(id,body(x)));return;}
                    if(method.equals("POST")&&Set.of("connect","reconnect","disconnect").contains(parts[1])){body(x);json(x,parts[1].equals("disconnect")?200:202,jobs.connectionAction(session.id(),id,parts[1]));return;}
                    throw new IllegalArgumentException("Unsupported connection operation");
                }
                UUID.fromString(id);
                if(jobs.activeConnection(id)&&!method.equals("GET"))throw new IllegalArgumentException("Connection has active jobs; cancel them before editing/removing");
                if(method.equals("DELETE")){connections.remove(id);contexts.removeConnection(id);agents.removeConnection(id);profiles.remove(id);json(x,200,Map.of("removed",true));}
                else if(method.equals("PUT")){ObjectNode result=setup.save(session.id(),id,body(x));connections.remove(id);json(x,200,result);}
                else if(method.equals("GET"))json(x,200,Profiles.publicProfile(profiles.get(id)));
                else throw new IllegalArgumentException("Unsupported connection method");return;
            }
            if(path.equals("/api/dba/connections/test")&&method.equals("POST")){JsonNode b=body(x);String id=Profiles.text(b,"connectionId",36);json(x,202,DatabaseTransport.of(profiles.get(id))==DatabaseTransport.JDBC?jobs.test(session.id(),id):setup.operation(session.id(),"draft-test",b));return;}
            if(path.equals("/api/dba/native/execute")&&method.equals("POST")){JsonNode b=body(x);ObjectNode review=nativeOperations.prepare(b);if(review.path("mutation").asBoolean())throw new IllegalArgumentException("Native mutations require Prepare, explicit browser review and Apply");json(x,202,nativeOperations.submit(session.id(),review,b,()->{}));return;}
            if(path.equals("/api/dba/native/tree")&&method.equals("POST")){json(x,202,nativeOperations.tree(session.id(),body(x)));return;} if(path.equals("/api/dba/native/prepare")&&method.equals("POST")){json(x,200,nativeOperations.prepareBrowser(session.id(),body(x)));return;}
            if(path.equals("/api/dba/native/apply")&&method.equals("POST")){json(x,202,nativeOperations.applyBrowser(session.id(),Profiles.text(body(x),"planId",36)));return;}
            if(path.startsWith("/api/dba/native/reviews/")&&method.equals("DELETE")){nativeOperations.discardBrowser(session.id(),path.substring("/api/dba/native/reviews/".length()));json(x,200,Map.of("released",true));return;}
            if(path.equals("/api/dba/metadata/node")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.metadata(session.id(),Profiles.text(b,"connectionId",36),optional(b,"schema"),optional(b,"table")));return;}
            if(path.equals("/api/dba/metadata/tree")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.metadataTree(session.id(),Profiles.text(b,"connectionId",36),b));return;}
            if(path.equals("/api/dba/query-builder/compile")&&method.equals("POST")){JsonNode b=body(x,2<<20);profiles.get(Profiles.text(b,"connectionId",36));json(x,200,VisualQuery.compile(b));return;}
            if(path.equals("/api/dba/query-builder/functions")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.functions(session.id(),Profiles.text(b,"connectionId",36),b));return;}
            if(path.equals("/api/dba/query-builder/source")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.queryBuilder(session.id(),Profiles.text(b,"connectionId",36),b,true));return;}
            if(path.equals("/api/dba/query-builder/import")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.queryBuilder(session.id(),Profiles.text(b,"connectionId",36),b,false));return;}
            if(path.equals("/api/dba/metadata/table-query")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.tablePreparation(session.id(),Profiles.text(b,"connectionId",36),b));return;}
            if(Set.of("/api/dba/table-properties/load","/api/dba/table-properties/prepare").contains(path)&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.tableProperties(session.id(),Profiles.text(b,"connectionId",36),b,path.endsWith("/prepare")));return;}
            if(path.equals("/api/dba/table-properties/new")&&method.equals("POST")){ObjectNode b=(ObjectNode)body(x);b.put("creation",true);json(x,202,jobs.tableProperties(session.id(),Profiles.text(b,"connectionId",36),b,false));return;}
            if(path.equals("/api/dba/table-properties/apply")&&method.equals("POST")){json(x,202,jobs.applyTableProperties(session.id(),body(x)));return;}
            if(Set.of("/api/dba/object-properties/load","/api/dba/object-properties/prepare").contains(path)&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.objectProperties(session.id(),Profiles.text(b,"connectionId",36),b,path.endsWith("/prepare")));return;}
            if(path.equals("/api/dba/object-properties/apply")&&method.equals("POST")){json(x,202,jobs.applyObjectProperties(session.id(),body(x)));return;}
            if(path.equals("/api/dba/objects/prepare")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.prepareCreation(session.id(),Profiles.text(b,"connectionId",36),b));return;}
            if(path.equals("/api/dba/objects/apply")&&method.equals("POST")){json(x,202,jobs.applyCreation(session.id(),body(x)));return;}
            if(path.equals("/api/dba/table-properties/category")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.tablePropertyDetails(session.id(),Profiles.text(b,"connectionId",36),b));return;}
            if(Set.of("/api/dba/metadata/object","/api/dba/metadata/object/action").contains(path)&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.metadataObject(session.id(),Profiles.text(b,"connectionId",36),b,path.endsWith("/action")));return;}
            if(path.equals("/api/dba/query/execute")&&method.equals("POST")){JsonNode b=body(x);if(b.has("database")&&!b.path("database").isTextual())throw new IllegalArgumentException("database must be text");if(b.has("autoCommit")&&!b.path("autoCommit").isBoolean())throw new IllegalArgumentException("autoCommit must be boolean");json(x,202,b.has("database")?jobs.tableQuery(session.id(),Profiles.text(b,"connectionId",36),Profiles.text(b,"sql",16384),b.path("parameters"),b.path("database").asText(),b.get("rowLimit")):jobs.humanQuery(session.id(),Profiles.text(b,"connectionId",36),Profiles.text(b,"sql",16384),b.has("parameters")?b.get("parameters"):Profiles.JSON.createArrayNode(),b.path("autoCommit").asBoolean(false),b.get("rowLimit")));return;}
            if(path.equals("/api/dba/query/grid-edit")&&method.equals("POST")){JsonNode b=body(x);profiles.get(Profiles.text(b,"connectionId",36));json(x,200,GridSql.prepare(b));return;}
            if(path.equals("/api/dba/query/explain")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.browserExplain(session.id(),Profiles.text(b,"connectionId",36),Profiles.text(b,"sql",16384),b.has("parameters")?b.get("parameters"):Profiles.JSON.createArrayNode(),b.path("database").asText("")));return;}
            if(path.equals("/api/dba/metadata/ddl")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.ddl(session.id(),Profiles.text(b,"connectionId",36),Profiles.text(b,"schema",128),Profiles.text(b,"object",128)));return;}
            if(path.startsWith("/api/dba/jobs/")){
                String id=path.substring("/api/dba/jobs/".length());
                if(id.endsWith("/cancel")&&method.equals("POST")){id=id.substring(0,id.length()-7);jobs.cancel(jobs.require(session.id(),id));json(x,200,Map.of("cancellationRequested",true));}
                else if(id.endsWith("/decision")&&method.equals("POST")){id=id.substring(0,id.length()-9);JsonNode b=body(x);jobs.decision(session.id(),id,Profiles.text(b,"decisionId",36),Profiles.text(b,"action",32));json(x,200,Map.of("accepted",true));}
                else if(method.equals("GET"))json(x,200,jobs.status(session.id(),id));
                else if(method.equals("DELETE")){jobs.remove(session.id(),id);json(x,200,Map.of("released",true));}
                else throw new IllegalArgumentException("Unsupported job method");return;
            }
            json(x,404,Map.of("error","Unknown DBA endpoint"));
        }catch(SecurityException e){json(x,403,Map.of("error",e.getMessage()));}
        catch(IllegalArgumentException e){json(x,400,Map.of("error",e.getMessage()==null?"Invalid request":e.getMessage()));}
        catch(Exception e){json(x,500,Map.of("error","DBA operation failed; check local configuration and vault availability"));}
        finally{x.close();}
    }
    private static String optional(JsonNode n,String key){return n.hasNonNull(key)?n.get(key).asText():null;}
    private ObjectNode settings(){ObjectNode n=jobs.telemetry().put("approvalMode",config.approvalMode()).put("approvalsEnabled",approvalsEnabled()).put("uiRows",config.uiRows()).put("agentRows",Math.min(100,config.agentRows())).put("timeoutSeconds",config.timeoutSeconds()).put("decisionTimeoutSeconds",config.decisionTimeoutSeconds()).put("pools",connections.count()).put("writeExecutionEnabled",auth!=null).put("agentWriteExecutionEnabled",approvalsEnabled()).put("agentToolsEnabled",true).put("milestone","statement-aware-human-sql");n.set("grids",grids.telemetry());n.set("nativeClients",nativeOperations.telemetry());return authorization.describe(n).put("reviewAvailable",broker.enabled());}
    public String authenticateAgent(String token){return agents.authenticate(token);}
    /** Only loopback-validated HTTP and local stdio transports may establish this identity. */
    public String trustedLocalAgent(){return agents.trustedLocal();}
    public boolean agentAlive(String id){return id!=null&&agents.alive("agent:"+id);}
    public boolean approvalsEnabled(){return authorization.automatic||broker.enabled();}
    private void requireApprovalAvailable(){if(!authorization.automatic)broker.requireAvailable();}
    public boolean editorPairingEnabled(){return editorPairings!=null;}
    private ObjectNode permissions(String principal){ObjectNode out=agents.permissions(principal,contexts);out.set("reusablePolicies",approvals.reusable.list(principal));return authorization.describe(out);}
    /** Only trusted transports register the logical SDK session, never tool arguments. */
    public void registerMcpSession(String id,String principal,long expires){if(!agentAlive(principal))throw new SecurityException("Agent revoked");approvals.reusable.sessions.register(id,principal,expires);}
    public void endMcpSession(String id){if(editorPairings!=null)editorPairings.sessionEnded(id);approvals.sessionEnded(id);agentRequests.sessionEnded(id);migrations.sessionEnded(id);}
    /** Called only with a principal established by the transport, never with an ID from tool arguments. */
    public JsonNode agentCall(String principal,String operation,JsonNode args){
        return agentCall(principal,null,operation,args);
    }
    private final TemplateDiscovery templateDiscovery = new TemplateDiscovery();
    private final WorkspaceDiscovery workspaceDiscovery = new WorkspaceDiscovery();
    public JsonNode agentCall(String principal,String mcpSession,String operation,JsonNode args){
        if(!agentAlive(principal))throw new SecurityException("Agent revoked");
        if(args==null||!args.isObject()||args.toString().length()>65_536)throw new IllegalArgumentException("Bounded JSON object required");
        authorization.requireSession(principal,mcpSession);
        boolean editorOperation=Set.of("dba_request_editor_access","dba_pair_editor","dba_list_editor_documents","dba_get_editor_document","dba_create_editor_draft","dba_apply_editor_edit").contains(operation)
            ||Set.of("dba_request_status","dba_cancel_request").contains(operation)&&editorRequests!=null&&editorRequests.has(args.path("approvalId").asText(args.path("requestId").asText()));
        if(!editorOperation)authorization.audit(principal,mcpSession,operation,args);
        JsonNode result=agentCallInternal(principal,mcpSession,operation,args);
        if(authorization.automatic&&!editorOperation&&result instanceof ObjectNode out){AgentAuthorization.approved(out);authorization.describe(out);}
        return result;
    }
    private JsonNode agentCallInternal(String principal,String mcpSession,String operation,JsonNode args){
        if(!agentAlive(principal))throw new SecurityException("DBA agent authentication required");
        if(args==null||!args.isObject()||args.toString().length()>65_536)throw new IllegalArgumentException("Bounded JSON object required");
        String owner="agent:"+principal;
        if(operation.equals("dba_list_templates"))return templateDiscovery.list(args);
        if(operation.equals("dba_request_editor_access")){if(editorRequests==null)throw new SecurityException("DBA UI is disabled; browser collaboration is unavailable");return editorRequests.request(principal,mcpSession,args);}
        if(operation.equals("dba_pair_editor")){if(editorPairings==null)throw new SecurityException("DBA browser workspace is unavailable");return editorPairings.pair(principal,mcpSession,Profiles.text(args,"pairingCode",32));}
        if(operation.equals("dba_list_editor_documents")){if(editorPairings==null)throw new SecurityException("DBA browser workspace is unavailable");return editorPairings.documents(principal,mcpSession);}
        if(operation.equals("dba_get_editor_document")){if(editorPairings==null)throw new SecurityException("DBA browser workspace is unavailable");return editorPairings.document(principal,mcpSession,Profiles.text(args,"documentId",36));}
        if(operation.equals("dba_create_editor_draft")){if(editorPairings==null)throw new SecurityException("DBA browser workspace is unavailable");return editorPairings.createDraft(principal,mcpSession,args);}
        if(operation.equals("dba_apply_editor_edit")){if(editorPairings==null)throw new SecurityException("DBA browser workspace is unavailable");return editorPairings.edit(principal,mcpSession,args);}
        if(operation.equals("dba_get_capabilities"))return agentCapabilities(principal,mcpSession,args);
        if(operation.equals("dba_capture_schema")){
            var resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);
            var target=resolver.catalog(principal,mcpSession,args);
            Runnable recheck=()->{if(!target.scope().equals(resolver.catalog(principal,mcpSession,target.request()).scope()))throw new IllegalArgumentException("Schema target changed before capture; submit a fresh request");};
            return DatabaseTransport.of(target.profile())==DatabaseTransport.JDBC?jobs.captureSchema(owner,target,recheck):nativeOperations.captureSchema(owner,target,recheck);
        }
        if(operation.equals("dba_compare_schemas")){
            var left=jobs.require(owner,Profiles.text(args,"leftSnapshotId",36));var right=jobs.require(owner,Profiles.text(args,"rightSnapshotId",36));
            int limit=ProjectContexts.number(args,"limit",50,1,100);
            var resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);
            Runnable authorize=()->{for(var job:List.of(left,right)){
                if(!job.state.equals("complete")||job.schemaScope==null||job.result==null)throw new IllegalArgumentException("Use completed, retained dba_capture_schema job IDs");
                if(!job.schemaScope.equals(resolver.catalog(principal,mcpSession,job.schemaRequest).scope()))throw new IllegalArgumentException("Snapshot target changed; capture again before comparing");
            }};
            authorize.run();Runnable release=jobs.retainResults(owner,List.of(left.id,right.id));
            try{return jobs.local(owner,job->{authorize.run();var result=SchemaSnapshots.compare(left.result,right.result,limit);authorize.run();return result;},release);}
            catch(RuntimeException rejected){release.run();throw rejected;}
        }
        if(operation.equals("dba_prepare_migration")){
            String snapshotId=Profiles.text(args,"snapshotId",36);QueryJobs.Job snapshot=jobs.require(owner,snapshotId);
            if(snapshot.schemaRequest==null)throw new IllegalArgumentException("Use a completed dba_capture_schema job");
            WorkflowTargets resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);
            WorkflowTargets.Target target=resolver.catalog(principal,mcpSession,snapshot.schemaRequest);
            if(!target.scope().equals(snapshot.schemaScope))throw new IllegalArgumentException("Snapshot target changed; capture again before planning");
            Runnable release=jobs.retainResults(owner,List.of(snapshotId));
            try{return migrations.bindSession(owner,migrations.prepare(owner,snapshot,args,release),authorization.automatic?mcpSession:null);}catch(RuntimeException failure){release.run();throw failure;}
        }
        if(operation.equals("dba_validate_migration")){
            MigrationPlans.Plan plan=migrations.requireSession(owner,Profiles.text(args,"planId",36),mcpSession);
            WorkflowTargets resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);
            WorkflowTargets.Target target=resolver.catalog(principal,mcpSession,plan.sourceRequest);
            if(!target.scope().equals(plan.sourceScope))throw new IllegalArgumentException("Migration target changed; prepare a new plan");
            return jobs.validateMigration(owner,target,plan,()->{
                WorkflowTargets.Target current=resolver.catalog(principal,mcpSession,plan.sourceRequest);
                if(!current.scope().equals(plan.sourceScope))throw new IllegalArgumentException("Migration target changed during validation");
            });
        }
        if(operation.equals("dba_prepare_migration_rehearsal")){
            MigrationPlans.Plan source=migrations.requireSession(owner,Profiles.text(args,"planId",36),mcpSession);
            QueryJobs.Job snapshot=jobs.require(owner,Profiles.text(args,"rehearsalSnapshotId",36));
            if(!snapshot.state.equals("complete")||snapshot.schemaRequest==null||snapshot.schemaScope==null)throw new IllegalArgumentException("Use a completed retained dba_capture_schema job for the rehearsal target");
            WorkflowTargets resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);WorkflowTargets.Target target=resolver.catalog(principal,mcpSession,snapshot.schemaRequest);
            if(!target.scope().equals(snapshot.schemaScope))throw new IllegalArgumentException("Rehearsal target changed; capture it again");
            boolean sameScope=target.scope().equals(source.sourceScope);String sourceUrl=sourceUrl(profiles.get(source.sourceScope.path("connectionId").asText())),targetUrl=sourceUrl(target.profile());
            boolean sameAlias=sourceUrl.equals(targetUrl)&&source.sourceScope.path("database").asText().equals(target.scope().path("database").asText())&&source.sourceScope.path("schema").asText().equals(target.scope().path("schema").asText());
            if(sameScope||sameAlias)throw new IllegalArgumentException("Rehearsal target must be a distinct disposable database/schema and cannot alias the source target");
            Runnable release=jobs.retainResults(owner,List.of(snapshot.id));try{return migrations.bindSession(owner,migrations.prepareRehearsal(owner,source,snapshot,args,release),authorization.automatic?mcpSession:null);}catch(RuntimeException failure){release.run();throw failure;}
        }
        if(operation.equals("dba_request_apply_migration")){
            requireApprovalAvailable();JsonNode duplicate=approvals.existingMigration(principal,mcpSession,args);if(duplicate!=null)return broker.decorate(duplicate);MigrationPlans.Plan plan=migrations.requireSession(owner,Profiles.text(args,"planId",36),mcpSession);
            WorkflowTargets.Target current=new WorkflowTargets(profiles,agents,contexts,approvals.reusable).catalog(principal,mcpSession,plan.sourceRequest);
            if(!current.scope().equals(plan.sourceScope))throw new IllegalArgumentException("Migration target changed; prepare a new plan");
            return broker.decorate(approvals.migration(principal,mcpSession,args,plan));
        }
        if(operation.equals("validate_database_contracts")){
            QueryJobs.Job snapshot=jobs.require(owner,Profiles.text(args,"snapshotId",36));
            if(!snapshot.state.equals("complete")||snapshot.schemaRequest==null||snapshot.schemaScope==null)throw new IllegalArgumentException("Use a completed retained dba_capture_schema job");
            WorkflowTargets resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);
            Runnable authorize=()->{WorkflowTargets.Target current=resolver.catalog(principal,mcpSession,snapshot.schemaRequest);if(!current.scope().equals(snapshot.schemaScope))throw new IllegalArgumentException("Snapshot target changed; capture again");};
            authorize.run();String project=snapshot.schemaScope.path("projectId").asText(args.path("projectId").asText());
            if(project.isBlank())throw new IllegalArgumentException("Standalone schema validation requires an explicit onboarded projectId");
            if(snapshot.schemaScope.has("projectId")&&args.has("projectId")&&!project.equals(args.path("projectId").asText()))throw new IllegalArgumentException("projectId cannot override the snapshot binding");
            JsonNode mappings=contexts.mappings(project);int limit=ProjectContexts.number(args,"limit",50,1,100);Runnable release=jobs.retainResults(owner,List.of(snapshot.id));
            try{return jobs.local(owner,job->{authorize.run();ObjectNode result=ContractValidation.validate(snapshot.result,mappings,limit);result.put("projectId",project);authorize.run();return result;},release);}catch(RuntimeException failure){release.run();throw failure;}
        }
        if(operation.equals("compare_query_plans")){
            QueryJobs.Job left=jobs.require(owner,Profiles.text(args,"leftPlanId",36)),right=jobs.require(owner,Profiles.text(args,"rightPlanId",36));int limit=ProjectContexts.number(args,"limit",50,1,100);
            WorkflowTargets resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);Runnable authorize=()->{for(QueryJobs.Job job:List.of(left,right)){if(!job.state.equals("complete")||job.planRequest==null||job.planScope==null||job.result==null)throw new IllegalArgumentException("Use completed retained estimated-plan job IDs");if(!resolver.catalog(principal,mcpSession,job.planRequest).scope().equals(job.planScope))throw new IllegalArgumentException("Plan target changed; collect the plan again");}};
            authorize.run();Runnable release=jobs.retainResults(owner,List.of(left.id,right.id));
            try{return jobs.local(owner,job->{authorize.run();ObjectNode result=QueryPlanAnalysis.compare(left.id,left.result,right.id,right.result,limit);authorize.run();return result;},release);}catch(RuntimeException failure){release.run();throw failure;}
        }
        if(operation.equals("get_workspace_context"))return workspaceDiscovery.page(principal,args,contexts.projects(),
                agentCall(principal,mcpSession,"dba_list_connections",Profiles.JSON.createObjectNode()),
                contexts.agent(principal,"dba_list_project_databases",Profiles.JSON.createObjectNode()));
        if(operation.equals("dba_request_live_sql")){requireApprovalAvailable();return broker.decorate(approvals.request(principal,mcpSession,args));}
        if(operation.equals("dba_cancel_live_request"))return approvals.cancel(principal,Profiles.text(args,"approvalId",36));
        if(operation.equals("dba_live_request_status"))return broker.decorate(approvals.get(principal,Profiles.text(args,"approvalId",36)));
        if(operation.equals("dba_get_my_permissions"))return permissions(principal);
        if(operation.equals("dba_list_my_created_connections")){ArrayNode out=Profiles.JSON.createArrayNode();for(JsonNode p:profiles.list())if(p.path("agentProvenance").path("agentId").asText().equals(principal)){ObjectNode row=Profiles.JSON.createObjectNode().put("id",p.path("id").asText()).put("name",p.path("name").asText()).put("requestId",p.path("agentProvenance").path("requestId").asText()).put("createdAt",p.path("agentProvenance").path("createdAt").asLong()).put("creatorReceivesAccess",false);row.set("bindings",contexts.bindingsForConnection(p.path("id").asText()));out.add(row);}return out;}
        if(operation.equals("dba_request_native_command")){requireApprovalAvailable();return broker.decorate(agentRequests.request(principal,mcpSession,"native_command",args));}
        if(operation.equals("dba_request_status")){String id=ApprovalIds.resolve(args);if(editorRequests!=null&&editorRequests.has(id))return editorRequests.status(principal,mcpSession,id);return broker.decorate(agentRequests.has(id)?agentRequests.get(principal,id):approvals.get(principal,id));}
        if(operation.equals("dba_cancel_request")){String id=ApprovalIds.resolve(args);if(editorRequests!=null&&editorRequests.has(id))return editorRequests.cancel(principal,mcpSession,id);return agentRequests.has(id)?agentRequests.cancel(principal,id):approvals.cancel(principal,id);}
        if(operation.equals("dba_get_connection_details")){requireApprovalAvailable();JsonNode result=agentRequests.maybeRead(principal,mcpSession,"connection_details",args);return result.has("state")?broker.decorate(result):result;}
        if(operation.equals("dba_request_connection_test")){requireApprovalAvailable();return broker.decorate(agentRequests.maybeRead(principal,mcpSession,"connection_test",args));}
        if(operation.startsWith("dba_request_connection_")||operation.startsWith("dba_request_binding_")){requireApprovalAvailable();String type=operation.substring("dba_request_".length());return broker.decorate(agentRequests.request(principal,mcpSession,type,args));}
        if(Set.of("dba_list_project_databases","dba_search_objects","dba_get_indexed_ddl","dba_get_indexed_properties","dba_get_database_dependencies","dba_find_code_references","dba_scan_status","dba_refresh_catalog").contains(operation)){
            if(!operation.equals("dba_list_project_databases")&&!args.has("bindingId")){
                WorkflowTargets resolver=new WorkflowTargets(profiles,agents,contexts,approvals.reusable);
                var firstCheck=new java.util.concurrent.atomic.AtomicBoolean(true);
                return contexts.standalone(principal,operation,args,()->{
                    if(!agentAlive(principal)||mcpSession!=null&&!approvals.reusable.sessions.alive(mcpSession,principal))throw new SecurityException("Agent/session expired");
                    return resolver.cached(principal,mcpSession,operation,args,firstCheck.getAndSet(false));
                });
            }
            if(args.has("bindingId"))for(String field:List.of("connectionId","connectionName","database","schema"))
                if(args.has(field))throw new IllegalArgumentException("A binding fixes the exact target; do not supply standalone overrides");
            var matched=new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
            java.util.function.Consumer<ObjectNode> check=b->{
                ObjectNode scope=ApprovalScope.resolve(profiles.get(b.path("connectionId").asText()),b,Profiles.JSON.createObjectNode());
                ObjectNode request=Profiles.JSON.createObjectNode().put("sql",operation).put("autoCommit",false);request.set("parameters",args);
                var op=new ReusableOperation.Result("ddl_inspection",true,true,"Cached metadata read");
                ObjectNode p=approvals.reusable.match(principal,mcpSession,request,scope,op);if(p==null)throw new SecurityException("Catalog permission required");
                approvals.reusable.require(p.path("id").asText(),principal,mcpSession,request,scope,op);approvals.reusable.auditUse(p.path("id").asText(),principal,operation);matched.set(p);
            };
            JsonNode result=contexts.agent(principal,operation,args,check);
            if(matched.get()!=null){check.accept(contexts.binding(Profiles.text(args,"bindingId",36)));if(result instanceof ObjectNode object){object.set("matchedPolicy",matched.get());object.put("authorizationReason","Scoped reusable catalog permission");}}
            return result;
        }

        if(operation.equals("dba_list_connections")){ArrayNode result=Profiles.JSON.createArrayNode();for(JsonNode p:profiles.list())try{if(!authorization.automatic&&!agents.isTrustedLocal(principal))agents.requireName(principal,p.path("id").asText(),p.path("name").asText());result.addObject().put("id",p.path("id").asText()).put("name",p.path("name").asText()).put("readOnly",!authorization.automatic);}catch(SecurityException denied){}return result;}
        if(Set.of("dba_job_status","dba_cancel_job","dba_release_job").contains(operation)){
            // Ownership authorizes lifecycle access to already admitted jobs (including standalone
            // human-approved SQL/tests); it does not grant a new database operation.
            String id=Profiles.text(args,"jobId",36);QueryJobs.Job job=jobs.require(owner,id);
            if(operation.equals("dba_job_status"))return jobs.status(owner,id,args,()->{
                if(!agentAlive(principal)||mcpSession!=null&&!approvals.reusable.sessions.alive(mcpSession,principal))
                    throw new SecurityException("MCP agent/session expired during job status wait");
                authorization.requireSession(principal,mcpSession);
            });
            if(operation.equals("dba_cancel_job"))jobs.cancel(job);else jobs.remove(owner,id);
            return Profiles.JSON.createObjectNode().put("ok",true);
        }
        boolean readCall=Set.of("dba_execute_read_query","dba_explain_query","dba_analyze_query_plan","dba_get_metadata","dba_get_object_ddl").contains(operation);
        if(Set.of("dba_execute_read_query","dba_explain_query","dba_analyze_query_plan").contains(operation))SqlReadGuard.validate(Profiles.text(args,"sql",16384));
        ObjectNode bindingTarget=readCall&&args.has("bindingId")?contexts.authorized(principal,Profiles.text(args,"bindingId",36),true):null;
        if(bindingTarget!=null&&(args.has("connectionId")||args.has("connectionName")||args.has("database")||args.has("schema")))throw new IllegalArgumentException("A binding fixes the complete target; do not also supply connection, database or schema");
        String connectionName=bindingTarget==null?Profiles.text(args,"connectionName",120):profiles.get(bindingTarget.path("connectionId").asText()).path("name").asText(),connection=bindingTarget==null?args.path("connectionId").asText(""):bindingTarget.path("connectionId").asText();
        if(connection.isEmpty()){List<String> matches=new ArrayList<>();for(JsonNode p:profiles.list())if(Profiles.nameKey(p.path("name").asText()).equals(Profiles.nameKey(connectionName)))matches.add(p.path("id").asText());if(matches.size()!=1)throw new SecurityException("Unknown or ambiguous connection name");connection=matches.get(0);}
        ObjectNode profile=profiles.get(connection);if(!Profiles.nameKey(profile.path("name").asText()).equals(Profiles.nameKey(connectionName)))throw new SecurityException("Connection name does not match ID");
        if(readCall&&(authorization.automatic||bindingTarget!=null||!legacyReadAuthorized(principal,connection,connectionName,operation,args))){
            if(mcpSession==null)throw new SecurityException("No existing read grant; a validated MCP session is required for scoped approval");
            if(bindingTarget==null&&!args.has("connectionId"))throw new IllegalArgumentException("Reusable or one-time access requires stable connectionId plus exact connectionName");
            if(approvalsEnabled())requireApprovalAvailable();
            ObjectNode input=Profiles.JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose",operation);
            if(bindingTarget==null)input.put("connectionId",connection).put("connectionName",connectionName);else input.put("bindingId",bindingTarget.path("id").asText());
            if(args.has("database"))input.set("database",args.path("database"));if(args.has("schema"))input.set("schema",args.path("schema"));
            if(operation.equals("dba_get_metadata")||operation.equals("dba_get_object_ddl")){
                ObjectNode scope=authorization.scope(profile,bindingTarget==null?Profiles.JSON.createObjectNode():bindingTarget,input);var query=TrustedCatalogRead.prepare(operation,scope,args);
                input.put("sql",query.sql());input.set("parameters",query.parameters());return broker.decorate(approvals.readTool(principal,mcpSession,input,true,approvalsEnabled()));
            }
            String sql=Profiles.text(args,"sql",16384);SqlReadGuard.validate(sql);
            input.put("sql",sql);input.set("parameters",args.has("parameters")?args.path("parameters"):Profiles.JSON.createArrayNode());
            return broker.decorate(operation.equals("dba_execute_read_query")?approvals.readTool(principal,mcpSession,input,false,approvalsEnabled()):approvals.planTool(principal,mcpSession,input,operation.equals("dba_analyze_query_plan"),approvalsEnabled()));
        }
        agents.requireName(principal,connection,connectionName);ArrayNode allowed=agents.objects(principal,connection);final String authorizedConnection=connection;
        String schema=optional(args,"schema"),object=optional(args,"object");
        contexts.databaseQueried(connection,schema);
        switch(operation){
            case "dba_get_metadata":
                if(object==null){ArrayNode rows=Profiles.JSON.createArrayNode();int cap=Math.min(100,config.agentRows()),matched=0;for(JsonNode o:allowed)if(schema==null||schema.equals(o.path("schema").asText())){matched++;if(rows.size()<cap)rows.add(o);}ObjectNode result=Profiles.JSON.createObjectNode().put("truncated",matched>cap);result.set("objects",rows);return result;}
                agents.requireObject(principal,connection,schema,object);return jobs.metadata(owner,connection,schema,object);
            case "dba_get_object_ddl": agents.requireObject(principal,connection,schema,object);return jobs.ddl(owner,connection,schema,object);
            case "dba_execute_read_query", "dba_explain_query", "dba_analyze_query_plan":
                String sql=Profiles.text(args,"sql",16_384);SqlReadGuard.validate(sql,(s,n)->agents.requireObject(principal,authorizedConnection,s,n));
                JsonNode parameters=args.has("parameters")?args.get("parameters"):Profiles.JSON.createArrayNode();
                if(operation.equals("dba_execute_read_query"))return jobs.query(owner,connection,sql,parameters);
                ObjectNode scope=ApprovalScope.resolve(profile,bindingTarget==null?Profiles.JSON.createObjectNode():bindingTarget,args);
                ObjectNode targetRequest=Profiles.JSON.createObjectNode();for(String key:List.of("bindingId","connectionId","connectionName","database","schema"))if(args.has(key))targetRequest.set(key,args.get(key));
                if(bindingTarget!=null)targetRequest.put("bindingId",bindingTarget.path("id").asText());
                return jobs.estimatedPlan(owner,connection,scope,targetRequest,sql,parameters,operation.equals("dba_analyze_query_plan"));
            default:throw new SecurityException("DBA operation is not available in restricted read mode");
        }
    }
    private JsonNode agentCapabilities(String principal,String mcpSession,JsonNode args){
        return agentCapabilities(principal,mcpSession,args,false);
    }
    private JsonNode agentCapabilities(String principal,String mcpSession,JsonNode args,boolean authorizeOnly){
        if(!agentAlive(principal))throw new SecurityException("Agent revoked");
        authorization.requireSession(principal,mcpSession);
        boolean live=args.path("live").asBoolean(false);
        ObjectNode binding=Profiles.JSON.createObjectNode(),profile;
        boolean allowed=false;
        if(args.has("bindingId")){
            for(String key:List.of("connectionId","connectionName","database","schema"))if(args.has(key))throw new IllegalArgumentException("A binding fixes the complete target; do not override it");
            String id=Profiles.text(args,"bindingId",36);binding=contexts.authorized(principal,id,true);
            try{contexts.authorized(principal,id,false);allowed=true;}catch(SecurityException denied){}
            profile=profiles.get(binding.path("connectionId").asText());
        }else{
            profile=profiles.get(Profiles.text(args,"connectionId",36));
            if(!profile.path("name").asText().equals(Profiles.text(args,"connectionName",120)))throw new IllegalArgumentException("Use the exact connection name matching connectionId");
            ObjectNode target=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText());
            allowed=agents.permitsRead(principal,live?"metadata":"connection_details",target)||agents.permitsRead(principal,"catalog",target)||legacyReadAuthorized(principal,profile.path("id").asText(),profile.path("name").asText(),"dba_get_metadata",args);
        }
        if(DatabaseTransport.of(profile)!=DatabaseTransport.JDBC){
            ObjectNode nativeScope=nativeOperations.observationScope(args);
            NativeTarget nativeTarget=NativeTarget.resolve(profile,nativeScope);
            if(!allowed&&!authorization.automatic)throw new SecurityException("Scoped profile/catalog read permission required; native capability descriptions are also available through dba_list_templates");
            if(authorizeOnly)return nativeScope;
            authorization.audit(principal,mcpSession,"dba_get_capabilities",nativeScope);
            if(live){
                ObjectNode submitted=args.deepCopy();
                return nativeOperations.observe("agent:"+principal,nativeTarget,nativeScope,approvalsEnabled(),()->{
                    JsonNode current=agentCapabilities(principal,mcpSession,submitted,true);
                    if(!nativeScope.equals(current))throw new IllegalArgumentException("Native capability target changed");
                });
            }
            ObjectNode described=NativeCatalog.describe(profile,nativeTarget,approvalsEnabled());
            described.set("target",nativeScope);return authorization.describe(described);
        }
        ObjectNode scope=authorization.automatic&&live?authorization.scope(profile,binding,args):ApprovalScope.resolve(profile,binding,args);
        if(authorization.automatic)allowed=true;
        ObjectNode matched=null;
        if(!allowed){
            ObjectNode request=Profiles.JSON.createObjectNode().put("sql","dba_get_capabilities").put("autoCommit",false);request.set("parameters",args);
            var operation=new ReusableOperation.Result("ddl_inspection",true,true,"Authorized cached capability inspection");
            matched=approvals.reusable.match(principal,mcpSession,request,scope,operation);
            if(matched==null)throw new IllegalArgumentException("Catalog or connection-details permission required; request reviewed access first. Capability discovery grants no access.");
            approvals.reusable.require(matched.path("id").asText(),principal,mcpSession,request,scope,operation);
            approvals.reusable.auditUse(matched.path("id").asText(),principal,"dba_get_capabilities");
        }
        if(authorizeOnly)return scope;
        if(live){
            ObjectNode submitted=args.deepCopy();
            return jobs.capabilities("agent:"+principal,profile.path("id").asText(),profile,scope,approvalsEnabled(),()->{
                if(!scope.equals(agentCapabilities(principal,mcpSession,submitted,true)))throw new IllegalArgumentException("Capability target changed before execution; submit a new request");
            });
        }
        JsonNode snapshot=Profiles.JSON.createObjectNode();
        for(JsonNode row:contexts.state().path("bindings"))if(row.path("connectionId").asText().equals(scope.path("connectionId").asText())
                &&(binding.has("id")?row.path("id").equals(binding.path("id")):
                row.path("database").asText().equals(scope.path("database").asText())&&row.path("schema").asText().equals(scope.path("schema").asText()))) {
            snapshot=row.path("snapshot");break;
        }
        ObjectNode result=DatabaseCapabilities.describe(profile,scope,snapshot,approvalsEnabled());
        if(matched!=null)result.set("matchedPolicy",matched);
        result.put("authorizationReason",matched==null?"Existing scoped catalog/profile permission":"Scoped reusable catalog permission");
        return result;
    }
    static byte[] validatedWorkspace(JsonNode source)throws IOException{return workspace(source);}
    private static byte[] workspace(JsonNode source)throws IOException {
        if(source.path("version").asInt()!=1||!source.path("tabs").isArray())throw new IllegalArgumentException("Workspace version 1 with tabs is required");
        ArrayNode input=(ArrayNode)source.path("tabs");if(input.size()>MAX_SCRIPT_TABS)throw new IllegalArgumentException("Workspace exceeds 12 Script tabs");
        ObjectNode result=Profiles.JSON.createObjectNode().put("version",1);ArrayNode output=result.putArray("tabs");Set<String> ids=new HashSet<>();long scriptBytes=0;
        for(JsonNode tab:input){
            if(!tab.isObject())throw new IllegalArgumentException("Each Script tab must be an object");String id=Profiles.text(tab,"id",36);UUID.fromString(id);if(!ids.add(id))throw new IllegalArgumentException("Duplicate Script tab ID");
            String type=tab.path("type").asText("script");if(!Set.of("script","table","builder","object","native").contains(type))throw new IllegalArgumentException("Unknown workspace tab type");
            if(type.equals("object")){
                String title=Profiles.text(tab,"title",2048),connection=Profiles.text(tab,"connection",36);UUID.fromString(connection);
                ObjectNode selection=MetadataActions.request(tab.path("object"));if(!ObjectDesigner.GROUPS.contains(selection.path("parent").path("kind").asText()))throw new IllegalArgumentException("Invalid object tab identity");
                String inner=tab.path("inner").asText("properties");if(!Set.of("properties","data","diagram").contains(inner)||!inner.equals("properties")&&!TableQueries.RELATIONS.contains(selection.path("parent").path("kind").asText()))throw new IllegalArgumentException("Invalid object view");
                ObjectNode saved=output.addObject().put("type","object").put("id",id).put("title",title).put("connection",connection).put("inner",inner);saved.set("object",selection);if(tab.has("builderDraft"))saved.set("builderDraft",VisualQuery.validateDraft(tab.path("builderDraft")));continue;
            }
            if(type.equals("native")){
                String title=Profiles.text(tab,"title",255),connection=Profiles.text(tab,"connection",36);UUID.fromString(connection);
                String command=tab.path("commandText").asText("");
                if(!tab.path("commandText").isTextual()||command.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("Native command text exceeds 128 KiB");
                ObjectNode saved=output.addObject().put("type","native").put("id",id).put("title",title).put("connection",connection).put("commandText",command);
                for(String field:List.of("database","collection")){String value=tab.path(field).asText("");if(value.length()>256)throw new IllegalArgumentException("Invalid native workspace target");saved.put(field,value);}continue;
            }
            if(type.equals("table")){
                String title=Profiles.text(tab,"title",2048),connection=Profiles.text(tab,"connection",36);UUID.fromString(connection);
                ObjectNode selection=MetadataActions.request(tab.path("table"));if(!TableQueries.RELATIONS.contains(selection.path("parent").path("kind").asText()))throw new IllegalArgumentException("Invalid Table tab identity");
                String inner=tab.path("inner").asText("data");if(!Set.of("properties","data","diagram").contains(inner))throw new IllegalArgumentException("Invalid Table view");
                ObjectNode saved=output.addObject().put("type","table").put("id",id).put("title",title).put("connection",connection).put("inner",inner);saved.set("table",selection);if(tab.has("builderDraft"))saved.set("builderDraft",VisualQuery.validateDraft(tab.path("builderDraft")));continue;
            }
            JsonNode titleNode=tab.get("title");String title=titleNode!=null&&titleNode.isTextual()?titleNode.asText():"";if(title.isBlank()||title.length()>255)throw new IllegalArgumentException("Missing/oversize Script title");JsonNode sqlNode=tab.get("sql");if(sqlNode==null||!sqlNode.isTextual())throw new IllegalArgumentException("Script SQL must be text");String sql=sqlNode.asText();int bytes=sql.getBytes(StandardCharsets.UTF_8).length;if(bytes>MAX_SCRIPT_BYTES)throw new IllegalArgumentException("A Script exceeds 1 MiB");scriptBytes+=bytes;if(scriptBytes>12L*MAX_SCRIPT_BYTES)throw new IllegalArgumentException("Workspace Script text exceeds 12 MiB");if(tab.has("dirty")&&!tab.path("dirty").isBoolean())throw new IllegalArgumentException("Invalid Script dirty state");
            ObjectNode saved=output.addObject().put("type",type).put("id",id).put("title",title).put("sql",sql).put("dirty",tab.path("dirty").asBoolean(false));
            if(tab.hasNonNull("connection")){String connection=tab.path("connection").asText();UUID.fromString(connection);saved.put("connection",connection);}else saved.putNull("connection");
            if(type.equals("builder")){if(bytes>16384||!tab.hasNonNull("connection"))throw new IllegalArgumentException("Builder requires a connection and at most 16 KiB of SQL");String database=tab.path("database").asText("");if(database.length()>256)throw new IllegalArgumentException("Invalid builder database");saved.put("database",database);if(tab.has("draft"))saved.set("draft",VisualQuery.validateDraft(tab.path("draft")));}
            if(tab.has("editorRatio")&&!tab.path("editorRatio").isNumber())throw new IllegalArgumentException("Invalid Script divider position");double ratio=tab.path("editorRatio").asDouble(.38);if(!Double.isFinite(ratio)||ratio<.1||ratio>.9)throw new IllegalArgumentException("Invalid Script divider position");saved.put("editorRatio",ratio);
            if(tab.has("lastEdited")&&(!tab.path("lastEdited").isIntegralNumber()||tab.path("lastEdited").asLong(-1)<0))throw new IllegalArgumentException("Invalid Script edit time");saved.put("lastEdited",Math.min(System.currentTimeMillis(),tab.path("lastEdited").asLong(0)));
            ObjectNode toggles=saved.putObject("railToggles");JsonNode supplied=tab.path("railToggles");for(String key:List.of("serverOutput","executionLog","sqlVariables")){if(supplied.has(key)&&!supplied.path(key).isBoolean())throw new IllegalArgumentException("Invalid Script output toggle");toggles.put(key,supplied.path(key).asBoolean(false));}
            String view=tab.path("outputView").asText("");if(Set.of("serverOutput","executionLog","sqlVariables").contains(view)&&toggles.path(view).asBoolean())saved.put("outputView",view);else saved.putNull("outputView");
        }
        String active=nullableUuid(source,"active"),lastSelected=nullableUuid(source,"lastSelected");if(active!=null&&!ids.contains(active))throw new IllegalArgumentException("Active Script tab is not in the workspace");if(active==null)result.putNull("active");else result.put("active",active);if(lastSelected==null)result.putNull("lastSelected");else result.put("lastSelected",lastSelected);
        byte[] encoded=Profiles.JSON.writeValueAsBytes(result);if(encoded.length>MAX_WORKSPACE_BYTES)throw new IllegalArgumentException("Workspace state exceeds 16 MiB");return encoded;
    }
    private static String nullableUuid(JsonNode source,String key){if(!source.hasNonNull(key))return null;String value=source.path(key).asText();UUID.fromString(value);return value;}
    private static String sourceUrl(JsonNode profile){return profile.path("url").asText().strip().replaceAll("(?i)(password|pwd|user)=[^;&?]*","$1=<redacted>").toLowerCase(Locale.ROOT);}
    private static JsonNode body(HttpExchange x)throws IOException {return body(x,131_072);}
    private static JsonNode body(HttpExchange x,int maximum)throws IOException {
        String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new IllegalArgumentException("JSON content type required");
        byte[] bytes=x.getRequestBody().readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalArgumentException("Request exceeds allowed size");
        try{JsonNode n=Profiles.JSON.readTree(bytes);if(n==null||!n.isObject())throw new IllegalArgumentException("JSON object required");return n;}
        catch(com.fasterxml.jackson.core.JacksonException e){throw new IllegalArgumentException("Invalid JSON");}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    private boolean legacyReadAuthorized(String principal,String connection,String name,String operation,JsonNode args){
        try{
            agents.requireName(principal,connection,name);
            if(args.has("database"))return false;
            if(Set.of("dba_execute_read_query","dba_explain_query","dba_analyze_query_plan").contains(operation))SqlReadGuard.validate(args.path("sql").asText(),(s,n)->agents.requireObject(principal,connection,s,n));
            else if(args.has("object"))agents.requireObject(principal,connection,optional(args,"schema"),optional(args,"object"));
            return true;
        }catch(SecurityException|IllegalArgumentException e){return false;}
    }
    static void asset(HttpExchange x,String path)throws IOException {
        if(Set.of("/dba/editor-client.js","/dba/grid-cell-editor.js","/dba/grid-state.js","/dba/grid-window.js","/dba/grid-interactions.js","/dba/grid-data.css","/dba/grid-operations.js","/dba/grid-search.js","/dba/grid-search-worker.js",
                "/dba/mongo-pipeline-state.js","/dba/mongo-pipeline-editor.js","/dba/driver-download-settings.js").contains(path)){
            String name=path.substring("/dba/".length());try(InputStream input=DbaRuntime.class.getResourceAsStream("/codegraph/dba/"+name)){
                if(input==null){json(x,404,Map.of("error","Asset not found"));return;}byte[] bytes=input.readAllBytes();x.getResponseHeaders().set("Content-Type",name.endsWith(".css")?"text/css; charset=utf-8":"application/javascript; charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);return;
            }
        }
        String file=switch(path){case "/dba/review"->"approval-review.html";case "/dba/approval-review.js"->"approval-review.js";case "/dba/approval-client.js"->"approval-client.js";case "/dba/approval-ui.js"->"approval-ui.js";case "/dba/project-context.js"->"project-context.js";case "/dba/catalog-ui.js"->"catalog-ui.js";case "/dba/object-properties.js"->"object-properties.js";case "/dba/object-creation.js"->"object-creation.js";case "/dba","/dba/"->"index.html";case "/dba/table-properties.js"->"table-properties.js";case "/dba/query-builder.css"->"query-builder.css";case "/dba/visual-model.js"->"visual-model.js";case "/dba/visual-expressions.js"->"visual-expressions.js";case "/dba/query-builder.js"->"query-builder.js";case "/dba/app.js"->"app.js";case "/dba/connection-editor.js"->"connection-editor.js";case "/dba/native-connection-editor.js"->"native-connection-editor.js";case "/dba/native-workspace.js"->"native-workspace.js";case "/dba/mongo-document-editor.js"->"mongo-document-editor.js";case "/dba/redis-stream-editor.js"->"redis-stream-editor.js";case "/dba/redis-set-editor.js"->"redis-set-editor.js";case "/dba/redis-string-editor.js"->"redis-string-editor.js";case "/dba/data-grid.js"->"data-grid.js";case "/dba/connection-tree.js"->"connection-tree.js";case "/dba/metadata-tree.js"->"metadata-tree.js";case "/dba/tree-icons.js"->"tree-icons.js";case "/dba/tree-actions.js"->"tree-actions.js";case "/dba/database.svg"->"database.svg";case "/dba/style.css"->"style.css";case "/dba/workspace-theme.css"->"workspace-theme.css";default->null;};
        if(file==null){json(x,404,Map.of("error","Asset not found"));return;}
        try(InputStream in=DbaRuntime.class.getResourceAsStream("/codegraph/dba/"+file)){
            if(in==null){json(x,404,Map.of("error","Asset not found"));return;}
            x.getResponseHeaders().set("Content-Type",file.endsWith("js")?"application/javascript; charset=utf-8":file.endsWith("css")?"text/css; charset=utf-8":file.endsWith("svg")?"image/svg+xml":"text/html; charset=utf-8");
            byte[] bytes=in.readAllBytes();x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
        }
    }
    private static void json(HttpExchange x,int status,Object data)throws IOException{byte[] bytes=Profiles.JSON.writeValueAsBytes(data);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
    private static void jsonBytes(HttpExchange x,int status,byte[] bytes)throws IOException{try{x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}finally{Arrays.fill(bytes,(byte)0);}}
    private static Map<String,String> query(HttpExchange x){
        Map<String,String> result=new HashMap<>();String raw=x.getRequestURI().getRawQuery();if(raw==null)return result;if(raw.length()>512)throw new IllegalArgumentException("Query too long");
        for(String item:raw.split("&")){String[] parts=item.split("=",2);if(parts.length==2)result.put(java.net.URLDecoder.decode(parts[0],StandardCharsets.UTF_8),java.net.URLDecoder.decode(parts[1],StandardCharsets.UTF_8));}return result;
    }
    @Override public void close(){if(!closed.compareAndSet(false,true))return;contextTimer.shutdownNow();broker.close();reviewServer.close();if(editorRequests!=null)editorRequests.close();if(editorPairings!=null)editorPairings.close();agentRequests.close();approvals.close();migrations.close();contexts.close();grids.close();jobs.close();nativeOperations.close();connections.close();if(auth!=null)auth.close();try{profiles.close();}catch(IOException ignored){}if(Thread.currentThread()!=shutdownHook)try{Runtime.getRuntime().removeShutdownHook(shutdownHook);}catch(IllegalStateException ignored){}}
}
