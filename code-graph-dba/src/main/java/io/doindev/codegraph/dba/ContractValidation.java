package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pure bounded comparison of static code mappings against an authorized schema observation. */
final class ContractValidation {
    static ObjectNode validate(JsonNode snapshot,JsonNode mappings,int limit){
        if(!snapshot.path("format").asText().equals("codegraph-schema-v1"))throw new IllegalArgumentException("Expected a retained schema snapshot");
        if(!mappings.isArray()||mappings.size()>100||limit<1||limit>100)throw new IllegalArgumentException("Contract validation bounds exceeded");
        if(java.util.Set.of("mongodb","redis").contains(snapshot.path("engine").asText()))return NativeContracts.validate(snapshot,mappings,limit);
        Map<String,JsonNode> objects=new HashMap<>();for(JsonNode object:snapshot.path("objects")){
            String identity=key(object.path("schema").asText(),object.path("name").asText());JsonNode current=objects.get(identity);
            if(current==null||!current.path("columns").isArray()&&object.path("columns").isArray())objects.put(identity,object);
        }
        ObjectNode out=Profiles.JSON.createObjectNode().put("format","codegraph-contract-validation-v1").put("state","complete")
                .put("schemaSnapshotId",snapshot.path("snapshotId").asText()).put("schemaFingerprint",snapshot.path("fingerprint").asText())
                .put("inventoryComplete",false).put("mappingCount",mappings.size());
        ArrayNode issues=out.putArray("issues");int total=0;long generation=-1;
        for(JsonNode mapping:mappings){
            if(generation<0)generation=mapping.path("generation").asLong();else if(generation!=mapping.path("generation").asLong())throw new IllegalArgumentException("Code mappings span graph generations; retry");
            String schema=mapping.path("schema").asText(snapshot.path("target").path("schema").asText()),table=mapping.path("table").asText(),column=mapping.path("column").asText();
            if(table.isBlank())continue;JsonNode object=objects.get(key(schema,table));String kind=null,detail=null;
            if(object==null){kind="unresolved_object";detail="No matching object was observed in the bounded authorized schema snapshot";}
            else if(!column.isBlank()){
                JsonNode matched=null;for(JsonNode candidate:object.path("columns")){String name=candidate.path("name").asText(candidate.path("COLUMN_NAME").asText());if(name.equalsIgnoreCase(column)){matched=candidate;break;}}
                if(matched==null){kind="unresolved_column";detail="No matching column was observed on the mapped object";}
                else{String expected=mapping.path("databaseType").asText(),actual=matched.path("type").asText();if(actual.isBlank())actual=matched.path("typeName").asText();if(actual.isBlank())actual=matched.path("TYPE_NAME").asText();if(!expected.isBlank()&&!actual.isBlank()&&!compatible(expected,actual)){kind="type_mismatch";detail="Static mapped type differs from the observed database type";}}
            }
            if(kind!=null){total++;if(issues.size()<limit){ObjectNode issue=issues.addObject().put("kind",kind).put("schema",schema).put("table",table).put("column",column).put("detail",detail).put("confidence",mapping.path("confidence").asDouble());issue.set("evidence",mapping.deepCopy());}}
        }
        out.put("graphGeneration",generation).put("totalIssues",total).put("truncated",total>limit);
        out.putArray("limitations").add("Dynamic SQL, reflection and custom naming strategies may be unresolved")
                .add("A missing observation is not proof that an object is absent because catalog coverage and privileges may be incomplete")
                .add("Type compatibility is conservative and does not model every vendor coercion")
                .add("Validation recommends review; it does not execute tests or application code");
        return out;
    }
    private static String key(String schema,String name){return schema.toLowerCase(Locale.ROOT)+"\u0000"+name.toLowerCase(Locale.ROOT);}
    private static boolean compatible(String expected,String actual){String a=expected.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]",""),b=actual.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");return a.equals(b)||a.startsWith(b)||b.startsWith(a)||a.matches("string|text")&&b.matches("varchar|character.*|text")||a.matches("int|integer|long")&&b.matches("int.*|integer|bigint|smallint");}
    private ContractValidation(){}
}
