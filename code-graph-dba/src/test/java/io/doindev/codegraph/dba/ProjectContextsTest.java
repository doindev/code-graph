package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.store.DocumentStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ProjectContextsTest {
    @TempDir Path directory;
    final String project=ProjectContexts.projectId("/sample/project");
    final AtomicLong clock=new AtomicLong(1_000_000);
    final ArrayNode projects=Profiles.JSON.createArrayNode().add(Profiles.JSON.createObjectNode().put("id",project).put("name","sample"));
    Profiles profiles;Connections connections;AgentAccess agents;ProjectContexts contexts;QueryJobs jobs;ApprovalQueue approvals;
    String connection,principal,url,binding;
    @BeforeEach void setup()throws Exception{
        url="jdbc:h2:mem:context_"+UUID.randomUUID().toString().replace("-","")+";DB_CLOSE_DELAY=-1";
        profiles=new Profiles(directory,new DbaTest.MemoryVault());
        ObjectNode input=new DbaTest().input().put("url",url).put("templateId","h2");connection=profiles.put(null,input).path("id").asText();
        connections=new Connections(profiles);agents=new AgentAccess(directory);
        principal=agents.create(Profiles.JSON.createObjectNode().put("name","context agent").set("grants",Profiles.JSON.createArrayNode()),profiles).path("id").asText();
        contexts=new ProjectContexts(profiles,connections,agents,clock::get,false);
        contexts.attach(new ProjectContextHost(){public JsonNode projects(){return projects;}public DocumentStore documents(){return DocumentStore.memory(64L<<20);}});
        binding=contexts.save(inputBinding()).path("id").asText();
        agents.contextGrants(principal,Profiles.JSON.createArrayNode().add(Profiles.JSON.createObjectNode().put("bindingId",binding).put("requestLive",true)),contexts);
        jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,5),agents::alive);approvals=new ApprovalQueue(contexts,profiles,agents,jobs,clock::get);
        try(Connection c=connections.open(connection);Statement s=c.createStatement()){s.execute("CREATE TABLE PUBLIC.ITEMS(ID INT PRIMARY KEY, LABEL VARCHAR(100))");s.execute("CREATE VIEW PUBLIC.ITEM_VIEW AS SELECT ID,LABEL FROM PUBLIC.ITEMS");s.execute("INSERT INTO PUBLIC.ITEMS VALUES(1,'first')");c.commit();}
    }
    ObjectNode inputBinding(){return Profiles.JSON.createObjectNode().put("projectId",project).put("connectionId",connection).put("schema","PUBLIC").put("environment","dev").put("role","primary").put("purpose","Primary application database").put("scanIntervalSeconds",10).put("idleTimeoutSeconds",20);}
    @AfterEach void cleanup()throws Exception{if(approvals!=null)approvals.close();if(contexts!=null)contexts.close();if(jobs!=null)jobs.close();if(connections!=null)connections.close();if(profiles!=null)profiles.close();}
    JsonNode state(){return contexts.state().path("bindings").get(0);}
    void finishScan()throws Exception{long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(contexts.state().path("scanInProgress").asBoolean()&&System.nanoTime()<until)Thread.sleep(10);assertFalse(contexts.state().path("scanInProgress").asBoolean(),"scan timed out");assertNotEquals("failed",state().path("state").asText(),state().toString());}
    ObjectNode args(){return Profiles.JSON.createObjectNode().put("bindingId",binding);}
    ObjectNode request(String sql){return args().put("requestId",UUID.randomUUID().toString()).put("purpose","Test approved execution").put("sql",sql);}
    JsonNode complete(String id)throws Exception{long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);JsonNode result;do{result=approvals.get(principal,id);if(Set.of("complete","failed","cancelled").contains(result.path("job").path("state").asText()))return result;Thread.sleep(10);}while(System.nanoTime()<until);fail(result.toString());return result;}
    long count()throws Exception{try(Connection c=connections.open(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM PUBLIC.ITEMS")){r.next();return r.getLong(1);}}
    @Test void trustedLocalCanDiscoverTargetsAndRequestSqlButCannotSkipHumanApproval()throws Exception{
        principal=agents.trustedLocal();var listed=contexts.agent(principal,"dba_list_project_databases",Profiles.JSON.createObjectNode());
        assertEquals(binding,listed.path("bindings").get(0).path("id").asText());assertTrue(listed.path("bindings").get(0).path("effectivePermissions").isEmpty());
        assertThrows(SecurityException.class,()->contexts.agent(principal,"dba_search_objects",args()));
        JsonNode proposed=approvals.request(principal,request("INSERT INTO PUBLIC.ITEMS VALUES(2,'approved')"));String id=proposed.path("id").asText();
        assertEquals("awaiting_approval",proposed.path("state").asText());assertEquals(1,count());
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,"always_environment_read",true));
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,"approve_once",false));assertEquals(1,count());
        approvals.decide("human",id,"approve_once",true);assertEquals("complete",complete(id).path("job").path("state").asText());assertEquals(2,count());
        agents.grantRead(principal,"always_environment_read","query",contexts.binding(binding));
        JsonNode next=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS WHERE ID=2"));assertEquals("awaiting_approval",next.path("state").asText());assertEquals(2,count());
        approvals.cancel(principal,next.path("id").asText());assertEquals(2,count());
    }
    @Test void idleThenQueryResumesAndStatusPollingDoesNotExtendLease()throws Exception{
        contexts.tick();assertFalse(state().path("active").asBoolean());assertEquals("never_scanned",state().path("state").asText());
        contexts.agent(principal,"dba_search_objects",args().put("query","ITEM"));contexts.tick();finishScan();assertTrue(state().path("active").asBoolean());assertTrue(state().path("snapshot").path("objects").asInt()>=2);
        long generation=state().path("generation").asLong();clock.addAndGet(21000);contexts.agent(principal,"dba_scan_status",args());contexts.tick();assertFalse(state().path("active").asBoolean());assertEquals(generation,state().path("generation").asLong());
        contexts.agent(principal,"dba_search_objects",args());contexts.tick();finishScan();assertTrue(state().path("generation").asLong()>generation);
    }
    @Test void snapshotsContainVersionDdlHashesAndBoundedPages()throws Exception{
        contexts.scanNow(binding);finishScan();JsonNode search=contexts.agent(principal,"dba_search_objects",args().put("query","ITEM").put("limit",1));assertEquals(1,search.path("objects").size());assertTrue(search.path("truncated").asBoolean());assertEquals("H2",search.path("version").path("product").asText());assertFalse(search.path("version").path("driverVersion").asText().isBlank());
        String id=search.path("objects").get(0).path("id").asText();JsonNode ddl=contexts.agent(principal,"dba_get_indexed_ddl",args().put("objectId",id).put("length",12));assertTrue(ddl.path("ddl").asText().length()<=12);assertEquals(64,ddl.path("object").path("definitionHash").asText().length());assertFalse(ddl.path("object").has("ddl"));
        assertThrows(SecurityException.class,()->contexts.agent("other","dba_search_objects",args()));
    }
    @Test void projectBindingsPersistAndCannotBeRetargetedUnderExistingGrants()throws Exception{
        assertThrows(IllegalArgumentException.class,()->contexts.save(contexts.binding(binding).put("schema","SECRET")));
        contexts.close();contexts=new ProjectContexts(profiles,connections,agents,clock::get,false);assertEquals(binding,contexts.binding(binding).path("id").asText());assertTrue(Files.readString(directory.resolve("project-contexts.json")).contains("PUBLIC"));
    }
    @Test void catalogCursorRejectsChangedGenerationAndOtherFilters()throws Exception{
        contexts.scanNow(binding);finishScan();
        JsonNode first=contexts.agent(principal,"dba_search_objects",args().put("query","ITEM").put("limit",1));
        String cursor=first.path("nextCursor").asText();assertFalse(cursor.isBlank());
        JsonNode next=contexts.agent(principal,"dba_search_objects",args().put("query","ITEM").put("limit",1).put("cursor",cursor));
        assertNotEquals(first.path("objects").get(0).path("id"),next.path("objects").get(0).path("id"));
        assertThrows(IllegalArgumentException.class,()->contexts.agent(principal,"dba_search_objects",args().put("query","OTHER").put("cursor",cursor)));
        assertThrows(IllegalArgumentException.class,()->contexts.agent(principal,"dba_search_objects",args().put("query","ITEM").put("offset",0).put("cursor",cursor)));
        contexts.scanNow(binding);finishScan();
        assertTrue(assertThrows(IllegalArgumentException.class,()->contexts.agent(principal,"dba_search_objects",args().put("query","ITEM").put("cursor",cursor))).getMessage().contains("stale_cursor"));
    }
    @Test void boundedStatusWaitDoesNotExtendActivityAndRefreshNeedsAuthorization()throws Exception{
        contexts.scanNow(binding);finishScan();long generation=state().path("generation").asLong();
        JsonNode waited=contexts.agent(principal,"dba_scan_status",args().put("afterGeneration",generation).put("waitMillis",30));
        assertFalse(waited.path("waitTimedOut").asBoolean(),"An idle target must not be reported as still pending");
        assertEquals("succeeded",waited.path("lastRun").path("state").asText());
        JsonNode revisionWait=contexts.agent(principal,"dba_scan_status",args().put("afterScanRevision",waited.path("scanRevision").asLong()).put("waitMillis",30));
        assertTrue(revisionWait.path("waitTimedOut").asBoolean());
        assertThrows(IllegalArgumentException.class,()->contexts.agent(principal,"dba_scan_status",args().put("waitMillis",1)));
        assertThrows(SecurityException.class,()->contexts.agent("other","dba_refresh_catalog",args()));
        contexts.agent(principal,"dba_refresh_catalog",args());finishScan();
        JsonNode newer=contexts.agent(principal,"dba_scan_status",args().put("afterGeneration",generation).put("waitMillis",100));
        assertFalse(newer.path("waitTimedOut").asBoolean());assertTrue(newer.path("generation").asLong()>generation);
    }
    @Test void legacyEnvironmentsAndLabelsMigrateWithoutWideningPolicies()throws Exception{
        contexts.close();ObjectNode old=Profiles.JSON.createObjectNode().put("version",1);ObjectNode legacy=old.putArray("bindings").addObject().put("id",binding).put("projectId",project).put("projectName","sample").put("connectionId",connection).put("database","").put("schema","PUBLIC").put("environment","qa").put("label","primary").put("enabled",true).put("scanIntervalSeconds",10).put("idleTimeoutSeconds",20);Files.writeString(directory.resolve("project-contexts.json"),old.toString());
        contexts=new ProjectContexts(profiles,connections,agents,clock::get,false);ObjectNode migrated=contexts.binding(binding);assertEquals("test",migrated.path("environment").asText());assertTrue(migrated.path("role").asText().startsWith("primary-legacy-"));assertTrue(migrated.path("reviewRequired").asBoolean());assertFalse(migrated.path("legacyEnvironment").asBoolean());
        contexts.close();legacy.put("environment","sandbox");Files.writeString(directory.resolve("project-contexts.json"),old.toString());contexts=new ProjectContexts(profiles,connections,agents,clock::get,false);ObjectNode unknown=contexts.binding(binding);assertTrue(unknown.path("legacyEnvironment").asBoolean());assertThrows(IllegalArgumentException.class,()->agents.grantRead(principal,"always_environment_read","catalog",unknown));
    }
    @Test void sameScopeSharesOneSnapshotAndAnyActiveProjectKeepsItRunning()throws Exception{
        String other=ProjectContexts.projectId("/sample/other");projects.addObject().put("id",other).put("name","other");String b2=contexts.save(inputBinding().put("projectId",other).put("idleTimeoutSeconds",60)).path("id").asText();
        contexts.touch(project);contexts.touch(other);contexts.tick();finishScan();long generation=state().path("generation").asLong();assertEquals(generation,contexts.state().path("bindings").get(1).path("generation").asLong());
        clock.addAndGet(21000);contexts.tick();finishScan();assertFalse(state().path("active").asBoolean());assertTrue(contexts.state().path("bindings").get(1).path("active").asBoolean());assertTrue(state().path("generation").asLong()>generation);
    }
    @Test void disablingBindingDeniesQueriesAndPendingApproval()throws Exception{
        JsonNode r=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS"));contexts.save(contexts.binding(binding).put("enabled",false));assertThrows(SecurityException.class,()->contexts.agent(principal,"dba_search_objects",args()));assertThrows(SecurityException.class,()->approvals.decide("human",r.path("id").asText(),true,true));assertEquals(1,count());
    }
    @Test void queryDoesNotExecuteUntilHumanApprovalAndApprovalIsSingleUse()throws Exception{
        ObjectNode input=request("DELETE FROM PUBLIC.ITEMS");JsonNode r=approvals.request(principal,input);String id=r.path("id").asText();assertEquals(1,count());assertEquals("destructive",r.path("classification").path("risk").asText());
        assertEquals(id,approvals.request(principal,input).path("id").asText());assertThrows(IllegalArgumentException.class,()->approvals.request(principal,input.deepCopy().put("sql","DROP TABLE PUBLIC.ITEMS")));
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,true,false));assertEquals(1,count());
        approvals.decide("human",id,true,true);JsonNode done=complete(id);assertEquals("complete",done.path("job").path("state").asText(),done.toString());assertEquals(0,count());assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,true,true));assertEquals(id,approvals.request(principal,input).path("id").asText());
    }
    @Test void rejectedExpiredForgedAndRevokedRequestsDoNotExecute()throws Exception{
        ObjectNode input=request("DELETE FROM PUBLIC.ITEMS");assertThrows(IllegalArgumentException.class,()->approvals.request(principal,input.deepCopy().put("approved",true)));
        String id=approvals.request(principal,input).path("id").asText();approvals.decide("human",id,false,false);assertEquals(1,count());
        String expired=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS")).path("id").asText();clock.addAndGet(301000);assertThrows(IllegalArgumentException.class,()->approvals.decide("human",expired,true,true));
        String revoked=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS")).path("id").asText();agents.contextGrants(principal,Profiles.JSON.createArrayNode(),contexts);assertThrows(SecurityException.class,()->approvals.decide("human",revoked,true,true));assertEquals(1,count());
    }
    @Test void connectionChangeInvalidatesReviewAndAuditContainsNoSqlLiterals()throws Exception{
        String id=approvals.request(principal,request("INSERT INTO PUBLIC.ITEMS VALUES (2,'sensitive_literal')")).path("id").asText();profiles.rename(connection,Profiles.JSON.createObjectNode().put("expectedName","test").put("name","changed"));assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,true,true));assertFalse(Files.readString(directory.resolve("agent-approvals.jsonl")).contains("sensitive_literal"));assertEquals(1,count());
    }
    @Test void pendingApprovalHoldsProjectWithoutOpeningConnectionAndExpires(){
        int pools=connections.count();String id=approvals.request(principal,request("SELECT * FROM PUBLIC.ITEMS")).path("id").asText();assertEquals(pools,connections.count());clock.addAndGet(21000);assertTrue(state().path("active").asBoolean());clock.addAndGet(301000);approvals.tick();assertEquals("expired",approvals.get(principal,id).path("state").asText());clock.addAndGet(21000);assertFalse(state().path("active").asBoolean());
    }
    @Test void metadataChangesInvalidateReviewButIdenticalScansDoNot()throws Exception{
        contexts.scanNow(binding);finishScan();String before=contexts.fingerprint(binding);String id=approvals.request(principal,request("SELECT * FROM ITEMS")).path("id").asText();contexts.scanNow(binding);finishScan();assertEquals(before,contexts.fingerprint(binding));
        try(Connection c=connections.open(connection);Statement st=c.createStatement()){st.execute("ALTER TABLE ITEMS ADD COLUMN EXTRA INT");c.commit();}contexts.scanNow(binding);finishScan();assertNotEquals(before,contexts.fingerprint(binding));assertTrue(state().path("snapshot").path("changes").path("modified").asInt()>0);assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,true,true));assertTrue(state().path("snapshot").path("recentScans").size()>=3);
    }
    @Test void failedPublicationRetainsPreviousSnapshot()throws Exception{
        int[] publications={0};contexts.attach(new ProjectContextHost(){public JsonNode projects(){return projects;}public DocumentStore documents(){DocumentStore delegate=DocumentStore.memory(64L<<20);return new DocumentStore(){public void replace(java.util.function.Consumer<Writer> writer){if(++publications[0]>1)throw new IllegalStateException("fixture publish failure");delegate.replace(writer);}public byte[] get(String key){return delegate.get(key);}public void scan(String prefix,java.util.function.BiConsumer<String,byte[]> visitor){delegate.scan(prefix,visitor);}public <T>T read(java.util.function.Supplier<T> read){return delegate.read(read);}public void close(){delegate.close();}};}});
        contexts.scanNow(binding);finishScan();String fingerprint=contexts.fingerprint(binding);contexts.scanNow(binding);finishScan();assertEquals("stale",state().path("state").asText());assertEquals(1,state().path("generation").asInt());assertEquals(fingerprint,contexts.fingerprint(binding));
        JsonNode failed=contexts.agent(principal,"dba_scan_status",args().put("afterGeneration",1).put("waitMillis",5000));
        assertFalse(failed.path("scanInProgress").asBoolean());assertFalse(failed.path("waitTimedOut").asBoolean());
        assertEquals("failed",failed.path("lastRun").path("state").asText());assertEquals("publishing",failed.path("lastRun").path("phase").asText());
        assertFalse(failed.toString().contains("fixture publish failure"));assertTrue(contexts.agent(principal,"dba_search_objects",args()).path("objects").size()>0);
    }
    @Test void databaseQueriesRenewHostLeaseButPollingDoesNot(){int[] leases={0};contexts.attach(new ProjectContextHost(){public JsonNode projects(){return projects;}public DocumentStore documents(){return DocumentStore.memory(64L<<20);}public AutoCloseable hold(String id){assertEquals(project,id);leases[0]++;return ()->{};}});contexts.agent(principal,"dba_scan_status",args());assertEquals(0,leases[0]);contexts.agent(principal,"dba_search_objects",args());assertEquals(1,leases[0]);}
    @Test void agentCanCancelWithoutLegacyConnectionGrant(){String id=approvals.request(principal,request("DELETE FROM ITEMS")).path("id").asText();assertThrows(SecurityException.class,()->approvals.cancel("other",id));assertEquals("cancelled",approvals.cancel(principal,id).path("state").asText());assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,true,true));}
    @Test void rolledBackApprovedFailureDoesNotLeakChanges()throws Exception{
        String id=approvals.request(principal,request("INSERT INTO ITEMS VALUES(2,'second')")).path("id").asText();approvals.decide("human",id,true,true);assertEquals("complete",complete(id).path("state").asText());assertEquals(2,count());
        String duplicate=approvals.request(principal,request("INSERT INTO ITEMS VALUES(2,'duplicate')")).path("id").asText();approvals.decide("human",duplicate,true,true);JsonNode result=complete(duplicate);assertEquals("failed",result.path("state").asText());assertEquals("rollback_requested",result.path("job").path("outcome").asText());assertEquals(2,count());
    }
    @Test void everyTemplateHasAProviderPathAndUnknownSqlAlwaysRequiresApproval()throws Exception{
        assertTrue(DatabaseCatalog.ALL.size()>40);for(var template:DatabaseCatalog.ALL)assertFalse(CatalogScanner.engine(Profiles.JSON.createObjectNode().put("templateId",template.id())).isBlank());
        try(Connection c=connections.open(connection)){for(var template:DatabaseCatalog.ALL)try(var docs=DocumentStore.memory(16L<<20)){ObjectNode profile=profiles.get(connection).put("templateId",template.id());ObjectNode[] snapshot={null};docs.replace(w->{try{snapshot[0]=new CatalogScanner(c,profile,contexts.binding(binding),w,()->false).scan();}catch(Exception e){throw new RuntimeException(template.id(),e);}});assertTrue(snapshot[0].path("objects").asInt()>=2,template.id());assertEquals("H2",snapshot[0].path("version").path("product").asText());c.rollback();}}
        for(String sql:List.of("DROP TABLE t","DELETE FROM t","TRUNCATE t","SELECT 1","CALL native_procedure()","MATCH (n) DETACH DELETE n"))assertTrue(ApprovalQueue.classify(sql).path("approvalRequired").asBoolean());
    }
    @Test void scanStatusNeverRewritesSavedDefaultCatalogSelector()throws Exception{
        assertEquals("",contexts.binding(binding).path("database").asText());contexts.scanNow(binding);finishScan();
        assertEquals("",state().path("database").asText());assertFalse(state().path("catalogTarget").path("database").asText().isBlank());
        var status=contexts.agent(principal,"dba_scan_status",args());assertEquals("",status.path("database").asText());
        contexts.save(((ObjectNode)state()).put("purpose","Updated after scanning"));assertEquals("Updated after scanning",contexts.binding(binding).path("purpose").asText());
        var summary=contexts.agent(agents.trustedLocal(),"dba_list_project_databases",Profiles.JSON.createObjectNode()).path("bindings").get(0);
        for(String field:List.of("snapshot","currentRun","lastRun","catalogTarget"))assertFalse(summary.has(field),field);
    }

}
