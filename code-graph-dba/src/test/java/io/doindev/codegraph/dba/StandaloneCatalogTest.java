package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneCatalogTest {
    @TempDir Path directory;
    ProjectContextsTest fixture;
    WorkflowTargets targets;
    String database;
    @BeforeEach void setup()throws Exception{
        fixture=new ProjectContextsTest();fixture.directory=directory;fixture.setup();
        database=ApprovalScope.resolve(fixture.profiles.get(fixture.connection),fixture.contexts.binding(fixture.binding),Profiles.JSON.createObjectNode()).path("database").asText();
        fixture.contexts.remove(fixture.binding);fixture.projects.removeAll();
        fixture.agents.grantRead(fixture.principal,"always_connection_read","catalog",Profiles.JSON.createObjectNode().put("connectionId",fixture.connection));
        targets=new WorkflowTargets(fixture.profiles,fixture.agents,fixture.contexts,fixture.approvals.reusable);
        fixture.contexts.accounting(fixture.jobs);
    }
    @AfterEach void close()throws Exception{if(fixture!=null)fixture.cleanup();}
    ObjectNode args(){return Profiles.JSON.createObjectNode().put("connectionId",fixture.connection).put("connectionName","test").put("database",database).put("schema","PUBLIC");}
    JsonNode call(String operation,ObjectNode args){
        var first=new java.util.concurrent.atomic.AtomicBoolean(true);
        return fixture.contexts.standalone(fixture.principal,operation,args,()->targets.cached(fixture.principal,null,operation,args,first.getAndSet(false)));
    }
    void scan()throws Exception{
        call("dba_refresh_catalog",args());
        long deadline=System.nanoTime()+5_000_000_000L;
        while(fixture.contexts.state().path("scanInProgress").asBoolean()&&System.nanoTime()<deadline)Thread.sleep(10);
        var status=call("dba_scan_status",args());assertTrue(status.path("generation").asLong()>0,status.toString());
    }
    @Test void standaloneCatalogSupportsSearchDdlPropertiesAndDependenciesWithoutProject()throws Exception{
        assertTrue(fixture.contexts.state().path("projects").isEmpty());assertTrue(fixture.contexts.state().path("bindings").isEmpty());
        scan();var search=call("dba_search_objects",args().put("query","ITEM").put("limit",1));
        assertEquals(1,search.path("objects").size());assertTrue(search.has("nextCursor"));assertEquals("standalone",search.path("scope").asText());
        String id=search.path("objects").get(0).path("id").asText();
        assertTrue(call("dba_get_indexed_ddl",args().put("objectId",id)).has("ddl"));
        assertTrue(call("dba_get_indexed_properties",args().put("objectId",id)).has("properties"));
        assertTrue(call("dba_get_database_dependencies",args().put("objectId",id)).has("dependencies"));
        assertThrows(IllegalArgumentException.class,()->call("dba_find_code_references",args().put("objectId",id)));
        assertTrue(fixture.contexts.state().path("bindings").isEmpty(),"No synthetic binding is persisted");
    }
    @Test void scopeErrorsRevocationAndNameChangesNeverReuseAuthorization()throws Exception{
        scan();var wrong=args().put("connectionName","wrong");
        assertThrows(IllegalArgumentException.class,()->call("dba_search_objects",wrong));
        var missing=args();missing.remove("database");assertThrows(IllegalArgumentException.class,()->call("dba_search_objects",missing));
        assertThrows(SecurityException.class,()->targets.cached("other",null,"dba_search_objects",args()));
        String policy=fixture.agents.agent(fixture.principal).path("readPolicies").get(0).path("id").asText();
        fixture.agents.removePolicy(fixture.principal,policy);
        assertThrows(SecurityException.class,()->call("dba_search_objects",args()));
        fixture.profiles.rename(fixture.connection,Profiles.JSON.createObjectNode().put("expectedName","test").put("name","changed"));
        assertThrows(IllegalArgumentException.class,()->call("dba_search_objects",args()));
    }
    @Test void statusDoesNotRenewStandaloneRetentionAndExpiryReleasesAccountedMemory()throws Exception{
        scan();var before=call("dba_scan_status",args());long expires=before.path("expiresAt").asLong();
        assertTrue(fixture.jobs.telemetry().path("reservedBytes").asLong()>0);
        fixture.clock.addAndGet(60_000);assertEquals(expires,call("dba_scan_status",args()).path("expiresAt").asLong());
        fixture.clock.set(expires+1);fixture.contexts.tick();
        assertEquals("never_scanned",call("dba_scan_status",args()).path("state").asText());
        assertEquals(0,fixture.jobs.telemetry().path("reservedBytes").asLong());
    }
    @Test void exactScopeSharesStorageWithBindingButNotPermission()throws Exception{
        fixture.projects.addObject().put("id",fixture.project).put("name","sample");
        String binding=fixture.contexts.save(fixture.inputBinding().put("database",database)).path("id").asText();
        scan();
        var bound=fixture.contexts.state().path("bindings").get(0);
        assertEquals(call("dba_scan_status",args()).path("generation"),bound.path("generation"));
        assertThrows(SecurityException.class,()->fixture.contexts.agent("other","dba_search_objects",Profiles.JSON.createObjectNode().put("bindingId",binding)));
        var cursor=call("dba_search_objects",args().put("query","ITEM").put("limit",1)).path("nextCursor").asText();
        assertThrows(IllegalArgumentException.class,()->call("dba_search_objects",args().put("schema","OTHER").put("cursor",cursor)));
    }
    @Test void expiredScopeCannotReuseItsCursorAfterRescanningSameTarget()throws Exception{
        scan();String cursor=call("dba_search_objects",args().put("query","ITEM").put("limit",1)).path("nextCursor").asText();
        fixture.clock.set(call("dba_scan_status",args()).path("expiresAt").asLong()+1);fixture.contexts.tick();
        scan();
        assertThrows(IllegalArgumentException.class,()->call("dba_search_objects",args().put("query","ITEM").put("limit",1).put("cursor",cursor)));
    }
    @Test void liveBudgetPressureFailsWithoutCorruptingPublishedGeneration()throws Exception{
        scan();long generation=call("dba_scan_status",args()).path("generation").asLong();
        var reservation=fixture.jobs.retainAllowance(fixture.jobs.availableRetainedBytes()-1);
        try{call("dba_refresh_catalog",args());long until=System.nanoTime()+2_000_000_000L;
            while(fixture.contexts.state().path("scanInProgress").asBoolean()&&System.nanoTime()<until)Thread.sleep(10);
            var status=call("dba_scan_status",args());assertEquals("stale",status.path("state").asText());assertEquals(generation,status.path("generation").asLong());
            var failed=call("dba_scan_status",args().put("afterGeneration",generation).put("waitMillis",5000));
            assertFalse(failed.path("waitTimedOut").asBoolean());assertFalse(failed.path("scanInProgress").asBoolean());assertEquals("failed",failed.path("lastRun").path("state").asText());
            var revision=call("dba_scan_status",args().put("afterScanRevision",0).put("waitMillis",5000));assertFalse(revision.path("waitTimedOut").asBoolean());
        }finally{reservation.close();}
        assertFalse(call("dba_search_objects",args()).path("objects").isEmpty());
    }
    @Test void failedInitialScanIsNotMisreportedAsPendingOrRetriedBySearch()throws Exception{
        try(var reserved=fixture.jobs.retainAllowance(fixture.jobs.availableRetainedBytes()-1)){
            var first=call("dba_refresh_catalog",args().put("afterGeneration",0).put("waitMillis",5000));assertEquals("failed",first.path("state").asText());
            var searched=call("dba_search_objects",args());assertEquals("failed",searched.path("state").asText());assertEquals(first.path("lastRun").path("id"),searched.path("lastRun").path("id"));assertFalse(searched.path("scanInProgress").asBoolean());
        }
        scan();assertEquals("succeeded",call("dba_scan_status",args()).path("lastRun").path("state").asText());
    }
    @Test void narrowedStatusRedactsCatalogCountsAndDiagnostics()throws Exception{
        scan();var selected=targets.cached(fixture.principal,null,"dba_scan_status",args());selected.scope().putArray("readPermissionProof");
        var status=fixture.contexts.standalone(fixture.principal,"dba_scan_status",args(),()->selected);
        assertFalse(status.has("snapshot"));assertTrue(status.path("lastRun").has("startedAt"));
        for(String field:List.of("objects","dependencies","bytes","error","errorCode"))assertFalse(status.path("lastRun").has(field),field);
    }

}
