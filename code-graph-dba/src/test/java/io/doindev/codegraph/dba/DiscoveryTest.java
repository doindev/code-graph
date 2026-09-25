package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryTest {
    @TempDir Path directory;

    @Test void standaloneLiveCapabilitiesUseAuthorizedBoundedJobsAndExactNames()throws Exception{
        var vault=new DbaTest.MemoryVault();String connection,principal;
        try(var profiles=new Profiles(directory,vault)){
            connection=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            var agents=new AgentAccess(directory);
            var request=Profiles.JSON.createObjectNode().put("name","observer");
            request.putArray("grants").addObject().put("connectionId",connection).putArray("objects").addObject().put("schema","PUBLIC").put("name","*");
            principal=agents.create(request,profiles).path("id").asText();
        }
        var config=new DbaConfig(directory,64L<<20,2,100,100,5,60,"none");
        try(var runtime=new DbaRuntime(config,vault,false)){
            var args=Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName","test").put("live",true);
            String unprivileged=runtime.trustedLocalAgent();
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(unprivileged,"dba_get_capabilities",args));
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,"dba_get_capabilities",args.deepCopy().put("connectionName","other")));
            var job=runtime.agentCall(principal,"dba_get_capabilities",args);
            var poll=Profiles.JSON.createObjectNode().put("jobId",job.path("id").asText());
            com.fasterxml.jackson.databind.JsonNode status=null;
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(System.nanoTime()<deadline){status=runtime.agentCall(principal,"dba_job_status",poll);if(java.util.Set.of("complete","failed","cancelled").contains(status.path("state").asText()))break;Thread.sleep(20);}
            assertNotNull(status);assertEquals("complete",status.path("state").asText(),status.toString());
            assertEquals("H2",status.path("result").path("version").path("product").asText());
            assertEquals("live",status.path("result").path("freshness").asText());
            assertFalse(status.toString().contains("jdbc:h2:"));
            assertFalse(status.toString().contains("credentialRefs"));
            assertFalse(status.path("result").has("generation"));
            runtime.agentCall(principal,"dba_release_job",poll);
        }
    }

    @Test void runtimeRequiresIdentityAndEmptyDiscoveryDoesNotStartJobs()throws Exception{
        var config=new DbaConfig(directory,64L<<20,2,100,100,5);
        try(var runtime=new DbaRuntime(config,new DbaTest.MemoryVault(),false)){
            var args=Profiles.JSON.createObjectNode();
            assertThrows(SecurityException.class,()->runtime.agentCall(null,"get_workspace_context",args));
            String principal=runtime.trustedLocalAgent();
            var context=runtime.agentCall(principal,"get_workspace_context",args);
            assertEquals(0,context.path("total").asInt());
            assertEquals(0,context.path("entries").size());
            assertTrue(context.path("dbaEnabled").asBoolean());
            assertTrue(runtime.agentCall(principal,"dba_list_templates",args).path("templates").size()>0);
        }
    }

    @Test void workspacePagesAreAllowlistedScopedAndInvalidatedByChanges(){
        var discovery=new WorkspaceDiscovery();var args=Profiles.JSON.createObjectNode().put("limit",1);
        var projects=Profiles.JSON.createArrayNode().add(Profiles.JSON.createObjectNode().put("id","p").put("name","project"));
        var connections=Profiles.JSON.createArrayNode().add(Profiles.JSON.createObjectNode().put("id","c").put("name","local").put("password","must-not-leak").put("url","private"));
        var bindings=Profiles.JSON.createObjectNode();bindings.putArray("bindings");
        var first=discovery.page("agent",args,projects,connections,bindings);
        args.put("cursor",first.path("nextCursor").asText());
        assertThrows(IllegalArgumentException.class,()->discovery.page("other",args,projects,connections,bindings));
        var second=discovery.page("agent",args,projects,connections,bindings);
        assertFalse(second.toString().contains("must-not-leak"));assertFalse(second.toString().contains("private"));
        assertEquals("connection",second.path("entries").get(0).path("kind").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode)projects.get(0)).put("generation",2);
        assertThrows(IllegalArgumentException.class,()->discovery.page("agent",args,projects,connections,bindings));
    }

    @Test void capabilitiesRequireObservedVerifiedEngineAndApprovalChannelForMigrations(){
        var profile=Profiles.JSON.createObjectNode().put("templateId","postgresql").put("password","not-public");
        var scope=Profiles.JSON.createObjectNode().put("connectionId","c").put("database","d").put("schema","public").put("profileRevision","internal");
        var empty=Profiles.JSON.createObjectNode();
        var unknown=DatabaseCapabilities.describe(profile,scope,empty,false);
        assertEquals("unknown",unknown.path("engine").asText());assertFalse(unknown.has("version"));
        assertFalse(unknown.toString().contains("not-public"));assertFalse(unknown.toString().contains("internal"));
        var snapshot=Profiles.JSON.createObjectNode().put("engine","postgresql").put("generation",8);
        snapshot.putObject("version").put("product","PostgreSQL").put("server","16.1");
        var known=DatabaseCapabilities.describe(profile,scope,snapshot,true);
        assertEquals("16.1",known.path("version").path("server").asText());
        assertTrue(known.path("operations").path("migrationPreparation").path("available").asBoolean());
        assertTrue(known.path("operations").path("migrationApplication").path("available").asBoolean());
        assertFalse(DatabaseCapabilities.describe(profile,scope,snapshot,false).path("operations").path("migrationApplication").path("available").asBoolean());
        var oracle=snapshot.deepCopy().put("engine","oracle");oracle.withObject("version").put("product","Oracle").put("major",19);
        var oracleOperations=DatabaseCapabilities.describe(profile,scope,oracle,true).path("operations");
        assertTrue(oracleOperations.path("estimatedPlan").path("mcpVendorSupported").asBoolean());
        assertTrue(oracleOperations.path("migrationPreparation").path("available").asBoolean());
        oracle.withObject("version").put("major",18);assertFalse(DatabaseCapabilities.describe(profile,scope,oracle,true).path("operations").path("estimatedPlan").path("mcpVendorSupported").asBoolean());
        var unverified=snapshot.deepCopy().put("engine","cockroachdb");
        assertFalse(DatabaseCapabilities.describe(profile,scope,unverified,true).path("operations").path("migrationPreparation").path("available").asBoolean());
    }
}
