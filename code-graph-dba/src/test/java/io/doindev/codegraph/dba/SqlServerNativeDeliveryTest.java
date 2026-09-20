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
    @Test @Timeout(180) void designerCrossDatabaseReviewRollbackAndNativeDefinitions()throws Exception{
        Assumptions.assumeTrue(Objects.toString(System.getenv("CG_SQLSERVER_OWNER"),"").matches("cgraph-sqlserver-[a-f0-9]+"),"Owned SQL Server fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,256L<<20,2,1000,100,25),_->true)){
            var profile=Profiles.JSON.createObjectNode().put("name","Designer SQL Server").put("templateId","sqlserver").put("driverClass","com.microsoft.sqlserver.jdbc.SQLServerDriver").put("jar",System.getenv("CG_SQLSERVER_JAR")).put("url",System.getenv("CG_SQLSERVER_URL")).put("username","sa").put("password",System.getenv("CG_SQLSERVER_PASSWORD")).put("readOnly",false);
            String id=profiles.put(null,profile).path("id").asText(),database="designer_"+UUID.randomUUID().toString().replace("-","");
            try(var c=connections.open(id);var s=c.createStatement()){c.setAutoCommit(true);s.execute("CREATE DATABASE ["+database+"]");}
            try(var target=connections.target(id,database);var s=target.connection().createStatement()){
                target.connection().setAutoCommit(true);s.execute("CREATE TABLE dbo.items(id int NOT NULL PRIMARY KEY, title nvarchar(40) CONSTRAINT DF_title DEFAULT N'old', score int)");s.execute("INSERT INTO dbo.items(id,title,score) VALUES(1,N'preserved',2)");
            }
            var parent=Profiles.JSON.createObjectNode().put("kind","tables").put("database",database).put("schema","dbo");var selection=MetadataActionsTest.selection(jobs,id,parent,"items");
            var loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));assertEquals("complete",loaded.path("state").asText(),loaded.toString());var snapshot=(ObjectNode)loaded.path("result");assertTrue(snapshot.path("editable").asBoolean(),snapshot.toString());
            var draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("columns").get(1)).put("name","display]name").put("type","nvarchar(80)").put("default","'new'").put("comment","Changed label");
            ((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("columns")).addObject().put("id","new:status").put("name","status").put("type","int").put("nullable",true).put("pk",0).put("default","5");
            var index=((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("objects")).addObject().put("category","Indexes").put("action","add").put("name","IX_status");index.putArray("columns").add("status");
            var request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
            var review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());
            var apply=Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true);assertThrows(IllegalArgumentException.class,()->jobs.applyTableProperties("other",apply));
            var saved=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("success",saved.path("result").path("status").asText(),saved.toString());jobs.remove("human",review.path("id").asText());
            try(var target=connections.target(id,database);var s=target.connection().createStatement();var row=s.executeQuery("SELECT [display]]name] FROM dbo.items WHERE id=1")){assertTrue(row.next());assertEquals("preserved",row.getString(1));}
            // The saved profile still targets master; schema edits must never fall back there.
            try(var c=connections.open(id);var s=c.createStatement();var row=s.executeQuery("SELECT COUNT(*) FROM sys.tables WHERE name='items'")){assertTrue(row.next());assertEquals(0,row.getInt(1));}
            loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));snapshot=(ObjectNode)loaded.path("result");draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("columns").get(1)).put("name","should_rollback").put("type","int");request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
            review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());saved=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("rolled_back",saved.path("result").path("outcome").asText(),saved.toString());jobs.remove("human",review.path("id").asText());
            try(var target=connections.target(id,database);var s=target.connection().createStatement();var row=s.executeQuery("SELECT [display]]name] FROM dbo.items WHERE id=1")){assertTrue(row.next());assertEquals("preserved",row.getString(1));}
            var creation=TableCreationTest.input("dbo");((ObjectNode)creation.path("target")).put("database",database);
            loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,creation,false));assertEquals("complete",loaded.path("state").asText(),loaded.toString());snapshot=(ObjectNode)loaded.path("result");draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("name","new_table").put("comment","Created through designer");
            ((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("columns")).addObject().put("id","new:id").put("name","id").put("type","int").put("nullable",false).put("pk",1).put("identity","d");creation.put("fingerprint",snapshot.path("fingerprint").asText());creation.set("draft",draft);
            review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,creation,true));assertEquals("complete",review.path("state").asText(),review.toString());saved=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("success",saved.path("result").path("status").asText(),saved.toString());jobs.remove("human",review.path("id").asText());
            var scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database",database).put("schema","dbo"));var target=new WorkflowTargets.Target(profiles.get(id),scope,Profiles.JSON.createObjectNode(),"fixture");var capture=MigrationPlansTest.await(jobs,"agent:a",jobs.captureSchema("agent:a",target,()->{}));assertEquals("complete",capture.state,capture.json().toString());
            assertTrue(capture.result.toString().contains("partial_structured_native"),capture.result.toString());assertTrue(capture.result.toString().contains("IX_status"),capture.result.toString());
            try(var plans=new MigrationPlans()){
                var input=Profiles.JSON.createObjectNode().put("snapshotId",capture.id);input.putArray("changes").addObject().put("action","add_column").put("table","items").put("column","from_migration").put("type","int");var plan=plans.prepare("agent:a",capture,input,()->{});assertEquals(1,plan.path("manifest").path("steps").size());
                var migration=Profiles.JSON.createObjectNode().put("connectionId",id).put("migrationPlanId",plan.path("id").asText()).put("migrationSchemaFingerprint",plan.path("schemaFingerprint").asText()).put("migrationTransactional",false);migration.set("migrationScope",scope);migration.set("migrationStatements",plan.path("statements"));var result=MigrationPlansTest.await(jobs,"agent:a",jobs.approved("a",migration,()->{},()->{}));assertTrue(result.result.path("applied").asBoolean(),result.json().toString());
            }
        }
    }
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
