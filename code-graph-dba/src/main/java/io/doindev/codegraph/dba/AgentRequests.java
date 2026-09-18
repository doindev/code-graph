package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Bounded, in-memory human approval queue for agent profile, binding, test and read requests. */
final class AgentRequests implements AutoCloseable {
    private Consumer<String> onPending=id->{};
    synchronized void onPending(Consumer<String> listener){onPending=listener;}
    private record Owner(String principal,long expires){}
    private final java.util.concurrent.ConcurrentHashMap<String,Owner> pendingOwners=new java.util.concurrent.ConcurrentHashMap<>();
    boolean alive(String owner){if(!owner.startsWith("approval:"))return false;Owner r=pendingOwners.get(owner.substring(9));return r!=null&&clock.getAsLong()<r.expires&&agents.alive("agent:"+r.principal);}
    static final Set<String> TYPES=Set.of("connection_create","connection_update","connection_delete","connection_test","connection_details","binding_create","binding_update","binding_delete");
    private final Profiles profiles;private final Connections connections;private final ConnectionSetup setup;private final ProjectContexts contexts;private final AgentAccess agents;private final QueryJobs jobs;private final LongSupplier clock;private final Path audit;
    private final Map<String,Request> requests=new LinkedHashMap<>();private IntSupplier otherCount=()->0;
    static final class Request{
        final ObjectNode value,payload;final String principal,hash;String state="awaiting_approval",jobId="";JsonNode result;
        Request(ObjectNode value,ObjectNode payload,String principal,String hash){this.value=value;this.payload=payload;this.principal=principal;this.hash=hash;}
        void clear(){scrub(payload);}
    }
    AgentRequests(Profiles p,Connections c,ConnectionSetup s,ProjectContexts pc,AgentAccess a,QueryJobs j){this(p,c,s,pc,a,j,System::currentTimeMillis);}
    AgentRequests(Profiles p,Connections c,ConnectionSetup s,ProjectContexts pc,AgentAccess a,QueryJobs j,LongSupplier clock){profiles=p;connections=c;setup=s;contexts=pc;agents=a;jobs=j;this.clock=clock;audit=p.directory().resolve("agent-administration-approvals.jsonl");}
    synchronized void otherCount(IntSupplier value){otherCount=value;}
    private volatile int retainedCount;
    private java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(32);
    synchronized void capacity(java.util.concurrent.Semaphore shared){if(!requests.isEmpty())throw new IllegalStateException("Approval capacity must be set before requests");capacity=shared;}
    int count(){return retainedCount;}
    synchronized boolean has(String id){return requests.containsKey(id);}
    synchronized JsonNode request(String principal,String type,JsonNode input){
        reap();if(!TYPES.contains(type))throw new IllegalArgumentException("Unsupported approval request type");
        String requestId=Profiles.text(input,"requestId",100),purpose=Profiles.text(input,"purpose",2000);
        ObjectNode payload=input.deepCopy();String hash=CatalogScanner.hash(payload.toString());
        for(Request r:requests.values())if(r.principal.equals(principal)&&r.value.path("requestId").asText().equals(requestId)){if(!r.hash.equals(hash)||!r.value.path("type").asText().equals(type))throw new IllegalArgumentException("Request ID was already used for different content");return status(r,false);}
        if(requests.size()+otherCount.getAsInt()>=32)throw new IllegalArgumentException("Approval queue is full; wait for completed requests to expire");
        ObjectNode value=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("type",type).put("requestId",requestId).put("purpose",purpose).put("createdAt",clock.getAsLong()).put("expiresAt",clock.getAsLong()+300_000).put("mutation",mutation(type));
        prepare(type,payload,value);
        if(mutation(type))value.set("approvalChoices",ApprovalQueue.choices(new ReusableOperation.Result("administration",false,false,"Administrative changes require one-time detailed approval"),false));
        Request r=new Request(value,payload,principal,hash);if(!capacity.tryAcquire()){r.clear();throw new IllegalArgumentException("Approval queue is full");}try{record(r,"requested","");}catch(RuntimeException e){capacity.release();r.clear();throw e;}requests.put(value.path("id").asText(),r);retainedCount=requests.size();pendingOwners.put(value.path("id").asText(),new Owner(principal,value.path("expiresAt").asLong()));onPending.accept(value.path("id").asText());return status(r,false);
    }
    private void prepare(String type,ObjectNode payload,ObjectNode value){
        if(type.startsWith("binding_")){
            ObjectNode proposed=payload.path("binding").isObject()?(ObjectNode)payload.path("binding"):payload;
            if(type.equals("binding_create")){value.set("after",bindingSummary(proposed));}
            else{ObjectNode before=contexts.binding(Profiles.text(payload,"bindingId",36));value.set("before",before);if(type.equals("binding_update"))value.set("after",bindingSummary(proposed));value.put("bindingId",before.path("id").asText()).put("connectionId",before.path("connectionId").asText()).put("projectId",before.path("projectId").asText()).put("environment",before.path("environment").asText()).put("role",before.path("role").asText()).put("targetRevision",ProjectContexts.profileRevision(before));}
            return;
        }
        ObjectNode target=resolveTarget(payload,type.equals("connection_create"));value.set("target",target);
        if(type.equals("connection_create")||type.equals("connection_update")){
            JsonNode supplied=payload.path("profile");if(!supplied.isObject())throw new IllegalArgumentException("profile must be an object");
            String id=type.equals("connection_update")?Profiles.text(payload,"connectionId",36):null;
            if(needsDriver(supplied)&&payload.path("driverInstall").isObject()){ObjectNode install=driverInstall(payload.path("driverInstall"),supplied.path("templateId").asText("custom"));value.set("driverInstall",install.deepCopy());value.put("requiresDriverInstall",true);value.set("after",preliminaryProfile(supplied,install));}
            else{ConnectionDraft draft=profiles.draft(id,supplied);try{payload.set("profile",canonicalInput(draft));value.set("after",Profiles.agentProfile(draft.profile()));}finally{draft.clear();}}
            if(id!=null){ObjectNode current=profiles.get(id);value.set("before",Profiles.agentProfile(current));value.put("targetRevision",ProjectContexts.profileRevision(current));}
            value.put("requiresSuccessfulTestOrSaveUntested",true);
        }else if(type.equals("connection_delete")){ObjectNode current=profiles.get(target.path("connectionId").asText());value.set("before",Profiles.agentProfile(current));value.put("targetRevision",ProjectContexts.profileRevision(current)).put("destructive",true);}
        else if(type.equals("connection_details")||type.equals("connection_test")){value.put("eligiblePersistentRead",true).put("readCapability",type.equals("connection_details")?"connection_details":"connection_test");if(target.has("bindingId")){ObjectNode b=contexts.binding(target.path("bindingId").asText());value.put("projectId",b.path("projectId").asText()).put("environment",b.path("environment").asText()).put("role",b.path("role").asText());}}
    }
    private ObjectNode resolveTarget(JsonNode input,boolean create){
        ObjectNode out=Profiles.JSON.createObjectNode();if(create)return out;
        if(input.hasNonNull("bindingId")){ObjectNode b=contexts.binding(Profiles.text(input,"bindingId",36));out.put("bindingId",b.path("id").asText()).put("connectionId",b.path("connectionId").asText()).put("connectionName",profiles.get(b.path("connectionId").asText()).path("name").asText());return out;}
        String id=Profiles.text(input,"connectionId",36),name=Profiles.text(input,"connectionName",120);ObjectNode p=profiles.get(id);if(!Profiles.nameKey(p.path("name").asText()).equals(Profiles.nameKey(name)))throw new SecurityException("Connection name does not match its stable ID");return out.put("connectionId",id).put("connectionName",p.path("name").asText());
    }
    synchronized JsonNode maybeRead(String principal,String type,JsonNode input){
        ObjectNode target=resolveTarget(input,false),binding=target.has("bindingId")?contexts.binding(target.path("bindingId").asText()):Profiles.JSON.createObjectNode().put("connectionId",target.path("connectionId").asText());
        String capability=type.equals("connection_details")?"connection_details":"connection_test";
        if(agents.permitsRead(principal,capability,binding))return executeRead(principal,type,target);
        return request(principal,type,input);
    }
    private JsonNode executeRead(String principal,String type,ObjectNode target){
        String connection=target.path("connectionId").asText();if(type.equals("connection_details")){ObjectNode out=Profiles.agentProfile(profiles.get(connection));out.set("bindings",contexts.bindingsForConnection(connection));return out;}
        return jobs.test("agent:"+principal,connection);
    }
    synchronized JsonNode testDraft(String session,String id){
        reap();Request r=requests.get(id);if(r==null||!Set.of("connection_create","connection_update").contains(r.value.path("type").asText())||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Connection proposal is not awaiting review");
        if(!r.jobId.isEmpty()){JsonNode previous=jobs.status("approval:"+id,r.jobId);if(!Set.of("failed","cancelled").contains(previous.path("state").asText()))return status(r,true);jobs.remove("approval:"+id,r.jobId);r.jobId="";}ObjectNode draft=((ObjectNode)r.payload.path("profile")).deepCopy();if(r.value.path("type").asText().equals("connection_update"))draft.put("connectionId",r.value.path("target").path("connectionId").asText());draft.put("confirmDriverEffects",true);
        ObjectNode job=setup.operation("approval:"+id,"draft-test",draft);r.jobId=job.path("id").asText();record(r,"test_started",session);return status(r,true);
    }
    synchronized JsonNode reviewDraft(String session,String id){
        reap();Request r=requests.get(id);if(r==null||!Set.of("connection_create","connection_update").contains(r.value.path("type").asText())||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Connection proposal is not awaiting review");
        if(r.value.path("requiresDriverInstall").asBoolean()){ObjectNode out=((ObjectNode)r.value.path("after")).deepCopy();out.set("driverBundle",r.value.path("driverInstall").deepCopy());out.putArray("jars");ArrayNode names=out.putArray("secretPropertyNames");r.payload.path("profile").path("secretProperties").fieldNames().forEachRemaining(names::add);out.put("hasCredential",r.payload.path("profile").has("password")).put("proposalId",id).put("proposalType",r.value.path("type").asText());return out;}
        String existing=r.value.path("type").asText().equals("connection_update")?r.value.path("target").path("connectionId").asText():null;ConnectionDraft draft=profiles.draft(existing,r.payload.path("profile"));
        try{ObjectNode out=Profiles.publicProfile(draft.profile());if(existing!=null)out.put("id",existing);ArrayNode names=out.putArray("secretPropertyNames");draft.secret().path("properties").fieldNames().forEachRemaining(names::add);out.put("hasCredential",draft.secret().has("password"));out.put("proposalId",id).put("proposalType",r.value.path("type").asText());return out;}finally{draft.clear();}
    }
    synchronized JsonNode reviseDraft(String session,String id,JsonNode input){
        reap();Request r=requests.get(id);if(r==null||!Set.of("connection_create","connection_update").contains(r.value.path("type").asText())||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Connection proposal is not awaiting review");
        if(!input.isObject())throw new IllegalArgumentException("Connection proposal draft must be an object");String existing=r.value.path("type").asText().equals("connection_update")?r.value.path("target").path("connectionId").asText():null;
        ObjectNode merged=mergeEdited(r,input);
        ConnectionDraft draft=profiles.draft(existing,merged);try{r.payload.set("profile",canonicalInput(draft));r.value.put("reviewVersion",r.value.path("reviewVersion").asInt()+1);r.value.set("after",Profiles.agentProfile(draft.profile()));r.value.remove(List.of("requiresDriverInstall","driverInstall"));r.payload.remove("driverInstall");}finally{draft.clear();}
        r.payload.remove(List.of("reviewerReceipt","reviewerSaveUntested","reviewerSession"));if(input.hasNonNull("receipt")){r.payload.put("reviewerReceipt",input.path("receipt").asText()).put("reviewerSession",session);}if(input.path("saveUntested").asBoolean())r.payload.put("reviewerSaveUntested",true).put("reviewerSession",session);
        if(!r.jobId.isEmpty()){try{jobs.cancel(jobs.require("approval:"+id,r.jobId));}catch(Exception ignored){}r.jobId="";}record(r,"proposal_revised",session);return status(r,true);
    }
    private static ObjectNode mergeEdited(Request r,JsonNode input){
        ObjectNode merged=((ObjectNode)r.payload.path("profile")).deepCopy();input.fields().forEachRemaining(e->{String key=e.getKey();if(!Set.of("password","removePassword","secretProperties","receipt","saveUntested").contains(key))merged.set(key,e.getValue().deepCopy());});
        if(input.has("password"))merged.set("password",input.path("password").deepCopy());if(input.path("removePassword").asBoolean()){merged.remove("password");merged.put("removePassword",true);}
        if(input.path("secretProperties").isObject()){ObjectNode secrets=merged.withObject("secretProperties");input.path("secretProperties").fields().forEachRemaining(e->{secrets.set(e.getKey(),e.getValue().deepCopy());});}
        return merged;
    }
    synchronized JsonNode testEditedDraft(String session,String id,JsonNode input){
        reap();Request r=requests.get(id);if(r==null||!typeNeedsReceipt(r)||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Connection proposal is not awaiting review");
        ObjectNode merged=mergeEdited(r,input);
        if(r.value.path("type").asText().equals("connection_update"))merged.put("connectionId",r.value.path("target").path("connectionId").asText());else merged.remove("connectionId");
        return setup.operation(session,"draft-test",merged);
    }
    synchronized JsonNode decide(String session,String id,String action,boolean acknowledged,JsonNode options){
        reap();Request r=requests.get(id);if(r==null||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Approval request expired or already consumed");
        if(action.equals("reject")){record(r,"rejected",session);r.state="rejected";pendingOwners.remove(id);r.clear();return status(r,true);}
        if(!acknowledged)throw new IllegalArgumentException("Acknowledge the exact target, diff and possible effects before approving");
        if(action.equals("always_environment_read"))throw new IllegalArgumentException("New approvals cannot include other or future bindings; use an exact target permission");
        boolean persistent=action.startsWith("always_");if(persistent&&!r.value.path("eligiblePersistentRead").asBoolean())throw new IllegalArgumentException("Persistent approval is available only for verified read requests");
        if(!action.equals("approve_once")&&!persistent)throw new IllegalArgumentException("Unsupported approval decision");
        validate(r);
        record(r,"authorized",session);
        try{
            if(persistent){ObjectNode target=(ObjectNode)r.value.path("target");JsonNode policyTarget=target.has("bindingId")?contexts.binding(target.path("bindingId").asText()):Profiles.JSON.createObjectNode().put("connectionId",target.path("connectionId").asText());agents.grantRead(r.principal,action,r.value.path("readCapability").asText(),policyTarget);}
            r.result=apply(session,r,options);pendingOwners.remove(id);r.state=r.jobId.isEmpty()?"complete":"submitted";record(r,action,session);if(r.jobId.isEmpty())r.clear();return status(r,true);
        }catch(Exception e){r.state="failed";pendingOwners.remove(id);r.clear();record(r,"failed",session);throw e instanceof RuntimeException re?re:new IllegalArgumentException(e.getMessage(),e);}
    }
    private JsonNode apply(String session,Request r,JsonNode options)throws Exception{
        String type=r.value.path("type").asText();
        if(type.equals("connection_details")||type.equals("connection_test")){JsonNode result=executeRead(r.principal,type,(ObjectNode)r.value.path("target"));if(type.equals("connection_test")&&result.path("id").isTextual())r.jobId=result.path("id").asText();return result;}
        if(type.equals("binding_create"))return contexts.save(r.payload.path("binding").isObject()?r.payload.path("binding"):r.payload);
        if(type.equals("binding_update")){ObjectNode draft=(r.payload.path("binding").isObject()?(ObjectNode)r.payload.path("binding"):r.payload).deepCopy();draft.put("id",r.value.path("bindingId").asText());return contexts.save(draft);}
        if(type.equals("binding_delete")){String id=r.value.path("bindingId").asText();contexts.remove(id);agents.removeBinding(id);return Profiles.JSON.createObjectNode().put("removed",true).put("bindingId",id);}
        if(type.equals("connection_delete")){String id=r.value.path("target").path("connectionId").asText();connections.remove(id);contexts.removeConnection(id);agents.removeConnection(id);profiles.remove(id);return Profiles.JSON.createObjectNode().put("removed",true).put("connectionId",id);}
        String existing=type.equals("connection_update")?r.value.path("target").path("connectionId").asText():null;ObjectNode draft=((ObjectNode)r.payload.path("profile")).deepCopy();String receipt=successfulReceipt(r),owner="approval:"+r.value.path("id").asText();if(receipt.isEmpty()&&r.payload.hasNonNull("reviewerReceipt")&&session.equals(r.payload.path("reviewerSession").asText())){receipt=r.payload.path("reviewerReceipt").asText();owner=session;}if(!receipt.isEmpty())draft.put("receipt",receipt);else if(options.path("saveUntested").asBoolean()||r.payload.path("reviewerSaveUntested").asBoolean())draft.put("saveUntested",true);else throw new IllegalArgumentException("Test this unchanged proposal successfully or explicitly choose Save untested");
        ObjectNode saved=setup.save(owner,existing,draft);if(existing!=null)connections.remove(existing);profiles.markAgentCreated(saved.path("id").asText(),r.principal,r.value.path("requestId").asText());
        ObjectNode result=Profiles.agentProfile(profiles.get(saved.path("id").asText())).put("creatorReceivesAccess",false);
        if(type.equals("connection_create")&&r.payload.path("binding").isObject()){ObjectNode binding=(ObjectNode)r.payload.path("binding").deepCopy();binding.put("connectionId",saved.path("id").asText());result.set("binding",contexts.save(binding));}
        if(!r.jobId.isEmpty()){try{jobs.remove("approval:"+r.value.path("id").asText(),r.jobId);}catch(IllegalArgumentException ignored){}r.jobId="";}
        return result;
    }
    private String successfulReceipt(Request r){if(r.jobId.isEmpty())return "";try{JsonNode job=jobs.status("approval:"+r.value.path("id").asText(),r.jobId);return job.path("state").asText().equals("complete")?job.path("result").path("receipt").asText():"";}catch(Exception e){return "";}}
    private void validate(Request r){
        if(!agents.alive("agent:"+r.principal))throw new SecurityException("Agent revoked");String type=r.value.path("type").asText();
        if(r.value.path("requiresDriverInstall").asBoolean())throw new IllegalArgumentException("Review the proposal and explicitly install or select the requested JDBC driver before approval");
        if(type.startsWith("connection_")&&!type.equals("connection_create")){ObjectNode current=profiles.get(r.value.path("target").path("connectionId").asText());if(r.value.has("targetRevision")&&!ProjectContexts.profileRevision(current).equals(r.value.path("targetRevision").asText()))throw new IllegalArgumentException("Connection changed after review; submit a new request");}
        if(type.startsWith("binding_")&&!type.equals("binding_create")){ObjectNode current=contexts.binding(r.value.path("bindingId").asText());if(!ProjectContexts.profileRevision(current).equals(r.value.path("targetRevision").asText()))throw new IllegalArgumentException("Binding changed after review; submit a new request");}
    }
    synchronized JsonNode list(){reap();ArrayNode out=Profiles.JSON.createArrayNode();for(Request r:requests.values())out.add(status(r,true));return out;}
    synchronized JsonNode get(String principal,String id){reap();Request r=require(principal,id);return status(r,false);}
    synchronized JsonNode cancel(String principal,String id){Request r=require(principal,id);if(r.state.equals("awaiting_approval")){r.state="cancelled";pendingOwners.remove(id);r.clear();record(r,"cancelled","");}else if(r.state.equals("submitted")&&!r.jobId.isEmpty()){try{jobs.cancel(jobs.require(jobOwner(r),r.jobId));}catch(Exception ignored){}record(r,"cancellation_requested","");}return status(r,false);}
    private String jobOwner(Request r){return r.value.path("type").asText().equals("connection_test")?"agent:"+r.principal:"approval:"+r.value.path("id").asText();}
    private Request require(String principal,String id){Request r=requests.get(id);if(r==null||!r.principal.equals(principal))throw new SecurityException("Approval request is not owned by this agent");return r;}
    private ObjectNode status(Request r,boolean browser){ObjectNode out=r.value.deepCopy().put("state",r.state);if(browser)out.put("agentId",r.principal);if(r.result!=null)out.set("result",r.result.deepCopy());if(!r.jobId.isEmpty()){out.put("jobId",r.jobId);try{JsonNode job=jobs.status(jobOwner(r),r.jobId);ObjectNode safe=job.deepCopy();if(browser&&safe.path("result").has("receipt"))((ObjectNode)safe.path("result")).remove("receipt");out.set("job",safe);String state=job.path("state").asText();if(Set.of("complete","failed","cancelled").contains(state)&&r.state.equals("submitted")){r.state=state;out.put("state",state);if(!typeNeedsReceipt(r)||!state.equals("complete"))r.clear();record(r,state,"");}}catch(Exception e){out.put("jobExpired",true);}}return out;}
    private static boolean typeNeedsReceipt(Request r){return Set.of("connection_create","connection_update").contains(r.value.path("type").asText());}
    private void reap(){long now=clock.getAsLong();var it=requests.values().iterator();while(it.hasNext()){Request r=it.next();if(r.state.equals("awaiting_approval")&&(now>=r.value.path("expiresAt").asLong()||!agents.alive("agent:"+r.principal))){r.state="expired";pendingOwners.remove(r.value.path("id").asText());r.clear();record(r,"expired","");}if(now-r.value.path("createdAt").asLong()>3_600_000&&!r.state.equals("submitted")){r.clear();it.remove();capacity.release();retainedCount=requests.size();}}}
    synchronized void tick(){reap();for(Request r:requests.values())if(r.state.equals("submitted"))status(r,false);}
    private void record(Request r,String action,String session){try{if(Files.exists(audit)&&Files.size(audit)>4L<<20)Files.move(audit,audit.resolveSibling("agent-administration-approvals.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}ObjectNode row=Profiles.JSON.createObjectNode().put("at",clock.getAsLong()).put("request",r.value.path("id").asText()).put("agent",r.principal).put("type",r.value.path("type").asText()).put("action",action).put("humanSession",session).put("requestHash",r.hash);Files.writeString(audit,row+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);}catch(Exception e){throw new IllegalStateException("Approval audit could not be written; operation is not authorized",e);}}
    private static boolean mutation(String type){return !Set.of("connection_details","connection_test").contains(type);}
    private static ObjectNode bindingSummary(JsonNode n){ObjectNode out=Profiles.JSON.createObjectNode();for(String key:List.of("id","projectId","connectionId","database","schema","environment","role","purpose","scanIntervalSeconds","idleTimeoutSeconds","enabled"))if(n.has(key))out.set(key,n.path(key).deepCopy());return out;}
    private static ObjectNode canonicalInput(ConnectionDraft draft){ObjectNode out=draft.profile().deepCopy().put("replaceSecretProperties",true).put("removePassword",!draft.secret().has("password"));if(draft.secret().has("password"))out.set("password",draft.secret().path("password").deepCopy());if(draft.secret().path("properties").isObject())out.set("secretProperties",draft.secret().path("properties").deepCopy());return out;}
    private static boolean needsDriver(JsonNode profile){return (!profile.path("jars").isArray()||profile.path("jars").isEmpty())&&!profile.hasNonNull("jar");}
    private static ObjectNode driverInstall(JsonNode input,String template){String group=Profiles.text(input,"groupId",200),artifact=Profiles.text(input,"artifactId",200),version=input.path("version").asText("latest").strip();if(!group.matches("[A-Za-z0-9_.-]+")||!artifact.matches("[A-Za-z0-9_.-]+")||!version.matches("latest|[A-Za-z0-9_.+\\-]+"))throw new IllegalArgumentException("Invalid Maven driver coordinates");return Profiles.JSON.createObjectNode().put("templateId",template).put("groupId",group).put("artifactId",artifact).put("version",version).put("source","agent-requested Maven installation");}
    private static ObjectNode preliminaryProfile(JsonNode input,ObjectNode install){String template=input.path("templateId").asText("custom");DatabaseCatalog.get(template);String url=Profiles.text(input,"url",8192),driver=Profiles.text(input,"driverClass",200);if(!url.startsWith("jdbc:")||url.matches("(?is).*(password|passwd|pwd|secret|token|credential|privatekey|private_key)=.*"))throw new IllegalArgumentException("JDBC URL must not contain credentials");if(!driver.matches("[A-Za-z_$][A-Za-z0-9_$.]+"))throw new IllegalArgumentException("Invalid driver class");ObjectNode out=Profiles.JSON.createObjectNode().put("name",Profiles.text(input,"name",120)).put("templateId",template).put("url",url).put("driverClass",driver).put("color",input.path("color").asText("transparent")).put("readOnly",input.path("readOnly").asBoolean(true));if(input.has("username"))out.put("username",input.path("username").asText());ObjectNode properties=out.putObject("properties");ArrayNode hidden=out.putArray("writeOnlyPropertyNames");input.path("properties").fields().forEachRemaining(e->{if(DatabaseCatalog.knownPublic(template,e.getKey()))properties.set(e.getKey(),e.getValue().deepCopy());else hidden.add(e.getKey());});input.path("secretProperties").fieldNames().forEachRemaining(hidden::add);if(input.path("pool").isObject())out.set("pool",input.path("pool").deepCopy());out.set("driverInstall",install.deepCopy());out.put("hasCredential",input.has("password"));return out;}
    private static void scrub(JsonNode n){if(n instanceof ObjectNode o){o.fields().forEachRemaining(e->{if(e.getValue().isContainerNode())scrub(e.getValue());});for(String key:List.of("password","passphrase","secretProperties","credentials","privateKey","private_key","private_key_file","private_key_pwd"))if(o.has(key)){JsonNode value=o.remove(key);if(value instanceof ObjectNode child)child.removeAll();}}else if(n instanceof ArrayNode a)a.forEach(AgentRequests::scrub);}
    public synchronized void close(){requests.values().forEach(Request::clear);capacity.release(requests.size());requests.clear();retainedCount=0;pendingOwners.clear();}
}
