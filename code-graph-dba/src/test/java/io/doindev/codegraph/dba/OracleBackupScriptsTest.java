package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleBackupScriptsTest {
 private final OracleDialect.Target pdb=new OracleDialect.Target("PROD","PROD","APP_PDB","3","app.example","ACCOUNT","ACCOUNT");
 @Test void bindsToResolvedContainerWithoutExecutionOrCredentials(){
  var input=Profiles.JSON.createObjectNode().put("directory","/srv/backups").put("archiveLogs",true);var result=OracleBackupScripts.generate(pdb,input);String script=result.path("script").asText();
  assertTrue(result.path("manualOnly").asBoolean());assertTrue(script.contains("PLUGGABLE DATABASE "+OracleDialect.identifier("APP_PDB")));assertTrue(script.contains("FORMAT '/srv/backups/%d_%T_%U.bkp'"));assertTrue(script.contains("BACKUP ARCHIVELOG ALL"));assertFalse(script.contains("CONNECT TARGET"));assertFalse(script.contains("DELETE"));
  var root=new OracleDialect.Target("PROD","PROD","CDB$ROOT","1","prod","ACCOUNT","ACCOUNT");assertTrue(OracleBackupScripts.generate(root,input.put("mode","validate")).path("script").asText().stripTrailing().endsWith("VALIDATE CHECK LOGICAL DATABASE;"));assertTrue(OracleBackupScripts.generate(pdb,input.put("mode","restore_validate")).path("script").asText().stripTrailing().endsWith("RESTORE PLUGGABLE DATABASE "+OracleDialect.identifier("APP_PDB")+" VALIDATE;"));
 }
 @Test void validatesPathsAndRmanTokens(){
  for(String path:java.util.List.of("","relative","/tmp/'; EXIT; #","/tmp/%U","/tmp/"+(char)10))assertThrows(IllegalArgumentException.class,()->OracleBackupScripts.generate(pdb,Profiles.JSON.createObjectNode().put("directory",path)));
  String win="C:"+(char)92+"backups";assertTrue(OracleBackupScripts.generate(pdb,Profiles.JSON.createObjectNode().put("directory",win)).path("script").asText().contains(win+(char)92+"%d_%T_%U.bkp"));
 }
}
