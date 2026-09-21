package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorAccessHttpTest {
    @TempDir Path root;
    String base,cookie,csrf;
    HttpClient client;
    HttpResponse<String> call(String path,String method,JsonNode body,String workspace,String document,String token)throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+"/api/dba"+path)).header("Origin",base).header("Content-Type","application/json");
        if(cookie!=null)b.header("Cookie",cookie);if(token!=null)b.header("X-Dba-CSRF",token);
        if(workspace!=null)b.header("X-Dba-Workspace",workspace);if(document!=null)b.header("X-Dba-Document",document);
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void consentIsTabOwnedCsrfProtectedAndNotBypassedByYolo(boolean yolo)throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var cfg=new DbaConfig(root.resolve("dba"),64L<<20,2,100,100,15,60,"none",yolo);
        try(var runtime=new DbaRuntime(cfg,new DbaTest.MemoryVault(),true,ApprovalBroker.NO_DESKTOP);var http=HttpClient.newHttpClient()){
            client=http;server.createContext("/",runtime::handle);server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
            var boot=call("/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null,null);assertEquals(200,boot.statusCode());
            cookie=boot.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];csrf=Profiles.JSON.readTree(boot.body()).path("csrf").asText();
            String workspace=UUID.randomUUID().toString(),document=UUID.randomUUID().toString();
            var registration=Profiles.JSON.createObjectNode().put("workspaceId",workspace).put("documentId",document);
            assertEquals(403,call("/editor/register","POST",registration,null,null,"wrong").statusCode());
            assertEquals(200,call("/editor/register","POST",registration,null,null,csrf).statusCode());
            assertEquals(403,call("/workspace","GET",null,workspace,null,csrf).statusCode());
            assertEquals(403,call("/workspace","GET",null,workspace,UUID.randomUUID().toString(),csrf).statusCode());
            String principal=runtime.trustedLocalAgent(),session=UUID.randomUUID().toString();runtime.registerMcpSession(session,principal,System.currentTimeMillis()+600000);
            var request=runtime.agentCall(principal,session,"dba_request_editor_access",Profiles.JSON.createObjectNode().put("requestId","consent").put("purpose","Review a draft"));
            assertEquals("awaiting_approval",request.path("state").asText());assertFalse(request.has("authorizationOutcome"));
            String id=request.path("approvalId").asText();
            var decision=Profiles.JSON.createObjectNode().put("approvalId",id);
            assertEquals(403,call("/editor/claim","POST",decision,workspace,document,"bad").statusCode());
            String originalCookie=cookie;cookie=null;
            var second=call("/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null,null);
            cookie=second.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            String secondCsrf=Profiles.JSON.readTree(second.body()).path("csrf").asText();
            assertEquals(403,call("/editor/claim","POST",decision,workspace,document,secondCsrf).statusCode(),"A different cookie cannot impersonate the selected tab");
            cookie=originalCookie;
            assertEquals(200,call("/editor/claim","POST",decision,workspace,document,csrf).statusCode());
            assertEquals(400,call("/editor/claim","POST",decision,workspace,document,csrf).statusCode());
            var status=runtime.agentCall(principal,session,"dba_request_status",decision);
            assertEquals("paired",status.path("state").asText());assertFalse(status.has("authorizationOutcome"));
            var docs=runtime.agentCall(principal,session,"dba_list_editor_documents",Profiles.JSON.createObjectNode());
            runtime.agentCall(principal,session,"dba_create_editor_draft",Profiles.JSON.createObjectNode().put("title","Recovery.sql").put("sql","SELECT 1").put("expectedWorkspaceRevision",docs.path("workspaceRevision").asLong()));
            var saved=(com.fasterxml.jackson.databind.node.ObjectNode)Profiles.JSON.readTree(call("/workspace","GET",null,workspace,document,csrf).body());
            saved.put("expectedWorkspaceRevision",saved.path("workspaceRevision").asLong());
            ((com.fasterxml.jackson.databind.node.ObjectNode)saved.path("tabs").get(0)).put("sql","SELECT 2");
            assertEquals(200,call("/editor/leave","POST",Profiles.JSON.createObjectNode().set("workspace",saved),workspace,document,csrf).statusCode());
            assertThrows(SecurityException.class,()->runtime.agentCall(principal,session,"dba_list_editor_documents",Profiles.JSON.createObjectNode()));
            String replacement=UUID.randomUUID().toString();registration.put("documentId",replacement);
            assertEquals(200,call("/editor/register","POST",registration,null,null,csrf).statusCode());
            assertEquals("SELECT 2",Profiles.JSON.readTree(call("/workspace","GET",null,workspace,replacement,csrf).body()).path("tabs").get(0).path("sql").asText(),"Final snapshot and document departure are committed together");
            runtime.endMcpSession(session);
            assertThrows(SecurityException.class,()->runtime.agentCall(principal,session,"dba_request_status",decision));
        }finally{server.stop(0);}
    }
}
