package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.dba.DbaConfig;
import io.doindev.codegraph.index.*;
import org.junit.jupiter.api.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YoloMcpHttpTest extends DbaMcpHttpTest {
    HttpServer yolo(int ui)throws Exception{return HttpServer.start(Workspace.open(List.of(),Analyzers.discover(),ConfigLoader::load),0,ui,false,Duration.ofHours(1),new DbaConfig(directory,64L<<20,2,1000,100,10,60,"desktop",true));}
    @Test @Timeout(90) void automaticLocalHttpSetupSqlAndRestartDefaultOff()throws Exception{
        String connection,old;
        try(var client=HttpClient.newHttpClient()){
            try(var server=yolo(-1)){
                assertEquals("127.0.0.1",server.host());String endpoint="http://localhost:"+server.port()+"/mcp";
                old=initialize(client,endpoint,null);
                assertTrue(rpc(client,endpoint,old,null,"tools/list",JSON.createObjectNode()).body().contains("dba_request_connection_create"));
                ObjectNode profile=JSON.createObjectNode().put("name","YOLO HTTP").put("templateId","h2").put("url","jdbc:h2:mem:YOLO_HTTP;DB_CLOSE_DELAY=-1")
                    .put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("driverClass","org.h2.Driver").put("username","sa").put("readOnly",false);
                ObjectNode proposal=JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","Isolated transport test").put("saveUntested",true);proposal.set("profile",profile);
                JsonNode created=finish(client,endpoint,old,ReusableMcpHttpTest.call(client,endpoint,old,"dba_request_connection_create",proposal));
                connection=created.path("job").path("result").path("id").asText();assertFalse(connection.isBlank());
                ObjectNode sql=JSON.createObjectNode().put("connectionId",connection).put("connectionName","YOLO HTTP").put("database","YOLO_HTTP").put("schema","PUBLIC").put("requestId",UUID.randomUUID().toString()).put("purpose","Isolated mutation").put("sql","CREATE TABLE PUBLIC.HTTP_ITEMS(ID INT)");
                JsonNode done=finish(client,endpoint,old,ReusableMcpHttpTest.call(client,endpoint,old,"dba_request_live_sql",sql));
                assertFalse(done.has("approvalChannel"));
                var permissions=ReusableMcpHttpTest.call(client,endpoint,old,"dba_get_my_permissions",JSON.createObjectNode());assertTrue(permissions.path("reusablePolicies").isEmpty());
            }
            try(var server=start(-1)){
                String endpoint="http://localhost:"+server.port()+"/mcp";assertEquals(404,rpc(client,endpoint,old,null,"tools/list",JSON.createObjectNode()).statusCode());
                String session=initialize(client,endpoint,null);assertFalse(rpc(client,endpoint,session,null,"tools/list",JSON.createObjectNode()).body().contains("dba_request_live_sql"));
                assertFalse(ReusableMcpHttpTest.call(client,endpoint,session,"dba_get_my_permissions",JSON.createObjectNode()).path("yolo").asBoolean());
            }
        }
    }
    @Test @Timeout(60) void browserSettingsCannotEnableOrDisableStartupAuthority()throws Exception{
        try(var client=HttpClient.newHttpClient();var server=yolo(0)){
            String base="http://localhost:"+server.vizPort();var boot=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);var settings=JSON.readTree(boot.body());
            assertTrue(settings.path("yolo").asBoolean());assertFalse(settings.path("reviewAvailable").asBoolean());
            String cookie=boot.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=settings.path("csrf").asText();
            assertEquals(400,rest(client,base,"/settings","PUT",JSON.createObjectNode().put("yolo",false),cookie,csrf).statusCode());
            assertEquals(200,rest(client,base,"/settings","PUT",JSON.createObjectNode().put("uiRows",123),cookie,csrf).statusCode());
            assertTrue(JSON.readTree(rest(client,base,"/settings","GET",null,cookie,null).body()).path("yolo").asBoolean());
            assertTrue(JSON.readTree(rest(client,base,"/approvals","GET",null,cookie,null).body()).isEmpty());
        }
    }

    @Test @Timeout(90) void headlessStdioCreatesAndDeletesWithoutPrompt()throws Exception{
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),
            "-Djava.awt.headless=true","--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
            "io.doindev.codegraph.mcp.Main","--dba","--yolo","--dba-dir",directory.toString());
        command.environment().remove("CODE_GRAPH_DBA_AGENT_TOKEN");command.environment().remove("CODE_GRAPH_ROOT");command.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child=command.start();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));var writer=new java.io.PrintWriter(child.getOutputStream(),true);
            ObjectNode init=JSON.createObjectNode().put("protocolVersion","2025-06-18");init.putObject("capabilities");init.putObject("clientInfo").put("name","yolo-stdio-test").put("version","1");
            assertTrue(stdioRpc(reader,writer,executor,"initialize",init).path("result").has("serverInfo"));
            writer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            ObjectNode profile=JSON.createObjectNode().put("name","YOLO stdio").put("templateId","h2").put("url","jdbc:h2:mem:YOLO_STDIO;DB_CLOSE_DELAY=-1")
                .put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("driverClass","org.h2.Driver").put("username","sa").put("readOnly",false);
            ObjectNode input=JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","Stdio isolation test").put("saveUntested",true);input.set("profile",profile);
            JsonNode created=stdioFinish(reader,writer,executor,stdioCall(reader,writer,executor,"dba_request_connection_create",input));
            String connection=created.path("job").path("result").path("id").asText();assertFalse(connection.isBlank());
            ObjectNode sql=JSON.createObjectNode().put("connectionId",connection).put("connectionName","YOLO stdio").put("database","YOLO_STDIO").put("schema","PUBLIC").put("requestId",UUID.randomUUID().toString()).put("purpose","Stdio mutation").put("sql","CREATE TABLE PUBLIC.STDIO_ITEMS(ID INT); INSERT INTO PUBLIC.STDIO_ITEMS VALUES(1); SELECT * FROM PUBLIC.STDIO_ITEMS; DROP TABLE PUBLIC.STDIO_ITEMS");
            JsonNode result=stdioFinish(reader,writer,executor,stdioCall(reader,writer,executor,"dba_request_live_sql",sql));assertFalse(result.has("approvalChannel"));
            writer.close();assertTrue(child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS),"stdio EOF must close the runtime");
        }finally{child.destroy();if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly();executor.shutdownNow();}
    }
    static JsonNode stdioRpc(java.io.BufferedReader reader,java.io.PrintWriter writer,java.util.concurrent.ExecutorService executor,String method,JsonNode params)throws Exception{
        String id=UUID.randomUUID().toString();ObjectNode request=JSON.createObjectNode().put("jsonrpc","2.0").put("id",id).put("method",method);request.set("params",params);writer.println(request);
        return executor.submit(()->{for(String line;(line=reader.readLine())!=null;){JsonNode response=JSON.readTree(line);if(id.equals(response.path("id").asText()))return response;}throw new java.io.EOFException();}).get(20,java.util.concurrent.TimeUnit.SECONDS);
    }
    static JsonNode stdioCall(java.io.BufferedReader reader,java.io.PrintWriter writer,java.util.concurrent.ExecutorService executor,String operation,ObjectNode args)throws Exception{
        ObjectNode params=JSON.createObjectNode().put("name",operation);params.set("arguments",args);JsonNode response=stdioRpc(reader,writer,executor,"tools/call",params);
        assertFalse(response.has("error"),response.toPrettyString());JsonNode result=response.path("result");assertFalse(result.path("isError").asBoolean(),result.toPrettyString());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }
    static JsonNode stdioFinish(java.io.BufferedReader reader,java.io.PrintWriter writer,java.util.concurrent.ExecutorService executor,JsonNode initial)throws Exception{
        JsonNode result=initial;long until=System.nanoTime()+20_000_000_000L;
        while(!Set.of("complete","failed","cancelled").contains(result.path("state").asText())&&System.nanoTime()<until){Thread.sleep(20);result=stdioCall(reader,writer,executor,"dba_request_status",JSON.createObjectNode().put("requestId",initial.path("id").asText()));}
        assertEquals("complete",result.path("state").asText(),result.toPrettyString());assertFalse(result.has("authorizationReason"));return result;
    }

    static JsonNode finish(HttpClient client,String endpoint,String session,JsonNode initial)throws Exception{
        long deadline=System.nanoTime()+20_000_000_000L;JsonNode result=initial;
        while(!Set.of("complete","failed","cancelled").contains(result.path("state").asText())&&System.nanoTime()<deadline){Thread.sleep(20);result=ReusableMcpHttpTest.call(client,endpoint,session,"dba_request_status",JSON.createObjectNode().put("requestId",initial.path("id").asText()));}
        assertEquals("complete",result.path("state").asText(),result.toPrettyString());assertFalse(result.has("authorizationReason"));return result;
    }
}
