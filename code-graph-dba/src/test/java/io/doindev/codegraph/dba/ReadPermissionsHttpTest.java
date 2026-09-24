package io.doindev.codegraph.dba;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadPermissionsHttpTest {
    @TempDir Path directory;
    @Test void humanOnlyEndpointsRevisionChecksAndRestart()throws Exception{
        var vault=new DbaTest.MemoryVault();String connection;
        try(var p=new Profiles(directory,vault)){connection=p.put(null,new DbaTest().input().put("name","Permission target").put("templateId","mysql").put("url","jdbc:mysql://localhost/app")).path("id").asText();}
        String policyId=null;
        for(int pass=0;pass<2;pass++){
            var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,100,100,10),vault);var client=HttpClient.newHttpClient()){
                String principal=runtime.trustedLocalAgent();runtime.registerMcpSession("test-session",principal,System.currentTimeMillis()+60000);
                server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort(),path="/api/dba/agents/"+principal+"/policies";
                var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
                String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(login.body()).path("csrf").asText();
                if(pass==0){
                    var input=Profiles.JSON.createObjectNode().put("lifetime","until_revoked");input.putArray("selectors").addObject().put("connectionId",connection).put("level","connection");
                    assertEquals(403,DbaTest.request(client,base,path,"POST",input,null,null).statusCode());
                    assertEquals(403,DbaTest.request(client,base,path,"POST",input,cookie,null).statusCode());
                    var created=DbaTest.request(client,base,path,"POST",input,cookie,csrf);assertEquals(201,created.statusCode(),created.body());var policy=Profiles.JSON.readTree(created.body());policyId=policy.path("id").asText();
                    var changed=input.deepCopy().put("revision",1).put("enabled",false);
                    assertEquals(200,DbaTest.request(client,base,path+"/"+policyId,"PUT",changed,cookie,csrf).statusCode());
                    assertEquals(409,DbaTest.request(client,base,path+"/"+policyId,"PUT",changed,cookie,csrf).statusCode());
                    assertEquals(400,DbaTest.request(client,base,path,"POST",input.deepCopy().put("unexpected",true),cookie,csrf).statusCode());
                    var settings=runtime.agentCall(principal,"test-session","dba_get_my_permissions",Profiles.JSON.createObjectNode());assertEquals(1,settings.path("reusablePolicies").size());
                    assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,"test-session","dba_create_read_permission",input));
                    var sessions=DbaTest.request(client,base,"/api/dba/agents/"+principal+"/read-sessions","GET",null,cookie,csrf);assertEquals(200,sessions.statusCode());assertFalse(sessions.body().contains("test-session"));
                }else{
                    var response=DbaTest.request(client,base,"/api/dba/agents/"+principal+"/permissions","GET",null,cookie,csrf);var policies=Profiles.JSON.readTree(response.body()).path("reusablePolicies");
                    assertEquals(1,policies.size());assertFalse(policies.get(0).path("enabled").asBoolean());assertEquals(2,policies.get(0).path("revision").asLong());
                    assertEquals(200,DbaTest.request(client,base,path+"/"+policyId,"DELETE",null,cookie,csrf).statusCode());
                }
            }finally{server.stop(0);}
        }
    }
    @Test void closingTargetDiscoveryCancelsAndReleasesAfterActualCompletion()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,1,100,100,10),owner->true)){
            var started=new java.util.concurrent.CountDownLatch(1);var finish=new java.util.concurrent.CountDownLatch(1);
            var result=jobs.local("browser",job->{started.countDown();while(finish.getCount()>0)try{finish.await();}catch(InterruptedException expected){}return Profiles.JSON.createObjectNode();},()->{});
            assertTrue(started.await(2,java.util.concurrent.TimeUnit.SECONDS));String id=result.path("id").asText();var job=jobs.require("browser",id);job.permissionDiscovery=true;
            try{jobs.remove("browser",id);assertTrue(job.cancelled);assertTrue(jobs.telemetry().path("reservedBytes").asLong()>0);assertEquals(0,job.finished);}
            finally{finish.countDown();}
            long until=System.nanoTime()+2_000_000_000L;while(job.finished==0&&System.nanoTime()<until)Thread.sleep(10);assertTrue(job.finished>0);jobs.reap();assertThrows(IllegalArgumentException.class,()->jobs.require("browser",id));
        }
    }
    @Test void restrictedReviewOnlyExposesItsOwnPicker(){
        String id=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        assertDoesNotThrow(()->ApprovalReviewServer.requireRoute("/api/dba/approvals/"+id+"/permission-targets","POST",id));
        assertDoesNotThrow(()->ApprovalReviewServer.requireRoute("/api/dba/approvals/"+id+"/permission-targets/"+other,"GET",id));
        assertThrows(SecurityException.class,()->ApprovalReviewServer.requireRoute("/api/dba/permissions/targets","POST",id));
        assertThrows(SecurityException.class,()->ApprovalReviewServer.requireRoute("/api/dba/agents/x/policies","POST",id));
        assertThrows(SecurityException.class,()->ApprovalReviewServer.requireRoute("/api/dba/approvals/"+other+"/permission-targets","POST",id));
    }
}