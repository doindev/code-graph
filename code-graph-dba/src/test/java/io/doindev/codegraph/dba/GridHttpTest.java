package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GridHttpTest {
    @TempDir Path root;
    @Test void rowLimitsApplyToReadOnlyAndPageableResultsWithoutWideningPaging()throws Exception{
        var cfg=new DbaConfig(root.resolve("limits"),128L<<20,2,1000,100,15);
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(cfg,new DbaTest.MemoryVault());var client=HttpClient.newHttpClient()){
            server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort();
            var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(login.body()).path("csrf").asText();
            ObjectNode input=new DbaTest().input().put("saveUntested",true);input.put("url",input.path("url").asText()+";DB_CLOSE_DELAY=-1");
            var created=DbaTest.request(client,base,"/api/dba/connections","POST",input,cookie,csrf);
            assertEquals(201,created.statusCode(),created.body());String id=Profiles.JSON.readTree(created.body()).path("id").asText();
            ObjectNode query=Profiles.JSON.createObjectNode().put("connectionId",id).put("sql","CREATE TABLE HTTP_LIMIT(ID INT PRIMARY KEY); INSERT INTO HTTP_LIMIT SELECT X FROM SYSTEM_RANGE(1,451)");
            query.putArray("parameters");finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf));
            query.put("sql","SELECT * FROM HTTP_LIMIT ORDER BY ID").put("rowLimit",350);
            JsonNode rows=finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf)).path("result").path("results").get(0);
            assertEquals(350,rows.path("rows").size());assertEquals(350,rows.path("grid").path("page").path("limit").asInt());
            String database=rows.path("grid").path("database").asText();
            for(boolean targeted:new boolean[]{false,true}){
                if(targeted)query.put("database",database);else query.remove("database");
                for(JsonNode invalid:Profiles.JSON.readTree("[0,-1,1001,1.5,\"7\",null,true,2147483648]")){
                    query.set("rowLimit",invalid);var response=DbaTest.request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf);
                    assertEquals(400,response.statusCode(),response.body());assertTrue(response.body().contains("rowLimit"));
                }
                query.put("rowLimit",17).put("sql","SELECT ID+1 AS NEXT_ID FROM HTTP_LIMIT ORDER BY ID");
                rows=finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf)).path("result").path("results").get(0);
                assertEquals(17,rows.path("rows").size());var grid=rows.path("grid");assertTrue(grid.path("capabilities").path("rowLimit").asBoolean(),grid.toString());assertFalse(grid.path("capabilities").path("page").asBoolean());assertFalse(grid.path("capabilities").path("edit").asBoolean());
                String endpoint="/api/dba/grids/"+grid.path("id").asText();ObjectNode resize=Profiles.JSON.createObjectNode().put("revision",1).put("direction","first").put("limit",9);
                assertEquals(403,DbaTest.request(client,base,endpoint+"/reload","POST",resize,cookie,null).statusCode());
                for(JsonNode invalid:Profiles.JSON.readTree("[0,-1,1001,1.5,\"7\",null,true,2147483648]")){
                    resize.set("limit",invalid);var response=finishAny(client,base,cookie,DbaTest.request(client,base,endpoint+"/reload","POST",resize,cookie,csrf));assertEquals("failed",response.path("state").asText());assertTrue(response.path("error").asText().contains("integer"),response.toString());
                    DbaTest.request(client,base,"/api/dba/jobs/"+response.path("id").asText(),"DELETE",null,cookie,csrf);
                }
                resize.put("limit",9);
                var reloaded=finish(client,base,cookie,csrf,DbaTest.request(client,base,endpoint+"/reload","POST",resize,cookie,csrf)).path("result");
                assertEquals(9,reloaded.path("rows").size());assertEquals("2",reloaded.path("rows").get(0).get(0).asText());assertEquals(9,reloaded.path("grid").path("page").path("limit").asInt());
                resize.put("revision",2).put("direction","refresh").remove("limit");
                assertEquals(9,finish(client,base,cookie,csrf,DbaTest.request(client,base,endpoint+"/reload","POST",resize,cookie,csrf)).path("result").path("rows").size());
                resize.put("revision",3).put("direction","next");
                assertEquals("failed",finishAny(client,base,cookie,DbaTest.request(client,base,endpoint+"/reload","POST",resize,cookie,csrf)).path("state").asText());
            }
            query.remove("database");query.put("sql","SELECT ID+1 AS NEXT_ID FROM HTTP_LIMIT ORDER BY ID LIMIT 5").put("rowLimit",17);
            rows=finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf)).path("result").path("results").get(0);
            assertEquals(5,rows.path("rows").size(),"Authored SQL limits remain authoritative");
            var grid=rows.path("grid");var resize=Profiles.JSON.createObjectNode().put("revision",1).put("direction","first").put("limit",30);
            assertEquals(5,finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/grids/"+grid.path("id").asText()+"/reload","POST",resize,cookie,csrf)).path("result").path("rows").size());
            query.put("sql","CALL 7");
            rows=finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/query/execute","POST",query,cookie,csrf)).path("result").path("results").get(0);
            grid=rows.path("grid");assertFalse(grid.path("capabilities").path("rowLimit").asBoolean(),"Procedure results must not become replayable via row limiting");
            assertEquals("failed",finishAny(client,base,cookie,DbaTest.request(client,base,"/api/dba/grids/"+grid.path("id").asText()+"/reload","POST",resize,cookie,csrf)).path("state").asText());
        }finally{server.stop(0);}
    }
    @Test void gridRoutesRequireOwnershipCsrfAndOneTransferDownloads()throws Exception{
        var cfg=new DbaConfig(root.resolve("http"),128L<<20,2,1000,100,15);
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(cfg,new DbaTest.MemoryVault());var client=HttpClient.newHttpClient()){
            server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort();
            var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(login.body()).path("csrf").asText();
            var second=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
            String otherCookie=second.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],otherCsrf=Profiles.JSON.readTree(second.body()).path("csrf").asText();
            ObjectNode input=new DbaTest().input().put("saveUntested",true);
            input.put("url",input.path("url").asText()+";DB_CLOSE_DELAY=-1");
            var created=DbaTest.request(client,base,"/api/dba/connections","POST",input,cookie,csrf);assertEquals(201,created.statusCode(),created.body());
            String id=Profiles.JSON.readTree(created.body()).path("id").asText();
            var sql=Profiles.JSON.createObjectNode().put("connectionId",id).put("sql","CREATE TABLE HTTP_GRID(ID INT PRIMARY KEY, NAME VARCHAR(20)); INSERT INTO HTTP_GRID VALUES(1,'first'),(2,'second'); SELECT * FROM HTTP_GRID");
            sql.putArray("parameters");
            JsonNode query=finish(client,base,cookie,csrf,DbaTest.request(client,base,"/api/dba/query/execute","POST",sql,cookie,csrf));
            var grid=query.path("result").path("results");JsonNode descriptor=null;for(JsonNode result:grid)if(result.path("kind").asText().equals("rows"))descriptor=result.path("grid");
            assertNotNull(descriptor);String endpoint="/api/dba/grids/"+descriptor.path("id").asText();
            assertEquals(200,DbaTest.request(client,base,endpoint,"GET",null,cookie,null).statusCode());
            assertEquals(403,DbaTest.request(client,base,endpoint,"GET",null,otherCookie,null).statusCode());
            assertEquals(403,DbaTest.request(client,base,endpoint,"DELETE",null,cookie,null).statusCode());
            var draft=Profiles.JSON.createObjectNode().put("revision",1);draft.putArray("changes").addObject().put("rowId",descriptor.path("rowIds").get(0).asText()).put("operation","delete").putObject("values");
            assertEquals(403,DbaTest.request(client,base,endpoint+"/prepare","POST",draft,cookie,null).statusCode());
            assertEquals(403,DbaTest.request(client,base,endpoint+"/prepare","POST",draft,otherCookie,otherCsrf).statusCode());
            JsonNode plan=finish(client,base,cookie,csrf,DbaTest.request(client,base,endpoint+"/prepare","POST",draft,cookie,csrf));
            var apply=Profiles.JSON.createObjectNode().put("revision",1).put("planId",plan.path("result").path("planId").asText());
            JsonNode denied=finishAny(client,base,cookie,DbaTest.request(client,base,endpoint+"/apply","POST",apply,cookie,csrf));
            assertEquals("failed",denied.path("state").asText());assertTrue(denied.path("error").asText().contains("Confirm"));
            var export=Profiles.JSON.createObjectNode().put("revision",1).put("scope","page").put("format","csv");export.putArray("columns").add("c1").add("c2");
            JsonNode exported=finish(client,base,cookie,csrf,DbaTest.request(client,base,endpoint+"/export","POST",export,cookie,csrf));
            String download="/api/dba/grids/exports/"+exported.path("result").path("exportId").asText();
            assertEquals(403,DbaTest.request(client,base,download,"GET",null,otherCookie,null).statusCode());
            var content=DbaTest.request(client,base,download,"GET",null,cookie,null);assertEquals(200,content.statusCode());assertTrue(content.body().contains("first"));
            assertEquals(403,DbaTest.request(client,base,download,"GET",null,cookie,null).statusCode());
            var dispose=Profiles.JSON.createObjectNode();dispose.putArray("ids").add(descriptor.path("id").asText());
            assertEquals(403,DbaTest.request(client,base,"/api/dba/grids/dispose","POST",dispose,cookie,null).statusCode());
            assertEquals(200,DbaTest.request(client,base,"/api/dba/grids/dispose","POST",dispose,cookie,csrf).statusCode());
            assertEquals(403,DbaTest.request(client,base,endpoint,"GET",null,cookie,null).statusCode());
        }finally{server.stop(0);}
    }
    private JsonNode finish(HttpClient client,String base,String cookie,String csrf,HttpResponse<String> response)throws Exception{
        JsonNode result=finishAny(client,base,cookie,response);assertEquals("complete",result.path("state").asText(),result.toString());
        DbaTest.request(client,base,"/api/dba/jobs/"+result.path("id").asText(),"DELETE",null,cookie,csrf);return result;
    }
    private JsonNode finishAny(HttpClient client,String base,String cookie,HttpResponse<String> response)throws Exception{
        assertEquals(202,response.statusCode(),response.body());String id=Profiles.JSON.readTree(response.body()).path("id").asText();long end=System.nanoTime()+10_000_000_000L;JsonNode result;
        do{result=Profiles.JSON.readTree(DbaTest.request(client,base,"/api/dba/jobs/"+id,"GET",null,cookie,null).body());if(result.path("finished").asLong()>0)return result;Thread.sleep(10);}while(System.nanoTime()<end);
        fail("Grid job did not settle: "+result);return result;
    }
}
