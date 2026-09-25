package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;

/** Bounded observations, never inferred completion. Oracle remains the source of job identity/state. */
final class OracleDataPumpHistory {
    private static final int MAX_BYTES=2*1024*1024,MAX_RECORDS=200;
    private final Path file;private ObjectNode state=Profiles.JSON.createObjectNode();
    OracleDataPumpHistory(){file=null;}
    OracleDataPumpHistory(Path directory)throws IOException{
        file=directory.resolve("oracle-datapump-observations.json");if(Files.exists(file)){
            if(Files.isSymbolicLink(file)||Files.size(file)>MAX_BYTES)throw new IOException("Invalid Data Pump observation history");
            JsonNode read=Profiles.JSON.readTree(Files.readAllBytes(file));if(!(read instanceof ObjectNode object)||object.size()>MAX_RECORDS)throw new IOException("Invalid Data Pump observation history");state=object;Profiles.protect(file);
        }
    }
    private String key(String connection,JsonNode status){return CatalogScanner.hash(connection+"\n"+status.path("target")+"\n"+status.path("owner").asText()+"\n"+status.path("name").asText());}
    synchronized ObjectNode observe(String connection,ObjectNode status)throws IOException{
        String key=key(connection,status);JsonNode last=state.get(key);
        if(last!=null)status.set("previousObservation",last.deepCopy());
        if(status.path("available").asBoolean()){
            ObjectNode record=status.deepCopy();record.remove("previousObservation");var next=state.deepCopy();next.remove(key);next.set(key,record);
            byte[] bytes=Profiles.JSON.writeValueAsBytes(next);while(next.size()>MAX_RECORDS||bytes.length>MAX_BYTES){next.remove(next.fieldNames().next());bytes=Profiles.JSON.writeValueAsBytes(next);}
            if(file!=null){Path temp=Files.createTempFile(file.getParent(),"oracle-datapump-",".tmp");try{Profiles.protect(temp);Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
            state=next;
        }return status;
    }
}
