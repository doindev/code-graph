package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;

/** Persisted HTTP MCP idle policy; transport renewals capture the current duration. */
final class McpSessionSettings {
    static final int DEFAULT_MINUTES=60, MIN_MINUTES=1, MAX_MINUTES=10080;
    private final Path file;
    private volatile int idleTimeoutMinutes=DEFAULT_MINUTES;
    private volatile String warning="";
    McpSessionSettings(Path directory)throws IOException {
        file=directory.resolve("mcp-session-settings.json");
        if(Files.exists(file))try{
            if(Files.size(file)>4096)throw new IllegalArgumentException("Settings file exceeds 4 KiB");
            JsonNode saved=Profiles.JSON.readTree(file.toFile());
            if(saved==null||!saved.isObject()||!saved.path("version").isIntegralNumber()||!saved.path("version").canConvertToInt()||saved.path("version").asInt()!=1)
                throw new IllegalArgumentException("Unsupported settings version");
            idleTimeoutMinutes=validate(saved.path("mcpSessionIdleTimeoutMinutes"));
        }catch(Exception invalid){warning="Saved MCP session timeout could not be loaded; the one-hour default is in use.";}
    }
    int idleTimeoutMinutes(){return idleTimeoutMinutes;}
    long idleTimeoutMillis(){return idleTimeoutMinutes*60_000L;}
    String warning(){return warning;}
    static int validate(JsonNode value){
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<MIN_MINUTES||value.asInt()>MAX_MINUTES)
            throw new IllegalArgumentException("MCP session idle timeout must be a whole number from 1 to 10080 minutes");
        return value.asInt();
    }
    synchronized void save(JsonNode value)throws IOException {
        int minutes=validate(value);
        var next=Profiles.JSON.createObjectNode().put("version",1).put("mcpSessionIdleTimeoutMinutes",minutes);
        Path temporary=Files.createTempFile(file.getParent(),".mcp-session-settings-",".json");
        try{
            Profiles.protect(temporary);Files.write(temporary,Profiles.JSON.writeValueAsBytes(next));
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            idleTimeoutMinutes=minutes;warning="";
        }finally{Files.deleteIfExists(temporary);}
    }
}
