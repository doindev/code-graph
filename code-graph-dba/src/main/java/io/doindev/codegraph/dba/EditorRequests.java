package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Bounded, single-use collaboration consent. Never grants database or file permissions. */
final class EditorRequests implements AutoCloseable {
    private static final Set<String> PENDING=Set.of("awaiting_approval","awaiting_browser");
    private static final long TTL=300_000;
    private static final class Request {
        final String id=UUID.randomUUID().toString(),principal,session,clientId,purpose;
        final long created,expires;
        String state="awaiting_approval",single="",ticket="",detail="",pairId="";
        boolean browserConsent;
        Request(String p,String s,String client,String purpose,long now){principal=p;session=s;clientId=client;this.purpose=purpose;created=now;expires=now+TTL;}
    }
    private final BrowserAuth auth;
    private final EditorPairings pairings;
    private final BiPredicate<String,String> alive;
    private final BooleanSupplier desktopAvailable;
    private final Function<URI,Boolean> launch;
    private final Function<String,String> agentName;
    private final LongSupplier clock;
    private final Path audit;
    private final Map<String,Request> requests=new LinkedHashMap<>();
    private final ExecutorService launcher=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("editor-browser-launch").factory());
    private Semaphore capacity=new Semaphore(32);
    private Runnable changed=()->{};
    private volatile URI address;
    EditorRequests(BrowserAuth auth,EditorPairings pairings,BiPredicate<String,String> alive,BooleanSupplier desktopAvailable,Function<URI,Boolean> launch,Function<String,String> agentName,Path directory){
        this(auth,pairings,alive,desktopAvailable,launch,agentName,directory,System::currentTimeMillis);
    }
    EditorRequests(BrowserAuth auth,EditorPairings pairings,BiPredicate<String,String> alive,BooleanSupplier desktopAvailable,Function<URI,Boolean> launch,Function<String,String> agentName,Path directory,LongSupplier clock){
        this.auth=auth;this.pairings=pairings;this.alive=alive;this.desktopAvailable=desktopAvailable;this.launch=launch;this.agentName=agentName;this.clock=clock;audit=directory.resolve("editor-consent.jsonl");
    }
    void configure(Semaphore shared,Runnable notify){capacity=shared;changed=notify;}
    void address(URI uri){address=uri;}
    synchronized boolean has(String id){return requests.containsKey(id);}
    private void requireSession(String principal,String session){if(session==null||!alive.test(session,principal))throw new SecurityException("A live logical MCP session is required");}
    synchronized ObjectNode request(String principal,String session,JsonNode input){
        reap();requireSession(principal,session);
        String client=Profiles.text(input,"requestId",100),purpose=Profiles.text(input,"purpose",500);
        for(Request r:requests.values())if(r.session.equals(session)&&r.clientId.equals(client)){if(!r.purpose.equals(purpose))throw new IllegalArgumentException("requestId already used for a different proposal");return view(r);}
        if(pairings.sessionPaired(session))throw new IllegalArgumentException("This MCP session is already paired; use its editor tools or ask the user to disconnect");
        if(requests.values().stream().anyMatch(r->r.session.equals(session)&&PENDING.contains(r.state)))throw new IllegalArgumentException("An editor access request is already pending for this MCP session");
        if(requests.size()>=64||!capacity.tryAcquire())throw new IllegalArgumentException("Approval queue is full; wait for an existing request");
        Request r=new Request(principal,session,client,purpose,clock.getAsLong());r.detail=auth.responsiveTabs().size()+" responsive DBA tab(s) detected. Existing tabs are selected by you; opening a new workspace does not close existing work.";
        r.browserConsent=!desktopAvailable.getAsBoolean();requests.put(r.id,r);changed.run();return view(r);
    }
    synchronized ObjectNode status(String principal,String session,String id){reap();Request r=owned(principal,session,id);return view(r);}
    synchronized ObjectNode cancel(String principal,String session,String id){reap();Request r=owned(principal,session,id);if(PENDING.contains(r.state))finish(r,"cancelled");return view(r);}
    private Request owned(String principal,String session,String id){requireSession(principal,session);Request r=requests.get(id);if(r==null||!r.principal.equals(principal)||!r.session.equals(session))throw new SecurityException("Editor request is unavailable to this MCP session");return r;}
    synchronized ArrayNode pending(){reap();ArrayNode out=Profiles.JSON.createArrayNode();for(Request r:requests.values())if(r.state.equals("awaiting_approval")&&!r.browserConsent)out.add(view(r));return out;}
    synchronized ObjectNode decide(String id,String action){
        reap();Request r=requests.get(id);if(r==null||!r.state.equals("awaiting_approval"))throw new IllegalArgumentException("Editor request expired or already decided");requireSession(r.principal,r.session);
        if(action.equals("reject")){record(r,"deny","");finish(r,"denied");return view(r);}
        if(!Set.of("editor_existing","editor_new").contains(action))throw new IllegalArgumentException("Choose an existing workspace or open a new one; reusable SQL approvals cannot authorize pairing");
        record(r,action,"");r.state="awaiting_browser";r.browserConsent=false;
        if(action.equals("editor_existing")){
            List<String> candidates=auth.responsiveTabs().stream().filter(t->!pairings.occupied(t)).toList();
            if(candidates.size()==1)r.single=candidates.getFirst();
            r.detail=candidates.isEmpty()?"Open or return to an existing /dba tab, then choose Use this /dba instance.":"Waiting for the selected browser workspace.";
        }else{
            if(address==null){r.detail="Browser address is unavailable. Open /dba manually and choose this instance.";return view(r);}
            r.ticket=BrowserAuth.token();URI uri=address.resolve("/dba#editor-handoff="+r.ticket);String ticket=r.ticket;
            launcher.execute(()->{synchronized(this){if(!r.ticket.equals(ticket)||!PENDING.contains(r.state))return;}boolean ok=false;try{ok=launch.apply(uri);}catch(RuntimeException ignored){}
                synchronized(this){if(!ok&&r.ticket.equals(ticket)&&PENDING.contains(r.state)){r.ticket="";r.detail="Browser launch failed. Open /dba manually and choose Use this /dba instance.";}}
            });
            r.detail="Opening a new DBA workspace; waiting for its authenticated handshake.";
        }
        return view(r);
    }
    synchronized ObjectNode offers(String workspace){
        reap();ObjectNode out=Profiles.JSON.createObjectNode();out.set("pairing",pairings.status(workspace));ArrayNode offers=out.putArray("requests");
        if(pairings.occupied(workspace))return out;
        for(Request r:requests.values())if((r.state.equals("awaiting_browser")&&r.ticket.isEmpty()&&(r.single.isEmpty()||r.single.equals(workspace)))||(r.state.equals("awaiting_approval")&&r.browserConsent)){
            ObjectNode offer=view(r);offer.put("autoAccept",r.single.equals(workspace)&&auth.tabResponsive(workspace));offers.add(offer);
        }
        return out;
    }
    synchronized ObjectNode accept(String workspace,String id,String ticket){
        reap();Request r=requests.get(id);
        if(ticket!=null&&!ticket.isEmpty())r=requests.values().stream().filter(q->!q.ticket.isEmpty()&&BrowserAuth.equal(q.ticket,ticket)).findFirst().orElse(null);
        if(r==null||!PENDING.contains(r.state))throw new IllegalArgumentException("This request expired or another DBA tab was selected");
        requireSession(r.principal,r.session);
        if(!workspace.contains(":")||!auth.tabResponsive(workspace))throw new SecurityException("Return to an explicitly identified DBA tab before accepting");
        if(!r.ticket.isEmpty()){if(!BrowserAuth.equal(r.ticket,ticket))throw new SecurityException("New-workspace handoff required");}
        else if(!(r.state.equals("awaiting_browser")||r.browserConsent)||!r.single.isEmpty()&&!r.single.equals(workspace))throw new SecurityException("This DBA tab was not selected");
        if(pairings.occupied(workspace)||pairings.sessionPaired(r.session))throw new IllegalArgumentException("Workspace or MCP session is already paired; disconnect first");
        record(r,"pair",workspace);
        ObjectNode paired=pairings.connect(r.principal,r.session,workspace);r.pairId=paired.path("pairId").asText();finish(r,"paired");return view(r);
    }
    synchronized ObjectNode reject(String workspace,String id){reap();Request r=requests.get(id);if(r==null||!PENDING.contains(r.state))throw new IllegalArgumentException("Request already resolved");boolean offered=false;for(JsonNode offer:offers(workspace).path("requests"))if(offer.path("id").asText().equals(id))offered=true;if(!offered)throw new SecurityException("Request is not offered to this workspace");record(r,"deny",workspace);finish(r,"denied");return view(r);}
    private ObjectNode view(Request r){
        ObjectNode out=Profiles.JSON.createObjectNode().put("id",r.id).put("approvalId",r.id).put("type","editor_pairing").put("state",r.state)
            .put("agentId",r.principal).put("agentName",agentName.apply(r.principal)).put("purpose",r.purpose).put("createdAt",r.created).put("expiresAt",r.expires)
            .put("mutation",false).put("eligiblePersistentRead",false).put("scopeNotice","One selected DBA tab, this MCP session only. Read Script text and create/edit unsaved Script drafts with revision checks. No SQL execution, file saving, credentials or database permissions.")
            .put("detail",r.detail).put("databasePermissionsGranted",false).put("approvalChannel",r.browserConsent?"browser":"desktop");
        if(!r.pairId.isEmpty())out.put("pairId",r.pairId);
        return out;
    }
    private void finish(Request r,String state){if(PENDING.contains(r.state))capacity.release();r.state=state;r.ticket="";r.single="";}
    synchronized void reap(){
        long now=clock.getAsLong();
        for(Request r:requests.values())if(PENDING.contains(r.state)){if(now>=r.expires)finish(r,"expired");else if(!alive.test(r.session,r.principal))finish(r,"cancelled");else{if(r.state.equals("awaiting_approval")&&!desktopAvailable.getAsBoolean())r.browserConsent=true;if(!r.single.isEmpty()&&!auth.tabResponsive(r.single))r.single="";}}
        requests.values().removeIf(r->!PENDING.contains(r.state)&&now-r.expires>3_600_000);
    }
    private void record(Request r,String action,String workspace){
        try{
            if(Files.exists(audit)&&Files.size(audit)>4L<<20)Files.move(audit,audit.resolveSibling("editor-consent.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);
            if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}
            ObjectNode row=Profiles.JSON.createObjectNode().put("at",clock.getAsLong()).put("request",r.id).put("agent",r.principal).put("sessionHash",CatalogScanner.hash(r.session)).put("workspaceHash",CatalogScanner.hash(workspace)).put("action",action);
            Files.writeString(audit,row+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);
        }catch(Exception e){throw new IllegalStateException("Editor consent audit unavailable; pairing is not authorized");}
    }
    @Override public synchronized void close(){for(Request r:requests.values())if(PENDING.contains(r.state))finish(r,"cancelled");requests.clear();launcher.shutdownNow();}
}
