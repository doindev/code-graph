package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Persistent, deny-by-default read grants. Only token hashes are persisted. */
final class AgentAccess {
    private final Path file;
    private ObjectNode entries;
    AgentAccess(Path directory) throws IOException {
        file=directory.resolve("agents.json");
        if(Files.exists(file)) {
            if(Files.size(file)>1_048_576)throw new IOException("Agent configuration too large");
            JsonNode n=Profiles.JSON.readTree(file.toFile());
            if(!n.isObject()||n.size()>64)throw new IOException("Invalid agent configuration");
            entries=(ObjectNode)n;
        } else entries=Profiles.JSON.createObjectNode();
    }
    synchronized ArrayNode list(){ArrayNode result=Profiles.JSON.createArrayNode();entries.forEach(n->{ObjectNode p=n.deepCopy();p.remove("tokenHash");result.add(p);});return result;}
    synchronized ObjectNode create(JsonNode input,Profiles profiles)throws IOException {
        if(entries.size()>=64)throw new IllegalArgumentException("Maximum 64 agents");
        String name=Profiles.text(input,"name",120);
        JsonNode grants=input.path("grants");
        if(!grants.isArray()||grants.size()>64)throw new IllegalArgumentException("grants must be an array, maximum 64");
        ArrayNode checked=Profiles.JSON.createArrayNode();
        for(JsonNode grant:grants){String connection=Profiles.text(grant,"connectionId",36);String connectionName=profiles.get(connection).path("name").asText();if(grant.has("connectionName")&&!Profiles.nameKey(grant.path("connectionName").asText()).equals(Profiles.nameKey(connectionName)))throw new IllegalArgumentException("Grant connection name does not match its ID");
            JsonNode objects=grant.path("objects");if(!objects.isArray()||objects.size()>128)throw new IllegalArgumentException("objects must be an array, maximum 128");
            ObjectNode g=checked.addObject().put("connectionId",connection).put("connectionName",connectionName);ArrayNode list=g.putArray("objects");
            for(JsonNode object:objects)list.addObject().put("schema",Profiles.text(object,"schema",128)).put("name",Profiles.text(object,"name",128));
        }
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String id=UUID.randomUUID().toString();ObjectNode agent=Profiles.JSON.createObjectNode().put("id",id).put("name",name).put("tokenHash",hash(token));agent.set("grants",checked);
        ObjectNode next=entries.deepCopy();next.set(id,agent);save(next);entries=next;
        return Profiles.JSON.createObjectNode().put("id",id).put("token",token);
    }
    synchronized void remove(String id)throws IOException {if(!entries.has(id))throw new IllegalArgumentException("Unknown agent");ObjectNode next=entries.deepCopy();next.remove(id);save(next);entries=next;}
    synchronized String authenticate(String token){
        if(token==null||token.length()!=43)throw new SecurityException("DBA agent authentication required");
        byte[] expected=hash(token).getBytes(StandardCharsets.US_ASCII);
        for(JsonNode agent:entries)if(MessageDigest.isEqual(expected,agent.path("tokenHash").asText().getBytes(StandardCharsets.US_ASCII)))return agent.path("id").asText();
        throw new SecurityException("DBA agent authentication required");
    }
    synchronized boolean alive(String owner){return owner.startsWith("agent:")&&entries.has(owner.substring(6));}
    synchronized ArrayNode objects(String id,String connection){
        JsonNode agent=entries.get(id);if(agent==null)throw new SecurityException("Agent revoked");
        ArrayNode result=Profiles.JSON.createArrayNode();for(JsonNode grant:agent.path("grants"))if(grant.path("connectionId").asText().equals(connection))grant.path("objects").forEach(o->result.add(o.deepCopy()));
        if(result.isEmpty())throw new SecurityException("Connection access denied");return result;
    }
    synchronized boolean permits(String id,String connection){try{objects(id,connection);return true;}catch(SecurityException e){return false;}}
    synchronized void bindLegacyNames(Profiles profiles)throws IOException{ObjectNode next=entries.deepCopy();boolean changed=false;for(JsonNode agent:next)for(JsonNode grant:agent.path("grants"))if(!grant.has("connectionName")){try{((ObjectNode)grant).put("connectionName",profiles.get(grant.path("connectionId").asText()).path("name").asText());changed=true;}catch(IllegalArgumentException removed){}}if(changed){save(next);entries=next;}}
    synchronized void requireName(String id,String connection,String name){objects(id,connection);for(JsonNode grant:entries.path(id).path("grants"))if(grant.path("connectionId").asText().equals(connection)&&Profiles.nameKey(grant.path("connectionName").asText()).equals(Profiles.nameKey(name)))return;throw new SecurityException("Connection name is not granted; renamed connections need a reviewed grant");}
    void requireObject(String id,String connection,String schema,String name){
        for(JsonNode object:objects(id,connection))if(object.path("schema").asText().equals(schema)&&object.path("name").asText().equals(name))return;
        throw new SecurityException("Object access denied; use an explicitly granted schema-qualified object");
    }
    private void save(ObjectNode next)throws IOException {byte[] bytes=Profiles.JSON.writeValueAsBytes(next);if(bytes.length>1_048_576)throw new IllegalArgumentException("Agent configuration too large");Path temp=Files.createTempFile(file.getParent(),"agents-",".tmp");try{Profiles.protect(temp);Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
