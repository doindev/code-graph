package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalPresentationTest {
    static ObjectNode request(String vendor,String sql,boolean sessionAlive){
        var scope=ReusableOperationTest.scope(vendor);
        var operation=ReusableOperation.classify(sql,scope);
        var request=Profiles.JSON.createObjectNode();
        request.set("operation",operation.json());request.set("approvalChoices",ApprovalQueue.choices(operation,sessionAlive));
        return request;
    }
    @Test void eligibleSelectOffersEveryReusableChoiceWithoutAnUnavailableMessage(){
        var request=request("postgresql","SELECT TRUE",true);
        assertEquals("",ApprovalPresentation.reusableUnavailable(request));
        for(var choice:request.path("approvalChoices"))assertTrue(choice.path("enabled").asBoolean());
        assertFalse(ApprovalPresentation.html(request).contains("Only Allow once"));
    }
    @Test void missingSessionExplainsWhyOtherwiseEligibleSelectCannotBeReused(){
        var request=request("postgresql","SELECT TRUE",false);
        assertTrue(request.path("operation").path("eligible").asBoolean());
        String message=ApprovalPresentation.reusableUnavailable(request);
        assertEquals("Only Allow once is available. A live validated MCP session is required",message);
        assertTrue(ApprovalPresentation.html(request).contains(message));
        assertTrue(request.path("approvalChoices").get(0).path("enabled").asBoolean());
        for(int i=1;i<4;i++)assertFalse(request.path("approvalChoices").get(i).path("enabled").asBoolean());
    }
    @Test void engineAndSqlRestrictionsRemainVisibleAndEnforced(){
        for(var request:java.util.List.of(request("h2","SELECT TRUE",true),request("postgresql","SELECT evil()",true),request("oracle","SELECT 1",true))){
            assertFalse(request.path("operation").path("eligible").asBoolean());
            String reason=request.path("operation").path("reason").asText();
            assertFalse(reason.isBlank());
            assertEquals("Only Allow once is available. "+reason,ApprovalPresentation.reusableUnavailable(request));
            for(int i=1;i<4;i++)assertFalse(request.path("approvalChoices").get(i).path("enabled").asBoolean());
        }
    }
    @Test void reasonsAreDeduplicatedAndEscapedInDetails(){
        var request=request("h2","SELECT 1",true);
        for(var choice:request.path("approvalChoices"))((ObjectNode)choice).put("reason","<custom> & reason");
        assertEquals("Only Allow once is available. <custom> & reason",ApprovalPresentation.reusableUnavailable(request));
        String html=ApprovalPresentation.html(request);
        assertTrue(html.contains("&lt;custom&gt; &amp; reason"));assertFalse(html.contains("<custom>"));
    }
    @Test void legacyAndPartiallyAvailableChoicesDoNotClaimOnlyAllowOnce(){
        assertEquals("",ApprovalPresentation.reusableUnavailable(Profiles.JSON.createObjectNode()));
        var request=request("h2","SELECT 1",true);
        ((ObjectNode)request.path("approvalChoices").get(1)).put("enabled",true);
        assertEquals("",ApprovalPresentation.reusableUnavailable(request));
    }
}
