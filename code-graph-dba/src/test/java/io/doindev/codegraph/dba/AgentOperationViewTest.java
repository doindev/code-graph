package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentOperationViewTest {
    @Test void pendingReviewAndPreparationStayPrivateButJobResultsRemainIntact(){
        var raw=Profiles.JSON.createObjectNode().put("id","server-id").put("approvalId","server-id").put("approvalChannel","desktop").put("detail","Ask the user");
        raw.putObject("job").putObject("result").put("receipt","browser-secret");
        for(String state:List.of("awaiting_approval","awaiting_browser","approved")){
            raw.put("state",state);var view=AgentOperationView.of(raw);
            assertEquals("queued",view.path("state").asText());assertEquals("server-id",view.path("operationId").asText());
            assertEquals(4,view.size());assertFalse(view.toString().contains("approval"));assertFalse(view.has("job"));
        }
        raw.put("state","complete");raw.withObject("job").putObject("result").putArray("rows").addObject().put("approvalId","a real database column");
        assertEquals(raw.path("job").path("result"),AgentOperationView.of(raw).path("job").path("result"));
        assertTrue(raw.has("approvalChannel"),"Projection must not mutate reviewer state");
    }
    @Test void denialAndTimeoutAreTerminalStructuredErrors(){
        var raw=Profiles.JSON.createObjectNode().put("approvalId","operation");
        for(String state:List.of("rejected","denied")){var view=AgentOperationView.of(raw.put("state",state));assertEquals("failed",view.path("state").asText());assertEquals("approval_denied",view.path("error").path("code").asText());}
        assertEquals("approval_timeout",AgentOperationView.of(raw.put("state","expired")).path("error").path("code").asText());
        assertEquals("access_revoked",AgentOperationView.of(raw.put("failureCode","access_revoked")).path("error").path("code").asText());
        assertEquals("cancelled",AgentOperationView.of(raw.put("state","cancelled")).path("state").asText());
    }
}
