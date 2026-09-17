package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** A standalone connection must not require a graph host, onboarded project, or binding. */
class StandaloneSqlApprovalTest {
    @TempDir Path directory;
    final AtomicLong clock=new AtomicLong(1_000_000);
    Profiles profiles; Connections connections; AgentAccess agents; ProjectContexts contexts;
    QueryJobs jobs; ApprovalQueue approvals;
    String principal,connection,url;

    @BeforeEach void setup()throws Exception {
        profiles=new Profiles(directory,new DbaTest.MemoryVault());
        url="jdbc:h2:mem:standalone_"+UUID.randomUUID().toString().replace("-","")+";DB_CLOSE_DELAY=-1";
        connection=profiles.put(null,new DbaTest().input().put("name","Standalone test").put("templateId","h2").put("url",url)).path("id").asText();
        connections=new Connections(profiles);agents=new AgentAccess(directory);
        principal=agents.create(Profiles.JSON.createObjectNode().put("name","standalone agent").set("grants",Profiles.JSON.createArrayNode()),profiles).path("id").asText();
        contexts=new ProjectContexts(profiles,connections,agents,clock::get,false);
        jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,5),agents::alive);
        approvals=new ApprovalQueue(contexts,profiles,agents,jobs,clock::get);
        try(Connection c=connections.open(connection);Statement s=c.createStatement()) {s.execute("CREATE TABLE PUBLIC.ITEMS(ID INT PRIMARY KEY, NAME VARCHAR(100))");s.execute("INSERT INTO PUBLIC.ITEMS VALUES(1,'original')");c.commit();}
    }
    @AfterEach void close()throws Exception {approvals.close();jobs.close();contexts.close();connections.close();profiles.close();}
    ObjectNode request(String sql){return Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName","Standalone test").put("requestId",UUID.randomUUID().toString()).put("purpose","Verify standalone SQL").put("sql",sql);}
    long count()throws Exception {try(Connection c=connections.open(connection);Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM PUBLIC.ITEMS")){r.next();return r.getLong(1);}}
    JsonNode await(String id)throws Exception {long until=System.nanoTime()+10_000_000_000L;JsonNode result;do{result=approvals.get(principal,id);if(Set.of("complete","failed","cancelled").contains(result.path("state").asText()))return result;Thread.sleep(10);}while(System.nanoTime()<until);fail(result.toString());return result;}

    @Test void writesRequireExactOneTimeApprovalWithoutAnyProject()throws Exception {
        JsonNode r=approvals.request(principal,request("INSERT INTO PUBLIC.ITEMS VALUES(2,'private_literal')"));String id=r.path("id").asText();
        assertEquals("awaiting_approval",r.path("state").asText());assertFalse(r.has("bindingId"));assertFalse(r.has("projectId"));assertEquals(1,connections.count());assertEquals(1,count());
        assertEquals(connection,r.path("target").path("connectionId").asText());assertTrue(ApprovalPresentation.html(r).contains("Standalone test"));
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,"approve_once",false));
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,"always_connection_read",true));
        approvals.decide("human",id,"approve_once",true);JsonNode completed=await(id);assertEquals("complete",completed.path("state").asText(),completed.toString());assertEquals(2,count());
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,"approve_once",true));
        assertTrue(contexts.state().path("bindings").isEmpty());assertTrue(agents.agent(principal).path("grants").isEmpty());
        String audit=Files.readString(directory.resolve("agent-approvals.jsonl"));assertTrue(audit.contains(connection));assertFalse(audit.contains("private_literal"));
    }
    @Test void targetFormsCannotBeOmittedMixedOrSpoofed() {
        for(String key:List.of("connectionId","connectionName")){ObjectNode missing=request("SELECT 1");missing.remove(key);assertThrows(IllegalArgumentException.class,()->approvals.request(principal,missing));}
        assertThrows(SecurityException.class,()->approvals.request(principal,request("SELECT 1").put("connectionName","Other database")));
        assertThrows(IllegalArgumentException.class,()->approvals.request(principal,request("SELECT 1").put("bindingId",UUID.randomUUID().toString())));
        assertThrows(IllegalArgumentException.class,()->approvals.request(principal,request("SELECT 1").put("database","unreviewed-target")));
        assertThrows(IllegalArgumentException.class,()->approvals.request(principal,request("SELECT 1").put("approved",true)));
    }
    @Test void connectionChangeOrRemovalInvalidatesReview()throws Exception {
        String id=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS")).path("id").asText();
        profiles.rename(connection,Profiles.JSON.createObjectNode().put("expectedName","Standalone test").put("name","Renamed"));
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,"approve_once",true));assertEquals(1,count());
        ObjectNode changed=request("DELETE FROM PUBLIC.ITEMS").put("connectionName","Renamed");String removal=approvals.request(principal,changed).path("id").asText();
        profiles.remove(connection);assertThrows(IllegalArgumentException.class,()->approvals.decide("human",removal,"approve_once",true));assertEquals(1,count());
    }
    @Test void requestIdsOwnershipExpiryCancellationAndRevocationRemainEnforced()throws Exception {
        ObjectNode input=request("DELETE FROM PUBLIC.ITEMS");String id=approvals.request(principal,input).path("id").asText();
        assertEquals(id,approvals.request(principal,input).path("id").asText());assertThrows(IllegalArgumentException.class,()->approvals.request(principal,input.deepCopy().put("sql","DROP TABLE PUBLIC.ITEMS")));
        assertThrows(SecurityException.class,()->approvals.get("other",id));assertThrows(SecurityException.class,()->approvals.cancel("other",id));
        assertEquals("cancelled",approvals.cancel(principal,id).path("state").asText());assertEquals(1,count());
        String expired=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS")).path("id").asText();clock.addAndGet(300001);
        assertEquals("expired",approvals.get(principal,expired).path("state").asText());assertThrows(IllegalArgumentException.class,()->approvals.decide("human",expired,"approve_once",true));
        String revoked=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS")).path("id").asText();agents.remove(principal);
        assertThrows(IllegalArgumentException.class,()->approvals.decide("human",revoked,"approve_once",true));assertEquals(1,count());
    }
    @Test void connectionReadPoliciesCannotBecomeEnvironmentPoliciesOrAuthorizeWrites()throws Exception {
        JsonNode request=approvals.request(principal,request("SELECT ID FROM PUBLIC.ITEMS"));String id=request.path("id").asText();assertTrue(request.path("eligiblePersistentRead").asBoolean());
        for(String action:List.of("always_environment_read","always_binding_read"))assertThrows(IllegalArgumentException.class,()->approvals.decide("human",id,action,true));
        approvals.decide("human",id,"always_connection_read",true);
        // H2 can reject JDBC read-only enforcement; this test checks policy admission, not certification.
        await(id);JsonNode policy=agents.agent(principal).path("readPolicies").get(0);assertEquals("connection",policy.path("scope").asText());assertEquals(connection,policy.path("connectionId").asText());
        assertNotEquals("awaiting_approval",approvals.request(principal,request("SELECT ID FROM PUBLIC.ITEMS WHERE ID=1")).path("state").asText());
        String other=profiles.put(null,new DbaTest().input().put("name","Other")).path("id").asText();assertFalse(agents.permitsRead(principal,"query",Profiles.JSON.createObjectNode().put("connectionId",other)));
        JsonNode write=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS"));assertEquals("awaiting_approval",write.path("state").asText());assertEquals(1,count());
        agents.removePolicy(principal,policy.path("id").asText());assertEquals("awaiting_approval",approvals.request(principal,request("SELECT ID FROM PUBLIC.ITEMS")).path("state").asText());
    }
    @Test void localIdentityCanExecuteDdlThenViewsAndBoundedParameterizedReads()throws Exception {
        principal=agents.trustedLocal();
        for(String sql:List.of("CREATE TABLE PUBLIC.EXTRA(ID INT PRIMARY KEY)","CREATE VIEW PUBLIC.ITEM_NAMES AS SELECT ID,NAME FROM PUBLIC.ITEMS")){
            String id=approvals.request(principal,request(sql)).path("id").asText();approvals.decide("human",id,"approve_once",true);JsonNode completed=await(id);assertEquals("complete",completed.path("state").asText(),completed.toString());
        }
        ObjectNode read=request("SELECT COUNT(*) FROM PUBLIC.ITEM_NAMES WHERE ID = ?");read.putArray("parameters").add(1);
        String id=approvals.request(principal,read).path("id").asText();approvals.decide("human",id,"approve_once",true);JsonNode result=await(id);
        assertEquals("complete",result.path("state").asText(),result.toString());assertEquals(1,result.path("job").path("result").path("results").get(0).path("rows").get(0).get(0).asInt());
        String job=result.path("jobId").asText();assertEquals("Unknown job",assertThrows(IllegalArgumentException.class,()->jobs.require("agent:other",job)).getMessage());jobs.remove("agent:"+principal,job);
        String capped=approvals.request(principal,request("SELECT X FROM SYSTEM_RANGE(1,200)")).path("id").asText();approvals.decide("human",capped,"approve_once",true);
        JsonNode page=await(capped).path("job").path("result").path("results").get(0);assertEquals(100,page.path("rows").size());assertTrue(page.path("truncated").asBoolean());
    }
    @Test void unavailableAuditPreventsExecution()throws Exception {
        String id=approvals.request(principal,request("DELETE FROM PUBLIC.ITEMS")).path("id").asText();Path audit=directory.resolve("agent-approvals.jsonl");Files.move(audit,audit.resolveSibling("preserved-audit.jsonl"));Files.createDirectory(audit);
        assertThrows(IllegalStateException.class,()->approvals.decide("human",id,"approve_once",true));assertEquals(1,count());
    }
}
