package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end H2 creation tests using the same queued execution and revocation callback as MCP. */
class ReusableSqlApprovalTest extends StandaloneSqlApprovalTest {
    void session(String id){approvals.reusable.sessions.register(id,principal,clock.get()+60000);}
    @Test void categoryGrantCreatesOnlyNewTablesOnSameSessionAndTarget()throws Exception{
        session("one");session("two");
        JsonNode first=approvals.request(principal,"one",request("CREATE TABLE PUBLIC.FIRST_QA(ID INT)"));
        assertTrue(first.path("operation").path("eligible").asBoolean(),first.toString());
        String id=first.path("id").asText();
        approvals.decide("human",id,ReusableApprovals.SESSION,true);
        JsonNode done=await(id);assertEquals("complete",done.path("state").asText(),done.toString());
        var second=approvals.request(principal,"one",request("CREATE TABLE PUBLIC.SECOND_QA(ID INT)"));
        assertTrue(second.has("matchedPolicy"));assertEquals("complete",await(second.path("id").asText()).path("state").asText());
        assertEquals("awaiting_approval",approvals.request(principal,"two",request("CREATE TABLE PUBLIC.THIRD_QA(ID INT)")).path("state").asText());
        for(String sql:List.of("CREATE VIEW PUBLIC.V AS SELECT 1","DROP TABLE PUBLIC.FIRST_QA","CREATE TABLE OTHER.X(ID INT)")){
            var pending=approvals.request(principal,"one",request(sql));assertEquals("awaiting_approval",pending.path("state").asText());
            if(!pending.path("operation").path("eligible").asBoolean())for(String action:ReusableApprovals.ACTIONS)
                assertThrows(IllegalArgumentException.class,()->approvals.decide("human",pending.path("id").asText(),action,true));
        }
        approvals.reusable.sessions.remove("one");assertTrue(approvals.reusable.list(principal).isEmpty());
    }
    @Test void persistentCreationDoesNotPermitReplacementAndExpiredSessionCannotApprove()throws Exception{
        session("one");session("two");String sql="CREATE TABLE PUBLIC.PERSISTED_QA(ID INT)";
        var first=approvals.request(principal,"one",request(sql));approvals.decide("human",first.path("id").asText(),ReusableApprovals.EXACT,true);
        assertEquals("complete",await(first.path("id").asText()).path("state").asText());
        var repeated=approvals.request(principal,"two",request(sql));assertTrue(repeated.has("matchedPolicy"));
        assertEquals("failed",await(repeated.path("id").asText()).path("state").asText(),"Existing object must fail rather than replace");
        var pending=approvals.request(principal,"one",request("CREATE TABLE PUBLIC.EXPIRED_QA(ID INT)"));
        approvals.reusable.sessions.remove("one");assertThrows(IllegalArgumentException.class,()->approvals.decide("human",pending.path("id").asText(),ReusableApprovals.SIMILAR,true));
    }
    @Test void revocationPreventsQueuedWorkFromStarting()throws Exception{
        session("one");
        var ready=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        try{
            for(int i=0;i<2;i++)jobs.local("agent:"+principal,job->{ready.countDown();if(!release.await(4,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("Test release timed out");return Profiles.JSON.createObjectNode();},()->{});
            assertTrue(ready.await(2,java.util.concurrent.TimeUnit.SECONDS));
            var proposed=approvals.request(principal,"one",request("CREATE TABLE PUBLIC.REVOKED_QA(ID INT)"));
            var approved=approvals.decide("human",proposed.path("id").asText(),ReusableApprovals.SIMILAR,true);
            approvals.reusable.change(principal,approved.path("matchedPolicy").path("id").asText(),null);
            release.countDown();var done=await(proposed.path("id").asText());assertEquals("failed",done.path("state").asText(),done.toString());
            try(var c=connections.open(connection);var rows=c.getMetaData().getTables(c.getCatalog(),"PUBLIC","REVOKED_QA",null)){assertFalse(rows.next());}
        }finally{release.countDown();}
    }
}
