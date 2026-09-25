package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class OracleDataPumpHistoryTest {
 @TempDir Path directory;
 @Test void retainsObservedCompletionAcrossRestartWithoutInferringSuccess()throws Exception{
  var status=Profiles.JSON.createObjectNode().put("owner","OWNER").put("name","JOB").put("available",true).put("state","COMPLETED").put("guid","job1").put("observedAt",1);status.putObject("target").put("databaseUniqueName","DB").put("containerId","3");
  new OracleDataPumpHistory(directory).observe("profile",status);var missing=status.deepCopy().put("available",false).put("state","UNKNOWN").put("observedAt",2);var restored=new OracleDataPumpHistory(directory).observe("profile",missing);
  assertEquals("UNKNOWN",restored.path("state").asText());assertEquals("COMPLETED",restored.path("previousObservation").path("state").asText());assertFalse(restored.path("available").asBoolean());
  var different=status.deepCopy().put("available",false);different.withObject("target").put("containerId","4");assertFalse(new OracleDataPumpHistory(directory).observe("profile",different).has("previousObservation"));
  assertFalse(new OracleDataPumpHistory(directory).observe("different-profile",status.deepCopy().put("available",false)).has("previousObservation"));
 }
 @Test void boundsHistoryAndRejectsPathTraversal(){
  for(String name:java.util.List.of("../dump.dmp","/dump.dmp","C:/dump.dmp","dump%U.dmp","dump.dmp; DROP"))assertThrows(IllegalArgumentException.class,()->OracleDataPump.fileName(name));
  assertEquals("dump-1.dmp",OracleDataPump.fileName("dump-1.dmp"));
 }
}
