package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Observational contracts: native names are case-sensitive, samples never establish a schema. */
final class NativeContracts {
    static ObjectNode validate(JsonNode snapshot,JsonNode mappings,int limit){
        String engine=snapshot.path("engine").asText();var issues=Profiles.JSON.createArrayNode();int total=0,checked=0,ignored=0;long generation=-1;
        for(JsonNode mapping:mappings){
            if(generation<0)generation=mapping.path("generation").asLong();else if(generation!=mapping.path("generation").asLong())throw new IllegalArgumentException("Code mappings span graph generations; retry");
            if(!mapping.path("transport").asText().equals(engine)){ignored++;continue;}
            checked++;String name=mapping.path("table").asText(),field=mapping.path("column").asText(),kind=null,detail=null;JsonNode object=null;
            if(engine.equals("mongodb")){
                for(JsonNode value:snapshot.path("objects"))if(value.path("name").asText().equals(name)){object=value;break;}
                if(object==null){kind="unresolved_collection";detail="Collection was not observed; bounded coverage or privileges may explain this";}
                else if(!field.isBlank()){
                    JsonNode declared=null,sampled=null;for(JsonNode column:object.path("columns"))if(column.path("name").asText().equals(field)){declared=column;break;}
                    for(JsonNode column:object.path("fieldObservations"))if(column.path("name").asText().equals(field)){sampled=column;break;}
                    String expected=mapping.path("databaseType").asText();
                    if(declared==null){kind=sampled==null?"unresolved_field":"sampled_field_only";detail=sampled==null?"No declared or sampled field observation; this does not establish absence":"Field was sampled, not constrained by a declared validator";}
                    else if(!expected.isBlank()&&!accepts(declared,expected)){kind="declared_type_mismatch";detail="Mapped BSON type differs from the declared validator; no application values were inspected";}
                    else if(mapping.path("required").asText().equals("true")&&!declared.path("required").asBoolean()){kind="required_not_declared";detail="Code requires this field but the observed validator does not require its presence";}
                }
            }else{
                List<JsonNode> observed=new ArrayList<>();for(JsonNode value:snapshot.path("objects"))if(value.path("namespace").asText().equals(name))observed.add(value);
                if(observed.isEmpty()){kind="unresolved_namespace";detail="No key in this namespace was observed; SCAN is not a complete or atomic inventory";}
                else{
                    String expected=mapping.path("databaseType").asText(),ttl=mapping.path("ttlPolicy").asText();
                    if(!expected.isBlank()&&observed.stream().anyMatch(key->!key.path("nativeType").asText().equals(expected))){kind="observed_key_type_mismatch";detail="At least one observed key has another type; namespace patterns are application conventions, not Redis constraints";}
                    else if(!ttl.isBlank()&&observed.stream().anyMatch(key->!key.path("ttlClass").asText().equals(ttl))){kind="observed_ttl_mismatch";detail="An observed key differs from the declared TTL policy; TTL and existence may change concurrently";}
                }
            }
            if(kind!=null){total++;if(issues.size()<limit){var issue=issues.addObject().put("kind",kind).put("table",name).put("column",field).put("detail",detail).put("confidence",mapping.path("confidence").asDouble());issue.set("evidence",mapping.deepCopy());}}
        }
        ObjectNode out=Profiles.JSON.createObjectNode().put("format","codegraph-contract-validation-v1").put("state","complete").put("engine",engine)
            .put("schemaSnapshotId",snapshot.path("snapshotId").asText()).put("schemaFingerprint",snapshot.path("fingerprint").asText()).put("graphGeneration",generation)
            .put("inventoryComplete",false).put("mappingCount",mappings.size()).put("checkedMappings",checked).put("ignoredOtherTransportMappings",ignored).put("totalIssues",total).put("truncated",total>limit);
        out.set("issues",issues);out.putArray("limitations").add("Case-sensitive declared/native namespace comparisons only; dynamic names, nested document paths and naming conventions remain unresolved")
            .add("Sampled BSON types and Redis type/TTL observations are not declared schemas or proof of absence")
            .add("Opening validation performs no data reads; only the authorized retained snapshot is compared");return out;
    }
    private static boolean accepts(JsonNode declared,String expected){
        if(declared.path("type").asText().equals(expected))return true;
        for(JsonNode type:declared.path("types"))if(type.asText().equals(expected))return true;
        return !declared.has("type")&&!declared.has("types");
    }
    private NativeContracts(){}
}
