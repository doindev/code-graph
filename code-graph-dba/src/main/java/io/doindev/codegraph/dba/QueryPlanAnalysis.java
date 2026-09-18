package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.TreeMap;

/** Evidence-first observations and comparisons; vendor cost units are never mixed. */
final class QueryPlanAnalysis {
    static void attach(ObjectNode plan){
        Map<String,Integer> operators=new TreeMap<>();walk(plan.path("nodes"),operators);
        ObjectNode analysis=plan.putObject("analysis").put("evidenceOnly",true).put("operatorCount",operators.values().stream().mapToInt(Integer::intValue).sum());
        ObjectNode counts=analysis.putObject("operators");operators.forEach(counts::put);
        ArrayNode observations=analysis.putArray("observations");
        for(var entry:operators.entrySet())observations.addObject().put("finding","operator_present").put("operator",entry.getKey()).put("count",entry.getValue()).put("evidence","Normalized estimated-plan node");
        observations.addObject().put("finding","no_runtime_measurements").put("evidence","The adapter requested an estimated plan and did not execute EXPLAIN ANALYZE");
        plan.put("analysisIncluded",true);
    }
    static ObjectNode compare(String leftId,JsonNode left,String rightId,JsonNode right,int limit){
        if(!left.path("estimated").asBoolean()||!right.path("estimated").asBoolean())throw new IllegalArgumentException("Use completed estimated-plan jobs");
        if(limit<1||limit>100)throw new IllegalArgumentException("limit must be 1..100");
        String leftEngine=left.path("engine").asText(),rightEngine=right.path("engine").asText();boolean cross=!leftEngine.equals(rightEngine);
        Map<String,Integer> a=new TreeMap<>(),b=new TreeMap<>();walk(left.path("nodes"),a);walk(right.path("nodes"),b);
        ObjectNode out=Profiles.JSON.createObjectNode().put("format","codegraph-plan-comparison-v1").put("state","complete").put("crossEngine",cross).put("costsCompared",false);
        out.putObject("sources").put("leftJobId",leftId).put("rightJobId",rightId).put("leftEngine",leftEngine).put("rightEngine",rightEngine)
                .put("leftRawHash",CatalogScanner.hash(CatalogScanner.stable(left.path("raw")))).put("rightRawHash",CatalogScanner.hash(CatalogScanner.stable(right.path("raw"))))
                .put("rawEvidenceRetainedInSourceJobs",true);
        ArrayNode differences=out.putArray("differences");var names=new java.util.TreeSet<String>();names.addAll(a.keySet());names.addAll(b.keySet());int total=0;
        for(String name:names)if(!a.getOrDefault(name,0).equals(b.getOrDefault(name,0))){total++;if(differences.size()<limit)differences.addObject().put("operator",name).put("leftCount",a.getOrDefault(name,0)).put("rightCount",b.getOrDefault(name,0)).put("evidence","Normalized operator occurrence count");}
        out.put("totalDifferences",total).put("truncated",total>limit);
        out.putArray("limitations").add("Raw vendor evidence remains available in the retained source jobs and is identified by a stable hash")
                .add(cross?"Vendor optimizer costs and operator names are not directly comparable; only normalized structural evidence is shown":"Vendor cost values are preserved in source evidence but not treated as measured latency")
                .add("No query was executed and no performance regression is asserted from a scan or cost estimate alone");
        return out;
    }
    private static void walk(JsonNode nodes,Map<String,Integer> operators){if(nodes.isArray())for(JsonNode node:nodes)walk(node,operators);else if(nodes.isObject()){String operator=nodes.path("operator").asText("Unknown");operators.merge(operator,1,Integer::sum);walk(nodes.path("children"),operators);}}
    private QueryPlanAnalysis(){}
}
