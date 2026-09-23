package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;

/** Server-issued approval identity, distinct from a caller's idempotency requestId. */
final class ApprovalIds {
    private ApprovalIds() {}

    static String resolve(JsonNode input) {
        String id=null;
        for(String key:java.util.List.of("operationId","approvalId","requestId"))if(input.has(key)){
            String candidate=Profiles.text(input,key,36);
            if(id!=null&&!id.equals(candidate))throw new IllegalArgumentException("Conflicting operationId and legacy request aliases");
            id=candidate;
        }
        if(id==null)throw new IllegalArgumentException("operationId is required; use the server-returned operationId, not your submission requestId");
        return id;
    }
}
