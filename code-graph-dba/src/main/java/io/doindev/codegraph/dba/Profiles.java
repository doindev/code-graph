package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.channels.*;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Atomic, owner-private configuration. Credentials live exclusively in the injected vault. */
public final class Profiles implements AutoCloseable {
    static final ObjectMapper JSON=new ObjectMapper();
    private final Path directory,file;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Vault vault;
    private ObjectNode state;
    public Profiles(Path directory,Vault vault) throws IOException {
        this.directory=directory.toAbsolutePath().normalize();this.vault=vault;
        if(this.directory.getParent()==null)throw new IOException("DBA data must use a dedicated directory");
        Path marker=this.directory.resolve(".code-graph-dba-owner");
        if(Files.exists(this.directory)){
            boolean empty;try(var entries=Files.list(this.directory)){empty=entries.findAny().isEmpty();}
            if(!empty&&(!Files.isRegularFile(marker)||Files.size(marker)>64||!Files.readString(marker).equals("code-graph-dba-v1")))
                throw new IOException("DBA data directory is not empty or owned by this application");
        }
        Files.createDirectories(this.directory); protect(this.directory);
        if(!Files.exists(marker))Files.writeString(marker,"code-graph-dba-v1",StandardOpenOption.CREATE_NEW);
        this.file=this.directory.resolve("profiles.json");
        lockChannel=FileChannel.open(this.directory.resolve("owner.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired=lockChannel.tryLock(); }
        catch(Exception e) {lockChannel.close();throw new IOException("DBA directory already in use",e);}
        if(acquired==null){lockChannel.close();throw new IOException("DBA directory already in use");}
        lock=acquired;
        try {
            if(Files.exists(file)) {
                if(Files.size(file)>1024*1024)throw new IOException("DBA profile file too large");
                JsonNode read=JSON.readTree(file.toFile());
                if(!read.isObject() || !Set.of(1,2).contains(read.path("version").asInt()) || !read.path("connections").isObject())throw new IOException("Unsupported DBA configuration");
                state=(ObjectNode)read;
            } else {state=JSON.createObjectNode().put("version",1);state.putObject("connections");save(state);}
        } catch(Exception e) {close();throw new IOException("Cannot open DBA configuration",e);}
    }
    public Path directory(){return directory;}
    static void protect(Path path) throws IOException {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class);
        if(posix!=null)posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(path)?"rwx------":"rw-------"));
        else {
            var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
            if(acl==null)throw new IOException("Owner-private file permissions unavailable");
            var builder=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(Files.isDirectory(path))builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT);
            acl.setAcl(List.of(builder.build()));
        }
    }
    private void save(ObjectNode next) throws IOException {
        Path temp=Files.createTempFile(directory,"profiles-",".tmp");
        try {protect(temp);Files.write(temp,JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        finally {Files.deleteIfExists(temp);}
    }
    public synchronized ArrayNode list() {ArrayNode out=JSON.createArrayNode();state.withObject("connections").elements().forEachRemaining(p->out.add(p.deepCopy()));return out;}
    public synchronized ObjectNode get(String id) {
        JsonNode p=state.path("connections").get(id);
        if(p==null)throw new IllegalArgumentException("Unknown connection");
        return (ObjectNode)p.deepCopy();
    }
    public synchronized ObjectNode put(String id,JsonNode input) throws IOException {
        ConnectionDraft draft=draft(id,input);
        try{return saveDraft(id,draft,input.path("validationStatus").asText("untested"));}finally{draft.clear();}
    }
    synchronized ConnectionDraft draft(String id,JsonNode input) {
        ObjectNode old=id==null?JSON.createObjectNode():get(id),merged=old.deepCopy();
        merged.setAll((ObjectNode)input);ObjectNode secret=SecretRecords.read(vault,old);
        try{return ConnectionDraft.create(merged,secret);}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Invalid connection settings; check readable driver files");}finally{secret.removeAll();}
    }
    synchronized ObjectNode saveDraft(String id,ConnectionDraft draft,String validation)throws IOException {
        for(JsonNode existing:state.path("connections"))if(!existing.path("id").asText().equals(id)&&nameKey(existing.path("name").asText()).equals(nameKey(draft.profile().path("name").asText())))throw new IllegalArgumentException("Connection names must be unique (ignoring case and surrounding spaces)");
        if(id==null){if(state.path("connections").size()>=64)throw new IllegalArgumentException("Maximum 64 profiles");id=UUID.randomUUID().toString();}
        ObjectNode old=state.path("connections").has(id)?get(id):JSON.createObjectNode();
        ObjectNode p=draft.profile().deepCopy().put("id",id);
        ObjectNode key=draft.validateKey();if(!key.isEmpty()){key.remove("contentHash");p.set("keyValidation",key);}
        if(draft.secret().has("password")||draft.secret().path("properties").size()>0)p.set("credentialRefs",SecretRecords.write(vault,draft.secret()));
        ArrayNode names=p.putArray("secretPropertyNames");draft.secret().path("properties").fieldNames().forEachRemaining(names::add);
        ObjectNode next=state.deepCopy().put("version",2);next.withObject("connections").set(id,p);
        try{if(JSON.writeValueAsBytes(next).length>1024*1024)throw new IllegalArgumentException("Profile storage exceeds 1 MiB");save(next);}catch(Exception e){SecretRecords.remove(vault,p);throw e;}
        state=next;SecretRecords.remove(vault,old);return publicProfile(p);
    }
    public synchronized void remove(String id) throws IOException {
        ObjectNode profile=get(id),next=state.deepCopy();next.withObject("connections").remove(id);
        save(next);state=next;
        SecretRecords.remove(vault,profile);
    }
    synchronized ObjectNode appearance(String id,JsonNode input)throws IOException{get(id);if(!input.isObject()||input.size()!=1||!input.has("color"))throw new IllegalArgumentException("Appearance accepts only color");String color=ConnectionDraft.color(input);ObjectNode next=state.deepCopy();((ObjectNode)next.path("connections").path(id)).put("color",color);save(next);state=next;return publicProfile(get(id));}
    synchronized ObjectNode rename(String id,JsonNode input)throws IOException{
        ObjectNode current=get(id);if(!input.path("expectedName").asText().equals(current.path("name").asText()))throw new IllegalArgumentException("Connection name changed; refresh and retry");
        String name=Profiles.text(input,"name",128).strip();if(name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Connection name cannot contain control characters");
        for(JsonNode p:state.path("connections"))if(!p.path("id").asText().equals(id)&&nameKey(p.path("name").asText()).equals(nameKey(name)))throw new IllegalArgumentException("Connection names must be unique");
        ObjectNode next=state.deepCopy();((ObjectNode)next.path("connections").path(id)).put("name",name);save(next);state=next;return publicProfile(get(id));
    }
    public static ObjectNode publicProfile(JsonNode p){ObjectNode result=p.deepCopy();result.remove(List.of("credentialRef","credentialRefs"));result.put("color",p.path("color").asText("transparent"));result.put("hasCredential",!SecretRecords.refs(p).isEmpty());return result;}
    public synchronized ArrayNode publicList(){ArrayNode out=JSON.createArrayNode();list().forEach(p->out.add(publicProfile(p)));return out;}
    synchronized ArrayNode reorder(JsonNode input)throws IOException{JsonNode ids=input.path("ids");ObjectNode current=state.withObject("connections");if(!ids.isArray()||ids.size()!=current.size())throw new IllegalArgumentException("Connection list changed; refresh before reordering");ObjectNode ordered=JSON.createObjectNode();for(JsonNode value:ids){if(!value.isTextual()||!current.has(value.asText())||ordered.has(value.asText()))throw new IllegalArgumentException("Order must contain every connection ID exactly once");ordered.set(value.asText(),current.path(value.asText()).deepCopy());}ObjectNode next=state.deepCopy();next.set("connections",ordered);save(next);state=next;return publicList();}
    Properties credentials(ObjectNode p) {
        ConnectionDraft draft=new ConnectionDraft(p,SecretRecords.read(vault,p));try{draft.validateKey();return draft.properties();}finally{draft.clear();}
    }
    synchronized String redactError(String id,String message){
        try{
            ObjectNode profile=get(id),secret=SecretRecords.read(vault,profile);
            try{
                List<String> sensitive=new ArrayList<>(SecretRecords.refs(profile));
                if(secret.has("password"))sensitive.add(expand(secret.path("password").asText()));
                for(JsonNode value:secret.path("properties"))sensitive.add(expand(value.asText()));
                sensitive.sort(Comparator.comparingInt(String::length).reversed());
                for(String value:sensitive)if(!value.isEmpty()){message=message.replace(value,"[redacted]");message=message.replace(java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8),"[redacted]");}
                message=message.replaceAll("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----","[redacted private key]");
                return message.length()>8192?message.substring(0,8192)+" [message truncated]":message;
            }finally{secret.removeAll();}
        }catch(Exception unavailable){return "Database operation failed; detailed message withheld because credential redaction was unavailable";}
    }
    static String expand(String value){if(value.replaceAll("\\$\\{[A-Za-z_][A-Za-z0-9_]*}","").contains("${"))throw new IllegalArgumentException("Invalid environment placeholder; use ${ENV_VAR_NAME}");Matcher m=Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}").matcher(value);StringBuilder out=new StringBuilder();
        while(m.find()){String v=System.getenv(m.group(1));if(v==null)throw new IllegalArgumentException("Unresolved environment placeholder");m.appendReplacement(out,Matcher.quoteReplacement(v));}m.appendTail(out);return out.toString();}
    static String text(JsonNode n,String key,int max){String value=n.path(key).asText("");if(value.isBlank()||value.length()>max)throw new IllegalArgumentException("Missing/oversize "+key);return value;}
    static String nameKey(String name){return java.text.Normalizer.normalize(name.strip(),java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT);}
    @Override public void close() throws IOException {try{lock.release();}finally{lockChannel.close();}}
}
