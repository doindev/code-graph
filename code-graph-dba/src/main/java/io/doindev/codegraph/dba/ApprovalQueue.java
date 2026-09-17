package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/** Human authority is a separate channel. No agent-supplied boolean can grant execution. */
final class ApprovalQueue implements AutoCloseable {
    private final ProjectContexts contexts;private final Profiles profiles;private final AgentAccess agents;private final QueryJobs jobs;
    private final Map<String,Request> requests=new LinkedHashMap<>();private final LongSupplier clock;private final Path audit;
    private java.util.function.Consumer<String> onPending=id->{};
    synchronized void onPending(java.util.function.Consumer<String> listener){onPending=listener;}
    private IntSupplier otherCount=()->0;
    static final class Request{
        final ObjectNode value;final String principal;final AutoCloseable hold;String state="awaiting_approval",jobId="";boolean released;
        Request(ObjectNode v,String p,AutoCloseable h){value=v;principal=p;hold=h;}
        synchronized void release(){if(!released){released=true;try{hold.close();}catch(Exception ignored){}}}
    }
    ApprovalQueue(ProjectContexts c,Profiles p,AgentAccess a,QueryJobs j){this(c,p,a,j,System::currentTimeMillis);}
    ApprovalQueue(ProjectContexts c,Profiles p,AgentAccess a,QueryJobs j,LongSupplier clock){contexts=c;profiles=p;agents=a;jobs=j;this.clock=clock;audit=p.directory().resolve("agent-approvals.jsonl");}
    synchronized void otherCount(IntSupplier value){otherCount=value;}
    private volatile int retainedCount;
    private java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(32);
    synchronized void capacity(java.util.concurrent.Semaphore shared){if(!requests.isEmpty())throw new IllegalStateException("Approval capacity must be set before requests");capacity=shared;}
    int count(){return retainedCount;}
    synchronized JsonNode request(String principal,JsonNode input){
        reap();Set<String> keys=Set.of("bindingId","connectionId","connectionName","sql","parameters","purpose","autoCommit","requestId");input.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new IllegalArgumentException("Unexpected live request field: "+k);});
        boolean bound=input.has("bindingId");
        if(bound&&(input.has("connectionId")||input.has("connectionName")))throw new IllegalArgumentException("Use bindingId or connectionId plus connectionName, not both target forms");
        if(!agents.alive("agent:"+principal))throw new SecurityException("Agent revoked");
        String bindingId=bound?Profiles.text(input,"bindingId",36):"";
        ObjectNode b=bound?contexts.authorized(principal,bindingId,true):Profiles.JSON.createObjectNode().put("connectionId",Profiles.text(input,"connectionId",36));
        ObjectNode p=profiles.get(b.path("connectionId").asText());
        if(!bound&&!Profiles.nameKey(p.path("name").asText()).equals(Profiles.nameKey(Profiles.text(input,"connectionName",120))))throw new SecurityException("Connection name does not match its stable ID");
        String sql=Profiles.text(input,"sql",65536),purpose=Profiles.text(input,"purpose",2000),requestId=Profiles.text(input,"requestId",100);
        JsonNode parameters=input.has("parameters")?input.path("parameters"):Profiles.JSON.createArrayNode();HumanSql.checkParameters(parameters);
        ObjectNode fingerprintInput=((ObjectNode)input).deepCopy();String hash=CatalogScanner.hash(fingerprintInput.toString());
        for(Request r:requests.values())if(r.principal.equals(principal)&&r.value.path("requestId").asText().equals(requestId)){if(!r.value.path("requestHash").asText().equals(hash))throw new IllegalArgumentException("Request ID was already used for different SQL or parameters");return status(r,false);}
        if(requests.size()+otherCount.getAsInt()>=32)throw new IllegalArgumentException("Approval queue is full; wait for completed requests to expire");
        ObjectNode value=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("requestId",requestId).put("requestHash",hash)
            .put("connectionId",b.path("connectionId").asText()).put("connectionName",p.path("name").asText()).put("database",b.path("database").asText()).put("schema",b.path("schema").asText())
            .put("type","live_sql").put("sql",sql).put("purpose",purpose).put("autoCommit",input.path("autoCommit").asBoolean(false)).put("createdAt",clock.getAsLong()).put("expiresAt",clock.getAsLong()+300000)
            .put("profileRevision",ProjectContexts.profileRevision(p));value.set("parameters",parameters.deepCopy());value.set("classification",classify(sql));
        if(bound)value.put("bindingId",bindingId).put("projectId",b.path("projectId").asText()).put("project",b.path("projectName").asText()).put("environment",b.path("environment").asText()).put("role",b.path("role").asText()).put("snapshotFingerprint",contexts.fingerprint(bindingId));
        else value.putObject("target").put("connectionId",p.path("id").asText()).put("connectionName",p.path("name").asText()).put("database","Saved connection default (no project binding)");
        boolean verifiedRead=false;try{SqlReadGuard.validate(sql);verifiedRead=Set.of("postgresql","h2","mysql","mariadb").contains(p.path("templateId").asText());}catch(IllegalArgumentException ignored){}value.put("eligiblePersistentRead",verifiedRead).put("readCapability","query");
        value.put("scopeNotice",bound?"The schema binding scopes catalog discovery. Review every referenced target in live SQL; native statements and routines can affect objects outside that schema.":"Standalone connection: use the saved connection's configured database and schema. No project is required. Review every referenced target; qualified SQL and routines may affect other objects allowed by the database account.");
        value.put("transactionNotice","Rollback depends on the database and statement. DDL, explicit commits, routines and external effects may commit independently. Unknown outcomes are never automatically replayed.");
        Request r=new Request(value,principal,bound?contexts.hold(bindingId):()->{});if(!capacity.tryAcquire()){r.release();throw new IllegalArgumentException("Approval queue is full");}try{record(r,"requested","");}catch(RuntimeException e){capacity.release();r.release();throw e;}requests.put(value.path("id").asText(),r);retainedCount=requests.size();if(verifiedRead&&agents.permitsRead(principal,"query",b))submit(r,"policy");if(r.state.equals("awaiting_approval"))onPending.accept(value.path("id").asText());return status(r,false);
    }
    static ObjectNode classify(String sql){ObjectNode out=Profiles.JSON.createObjectNode().put("approvalRequired",true).put("scopeVerified",false);try{
        var statements=net.sf.jsqlparser.parser.CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(500));ArrayNode names=out.putArray("statements");boolean destructive=false;
        for(var statement:statements){String name=statement.getClass().getSimpleName();names.add(name);if(Set.of("Drop","Delete","Truncate","Alter","Merge","Update","Grant","Revoke").contains(name))destructive=true;}
        out.put("risk",destructive?"destructive":"review_required").put("parsed",true);
    }catch(Exception e){out.put("risk","unknown_native_statement").put("parsed",false);}
        return out;
    }
    synchronized JsonNode list(){reap();ArrayNode out=Profiles.JSON.createArrayNode();for(Request r:requests.values())out.add(status(r,true));return out;}
    synchronized JsonNode get(String principal,String id){reap();Request r=require(principal,id);authorize(r);return status(r,false);}
    synchronized JsonNode decide(String session,String id,boolean approved,boolean acknowledged){
        return decide(session,id,approved?"approve_once":"reject",acknowledged);
    }
    synchronized JsonNode decide(String session,String id,String action,boolean acknowledged){
        reap();Request r=requests.get(id);if(r==null||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Approval request expired or already consumed");
        if(action.equals("reject")){record(r,"rejected",session);r.state="rejected";r.release();return status(r,true);}
        boolean persistent=action.startsWith("always_");if(persistent&&!r.value.path("eligiblePersistentRead").asBoolean())throw new IllegalArgumentException("Persistent approval is available only for verified read-only SQL");if(!action.equals("approve_once")&&!persistent)throw new IllegalArgumentException("Unsupported approval decision");
        if(persistent&&!r.value.has("bindingId")&&!action.equals("always_connection_read"))throw new IllegalArgumentException("Standalone SQL can only receive a connection-scoped read policy");
        if(!acknowledged)throw new IllegalArgumentException("Acknowledge the exact SQL, target and possible effects before approving");validate(r);
        record(r,"authorized",session);
        if(persistent)try{agents.grantRead(r.principal,action,"query",r.value.has("bindingId")?contexts.binding(r.value.path("bindingId").asText()):Profiles.JSON.createObjectNode().put("connectionId",r.value.path("connectionId").asText()));}catch(java.io.IOException e){throw new IllegalStateException("Read policy could not be persisted; SQL was not submitted",e);}
        submit(r,session);
        return status(r,true);
    }
    private void submit(Request r,String session){record(r,"approved",session);r.state="approved";try{ObjectNode job=jobs.approved(r.principal,r.value,()->validate(r),()->{contexts.changed(r.value.path("connectionId").asText());r.release();});r.jobId=job.path("id").asText();r.state="submitted";record(r,"submitted",session);}catch(Exception e){r.state="failed_to_submit";r.release();record(r,"failed_to_submit",session);throw e;}}
    private void authorize(Request r){if(!agents.alive("agent:"+r.principal))throw new SecurityException("Agent revoked");if(r.value.has("bindingId"))contexts.authorized(r.principal,r.value.path("bindingId").asText(),true);}
    private void validate(Request r){String fingerprint=r.value.path("snapshotFingerprint").asText();if(!fingerprint.isEmpty()&&!fingerprint.equals(contexts.fingerprint(r.value.path("bindingId").asText())))throw new IllegalArgumentException("Catalog or database version changed since review; request approval again");authorize(r);if(!ProjectContexts.profileRevision(profiles.get(r.value.path("connectionId").asText())).equals(r.value.path("profileRevision").asText()))throw new IllegalArgumentException("Connection changed after request; submit a new request for review");}
    synchronized JsonNode cancel(String principal,String id){Request r=require(principal,id);if(r.state.equals("awaiting_approval")){r.state="cancelled";r.release();record(r,"cancelled","");}else if(!r.jobId.isEmpty()&&r.state.equals("submitted")){jobs.cancel(jobs.require("agent:"+principal,r.jobId));record(r,"cancellation_requested","");}return status(r,false);}
    private Request require(String principal,String id){Request r=requests.get(id);if(r==null||!r.principal.equals(principal))throw new SecurityException("Approval request is not owned by this agent");return r;}
    private ObjectNode status(Request r,boolean browser){ObjectNode out=r.value.deepCopy();out.remove(List.of("profileRevision","requestHash"));out.put("state",r.state);if(browser)out.put("agentId",r.principal);if(!r.jobId.isEmpty()){out.put("jobId",r.jobId);try{JsonNode job=jobs.status("agent:"+r.principal,r.jobId);if(browser){ObjectNode summary=((ObjectNode)job).deepCopy();summary.remove(List.of("result","exception"));out.set("job",summary);}else out.set("job",job);String state=job.path("state").asText();if(Set.of("complete","failed","cancelled").contains(state)&&!r.state.equals(state)){r.state=state;r.release();out.put("state",state);record(r,state,"");}}catch(Exception e){out.put("jobExpired",true);if(r.state.equals("submitted")){r.state="result_expired";r.release();out.put("state",r.state);record(r,r.state,"");}}}return out;}
    private void reap(){long now=clock.getAsLong();var iterator=requests.values().iterator();while(iterator.hasNext()){Request r=iterator.next();if(r.state.equals("awaiting_approval")&&(now>=r.value.path("expiresAt").asLong()||!agents.alive("agent:"+r.principal))){r.state="expired";r.release();record(r,"expired","");}if(now-r.value.path("createdAt").asLong()>3600000&&!r.state.equals("submitted")){r.release();iterator.remove();capacity.release();retainedCount=requests.size();}}}
    private void record(Request r,String action,String session){try{if(Files.exists(audit)&&Files.size(audit)>4L<<20)Files.move(audit,audit.resolveSibling("agent-approvals.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}ObjectNode row=Profiles.JSON.createObjectNode().put("at",clock.getAsLong()).put("request",r.value.path("id").asText()).put("agent",r.principal).put("binding",r.value.path("bindingId").asText()).put("connection",r.value.path("connectionId").asText()).put("action",action).put("humanSession",session).put("sqlHash",CatalogScanner.hash(r.value.path("sql").asText())).put("job",r.jobId);Files.writeString(audit,row+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);}catch(Exception e){throw new IllegalStateException("Approval audit could not be written; execution is not authorized",e);}}
    synchronized void tick(){reap();for(Request r:requests.values())if(r.state.equals("submitted"))status(r,false);}
    public synchronized void close(){requests.values().forEach(Request::release);capacity.release(requests.size());requests.clear();retainedCount=0;}
}
