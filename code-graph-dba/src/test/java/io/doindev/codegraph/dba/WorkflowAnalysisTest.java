package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowAnalysisTest {
    @Test void contractValidationPreservesEvidenceAndNeverClaimsCompleteAbsence(){
        var snapshot=Profiles.JSON.createObjectNode().put("format","codegraph-schema-v1").put("snapshotId","s").put("fingerprint","f");
        snapshot.putObject("target").put("schema","public");var objects=snapshot.putArray("objects");
        objects.addObject().put("schema","public").put("name","users").putArray("columns").addObject().put("name","id").put("typeName","INTEGER");
        var mappings=Profiles.JSON.createArrayNode();
        mappings.addObject().put("id","m1").put("generation",7).put("schema","public").put("table","users").put("column","missing").put("path","A.java").put("confidence",.9);
        mappings.addObject().put("id","m2").put("generation",7).put("schema","public").put("table","orders").put("path","B.java").put("confidence",.6);
        var result=ContractValidation.validate(snapshot,mappings,10);
        assertEquals(2,result.path("totalIssues").asInt());assertFalse(result.path("inventoryComplete").asBoolean());
        assertEquals("A.java",result.path("issues").get(0).path("evidence").path("path").asText());
    }

    @Test void planAnalysisIsStructuralAndCrossVendorCostsAreNeverCompared(){
        var left=Profiles.JSON.createObjectNode().put("estimated",true).put("engine","postgresql");left.putObject("raw").put("Plan","x");
        left.putArray("nodes").addObject().put("operator","Seq Scan").putArray("children");
        var right=Profiles.JSON.createObjectNode().put("estimated",true).put("engine","mysql");right.putObject("raw").put("query_block","x");
        right.putArray("nodes").addObject().put("operator","Index lookup").putArray("children");
        QueryPlanAnalysis.attach(left);assertTrue(left.path("analysisIncluded").asBoolean());
        var compared=QueryPlanAnalysis.compare("a",left,"b",right,10);
        assertTrue(compared.path("crossEngine").asBoolean());assertFalse(compared.path("costsCompared").asBoolean());
        assertEquals(2,compared.path("totalDifferences").asInt());assertTrue(compared.path("sources").path("rawEvidenceRetainedInSourceJobs").asBoolean());
    }

    @Test void contractValidationAcceptsCanonicalCatalogColumnType(){
        var snapshot=Profiles.JSON.createObjectNode().put("format","codegraph-schema-v1").put("snapshotId","s").put("fingerprint","f");
        snapshot.putObject("target").put("schema","public");var objects=snapshot.putArray("objects");objects.addObject().put("schema","public").put("name","items")
                .putArray("columns").addObject().put("name","state").put("type","VARCHAR");
        objects.addObject().put("schema","public").put("name","items").put("kind","type");
        var mappings=Profiles.JSON.createArrayNode();mappings.addObject().put("generation",1).put("schema","public").put("table","items")
                .put("column","state").put("databaseType","VARCHAR").put("confidence",1.0);
        assertEquals(0,ContractValidation.validate(snapshot,mappings,10).path("totalIssues").asInt());
    }
}
