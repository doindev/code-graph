package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class GridSettingsTest {
    @TempDir Path root;
    ObjectNode request(long revision,String scope,ObjectNode values){var out=Profiles.JSON.createObjectNode().put("expectedRevision",revision).put("scope",scope);out.set("settings",values);return out;}
    @Test void preferencesPersistValidateAndNeverInvalidateConnections()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault())){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText(),authorization=profiles.get(id).path("authorizationRevision").asText();
            var settings=new GridSettings(root);assertEquals(200,GridSettings.defaults().path("pageSize").asInt());
            var values=Profiles.JSON.createObjectNode().put("fontSize",16).put("autoFetch",false);
            settings.save(request(0,"global",values),profiles,1000);
            var connection=request(1,"connection",Profiles.JSON.createObjectNode().put("readOnly",true)).put("connectionId",id);
            settings.save(connection,profiles,1000);
            assertEquals(authorization,profiles.get(id).path("authorizationRevision").asText());
            var reopened=new GridSettings(root);assertEquals(16,reopened.json(1000).path("global").path("fontSize").asInt());
            assertTrue(reopened.json(1000).path("connections").path(id).path("readOnly").asBoolean());
            assertThrows(IllegalArgumentException.class,()->reopened.save(connection,profiles,1000));
            for(String invalid:new String[]{"{\"fontSize\":21}","{\"fontSize\":\"12\"}","{\"autoCount\":1}","{\"sorting\":\"guess\"}","{\"sql\":\"SELECT 1\"}","{\"emptyText\":\"\\n\"}","{\"cancelTimeout\":999}"})
                assertThrows(IllegalArgumentException.class,()->GridSettings.validate(Profiles.JSON.readTree(invalid)));
            reopened.removeConnection(id);assertFalse(new GridSettings(root).json(1000).path("connections").has(id));
        }
    }
    @Test void corruptFileFallsBackWithoutDeletingEvidence()throws Exception{
        Files.writeString(root.resolve("grid-settings.json"),"invalid");
        var settings=new GridSettings(root);assertFalse(settings.json(1000).path("warning").asText().isBlank());
        assertEquals("invalid",Files.readString(root.resolve("grid-settings.json")));
    }
    @Test void endpointRequiresBrowserSessionAndCsrf()throws Exception{
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(new DbaConfig(root,128L<<20,2,1000,100,15),new DbaTest.MemoryVault());var client=HttpClient.newHttpClient()){
            server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort(),path="/api/dba/grid-settings";
            assertEquals(403,DbaTest.request(client,base,path,"GET",null,null,null).statusCode());
            var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(login.body()).path("csrf").asText();
            var request=request(0,"global",Profiles.JSON.createObjectNode().put("fontSize",14));
            assertEquals(403,DbaTest.request(client,base,path,"PUT",request,cookie,null).statusCode());
            assertEquals(200,DbaTest.request(client,base,path,"PUT",request,cookie,csrf).statusCode());
            assertEquals(400,DbaTest.request(client,base,path,"PUT",request,cookie,csrf).statusCode());
            var saved=Profiles.JSON.readTree(DbaTest.request(client,base,path,"GET",null,cookie,csrf).body());
            assertEquals(14,saved.path("global").path("fontSize").asInt());assertTrue(saved.path("schema").path("fields").size()>20);
        }finally{server.stop(0);}
    }
    @Test void countsRespectQueryAndDoNotReplaceRowsOrRevision()throws Exception{
        var owner=new GridValuesTest();owner.root=root;
        try(var f=owner.new Fixture()){
            f.sql("CREATE TABLE COUNT_ITEMS(ID INT, LABEL VARCHAR(20))");
            f.sql("INSERT INTO COUNT_ITEMS VALUES(1,NULL),(2,NULL),(3,''),(4,'same'),(5,'same')");
            JsonNode rows=f.query("SELECT LABEL AS CHOICE FROM COUNT_ITEMS WHERE ID>1 ORDER BY ID LIMIT 3"),grid=rows.path("grid");
            assertFalse(grid.path("capabilities").path("edit").asBoolean());assertTrue(grid.path("capabilities").path("count").asBoolean(),grid.toString());
            var context=f.grids.require("human",grid.path("id").asText());String original=context.result.toString();
            var request=Profiles.JSON.createObjectNode().put("revision",grid.path("revision").asLong());
            JsonNode count=f.finish(f.grids.operation("human",context.id,"count",request));
            assertEquals(3,count.path("total").asInt());assertEquals(original,context.result.toString());assertEquals(1,context.revision);
            assertThrows(SecurityException.class,()->f.grids.operation("other",context.id,"count",request));
            assertThrows(IllegalArgumentException.class,()->f.grids.operation("human",context.id,"count",request.deepCopy().put("revision",0)));
            f.grids.release("human",context.id);assertThrows(SecurityException.class,()->f.grids.operation("human",context.id,"count",request));
        }
    }
    @Test void optionalPagingCountAndConfirmedSaveRequireFreshSnapshot()throws Exception{
        var owner=new GridValuesTest();owner.root=root;
        try(var f=owner.new Fixture()){
            f.sql("CREATE TABLE SAVE_ITEMS(ID INT PRIMARY KEY, LABEL VARCHAR(20))");
            f.sql("INSERT INTO SAVE_ITEMS SELECT X,'before' FROM SYSTEM_RANGE(1,7)");
            JsonNode rows=f.query("SELECT * FROM SAVE_ITEMS ORDER BY ID"),grid=rows.path("grid");String id=grid.path("id").asText();
            var context=f.grids.require("human",id);String countSql=context.countSql;context.countSql=null;
            var page=Profiles.JSON.createObjectNode().put("revision",1).put("direction","first").put("limit",2).put("countPolicy","none");
            rows=f.finish(f.grids.operation("human",id,"page",page));assertEquals(2,rows.path("rows").size());assertFalse(rows.path("grid").path("page").has("total"));context.countSql=countSql;
            page.put("revision",context.revision).put("direction","last");
            rows=f.finish(f.grids.operation("human",id,"page",page));assertEquals(7,rows.path("grid").path("page").path("total").asInt());assertFalse(rows.path("grid").path("page").path("hasMore").asBoolean());
            var edits=Profiles.JSON.createObjectNode().put("revision",context.revision);
            edits.putArray("changes").addObject().put("rowId",context.rowIds.getFirst()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","after");
            JsonNode plan=f.finish(f.grids.operation("human",id,"prepare",edits));
            var apply=Profiles.JSON.createObjectNode().put("revision",context.revision).put("planId",plan.path("planId").asText());
            JsonNode saved=f.finish(f.grids.operation("human",id,"apply",apply));
            assertEquals("commit_acknowledged",saved.path("outcome").asText());assertTrue(context.refreshRequired);
            assertFalse(f.grids.status("human",id).path("capabilities").path("edit").asBoolean());
            assertThrows(IllegalArgumentException.class,()->f.grids.operation("human",id,"prepare",edits.put("revision",context.revision)));
            page.put("revision",context.revision).put("direction","refresh");
            f.finish(f.grids.operation("human",id,"page",page));assertFalse(context.refreshRequired);assertTrue(f.grids.status("human",id).path("capabilities").path("edit").asBoolean());
        }
    }
    @Test void retainedCountDoesNotClampNavigationAfterConcurrentInserts()throws Exception{
        var owner=new GridValuesTest();owner.root=root;
        try(var f=owner.new Fixture()){
            f.sql("CREATE TABLE MOVING_ITEMS(ID INT PRIMARY KEY)");
            f.sql("INSERT INTO MOVING_ITEMS VALUES(1),(2),(3)");
            JsonNode grid=f.query("SELECT * FROM MOVING_ITEMS ORDER BY ID").path("grid");
            var context=f.grids.require("human",grid.path("id").asText());
            var page=Profiles.JSON.createObjectNode().put("revision",context.revision).put("direction","first").put("limit",2).put("countPolicy","auto");
            JsonNode rows=f.finish(f.grids.operation("human",context.id,"page",page));
            assertEquals(3,rows.path("grid").path("page").path("total").asInt());
            JsonNode captured=rows.path("grid").path("page").path("totalCapturedAt");
            f.sql("INSERT INTO MOVING_ITEMS VALUES(4),(5),(6),(7)");
            for(int offset:new int[]{2,4,6}){
                page.put("revision",context.revision).put("direction","next").put("countPolicy","none");
                rows=f.finish(f.grids.operation("human",context.id,"page",page));
                assertEquals(offset,rows.path("grid").path("page").path("offset").asInt());
                assertEquals(3,rows.path("grid").path("page").path("total").asInt());
                assertEquals(captured,rows.path("grid").path("page").path("totalCapturedAt"));
            }
        }
    }

    @Test void countRetainsPositionalParametersAndCancelsWithoutChangingRows()throws Exception{
        var owner=new GridValuesTest();owner.root=root;
        try(var f=owner.new Fixture()){
            f.sql("CREATE TABLE PARAM_ITEMS(ID INT PRIMARY KEY)");f.sql("INSERT INTO PARAM_ITEMS VALUES(1),(2),(3)");
            JsonNode rows=f.finish(f.jobs.humanQuery("human",f.id,"SELECT ID FROM PARAM_ITEMS WHERE ID > ? ORDER BY ID * ?",Profiles.JSON.createArrayNode().add(1).add(1),false)).path("results").get(0);
            var context=f.grids.require("human",rows.path("grid").path("id").asText());
            var request=Profiles.JSON.createObjectNode().put("revision",context.revision);
            assertEquals(2,f.finish(f.grids.operation("human",context.id,"count",request)).path("total").asInt());
            var gate=new java.util.concurrent.CountDownLatch(1);var entered=new java.util.concurrent.CountDownLatch(2);
            var blockers=new java.util.ArrayList<ObjectNode>();
            try{
                for(int i=0;i<2;i++)blockers.add(f.jobs.local("human",job->{entered.countDown();gate.await();return Profiles.JSON.createObjectNode();},()->{}));
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                ObjectNode queued=f.grids.operation("human",context.id,"count",request);
                f.jobs.cancel(f.jobs.require("human",queued.path("id").asText()));gate.countDown();
                assertEquals("cancelled",HumanSqlTest.finish(f.jobs,"human",queued).path("state").asText());
                assertEquals(1,context.revision);assertFalse(context.uncertain);assertFalse(context.busy);
            }finally{gate.countDown();for(ObjectNode job:blockers)HumanSqlTest.finish(f.jobs,"human",job);}
            f.profiles.put(f.id,new DbaTest().input().put("name","changed"));
            assertThrows(IllegalArgumentException.class,()->f.grids.operation("human",context.id,"count",request));
        }
    }

}
