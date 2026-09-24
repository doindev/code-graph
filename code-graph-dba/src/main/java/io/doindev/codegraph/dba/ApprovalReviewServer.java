package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Short-lived loopback site with a request-scoped session and no automatic local bootstrap. */
final class ApprovalReviewServer implements AutoCloseable {
    private record Grant(String request,String hash,String codeHash,long expires){}
    private final DbaRuntime runtime;
    private final ApprovalBroker broker;
    private final BrowserAuth auth;
    private final Map<String,Grant> grants=new HashMap<>();
    private final Map<String,String> scopes=new ConcurrentHashMap<>();
    private final Map<String,Long> completedScopes=new HashMap<>();
    private HttpServer server;
    private ExecutorService executor;
    private final ScheduledExecutorService cleanup=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("approval-review-expiry").factory());
    private int failedAttempts;
    private long attemptWindow,idleSince;
    ApprovalReviewServer(DbaRuntime runtime,ApprovalBroker broker,Path path)throws IOException {
        this.runtime=runtime;this.broker=broker;auth=new BrowserAuth(path,"dba_review_"+UUID.randomUUID().toString().replace("-",""));
        cleanup.scheduleWithFixedDelay(this::tick,1,1,TimeUnit.SECONDS);
    }
    synchronized ApprovalBroker.Handoff open(String id){
        JsonNode request=broker.pending(id);long expires=request.path("expiresAt").asLong();
        if(server==null)try{
            server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),16);
            executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);server.createContext("/",this::handle);server.start();
        }catch(IOException e){throw new IllegalStateException("Cannot start local approval review");}
        if(grants.size()>=32)throw new IllegalArgumentException("Too many detailed reviews");
        String token=BrowserAuth.token(),code=String.format(Locale.ROOT,"%010d",new SecureRandom().nextLong(10_000_000_000L));
        grants.put(id,new Grant(id,CatalogScanner.hash(token),CatalogScanner.hash(code),expires));idleSince=0;
        return new ApprovalBroker.Handoff(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/dba/review#"+token),code);
    }
    boolean alive(String session){return scopes.containsKey(session)&&auth.alive(session);}
    static boolean assetAllowed(String path){return Set.of("/dba/review","/dba/approval-review.js","/dba/approval-client.js","/dba/approval-ui.js","/dba/read-permissions.js","/dba/connection-editor.js","/dba/native-connection-editor.js","/dba/style.css","/dba/tree-icons.js","/dba/database.svg").contains(path);}
    static void requireRoute(String path,String method,String scope){
        String prefix="/api/dba/approvals/"+scope;
        if(path.equals("/api/dba/approvals")&&method.equals("GET"))return;
        if(path.equals("/api/dba/approvals/events")&&method.equals("GET")||path.equals("/api/dba/approvals/presence")&&method.equals("POST"))return;
        if(path.equals(prefix)&&method.equals("POST"))return;
        if(path.equals(prefix+"/permission-targets")&&method.equals("POST"))return;
        if(path.startsWith(prefix+"/permission-targets/")&&Set.of("GET","DELETE").contains(method)&&path.substring((prefix+"/permission-targets/").length()).matches("[a-f0-9-]{36}"))return;
        if(path.startsWith(prefix+"/")&&Set.of("claim","renew","release","draft","test","test-draft").contains(path.substring(prefix.length()+1)))return;
        if(Set.of("/api/dba/templates","/api/dba/session").contains(path)&&method.equals("GET"))return;
        if(path.startsWith("/api/dba/jobs/")&&path.substring("/api/dba/jobs/".length()).matches("[a-f0-9-]{36}(/cancel)?"))return;
        if(path.startsWith("/api/dba/setup/")&&method.equals("POST")&&Set.of("driver-status","driver-install","driver-inspect","properties","key-validate","file-select").contains(path.substring("/api/dba/setup/".length())))return;
        if(path.equals("/api/dba/drivers/import")&&method.equals("POST"))return;
        throw new SecurityException("Operation is outside this approval review");
    }
    private void handle(HttpExchange x)throws IOException {
        try{
            BrowserAuth.local(x);x.getResponseHeaders().set("Cache-Control","no-store");x.getResponseHeaders().set("Referrer-Policy","no-referrer");
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            if(assetAllowed(path)&&method.equals("GET")){DbaRuntime.asset(x,path);return;}
            if(path.equals("/api/dba/review-bootstrap")&&method.equals("POST")){bootstrap(x);return;}
            BrowserAuth.Session session=auth.require(x);String scope;synchronized(this){scope=scopes.get(session.id());}
            if(scope==null)throw new SecurityException("Detailed review session expired");
            if(path.equals("/api/dba/session")&&method.equals("GET")){json(x,200,Profiles.JSON.createObjectNode().put("csrf",session.csrf()).put("requestId",scope).put("expires",session.expires()));return;}
            if(!path.equals("/api/dba/approvals")&&!path.equals("/api/dba/session"))broker.pending(scope);
            requireRoute(path,method,scope);
            if(path.startsWith("/api/dba/setup/")||path.equals("/api/dba/drivers/import"))
                broker.requireLease(session.id(),x.getRequestHeaders().getFirst("X-Dba-Tab"),scope,x.getRequestHeaders().getFirst("X-Dba-Review"));
            runtime.handleReview(x,auth,scope);
        }catch(SecurityException e){json(x,403,Map.of("error",e.getMessage()));}
        catch(IllegalArgumentException e){json(x,400,Map.of("error",e.getMessage()));}
        catch(Exception e){json(x,500,Map.of("error","Approval review unavailable"));}
        finally{x.close();}
    }
    private synchronized void bootstrap(HttpExchange x)throws IOException {
        long now=System.currentTimeMillis();if(now-attemptWindow>60_000){attemptWindow=now;failedAttempts=0;}
        if(failedAttempts>=5){json(x,429,Map.of("error","Too many pairing attempts; wait one minute"));return;}
        if(!Objects.toString(x.getRequestHeaders().getFirst("Content-Type"),"").startsWith("application/json"))throw new IllegalArgumentException("JSON required");
        byte[] bytes=x.getRequestBody().readNBytes(1025);if(bytes.length>1024)throw new IllegalArgumentException("Pairing request too large");
        JsonNode input;try{input=Profiles.JSON.readTree(bytes);}finally{Arrays.fill(bytes,(byte)0);}
        if(input==null||!input.isObject())throw new IllegalArgumentException("Pairing request required");
        String token=input.path("token").asText(),code=input.path("code").asText(),hash=CatalogScanner.hash(token.isEmpty()?code:token);Grant found=null;
        for(Grant grant:grants.values())if(grant.expires>now&&BrowserAuth.equal(hash,token.isEmpty()?grant.codeHash:grant.hash)){found=grant;break;}
        if(found==null){failedAttempts++;throw new SecurityException("Invalid or expired pairing request");}
        broker.pending(found.request);grants.remove(found.request);
        BrowserAuth.Session session=auth.create(found.expires+10_000);scopes.put(session.id(),found.request);broker.restrictSession(session.id(),found.request);
        x.getResponseHeaders().add("Set-Cookie",auth.cookieName+"="+session.id()+"; Path=/api/dba/; HttpOnly; SameSite=Strict; Max-Age=310");
        json(x,200,Profiles.JSON.createObjectNode().put("csrf",session.csrf()).put("requestId",found.request).put("expires",session.expires()));
    }
    private synchronized void tick(){
        try{
            long now=System.currentTimeMillis();grants.values().removeIf(g->g.expires<=now||!pending(g.request));
            scopes.entrySet().removeIf(e->{
                if(auth.alive(e.getKey())){
                    if(pending(e.getValue()))return false;
                    if(now-completedScopes.computeIfAbsent(e.getKey(),key->now)<10_000)return false;
                }
                completedScopes.remove(e.getKey());auth.logout(e.getKey());broker.forgetSession(e.getKey());return true;
            });
            if(server!=null&&grants.isEmpty()&&scopes.isEmpty()){if(idleSince==0)idleSince=now;else if(now-idleSince>=10_000)stopServer();}
        }catch(Exception ignored){}
    }
    private boolean pending(String id){try{broker.pending(id);return true;}catch(IllegalArgumentException e){return false;}}
    private static void json(HttpExchange x,int status,Object value)throws IOException {byte[] bytes=Profiles.JSON.writeValueAsBytes(value);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
    private void stopServer(){if(server!=null){server.stop(0);server=null;}if(executor!=null){executor.shutdownNow();executor=null;}}
    @Override public synchronized void close(){cleanup.shutdownNow();stopServer();grants.clear();scopes.keySet().forEach(broker::forgetSession);scopes.clear();completedScopes.clear();auth.close();}
}
