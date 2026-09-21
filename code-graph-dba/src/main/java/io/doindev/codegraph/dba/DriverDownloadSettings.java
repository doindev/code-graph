package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.Set;

/** Stores paths/preferences, never Maven credentials or certificate contents. */
final class DriverDownloadSettings {
    private final Path file;
    private volatile DriverDownloadConfig current;
    DriverDownloadSettings(Path data,DriverDownloadConfig startup)throws IOException{
        file=data.resolve("settings.json");
        current=startup;
        if(!startup.explicit()&&Files.isRegularFile(file)){
            if(Files.size(file)>65536)throw new IOException("Application settings exceed 64 KiB");
            // Missing mounted files must not prevent the app starting: validate on Apply/download.
            JsonNode saved=Profiles.JSON.readTree(file.toFile());
            if(saved.has("driverDownloads"))current=read(saved.path("driverDownloads"),false);
        }
    }
    DriverDownloadConfig current(){return current;}
    ObjectNode json(){
        var c=current;return Profiles.JSON.createObjectNode().put("mode",c.mode()).put("command",c.command())
            .put("settings",c.settings()==null?"":c.settings().toString()).put("certPem",c.certPem()==null?"":c.certPem().toString())
            .put("insecureTls",c.insecureTls()).put("defaultSettings",Path.of(System.getProperty("user.home"),".m2","settings.xml").toString());
    }
    synchronized ObjectNode save(JsonNode input)throws Exception{
        var c=read(input,true);
        if(c.certPem()!=null&&!c.insecureTls())ExternalMaven.certificates(c.certPem());
        Path temporary=Files.createTempFile(file.getParent(),".driver-settings-",".json");
        try{
            ObjectNode n=Profiles.JSON.createObjectNode().put("mode",c.mode()).put("command",c.command())
                .put("settings",c.settings()==null?"":c.settings().toString()).put("certPem",c.certPem()==null?"":c.certPem().toString()).put("insecureTls",c.insecureTls());
            if(Files.exists(file)&&Files.size(file)>65536)throw new IOException("Application settings exceed 64 KiB");
            ObjectNode settings=Files.exists(file)?(ObjectNode)Profiles.JSON.readTree(file.toFile()):Profiles.JSON.createObjectNode();
            settings.set("driverDownloads",n);byte[] bytes=Profiles.JSON.writeValueAsBytes(settings);
            if(bytes.length>65536)throw new IllegalArgumentException("Application settings exceed 64 KiB");
            Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            current=c;return json();
        }finally{Files.deleteIfExists(temporary);}
    }
    private static DriverDownloadConfig read(JsonNode input,boolean validate){
        if(input==null||!input.isObject())throw new IllegalArgumentException("Driver download settings must be an object");
        input.fieldNames().forEachRemaining(k->{if(!Set.of("mode","command","settings","certPem","insecureTls").contains(k))throw new IllegalArgumentException("Unknown driver download setting: "+k);});
        if(input.has("mode")&&!input.path("mode").isTextual())throw new IllegalArgumentException("Download mode must be embedded or maven");
        String mode=input.path("mode").asText("embedded"),command=optional(input,"command");
        String settings=optional(input,"settings"),cert=optional(input,"certPem");
        if(input.has("insecureTls")&&!input.path("insecureTls").isBoolean())throw new IllegalArgumentException("insecureTls must be a boolean");
        return new DriverDownloadConfig(mode,command,settings.isBlank()?null:Path.of(settings),cert.isBlank()?null:Path.of(cert),validate,input.path("insecureTls").asBoolean());
    }
    private static String optional(JsonNode input,String key){
        JsonNode node=input.get(key);if(node==null)return "";
        if(!node.isTextual()||node.asText().length()>4096||node.asText().matches("(?s).*[\\r\\n\\x00].*"))throw new IllegalArgumentException("Invalid "+key+" path");
        return node.asText().strip();
    }
}
