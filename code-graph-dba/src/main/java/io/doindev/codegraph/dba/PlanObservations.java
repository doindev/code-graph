package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

/** Bounded observations anchored to actual EXPLAIN fields, not index recommendations. */
final class PlanObservations {
    static void attach(ObjectNode result){
        result.put("executed",false).put("analysis","Estimated plan only; costs are planner estimates, not measured milliseconds. A scan alone does not prove an index is needed.");
        ArrayNode observations=result.putArray("observations");
        if(result.path("cellsTruncated").asBoolean()||result.path("truncated").asBoolean()){
            result.put("observationsUnavailable","Plan was truncated; refine the query before interpreting it.");return;
        }
        try{String text=result.path("rows").get(0).get(0).asText();if(text.length()>8192)return;JsonNode plan=Profiles.JSON.readTree(text).get(0).path("Plan");visit(plan,observations,0);}
        catch(Exception ignored){result.put("observationsUnavailable","Plan format is unsupported");}
    }
    private static void visit(JsonNode plan,ArrayNode out,int depth){
        if(depth>24||out.size()>=24||!plan.isObject())return;
        ObjectNode evidence=out.addObject().put("nodeType",plan.path("Node Type").asText());
        for(String key:new String[]{"Relation Name","Schema","Index Name","Plan Rows","Startup Cost","Total Cost","Join Type"})if(plan.has(key))evidence.set(key,plan.get(key));
        for(JsonNode child:plan.path("Plans"))visit(child,out,depth+1);
    }
}
