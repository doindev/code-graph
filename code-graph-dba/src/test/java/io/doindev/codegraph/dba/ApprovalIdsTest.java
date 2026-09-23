package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalIdsTest {
    @Test void acceptsCanonicalLegacyAndMatchingAliases() {
        String id=UUID.randomUUID().toString();
        assertEquals(id,ApprovalIds.resolve(Profiles.JSON.createObjectNode().put("operationId",id)));
        assertEquals(id,ApprovalIds.resolve(Profiles.JSON.createObjectNode().put("approvalId",id)));
        assertEquals(id,ApprovalIds.resolve(Profiles.JSON.createObjectNode().put("requestId",id)));
        assertEquals(id,ApprovalIds.resolve(Profiles.JSON.createObjectNode().put("approvalId",id).put("requestId",id)));
    }
    @Test void rejectsConflictingMissingNullOrOversizedIdentifiers() {
        assertThrows(IllegalArgumentException.class,()->ApprovalIds.resolve(Profiles.JSON.createObjectNode()));
        assertThrows(IllegalArgumentException.class,()->ApprovalIds.resolve(Profiles.JSON.createObjectNode().putNull("approvalId")));
        assertThrows(IllegalArgumentException.class,()->ApprovalIds.resolve(Profiles.JSON.createObjectNode().put("approvalId","x".repeat(37))));
        assertThrows(IllegalArgumentException.class,()->ApprovalIds.resolve(Profiles.JSON.createObjectNode()
                .put("approvalId",UUID.randomUUID().toString()).put("requestId",UUID.randomUUID().toString())));
    }
}
