package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionLifecycleTest {
    @TempDir Path root;
    @Test void lifecycleEndpointsRequireBrowserSessionAndCsrf()throws Exception{
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,10,5,10),new DbaTest.MemoryVault());var client=java.net.http.HttpClient.newHttpClient()){
            server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort();
            var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(login.body()).path("csrf").asText();
            var saved=DbaTest.request(client,base,"/api/dba/connections","POST",new DbaTest().input().put("saveUntested",true),cookie,csrf);assertEquals(201,saved.statusCode());String path="/api/dba/connections/"+Profiles.JSON.readTree(saved.body()).path("id").asText();
            assertEquals(403,DbaTest.request(client,base,path+"/state","GET",null,null,null).statusCode());assertFalse(Profiles.JSON.readTree(DbaTest.request(client,base,path+"/state","GET",null,cookie,null).body()).path("connected").asBoolean());
            assertEquals(403,DbaTest.request(client,base,path+"/connect","POST",Profiles.JSON.createObjectNode(),cookie,null).statusCode());
            var start=DbaTest.request(client,base,path+"/connect","POST",Profiles.JSON.createObjectNode(),cookie,csrf);assertEquals(202,start.statusCode());String job=Profiles.JSON.readTree(start.body()).path("id").asText();com.fasterxml.jackson.databind.JsonNode state=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            do{state=Profiles.JSON.readTree(DbaTest.request(client,base,"/api/dba/jobs/"+job,"GET",null,cookie,null).body());if(state.path("finished").asLong()>0)break;Thread.sleep(20);}while(System.nanoTime()<deadline);assertEquals("complete",state.path("state").asText(),state.toString());
            assertTrue(Profiles.JSON.readTree(DbaTest.request(client,base,path+"/state","GET",null,cookie,null).body()).path("connected").asBoolean());
            assertEquals(403,DbaTest.request(client,base,path+"/disconnect","POST",Profiles.JSON.createObjectNode(),cookie,null).statusCode());assertEquals(200,DbaTest.request(client,base,path+"/disconnect","POST",Profiles.JSON.createObjectNode(),cookie,csrf).statusCode());
            assertFalse(Profiles.JSON.readTree(DbaTest.request(client,base,path+"/state","GET",null,cookie,null).body()).path("connected").asBoolean());
            var tree=Profiles.JSON.createObjectNode().put("connectionId",Profiles.JSON.readTree(saved.body()).path("id").asText()).put("kind","root");
            assertEquals(403,DbaTest.request(client,base,"/api/dba/metadata/tree","POST",tree,null,null).statusCode());
            assertEquals(403,DbaTest.request(client,base,"/api/dba/metadata/tree","POST",tree,cookie,null).statusCode());
            assertEquals(202,DbaTest.request(client,base,"/api/dba/metadata/tree","POST",tree,cookie,csrf).statusCode());
            assertEquals(200,DbaTest.request(client,base,"/dba/metadata-tree.js","GET",null,cookie,null).statusCode());
            var color=Profiles.JSON.createObjectNode().put("color","#ed6363");
            assertEquals(403,DbaTest.request(client,base,path+"/appearance","PUT",color,cookie,null).statusCode());
            assertEquals(200,DbaTest.request(client,base,path+"/appearance","PUT",color,cookie,csrf).statusCode());
            var order=Profiles.JSON.createObjectNode();order.putArray("ids").add(Profiles.JSON.readTree(saved.body()).path("id").asText());
            assertEquals(403,DbaTest.request(client,base,"/api/dba/connections/order","PUT",order,cookie,null).statusCode());
            assertEquals(200,DbaTest.request(client,base,"/api/dba/connections/order","PUT",order,cookie,csrf).statusCode());
        }finally{server.stop(0);}
    }
    @Test void connectReconnectDisconnectAndBusyProtection()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,10,5,10),owner->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            assertFalse(jobs.connectionState(id).path("connected").asBoolean());
            assertThrows(SecurityException.class,()->jobs.connectionAction("agent:test",id,"connect"));
            assertEquals("complete",HumanSqlTest.finish(jobs,"human",jobs.connectionAction("human",id,"connect")).path("state").asText());
            assertTrue(jobs.connectionState(id).path("connected").asBoolean());
            assertThrows(IllegalArgumentException.class,()->jobs.connectionAction("human",id,"connect"));
            assertEquals("complete",HumanSqlTest.finish(jobs,"human",jobs.connectionAction("human",id,"reconnect")).path("state").asText());
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
            var pending=jobs.submit("human",id,(job,c)->{started.countDown();release.await(5,TimeUnit.SECONDS);return Profiles.JSON.createObjectNode();});
            try{assertTrue(started.await(5,TimeUnit.SECONDS));assertTrue(jobs.connectionState(id).path("busy").asBoolean());assertThrows(IllegalArgumentException.class,()->jobs.connectionAction("human",id,"disconnect"));assertThrows(IllegalArgumentException.class,()->jobs.connectionAction("human",id,"reconnect"));}finally{release.countDown();}
            HumanSqlTest.finish(jobs,"human",pending);
            assertFalse(jobs.connectionAction("human",id,"disconnect").path("connected").asBoolean());assertEquals(0,connections.count());
            assertEquals(id,profiles.get(id).path("id").asText());
            assertThrows(IllegalArgumentException.class,()->jobs.connectionAction("human",id,"reconnect"));
        }
    }
}
