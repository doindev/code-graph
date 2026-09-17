package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.config.loader.ConfigLoader;
import io.doindev.codegraph.dba.DbaConfig;
import io.doindev.codegraph.index.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DbaMcpHttpTest {
    static final ObjectMapper JSON=new ObjectMapper();
    @TempDir Path directory;
    HttpServer start(int ui)throws Exception{return HttpServer.start(Workspace.open(List.of(),Analyzers.discover(),ConfigLoader::load),0,ui,false,Duration.ofHours(1),new DbaConfig(directory,64L<<20,2,1000,100,5,60,ui<0?"none":"browser"));}
    @Test @Timeout(120) void browserGrantsSurviveHeadlessRestartAndTransportEnforcesIdentity()throws Exception{
        String token,otherToken,connection,previousSession,previousAnonymousSession;
        try(var client=HttpClient.newHttpClient()){
            try(var server=start(0)){
                assertEquals("127.0.0.1",server.host());
                String base="http://localhost:"+server.vizPort();
                var login=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);
                assertEquals(200,login.statusCode());String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=JSON.readTree(login.body()).path("csrf").asText();
                var input=JSON.createObjectNode().put("name","fixture profile").put("url","jdbc:h2:mem:transport").put("driverClass","org.h2.Driver").put("saveUntested",true).put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
                var saved=rest(client,base,"/connections","POST",input,cookie,csrf);assertEquals(201,saved.statusCode(),saved.body());connection=JSON.readTree(saved.body()).path("id").asText();
                var grant=JSON.createObjectNode().put("name","alice");grant.putArray("grants").addObject().put("connectionId",connection).putArray("objects").addObject().put("schema","public").put("name","items");
                assertEquals(403,rest(client,base,"/agents","POST",grant,cookie,null).statusCode());
                var created=rest(client,base,"/agents","POST",grant,cookie,csrf);assertEquals(201,created.statusCode());token=JSON.readTree(created.body()).path("token").asText();
                otherToken=JSON.readTree(rest(client,base,"/agents","POST",grant.put("name","bob"),cookie,csrf).body()).path("token").asText();
                assertFalse(rest(client,base,"/agents","GET",null,cookie,null).body().contains(token));
                assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(base+"/dba")).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                String endpoint="http://localhost:"+server.port()+"/mcp";
                previousSession=initialize(client,endpoint,token);
                previousAnonymousSession=initialize(client,endpoint,null);
            }
            try(var server=start(-1)){
                assertEquals(-1,server.vizPort());assertFalse(Files.exists(directory.resolve("browser-token")));
                String endpoint="http://localhost:"+server.port()+"/mcp";
                // The new process has lost both named-token and trusted-local session bindings.
                assertEquals(404,rpc(client,endpoint,previousSession,token,"tools/list",JSON.createObjectNode()).statusCode());
                assertEquals(404,rpc(client,endpoint,previousAnonymousSession,null,"tools/list",JSON.createObjectNode()).statusCode());
                assertEquals(403,rpc(client,endpoint,previousSession,"invalid-token","tools/list",JSON.createObjectNode()).statusCode());
                String session=initialize(client,endpoint,token),other=initialize(client,endpoint,otherToken),anonymous=initialize(client,endpoint,null);
                var list=rpc(client,endpoint,session,token,"tools/list",JSON.createObjectNode());assertEquals(200,list.statusCode());assertTrue(list.body().contains("dba_execute_read_query"));assertTrue(list.body().contains("dba_get_my_permissions"));assertFalse(list.body().contains("dba_execute_write"));assertFalse(list.body().contains("dba_request_connection_create"));assertFalse(list.body().contains("dba_request_live_sql"));assertFalse(list.body().contains("open_script_tab"));
                var params=JSON.createObjectNode().put("name","dba_list_connections");params.set("arguments",JSON.createObjectNode());
                var allowed=rpc(client,endpoint,session,token,"tools/call",params);assertFalse(payload(allowed.body()).path("result").path("isError").asBoolean(true));assertTrue(allowed.body().contains(connection));assertFalse(allowed.body().contains("jdbc:"));
                var local=rpc(client,endpoint,anonymous,null,"tools/call",params);assertFalse(payload(local.body()).path("result").path("isError").asBoolean());assertTrue(local.body().contains(connection));assertFalse(local.body().contains("jdbc:"));
                var permissionArgs=JSON.createObjectNode().put("name","dba_get_my_permissions");permissionArgs.set("arguments",JSON.createObjectNode());
                var localPermissions=rpc(client,endpoint,anonymous,null,"tools/call",permissionArgs);assertFalse(payload(localPermissions.body()).path("result").path("isError").asBoolean());assertTrue(localPermissions.body().contains("Trusted local agents"));
                var deniedArgs=JSON.createObjectNode().put("name","dba_get_metadata");deniedArgs.set("arguments",JSON.createObjectNode().put("connectionId",connection).put("connectionName","fixture profile"));
                assertTrue(payload(rpc(client,endpoint,anonymous,null,"tools/call",deniedArgs).body()).path("result").path("isError").asBoolean(),"Local identity does not automatically grant database access");
                assertEquals(403,rpc(client,endpoint,session,otherToken,"tools/call",params).statusCode());assertEquals(403,rpc(client,endpoint,session,null,"tools/call",params).statusCode());
                assertEquals(403,rpc(client,endpoint,anonymous,token,"tools/call",params).statusCode());
                var replay=HttpRequest.newBuilder(URI.create(endpoint)).header("Accept","text/event-stream").header("Mcp-Session-Id",session).header("Authorization","Bearer "+otherToken).GET().build();assertEquals(403,client.send(replay,HttpResponse.BodyHandlers.ofString()).statusCode());
                params.put("name","dba_execute_read_query");params.set("arguments",JSON.createObjectNode().put("connectionName","fixture profile").put("connectionId",connection).put("sql","DELETE FROM public.items"));assertTrue(payload(rpc(client,endpoint,session,token,"tools/call",params).body()).path("result").path("isError").asBoolean());
                params.put("name","dba_get_metadata");params.set("arguments",JSON.createObjectNode().put("connectionName","fixture profile"));assertTrue(rpc(client,endpoint,session,token,"tools/call",params).body().contains("items"));
                params.put("name","dba_job_status");params.set("arguments",JSON.createObjectNode().put("jobId",UUID.randomUUID().toString()));assertTrue(payload(rpc(client,endpoint,other,otherToken,"tools/call",params).body()).path("result").path("isError").asBoolean());
            }
            var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"io.doindev.codegraph.mcp.Main","--dba","--dba-dir",directory.toString(),"--dba-approval-mode","none");
            // Token supplied to the process environment, never in command arguments or protocol data.
            command.environment().put("CODE_GRAPH_DBA_AGENT_TOKEN",token);command.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process child=command.start();
            var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{
                var reader=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));var writer=new java.io.PrintWriter(child.getOutputStream(),true);
                writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"stdio-test\",\"version\":\"1\"}}}");
                assertTrue(executor.submit(reader::readLine).get(20,java.util.concurrent.TimeUnit.SECONDS).contains("serverInfo"));
                writer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
                writer.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"dba_list_connections\",\"arguments\":{}}}");
                String result=executor.submit(reader::readLine).get(20,java.util.concurrent.TimeUnit.SECONDS);assertTrue(result.contains(connection));assertFalse(JSON.readTree(result).path("result").path("isError").asBoolean(true));
            }finally{child.destroy();if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly();executor.shutdownNow();}
        }
    }
    @Test @Timeout(120) void deletedSessionReturnsNotFoundAndClientCanReinitialize()throws Exception{
        try(var client=HttpClient.newHttpClient();var server=start(-1)){
            String endpoint="http://localhost:"+server.port()+"/mcp";
            String session=initialize(client,endpoint,null);
            var delete=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15))
                    .header("Mcp-Session-Id",session).DELETE().build();
            int deleted=client.send(delete,HttpResponse.BodyHandlers.ofString()).statusCode();
            assertTrue(deleted>=200&&deleted<300,"Session termination failed: "+deleted);
            assertEquals(404,rpc(client,endpoint,session,null,"tools/list",JSON.createObjectNode()).statusCode());
            var staleStream=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15))
                    .header("Mcp-Session-Id",session).header("Accept","text/event-stream").GET().build();
            assertEquals(404,client.send(staleStream,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404,client.send(delete,HttpResponse.BodyHandlers.ofString()).statusCode());
            String fresh=initialize(client,endpoint,null);
            assertNotEquals(session,fresh);
            var tools=rpc(client,endpoint,fresh,null,"tools/list",JSON.createObjectNode());
            assertEquals(200,tools.statusCode());
            assertTrue(tools.body().contains("list_projects"));
        }
    }
    @Test @Timeout(60) void stdioWithoutAgentTokenUsesTrustedLocalIdentity()throws Exception{
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"--enable-native-access=ALL-UNNAMED","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),"io.doindev.codegraph.mcp.Main","--dba","--dba-dir",directory.toString(),"--dba-approval-mode","none");
        command.environment().remove("CODE_GRAPH_DBA_AGENT_TOKEN");command.environment().remove("CODE_GRAPH_ROOT");command.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process child=command.start();var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()));var writer=new java.io.PrintWriter(child.getOutputStream(),true);
            writer.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"local-stdio-test\",\"version\":\"1\"}}}");
            assertTrue(executor.submit(reader::readLine).get(20,java.util.concurrent.TimeUnit.SECONDS).contains("serverInfo"));
            writer.println("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            writer.println("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"dba_get_my_permissions\",\"arguments\":{}}}");
            String result=executor.submit(reader::readLine).get(20,java.util.concurrent.TimeUnit.SECONDS);assertTrue(result.contains("Trusted local agents"));assertFalse(JSON.readTree(result).path("result").path("isError").asBoolean(true));
        }finally{child.destroy();if(!child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS))child.destroyForcibly();executor.shutdownNow();}
    }
    static String initialize(HttpClient c,String url,String token)throws Exception{
        var args=JSON.createObjectNode().put("protocolVersion","2025-06-18");args.putObject("capabilities");args.putObject("clientInfo").put("name","dba-test").put("version","1");
        var r=rpc(c,url,null,token,"initialize",args);assertEquals(200,r.statusCode(),r.body());return r.headers().firstValue("Mcp-Session-Id").orElseThrow();
    }
    static HttpResponse<String> rpc(HttpClient c,String url,String session,String token,String method,JsonNode params)throws Exception{
        var body=JSON.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method",method);body.set("params",params);
        var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json").header("Accept","application/json, text/event-stream");if(session!=null)b.header("Mcp-Session-Id",session);if(token!=null)b.header("Authorization","Bearer "+token);
        return c.send(b.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    static JsonNode payload(String body)throws Exception{for(String line:body.split("\n"))if(line.startsWith("data: "))return JSON.readTree(line.substring(6));return JSON.readTree(body);}
    static HttpResponse<String> rest(HttpClient c,String base,String path,String method,JsonNode data,String cookie,String csrf)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+"/api/dba"+path)).header("Origin",base).header("Content-Type","application/json");if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-Dba-CSRF",csrf);return c.send(b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
}
