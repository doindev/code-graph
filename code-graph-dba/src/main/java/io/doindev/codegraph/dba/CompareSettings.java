package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.*;

/** Optional persisted comparison metadata budget; editor limits remain independent. */
final class CompareSettings {
    static final int DEFAULT_MIB=16, MIN_MIB=1, MAX_MIB=256;
    private final Path file;
    private volatile Integer metadataMiB;
    private volatile String warning="";
    CompareSettings(Path directory)throws IOException {
        file=directory.resolve("compare-settings.json");
        if(Files.exists(file))try{
            if(Files.size(file)>4096)throw new IllegalArgumentException("Settings file exceeds 4 KiB");
            JsonNode saved=Profiles.JSON.readTree(file.toFile());
            if(saved==null||!saved.isObject()||saved.path("version").asInt()!=1)throw new IllegalArgumentException("Unsupported settings version");
            metadataMiB=validate(saved.get("compareMetadataMiB"));
        }catch(Exception invalid){warning="Saved comparison limit could not be loaded; the 16 MiB default is in use.";}
    }
    Integer metadataMiB(){return metadataMiB;}
    long bytes(){return (long)(metadataMiB==null?DEFAULT_MIB:metadataMiB)<<20;}
    String warning(){return warning;}
    static Integer validate(JsonNode value){
        if(value==null||value.isNull())return null;
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<MIN_MIB||value.asInt()>MAX_MIB)
            throw new IllegalArgumentException("Comparison metadata limit must be null (default) or a whole number from 1 to 256 MiB");
        return value.asInt();
    }
    synchronized void save(JsonNode value)throws IOException {
        Integer nextMiB=validate(value);
        var next=Profiles.JSON.createObjectNode().put("version",1).put("compareMetadataMiB",nextMiB);
        Path temporary=Files.createTempFile(file.getParent(),".compare-settings-",".json");
        try{
            Profiles.protect(temporary);Files.write(temporary,Profiles.JSON.writeValueAsBytes(next));
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            metadataMiB=nextMiB;warning="";
        }finally{Files.deleteIfExists(temporary);}
    }
}
