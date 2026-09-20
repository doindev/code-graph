package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSchemaObservationsTest {
    NativeTarget target(){return new NativeTarget(DatabaseTransport.MONGODB,"b069f21e-d334-49df-903b-9fb8a1be77b4","Test","app","","standalone");}
    @Test void declarationsDoNotInferRequiredFieldsOrConvertSamplesToSchema()throws Exception{
        var fields=NativeSchemaObservations.declaredFields(Profiles.JSON.readTree("{\"$jsonSchema\":{\"required\":[\"name\"],\"properties\":{\"name\":{\"bsonType\":\"string\"},\"age\":{\"bsonType\":[\"int\",\"null\"]}}}}"));
        assertEquals(2,fields.size());assertTrue(fields.get(0).path("required").asBoolean());assertFalse(fields.get(1).path("required").asBoolean());
        assertEquals("declared_validator",fields.get(0).path("evidence").asText());assertTrue(fields.get(1).path("types").isArray());
    }
    @Test void retainedObjectsAreCappedAndVolatileObservationsDoNotChangeSchemaFingerprint(){
        var a=new NativeSchemaObservations.Builder(target(),"a",1,16384);var b=new NativeSchemaObservations.Builder(target(),"b",1,16384);
        var object=Profiles.JSON.createObjectNode().put("name","key").put("kind","key").put("nativeType","string").put("observedTtlMillis",1000);
        assertTrue(a.add(object.deepCopy()));assertTrue(b.add(object.deepCopy().put("observedTtlMillis",900)));
        assertEquals(a.finish().path("fingerprint"),b.finish().path("fingerprint"));
        assertFalse(a.add(object.deepCopy().put("name","other")));assertTrue(a.finish().path("truncated").asBoolean());assertFalse(a.finish().path("inventoryComplete").asBoolean());
        var bounded=new NativeSchemaObservations.Builder(target(),"c",100,16384);assertFalse(bounded.add(object.deepCopy().put("ddl","x".repeat(20000))));
    }
    @Test void nativeDefinitionChangesAreComparedButNeverAssertRemoval(){
        var a=new NativeSchemaObservations.Builder(target(),"a",100,65536);var b=new NativeSchemaObservations.Builder(target(),"b",100,65536);
        var object=Profiles.JSON.createObjectNode().put("name","users").put("kind","collection").put("schema","");object.putObject("nativeDeclared").put("validationLevel","strict");a.add(object.deepCopy());
        object.withObject("nativeDeclared").put("validationLevel","moderate");b.add(object);
        var differences=SchemaSnapshots.compare(a.finish(),b.finish(),10);assertEquals(1,differences.path("totalDifferences").asInt());assertFalse(differences.path("inventoryComplete").asBoolean());
    }
}
