package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;

/** Bounded chunks respect the smallest supported OS-vault record limit. */
final class SecretRecords {
    static ObjectNode read(Vault vault,JsonNode profile){
        try(var out=new ByteArrayOutputStream()){
            List<String> refs=refs(profile);if(refs.isEmpty())return Profiles.JSON.createObjectNode();
            for(String ref:refs){byte[] b=vault.get(ref);try{if(out.size()+b.length>65_536)throw new IllegalArgumentException("Secret configuration exceeds 64 KiB");out.write(b);}finally{Arrays.fill(b,(byte)0);}}
            byte[] bytes=out.toByteArray();try{return (ObjectNode)Profiles.JSON.readTree(bytes);}finally{Arrays.fill(bytes,(byte)0);}
        }catch(IOException e){throw new IllegalStateException("Unreadable OS-vault record");}
    }
    static List<String> refs(JsonNode profile){List<String> refs=new ArrayList<>();if(profile.has("credentialRefs"))profile.path("credentialRefs").forEach(n->refs.add(n.asText()));else if(profile.has("credentialRef"))refs.add(profile.path("credentialRef").asText());if(refs.size()>28)throw new IllegalArgumentException("Invalid vault reference count");return refs;}
    static ArrayNode write(Vault vault,ObjectNode secret)throws IOException {
        byte[] bytes=Profiles.JSON.writeValueAsBytes(secret);if(bytes.length>65_536)throw new IllegalArgumentException("Secret configuration exceeds 64 KiB");
        ArrayNode refs=Profiles.JSON.createArrayNode();try{for(int start=0;start<bytes.length;start+=2400){String ref="code-graph-dba/"+UUID.randomUUID();byte[] part=Arrays.copyOfRange(bytes,start,Math.min(bytes.length,start+2400));try{vault.put(ref,part);refs.add(ref);}finally{Arrays.fill(part,(byte)0);}}return refs;}
        catch(RuntimeException e){for(JsonNode ref:refs)try{vault.remove(ref.asText());}catch(RuntimeException ignored){}throw e;}finally{Arrays.fill(bytes,(byte)0);}
    }
    static void remove(Vault vault,JsonNode profile){for(String ref:refs(profile))vault.remove(ref);}
}
