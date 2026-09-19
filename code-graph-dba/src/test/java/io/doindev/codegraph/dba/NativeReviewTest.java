package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class NativeReviewTest {
    @TempDir Path root;
    @Test void mutationReviewsAreOwnedSingleUseBoundedAndRevisionChecked()throws Exception {
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,5),owner->true);
            var nativeOps=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Review")
                    .put("templateId","redis-native").put("url","redis://127.0.0.1:1").put("readOnly",false));
            String id=profile.path("id").asText();
            var input=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Review").put("database","0");
            input.putArray("command").add("SET").add("example").add("value");
            var review=nativeOps.prepareBrowser("human",input);String reviewId=review.path("id").asText();
            assertTrue(review.path("mutation").asBoolean());assertEquals(2L<<20,jobs.telemetry().path("reservedBytes").asLong());
            assertEquals(0,jobs.telemetry().path("activeJobs").asInt());
            assertThrows(SecurityException.class,()->nativeOps.applyBrowser("stranger",reviewId));
            assertThrows(SecurityException.class,()->nativeOps.discardBrowser("stranger",reviewId));
            var altered=input.deepCopy();altered.withArray("command").set(2,Profiles.JSON.getNodeFactory().textNode("different"));
            assertThrows(IllegalArgumentException.class,()->nativeOps.validate(review,altered));
            profiles.put(id,Profiles.JSON.createObjectNode().put("name","Review").put("readOnly",true));
            assertThrows(IllegalArgumentException.class,()->nativeOps.applyBrowser("human",reviewId));
            nativeOps.discardBrowser("human",reviewId);
            assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
            assertThrows(SecurityException.class,()->nativeOps.applyBrowser("human",reviewId));
            assertThrows(IllegalArgumentException.class,()->nativeOps.prepareBrowser("human",input));
        }
    }
    @Test void mutationValidationRejectsUnboundedDeletesContextOverridesAndBadShapes()throws Exception{
        String id=java.util.UUID.randomUUID().toString();
        var target=new NativeTarget(DatabaseTransport.MONGODB,id,"Test","app","items","standalone");
        for(String invalid:new String[]{
                "{\"delete\":\"items\",\"deletes\":[{\"q\":{},\"limit\":1}]}",
                "{\"delete\":\"items\",\"deletes\":[{\"q\":{\"id\":1},\"limit\":0}]}",
                "{\"update\":\"items\",\"updates\":[{\"q\":{\"id\":1},\"u\":{\"$set\":{\"x\":1}},\"multi\":true}]}",
                "{\"insert\":\"other\",\"documents\":[{\"x\":1}]}",
                "{\"insert\":\"items\",\"documents\":[{\"x\":1}],\"writeConcern\":{\"w\":0}}",
                "{\"insert\":\"items\",\"documents\":[{\"x\":1}],\"$db\":\"other\"}"}){
            JsonNode command=Profiles.JSON.readTree(invalid);
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target,command),invalid);
        }
        NativeMutations.validate(target,Profiles.JSON.readTree("{\"insert\":\"items\",\"documents\":[{\"x\":1}]}"));
        assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,
                Profiles.JSON.readTree("{\"aggregate\":\"items\",\"pipeline\":[{\"$out\":\"other\"},42]}")));
    }
}
