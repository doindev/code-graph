package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowTargetsTest {
    @TempDir Path directory;
    @Test void catalogScopeDoesNotWidenLegacyObjectAccessAndRevocationTakesEffect()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var policies=new ReusableApprovals(directory,System::currentTimeMillis)){
            String id=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            var agents=new AgentAccess(directory);String principal=agents.create(AgentAccessTest.grant(id),profiles).path("id").asText();
            try(var contexts=new ProjectContexts(profiles,connections,agents,System::currentTimeMillis,false)){
                var resolver=new WorkflowTargets(profiles,agents,contexts,policies);
                var request=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","test");
                assertThrows(SecurityException.class,()->resolver.catalog(principal,null,request));
                var permission=agents.grantRead(principal,"always_connection_read","catalog",Profiles.JSON.createObjectNode().put("connectionId",id));
                var target=resolver.catalog(principal,null,request);
                assertEquals("PUBLIC",target.scope().path("schema").asText());assertTrue(target.scope().has("profileRevision"));
                assertThrows(IllegalArgumentException.class,()->resolver.catalog(principal,null,request.deepCopy().put("connectionName","not test")));
                agents.removePolicy(principal,permission.path("id").asText());
                assertThrows(SecurityException.class,()->resolver.catalog(principal,null,request));
            }
        }
    }
    @Test void runtimeCaptureAndComparisonEnforceOwnerAndRetainedSnapshotIds()throws Exception{
        var vault=new DbaTest.MemoryVault();String id,principal;
        try(var profiles=new Profiles(directory,vault)){
            id=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            var agents=new AgentAccess(directory);principal=agents.create(AgentAccessTest.grant(id),profiles).path("id").asText();
            agents.grantRead(principal,"always_connection_read","catalog",Profiles.JSON.createObjectNode().put("connectionId",id));
        }
        try(var runtime=new DbaRuntime(new DbaConfig(directory,128L<<20,2,100,100,10,60,"none"),vault,false)){
            var request=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","test");
            var first=await(runtime,principal,runtime.agentCall(principal,"dba_capture_schema",request));
            var second=await(runtime,principal,runtime.agentCall(principal,"dba_capture_schema",request));
            var compare=Profiles.JSON.createObjectNode().put("leftSnapshotId",first.path("id").asText()).put("rightSnapshotId",second.path("id").asText());
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(runtime.trustedLocalAgent(),"dba_compare_schemas",compare));
            var result=await(runtime,principal,runtime.agentCall(principal,"dba_compare_schemas",compare));
            assertEquals(0,result.path("result").path("differences").size());
            assertFalse(result.toString().contains("jdbc:h2:"));assertFalse(result.toString().contains("profileRevision"));
            runtime.agentCall(principal,"dba_release_job",Profiles.JSON.createObjectNode().put("jobId",first.path("id").asText()));
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,"dba_compare_schemas",compare));
        }
    }
    private static JsonNode await(DbaRuntime runtime,String principal,JsonNode job)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);JsonNode status=job;
        while(System.nanoTime()<deadline){status=runtime.agentCall(principal,"dba_job_status",Profiles.JSON.createObjectNode().put("jobId",job.path("id").asText()));if(status.path("finished").asLong()>0)break;Thread.sleep(10);}
        assertEquals("complete",status.path("state").asText(),status.toString());assertTrue(status.path("finished").asLong()>0);return status;
    }
}
