package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Browser tree projection of the same bounded native reads used by workspaces. */
final class NativeMetadataTree {
    static ObjectNode load(NativeConnections.Lease lease,NativeTarget target,JsonNode request,QueryJobs.Job job)throws Exception{
        String kind=request.path("kind").asText("root");
        var result=Profiles.JSON.createObjectNode().put("transport",target.transport().id).put("database",target.database());
        var nodes=result.putArray("nodes");
        if(kind.equals("root")){
            node(nodes,"database",target.database(),true,target);
            result.put("coverage","Configured database only; use a native workspace with an explicit database to inspect another target.");
            return result;
        }
        if(kind.equals("native_database")){
            if(target.transport()==DatabaseTransport.MONGODB){node(nodes,"collections","Collections",true,target);node(nodes,"views","Views",true,target);}
            else node(nodes,"keys","Keys",true,target);
            return result;
        }
        if(kind.equals("native_collection")){node(nodes,"indexes","Indexes",true,target);return result;}
        JsonNode command;
        if(kind.equals("native_collections")||kind.equals("native_views")){
            ObjectNode metadata=Profiles.JSON.createObjectNode().put("listCollections",1);
            var filter=metadata.putObject("filter");
            if(kind.equals("native_views"))filter.put("type","view");
            else{
                filter.putObject("type").putArray("$in").add("collection").add("timeseries");
                filter.putObject("name").putObject("$not").put("$regex","^system\\.");
            }
            command=metadata;
        }else if(kind.equals("native_indexes"))command=Profiles.JSON.createObjectNode().put("listIndexes",target.collection());
        else if(kind.equals("native_keys")){
            command=Profiles.JSON.createArrayNode().add("SCAN").add(request.path("offset").asText("0")).add("MATCH").add(request.path("pattern").asText("*"));
        }else throw new IllegalArgumentException("Unsupported native metadata branch");
        ObjectNode rows=NativeReadExecutor.execute(lease,target,command,new NativeReadExecutor.Limits(100,1<<20,job.remainingSeconds()),()->job.cancelled);
        List<ObjectNode> found=new ArrayList<>();
        for(JsonNode row:rows.path("entries")){
            if(kind.equals("native_keys")){
                ObjectNode key=keyNode(row,target);
                if(key==null)result.put("truncated",true).put("warning","Some key names exceed the interactive byte allowance; refine the scan pattern.");
                else found.add(key);
                continue;
            }
            String name=kind.equals("native_keys")?row.path("text").asText(""):row.path("name").asText("");
            if(name.isBlank()||name.length()>255||row.path("truncated").asBoolean()||row.path("documentOmitted").asBoolean()){
                result.put("truncated",true).put("warning","Some names require the typed native workspace and cannot be represented as text tree items.");continue;
            }
            String child=kind.equals("native_collections")?"collection":kind.equals("native_views")?"view":kind.equals("native_indexes")?"index":"key";
            var entry=Profiles.JSON.createObjectNode();var one=Profiles.JSON.createArrayNode();node(one,child,name,child.equals("collection"),target);entry=(ObjectNode)one.get(0);
            if(child.equals("collection")||child.equals("view"))entry.put("collection",name);
            entry.put("nativeObject",true);entry.set("command",commandFor(child,name,target));
            found.add(entry);
        }
        found.sort(Comparator.comparing(n->n.path("name").asText(),String.CASE_INSENSITIVE_ORDER));found.forEach(nodes::add);
        if(rows.path("truncated").asBoolean())result.put("truncated",true).put("warning","Metadata exceeded the interactive allowance; use a filtered native metadata command to narrow the inventory.");
        if(rows.has("nextCursor")&&!rows.path("scanComplete").asBoolean())result.put("nextOffset",rows.path("nextCursor").asText());
        result.put("coverage","bounded_live_non_snapshot");return result;
    }
    static ObjectNode keyNode(JsonNode row,NativeTarget target){
        if(row.path("truncated").asBoolean()||!row.path("base64").isTextual())return null;
        String encoded=row.path("base64").asText();
        var argument=Profiles.JSON.createObjectNode().put("base64",encoded);
        var command=Profiles.JSON.createArrayNode().add("TYPE").add(argument);
        try{NativeRedisArguments.validate(command);}catch(IllegalArgumentException invalid){return null;}
        String text=row.path("text").asText("");
        String name=!text.isBlank()&&text.length()<=255&&text.chars().noneMatch(Character::isISOControl)
                ?text:encoded.isEmpty()?"[empty key]":"[binary key: "+encoded.substring(0,Math.min(48,encoded.length()))+(encoded.length()>48?"…":"")+"]";
        var one=Profiles.JSON.createArrayNode();node(one,"key",name,false,target);
        ObjectNode entry=(ObjectNode)one.get(0);
        entry.put("key","key:base64:"+encoded).put("keyEncoding","base64").put("nativeObject",true);
        entry.set("command",command);return entry;
    }
    private static JsonNode commandFor(String kind,String name,NativeTarget target){
        return switch(kind){
            case "key" -> Profiles.JSON.createArrayNode().add("TYPE").add(name);
            case "index" -> Profiles.JSON.createObjectNode().put("listIndexes",target.collection());
            default -> Profiles.JSON.createObjectNode().put("find",name).put("limit",100).set("filter",Profiles.JSON.createObjectNode());
        };
    }
    private static void node(ArrayNode list,String kind,String name,boolean branch,NativeTarget target){
        list.addObject().put("key",kind+":"+name).put("kind","native_"+kind).put("name",name).put("branch",branch)
                .put("database",target.database()).put("collection",target.collection()).put("transport",target.transport().id);
    }
    private NativeMetadataTree(){}
}
