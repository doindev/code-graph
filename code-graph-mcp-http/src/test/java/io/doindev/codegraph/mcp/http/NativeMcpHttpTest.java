package io.doindev.codegraph.mcp.http;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.doindev.codegraph.mcp.http.DbaMcpHttpTest.*;
import java.net.http.HttpClient;
import java.util.*;

/** Real HTTP/MCP and browser security boundaries; deliberately never connects to a database. */
class NativeMcpHttpTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    HttpServer start(int ui)throws Exception{
        return HttpServer.start(io.doindev.codegraph.index.Workspace.open(List.of(),io.doindev.codegraph.index.Analyzers.discover(),io.doindev.codegraph.config.loader.ConfigLoader::load),
                0,ui,false,java.time.Duration.ofHours(1),new io.doindev.codegraph.dba.DbaConfig(directory,128L<<20,2,1000,100,5,60,"browser"));
    }
    @Test @Timeout(60) void changeStreamReadRequiresAgentApprovalAndBrowserCsrf()throws Exception{
        try(var client=HttpClient.newHttpClient();var server=start(0)){
            String base="http://localhost:"+server.vizPort(),endpoint="http://localhost:"+server.port()+"/mcp";
            var login=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=JSON.readTree(login.body()).path("csrf").asText();
            var profile=JSON.createObjectNode().put("name","Stream fixture").put("templateId","mongodb-native").put("url","mongodb://127.0.0.1:1").put("saveUntested",true);profile.putObject("nativeOptions").put("database","app").put("topology","replica_set").put("replicaSet","fixture");
            var saved=rest(client,base,"/connections","POST",profile,cookie,csrf);assertEquals(201,saved.statusCode());String id=JSON.readTree(saved.body()).path("id").asText(),session=initialize(client,endpoint,null);
            var input=JSON.createObjectNode().put("connectionId",id).put("connectionName","Stream fixture").put("database","app").put("collection","items").put("requestId",UUID.randomUUID().toString()).put("purpose","Read-only change-stream review");input.putObject("command").put("watch","items").put("waitMillis",0);
            var pending=call(client,endpoint,session,"dba_request_native_command",input);assertEquals("awaiting_approval",pending.path("state").asText());assertFalse(pending.has("jobId"));assertFalse(pending.path("mutation").asBoolean());
            assertEquals(403,rest(client,base,"/native/execute","POST",input,cookie,null).statusCode());
            assertEquals("cancelled",call(client,endpoint,session,"dba_cancel_request",JSON.createObjectNode().put("approvalId",pending.path("id").asText())).path("state").asText());
            var settings=JSON.readTree(rest(client,base,"/settings","GET",null,cookie,null).body());assertEquals(0,settings.path("nativeClients").path("clients").asInt());assertEquals(0,settings.path("nativeClients").path("activeChangeStreamCursors").asInt());
        }
    }
    private JsonNode call(HttpClient client,String endpoint,String session,String name,JsonNode args)throws Exception{
        var params=JSON.createObjectNode().put("name",name);params.set("arguments",args);
        var reply=payload(rpc(client,endpoint,session,null,"tools/call",params).body()).path("result");
        // The existing contract marks terminal cancelled jobs/requests as isError;
        // cancellation remains structured, not a malformed JSON-RPC response.
        assertEquals(name.equals("dba_cancel_request"),reply.path("isError").asBoolean(),reply.toPrettyString());
        JsonNode result=JSON.readTree(reply.path("content").get(0).path("text").asText());
        assertEquals(result,reply.path("structuredContent").path("data"));return result;
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings={"redis","redis-transaction","redis-stream","redis-pipeline","redis-values","mongodb","mongodb-settings","mongodb-transaction"})
    @Timeout(60) void nativeRequestsAreReviewedAndBrowserMutationCannotBypassItsPlan(String scenario)throws Exception{
        String engine=scenario.startsWith("mongodb")?"mongodb":"redis";
        try(var client=HttpClient.newHttpClient();var server=start(0)){
            String base="http://localhost:"+server.vizPort(),endpoint="http://localhost:"+server.port()+"/mcp";
            var login=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=JSON.readTree(login.body()).path("csrf").asText();
            String database=engine.equals("mongodb")?"app":"0";
            var profile=JSON.createObjectNode().put("name","Native protocol fixture").put("templateId",engine+"-native").put("url",engine+"://127.0.0.1:1").put("readOnly",false).put("saveUntested",true);
            profile.putObject("nativeOptions").put("database",database);
            if(scenario.equals("mongodb-transaction"))profile.withObject("nativeOptions").put("topology","replica_set").put("replicaSet","fixture");
            var saved=rest(client,base,"/connections","POST",profile,cookie,csrf);assertEquals(201,saved.statusCode(),saved.body());
            String id=JSON.readTree(saved.body()).path("id").asText(),session=initialize(client,endpoint,null);
            assertTrue(rpc(client,endpoint,session,null,"tools/list",JSON.createObjectNode()).body().contains("dba_request_native_command"));
            var input=JSON.createObjectNode().put("connectionId",id).put("connectionName","Native protocol fixture").put("database",database)
                    .put("requestId",UUID.randomUUID().toString()).put("purpose","Review-only native HTTP regression");
            if(scenario.equals("mongodb-transaction"))input.put("collection","items").putObject("command").putArray("transaction").addObject().put("insert","items").putArray("documents").addObject().put("value","reviewed");
            else if(scenario.equals("mongodb-settings"))input.put("collection","items").putObject("command").put("collMod","items").put("cappedMax",200);
            else if(engine.equals("mongodb"))input.put("collection","items").putObject("command").put("renameCollection","app.items").put("to","app.next").put("dropTarget",false);
            else if(scenario.equals("redis-transaction"))input.putObject("command").putArray("transaction").addArray().add("SET").add("reviewed-key").add("never-executed");
            else if(scenario.equals("redis-pipeline"))input.putObject("command").putArray("pipeline").addArray().add("SET").add("reviewed-key").add("never-executed");
            else if(scenario.equals("redis-values"))input.putArray("command").add("PFCOUNT").add("reviewed-key");
            else if(scenario.equals("redis-stream"))input.putArray("command").add("XREADGROUP").add("GROUP").add("workers").add("consumer").add("COUNT").add("2").add("STREAMS").add("reviewed-stream").add(">");
            else input.putArray("command").add("SET").add("reviewed-key").add("never-executed");
            JsonNode pending=call(client,endpoint,session,"dba_request_native_command",input);
            assertEquals("awaiting_approval",pending.path("state").asText());assertFalse(pending.has("jobId"));
            assertEquals(database,pending.path("target").path("database").asText());
            if(scenario.equals("redis-stream")){assertTrue(pending.path("mutation").asBoolean());assertFalse(pending.path("eligiblePersistentRead").asBoolean());assertTrue(pending.path("transactionNotice").asText().contains("pending/delivery"));}
            if(scenario.equals("redis-transaction"))assertTrue(pending.path("transactionNotice").asText().contains("does not roll back"));
            if(scenario.equals("redis-pipeline")){assertTrue(pending.path("mutation").asBoolean());assertFalse(pending.path("eligiblePersistentRead").asBoolean());assertTrue(pending.path("transactionNotice").asText().contains("not transactions"));}
            if(scenario.equals("redis-values")){assertTrue(pending.path("mutation").asBoolean());assertFalse(pending.path("eligiblePersistentRead").asBoolean());assertTrue(pending.path("transactionNotice").asText().contains("cached cardinality"));}
            if(scenario.equals("mongodb-transaction"))assertTrue(pending.path("transactionNotice").asText().contains("atomic"));
            else if(engine.equals("mongodb")){
                assertTrue(pending.path("destructive").asBoolean());
                if(scenario.equals("mongodb-settings")){
                    assertEquals("app.items",pending.path("affectedNamespaces").get(0).asText());
                    assertTrue(pending.path("transactionNotice").asText().contains("delete"));
                }else assertEquals("app.next",pending.path("affectedNamespaces").get(1).asText());
            }
            assertEquals(400,rest(client,base,"/native/execute","POST",input,cookie,csrf).statusCode());
            assertEquals(403,rest(client,base,"/native/prepare","POST",input,cookie,null).statusCode());
            var prepared=rest(client,base,"/native/prepare","POST",input,cookie,csrf);assertEquals(200,prepared.statusCode(),prepared.body());
            String plan=JSON.readTree(prepared.body()).path("id").asText();
            var second=rest(client,base,"/bootstrap","POST",JSON.createObjectNode(),null,null);
            String otherCookie=second.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],otherCsrf=JSON.readTree(second.body()).path("csrf").asText();
            assertEquals(403,rest(client,base,"/native/apply","POST",JSON.createObjectNode().put("planId",plan),otherCookie,otherCsrf).statusCode());
            assertEquals(200,rest(client,base,"/native/reviews/"+plan,"DELETE",null,cookie,csrf).statusCode());
            assertEquals(403,rest(client,base,"/native/apply","POST",JSON.createObjectNode().put("planId",plan),cookie,csrf).statusCode());
            assertEquals("cancelled",call(client,endpoint,session,"dba_cancel_request",JSON.createObjectNode().put("requestId",pending.path("id").asText())).path("state").asText());
            JsonNode settings=JSON.readTree(rest(client,base,"/settings","GET",null,cookie,null).body());
            assertEquals(0,settings.path("nativeClients").path("clients").asInt());assertEquals(0,settings.path("nativeClients").path("retainedReviews").asInt());
        }
    }
}
