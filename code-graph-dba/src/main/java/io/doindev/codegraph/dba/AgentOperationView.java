package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** MCP sees operation lifecycle, while the browser/native reviewer retains the full review record. */
final class AgentOperationView {
    private static final Set<String> PENDING=Set.of("awaiting_approval","awaiting_browser","approved");
    private static final List<String> REVIEW_FIELDS=List.of("matchedPolicy","authorizationReason","authorizationOutcome",
        "approvalChannel","reviewAvailable","deliveryStatus","effectiveApprovalBehavior","approvalChoices");
    static JsonNode of(JsonNode value){
        if(!(value instanceof ObjectNode original))return value;
        if(!original.hasNonNull("approvalId")){
            ObjectNode out=original.deepCopy();out.remove(REVIEW_FIELDS);return out;
        }
        String id=original.path("approvalId").asText(),state=original.path("state").asText();
        ObjectNode out=Profiles.JSON.createObjectNode().put("id",id).put("operationId",id);
        if(PENDING.contains(state))return out.put("state","queued").put("pollAfterMillis",1000);
        out.put("state",state);
        switch(state){
            case "rejected","denied" -> failure(out,"approval_denied","The user denied this operation.");
            case "expired" -> {
                String code=original.path("failureCode").asText("approval_timeout");
                failure(out,code,code.equals("approval_timeout")?"Timed out waiting for user approval; nothing was executed.":"The requesting session or access is no longer valid; nothing was executed.");
            }
            case "failed_to_submit" -> {
                for(String key:List.of("jobId","job"))if(original.has(key))out.set(key,original.get(key).deepCopy());
                failure(out,"submission_failed","Submission failed; inspect any returned job and database state before retrying.");
            }
            case "result_expired" -> failure(out,"result_expired","The retained result is no longer available; the operation was not replayed.");
            default -> {
                for(String key:List.of("jobId","job","result","pairId","jobExpired"))if(original.has(key))out.set(key,original.get(key).deepCopy());
                if(state.equals("submitted"))out.put("state",original.path("job").path("state").asText("running"));
                if(out.path("job") instanceof ObjectNode job)job.remove(REVIEW_FIELDS);
                if(state.equals("failed")&&!out.has("job"))failure(out,"operation_failed","The operation failed. Inspect the application log for details.");
            }
        }
        return out;
    }
    private static void failure(ObjectNode out,String code,String message){out.put("state","failed");out.putObject("error").put("code",code).put("message",message);}
}
