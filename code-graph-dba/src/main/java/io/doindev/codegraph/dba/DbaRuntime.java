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
    private final Profiles profiles;
    private final BrowserAuth auth;
    private final AgentAccess agents;
    private final Connections connections;
    private final QueryJobs jobs;
    private final ConnectionSetup setup;
    private final Thread shutdownHook;
    private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
    public DbaRuntime(DbaConfig config)throws IOException {this(config,Vault.system(),true);}
    public DbaRuntime(DbaConfig config,boolean ui)throws IOException {this(config,Vault.system(),ui);}
    public DbaRuntime(DbaConfig config,Vault vault)throws IOException {this(config,vault,true);}
    public DbaRuntime(DbaConfig config,Vault vault,boolean ui)throws IOException {
        this.config=config;profiles=new Profiles(config.directory(),vault);
        try{agents=new AgentAccess(profiles.directory());agents.bindLegacyNames(profiles);auth=ui?new BrowserAuth(profiles.directory()):null;}catch(IOException e){profiles.close();throw e;}
        connections=new Connections(profiles);jobs=new QueryJobs(connections,config,owner->agents.alive(owner)||(auth!=null&&auth.alive(owner)));
        setup=new ConnectionSetup(profiles,jobs);
        shutdownHook=new Thread(this::close,"dba-shutdown");Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
    public static boolean matches(String path){return path.equals("/dba")||path.startsWith("/dba/")||path.equals("/api/dba")||path.startsWith("/api/dba/");}
    public void handle(HttpExchange x)throws IOException {
        try{
            if(auth==null){json(x,404,Map.of("error","DBA UI is disabled"));return;}
            BrowserAuth.local(x);
            x.getResponseHeaders().set("Cache-Control","no-store");
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            x.getResponseHeaders().set("Referrer-Policy","no-referrer");
            x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(method.equals("GET")&&(path.equals("/dba")||path.startsWith("/dba/"))){asset(x,path);return;}
            if(path.equals("/api/dba/bootstrap")&&method.equals("POST")){
                body(x);var session=auth.bootstrap(x);
                x.getResponseHeaders().add("Set-Cookie","dba_session="+session.id()+"; Path=/api/dba/; HttpOnly; SameSite=Strict; Max-Age=3600");
                json(x,200,settings().put("csrf",session.csrf()).put("expires",session.expires()));return;
            }
            BrowserAuth.Session session=auth.require(x);
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
            if(path.equals("/api/dba/logout")&&method.equals("POST")){jobs.cancelOwner(session.id());auth.logout(session.id());json(x,200,Map.of("ok",true));return;}
            if(path.equals("/api/dba/workspace")){
                if(method.equals("GET"))jsonBytes(x,200,auth.workspace(session.id()));
                else if(method.equals("PUT")){byte[] state=workspace(body(x,MAX_WORKSPACE_BYTES));auth.workspace(session.id(),state);Arrays.fill(state,(byte)0);json(x,200,Map.of("saved",true));}
                else throw new IllegalArgumentException("Unsupported workspace method");return;
            }
            if(path.equals("/api/dba/settings")){
                if(method.equals("PUT")){JsonNode b=body(x);DbaConfig next=new DbaConfig(config.directory(),b.has("memory")?DbaConfig.budget(b.path("memory").asText()):config.memoryBytes(),b.path("concurrency").asInt(config.concurrency()),b.path("uiRows").asInt(config.uiRows()),b.path("agentRows").asInt(config.agentRows()),b.path("timeoutSeconds").asInt(config.timeoutSeconds()),b.path("decisionTimeoutSeconds").asInt(config.decisionTimeoutSeconds()));jobs.configure(next);config=next;}
                else if(!method.equals("GET"))throw new IllegalArgumentException("Unsupported settings method");
                json(x,200,settings());return;
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
                if(method.equals("DELETE")){connections.remove(id);profiles.remove(id);json(x,200,Map.of("removed",true));}
                else if(method.equals("PUT")){ObjectNode result=setup.save(session.id(),id,body(x));connections.remove(id);json(x,200,result);}
                else if(method.equals("GET"))json(x,200,Profiles.publicProfile(profiles.get(id)));
                else throw new IllegalArgumentException("Unsupported connection method");return;
            }
            if(path.equals("/api/dba/connections/test")&&method.equals("POST")){JsonNode b=body(x);json(x,202,jobs.test(session.id(),Profiles.text(b,"connectionId",36)));return;}
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
            if(path.equals("/api/dba/query/execute")&&method.equals("POST")){JsonNode b=body(x);if(b.has("database")&&!b.path("database").isTextual())throw new IllegalArgumentException("database must be text");if(b.has("autoCommit")&&!b.path("autoCommit").isBoolean())throw new IllegalArgumentException("autoCommit must be boolean");json(x,202,b.has("database")?jobs.tableQuery(session.id(),Profiles.text(b,"connectionId",36),Profiles.text(b,"sql",16384),b.path("parameters"),b.path("database").asText()):jobs.humanQuery(session.id(),Profiles.text(b,"connectionId",36),Profiles.text(b,"sql",16384),b.has("parameters")?b.get("parameters"):Profiles.JSON.createArrayNode(),b.path("autoCommit").asBoolean(false)));return;}
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
    private ObjectNode settings(){ObjectNode n=jobs.telemetry().put("uiRows",config.uiRows()).put("agentRows",Math.min(100,config.agentRows())).put("timeoutSeconds",config.timeoutSeconds()).put("decisionTimeoutSeconds",config.decisionTimeoutSeconds()).put("pools",connections.count()).put("writeExecutionEnabled",auth!=null).put("agentWriteExecutionEnabled",false).put("agentToolsEnabled",true).put("milestone","statement-aware-human-sql");return n;}
    public String authenticateAgent(String token){return agents.authenticate(token);}
    public boolean agentAlive(String id){return id!=null&&agents.alive("agent:"+id);}
    /** Called only with a principal established by the transport, never with an ID from tool arguments. */
    public JsonNode agentCall(String principal,String operation,JsonNode args){
        if(!agentAlive(principal))throw new SecurityException("DBA agent authentication required");
        if(args==null||!args.isObject()||args.toString().length()>65_536)throw new IllegalArgumentException("Bounded JSON object required");
        String owner="agent:"+principal;
        if(operation.equals("dba_list_connections")){ArrayNode result=Profiles.JSON.createArrayNode();for(JsonNode p:profiles.list())try{agents.requireName(principal,p.path("id").asText(),p.path("name").asText());result.addObject().put("id",p.path("id").asText()).put("name",p.path("name").asText()).put("readOnly",true);}catch(SecurityException denied){}return result;}
        if(Set.of("dba_job_status","dba_cancel_job","dba_release_job").contains(operation)){
            String id=Profiles.text(args,"jobId",36);QueryJobs.Job job=jobs.require(owner,id);agents.requireName(principal,job.connection,profiles.get(job.connection).path("name").asText());
            if(operation.equals("dba_job_status"))return jobs.status(owner,id);
            if(operation.equals("dba_cancel_job"))jobs.cancel(job);else jobs.remove(owner,id);
            return Profiles.JSON.createObjectNode().put("ok",true);
        }
        String connectionName=Profiles.text(args,"connectionName",120),connection=args.path("connectionId").asText("");
        if(connection.isEmpty()){List<String> matches=new ArrayList<>();for(JsonNode p:profiles.list())if(Profiles.nameKey(p.path("name").asText()).equals(Profiles.nameKey(connectionName)))matches.add(p.path("id").asText());if(matches.size()!=1)throw new SecurityException("Unknown or ambiguous connection name");connection=matches.get(0);}
        ObjectNode profile=profiles.get(connection);if(!Profiles.nameKey(profile.path("name").asText()).equals(Profiles.nameKey(connectionName)))throw new SecurityException("Connection name does not match ID");agents.requireName(principal,connection,connectionName);ArrayNode allowed=agents.objects(principal,connection);final String authorizedConnection=connection;
        String schema=optional(args,"schema"),object=optional(args,"object");
        switch(operation){
            case "dba_get_metadata":
                if(object==null){ArrayNode rows=Profiles.JSON.createArrayNode();int cap=Math.min(100,config.agentRows()),matched=0;for(JsonNode o:allowed)if(schema==null||schema.equals(o.path("schema").asText())){matched++;if(rows.size()<cap)rows.add(o);}ObjectNode result=Profiles.JSON.createObjectNode().put("truncated",matched>cap);result.set("objects",rows);return result;}
                agents.requireObject(principal,connection,schema,object);return jobs.metadata(owner,connection,schema,object);
            case "dba_get_object_ddl": agents.requireObject(principal,connection,schema,object);return jobs.ddl(owner,connection,schema,object);
            case "dba_execute_read_query", "dba_explain_query", "dba_analyze_query_plan":
                String sql=Profiles.text(args,"sql",16_384);SqlReadGuard.validate(sql,(s,n)->agents.requireObject(principal,authorizedConnection,s,n));
                JsonNode parameters=args.has("parameters")?args.get("parameters"):Profiles.JSON.createArrayNode();
                return operation.equals("dba_execute_read_query")?jobs.query(owner,connection,sql,parameters):jobs.explain(owner,connection,sql,parameters);
            default:throw new SecurityException("DBA operation is not available in restricted read mode");
        }
    }
    private static byte[] workspace(JsonNode source)throws IOException {
        if(source.path("version").asInt()!=1||!source.path("tabs").isArray())throw new IllegalArgumentException("Workspace version 1 with tabs is required");
        ArrayNode input=(ArrayNode)source.path("tabs");if(input.size()>MAX_SCRIPT_TABS)throw new IllegalArgumentException("Workspace exceeds 12 Script tabs");
        ObjectNode result=Profiles.JSON.createObjectNode().put("version",1);ArrayNode output=result.putArray("tabs");Set<String> ids=new HashSet<>();long scriptBytes=0;
        for(JsonNode tab:input){
            if(!tab.isObject())throw new IllegalArgumentException("Each Script tab must be an object");String id=Profiles.text(tab,"id",36);UUID.fromString(id);if(!ids.add(id))throw new IllegalArgumentException("Duplicate Script tab ID");
            String type=tab.path("type").asText("script");if(!Set.of("script","table","builder","object").contains(type))throw new IllegalArgumentException("Unknown workspace tab type");
            if(type.equals("object")){
                String title=Profiles.text(tab,"title",2048),connection=Profiles.text(tab,"connection",36);UUID.fromString(connection);
                ObjectNode selection=MetadataActions.request(tab.path("object"));if(!ObjectDesigner.GROUPS.contains(selection.path("parent").path("kind").asText()))throw new IllegalArgumentException("Invalid object tab identity");
                String inner=tab.path("inner").asText("properties");if(!Set.of("properties","data","diagram").contains(inner)||!inner.equals("properties")&&!TableQueries.RELATIONS.contains(selection.path("parent").path("kind").asText()))throw new IllegalArgumentException("Invalid object view");
                ObjectNode saved=output.addObject().put("type","object").put("id",id).put("title",title).put("connection",connection).put("inner",inner);saved.set("object",selection);if(tab.has("builderDraft"))saved.set("builderDraft",VisualQuery.validateDraft(tab.path("builderDraft")));continue;
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
    private static JsonNode body(HttpExchange x)throws IOException {return body(x,131_072);}
    private static JsonNode body(HttpExchange x,int maximum)throws IOException {
        String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new IllegalArgumentException("JSON content type required");
        byte[] bytes=x.getRequestBody().readNBytes(maximum+1);if(bytes.length>maximum)throw new IllegalArgumentException("Request exceeds allowed size");
        try{JsonNode n=Profiles.JSON.readTree(bytes);if(n==null||!n.isObject())throw new IllegalArgumentException("JSON object required");return n;}
        catch(com.fasterxml.jackson.core.JacksonException e){throw new IllegalArgumentException("Invalid JSON");}
        finally{Arrays.fill(bytes,(byte)0);}
    }
    private static void asset(HttpExchange x,String path)throws IOException {
        String file=switch(path){case "/dba/object-properties.js"->"object-properties.js";case "/dba/object-creation.js"->"object-creation.js";case "/dba","/dba/"->"index.html";case "/dba/table-properties.js"->"table-properties.js";case "/dba/query-builder.css"->"query-builder.css";case "/dba/visual-model.js"->"visual-model.js";case "/dba/visual-expressions.js"->"visual-expressions.js";case "/dba/query-builder.js"->"query-builder.js";case "/dba/app.js"->"app.js";case "/dba/connection-editor.js"->"connection-editor.js";case "/dba/data-grid.js"->"data-grid.js";case "/dba/connection-tree.js"->"connection-tree.js";case "/dba/metadata-tree.js"->"metadata-tree.js";case "/dba/tree-icons.js"->"tree-icons.js";case "/dba/tree-actions.js"->"tree-actions.js";case "/dba/database.svg"->"database.svg";case "/dba/style.css"->"style.css";default->null;};
        if(file==null){json(x,404,Map.of("error","Asset not found"));return;}
        try(InputStream in=DbaRuntime.class.getResourceAsStream("/codegraph/dba/"+file)){
            if(in==null){json(x,404,Map.of("error","Asset not found"));return;}
            x.getResponseHeaders().set("Content-Type",file.endsWith("js")?"application/javascript; charset=utf-8":file.endsWith("css")?"text/css; charset=utf-8":file.endsWith("svg")?"image/svg+xml":"text/html; charset=utf-8");
            byte[] bytes=in.readAllBytes();x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
        }
    }
    private static void json(HttpExchange x,int status,Object data)throws IOException{byte[] bytes=Profiles.JSON.writeValueAsBytes(data);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
    private static void jsonBytes(HttpExchange x,int status,byte[] bytes)throws IOException{try{x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}finally{Arrays.fill(bytes,(byte)0);}}
    @Override public void close(){if(!closed.compareAndSet(false,true))return;jobs.close();connections.close();if(auth!=null)auth.close();try{profiles.close();}catch(IOException ignored){}if(Thread.currentThread()!=shutdownHook)try{Runtime.getRuntime().removeShutdownHook(shutdownHook);}catch(IllegalStateException ignored){}}
}
