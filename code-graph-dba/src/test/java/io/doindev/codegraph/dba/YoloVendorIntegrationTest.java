package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only against ownership-labelled disposable fixtures provisioned by the harness. */
class YoloVendorIntegrationTest {
    @TempDir Path root;
    DbaRuntime runtime;String principal,session,connection,schema;
    JsonNode call(String name,ObjectNode args){return runtime.agentCall(principal,session,name,args);}
    ObjectNode request(){return Profiles.JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","Owned disposable YOLO vendor fixture");}
    ObjectNode target(){return Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName","YOLO vendor").put("database","approval_test").put("schema",schema);}
    JsonNode finish(JsonNode initial,boolean request)throws Exception{
        JsonNode state=initial;long until=System.nanoTime()+40_000_000_000L;
        do{Thread.sleep(20);state=call(request?"dba_request_status":"dba_job_status",Profiles.JSON.createObjectNode().put(request?"requestId":"jobId",initial.path("id").asText()));}while(!Set.of("complete","failed","cancelled").contains(state.path("state").asText())&&System.nanoTime()<until);
        assertEquals("complete",state.path("state").asText(),state.toPrettyString());assertEquals("auto_approved",state.path("authorizationOutcome").asText());return state;
    }
    void release(JsonNode request){if(request.has("jobId"))call("dba_release_job",Profiles.JSON.createObjectNode().put("jobId",request.path("jobId").asText()));}
    JsonNode sql(String source)throws Exception{ObjectNode args=target();args.setAll(request());args.put("sql",source);JsonNode result=finish(call("dba_request_live_sql",args),true);release(result);return result;}
    @Test @Timeout(180) void automaticWorkflowAgainstDisposableVendor()throws Exception{
        String owner=System.getenv("DBA_REUSABLE_DISPOSABLE");Assumptions.assumeTrue(owner!=null&&owner.matches("cgraph-reusable-qa-[a-f0-9]+"));
        String vendor=System.getenv("DBA_REUSABLE_VENDOR");schema=vendor.equals("postgresql")?"public":"approval_test";
        try(var app=new DbaRuntime(new DbaConfig(root,256L<<20,4,1000,100,30,60,"none",true),new DbaTest.MemoryVault(),false)){
            runtime=app;principal=runtime.trustedLocalAgent();session=UUID.randomUUID().toString();runtime.registerMcpSession(session,principal,System.currentTimeMillis()+600000);
            var template=DatabaseCatalog.get(vendor);ObjectNode draft=Profiles.JSON.createObjectNode().put("name","YOLO vendor").put("templateId",vendor).put("url",System.getenv("DBA_REUSABLE_URL"))
                .put("jar",System.getenv("DBA_REUSABLE_JAR")).put("driverClass",template.driver()).put("username",System.getenv("DBA_REUSABLE_USER")).put("password",System.getenv("DBA_REUSABLE_PASSWORD")).put("readOnly",false);
            ObjectNode create=request().put("testBeforeSave",true);create.set("profile",draft);JsonNode saved=finish(call("dba_request_connection_create",create),true);
            connection=saved.path("job").path("result").path("id").asText();assertFalse(connection.isBlank());release(saved);
            String table=schema+".yolo_items";sql("CREATE TABLE "+table+"(id INT PRIMARY KEY, title VARCHAR(60))");sql("INSERT INTO "+table+" VALUES(1,'synthetic-yolo')");sql("UPDATE "+table+" SET title='updated-yolo' WHERE id=1");
            sql("CREATE VIEW "+schema+".yolo_view AS SELECT id,title FROM "+table);
            JsonNode read=finish(call("dba_execute_read_query",target().put("sql","SELECT id,title FROM "+table)),true);assertTrue(read.toString().contains("updated-yolo"));release(read);
            JsonNode plan=finish(call("dba_explain_query",target().put("sql","SELECT id FROM "+table+" WHERE id=1")),true);release(plan);
            JsonNode ddl=finish(call("dba_get_object_ddl",target().put("object","yolo_items")),true);assertFalse(ddl.path("job").path("result").isMissingNode());release(ddl);
            String function=vendor.equals("postgresql")?"CREATE FUNCTION "+schema+".yolo_fn() RETURNS integer LANGUAGE SQL AS $$ SELECT 7 $$":"CREATE FUNCTION "+schema+".yolo_fn() RETURNS INT DETERMINISTIC RETURN 7";
            sql(function);sql("SELECT "+schema+".yolo_fn()");
            String procedure=vendor.equals("postgresql")?"CREATE PROCEDURE "+schema+".yolo_proc() LANGUAGE SQL AS $$ SELECT 8 $$":"CREATE PROCEDURE "+schema+".yolo_proc() SELECT 8";
            sql(procedure);sql("CALL "+schema+".yolo_proc()");
            JsonNode snapshot=finish(call("dba_capture_schema",target()),false);
            ObjectNode migration=Profiles.JSON.createObjectNode().put("snapshotId",snapshot.path("id").asText()).put("sql","ALTER TABLE "+table+" ADD COLUMN migration_value INT");
            JsonNode prepared=call("dba_prepare_migration",migration);JsonNode applied=finish(call("dba_request_apply_migration",request().put("planId",prepared.path("id").asText())),true);
            assertTrue(applied.path("job").path("result").path("applied").asBoolean());release(applied);
            sql("SELECT migration_value FROM "+table);
            String project=DbaRuntime.projectContextId("yolo-vendor-project");runtime.attachProjectContext(new ProjectContextHost(){public JsonNode projects(){return Profiles.JSON.createArrayNode().add(Profiles.JSON.createObjectNode().put("id",project).put("name","YOLO project"));}public io.doindev.codegraph.store.DocumentStore documents(){return io.doindev.codegraph.store.DocumentStore.memory(16L<<20);}});
            ObjectNode binding=Profiles.JSON.createObjectNode().put("projectId",project).put("connectionId",connection).put("database","approval_test").put("schema",schema).put("environment","local").put("role","primary").put("purpose","disposable test").put("enabled",false);
            ObjectNode bind=request();bind.set("binding",binding);JsonNode bound=finish(call("dba_request_binding_create",bind),true);String bindingId=bound.path("job").path("result").path("id").asText();release(bound);
            binding.put("enabled",true).put("role","reporting");ObjectNode change=request().put("bindingId",bindingId);change.set("binding",binding);
            JsonNode changed=finish(call("dba_request_binding_update",change),true);assertEquals("reporting",changed.path("job").path("result").path("role").asText());release(changed);
            JsonNode scan=call("dba_refresh_catalog",Profiles.JSON.createObjectNode().put("bindingId",bindingId).put("afterGeneration",0).put("waitMillis",5000));
            long untilScan=System.nanoTime()+30_000_000_000L;
            while(scan.path("generation").asLong()==0&&System.nanoTime()<untilScan)scan=call("dba_scan_status",Profiles.JSON.createObjectNode().put("bindingId",bindingId).put("afterGeneration",0).put("waitMillis",5000));
            assertTrue(scan.path("generation").asLong()>0,scan.toPrettyString());
            JsonNode capabilities=call("dba_get_capabilities",target());assertTrue(capabilities.path("yolo").asBoolean());
            JsonNode context=call("get_workspace_context",Profiles.JSON.createObjectNode());assertTrue(context.path("yolo").asBoolean());
            JsonNode unbound=finish(call("dba_request_binding_delete",request().put("bindingId",bindingId)),true);release(unbound);
            ObjectNode test=request().put("connectionId",connection).put("connectionName","YOLO vendor");JsonNode tested=finish(call("dba_request_connection_test",test),true);assertTrue(tested.path("job").path("result").path("connected").asBoolean());release(tested);
            sql("DROP PROCEDURE "+schema+".yolo_proc"+(vendor.equals("postgresql")?"()":""));sql("DROP FUNCTION "+schema+".yolo_fn"+(vendor.equals("postgresql")?"()":""));
            sql("DROP VIEW "+schema+".yolo_view");sql("DELETE FROM "+table);sql("TRUNCATE TABLE "+table);sql("DROP TABLE "+table);
            JsonNode deleted=finish(call("dba_request_connection_delete",request().put("connectionId",connection).put("connectionName","YOLO vendor")),true);release(deleted);
            assertTrue(call("dba_get_my_permissions",Profiles.JSON.createObjectNode()).path("reusablePolicies").isEmpty());
            System.out.println("YOLO_VENDOR_VERIFIED "+vendor);
        }
    }
}
