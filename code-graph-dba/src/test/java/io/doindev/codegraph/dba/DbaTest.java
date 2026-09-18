package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DbaTest {
    @TempDir Path root;
    static final class MemoryVault implements Vault {
        final Map<String,byte[]> secrets=new HashMap<>();
        public void put(String id,byte[] secret){secrets.put(id,secret.clone());}
        public byte[] get(String id){if(!secrets.containsKey(id))throw new IllegalStateException("Missing vault entry");return secrets.get(id).clone();}
        public void remove(String id){secrets.remove(id);}
    }
    ObjectNode input()throws Exception{return Profiles.JSON.createObjectNode().put("name","test").put("driverClass","org.h2.Driver")
            .put("jar",Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())
            .put("url","jdbc:h2:mem:dba_"+UUID.randomUUID()).put("username","sa").put("readOnly",true);}
    @Test void configurationIsExplicitAndBounded(){assertTrue(DbaConfig.parse(new String[]{}).isEmpty());var c=DbaConfig.parse(new String[]{"--dba"}).orElseThrow();assertEquals(256L<<20,c.memoryBytes());assertEquals(4,c.concurrency());assertEquals(60,c.decisionTimeoutSeconds());assertEquals(75,DbaConfig.parse(new String[]{"--dba","--dba-decision-timeout","75"}).orElseThrow().decisionTimeoutSeconds());assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba-memory","64m"}));assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-concurrency","99"}));assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-decision-timeout","5"}));assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-typo","1"}));}
    @Test void secretsNeverPersistInProfilesAndVaultReplacementsAreRemoved()throws Exception{
        var vault=new MemoryVault();String id;
        try(var p=new Profiles(root.resolve("profiles"),vault)){
            var b=input().put("password","test-secret-never-persist");var saved=p.put(null,b);id=saved.path("id").asText();
            assertFalse(saved.has("credentialRef"));assertFalse(saved.has("password"));assertEquals(1,vault.secrets.size());
            assertFalse(Files.readString(root.resolve("profiles/profiles.json")).contains("test-secret-never-persist"));
            p.put(id,b.put("password","replacement"));assertEquals(1,vault.secrets.size());
            assertFalse(p.publicList().toString().contains("credentialRef"));assertThrows(java.io.IOException.class,()->new Profiles(root.resolve("profiles"),vault));
        }
        try(var p=new Profiles(root.resolve("profiles"),vault)){assertEquals(1,p.publicList().size());p.remove(id);assertTrue(vault.secrets.isEmpty());}
    }
    @Test void secretsEmbeddedInUrlAreRejected()throws Exception{try(var p=new Profiles(root,new MemoryVault())){var b=input().put("url","jdbc:postgresql://localhost/test?password=bad");assertThrows(IllegalArgumentException.class,()->p.put(null,b));}}
    @Test void unrelatedDataDirectoryIsNotAdoptedOrModified()throws Exception{Path existing=root.resolve("keep.txt");Files.writeString(existing,"user content");assertThrows(java.io.IOException.class,()->new Profiles(root,new MemoryVault()));assertEquals("user content",Files.readString(existing));assertFalse(Files.exists(root.resolve(".code-graph-dba-owner")));}
    @ParameterizedTest @ValueSource(strings={"DROP DATABASE x","TRUNCATE x","DELETE FROM x","UPDATE x SET a=1","SELECT 1; DELETE FROM x","SELECT dangerous()","SELECT CAST('a' AS custom_type)","SELECT * INTO x FROM y","SELECT * FROM x FOR UPDATE","WITH gone AS (DELETE FROM x RETURNING *) SELECT * FROM gone","SELECT id FROM x ORDER BY dangerous()","SELECT id FROM x GROUP BY dangerous()","SELECT id FROM x WHERE dangerous() = 1","SELECT * FROM x JOIN y ON dangerous() = 1"})
    void unsupportedWritesAndReadSideEffectsFailClosed(String sql){assertThrows(IllegalArgumentException.class,()->SqlReadGuard.validate(sql));}
    @Test void simpleParameterizedSelectValuesAndQuotedSemicolonsWork(){assertEquals("SELECT 1",SqlReadGuard.validate("SELECT 1"));assertEquals("VALUES (1), (2)",SqlReadGuard.validate("VALUES (1), (2)"));assertDoesNotThrow(()->SqlReadGuard.validate("SELECT ';' AS x FROM public.example WHERE id = ?"));}
    @Test void envPlaceholderFailsWithoutEchoingItsValue(){assertThrows(IllegalArgumentException.class,()->Profiles.expand("${CODE_GRAPH_DBA_MISSING_TEST_1288}"));}
    @Test void boundedReadDuplicateLabelsAndJobOwnership()throws Exception{
        var cfg=new DbaConfig(root,64L<<20,2,10,5,10);
        try(var p=new Profiles(root,new MemoryVault());var connections=new Connections(p);var jobs=new QueryJobs(connections,cfg,s->true)){
            String id=p.put(null,input()).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){c.setAutoCommit(true);s.execute("CREATE TABLE TEST AS SELECT X FROM SYSTEM_RANGE(1, 100)");}
            JsonNode submitted=jobs.query("alice",id,"SELECT X AS SAME, X AS SAME FROM PUBLIC.TEST",Profiles.JSON.createArrayNode());
            String job=submitted.path("id").asText();assertThrows(IllegalArgumentException.class,()->jobs.status("bob",job));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);JsonNode result;
            do{result=jobs.status("alice",job);if(result.path("finished").asLong()>0)break;Thread.sleep(20);}while(System.nanoTime()<deadline);
            assertEquals("complete",result.path("state").asText(),result.toString());
            assertEquals(10,result.path("result").path("rows").size());assertTrue(result.path("result").path("truncated").asBoolean());
            assertNotEquals(result.path("result").path("columns").get(0).path("id"),result.path("result").path("columns").get(1).path("id"));
            jobs.remove("alice",job);assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
    @Test void httpAuthenticationProfilesQueriesAndCleanup()throws Exception{
        var vault=new MemoryVault();var cfg=new DbaConfig(root.resolve("http"),64L<<20,2,10,5,10);
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(cfg,vault);var client=HttpClient.newHttpClient()){
            server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort();
            assertEquals(200,request(client,base,"/dba","GET",null,null,null).statusCode());
            assertEquals(403,request(client,base,"/api/dba/connections","GET",null,null,null).statusCode());
            var bad=client.send(HttpRequest.newBuilder(URI.create(base+"/api/dba/login")).header("Origin","http://evil.invalid").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,bad.statusCode());
            var logged=request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);assertEquals(200,logged.statusCode());
            String cookie=logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(logged.body()).path("csrf").asText();
            assertTrue(logged.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));
            String tabId=UUID.randomUUID().toString();ObjectNode workspace=Profiles.JSON.createObjectNode().put("version",1).put("active",tabId).putNull("lastSelected");ObjectNode tab=workspace.putArray("tabs").addObject().put("id",tabId).put("title","draft.sql").put("sql","SELECT 1 -- unsaved").put("dirty",true).putNull("connection").put("editorRatio",.61).put("outputView","executionLog");tab.putObject("railToggles").put("serverOutput",false).put("executionLog",true).put("sqlVariables",false);
            assertEquals(403,request(client,base,"/api/dba/workspace","PUT",workspace,cookie,null).statusCode());assertEquals(200,request(client,base,"/api/dba/workspace","PUT",workspace,cookie,csrf).statusCode());JsonNode recovered=Profiles.JSON.readTree(request(client,base,"/api/dba/workspace","GET",null,cookie,null).body());assertEquals(tabId,recovered.path("active").asText());assertEquals("SELECT 1 -- unsaved",recovered.path("tabs").get(0).path("sql").asText());assertEquals(.61,recovered.path("tabs").get(0).path("editorRatio").asDouble(),.001);assertTrue(recovered.path("tabs").get(0).path("railToggles").path("executionLog").asBoolean());assertEquals(1,recovered.path("workspaceRevision").asLong());
            ObjectNode stale=workspace.deepCopy();stale.put("expectedWorkspaceRevision",0);assertEquals(400,request(client,base,"/api/dba/workspace","PUT",stale,cookie,csrf).statusCode(),"stale browser autosaves must not overwrite paired edits");workspace.put("expectedWorkspaceRevision",1);
            assertEquals(403,request(client,base,"/api/dba/connections","POST",input(),cookie,null).statusCode());
            var saved=request(client,base,"/api/dba/connections","POST",input().put("saveUntested",true),cookie,csrf);assertEquals(201,saved.statusCode(),saved.body());String id=Profiles.JSON.readTree(saved.body()).path("id").asText();
            String tableTabId=UUID.randomUUID().toString();ObjectNode tableTab=workspace.withArray("tabs").addObject().put("id",tableTabId).put("type","table").put("title","ITEMS").put("connection",id).put("inner","diagram");tableTab.putObject("table").put("key","relation:ITEMS").putObject("parent").put("kind","tables").put("schema","PUBLIC");workspace.put("active",tableTabId);
            assertEquals(200,request(client,base,"/api/dba/workspace","PUT",workspace,cookie,csrf).statusCode());var mixed=Profiles.JSON.readTree(request(client,base,"/api/dba/workspace","GET",null,cookie,null).body());assertEquals(2,mixed.path("tabs").size());assertEquals("diagram",mixed.path("tabs").get(1).path("inner").asText());assertEquals("SELECT 1 -- unsaved",mixed.path("tabs").get(0).path("sql").asText());assertFalse(mixed.path("tabs").get(1).has("rows"));
            tableTab.put("inner","invalid");assertEquals(400,request(client,base,"/api/dba/workspace","PUT",workspace,cookie,csrf).statusCode());tableTab.put("inner","data");
            var prepare=Profiles.JSON.createObjectNode().put("connectionId",id).put("key","relation:ITEMS");prepare.putObject("parent").put("kind","tables").put("schema","PUBLIC");assertEquals(403,request(client,base,"/api/dba/metadata/table-query","POST",prepare,cookie,null).statusCode());
            var query=Profiles.JSON.createObjectNode().put("connectionId",id).put("sql","SELECT ? AS A, 'second' AS A");query.putArray("parameters").add(42);
            var response=request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf);assertEquals(202,response.statusCode(),response.body());String job=Profiles.JSON.readTree(response.body()).path("id").asText();
            JsonNode result=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            do{var polled=request(client,base,"/api/dba/jobs/"+job,"GET",null,cookie,null);result=Profiles.JSON.readTree(polled.body());if(result.path("finished").asLong()>0)break;Thread.sleep(25);}while(System.nanoTime()<deadline);
            assertEquals("complete",result.path("state").asText(),result.toString());assertEquals(2,result.path("result").path("columns").size());assertEquals("42",result.path("result").path("rows").get(0).get(0).asText());
            var secondLogin=request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);String otherCookie=secondLogin.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];assertEquals(0,Profiles.JSON.readTree(request(client,base,"/api/dba/workspace","GET",null,otherCookie,null).body()).path("tabs").size(),"workspace state must be isolated per browser session");
            assertEquals(400,request(client,base,"/api/dba/jobs/"+job,"GET",null,otherCookie,null).statusCode());
            assertEquals(200,request(client,base,"/api/dba/jobs/"+job,"DELETE",null,cookie,csrf).statusCode());
            var script=Profiles.JSON.createObjectNode().put("connectionId",id).put("sql","SELECT 1; SELECT * FROM absent_table; SELECT 2");script.putArray("parameters");var scriptStart=request(client,base,"/api/dba/query/execute","POST",script,cookie,csrf);assertEquals(202,scriptStart.statusCode());String scriptJob=Profiles.JSON.readTree(scriptStart.body()).path("id").asText();JsonNode paused=null;
            do{paused=Profiles.JSON.readTree(request(client,base,"/api/dba/jobs/"+scriptJob,"GET",null,cookie,null).body());if(paused.path("state").asText().equals("awaiting_decision"))break;Thread.sleep(20);}while(System.nanoTime()<deadline+TimeUnit.SECONDS.toNanos(10));assertEquals("awaiting_decision",paused.path("state").asText(),paused.toString());String decisionId=paused.path("decision").path("id").asText();ObjectNode decision=Profiles.JSON.createObjectNode().put("decisionId",decisionId).put("action","continue");assertEquals(403,request(client,base,"/api/dba/jobs/"+scriptJob+"/decision","POST",decision,cookie,null).statusCode());assertEquals(400,request(client,base,"/api/dba/jobs/"+scriptJob+"/decision","POST",decision,otherCookie,Profiles.JSON.readTree(secondLogin.body()).path("csrf").asText()).statusCode());assertEquals(200,request(client,base,"/api/dba/jobs/"+scriptJob+"/decision","POST",decision,cookie,csrf).statusCode());
            do{result=Profiles.JSON.readTree(request(client,base,"/api/dba/jobs/"+scriptJob,"GET",null,cookie,null).body());if(result.path("finished").asLong()>0)break;Thread.sleep(20);}while(System.nanoTime()<deadline+TimeUnit.SECONDS.toNanos(10));assertEquals("complete",result.path("state").asText(),result.toString());assertEquals(2,result.path("result").path("results").size());assertEquals(1,result.path("result").path("errors").size());assertEquals(200,request(client,base,"/api/dba/jobs/"+scriptJob,"DELETE",null,cookie,csrf).statusCode());
            var rename=Profiles.JSON.createObjectNode().put("expectedName","test").put("name","renamed connection");
            assertEquals(403,request(client,base,"/api/dba/connections/"+id+"/rename","PUT",rename,cookie,null).statusCode());
            assertEquals(200,request(client,base,"/api/dba/connections/"+id+"/rename","PUT",rename,cookie,csrf).statusCode());
            var object=Profiles.JSON.createObjectNode().put("connectionId",id).put("key","object:test").put("action","delete");object.putObject("parent").put("kind","tables").put("schema","PUBLIC");
            assertEquals(403,request(client,base,"/api/dba/metadata/object/action","POST",object,cookie,null).statusCode());
            assertEquals(400,request(client,base,"/api/dba/metadata/object/action","POST",object,cookie,csrf).statusCode());
            assertEquals(200,request(client,base,"/api/dba/connections/"+id,"DELETE",null,cookie,csrf).statusCode());
            assertEquals(404,request(client,base,"/dba/not-a-file.js","GET",null,null,null).statusCode());
        }finally{server.stop(0);}
        assertFalse(Files.exists(cfg.directory().resolve("browser-token")));
    }
    static HttpResponse<String> request(HttpClient c,String base,String path,String method,JsonNode data,String cookie,String csrf)throws Exception{
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Origin",base).header("Content-Type","application/json");if(cookie!=null)b.header("Cookie",cookie);if(csrf!=null)b.header("X-Dba-CSRF",csrf);b.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data.toString()));return c.send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
}
