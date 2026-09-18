package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Disposable QA server, not an application entry point. Stops automatically or via test-only endpoint. */
public final class BrowserFixture {
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("code-graph-dba-browser-test-");var stop=new CountDownLatch(1);
        var vault=new DbaTest.MemoryVault();String schema="";java.sql.Connection fixture=null;
        if(System.getenv("DBA_TEST_DISPOSABLE")!=null&&System.getenv("DBA_TEST_DISPOSABLE").matches("code-graph-dba-test-[a-f0-9]+")){
            schema="browser_"+java.util.UUID.randomUUID().toString().replace("-","");
            fixture=java.sql.DriverManager.getConnection(System.getenv("DBA_TEST_URL"),"postgres",System.getenv("DBA_TEST_PASSWORD"));
            try(var s=fixture.createStatement()){s.execute("CREATE SCHEMA "+schema);s.execute("CREATE TABLE "+schema+".items AS SELECT n AS id FROM generate_series(1,1000) n");s.execute("CREATE TABLE "+schema+".menu_target(id int)");s.execute("CREATE VIEW "+schema+".menu_view AS SELECT * FROM "+schema+".menu_target");}
            try(var profiles=new Profiles(root,vault)){profiles.put(null,Profiles.JSON.createObjectNode().put("name","Browser PostgreSQL").put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("url",System.getenv("DBA_TEST_URL")).put("username","postgres").put("password",System.getenv("DBA_TEST_PASSWORD")));}
        }
        boolean contextSuite=java.util.Set.of("project-context","approvals","approval-review").contains(java.util.Objects.toString(System.getenv("DBA_BROWSER_SUITE"),""));
        if(contextSuite){try(var marker=new Profiles(root,vault)){}String url="jdbc:h2:"+root.resolve("context-db").toAbsolutePath();try(var c=java.sql.DriverManager.getConnection(url,"sa","");var st=c.createStatement()){st.execute("CREATE TABLE ITEMS(ID INT PRIMARY KEY, NAME VARCHAR(40))");st.execute("INSERT INTO ITEMS VALUES(1,'original')");}try(var p=new Profiles(root,vault)){p.put(null,Profiles.JSON.createObjectNode().put("name","Context H2").put("templateId","h2").put("driverClass","org.h2.Driver").put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("url",url).put("username","sa").put("password",""));}}
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,10,5,10),vault,true,new ApprovalBrokerTest.FakeDesktop(){public boolean available(){return false;}})){
            if("workspace-toolbar".equals(System.getenv("DBA_BROWSER_SUITE")))runtime.trustedLocalAgent();
            if(contextSuite){runtime.attachProjectContext(new ProjectContextHost(){public com.fasterxml.jackson.databind.JsonNode projects(){return Profiles.JSON.createArrayNode().add(Profiles.JSON.createObjectNode().put("id",DbaRuntime.projectContextId("fixture-project")).put("name","Fixture project"));}public io.doindev.codegraph.store.DocumentStore documents(){return io.doindev.codegraph.store.DocumentStore.memory(64L<<20);}});
                server.createContext("/__test/agent",x->{try{var body=Profiles.JSON.readTree(x.getRequestBody().readNBytes(65537));String principal=runtime.authenticateAgent(x.getRequestHeaders().getFirst("X-Test-Agent"));runtime.registerMcpSession("fixture:"+principal,principal,Long.MAX_VALUE);byte[] bytes=runtime.agentCall(principal,"fixture:"+principal,body.path("operation").asText(),body.path("args")).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}catch(Exception e){byte[] bytes=Profiles.JSON.createObjectNode().put("error",e.getClass().getSimpleName()).toString().getBytes();x.sendResponseHeaders(400,bytes.length);x.getResponseBody().write(bytes);}finally{x.close();}});
            }
            if(contextSuite)server.createContext("/__test/review",x->{try{
                String principal=runtime.authenticateAgent(x.getRequestHeaders().getFirst("X-Test-Agent"));String id=Profiles.JSON.readTree(x.getRequestBody()).path("id").asText();
                runtime.agentCall(principal,"dba_request_status",Profiles.JSON.createObjectNode().put("requestId",id));
                var field=DbaRuntime.class.getDeclaredField("reviewServer");field.setAccessible(true);var handoff=((ApprovalReviewServer)field.get(runtime)).open(id);
                byte[] bytes=Profiles.JSON.createObjectNode().put("url",handoff.uri().toString()).toString().getBytes();x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
            }catch(Exception e){x.sendResponseHeaders(400,-1);}finally{x.close();}});
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/",runtime::handle);
            server.createContext("/__test/stop",x->{x.sendResponseHeaders(204,-1);x.close();stop.countDown();});server.start();
            System.out.println("DBA_FIXTURE="+Profiles.JSON.createObjectNode().put("base","http://localhost:"+server.getAddress().getPort()).put("schema",schema).put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()));
            stop.await(5,TimeUnit.MINUTES);
        }finally{server.stop(0);if(fixture!=null){try(var s=fixture.createStatement()){s.execute("DROP SCHEMA "+schema+" CASCADE");}finally{fixture.close();}}try(var files=Files.walk(root)){for(Path path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
