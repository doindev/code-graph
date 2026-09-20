package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;

/** Server-issued approval identity, distinct from a caller's idempotency requestId. */
final class ApprovalIds {
    private ApprovalIds() {}

    static String resolve(JsonNode input) {
        String canonical=input.has("approvalId")?Profiles.text(input,"approvalId",36):null;
        String legacy=input.has("requestId")?Profiles.text(input,"requestId",36):null;
        if(canonical==null&&legacy==null)
            throw new IllegalArgumentException("approvalId is required; use the server-returned approvalId, not your submission requestId");
        if(canonical!=null&&legacy!=null&&!canonical.equals(legacy))
            throw new IllegalArgumentException("Conflicting approvalId and deprecated requestId alias");
        return canonical==null?legacy:canonical;
    }
}
