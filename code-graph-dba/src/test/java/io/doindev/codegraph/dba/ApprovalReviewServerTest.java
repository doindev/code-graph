package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalReviewServerTest {
    @TempDir Path root;
    DbaRuntime runtime;
    HttpClient client;
    String principal,requestId,base,cookie,csrf,tab,lease;
    ApprovalBroker broker;
    ApprovalReviewServer review;
    @BeforeEach void start()throws Exception {
        var vault=new DbaTest.MemoryVault();
        try(var profiles=new Profiles(root,vault)){principal=new AgentAccess(root).create(Profiles.JSON.createObjectNode().put("name","fixture agent").set("grants",Profiles.JSON.createArrayNode()),profiles).path("id").asText();}
        runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,100,100,5,60,"browser"),vault,true,new ApprovalBrokerTest.FakeDesktop(){public boolean available(){return false;}});
        client=HttpClient.newHttpClient();
        ObjectNode input=Profiles.JSON.createObjectNode().put("requestId","test-request").put("purpose","Review a disposable H2 connection");
        input.set("profile",new DbaTest().input().put("password","never-return-this"));
        requestId=runtime.agentCall(principal,"dba_request_connection_create",input).path("id").asText();
        var bf=DbaRuntime.class.getDeclaredField("broker");bf.setAccessible(true);broker=(ApprovalBroker)bf.get(runtime);
        var rf=DbaRuntime.class.getDeclaredField("reviewServer");rf.setAccessible(true);review=(ApprovalReviewServer)rf.get(runtime);
    }
    @AfterEach void stop(){if(runtime!=null)runtime.close();if(client!=null)client.close();}
    HttpResponse<String> send(String path,String method,JsonNode body,boolean authenticated)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Origin",base).header("Content-Type","application/json");
        if(authenticated){b.header("Cookie",cookie);if(csrf!=null)b.header("X-Dba-CSRF",csrf);if(tab!=null)b.header("X-Dba-Tab",tab);if(lease!=null)b.header("X-Dba-Review",lease);}
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));
        return client.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    void pair(boolean code)throws Exception {
        var handoff=review.open(requestId);base="http://127.0.0.1:"+handoff.uri().getPort();
        var response=send("/api/dba/review-bootstrap","POST",Profiles.JSON.createObjectNode().put(code?"code":"token",code?handoff.code():handoff.uri().getFragment()),false);
        assertEquals(200,response.statusCode(),response.body());cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];csrf=Profiles.JSON.readTree(response.body()).path("csrf").asText();
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().contains("SameSite=Strict"));
        tab=UUID.randomUUID().toString();
        assertEquals(200,send("/api/dba/approvals/presence","POST",Profiles.JSON.createObjectNode().put("tabId",tab).put("visible",true).put("focused",true).put("polling",true),true).statusCode());
    }
    void claim()throws Exception{
        var response=send("/api/dba/approvals/"+requestId+"/claim","POST",Profiles.JSON.createObjectNode(),true);
        assertEquals(200,response.statusCode(),response.body());lease=Profiles.JSON.readTree(response.body()).path("lease").asText();
    }
    @Test void unauthorizedBootstrapAndUnrelatedApisFailClosed()throws Exception{
        var handoff=review.open(requestId);base="http://127.0.0.1:"+handoff.uri().getPort();
        assertEquals(200,send("/dba/review","GET",null,false).statusCode());
        assertEquals(200,send("/dba/native-connection-editor.js","GET",null,false).statusCode());
        assertEquals(403,send("/dba/native-workspace.js","GET",null,false).statusCode());
        assertEquals(403,send("/dba/redis-set-editor.js","GET",null,false).statusCode());
        assertEquals(403,send("/dba/redis-stream-editor.js","GET",null,false).statusCode());
        assertEquals(403,send("/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),false).statusCode());
        assertEquals(403,send("/api/dba/connections","GET",null,false).statusCode());
        for(int i=0;i<5;i++)assertEquals(403,send("/api/dba/review-bootstrap","POST",Profiles.JSON.createObjectNode().put("code","wrong"),false).statusCode());
        assertEquals(429,send("/api/dba/review-bootstrap","POST",Profiles.JSON.createObjectNode().put("code",handoff.code()),false).statusCode());
    }
    @Test void oneTimeBootstrapScopesSessionAndSecretsAreExcluded()throws Exception{
        var handoff=review.open(requestId);base="http://127.0.0.1:"+handoff.uri().getPort();
        var payload=Profiles.JSON.createObjectNode().put("token",handoff.uri().getFragment());
        var response=send("/api/dba/review-bootstrap","POST",payload,false);assertEquals(200,response.statusCode());
        cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];csrf=Profiles.JSON.readTree(response.body()).path("csrf").asText();
        assertEquals(403,send("/api/dba/review-bootstrap","POST",payload,false).statusCode());
        var list=send("/api/dba/approvals","GET",null,true);assertEquals(200,list.statusCode());assertFalse(list.body().contains("never-return-this"));assertEquals(1,Profiles.JSON.readTree(list.body()).size());
        for(String path:List.of("/api/dba/connections","/api/dba/agents","/api/dba/workspace","/api/dba/approvals/"+UUID.randomUUID()+"/draft","/dba/app.js"))assertEquals(403,send(path,"GET",null,true).statusCode(),path);
        assertEquals(403,send("/api/dba/query/execute","POST",Profiles.JSON.createObjectNode().put("sql","DROP TABLE X"),true).statusCode());
        for(String path:List.of("/api/dba/native/prepare","/api/dba/native/execute","/api/dba/native/apply"))
            assertEquals(403,send(path,"POST",Profiles.JSON.createObjectNode(),true).statusCode(),path);
        assertEquals(403,send("/api/dba/setup/driver-install","POST",Profiles.JSON.createObjectNode(),true).statusCode());
    }
    @Test void pairingCodeRequiresCorrectOrigin(){
        assertThrows(SecurityException.class,()->ApprovalReviewServer.requireRoute("/api/dba/connections","POST",requestId));
        assertDoesNotThrow(()->ApprovalReviewServer.requireRoute("/api/dba/approvals/"+requestId+"/draft","PUT",requestId));
    }
    @Test void detailedReviewTestsEditsAndApprovesOnlyItsExactProposal()throws Exception{
        pair(true);claim();
        String path="/api/dba/approvals/"+requestId;
        var loaded=send(path+"/draft","GET",null,true);
        assertEquals(200,loaded.statusCode(),loaded.body());assertFalse(loaded.body().contains("never-return-this"));
        ObjectNode draft=(ObjectNode)Profiles.JSON.readTree(loaded.body());draft.put("name","Reviewed in temporary site").put("confirmDriverEffects",true);
        var started=send(path+"/test-draft","POST",draft,true);
        assertEquals(202,started.statusCode(),started.body());
        String job=Profiles.JSON.readTree(started.body()).path("id").asText();JsonNode status=null;
        for(int i=0;i<100;i++){status=Profiles.JSON.readTree(send("/api/dba/jobs/"+job,"GET",null,true).body());if(Set.of("complete","failed","cancelled").contains(status.path("state").asText()))break;Thread.sleep(25);}
        assertEquals("complete",status.path("state").asText(),status.toString());
        draft.put("receipt",status.path("result").path("receipt").asText());
        assertEquals(200,send(path+"/draft","PUT",draft,true).statusCode());
        // The old lease cannot approve even though it is the same browser session.
        assertTrue(Set.of(400,403).contains(send(path,"POST",Profiles.JSON.createObjectNode().put("action","approve_once").put("acknowledged",true),true).statusCode()));
        claim();
        String correctCsrf=csrf;csrf="wrong";
        assertEquals(403,send(path,"POST",Profiles.JSON.createObjectNode().put("action","approve_once").put("acknowledged",true),true).statusCode());csrf=correctCsrf;
        var applied=send(path,"POST",Profiles.JSON.createObjectNode().put("action","approve_once").put("acknowledged",true),true);
        assertEquals(200,applied.statusCode(),applied.body());assertEquals("complete",Profiles.JSON.readTree(applied.body()).path("state").asText());
        assertFalse(applied.body().contains("never-return-this"));
        assertNotEquals(200,send(path,"POST",Profiles.JSON.createObjectNode().put("action","approve_once").put("acknowledged",true),true).statusCode());
        // Reload uses the scoped cookie, never another capability token.
        assertEquals(requestId,Profiles.JSON.readTree(send("/api/dba/session","GET",null,true).body()).path("requestId").asText());
    }
    @Test void wrongOriginAndUnrelatedJobAreRejected()throws Exception{
        pair(false);claim();
        assertEquals(403,send("/api/dba/setup/draft-test","POST",Profiles.JSON.createObjectNode().put("connectionId",UUID.randomUUID().toString()),true).statusCode(),"Draft tests must use the exact proposal endpoint, not arbitrary saved profiles");
        var foreign=HttpRequest.newBuilder(URI.create(base+"/api/dba/approvals/presence")).header("Origin","http://evil.example").header("Cookie",cookie).header("X-Dba-CSRF",csrf).POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,client.send(foreign,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertNotEquals(200,send("/api/dba/jobs/"+UUID.randomUUID(),"GET",null,true).statusCode());
    }
    @Test void resolvedReviewsReleaseTheirListener()throws Exception{
        pair(false);claim();
        assertEquals(200,send("/api/dba/approvals/"+requestId,"POST",Profiles.JSON.createObjectNode().put("action","reject"),true).statusCode());
        var field=ApprovalReviewServer.class.getDeclaredField("server");field.setAccessible(true);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        while(field.get(review)!=null&&System.nanoTime()<deadline)Thread.sleep(100);
        assertNull(field.get(review),"Temporary listener must close after its completion grace period");
    }
    @Test void concurrentRequestsRemainBounded()throws Exception{
        java.util.concurrent.atomic.AtomicInteger admitted=new java.util.concurrent.atomic.AtomicInteger();
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var futures=new ArrayList<java.util.concurrent.Future<?>>();
            for(int i=0;i<48;i++){int n=i;futures.add(workers.submit(()->{
                ObjectNode input=Profiles.JSON.createObjectNode().put("requestId","capacity-"+n).put("purpose","Bounded queue test");
                input.set("profile",new DbaTest().input().put("name","Capacity "+n));
                try{runtime.agentCall(principal,"dba_request_connection_create",input);admitted.incrementAndGet();}
                catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("queue is full"),expected.getMessage());}
                return null;
            }));}
            for(var task:futures)task.get(15,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(31,admitted.get(),"The existing fixture request plus new requests must total at most 32");
    }
    @Test void desktopOnlyRuntimeHandsComplexReviewToScopedBrowser()throws Exception{
        runtime.close();var desktop=new ApprovalBrokerTest.FakeDesktop();
        runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,100,100,5,60,"desktop"),new DbaTest.MemoryVault(),false,desktop);
        ObjectNode input=Profiles.JSON.createObjectNode().put("requestId","desktop-only").put("purpose","No main DBA UI");
        input.set("profile",new DbaTest().input().put("name","Desktop-only proposal"));
        requestId=runtime.agentCall(principal,"dba_request_connection_create",input).path("id").asText();
        for(int i=0;i<100&&desktop.detailed==null;i++)Thread.sleep(20);
        assertNotNull(desktop.detailed);desktop.detailed.run();
        for(int i=0;i<100&&desktop.launched==null;i++)Thread.sleep(20);
        assertNotNull(desktop.launched);base="http://127.0.0.1:"+desktop.launched.getPort();
        var response=send("/api/dba/review-bootstrap","POST",Profiles.JSON.createObjectNode().put("token",desktop.launched.getFragment()),false);
        assertEquals(200,response.statusCode(),response.body());cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];csrf=Profiles.JSON.readTree(response.body()).path("csrf").asText();tab=UUID.randomUUID().toString();
        assertEquals(200,send("/api/dba/approvals/presence","POST",Profiles.JSON.createObjectNode().put("tabId",tab).put("visible",true).put("polling",true),true).statusCode());
        claim();
        assertEquals(403,send("/dba","GET",null,true).statusCode());
        var decision=send("/api/dba/approvals/"+requestId,"POST",Profiles.JSON.createObjectNode().put("action","approve_once").put("acknowledged",true).put("saveUntested",true),true);
        assertEquals(200,decision.statusCode(),decision.body());assertEquals("complete",Profiles.JSON.readTree(decision.body()).path("state").asText());
    }
}
