package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReusableMcpHttpTest extends DbaMcpHttpTest {
    @Test @Timeout(120) void temporaryGrantIsBoundToLogicalTransportSessionAndForgedChoicesFail()throws Exception{
        try(var client=HttpClient.newHttpClient();var server=start(0)){
            String base="http://localhost:"+server.vizPort(),endpoint="http://localhost:"+server.port()+"/mcp";
            var bootstrap=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);
            String cookie=bootstrap.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=JSON.readTree(bootstrap.body()).path("csrf").asText(),tab=UUID.randomUUID().toString();
            var presence=JSON.createObjectNode().put("tabId",tab).put("visible",true).put("focused",true).put("polling",true);
            assertEquals(200,rest(client,base,"/approvals/presence","POST",presence,cookie,csrf).statusCode());
            var profile=JSON.createObjectNode().put("name","Scoped HTTP fixture").put("templateId","h2").put("url","jdbc:h2:mem:reusable_http;DB_CLOSE_DELAY=-1")
                    .put("driverClass","org.h2.Driver").put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("saveUntested",true).put("readOnly",false);
            var saved=rest(client,base,"/connections","POST",profile,cookie,csrf);assertEquals(201,saved.statusCode(),saved.body());String connection=JSON.readTree(saved.body()).path("id").asText();
            String one=initialize(client,endpoint,null),two=initialize(client,endpoint,null);
            var first=call(client,endpoint,one,"dba_request_live_sql",sql(connection,"CREATE TABLE PUBLIC.FIRST_HTTP(ID INT)"));
            assertEquals("queued",first.path("state").asText());assertFalse(first.has("approvalChoices"));assertEquals(first.path("id"),first.path("operationId"));
            var unclaimed=review(client,base,first.path("id").asText(),tab,"",cookie,csrf,"always_similar");assertTrue(Set.of(400,403).contains(unclaimed.statusCode()),unclaimed.body());
            String lease=claim(client,base,first.path("id").asText(),tab,cookie,csrf);
            assertEquals(403,review(client,base,first.path("id").asText(),tab,lease,cookie,null,"session_similar").statusCode());
            var approved=review(client,base,first.path("id").asText(),tab,lease,cookie,csrf,"session_similar");assertEquals(200,approved.statusCode(),approved.body());
            finish(client,endpoint,one,first);
            var automatic=call(client,endpoint,one,"dba_request_live_sql",sql(connection,"CREATE TABLE PUBLIC.SECOND_HTTP(ID INT)"));
            assertFalse(automatic.has("matchedPolicy"));assertNotEquals("queued",automatic.path("state").asText());finish(client,endpoint,one,automatic);
            var other=call(client,endpoint,two,"dba_request_live_sql",sql(connection,"CREATE TABLE PUBLIC.THIRD_HTTP(ID INT)"));assertEquals("queued",other.path("state").asText());
            call(client,endpoint,two,"dba_cancel_request",JSON.createObjectNode().put("requestId",other.path("id").asText()));
            var delete=HttpRequest.newBuilder(URI.create(endpoint)).header("Mcp-Session-Id",one).DELETE().build();
            assertTrue(client.send(delete,HttpResponse.BodyHandlers.ofString()).statusCode()<300);
            var permissions=call(client,endpoint,two,"dba_get_my_permissions",JSON.createObjectNode());assertTrue(permissions.path("reusablePolicies").isEmpty());
            String fresh=initialize(client,endpoint,null);assertNotEquals(one,fresh);
            var next=call(client,endpoint,fresh,"dba_request_live_sql",sql(connection,"CREATE TABLE PUBLIC.FOURTH_HTTP(ID INT)"));assertEquals("queued",next.path("state").asText());
            call(client,endpoint,fresh,"dba_cancel_request",JSON.createObjectNode().put("requestId",next.path("id").asText()));
            for(String action:List.of("always_exact","session_similar","always_similar")){
                var dangerous=call(client,endpoint,two,"dba_request_live_sql",sql(connection,"DROP TABLE PUBLIC.FIRST_HTTP"));
                String id=dangerous.path("id").asText(),claim=claim(client,base,id,tab,cookie,csrf);
                assertEquals(400,review(client,base,id,tab,claim,cookie,csrf,action).statusCode());
                assertEquals(200,review(client,base,id,tab,claim,cookie,csrf,"reject").statusCode());
            }
        }
    }
    @Test @Timeout(90) void concurrentOperationsHideApprovalDetailsAndReportDenialAndConfiguredTimeout()throws Exception {
        try(var client=HttpClient.newHttpClient();var server=start(0)){
            String base="http://localhost:"+server.vizPort(),endpoint="http://localhost:"+server.port()+"/mcp";
            var boot=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);
            String cookie=boot.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=JSON.readTree(boot.body()).path("csrf").asText(),tab=UUID.randomUUID().toString();
            assertEquals(200,rest(client,base,"/approvals/presence","POST",JSON.createObjectNode().put("tabId",tab).put("visible",true).put("focused",true).put("polling",true),cookie,csrf).statusCode());
            assertEquals(200,rest(client,base,"/settings","PUT",JSON.createObjectNode().put("approvalTimeoutSeconds",10),cookie,csrf).statusCode());
            var profile=JSON.createObjectNode().put("name","Scoped HTTP fixture").put("templateId","h2").put("url","jdbc:h2:mem:approval_http_timeout;DB_CLOSE_DELAY=-1")
                .put("driverClass","org.h2.Driver").put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()).put("saveUntested",true).put("readOnly",false);
            String connection=JSON.readTree(rest(client,base,"/connections","POST",profile,cookie,csrf).body()).path("id").asText();String session=initialize(client,endpoint,null);
            List<JsonNode> pending=new ArrayList<>();
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                List<java.util.concurrent.Future<JsonNode>> calls=new ArrayList<>();
                for(int i=0;i<4;i++)calls.add(executor.submit(()->call(client,endpoint,session,"dba_request_live_sql",sql(connection,"SELECT 1"))));
                for(var future:calls){JsonNode result=future.get();pending.add(result);assertEquals("queued",result.path("state").asText());assertFalse(result.toString().contains("approval"));}
            }
            assertEquals(4,pending.stream().map(r->r.path("operationId").asText()).distinct().count());
            JsonNode first=pending.getFirst();String id=first.path("operationId").asText(),lease=claim(client,base,id,tab,cookie,csrf);
            assertEquals(200,review(client,base,id,tab,lease,cookie,csrf,"reject").statusCode());
            JsonNode denied=operationError(client,endpoint,session,id);assertEquals("approval_denied",denied.path("error").path("code").asText());
            String cancelled=pending.get(1).path("operationId").asText();call(client,endpoint,session,"dba_cancel_request",JSON.createObjectNode().put("operationId",cancelled));
            assertEquals(200,rest(client,base,"/settings","PUT",JSON.createObjectNode().put("approvalTimeoutSeconds",60),cookie,csrf).statusCode());
            Thread.sleep(10_100);
            for(JsonNode request:pending.subList(2,4))assertEquals("approval_timeout",operationError(client,endpoint,session,request.path("operationId").asText()).path("error").path("code").asText());
            assertTrue(JSON.readTree(Files.readString(directory.resolve("approval-settings.json"))).path("approvalTimeoutSeconds").asInt()==60);
            var fresh=call(client,endpoint,session,"dba_request_live_sql",sql(connection,"SELECT 2"));id=fresh.path("operationId").asText();lease=claim(client,base,id,tab,cookie,csrf);
            assertEquals(200,review(client,base,id,tab,lease,cookie,csrf,"approve_once").statusCode());finish(client,endpoint,session,fresh);
        }
    }
    static JsonNode operationError(HttpClient client,String endpoint,String session,String id)throws Exception {
        var input=JSON.createObjectNode().put("name","dba_request_status");input.putObject("arguments").put("operationId",id);
        var response=rpc(client,endpoint,session,null,"tools/call",input);assertEquals(200,response.statusCode());
        var result=payload(response.body()).path("result");assertTrue(result.path("isError").asBoolean(),response.body());
        assertEquals("error",result.path("structuredContent").path("meta").path("invocationState").asText());
        return JSON.readTree(result.path("content").get(0).path("text").asText());
    }
    static ObjectNode sql(String id,String text){return JSON.createObjectNode().put("connectionId",id).put("connectionName","Scoped HTTP fixture").put("requestId",UUID.randomUUID().toString()).put("sql",text).put("purpose","Isolated MCP transport QA");}
    static JsonNode call(HttpClient c,String endpoint,String session,String tool,JsonNode args)throws Exception{
        var input=JSON.createObjectNode().put("name",tool);input.set("arguments",args);var r=rpc(c,endpoint,session,null,"tools/call",input);assertEquals(200,r.statusCode(),r.body());
        var result=payload(r.body()).path("result");var value=JSON.readTree(result.path("content").get(0).path("text").asText());
        if(tool.equals("dba_cancel_request")){assertEquals("cancelled",value.path("state").asText(),r.body());}else assertFalse(result.path("isError").asBoolean(),r.body());return value;
    }
    static String claim(HttpClient c,String base,String id,String tab,String cookie,String csrf)throws Exception{
        var response=c.send(HttpRequest.newBuilder(URI.create(base+"/api/dba/approvals/"+id+"/claim")).header("Origin",base).header("Content-Type","application/json").header("Cookie",cookie).header("X-Dba-CSRF",csrf).header("X-Dba-Tab",tab).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());return JSON.readTree(response.body()).path("lease").asText();
    }
    static HttpResponse<String> review(HttpClient c,String base,String id,String tab,String lease,String cookie,String csrf,String action)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base+"/api/dba/approvals/"+id)).header("Origin",base).header("Content-Type","application/json").header("Cookie",cookie).header("X-Dba-Tab",tab).header("X-Dba-Review",lease);
        if(csrf!=null)builder.header("X-Dba-CSRF",csrf);
        return c.send(builder.POST(HttpRequest.BodyPublishers.ofString(JSON.createObjectNode().put("action",action).put("acknowledged",true).toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    static void finish(HttpClient c,String endpoint,String session,JsonNode initial)throws Exception{
        JsonNode state=initial;long until=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<until){state=call(c,endpoint,session,"dba_request_status",JSON.createObjectNode().put("requestId",initial.path("id").asText()));if(Set.of("complete","failed","cancelled").contains(state.path("state").asText()))break;Thread.sleep(20);}
        assertEquals("complete",state.path("state").asText(),state.toString());
    }
}
