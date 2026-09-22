package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Preferences never change connection credentials or authorization revisions. */
final class GridSettings {
    static final JsonNode SCHEMA;
    static {
        try(var input=GridSettings.class.getResourceAsStream("/codegraph/dba/grid-settings-schema.json")){
            if(input==null)throw new IOException("Missing grid settings schema");
            SCHEMA=Profiles.JSON.readTree(input);
        }catch(IOException error){throw new ExceptionInInitializerError(error);}
    }
    private final Path file;
    private ObjectNode state=empty();
    private String warning="";
    GridSettings(Path directory)throws IOException {
        file=directory.resolve("grid-settings.json");
        if(Files.exists(file))try{
            if(Files.size(file)>1<<20)throw new IllegalArgumentException("File exceeds 1 MiB");
            JsonNode saved=Profiles.JSON.readTree(file.toFile());
            if(!saved.isObject()||saved.path("version").asInt()!=1||!saved.path("revision").isIntegralNumber()||!saved.path("revision").canConvertToLong()||saved.path("revision").asLong()<0||!saved.path("connections").isObject()||saved.path("connections").size()>64)throw new IllegalArgumentException("Invalid settings file");
            validate(saved.path("global"));
            var entries=saved.path("connections").fields();while(entries.hasNext()){var entry=entries.next();UUID.fromString(entry.getKey());validate(entry.getValue());}
            state=(ObjectNode)saved;
        }catch(Exception invalid){warning="Saved grid settings could not be loaded; defaults are in use. "+invalid.getMessage();}
    }
    private static ObjectNode empty(){var node=Profiles.JSON.createObjectNode().put("version",1).put("revision",0);node.putObject("global");node.putObject("connections");return node;}
    static ObjectNode defaults(){var out=Profiles.JSON.createObjectNode();for(JsonNode field:SCHEMA.path("fields"))out.set(field.path("key").asText(),field.path("default").deepCopy());return out;}
    static ObjectNode validate(JsonNode values){
        if(!values.isObject())throw new IllegalArgumentException("Settings must be an object");
        var definitions=new HashMap<String,JsonNode>();for(JsonNode field:SCHEMA.path("fields"))definitions.put(field.path("key").asText(),field);
        var entries=values.fields();while(entries.hasNext()){
            var entry=entries.next();JsonNode field=definitions.get(entry.getKey()),value=entry.getValue();
            if(field==null)throw new IllegalArgumentException("Unknown grid setting: "+entry.getKey());
            boolean valid=switch(field.path("type").asText()){
                case "boolean"->value.isBoolean();
                case "integer"->value.isIntegralNumber()&&value.canConvertToInt()&&value.asInt()>=field.path("min").asInt()&&value.asInt()<=field.path("max").asInt();
                case "enum"->value.isTextual()&&field.path("options").has(value.asText());
                case "text"->value.isTextual()&&value.asText().codePointCount(0,value.asText().length())<=field.path("maxLength").asInt()&&!value.asText().matches("(?s).*[\\r\\n\\x00].*");
                default->false;
            };
            if(!valid)throw new IllegalArgumentException("Invalid "+field.path("label").asText());
        }
        return ((ObjectNode)values).deepCopy();
    }
    synchronized ObjectNode json(int ceiling){var out=state.deepCopy();out.set("schema",SCHEMA.deepCopy());out.put("rowCeiling",ceiling).put("warning",warning);return out;}
    synchronized ObjectNode save(JsonNode request,Profiles profiles,int ceiling)throws IOException {
        if(!request.isObject())throw new IllegalArgumentException("Settings request must be an object");
        request.fieldNames().forEachRemaining(key->{if(!Set.of("scope","connectionId","expectedRevision","settings").contains(key))throw new IllegalArgumentException("Unknown settings request property: "+key);});
        JsonNode revision=request.path("expectedRevision");
        if(!revision.isIntegralNumber()||!revision.canConvertToLong()||revision.asLong()!=state.path("revision").asLong())throw new IllegalArgumentException("Grid settings changed in another window. Reload settings before applying again.");
        ObjectNode values=validate(request.path("settings")),next=state.deepCopy();
        switch(request.path("scope").asText()){
            case "global"->{if(request.has("connectionId"))throw new IllegalArgumentException("Global settings do not have a connection");next.set("global",values);}
            case "connection"->{String id=Profiles.text(request,"connectionId",36);UUID.fromString(id);profiles.get(id);if(values.isEmpty())next.withObject("connections").remove(id);else next.withObject("connections").set(id,values);}
            default->throw new IllegalArgumentException("Choose global or connection settings");
        }
        write(next);return json(ceiling);
    }
    synchronized void removeConnection(String id)throws IOException {if(!state.path("connections").has(id))return;ObjectNode next=state.deepCopy();next.withObject("connections").remove(id);write(next);}
    private void write(ObjectNode next)throws IOException {
        next.put("revision",state.path("revision").asLong()+1);byte[] bytes=Profiles.JSON.writeValueAsBytes(next);
        if(bytes.length>1<<20)throw new IllegalArgumentException("Grid settings exceed 1 MiB");
        Path temporary=Files.createTempFile(file.getParent(),".grid-settings-",".json");
        try{Profiles.protect(temporary);Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);state=next;warning="";}finally{Files.deleteIfExists(temporary);}
    }
}
