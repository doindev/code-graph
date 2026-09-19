package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL Server gate. The harness owns the whole disposable container and database. */
class SqlServerNativeDeliveryTest {
    @TempDir Path root;
    DbaRuntime runtime;String principal,session,id,database;
    ObjectNode request(){return Profiles.JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","Disposable SQL Server native-delivery test");}
    ObjectNode target(){return Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","SQL Server fixture").put("database",database).put("schema","dbo");}
    JsonNode call(String name,JsonNode args){return runtime.agentCall(principal,session,name,args);}
    JsonNode finish(JsonNode initial,boolean approval)throws Exception {
        long until=System.nanoTime()+45_000_000_000L;JsonNode result=initial;
        do{Thread.sleep(25);result=call(approval?"dba_request_status":"dba_job_status",Profiles.JSON.createObjectNode().put(approval?"requestId":"jobId",initial.path("id").asText()));}
        while(!Set.of("complete","failed","cancelled").contains(result.path("state").asText())&&System.nanoTime()<until);
        assertEquals("complete",result.path("state").asText(),result.toPrettyString());return result;
    }
    JsonNode sql(String text)throws Exception {
        ObjectNode input=target();input.setAll(request());input.put("sql",text);
        JsonNode done=finish(call("dba_request_live_sql",input),true);release(done);return done;
    }
    void release(JsonNode done){if(done.has("jobId"))call("dba_release_job",Profiles.JSON.createObjectNode().put("jobId",done.path("jobId").asText()));}
    @Test @Timeout(180) void actualServerTargetingDdlPlansRoutinesAndSnapshots()throws Exception {
        Assumptions.assumeTrue(Objects.toString(System.getenv("CG_SQLSERVER_OWNER"),"").matches("cgraph-sqlserver-[a-f0-9]+"),"Owned SQL Server Docker fixture required");
        try(var app=new DbaRuntime(new DbaConfig(root,256L<<20,2,1000,100,20,60,"none",true),new DbaTest.MemoryVault(),false)){
            runtime=app;principal=runtime.trustedLocalAgent();session=UUID.randomUUID().toString();runtime.registerMcpSession(session,principal,System.currentTimeMillis()+300000);
            ObjectNode profile=Profiles.JSON.createObjectNode().put("name","SQL Server fixture").put("templateId","sqlserver")
                    .put("driverClass","com.microsoft.sqlserver.jdbc.SQLServerDriver").put("jar",System.getenv("CG_SQLSERVER_JAR"))
                    .put("url",System.getenv("CG_SQLSERVER_URL")).put("username","sa").put("password",System.getenv("CG_SQLSERVER_PASSWORD")).put("readOnly",false);
            ObjectNode input=request().put("testBeforeSave",true);input.set("profile",profile);
            JsonNode created=finish(call("dba_request_connection_create",input),true);id=created.path("job").path("result").path("id").asText();release(created);
            assertFalse(id.isEmpty());
            database="master";String owned="cg_"+UUID.randomUUID().toString().replace("-","");
            sql("CREATE DATABASE ["+owned+"]");database=owned;
            sql("CREATE TABLE dbo.native_items(id int NOT NULL PRIMARY KEY, title nvarchar(100), created_at datetime2 DEFAULT SYSUTCDATETIME())");
            sql("INSERT INTO dbo.native_items(id,title) VALUES(1,N'synthetic')");
            sql("CREATE VIEW dbo.native_view AS SELECT id,title FROM dbo.native_items");
            sql("CREATE FUNCTION dbo.native_answer() RETURNS int AS BEGIN RETURN 7 END");
            assertEquals(7,sql("SELECT dbo.native_answer() AS answer").path("job").path("result").path("results").get(0).path("rows").get(0).get(0).asInt());
            sql("CREATE PROCEDURE dbo.native_proc AS SELECT 8 AS answer");
            assertEquals(8,sql("EXEC dbo.native_proc").path("job").path("result").path("results").get(0).path("rows").get(0).get(0).asInt());
            for(String object:List.of("native_items","native_view","native_proc","native_answer")){
                JsonNode ddl=finish(call("dba_get_object_ddl",target().put("object",object)),true);
                assertTrue(ddl.path("job").path("result").path("results").get(0).path("rows").size()>0,ddl.toPrettyString());release(ddl);
            }
            JsonNode plan=finish(call("dba_explain_query",target().put("sql","SELECT id FROM dbo.native_items WHERE id = 1")),true);
            assertFalse(plan.path("job").path("result").isMissingNode());release(plan);
            JsonNode live=finish(call("dba_get_capabilities",target().put("live",true)),false);
            assertEquals("sqlserver",live.path("result").path("engine").asText());
            call("dba_release_job",Profiles.JSON.createObjectNode().put("jobId",live.path("id").asText()));
            JsonNode snapshot=finish(call("dba_capture_schema",target()),false);
            assertTrue(snapshot.path("result").toString().contains("native_items"));assertEquals("sqlserver",snapshot.path("result").path("engine").asText());
            call("dba_release_job",Profiles.JSON.createObjectNode().put("jobId",snapshot.path("id").asText()));
            // Cross-catalog targeting must not create the fixture table in the saved master database.
            database="master";
            JsonNode wrong=sql("SELECT COUNT(*) AS wrong_database_count FROM sys.tables WHERE name = 'native_items'");
            assertEquals(0,wrong.path("job").path("result").path("results").get(0).path("rows").get(0).get(0).asInt(),wrong.toPrettyString());
            database=owned;
            JsonNode remaining=sql("SELECT title FROM dbo.native_items WHERE id=1");assertTrue(remaining.toString().contains("synthetic"));
            System.out.println("SQLSERVER_NATIVE_DELIVERY_BASELINE_VERIFIED");
        }
    }
}
