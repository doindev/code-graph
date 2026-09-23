package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;

/** Persisted authorization wait limit; changes apply to newly submitted requests only. */
final class ApprovalSettings {
    static final int DEFAULT_SECONDS=300, MIN_SECONDS=10, MAX_SECONDS=3600;
    private final Path file;
    private volatile int timeoutSeconds=DEFAULT_SECONDS;
    private volatile String warning="";
    ApprovalSettings(Path directory)throws IOException {
        file=directory.resolve("approval-settings.json");
        if(Files.exists(file))try{
            if(Files.size(file)>4096)throw new IllegalArgumentException("Settings file exceeds 4 KiB");
            JsonNode saved=Profiles.JSON.readTree(file.toFile());
            if(saved==null||!saved.isObject()||saved.path("version").asInt()!=1)throw new IllegalArgumentException("Unsupported settings version");
            timeoutSeconds=validate(saved.path("approvalTimeoutSeconds"));
        }catch(Exception invalid){warning="Saved approval timeout could not be loaded; the five-minute default is in use.";}
    }
    int timeoutSeconds(){return timeoutSeconds;}
    String warning(){return warning;}
    static int validate(JsonNode value){
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<MIN_SECONDS||value.asInt()>MAX_SECONDS)
            throw new IllegalArgumentException("Approval timeout must be a whole number from 10 to 3600 seconds");
        return value.asInt();
    }
    synchronized void save(JsonNode value)throws IOException {
        int seconds=validate(value);
        var next=Profiles.JSON.createObjectNode().put("version",1).put("approvalTimeoutSeconds",seconds);
        Path temporary=Files.createTempFile(file.getParent(),".approval-settings-",".json");
        try{
            Profiles.protect(temporary);Files.write(temporary,Profiles.JSON.writeValueAsBytes(next));
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            timeoutSeconds=seconds;warning="";
        }finally{Files.deleteIfExists(temporary);}
    }
}
