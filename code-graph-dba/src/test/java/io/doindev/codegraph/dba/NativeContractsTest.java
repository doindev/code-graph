package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeContractsTest {
    @Test void mongoNamesAreCaseSensitiveAndSamplesNeverDeclareTypes()throws Exception{
        var snapshot=Profiles.JSON.readTree("""
            {"format":"codegraph-schema-v1","engine":"mongodb","objects":[{"name":"People","columns":[{"name":"age","type":"int","evidence":"declared_validator"}],"fieldObservations":[{"name":"nickname","types":["string"]}]}]}
            """);
        var mappings=Profiles.JSON.readTree("""
            [{"transport":"mongodb","table":"People","column":"age","databaseType":"string","generation":4},{"transport":"mongodb","table":"People","column":"nickname","generation":4},{"transport":"mongodb","table":"people","generation":4},{"framework":"jpa","table":"People","generation":4}]
            """);
        var result=ContractValidation.validate(snapshot,mappings,100);assertEquals(3,result.path("totalIssues").asInt());assertEquals(1,result.path("ignoredOtherTransportMappings").asInt());
        assertEquals("declared_type_mismatch",result.path("issues").get(0).path("kind").asText());assertEquals("sampled_field_only",result.path("issues").get(1).path("kind").asText());assertEquals("unresolved_collection",result.path("issues").get(2).path("kind").asText());
    }
    @Test void redisChecksTypeAndTtlConventionsWithoutClaimingACompleteInventory()throws Exception{
        var snapshot=Profiles.JSON.readTree("""
            {"format":"codegraph-schema-v1","engine":"redis","objects":[{"namespace":"sessions","nativeType":"hash","ttlClass":"persistent"}]}
            """);
        var mappings=Profiles.JSON.readTree("""
            [{"transport":"redis","table":"sessions","databaseType":"hash","ttlPolicy":"expiring","generation":2},{"transport":"redis","table":"sessions","databaseType":"string","generation":2},{"transport":"redis","table":"missing","generation":2}]
            """);
        var result=ContractValidation.validate(snapshot,mappings,1);assertEquals(3,result.path("totalIssues").asInt());assertEquals(1,result.path("issues").size());assertTrue(result.path("truncated").asBoolean());assertFalse(result.path("inventoryComplete").asBoolean());assertEquals("observed_ttl_mismatch",result.path("issues").get(0).path("kind").asText());
    }
}
