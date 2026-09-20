package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.store.DocumentStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRequestsTest {
    @TempDir Path directory;
    Profiles profiles;Connections connections;ConnectionSetup setup;AgentAccess agents;ProjectContexts contexts;QueryJobs jobs;AgentRequests requests;NativeOperations nativeOperations;
    String principal,project,connection;
    ArrayNode projects=Profiles.JSON.createArrayNode();
    @BeforeEach void start()throws Exception{
        profiles=new Profiles(directory,new DbaTest.MemoryVault());connection=profiles.put(null,new DbaTest().input().put("name","Development")).path("id").asText();
        connections=new Connections(profiles);agents=new AgentAccess(directory);principal=agents.create(Profiles.JSON.createObjectNode().put("name","builder").set("grants",Profiles.JSON.createArrayNode()),profiles).path("id").asText();
        contexts=new ProjectContexts(profiles,connections,agents,System::currentTimeMillis,false);project=ProjectContexts.projectId("/work/sample");projects.addObject().put("id",project).put("name","sample");
        contexts.attach(new ProjectContextHost(){public JsonNode projects(){return projects;}public DocumentStore documents(){return DocumentStore.memory(16L<<20);}});
        jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,5),agents::alive);setup=new ConnectionSetup(profiles,jobs);requests=new AgentRequests(profiles,connections,setup,contexts,agents,jobs);
    }
    @AfterEach void stop()throws Exception{requests.close();contexts.close();jobs.close();if(nativeOperations!=null)nativeOperations.close();connections.close();profiles.close();}
    @Test void nativeReadsRequireExactReviewAndRejectStaleTargetsOrForgedPersistentChoices()throws Exception{
        nativeOperations=new NativeOperations(profiles,jobs);requests.nativeOperations(nativeOperations);
        ObjectNode nativeProfile=Profiles.JSON.createObjectNode().put("name","Native local").put("templateId","redis-native").put("url","redis://127.0.0.1:1");
        String id=profiles.put(null,nativeProfile).path("id").asText();
        ObjectNode input=request("native-1","Inspect local Redis only").put("connectionId",id).put("connectionName","Native local").put("database","0");
        input.putArray("command").add("PING");
        JsonNode pending=requests.request(principal,"native_command",input);
        assertEquals("awaiting_approval",pending.path("state").asText());assertFalse(pending.path("mutation").asBoolean());
        assertEquals(0,nativeOperations.telemetry().path("clients").asInt());assertFalse(pending.has("jobId"));
        assertThrows(IllegalArgumentException.class,()->requests.decide("human",pending.path("id").asText(),"always_allow",true,Profiles.JSON.createObjectNode()));
        assertEquals("awaiting_approval",requests.get(principal,pending.path("id").asText()).path("state").asText());
        profiles.rename(id,Profiles.JSON.createObjectNode().put("name","Renamed native").put("expectedName","Native local"));
        assertThrows(IllegalArgumentException.class,()->requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode()));
        assertEquals(0,nativeOperations.telemetry().path("clients").asInt());
    }
    @Test void nativeBindingsFixEnvironmentDatabaseAndRejectRevisionsOrOverrides()throws Exception{
        nativeOperations=new NativeOperations(profiles,jobs,contexts);requests.nativeOperations(nativeOperations);principal=agents.trustedLocal();
        connection=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Native bound").put("templateId","redis-native").put("url","redis://127.0.0.1:1")).path("id").asText();
        ObjectNode relation=binding("cache").put("database","0");String bindingId=contexts.save(relation).path("id").asText();
        assertFalse(contexts.state().path("bindings").get(0).path("scanSupported").asBoolean(true));
        assertThrows(IllegalArgumentException.class,()->contexts.scanNow(bindingId));
        ObjectNode input=request("native-bound","Inspect bound cache").put("bindingId",bindingId);input.putArray("command").add("PING");
        JsonNode pending=requests.request(principal,"native_command",input);
        assertEquals("dev",pending.path("target").path("environment").asText());assertEquals("cache",pending.path("target").path("role").asText());
        var observed=nativeOperations.observationScope(Profiles.JSON.createObjectNode().put("bindingId",bindingId));
        assertEquals("cache",observed.path("role").asText());assertEquals("0",observed.path("database").asText());
        assertFalse(observed.path("profileRevision").asText().isEmpty());assertFalse(observed.path("bindingRevision").asText().isEmpty());
        assertThrows(IllegalArgumentException.class,()->nativeOperations.observationScope(input.deepCopy().put("database","1")));
        assertEquals("0",pending.path("target").path("database").asText());
        assertThrows(IllegalArgumentException.class,()->requests.request(principal,"native_command",input.deepCopy().put("requestId","override").put("database","1")));
        assertThrows(IllegalArgumentException.class,()->contexts.save(relation.deepCopy().put("role","bad").put("schema","public")));
        contexts.save(contexts.binding(bindingId).put("purpose","Changed purpose"));
        assertNotEquals(observed,nativeOperations.observationScope(input));
        assertThrows(IllegalArgumentException.class,()->requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode()));
        assertEquals(0,nativeOperations.telemetry().path("clients").asInt());
    }
    @Test void nativeProfileProposalsRemainWriteOnlyAndHaveNoAutomaticAccess()throws Exception{
        ObjectNode proposed=Profiles.JSON.createObjectNode().put("templateId","mongodb-native").put("url","mongodb://localhost:27017").put("name","Native proposal").put("username","worker").put("password","never-publish-native");
        proposed.putObject("nativeOptions").put("authDatabase","admin").put("database","app");
        ObjectNode input=request("native-create","Propose native connection");input.set("profile",proposed);
        JsonNode pending=requests.request(principal,"connection_create",input);
        assertFalse(pending.toString().contains("never-publish-native"));assertEquals("mongodb",pending.path("after").path("transport").asText());
        JsonNode saved=requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode().put("saveUntested",true));
        String id=saved.path("result").path("id").asText();assertFalse(saved.path("result").path("creatorReceivesAccess").asBoolean());
        Properties secrets=profiles.credentials(profiles.get(id));try{assertEquals("never-publish-native",secrets.getProperty("password"));}finally{secrets.clear();}
        assertFalse(Files.readString(directory.resolve("agent-administration-approvals.jsonl")).contains("never-publish-native"));
    }
    @Test void sentinelProposalSecretsSurviveReviewAndNeverAppearInStatusOrAudit()throws Exception{
        ObjectNode proposed=NativeSentinelAuthTest.input();
        ObjectNode input=request("sentinel-create","Configure independent Sentinel authentication");input.set("profile",proposed);
        JsonNode pending=requests.request(principal,"connection_create",input);String approval=pending.path("id").asText();
        assertEquals("awaiting_approval",pending.path("state").asText());
        assertFalse(pending.toString().contains("sentinel-secret"));
        JsonNode editor=requests.reviewDraft("human",approval);
        assertTrue(editor.path("secretPropertyNames").toString().contains("sentinelPassword"));
        assertFalse(editor.toString().contains("sentinel-secret"));
        ObjectNode revision=Profiles.JSON.createObjectNode().put("color","#223344");
        revision.putObject("secretProperties").put("sentinelPassword","reviewer-secret");
        requests.reviseDraft("human",approval,revision);
        JsonNode result=requests.decide("human",approval,"approve_once",true,Profiles.JSON.createObjectNode().put("saveUntested",true));
        String id=result.path("result").path("id").asText();
        assertFalse(result.toString().contains("reviewer-secret"));
        Properties secrets=profiles.credentials(profiles.get(id));
        try{assertEquals("reviewer-secret",secrets.getProperty("sentinelPassword"));assertEquals("data-secret",secrets.getProperty("password"));}finally{secrets.clear();}
        String audit=Files.readString(directory.resolve("agent-administration-approvals.jsonl"));
        for(String secret:List.of("sentinel-secret","reviewer-secret","data-secret"))assertFalse(audit.contains(secret));
        assertFalse(result.path("result").path("creatorReceivesAccess").asBoolean());
    }
    ObjectNode binding(String role){return Profiles.JSON.createObjectNode().put("projectId",project).put("connectionId",connection).put("database","sample").put("environment","dev").put("role",role).put("purpose",role+" database").put("scanIntervalSeconds",60).put("idleTimeoutSeconds",120).put("enabled",true);}
    ObjectNode request(String id,String purpose){return Profiles.JSON.createObjectNode().put("requestId",id).put("purpose",purpose);}
    @Test void trustedLocalCreationAndTestsStillNeedHumanApproval()throws Exception{
        principal=agents.trustedLocal();ObjectNode input=request("local-create","Propose a disposable local database");input.set("profile",new DbaTest().input().put("name","Local proposal"));
        JsonNode pending=requests.request(principal,"connection_create",input);assertEquals("awaiting_approval",pending.path("state").asText());assertEquals(1,profiles.list().size());
        assertEquals(pending.path("id"),pending.path("approvalId"));assertEquals("local-create",pending.path("requestId").asText());
        assertTrue(agents.agent(principal).path("grants").isEmpty());
        requests.decide("human",pending.path("id").asText(),"reject",false,Profiles.JSON.createObjectNode());assertEquals(1,profiles.list().size());
        var test=requests.maybeRead(principal,"connection_test",request("local-test","Test only after permission").put("connectionId",connection).put("connectionName","Development"));
        assertEquals("awaiting_approval",test.path("state").asText());assertFalse(test.has("jobId"));
        requests.cancel(principal,test.path("id").asText());
    }
    @Test void canonicalEnvironmentAndRoleAreRequiredAndUnique()throws Exception{
        ObjectNode first=contexts.save(binding("primary"));assertEquals("dev",first.path("environment").asText());assertEquals("primary",first.path("role").asText());
        assertThrows(IllegalArgumentException.class,()->contexts.save(binding(" PRIMARY ")));
        assertThrows(IllegalArgumentException.class,()->contexts.save(binding("reporting").put("environment","sandbox")));
        assertDoesNotThrow(()->contexts.save(binding("reporting").put("environment","development")));
    }
    @Test void agentBindingMutationRequiresExactOneTimeApproval()throws Exception{
        ObjectNode input=request("bind-1","Associate the development database");input.set("binding",binding("primary"));
        JsonNode pending=requests.request(principal,"binding_create",input);assertEquals("awaiting_approval",pending.path("state").asText());assertTrue(contexts.state().path("bindings").isEmpty());
        requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode());assertEquals(1,contexts.state().path("bindings").size());
        assertThrows(IllegalArgumentException.class,()->requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode()));
        JsonNode removal=requests.request(principal,"binding_delete",request("unbind-1","Remove association").put("bindingId",contexts.state().path("bindings").get(0).path("id").asText()));requests.decide("human",removal.path("id").asText(),"reject",false,Profiles.JSON.createObjectNode());assertEquals(1,contexts.state().path("bindings").size());
    }
    @Test void environmentPolicyIncludesFutureBindingsAndCanBeRevoked()throws Exception{
        ObjectNode first=contexts.save(binding("primary"));ObjectNode detail=request("detail-1","Inspect safe connection settings").put("bindingId",first.path("id").asText());
        JsonNode pending=requests.maybeRead(principal,"connection_details",detail);assertEquals("awaiting_approval",pending.path("state").asText());
        assertThrows(IllegalArgumentException.class,()->requests.decide("human",pending.path("id").asText(),"always_environment_read",true,Profiles.JSON.createObjectNode()));
        agents.grantRead(principal,"always_environment_read","connection_details",first); // Simulate a stored legacy policy, not a new approval.
        JsonNode approved=requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode());assertEquals("complete",approved.path("state").asText());assertFalse(approved.toString().contains("credentialRef"));
        String secondConnection=profiles.put(null,new DbaTest().input().put("name","Analytics")).path("id").asText();connection=secondConnection;ObjectNode future=contexts.save(binding("analytics"));JsonNode immediate=requests.maybeRead(principal,"connection_details",request("detail-2","Inspect future binding").put("bindingId",future.path("id").asText()));assertEquals("Analytics",immediate.path("name").asText());
        JsonNode permissions=agents.permissions(principal,contexts);String policy=permissions.path("readPolicies").get(0).path("id").asText();agents.removePolicy(principal,policy);assertEquals("awaiting_approval",requests.maybeRead(principal,"connection_details",request("detail-3","Inspect after revoke").put("bindingId",future.path("id").asText())).path("state").asText());
    }
    @Test void writeOnlySecretsNeverAppearInResponsesOrAudit()throws Exception{
        ObjectNode profile=new DbaTest().input().put("name","Secret proposal").put("password","do-not-return");profile.putObject("secretProperties").put("apiToken","also-secret");
        ObjectNode input=request("create-secret","Create a local design connection");input.set("profile",profile);
        JsonNode pending=requests.request(principal,"connection_create",input);assertFalse(pending.toString().contains("do-not-return"));assertFalse(pending.toString().contains("also-secret"));
        requests.decide("human",pending.path("id").asText(),"reject",false,Profiles.JSON.createObjectNode());String audit=Files.readString(directory.resolve("agent-administration-approvals.jsonl"));assertFalse(audit.contains("do-not-return"));assertFalse(audit.contains("also-secret"));
    }
    @Test void humanCanReviseAndTestAProposalWithoutReadingBackAgentSecrets()throws Exception{
        ObjectNode profile=new DbaTest().input().put("name","Agent draft").put("password","retained-password");profile.putObject("secretProperties").put("apiToken","retained-token");
        ObjectNode input=request("revise-secret","Review a local design connection");input.set("profile",profile);JsonNode pending=requests.request(principal,"connection_create",input);String approval=pending.path("id").asText();
        ObjectNode review=(ObjectNode)requests.reviewDraft("human",approval);assertFalse(review.toString().contains("retained-password"));assertFalse(review.toString().contains("retained-token"));assertTrue(review.path("hasCredential").asBoolean());assertTrue(review.path("secretPropertyNames").toString().contains("apiToken"));
        review.put("name","Reviewed local").put("saveUntested",true);JsonNode revised=requests.reviseDraft("human",approval,review);assertEquals("Reviewed local",revised.path("after").path("name").asText());assertFalse(revised.toString().contains("retained-password"));
        JsonNode done=requests.decide("human",approval,"approve_once",true,Profiles.JSON.createObjectNode());String id=done.path("result").path("id").asText();Properties secret=profiles.credentials(profiles.get(id));try{assertEquals("retained-password",secret.getProperty("password"));assertEquals("retained-token",secret.getProperty("apiToken"));}finally{secret.clear();}
    }
    @Test void createdProfilesGrantNoAutomaticReadPermission()throws Exception{
        ObjectNode profile=new DbaTest().input().put("name","Agent local");ObjectNode input=request("create-local","Create disposable local profile");input.set("profile",profile);
        JsonNode pending=requests.request(principal,"connection_create",input);JsonNode done=requests.decide("human",pending.path("id").asText(),"approve_once",true,Profiles.JSON.createObjectNode().put("saveUntested",true));
        assertFalse(done.path("result").path("creatorReceivesAccess").asBoolean(true));String id=done.path("result").path("id").asText();assertFalse(id.isBlank());assertTrue(contexts.bindingsForConnection(id).isEmpty());
        assertEquals(principal,profiles.get(id).path("agentProvenance").path("agentId").asText());
    }
    @Test void requestedMavenDriverMustBeExplicitlyInstalledBeforeApproval()throws Exception{
        ObjectNode profile=new DbaTest().input().put("name","Needs driver").put("templateId","h2");profile.remove("jar");ObjectNode input=request("driver-create","Create a local H2 connection");input.set("profile",profile);input.putObject("driverInstall").put("groupId","com.h2database").put("artifactId","h2").put("version","latest");
        JsonNode pending=requests.request(principal,"connection_create",input);String approval=pending.path("id").asText();assertTrue(pending.path("requiresDriverInstall").asBoolean());assertEquals("com.h2database",pending.path("driverInstall").path("groupId").asText());
        assertThrows(IllegalArgumentException.class,()->requests.decide("human",approval,"approve_once",true,Profiles.JSON.createObjectNode().put("saveUntested",true)));assertEquals("awaiting_approval",requests.get(principal,approval).path("state").asText());
        ObjectNode review=(ObjectNode)requests.reviewDraft("human",approval);assertTrue(review.path("jars").isEmpty());review.putArray("jars").add(Path.of(org.h2.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());review.put("saveUntested",true);
        JsonNode revised=requests.reviseDraft("human",approval,review);assertFalse(revised.path("requiresDriverInstall").asBoolean());assertEquals("Needs driver",requests.decide("human",approval,"approve_once",true,Profiles.JSON.createObjectNode()).path("result").path("name").asText());
    }
    @Test void unavailableAuditCannotAuthorizeMutationOrPersistentAccess()throws Exception{
        ObjectNode input=request("audit-fail","Test fail-closed audit");input.set("binding",binding("primary"));
        String id=requests.request(principal,"binding_create",input).path("id").asText();
        Path audit=directory.resolve("agent-administration-approvals.jsonl");Files.move(audit,audit.resolveSibling("audit-test-backup"));Files.createDirectory(audit);
        assertThrows(IllegalStateException.class,()->requests.decide("human",id,"approve_once",true,Profiles.JSON.createObjectNode()));
        assertTrue(contexts.state().path("bindings").isEmpty());
        assertEquals("awaiting_approval",requests.get(principal,id).path("state").asText());
    }
    @Test void explicitSecretRemovalSurvivesReviewCanonicalization()throws Exception{
        profiles.put(connection,new DbaTest().input().put("name","Development").put("password","old-password").set("secretProperties",Profiles.JSON.createObjectNode().put("apiToken","old-token")));
        ObjectNode input=request("secret-removal","Remove existing credentials").put("connectionId",connection).put("connectionName","Development");
        ObjectNode patch=Profiles.JSON.createObjectNode().put("name","Development").put("removePassword",true);patch.putObject("secretProperties").putNull("apiToken");input.set("profile",patch);
        String id=requests.request(principal,"connection_update",input).path("id").asText();
        ObjectNode review=(ObjectNode)requests.reviewDraft("human",id);review.put("saveUntested",true);requests.reviseDraft("human",id,review);
        requests.decide("human",id,"approve_once",true,Profiles.JSON.createObjectNode());
        Properties secret=profiles.credentials(profiles.get(connection));try{assertNull(secret.getProperty("password"));assertNull(secret.getProperty("apiToken"));}finally{secret.clear();}
    }
}
