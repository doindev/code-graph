package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Routes consent, never grants it. All execution still passes through the authoritative queues. */
final class ApprovalBroker implements AutoCloseable {
    static final long PRESENCE_MS=25_000, LEASE_MS=30_000;
    interface Requests {
        JsonNode list();
        JsonNode decide(String reviewer,String id,String action,boolean acknowledged,JsonNode options);
    }
    interface Desktop extends AutoCloseable {
        boolean available();
        boolean browse(URI uri);
        void show(JsonNode request,int waiting,Consumer<Decision> decide,Runnable detailed);
        void dismiss();
        default void updateWaiting(int waiting) {}
        default void handoff(URI uri,String code) {}
        default void close(){dismiss();}
    }
    static final Desktop NO_DESKTOP=new Desktop(){
        public boolean available(){return false;}
        public boolean browse(URI uri){return false;}
        public void show(JsonNode request,int waiting,Consumer<Decision> decide,Runnable detailed){throw new IllegalStateException("No interactive approval channel");}
        public void dismiss(){}
    };
    record Decision(String action,boolean acknowledged) {}
    record Handoff(URI uri,String code) {}
    static final class Unavailable extends IllegalStateException {
        Unavailable(){super("approval_unavailable: No interactive approval channel is available");}
    }
    private static final class Presence {
        final String session,tab; long seen,focused; boolean visible,connected,polling;
        final Set<ArrayBlockingQueue<String>> streams=new HashSet<>();
        Presence(String session,String tab){this.session=session;this.tab=tab;}
        String key(){return session+":"+tab;}
    }
    private static final class Delivery {
        String channel="pending",state="pending",owner="",token="",revision="";
        long leaseUntil,deliveredAt; boolean browserAttempted,editor;
    }
    private final String mode;
    private final boolean browserEnabled;
    private final Requests requests;
    private final Desktop desktop;
    private final Predicate<String> sessionAlive;
    private final LongSupplier clock;
    private final Map<String,String> restrictedSessions=new HashMap<>();
    synchronized void restrictSession(String session,String request){restrictedSessions.put(session,request);}
    private final Map<String,Presence> tabs=new LinkedHashMap<>();
    private final Map<String,Delivery> deliveries=new LinkedHashMap<>();
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("approval-broker").factory());
    private final ExecutorService launchExecutor=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("approval-launch").factory());
    private Function<String,Handoff> detailed;
    private volatile URI browserUri;
    private volatile boolean closed;
    private String desktopId="";
    private long sequence,lastLaunch;
    private String lastPending="";
    ApprovalBroker(String mode,boolean ui,Requests requests,Desktop desktop,Predicate<String> alive) {
        this(mode,ui,requests,desktop,alive,System::currentTimeMillis,true);
    }
    ApprovalBroker(String mode,boolean ui,Requests requests,Desktop desktop,Predicate<String> alive,LongSupplier clock,boolean schedule) {
        this.mode=mode;this.requests=requests;this.desktop=desktop;this.sessionAlive=alive;this.clock=clock;
        if(mode.equals("browser")&&!ui)throw new IllegalArgumentException("Browser approval mode requires --viz");
        if(mode.equals("desktop")&&!desktop.available())throw new IllegalArgumentException("Desktop approval mode requires a usable graphical session");
        browserEnabled=ui&&!Set.of("none","desktop").contains(mode);
        if(schedule)executor.scheduleWithFixedDelay(this::safeTick,1,1,TimeUnit.SECONDS);
    }
    synchronized void detailed(Function<String,Handoff> opener){detailed=opener;}
    void browserUri(URI uri){browserUri=uri;wake();}
    boolean enabled(){return !mode.equals("none")&&(browserEnabled||desktop.available());}
    void requireAvailable(){if(!enabled())throw new Unavailable();}
    void published(String id){wake();}
    void wake(){if(!closed)try{executor.execute(this::safeTick);}catch(RejectedExecutionException ignored){}}
    private void safeTick(){try{tick();}catch(Exception ignored){/* Failure never grants consent; next tick retries notification only. */}}
    static String revision(JsonNode r){
        ObjectNode copy=((ObjectNode)r).deepCopy();
        copy.remove(List.of("job","jobId","jobExpired","state","result","approvalChannel","reviewAvailable","deliveryStatus","reviewRevision"));
        return CatalogScanner.hash(copy.toString());
    }
    JsonNode pending(String id){
        for(JsonNode r:requests.list())if(r.path("id").asText().equals(id)&&r.path("state").asText().equals("awaiting_approval"))return r;
        throw new IllegalArgumentException("Approval request expired or already consumed");
    }
    synchronized ObjectNode decorate(JsonNode request){
        if(request.path("authorizationReason").asText().equals("startup_yolo"))return AgentAuthorization.approved(request.deepCopy());
        ObjectNode out=request.deepCopy();Delivery d=deliveries.get(request.path("id").asText());
        return out.put("approvalChannel",d==null?(enabled()?"pending":"none"):d.channel)
            .put("reviewAvailable",enabled()).put("deliveryStatus",d==null?"pending":d.state);
    }
    synchronized ObjectNode presence(String session,JsonNode input){
        String tab=Profiles.text(input,"tabId",36);UUID.fromString(tab);
        String key=session+":"+tab;Presence p=tabs.get(key);
        if(p==null){prune();if(tabs.size()>=24)throw new IllegalArgumentException("Too many approval browser tabs");p=new Presence(session,tab);tabs.put(key,p);}
        p.seen=clock.getAsLong();p.visible=input.path("visible").asBoolean();p.polling=input.path("polling").asBoolean();
        if(input.path("focused").asBoolean()&&p.visible)p.focused=++sequence;
        return notifications(p);
    }
    private void prune(){
        long now=clock.getAsLong();
        tabs.values().removeIf(p->{boolean gone=!sessionAlive.test(p.session)||now-p.seen>120_000;if(gone)p.streams.forEach(q->offer(q,"closed"));return gone;});
        for(Delivery d:deliveries.values())if(!d.owner.isEmpty()&&(d.leaseUntil<=now||(!d.owner.equals("desktop")&&!tabs.containsKey(d.owner)))){d.owner="";d.token="";d.state="pending";}
    }
    private Presence selected(String id){
        return tabs.values().stream().filter(p->(!restrictedSessions.containsKey(p.session)||restrictedSessions.get(p.session).equals(id))&&p.visible&&(p.connected||p.polling)&&clock.getAsLong()-p.seen<PRESENCE_MS&&sessionAlive.test(p.session))
            .max(Comparator.comparingLong((Presence p)->p.focused).thenComparingLong(p->p.seen)).orElse(null);
    }
    private ObjectNode notifications(Presence p){
        ObjectNode n=Profiles.JSON.createObjectNode().put("sequence",sequence);ArrayNode ids=n.putArray("requests");
        for(var entry:deliveries.entrySet()){if(restrictedSessions.containsKey(p.session)&&!restrictedSessions.get(p.session).equals(entry.getKey()))continue;Delivery d=entry.getValue();if(d.state.equals("offered")&&d.owner.equals(p.key()))ids.add(entry.getKey());}
        n.put("pendingCount",deliveries.entrySet().stream().filter(e->!e.getValue().editor&&(!restrictedSessions.containsKey(p.session)||restrictedSessions.get(p.session).equals(e.getKey()))).count());return n;
    }
    synchronized ObjectNode poll(String session,JsonNode input){ObjectNode result=presence(session,input);wake();return result;}
    void events(HttpExchange x,String session,String tab)throws IOException {
        UUID.fromString(tab);ArrayBlockingQueue<String> queue=new ArrayBlockingQueue<>(16);Presence p;
        synchronized(this){p=tabs.get(session+":"+tab);if(p==null)throw new SecurityException("Register browser presence first");
            if(!p.streams.isEmpty())throw new IllegalArgumentException("Only one event stream per tab");p.streams.add(queue);p.connected=true;}
        x.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");x.getResponseHeaders().set("X-Accel-Buffering","no");
        x.sendResponseHeaders(200,0);wake();
        try(OutputStream out=x.getResponseBody()){
            while(!closed&&sessionAlive.test(session)){
                String signal;try{signal=queue.poll(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
                if("closed".equals(signal))break;
                String data; synchronized(this){data=notifications(p).toString();}
                out.write(("event: approvals\ndata: "+data+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
            }
        }catch(IOException ignored){/* Disconnected event streams are not JSON responses. */}
        finally{synchronized(this){p.streams.remove(queue);p.connected=false;}}
    }
    private static void offer(ArrayBlockingQueue<String> q,String value){if(!q.offer(value)){q.clear();q.offer(value);}}
    private void signal(){sequence++;tabs.values().forEach(p->p.streams.forEach(q->offer(q,"changed")));}
    synchronized ObjectNode claim(String session,String tab,String id){
        String owner=session+":"+tab;if(restrictedSessions.containsKey(session)&&!restrictedSessions.get(session).equals(id))throw new SecurityException("Approval is outside this review");if(!enabled()||!tabs.containsKey(owner)||!sessionAlive.test(session))throw new SecurityException("Active approval session required");
        return claimOwner(owner,id);
    }
    private ObjectNode claimOwner(String owner,String id){
        JsonNode r=pending(id);Delivery d=deliveries.computeIfAbsent(id,k->new Delivery());long now=clock.getAsLong();
        if(d.state.equals("reviewing")&&!d.owner.isEmpty()&&!d.owner.equals(owner)&&d.leaseUntil>now)throw new SecurityException("This request is being reviewed in another window");
        if(d.state.equals("offered"))d.owner="";
        d.owner=owner;d.token=BrowserAuth.token();d.revision=revision(r);d.leaseUntil=now+LEASE_MS;d.state="reviewing";
        d.channel=owner.equals("desktop")?"desktop":"browser";signal();
        return Profiles.JSON.createObjectNode().put("lease",d.token).put("reviewRevision",d.revision).put("leaseExpiresAt",d.leaseUntil);
    }
    synchronized <T> T withLease(String session,String tab,String id,String token,Supplier<T> work){checkLease(session+":"+tab,id,token);return work.get();}
    synchronized void requireLease(String session,String tab,String id,String token){
        checkLease(session+":"+tab,id,token);
    }
    private Delivery checkLease(String owner,String id,String token){
        if(!owner.equals("desktop")){
            Presence p=tabs.get(owner);
            if(p==null||!sessionAlive.test(p.session))throw new SecurityException("Approval session expired");
        }
        Delivery d=deliveries.get(id);
        if(d==null||!d.owner.equals(owner)||d.leaseUntil<=clock.getAsLong()||!BrowserAuth.equal(d.token,token))throw new SecurityException("Approval review lease expired or is owned by another window");
        if(!d.revision.equals(revision(pending(id)))){d.token="";d.leaseUntil=0;throw new IllegalArgumentException("Proposal changed; review it again before deciding");}
        return d;
    }
    synchronized ObjectNode renew(String session,String tab,String id,String token){
        Delivery d=checkLease(session+":"+tab,id,token);d.leaseUntil=clock.getAsLong()+LEASE_MS;
        return Profiles.JSON.createObjectNode().put("leaseExpiresAt",d.leaseUntil);
    }
    synchronized void release(String session,String tab,String id,String token){
        Delivery d=deliveries.get(id);if(d!=null&&d.owner.equals(session+":"+tab)&&BrowserAuth.equal(d.token,token)){d.owner="";d.token="";d.leaseUntil=0;d.state="pending";signal();}
    }
    synchronized JsonNode decide(String session,String tab,String id,String token,String action,boolean acknowledged,JsonNode options){
        checkLease(session+":"+tab,id,token);
        JsonNode result=requests.decide(session,id,action,acknowledged,options);
        finish(id);return decorate(result);
    }
    private void finish(String id){deliveries.remove(id);if(desktopId.equals(id)){desktopId="";desktop.dismiss();}signal();wake();}
    synchronized void forgetSession(String session){restrictedSessions.remove(session);tabs.values().removeIf(p->{if(!p.session.equals(session))return false;p.streams.forEach(q->offer(q,"closed"));return true;});prune();signal();}
    void tick(){
        if(closed)return;
        List<JsonNode> pending=new ArrayList<>();for(JsonNode r:requests.list())if(r.path("state").asText().equals("awaiting_approval")&&(!mode.equals("none")||r.path("type").asText().equals("editor_pairing")))pending.add(r);
        pending.sort(Comparator.comparingLong(r->r.path("createdAt").asLong()));
        synchronized(this){
            prune();Set<String> ids=new HashSet<>();pending.forEach(r->ids.add(r.path("id").asText()));deliveries.keySet().removeIf(id->!ids.contains(id));
            if(!desktopId.isEmpty()&&!ids.contains(desktopId)){desktopId="";desktop.dismiss();}
            if(!desktopId.isEmpty())desktop.updateWaiting(pending.size());
            String signature=pending.stream().map(r->r.path("id").asText()+revision(r)).reduce("",String::concat);
            if(!lastPending.equals(signature)){lastPending=signature;signal();}

            for(JsonNode r:pending){
                String id=r.path("id").asText();boolean editor=r.path("type").asText().equals("editor_pairing");Presence recipient=editor?null:selected(id);Delivery d=deliveries.computeIfAbsent(id,k->new Delivery());d.editor=editor;
                if(d.state.equals("reviewing")&&d.leaseUntil>clock.getAsLong()){
                    if(d.owner.equals("desktop")){if(!desktop.available()){desktop.dismiss();desktopId="";d.owner="";d.token="";d.leaseUntil=0;d.state="approval_unavailable";continue;}d.leaseUntil=clock.getAsLong()+LEASE_MS;if(!d.revision.equals(revision(r))){desktop.dismiss();desktopId="";d.owner="";d.leaseUntil=0;d.state="pending";}else continue;}
                    else continue;
                }
                if(recipient!=null&&(browserEnabled||restrictedSessions.containsKey(recipient.session))){if(!d.owner.equals(recipient.key())||!d.state.equals("offered")){
                    d.owner=recipient.key();d.channel="browser";d.state="offered";d.leaseUntil=clock.getAsLong()+LEASE_MS;signal();}continue;}
                if(!editor&&browserEnabled&&!d.browserAttempted&&browserUri!=null&&clock.getAsLong()-lastLaunch>=10_000){
                    d.browserAttempted=true;d.channel="browser";d.state="launching";d.deliveredAt=clock.getAsLong();lastLaunch=d.deliveredAt;
                    URI uri=browserUri.resolve("/dba#approval="+id);
                    launchExecutor.execute(()->{boolean ok=desktop.browse(uri);synchronized(this){if(deliveries.get(id)==d&&d.state.equals("launching")){d.state=ok?"browser_opened":"browser_failed";signal();}}});continue;
                }
                if(Set.of("launching","browser_opened").contains(d.state))continue;
                if((editor||!mode.equals("browser"))&&desktop.available()&&desktopId.isEmpty()&&r==pending.getFirst()){
                    d.owner="";d.leaseUntil=0;ObjectNode lease=claimOwner("desktop",id);desktopId=id;String token=lease.path("lease").asText();
                    desktop.show(ApprovalPresentation.safe(r),pending.size(),decision->executor.execute(()->desktopDecision(id,token,decision)),()->executor.execute(()->openDetailed(id,token)));
                }else if(!desktop.available()&&!browserEnabled){d.channel="none";d.state="approval_unavailable";}
            }
        }
    }
    private void desktopDecision(String id,String token,Decision decision){
        synchronized(this){
            try{checkLease("desktop",id,token);JsonNode r=pending(id);if(ApprovalPresentation.complex(r)&&!decision.action().equals("reject"))throw new SecurityException("Detailed browser review is required");
                requests.decide("desktop",id,decision.action(),decision.acknowledged(),Profiles.JSON.createObjectNode());finish(id);
            }catch(Exception e){desktopId="";desktop.dismiss();Delivery d=deliveries.get(id);if(d!=null){d.owner="";d.leaseUntil=0;d.state="delivery_failed";}}
        }
    }
    private void openDetailed(String id,String token){
        try{
            synchronized(this){checkLease("desktop",id,token);if(detailed==null)throw new Unavailable();}
            Handoff target=detailed.apply(id);
            synchronized(this){Delivery d=checkLease("desktop",id,token);d.owner="";d.token="";d.leaseUntil=0;d.channel="browser";d.state="browser_opened";desktopId="";desktop.dismiss();}
            if(!desktop.browse(target.uri()))desktop.handoff(target.code().isEmpty()?target.uri():target.uri().resolve("/dba/review"),target.code());
        }catch(Exception e){desktopDecision(id,token,new Decision("reject",false));}
    }
    @Override public synchronized void close(){
        closed=true;tabs.values().forEach(p->p.streams.forEach(q->offer(q,"closed")));tabs.clear();deliveries.clear();
        executor.shutdownNow();launchExecutor.shutdownNow();desktop.close();
    }
}
