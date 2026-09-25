package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Bounded snapshot observations and pure comparison. Neither generated nor observed SQL is executed. */
final class SchemaSnapshots {
    static ObjectNode capture(QueryJobs.Job job,Connection connection,ObjectNode profile,ObjectNode scope)throws Exception{
        ObjectNode out=Profiles.JSON.createObjectNode().put("format","codegraph-schema-v1").put("snapshotId",job.id).put("capturedAt",System.currentTimeMillis());
        out.set("target",ApprovalScope.display(scope));
        ArrayNode objects=out.putArray("objects");var metadata=connection.getMetaData();
        out.put("engine",ExplainPlans.engine(metadata));
        out.putObject("version").put("product",metadata.getDatabaseProductName()).put("server",metadata.getDatabaseProductVersion()).put("driver",metadata.getDriverVersion()).put("major",metadata.getDatabaseMajorVersion());
        if(OracleDialect.isOracle(connection)){var target=OracleDialect.target(job,connection,job.remainingSeconds());if(!target.matches(scope.path("database").asText()))throw new IllegalArgumentException("Oracle schema capture targets a different service/PDB");ObjectNode identity=target.json();identity.remove("schema");identity.put("owner",scope.path("schema").asText());out.set("resolvedTarget",identity);}
        long[] bytes={0};boolean[] stopped={false};int maxBytes=Math.min(512*1024,job.byteLimit/2);
        var limits=new CatalogScanner.Limits(Math.min(100,job.rowLimit),maxBytes,Math.min(100,job.rowLimit),32768);
        CatalogScanner scanner=new CatalogScanner(connection,profile,scope,(key,value)->{
            if(!key.startsWith("o/"))return;
            bytes[0]+=value.length;if(bytes[0]>maxBytes)throw new CatalogScanner.CaptureLimit("Snapshot exceeds retained byte allowance");
            try{objects.add(Profiles.JSON.readTree(value));}catch(Exception invalid){throw new IllegalStateException("Invalid internal schema record");}
        },()->job.cancelled,limits,statement->job.statement=statement,job::remainingSeconds);
        try{out.set("coverage",scanner.scan());}catch(CatalogScanner.CaptureLimit limit){stopped[0]=true;out.putObject("coverage").put("inventoryComplete",false).put("reason",limit.getMessage());}
        out.put("truncated",stopped[0]).put("bytes",bytes[0]);
        // Accessible JDBC inventories do not prove absence of inaccessible/vendor-specific objects.
        out.put("inventoryComplete",false).put("absenceProvesRemoval",false);
        var fingerprints=new TreeMap<String,String>();for(JsonNode object:objects)fingerprints.put(object.path("id").asText(),object.path("objectHash").asText());
        out.put("fingerprint",CatalogScanner.hash(fingerprints.toString()+(out.has("resolvedTarget")?"\n"+out.path("resolvedTarget"):"")));
        out.put("consistency","Metadata observation, not a schema lock; concurrent DDL and privilege filtering can affect coverage");
        return out;
    }
    static ObjectNode compare(JsonNode left,JsonNode right,int limit){
        for(JsonNode value:List.of(left,right))if(!value.path("format").asText().equals("codegraph-schema-v1"))throw new IllegalArgumentException("Expected retained schema snapshots");
        if(limit<1||limit>100)throw new IllegalArgumentException("Comparison limit must be 1..100");
        boolean cross=!left.path("engine").equals(right.path("engine"));
        var out=Profiles.JSON.createObjectNode().put("state","complete").put("crossEngine",cross).put("inventoryComplete",false);
        out.putObject("target").set("left",left.path("target"));out.withObject("target").set("right",right.path("target"));
        out.putObject("sources").put("leftSnapshot",left.path("snapshotId").asText()).put("rightSnapshot",right.path("snapshotId").asText()).put("leftFingerprint",left.path("fingerprint").asText()).put("rightFingerprint",right.path("fingerprint").asText());
        var a=objects(left);var b=objects(right);var identities=new TreeSet<>(a.keySet());identities.addAll(b.keySet());
        ArrayNode differences=out.putArray("differences");int count=0;
        for(String identity:identities){
            JsonNode old=a.get(identity),next=b.get(identity);ObjectNode difference=null;
            if(old==null||next==null){difference=Profiles.JSON.createObjectNode().put("kind","observed_on_one_side_only").put("side",old==null?"right":"left").put("reason","Incomplete inventories cannot establish creation or removal");}
            else{
                var before=semantic(old);var after=semantic(next);
                if(!before.equals(after)){
                    difference=Profiles.JSON.createObjectNode().put("kind",cross?"compatibility_difference":"structural_difference");
                    var fields=difference.putArray("fields");
                    var names=new TreeSet<String>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);
                    for(String field:names)if(!before.path(field).equals(after.path(field)))fields.addObject().put("field",field).set("before",old.path(field));
                    for(JsonNode field:fields)((ObjectNode)field).set("after",next.path(field.path("field").asText()));
                }else if(!old.path("ddl").equals(next.path("ddl")))difference=Profiles.JSON.createObjectNode().put("kind","definition_text_difference").put("reason","Structured properties agree; definition formatting or unmodeled semantics may differ");
            }
            if(difference!=null){count++;if(differences.size()<limit){JsonNode object=old==null?next:old;difference.put("schema",object.path("schema").asText()).put("name",object.path("name").asText()).put("objectKind",object.path("kind").asText());differences.add(difference);}}
        }
        out.put("totalDifferences",count).put("truncated",count>limit).put("migrationGenerated",false);
        out.putArray("limitations").add("Definition-text differences are not asserted to be semantic or formatting-only without a verified vendor normalizer")
                .add("No rename is inferred; inaccessible and omitted objects cannot establish removals")
                .add(cross?"Cross-engine types and expressions require compatibility review; no automatic cross-engine migration":"Defaults, names and expressions are compared exactly; vendor-equivalent spellings may differ");
        return out;
    }
    private static Map<String,JsonNode> objects(JsonNode snapshot){
        var out=new TreeMap<String,JsonNode>();if(snapshot.path("objects").size()>100)throw new IllegalArgumentException("Snapshot object limit exceeded");
        for(JsonNode object:snapshot.path("objects"))out.put(object.path("schema").asText()+"\0"+object.path("kind").asText()+"\0"+object.path("name").asText()+"\0"+object.path("signature").asText(),object);return out;
    }
    private static ObjectNode semantic(JsonNode object){
        ObjectNode out=Profiles.JSON.createObjectNode();for(String key:List.of("columns","indexes","primaryKeys","foreignKeys","privileges","signature","jdbcType","nativeDeclared","nativeType","ttlClass","nativeColumns","nativeKeys","nativeIndexes","constraints"))if(object.has(key))out.put(key,CatalogScanner.stable(object.path(key)));return out;
    }
}
