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
    private final ProjectContexts contexts;private final Profiles profiles;private final AgentAccess agents;private final QueryJobs jobs;private MigrationPlans migrations;
    final ReusableApprovals reusable;
    private final Map<String,Request> requests=new LinkedHashMap<>();private final LongSupplier clock;private final Path audit;
    private java.util.function.Consumer<String> onPending=id->{};
    synchronized void onPending(java.util.function.Consumer<String> listener){onPending=listener;}
    private IntSupplier otherCount=()->0;
    static final class Request{
        final ObjectNode value;final String principal;final AutoCloseable hold;String state="awaiting_approval",jobId="";boolean released;
        String mcpSession,policyId; ObjectNode scope; ReusableOperation.Result operation;
        Request(ObjectNode v,String p,AutoCloseable h){value=v;principal=p;hold=h;}
        synchronized void release(){if(!released){released=true;try{hold.close();}catch(Exception ignored){}}}
    }
    ApprovalQueue(ProjectContexts c,Profiles p,AgentAccess a,QueryJobs j){this(c,p,a,j,System::currentTimeMillis);}
    ApprovalQueue(ProjectContexts c,Profiles p,AgentAccess a,QueryJobs j,LongSupplier clock){contexts=c;profiles=p;agents=a;jobs=j;this.clock=clock;audit=p.directory().resolve("agent-approvals.jsonl");try{reusable=new ReusableApprovals(p.directory(),clock);}catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}}
    synchronized void otherCount(IntSupplier value){otherCount=value;}
    private volatile int retainedCount;
    private java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(32);
    synchronized void capacity(java.util.concurrent.Semaphore shared){if(!requests.isEmpty())throw new IllegalStateException("Approval capacity must be set before requests");capacity=shared;}
    synchronized void migrations(MigrationPlans value){migrations=value;}
    int count(){return retainedCount;}
    synchronized JsonNode request(String principal,JsonNode input){
        return request(principal,null,input);
    }
    synchronized JsonNode request(String principal,String mcpSession,JsonNode input){
        return request(principal,mcpSession,input,null);
    }
    synchronized JsonNode trustedCatalog(String principal,String mcpSession,JsonNode input){
        return request(principal,mcpSession,input,"ddl_inspection");
    }
    private JsonNode request(String principal,String mcpSession,JsonNode input,String trustedCategory){
        return request(principal,mcpSession,input,trustedCategory,false);
    }
    synchronized JsonNode readTool(String principal,String mcpSession,JsonNode input,boolean catalog,boolean allowReview){
        return request(principal,mcpSession,input,catalog?"ddl_inspection":null,!allowReview);
    }
    synchronized JsonNode planTool(String principal,String mcpSession,JsonNode input,boolean analyze,boolean allowReview){
        ObjectNode approval=((ObjectNode)input).deepCopy();String sql=Profiles.text(input,"sql",16384);approval.put("sql","EXPLAIN "+sql);
        return request(principal,mcpSession,approval,null,!allowReview,r->{
            r.value.put("planSql",sql).put("planAnalyze",analyze);r.value.set("planScope",r.scope.deepCopy());
            ObjectNode target=Profiles.JSON.createObjectNode();for(String key:List.of("bindingId","connectionId","connectionName","database","schema"))if(input.has(key))target.set(key,input.get(key));r.value.set("planRequest",target);
        });
    }
    synchronized JsonNode migration(String principal,String mcpSession,JsonNode input,MigrationPlans.Plan plan){
        ObjectNode request=plan.sourceRequest.deepCopy().put("requestId",Profiles.text(input,"requestId",100))
                .put("purpose",Profiles.text(input,"purpose",2000)).put("autoCommit",!plan.value.path("transactional").asBoolean());
        request.put("sql",String.join(";\n",Profiles.JSON.convertValue(plan.value.path("statements"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){})));
        request.set("parameters",Profiles.JSON.createArrayNode());
        JsonNode submitted=request(principal,mcpSession,request,null,false,null,true);
        Request retained=requests.get(submitted.path("id").asText());if(retained==null||!retained.state.equals("awaiting_approval"))throw new IllegalStateException("Migration review must require a fresh one-time approval");
        boolean rehearsal=plan.value.path("rehearsal").asBoolean();
        retained.value.put("type",rehearsal?"migration_rehearsal":"migration").put("migrationPlanId",plan.id).put("migrationPlanHash",plan.value.path("planHash").asText())
                .put("migrationSchemaFingerprint",plan.value.path("schemaFingerprint").asText()).put("migrationTransactional",plan.value.path("transactional").asBoolean());
        retained.value.set("migrationStatements",plan.value.path("statements").deepCopy());retained.value.set("migrationScope",plan.sourceScope.deepCopy());
        retained.value.put("migrationRehearsal",rehearsal);retained.value.set("migrationChecks",plan.value.path("checks").deepCopy());
        retained.operation=new ReusableOperation.Result("migration_application",false,false,"Complete migration plans require exact one-time review");
        retained.value.set("operation",retained.operation.json());retained.value.set("approvalChoices",choices(retained.operation,false));
        retained.value.put("transactionNotice",plan.value.path("transactional").asBoolean()?"Atomic transaction requested; connection loss during commit can still produce an uncertain outcome.":"This engine can auto-commit DDL. Execution stops at the first failure and reports acknowledged prior steps.");
        if(rehearsal)retained.value.put("rehearsalNotice","This exact disposable target will be mutated. Setup and fixtures are synthetic and bounded; the application will not delete the target afterward.");
        return status(retained,false);
    }
    private JsonNode request(String principal,String mcpSession,JsonNode input,String trustedCategory,boolean requireReusableRead){
        return request(principal,mcpSession,input,trustedCategory,requireReusableRead,null);
    }
    private JsonNode request(String principal,String mcpSession,JsonNode input,String trustedCategory,boolean requireReusableRead,java.util.function.Consumer<Request> initialize){
        return request(principal,mcpSession,input,trustedCategory,requireReusableRead,initialize,false);
    }
    private JsonNode request(String principal,String mcpSession,JsonNode input,String trustedCategory,boolean requireReusableRead,java.util.function.Consumer<Request> initialize,boolean forceExactReview){
        reap();Set<String> keys=Set.of("bindingId","connectionId","connectionName","database","schema","sql","parameters","purpose","autoCommit","requestId");input.fieldNames().forEachRemaining(k->{if(!keys.contains(k))throw new IllegalArgumentException("Unexpected live request field: "+k);});
        if(mcpSession!=null&&!reusable.sessions.alive(mcpSession,principal))throw new SecurityException("MCP session expired");
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
        for(Request r:requests.values())if(r.principal.equals(principal)&&Objects.equals(r.mcpSession,mcpSession)&&r.value.path("requestId").asText().equals(requestId)){if(!r.value.path("requestHash").asText().equals(hash))throw new IllegalArgumentException("Request ID was already used for different SQL or parameters");return status(r,false);}
        ObjectNode permissionScope=ApprovalScope.resolve(p,b,input);
        ReusableOperation.Result operation=trustedCategory==null?ReusableOperation.classify(sql,permissionScope):new ReusableOperation.Result(trustedCategory,!permissionScope.path("vendor").asText().equals("h2"),true,"Server-generated, scope-filtered catalog inspection; H2 requires one-time review");
        if(requests.size()+otherCount.getAsInt()>=32)throw new IllegalArgumentException("Approval queue is full; wait for completed requests to expire");
        ObjectNode value=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("requestId",requestId).put("requestHash",hash)
            .put("connectionId",b.path("connectionId").asText()).put("connectionName",p.path("name").asText()).put("database",b.path("database").asText()).put("schema",b.path("schema").asText())
            .put("type","live_sql").put("sql",sql).put("purpose",purpose).put("autoCommit",input.path("autoCommit").asBoolean(false)).put("createdAt",clock.getAsLong()).put("expiresAt",clock.getAsLong()+300000)
            .put("profileRevision",ProjectContexts.profileRevision(p));value.set("parameters",parameters.deepCopy());value.set("classification",classify(sql));
        if(bound)value.put("bindingId",bindingId).put("projectId",b.path("projectId").asText()).put("project",b.path("projectName").asText()).put("environment",b.path("environment").asText()).put("role",b.path("role").asText()).put("snapshotFingerprint",contexts.fingerprint(bindingId));
        else{value.put("database",input.path("database").asText("")).put("schema",input.path("schema").asText(""));value.putObject("target").put("connectionId",p.path("id").asText()).put("connectionName",p.path("name").asText()).put("database",permissionScope.path("database").asText("Saved connection default (no project binding)"));}
        value.set("operation",operation.json());value.set("permissionScope",ApprovalScope.display(permissionScope));value.put("agentName",agents.isTrustedLocal(principal)?"Trusted local agents":agents.agent(principal).path("name").asText());
        value.put("identityNotice",agents.isTrustedLocal(principal)?"Persistent grants are shared by Trusted local agents. Session grants apply only to this logical MCP session.":"Permissions belong to this agent identity; temporary grants apply only to this logical MCP session.");
        value.set("approvalChoices",choices(operation,mcpSession!=null&&reusable.sessions.alive(mcpSession,principal)));
        boolean verifiedRead=false;try{SqlReadGuard.validate(sql);verifiedRead=Set.of("postgresql","h2","mysql","mariadb").contains(p.path("templateId").asText());}catch(IllegalArgumentException ignored){}value.put("eligiblePersistentRead",verifiedRead).put("readCapability","query");
        value.put("scopeNotice",bound?"The schema binding scopes catalog discovery. Review every referenced target in live SQL; native statements and routines can affect objects outside that schema.":"Standalone connection: use the saved connection's configured database and schema. No project is required. Review every referenced target; qualified SQL and routines may affect other objects allowed by the database account.");
        value.put("transactionNotice","Rollback depends on the database and statement. DDL, explicit commits, routines and external effects may commit independently. Unknown outcomes are never automatically replayed.");
        ObjectNode matched=forceExactReview?null:reusable.match(principal,mcpSession,value,permissionScope,operation);
        if(requireReusableRead&&(!operation.readOnly()||matched==null))throw new SecurityException("No reusable read permission matches; enable an approval channel to review this request");
        Request r=new Request(value,principal,bound?contexts.hold(bindingId):()->{});r.mcpSession=mcpSession;r.scope=permissionScope;r.operation=operation;if(initialize!=null)initialize.accept(r);
        if(!capacity.tryAcquire()){r.release();throw new IllegalArgumentException("Approval queue is full");}try{record(r,"requested","");}catch(RuntimeException e){capacity.release();r.release();throw e;}requests.put(value.path("id").asText(),r);retainedCount=requests.size();
        if(matched!=null){usePolicy(r,matched);submit(r,"reusable_policy");}
        else if(!forceExactReview&&verifiedRead&&!input.has("database")&&!input.has("schema")&&agents.permitsRead(principal,"query",b)){value.put("authorizationReason","Legacy read policy (unchanged scope)");submit(r,"legacy_policy");}
        if(r.state.equals("awaiting_approval"))onPending.accept(value.path("id").asText());return status(r,false);
    }
    static ArrayNode choices(ReusableOperation.Result op,boolean sessionAlive){
        ArrayNode out=Profiles.JSON.createArrayNode();String[] labels={"Allow once","Always allow","Allow similar for this MCP session","Always allow similar"};String[] actions={"approve_once",ReusableApprovals.EXACT,ReusableApprovals.SESSION,ReusableApprovals.SIMILAR};
        for(int i=0;i<actions.length;i++)out.addObject().put("action",actions[i]).put("label",labels[i]).put("enabled",i==0||op.eligible()&&sessionAlive).put("reason",i==0?"Execute this request once":!op.eligible()?op.reason():!sessionAlive?"A live validated MCP session is required":op.reason()).put("lifetime",i==0?"once":i==2?"mcp_session":"until_revoked").put("match",i==0?"request":i==1?"exact":"category");return out;
    }
    private void usePolicy(Request r,JsonNode policy){r.policyId=policy.path("id").asText();r.value.set("matchedPolicy",policy.deepCopy());r.value.put("authorizationReason","Matched "+policy.path("match").asText()+" permission within the exact target scope");r.value.set("reusableScope",r.scope.deepCopy());r.value.put("reusableRead",r.operation.readOnly());}
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
        if(ReusableApprovals.ACTIONS.contains(action)){
            if(!acknowledged)throw new IllegalArgumentException("An explicit human approval decision is required");
            validate(r);if(!r.operation.eligible())throw new IllegalArgumentException("Reusable approval unavailable: "+r.operation.reason());
            record(r,"authorized_"+action,session);
            ObjectNode policy=reusable.grant(r.principal,r.mcpSession,action,r.value,r.scope,r.operation);usePolicy(r,policy);submit(r,session);return status(r,true);
        }
        if(!action.equals("approve_once"))throw new IllegalArgumentException("Use the scoped reusable approval choices; legacy SQL grants cannot be created by a new approval");
        if(!acknowledged)throw new IllegalArgumentException("Acknowledge the exact SQL, target and possible effects before approving");validate(r);
        record(r,"authorized",session);
        submit(r,session);
        return status(r,true);
    }
    private void submit(Request r,String session){record(r,"approved",session);r.state="approved";try{if(r.value.has("migrationPlanId"))migrations.consume(migrations.require("agent:"+r.principal,r.value.path("migrationPlanId").asText()));ObjectNode job=jobs.approved(r.principal,r.value,()->validate(r),()->{contexts.changed(r.value.path("connectionId").asText());r.release();});r.jobId=job.path("id").asText();r.state="submitted";record(r,"submitted",session);}catch(Exception e){r.state="failed_to_submit";r.release();record(r,"failed_to_submit",session);throw e;}}
    private void authorize(Request r){if(!agents.alive("agent:"+r.principal))throw new SecurityException("Agent revoked");if(r.value.has("bindingId"))contexts.authorized(r.principal,r.value.path("bindingId").asText(),true);}
    private void validate(Request r){
        if(r.mcpSession!=null&&!reusable.sessions.alive(r.mcpSession,r.principal))throw new IllegalArgumentException("Requesting MCP session expired; request approval again");
        String fingerprint=r.value.path("snapshotFingerprint").asText();if(!fingerprint.isEmpty()&&!fingerprint.equals(contexts.fingerprint(r.value.path("bindingId").asText())))throw new IllegalArgumentException("Catalog or database version changed since review; request approval again");authorize(r);if(!ProjectContexts.profileRevision(profiles.get(r.value.path("connectionId").asText())).equals(r.value.path("profileRevision").asText()))throw new IllegalArgumentException("Connection changed after request; submit a new request for review");
        if(r.value.has("bindingId")&&!r.scope.path("bindingRevision").asText().equals(CatalogScanner.hash(contexts.binding(r.value.path("bindingId").asText()).toString())))throw new IllegalArgumentException("Binding changed after request; request approval again");
        if(r.value.has("migrationPlanId")){
            if(migrations==null)throw new IllegalStateException("Migration planning service unavailable");
            migrations.verify("agent:"+r.principal,r.value.path("migrationPlanId").asText(),r.value.path("migrationPlanHash").asText(),r.scope);
        }
        if(r.policyId!=null)reusable.require(r.policyId,r.principal,r.mcpSession,r.value,r.scope,r.operation);
    }
    synchronized JsonNode cancel(String principal,String id){Request r=require(principal,id);if(r.state.equals("awaiting_approval")){r.state="cancelled";r.release();record(r,"cancelled","");}else if(!r.jobId.isEmpty()&&r.state.equals("submitted")){jobs.cancel(jobs.require("agent:"+principal,r.jobId));record(r,"cancellation_requested","");}return status(r,false);}
    private Request require(String principal,String id){Request r=requests.get(id);if(r==null||!r.principal.equals(principal))throw new SecurityException("Approval request is not owned by this agent");return r;}
    private ObjectNode status(Request r,boolean browser){ObjectNode out=r.value.deepCopy();out.remove(List.of("profileRevision","requestHash","reusableScope","migrationStatements","migrationScope","migrationChecks"));out.put("state",r.state);if(browser)out.put("agentId",r.principal);if(!r.jobId.isEmpty()){out.put("jobId",r.jobId);try{JsonNode job=jobs.status("agent:"+r.principal,r.jobId);if(browser){ObjectNode summary=((ObjectNode)job).deepCopy();summary.remove(List.of("result","exception"));out.set("job",summary);}else out.set("job",job);String state=job.path("state").asText();if(Set.of("complete","failed","cancelled").contains(state)&&!r.state.equals(state)){r.state=state;r.release();out.put("state",state);record(r,state,"");}}catch(Exception e){out.put("jobExpired",true);if(r.state.equals("submitted")){r.state="result_expired";r.release();out.put("state",r.state);record(r,r.state,"");}}}return out;}
    private void reap(){long now=clock.getAsLong();var iterator=requests.values().iterator();while(iterator.hasNext()){Request r=iterator.next();if(r.state.equals("awaiting_approval")&&(now>=r.value.path("expiresAt").asLong()||!agents.alive("agent:"+r.principal)||r.mcpSession!=null&&!reusable.sessions.alive(r.mcpSession,r.principal))){r.state="expired";r.release();record(r,"expired","");}if(now-r.value.path("createdAt").asLong()>3600000&&!r.state.equals("submitted")){r.release();iterator.remove();capacity.release();retainedCount=requests.size();}}}
    private void record(Request r,String action,String session){try{if(Files.exists(audit)&&Files.size(audit)>4L<<20)Files.move(audit,audit.resolveSibling("agent-approvals.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}ObjectNode row=Profiles.JSON.createObjectNode().put("at",clock.getAsLong()).put("request",r.value.path("id").asText()).put("agent",r.principal).put("binding",r.value.path("bindingId").asText()).put("connection",r.value.path("connectionId").asText()).put("action",action).put("humanSession",session).put("sqlHash",CatalogScanner.hash(r.value.path("sql").asText())).put("job",r.jobId).put("policy",r.policyId==null?"":r.policyId).put("category",r.operation==null?"":r.operation.category()).put("scopeHash",r.scope==null?"":CatalogScanner.hash(r.scope.toString()));Files.writeString(audit,row+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);}catch(Exception e){throw new IllegalStateException("Approval audit could not be written; execution is not authorized",e);}}
    synchronized void tick(){reap();for(Request r:requests.values())if(r.state.equals("submitted"))status(r,false);}
    synchronized void sessionEnded(String id){
        reusable.sessions.remove(id);
        for(Request r:requests.values())if(Objects.equals(id,r.mcpSession)){
            if(r.state.equals("awaiting_approval")){r.state="expired";r.release();record(r,"session_expired","");}
            else if(r.state.equals("submitted")&&!r.jobId.isEmpty())try{jobs.cancel(jobs.require("agent:"+r.principal,r.jobId));}catch(IllegalArgumentException ignored){}
        }
    }
    public synchronized void close(){requests.values().forEach(Request::release);capacity.release(requests.size());requests.clear();retainedCount=0;reusable.close();}
}
