package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Persistent, deny-by-default database grants; local transports also have a built-in shared identity. */
final class AgentAccess {
    static final String TRUSTED_LOCAL_ID=UUID.nameUUIDFromBytes("code-graph:trusted-local-agents".getBytes(StandardCharsets.UTF_8)).toString();
    private final Path file;
    private ObjectNode entries;
    final AgentAuthorization authorization;
    AgentAccess(Path directory) throws IOException {
        this(directory,new AgentAuthorization(false,directory));
    }
    AgentAccess(Path directory,AgentAuthorization authorization) throws IOException {
        this.authorization=authorization;
        file=directory.resolve("agents.json");
        if(Files.exists(file)) {
            if(Files.size(file)>1_048_576)throw new IOException("Agent configuration too large");
            JsonNode n=Profiles.JSON.readTree(file.toFile());
            if(!n.isObject()||n.size()-(n.has(TRUSTED_LOCAL_ID)?1:0)>64)throw new IOException("Invalid agent configuration");
            entries=(ObjectNode)n;
        } else entries=Profiles.JSON.createObjectNode();
    }
    synchronized ArrayNode list(){ArrayNode result=Profiles.JSON.createArrayNode();entries.forEach(n->{ObjectNode p=n.deepCopy();p.remove("tokenHash");result.add(p);});return result;}
    /** Called only after a transport has established local access. This creates no database grants. */
    synchronized String trustedLocal(){
        if(!entries.has(TRUSTED_LOCAL_ID)){
            ObjectNode local=Profiles.JSON.createObjectNode().put("id",TRUSTED_LOCAL_ID).put("name","Trusted local agents").put("trustedLocal",true);
            local.putArray("grants");local.putArray("contextGrants");local.putArray("readPolicies");
            ObjectNode next=entries.deepCopy();next.set(TRUSTED_LOCAL_ID,local);
            try{save(next);}catch(IOException e){throw new java.io.UncheckedIOException("Cannot initialize trusted local agent identity",e);}
            entries=next;
        }
        return TRUSTED_LOCAL_ID;
    }
    synchronized boolean isTrustedLocal(String id){return TRUSTED_LOCAL_ID.equals(id)&&entries.has(TRUSTED_LOCAL_ID);}
    synchronized ObjectNode create(JsonNode input,Profiles profiles)throws IOException {
        if(entries.size()-(entries.has(TRUSTED_LOCAL_ID)?1:0)>=64)throw new IllegalArgumentException("Maximum 64 named agents");
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
    synchronized void remove(String id)throws IOException {if(TRUSTED_LOCAL_ID.equals(id))throw new IllegalArgumentException("The built-in local identity cannot be removed; revoke its read policies or grants instead");if(!entries.has(id))throw new IllegalArgumentException("Unknown agent");ObjectNode next=entries.deepCopy();next.remove(id);save(next);entries=next;}
    synchronized String authenticate(String token){
        if(token==null||token.length()!=43)throw new SecurityException("DBA agent authentication required");
        byte[] expected=hash(token).getBytes(StandardCharsets.US_ASCII);
        for(JsonNode agent:entries)if(MessageDigest.isEqual(expected,agent.path("tokenHash").asText().getBytes(StandardCharsets.US_ASCII)))return agent.path("id").asText();
        throw new SecurityException("DBA agent authentication required");
    }
    synchronized boolean alive(String owner){return owner.startsWith("agent:")&&entries.has(owner.substring(6));}
    synchronized ObjectNode agent(String id){JsonNode value=entries.get(id);if(value==null)throw new SecurityException("Agent revoked");ObjectNode out=value.deepCopy();out.remove("tokenHash");return out;}
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
    ObjectNode contextGrants(String id,JsonNode grants,ProjectContexts contexts)throws IOException{
        if(!grants.isArray()||grants.size()>128)throw new IllegalArgumentException("Maximum 128 project context grants");
        ArrayNode checked=Profiles.JSON.createArrayNode();Set<String> ids=new HashSet<>();
        for(JsonNode grant:grants){String binding=Profiles.text(grant,"bindingId",36);contexts.binding(binding);if(!ids.add(binding))throw new IllegalArgumentException("Duplicate binding grant");checked.addObject().put("bindingId",binding).put("requestLive",grant.path("requestLive").asBoolean(false));}
        synchronized(this){if(!entries.has(id))throw new IllegalArgumentException("Unknown agent");ObjectNode next=entries.deepCopy();((ObjectNode)next.path(id)).set("contextGrants",checked);save(next);entries=next;return Profiles.JSON.createObjectNode().put("saved",true);}
    }
    synchronized void requireContext(String id,ObjectNode binding,boolean live){
        if(!entries.has(id))throw new SecurityException("Agent revoked");
        if(authorization.automatic)return;
        // Permission to ask the human is not permission to execute SQL or read cached data.
        if(live&&isTrustedLocal(id))return;
        for(JsonNode grant:entries.path(id).path("contextGrants"))if(grant.path("bindingId").asText().equals(binding.path("id").asText())&&(!live||grant.path("requestLive").asBoolean()))return;
        if(permitsRead(id,live?"query":"catalog",binding)||live&&permitsRead(id,"catalog",binding))return;
        throw new SecurityException("Project database scope is not granted to this agent");
    }
    synchronized boolean permitsRead(String id,String capability,JsonNode binding){
        JsonNode agent=entries.get(id);if(agent==null)return false;for(JsonNode policy:agent.path("readPolicies")){if(!policy.path("enabled").asBoolean(true))continue;String granted=policy.path("capability").asText();if(!granted.equals("*")&&!granted.equals(capability))continue;String scope=policy.path("scope").asText();
            if(scope.equals("binding")&&policy.path("bindingId").asText().equals(binding.path("id").asText()))return true;
            if(scope.equals("environment")&&!binding.path("legacyEnvironment").asBoolean()&&policy.path("projectId").asText().equals(binding.path("projectId").asText())&&policy.path("environment").asText().equals(binding.path("environment").asText()))return true;
            if(scope.equals("connection")&&policy.path("connectionId").asText().equals(binding.path("connectionId").asText()))return true;
        }return false;
    }
    synchronized ArrayNode effectiveForBinding(String id,JsonNode binding){ArrayNode out=Profiles.JSON.createArrayNode();for(String capability:List.of("catalog","connection_details","connection_test","metadata","ddl","query","plan"))if(authorization.automatic&&alive("agent:"+id)||permitsRead(id,capability,binding))out.add(capability);return out;}
    synchronized ObjectNode permissions(String id,ProjectContexts contexts){ObjectNode out=Profiles.JSON.createObjectNode();ObjectNode a=agent(id);out.put("agentId",id).put("agentName",a.path("name").asText()).put("sharedLocalIdentity",isTrustedLocal(id));out.set("legacyGrants",a.path("grants").deepCopy());out.set("bindingGrants",a.path("contextGrants").deepCopy());out.set("readPolicies",a.path("readPolicies").deepCopy());ArrayNode effective=out.putArray("effectiveBindings");for(JsonNode b:contexts.state().path("bindings")){ArrayNode allowed=effectiveForBinding(id,b);if(!allowed.isEmpty())effective.addObject().put("bindingId",b.path("id").asText()).put("projectId",b.path("projectId").asText()).put("environment",b.path("environment").asText()).put("role",b.path("role").asText()).set("capabilities",allowed);}return out;}
    synchronized ObjectNode grantRead(String id,String action,String capability,JsonNode target)throws IOException{
        if(!entries.has(id))throw new SecurityException("Agent revoked");if(!Set.of("always_binding_read","always_environment_read","always_connection_read").contains(action))throw new IllegalArgumentException("Unsupported persistent read decision");
        ObjectNode policy=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("scope",action.equals("always_environment_read")?"environment":action.equals("always_connection_read")?"connection":"binding").put("capability",action.equals("always_environment_read")?"*":capability).put("enabled",true).put("createdAt",System.currentTimeMillis());
        if(policy.path("scope").asText().equals("binding"))policy.put("bindingId",Profiles.text(target,"id",36));else if(policy.path("scope").asText().equals("connection"))policy.put("connectionId",Profiles.text(target,"connectionId",36));else{if(target.path("legacyEnvironment").asBoolean())throw new IllegalArgumentException("Correct the legacy environment before granting an environment policy");policy.put("projectId",Profiles.text(target,"projectId",36)).put("environment",ProjectContexts.environment(Profiles.text(target,"environment",32)));}
        ObjectNode next=entries.deepCopy();ObjectNode owner=(ObjectNode)next.path(id);ArrayNode policies=owner.has("readPolicies")?(ArrayNode)owner.path("readPolicies"):owner.putArray("readPolicies");if(policies.size()>=128)throw new IllegalArgumentException("Maximum 128 read policies per agent");for(JsonNode existing:policies)if(existing.path("scope").equals(policy.path("scope"))&&existing.path("capability").equals(policy.path("capability"))&&existing.path("bindingId").equals(policy.path("bindingId"))&&existing.path("connectionId").equals(policy.path("connectionId"))&&existing.path("projectId").equals(policy.path("projectId"))&&existing.path("environment").equals(policy.path("environment")))return existing.deepCopy();policies.add(policy);save(next);entries=next;return policy.deepCopy();
    }
    synchronized ObjectNode removePolicy(String agentId,String policyId)throws IOException{if(!entries.has(agentId))throw new IllegalArgumentException("Unknown agent");ObjectNode next=entries.deepCopy();ArrayNode policies=(ArrayNode)next.path(agentId).path("readPolicies");boolean found=false;for(int i=policies.size()-1;i>=0;i--)if(policies.get(i).path("id").asText().equals(policyId)){policies.remove(i);found=true;}if(!found)throw new IllegalArgumentException("Unknown policy");save(next);entries=next;return Profiles.JSON.createObjectNode().put("removed",true);}
    synchronized void removeBinding(String binding)throws IOException{ObjectNode next=entries.deepCopy();boolean changed=false;for(JsonNode raw:next){ObjectNode a=(ObjectNode)raw;for(String field:List.of("contextGrants","readPolicies"))if(a.path(field).isArray()){ArrayNode list=(ArrayNode)a.path(field);for(int i=list.size()-1;i>=0;i--)if(list.get(i).path("bindingId").asText().equals(binding)){list.remove(i);changed=true;}}}if(changed){save(next);entries=next;}}
    synchronized void removeConnection(String connection)throws IOException{ObjectNode next=entries.deepCopy();boolean changed=false;for(JsonNode raw:next){ObjectNode a=(ObjectNode)raw;for(String field:List.of("grants","readPolicies"))if(a.path(field).isArray()){ArrayNode list=(ArrayNode)a.path(field);for(int i=list.size()-1;i>=0;i--)if(list.get(i).path("connectionId").asText().equals(connection)){list.remove(i);changed=true;}}}if(changed){save(next);entries=next;}}

    private void save(ObjectNode next)throws IOException {byte[] bytes=Profiles.JSON.writeValueAsBytes(next);if(bytes.length>1_048_576)throw new IllegalArgumentException("Agent configuration too large");Path temp=Files.createTempFile(file.getParent(),"agents-",".tmp");try{Profiles.protect(temp);Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temp);}}
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
