package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class YoloTest {
    @TempDir Path root;
    DbaRuntime runtime;
    final DbaTest.MemoryVault vault=new DbaTest.MemoryVault();
    String principal,session,connection,database;
    @BeforeEach void start()throws Exception{
        database="YOLO_"+UUID.randomUUID().toString().replace("-","").toUpperCase(Locale.ROOT);
        runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,100,100,15,60,"desktop",true),vault,false,new ApprovalBroker.Desktop(){
            public boolean available(){throw new AssertionError("YOLO must not probe the desktop");}
            public boolean browse(java.net.URI uri){throw new AssertionError("No browser");}
            public void show(JsonNode request,int waiting,java.util.function.Consumer<ApprovalBroker.Decision> decide,Runnable detailed){throw new AssertionError("No prompt");}
            public void dismiss(){}
        });
        principal=runtime.trustedLocalAgent();session=UUID.randomUUID().toString();runtime.registerMcpSession(session,principal,System.currentTimeMillis()+600000);
    }
    @AfterEach void close(){runtime.close();}
    JsonNode call(String operation,ObjectNode input){return runtime.agentCall(principal,session,operation,input);}
    ObjectNode request(){return Profiles.JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","YOLO isolated test");}
    ObjectNode draft()throws Exception{return new DbaTest().input().put("templateId","h2").put("name","YOLO fixture").put("url","jdbc:h2:mem:"+database+";DB_CLOSE_DELAY=-1").put("readOnly",false);}
    ObjectNode target(){return Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName","YOLO fixture").put("database",database).put("schema","PUBLIC");}
    JsonNode finish(JsonNode submitted)throws Exception{
        String id=submitted.path("id").asText();JsonNode status=submitted;long end=System.nanoTime()+20_000_000_000L;
        while(Set.of("submitted","approved","queued","running").contains(status.path("state").asText())&&System.nanoTime()<end){
            Thread.sleep(15);status=call("dba_request_status",Profiles.JSON.createObjectNode().put("requestId",id));
        }
        assertEquals("complete",status.path("state").asText(),status.toPrettyString());
        assertEquals("auto_approved",status.path("authorizationOutcome").asText());
        assertEquals("automatic",status.path("approvalChannel").asText());
        return status;
    }
    void create(boolean test)throws Exception{
        ObjectNode input=request().put(test?"testBeforeSave":"saveUntested",true).put("confirmDriverEffects",true);input.set("profile",draft());
        JsonNode done=finish(call("dba_request_connection_create",input));connection=done.path("job").path("result").path("id").asText();
        assertFalse(connection.isBlank(),done.toPrettyString());release(done);
    }
    void release(JsonNode done){String job=done.path("jobId").asText();if(!job.isBlank())call("dba_release_job",Profiles.JSON.createObjectNode().put("jobId",job));}
    JsonNode sql(String sql)throws Exception{ObjectNode input=target();input.setAll(request());input.put("sql",sql);JsonNode result=finish(call("dba_request_live_sql",input));release(result);return result;}

    @Test void strictStartupFlagsAndDefaultOff(){
        assertFalse(DbaConfig.parse(new String[]{"--dba"}).orElseThrow().yolo());
        assertTrue(DbaConfig.parse(new String[]{"--dba","--yolo"}).orElseThrow().yolo());
        for(String[] args:List.of(new String[]{"--yolo"},new String[]{"--dba","--yolo=false"},new String[]{"--dba","--yolo","false"},new String[]{"--dba","--YOLO"},new String[]{"--dba","--no-dba","--yolo"}))
            assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(args));
    }
    @Test void headlessSetupReadsWritesAndDestructiveSqlNeedNoGrants()throws Exception{
        assertTrue(runtime.approvalsEnabled());assertFalse(runtime.editorPairingEnabled());create(true);
        sql("CREATE TABLE PUBLIC.ITEMS(ID INT PRIMARY KEY, NAME VARCHAR(40))");
        sql("INSERT INTO PUBLIC.ITEMS VALUES(1,'fixture')");
        sql("UPDATE PUBLIC.ITEMS SET NAME='changed' WHERE ID=1");
        JsonNode rows=sql("SELECT * FROM PUBLIC.ITEMS");assertTrue(rows.toString().contains("changed"));
        sql("CREATE VIEW PUBLIC.ITEM_VIEW AS SELECT ID FROM PUBLIC.ITEMS");
        sql("DROP VIEW PUBLIC.ITEM_VIEW");sql("DELETE FROM PUBLIC.ITEMS");sql("TRUNCATE TABLE PUBLIC.ITEMS");sql("DROP TABLE PUBLIC.ITEMS");
        JsonNode permissions=call("dba_get_my_permissions",Profiles.JSON.createObjectNode());assertTrue(permissions.path("legacyGrants").isEmpty());assertTrue(permissions.path("reusablePolicies").isEmpty());
        ObjectNode remove=request().put("connectionId",connection).put("connectionName","YOLO fixture");JsonNode done=finish(call("dba_request_connection_delete",remove));release(done);
        assertTrue(call("dba_list_connections",Profiles.JSON.createObjectNode()).isEmpty());assertTrue(vault.secrets.isEmpty());
    }
    @Test void setupRequiresExplicitIntentAndSuccessfulUnchangedTest()throws Exception{
        ObjectNode input=request();input.set("profile",draft());
        assertThrows(IllegalArgumentException.class,()->call("dba_request_connection_create",input));
        input.put("testBeforeSave",true).put("saveUntested",true);assertThrows(IllegalArgumentException.class,()->call("dba_request_connection_create",input));
        input.remove("saveUntested");input.put("confirmDriverEffects",false);
        JsonNode submitted=call("dba_request_connection_create",input);long end=System.nanoTime()+10_000_000_000L;JsonNode status;
        do{Thread.sleep(15);status=call("dba_request_status",Profiles.JSON.createObjectNode().put("requestId",submitted.path("id").asText()));}while(status.path("state").asText().equals("submitted")&&System.nanoTime()<end);
        assertEquals("failed",status.path("state").asText(),status.toString());assertTrue(call("dba_list_connections",Profiles.JSON.createObjectNode()).isEmpty());release(status);
    }
    @Test void terminalHistoryDoesNotConsumeActiveCapacityAndCannotReplay()throws Exception{
        create(false);ObjectNode first=target();first.setAll(request());first.put("sql","SELECT 123");
        JsonNode submitted=call("dba_request_live_sql",first);JsonNode done=finish(submitted);release(done);
        assertEquals(submitted.path("id"),call("dba_request_live_sql",first).path("id"));
        for(int i=0;i<70;i++)sql("SELECT "+i);
        assertThrows(IllegalArgumentException.class,()->call("dba_request_live_sql",first),"Evicted status must not replay SQL");
    }
    @Test void explicitTargetsSessionsReadContractsAndPairingRemainRequired()throws Exception{
        create(false);
        ObjectNode missing=target();missing.setAll(request());missing.put("sql","SELECT 1");missing.remove("database");
        assertThrows(IllegalArgumentException.class,()->call("dba_request_live_sql",missing));
        assertThrows(IllegalArgumentException.class,()->call("dba_execute_read_query",target().put("sql","DELETE FROM PUBLIC.ITEMS")));
        for(String key:List.of("connectionId","connectionName","database","schema")){
            ObjectNode mixed=request().put("bindingId",UUID.randomUUID().toString()).put(key,"must-not-override");
            assertTrue(assertThrows(IllegalArgumentException.class,()->call("dba_get_connection_details",mixed)).getMessage().contains("no overrides"));
        }
        assertThrows(SecurityException.class,()->call("dba_list_editor_documents",Profiles.JSON.createObjectNode()));
        runtime.endMcpSession(session);assertThrows(SecurityException.class,()->call("dba_list_connections",Profiles.JSON.createObjectNode()));
    }
    @Test void auditFailureIsFailClosed()throws Exception{
        create(false);Path audit=root.resolve("agent-automatic-authorization.jsonl");Files.delete(audit);Files.createDirectory(audit);
        assertThrows(IllegalStateException.class,()->sql("CREATE TABLE PUBLIC.MUST_NOT_EXIST(ID INT)"));
    }
    JsonNode job(JsonNode submitted)throws Exception{
        JsonNode state=submitted;long end=System.nanoTime()+20_000_000_000L;
        do{Thread.sleep(15);state=call("dba_job_status",Profiles.JSON.createObjectNode().put("jobId",submitted.path("id").asText()));}while(!Set.of("complete","failed","cancelled").contains(state.path("state").asText())&&System.nanoTime()<end);
        assertEquals("complete",state.path("state").asText(),state.toPrettyString());return state;
    }
    @Test void migrationMetadataIsInitializedBeforeSubmissionAndPlansAreSessionBound()throws Exception{
        create(false);sql("CREATE TABLE PUBLIC.MIGRATION_ITEMS(ID INT)");
        JsonNode snapshot=job(call("dba_capture_schema",target()));
        ObjectNode prepare=Profiles.JSON.createObjectNode().put("snapshotId",snapshot.path("id").asText()).put("sql","ALTER TABLE PUBLIC.MIGRATION_ITEMS ADD COLUMN TITLE VARCHAR(20)");
        JsonNode plan=call("dba_prepare_migration",prepare);
        String second=UUID.randomUUID().toString();runtime.registerMcpSession(second,principal,System.currentTimeMillis()+600000);
        ObjectNode apply=request().put("planId",plan.path("id").asText());
        assertThrows(SecurityException.class,()->runtime.agentCall(principal,second,"dba_request_apply_migration",apply));
        JsonNode done=finish(call("dba_request_apply_migration",apply));assertTrue(done.path("job").path("result").path("applied").asBoolean(),done.toPrettyString());assertEquals(done.path("id"),call("dba_request_apply_migration",apply).path("id"));release(done);
        sql("SELECT TITLE FROM PUBLIC.MIGRATION_ITEMS");
    }
    @Test void syntaxAndParametersFailBeforeExecutingAnything()throws Exception{
        create(false);
        for(String sql:List.of("SELECT 'unterminated","SELECT ?","DELIMITER $$\nSELECT 1","SELECT 1;".repeat(33))){
            ObjectNode input=target();input.setAll(request());input.put("sql",sql);
            assertThrows(IllegalArgumentException.class,()->call("dba_request_live_sql",input),sql);
        }
    }

    @Test @Timeout(120) @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="YOLO_TEST_MAVEN",matches="true")
    void explicitPinnedDriverInstallThenTestAndSave()throws Exception{
        ObjectNode input=request().put("testBeforeSave",true).put("confirmDriverEffects",true),profile=draft();profile.remove(List.of("jar","jars"));input.set("profile",profile);
        input.putObject("driverInstall").put("groupId","com.h2database").put("artifactId","h2").put("version","2.4.240");
        JsonNode pending=call("dba_request_connection_create",input),state=pending;long until=System.nanoTime()+100_000_000_000L;
        do{Thread.sleep(100);state=call("dba_request_status",Profiles.JSON.createObjectNode().put("requestId",pending.path("id").asText()));}while(state.path("state").asText().equals("submitted")&&System.nanoTime()<until);
        assertEquals("complete",state.path("state").asText(),state.toPrettyString());connection=state.path("job").path("result").path("id").asText();assertFalse(connection.isBlank());
        Profiles profiles=field("profiles");JsonNode saved=profiles.get(connection);assertEquals("2.4.240",saved.path("driverBundle").path("version").asText());DriverBundles.verify(saved.path("driverBundle"));release(state);
        sql("CREATE TABLE PUBLIC.INSTALLED_DRIVER(ID INT)");sql("DROP TABLE PUBLIC.INSTALLED_DRIVER");
        ObjectNode changed=input.deepCopy();changed.put("requestId",UUID.randomUUID().toString());((ObjectNode)changed.path("driverInstall")).put("version","latest");
        assertThrows(IllegalArgumentException.class,()->call("dba_request_connection_create",changed));
    }


    @Test void secretUpdatesRemainWriteOnlyAndRemovable()throws Exception{
        create(false);String password="private-yolo-test-value",property="private-property-value";
        ObjectNode profile=draft().put("password",password);profile.putObject("secretProperties").put("unverifiedOption",property);
        ObjectNode update=request().put("connectionId",connection).put("connectionName","YOLO fixture").put("saveUntested",true);update.set("profile",profile);
        JsonNode saved=finish(call("dba_request_connection_update",update));assertFalse(saved.toString().contains(password));assertFalse(saved.toString().contains(property));release(saved);
        JsonNode details=finish(call("dba_get_connection_details",request().put("connectionId",connection).put("connectionName","YOLO fixture")));assertFalse(details.toString().contains(password));assertFalse(details.toString().contains(property));release(details);
        profile.remove("password");profile.put("removePassword",true).put("replaceSecretProperties",true);profile.set("secretProperties",Profiles.JSON.createObjectNode());
        update.put("requestId",UUID.randomUUID().toString());JsonNode removed=finish(call("dba_request_connection_update",update));release(removed);
        assertTrue(vault.secrets.isEmpty());
        try(var paths=Files.list(root)){for(Path path:paths.filter(f->f.toString().endsWith(".jsonl")||f.getFileName().toString().equals("profiles.json")).toList()){String text=Files.readString(path);assertFalse(text.contains(password));assertFalse(text.contains(property));}}
    }
    @Test void automaticSqlRetainsAgentResultCaps()throws Exception{
        create(false);JsonNode result=sql("SELECT X FROM SYSTEM_RANGE(1, 500)");List<JsonNode> rows=result.findValues("rows");
        assertTrue(rows.stream().anyMatch(n->n.isArray()&&n.size()==100),result.toPrettyString());
        assertTrue(rows.stream().allMatch(n->!n.isArray()||n.size()<=100));assertTrue(result.toString().contains("\"truncated\":true"));
    }

    @SuppressWarnings("unchecked") <T>T field(String name)throws Exception{var field=DbaRuntime.class.getDeclaredField(name);field.setAccessible(true);return (T)field.get(runtime);}
    @Test void namedAgentNeedsNoGrantsAndRevocationStillWorks()throws Exception{
        create(false);AgentAccess agents=field("agents");Profiles profiles=field("profiles");
        principal=agents.create(Profiles.JSON.createObjectNode().put("name","Named YOLO").set("grants",Profiles.JSON.createArrayNode()),profiles).path("id").asText();
        session=UUID.randomUUID().toString();runtime.registerMcpSession(session,principal,System.currentTimeMillis()+600000);
        assertEquals(1,call("dba_list_connections",Profiles.JSON.createObjectNode()).size());sql("CREATE TABLE PUBLIC.NAMED_AGENT(ID INT)");sql("DROP TABLE PUBLIC.NAMED_AGENT");
        agents.remove(principal);assertThrows(SecurityException.class,()->call("dba_list_connections",Profiles.JSON.createObjectNode()));
    }
    @Test void endingSessionCancelsQueuedConnectionCreation()throws Exception{
        QueryJobs jobs=field("jobs");var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(2);
        try{
            for(int i=0;i<2;i++)jobs.local("agent:"+principal,j->{started.countDown();release.await();return Profiles.JSON.createObjectNode();},()->{});
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            ObjectNode input=request().put("saveUntested",true);input.set("profile",draft());
            JsonNode pending=call("dba_request_connection_create",input);runtime.endMcpSession(session);release.countDown();
            session=UUID.randomUUID().toString();runtime.registerMcpSession(session,principal,System.currentTimeMillis()+600000);
            JsonNode state=pending;long until=System.nanoTime()+5_000_000_000L;
            do{Thread.sleep(20);state=call("dba_request_status",Profiles.JSON.createObjectNode().put("requestId",pending.path("id").asText()));}while(state.path("state").asText().equals("submitted")&&System.nanoTime()<until);
            assertEquals("cancelled",state.path("state").asText(),state.toString());assertTrue(call("dba_list_connections",Profiles.JSON.createObjectNode()).isEmpty());
        }finally{release.countDown();}
    }
    @Test void staleQueuedConnectionRevisionCannotBeApplied()throws Exception{
        create(false);QueryJobs jobs=field("jobs");Profiles profiles=field("profiles");var release=new java.util.concurrent.CountDownLatch(1);var started=new java.util.concurrent.CountDownLatch(2);
        try{
            for(int i=0;i<2;i++)jobs.local("agent:"+principal,j->{started.countDown();release.await();return Profiles.JSON.createObjectNode();},()->{});
            assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            JsonNode pending=call("dba_request_connection_delete",request().put("connectionId",connection).put("connectionName","YOLO fixture"));
            profiles.rename(connection,Profiles.JSON.createObjectNode().put("expectedName","YOLO fixture").put("name","Changed while queued"));release.countDown();
            JsonNode state;long until=System.nanoTime()+5_000_000_000L;
            do{Thread.sleep(20);state=call("dba_request_status",Profiles.JSON.createObjectNode().put("requestId",pending.path("id").asText()));}while(state.path("state").asText().equals("submitted")&&System.nanoTime()<until);
            assertEquals("failed",state.path("state").asText(),state.toString());assertEquals("Changed while queued",profiles.get(connection).path("name").asText());
        }finally{release.countDown();}
    }
}
