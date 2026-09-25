package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;

/** Manual RMAN artifacts only. No path on the application host is read or written. */
final class OracleBackupScripts {
    static ObjectNode generate(OracleDialect.Target target,JsonNode input){
        String mode=input.path("mode").asText("backup");if(!Set.of("backup","validate","restore_validate").contains(mode))throw new IllegalArgumentException("Choose backup, validate or restore_validate");
        boolean pdb=!target.container().isBlank()&&!target.containerId().equals("0")&&!target.containerId().equals("1");
        String scope=pdb?"PLUGGABLE DATABASE "+name(target.container()):"DATABASE";
        String header="# Manual RMAN script for DB_UNIQUE_NAME "+comment(target.databaseUniqueName())+"\n# Observed container: "+comment(target.container())+"; service: "+comment(target.service())+"\n# Connect RMAN to "+(pdb?"the CDB root":"this database")+" using an explicitly authorized SYSBACKUP or SYSDBA account.\n# Review the target and prerequisites before execution. This application never executes RMAN.\n";
        String commands;
        if(mode.equals("backup")){
            String directory=input.path("directory").asText();if(directory.isEmpty()||directory.length()>1000||directory.contains("'")||directory.contains("\"")||directory.contains("%")||directory.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Supply a server backup directory without quotes, control characters, or RMAN substitution tokens");
            if(!directory.startsWith("/")&&!directory.matches("[A-Za-z]:[\\\\/].*")&&!directory.startsWith("\\\\"))throw new IllegalArgumentException("Use an absolute directory on the Oracle database host");
            String separator=directory.endsWith("/")||directory.endsWith("\\")?"":directory.contains("\\")?"\\":"/",prefix=directory+separator;
            header+="# Directory must already exist and be writable by Oracle. An online backup requires ARCHIVELOG mode.\n# The control-file backup belongs to the whole database/CDB. Retention and restore testing are managed manually.\n";
            commands="RUN {\n  ALLOCATE CHANNEL cg_disk DEVICE TYPE DISK;\n  BACKUP AS BACKUPSET "+scope+" FORMAT '"+prefix+"%d_%T_%U.bkp';\n";
            if(input.has("archiveLogs")&&!input.path("archiveLogs").isBoolean())throw new IllegalArgumentException("archiveLogs must be boolean");
            if(input.path("archiveLogs").asBoolean())commands+="  BACKUP ARCHIVELOG ALL NOT BACKED UP 1 TIMES FORMAT '"+prefix+"%d_arch_%T_%U.bkp';\n";
            commands+="  BACKUP CURRENT CONTROLFILE FORMAT '"+prefix+"%d_control_%T_%U.bkp';\n  RELEASE CHANNEL cg_disk;\n}\n";
        }else commands=mode.equals("validate")?"VALIDATE CHECK LOGICAL "+scope+";\n":"RESTORE "+scope+" VALIDATE;\n";
        return io.doindev.codegraph.dba.Profiles.JSON.createObjectNode().put("manualOnly",true).put("mode",mode).put("fileName","oracle-"+mode+".rman").put("script",header+commands);
    }
    private static String name(String value){if(value.indexOf('"')>=0||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("This Oracle name requires a manually authored RMAN script");return OracleDialect.identifier(value);}
    private static String comment(String value){return value.replaceAll("[\\r\\n\\p{Cntrl}]"," ");}
}
