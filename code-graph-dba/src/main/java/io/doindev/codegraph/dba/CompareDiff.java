package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Immutable review evidence; selections project only reviewed changes into the desired schema. */
final class CompareDiff {
    record Change(String id,String section,String name,String attribute,JsonNode before,JsonNode after,boolean destructive){
        ObjectNode json(){ObjectNode n=Profiles.JSON.createObjectNode().put("id",id).put("section",section).put("name",name).put("attribute",attribute).put("destructive",destructive);
            n.put("action",before==null?"create":after==null?"remove":"alter");if(before!=null)n.set("before",before);if(after!=null)n.set("after",after);return n;}
    }
    static final class ObjectDiff {
        final String id,key,status;final ObjectNode source,destination;final List<Change> changes;
        ObjectDiff(String id,String key,String status,ObjectNode source,ObjectNode destination,List<Change> changes){this.id=id;this.key=key;this.status=status;this.source=source;this.destination=destination;this.changes=List.copyOf(changes);}
        ObjectNode json(boolean details){
            JsonNode o=source==null?destination:source;ObjectNode n=Profiles.JSON.createObjectNode().put("id",id).put("name",str(o,"name")).put("schema",str(o,"schema")).put("kind",str(o,"kind")).put("status",status).put("supported",source!=null&&source.path("supported").asBoolean()).put("reason",str(o,"reason")).put("dataSupported",source!=null&&source.path("dataSupported").asBoolean());
            n.set("stateModes",o.path("stateModes"));n.set("keys",o.path("keys"));ArrayNode list=n.putArray("changes");changes.forEach(c->list.add(c.json()));
            if(details){if(source!=null)n.set("source",source);if(destination!=null)n.set("destination",destination);}return n;
        }
    }
    final Inventory source,destination;final Target from,to;final Map<String,ObjectDiff> objects=new LinkedHashMap<>();final String revision=UUID.randomUUID().toString();
    final CompareSql.Plan mapping;
    CompareDiff(Inventory source,Inventory destination,Target from,Target to)throws Exception{
        this.source=source;this.destination=destination;this.from=from;this.to=to;mapping=new CompareSql.Plan(source,destination,from,to,Profiles.JSON.createObjectNode());
        Set<String> matched=new HashSet<>();
        for(var entry:source.objects.entrySet()){
            ObjectNode a=entry.getValue();String destKey=key(mapping.schema(str(a,"schema")),str(a,"kind"),str(a,"name"));ObjectNode b=destination.objects.get(destKey);if(b!=null)matched.add(destKey);
            List<Change> changes=new ArrayList<>();
            if(!a.path("implicit").asBoolean()){
                if(b==null)add(changes,a,"object",str(a,"name"),"",null,a,false);
                else if(str(a,"kind").equals("tables")){
                    compareColumns(changes,a,b);
                    for(String section:List.of("constraints","foreignKeys","indexes"))compareNamed(changes,a,section,a.path(section),b.path(section));
                    if(a.has("nativeDdl")&&!CompareSql.normalizeMysql(mapping.mapped(str(a,"nativeDdl"))).equals(CompareSql.normalizeMysql(str(b,"nativeDdl"))))add(changes,a,"nativeDdl",str(a,"name"),"",b.path("nativeDdl"),a.path("nativeDdl"),true);
                    if(!clean(a.path("triggers"),true).equals(clean(b.path("triggers"),false)))add(changes,a,"triggers",str(a,"name"),"",b.path("triggers"),a.path("triggers"),false);
                }else if(!CompareSql.same(mapping,a,b))add(changes,a,"object",str(a,"name"),"",b,a,false);
            }
            String status=!a.path("supported").asBoolean()?"unsupported":b==null?"source_only":changes.isEmpty()?"identical":"different";
            objects.put(str(a,"id"),new ObjectDiff(str(a,"id"),entry.getKey(),status,a,b,changes));
        }
        for(var entry:destination.objects.entrySet())if(!matched.contains(entry.getKey())){
            ObjectNode b=entry.getValue();String id="destination-"+str(b,"id");objects.put(id,new ObjectDiff(id,entry.getKey(),"destination_only",null,b,List.of()));
        }
    }
    private JsonNode clean(JsonNode n,boolean sourceSide){
        JsonNode copy=n.deepCopy();cleanNode(copy,sourceSide);CompareSql.canonicalNode(copy);return copy;
    }
    private void cleanNode(JsonNode node,boolean sourceSide){
        if(node.isObject()){ObjectNode obj=(ObjectNode)node;obj.remove(List.of("id","oid","identityBase"));List<String> names=new ArrayList<>();obj.fieldNames().forEachRemaining(names::add);
            for(String name:names){JsonNode value=obj.get(name);
                if(sourceSide&&value.isTextual()&&name.equals("schema"))obj.put(name,mapping.schema(value.asText()));
                else if(sourceSide&&value.isTextual())obj.put(name,mapping.mapped(value.asText()));
                else cleanNode(value,sourceSide);}}
        else if(node.isArray())for(JsonNode child:node)cleanNode(child,sourceSide);
    }
    private void compareColumns(List<Change> changes,ObjectNode a,ObjectNode b)throws Exception{
        Map<String,JsonNode> before=CompareSql.byName(b.path("columns")),after=CompareSql.byName(a.path("columns"));
        Set<String> names=new LinkedHashSet<>(after.keySet());names.addAll(before.keySet());
        for(String name:names){JsonNode old=before.get(name),next=after.get(name);
            if(old==null||next==null){add(changes,a,"columns",name,"",old,next,next==null);continue;}
            for(String field:List.of("type","nullable","default","identity","identityOptions","generated","comment")){
                JsonNode x=old.get(field),y=next.get(field);if(x==null&&y==null)continue;
                JsonNode xc=x==null?NullNode.instance:clean(x,false),yc=y==null?NullNode.instance:clean(y,true);
                if(yc.isTextual())yc=Profiles.JSON.getNodeFactory().textNode(mapping.mapped(yc.asText()));
                if(CompareSql.SQL_FIELDS.contains(field)){if(xc.isTextual())xc=Profiles.JSON.getNodeFactory().textNode(CompareSql.canonical(xc.asText()));if(yc.isTextual())yc=Profiles.JSON.getNodeFactory().textNode(CompareSql.canonical(yc.asText()));}
                if(!xc.equals(yc))add(changes,a,"columns",name,field,x,y,Set.of("type","identity","generated").contains(field)||field.equals("nullable")&&!next.path("nullable").asBoolean());
            }
        }
    }
    private void compareNamed(List<Change> changes,ObjectNode owner,String section,JsonNode a,JsonNode b)throws Exception{
        Map<String,JsonNode> before=CompareSql.byName(b),after=CompareSql.byName(a);Set<String> names=new TreeSet<>(after.keySet());names.addAll(before.keySet());
        for(String name:names){JsonNode old=before.get(name),next=after.get(name);if(old!=null&&next!=null&&clean(next,true).equals(clean(old,false)))continue;
            if(section.equals("indexes")&&(old!=null&&old.path("implicit").asBoolean()||next!=null&&next.path("implicit").asBoolean()))continue;
            add(changes,owner,section,name,"",old,next,next==null);}
    }
    private static void add(List<Change> changes,ObjectNode object,String section,String name,String attribute,JsonNode before,JsonNode after,boolean destructive)throws Exception{
        String id=TableDesigner.hash(Profiles.JSON.createArrayNode().add(str(object,"id")).add(section).add(name).add(attribute)).substring(0,32);
        changes.add(new Change(id,section,name,attribute,before,after,destructive));
    }
    ObjectNode results(int offset,int limit,String query,String status){
        ObjectNode result=Profiles.JSON.createObjectNode().put("revision",revision).put("engine",source.engine);ObjectNode counts=result.putObject("counts");
        for(ObjectDiff o:objects.values())counts.put(o.status,counts.path(o.status).asInt()+1);
        List<ObjectDiff> filtered=objects.values().stream().filter(o->status.isEmpty()||o.status.equals(status)).filter(o->query.isEmpty()||(str(o.source==null?o.destination:o.source,"name")+" "+str(o.source==null?o.destination:o.source,"schema")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        ArrayNode list=result.putArray("objects");for(int i=offset;i<Math.min(filtered.size(),offset+limit);i++)list.add(filtered.get(i).json(false));
        result.put("total",filtered.size());if(offset+limit<filtered.size())result.put("nextOffset",offset+limit);return result;
    }
    CompareSql.Plan plan(JsonNode request)throws Exception{
        if(!revision.equals(str(request,"revision")))throw new IllegalArgumentException("Comparison revision changed");
        Inventory projected=new Inventory(source.engine,source.database,source.version);projected.schemas.addAll(source.schemas);projected.objects.putAll(source.objects);
        ObjectNode translated=request.deepCopy();ArrayNode selected=translated.putArray("objects");Set<String> seen=new HashSet<>();
        for(JsonNode selection:request.path("objects")){
            String id=str(selection,"id");ObjectDiff object=objects.get(id);if(object==null||object.source==null||!seen.add(id))throw new IllegalArgumentException("Invalid object selection");
            Set<String> ids=new HashSet<>();selection.path("changes").forEach(n->ids.add(n.asText()));Set<String> known=new HashSet<>();object.changes.forEach(c->known.add(c.id));
            if(!known.containsAll(ids))throw new IllegalArgumentException("Unknown change selection");
            ObjectNode desired=project(object,ids);projected.objects.put(object.key,desired);
            ObjectNode option=selection.deepCopy();option.remove("changes");selected.add(option);
        }
        return CompareSql.prepare(projected,destination,from,to,translated);
    }
    private ObjectNode project(ObjectDiff object,Set<String> chosen){
        if(object.destination==null){if(object.changes.isEmpty()||!chosen.contains(object.changes.getFirst().id))throw new IllegalArgumentException("Select creation of "+str(object.source,"name"));return object.source;}
        if(!str(object.source,"kind").equals("tables")){ObjectNode result=object.changes.isEmpty()||chosen.contains(object.changes.getFirst().id)?object.source:reverse(object.destination);result.put("id",object.id);return result;}
        ObjectNode result=reverse(object.destination);result.put("id",object.id);result.set("dependencies",object.source.path("dependencies"));result.set("selection",object.source.path("selection"));
        for(Change change:object.changes)if(chosen.contains(change.id)){
            if(Set.of("columns","constraints","foreignKeys","indexes").contains(change.section)){
                ArrayNode array=(ArrayNode)result.path(change.section);int index=-1;for(int i=0;i<array.size();i++)if(str(array.get(i),"name").equals(change.name)){index=i;break;}
                if(change.attribute.isEmpty()){if(index>=0)array.remove(index);if(change.after!=null){if(index<0)array.add(change.after.deepCopy());else array.insert(index,change.after.deepCopy());}}
                else{if(index<0)throw new IllegalArgumentException("Column change requires creating "+change.name);ObjectNode column=(ObjectNode)array.get(index);if(change.after==null)column.remove(change.attribute);else column.set(change.attribute,change.after.deepCopy());}
            }else if(change.after!=null)result.set(change.section,change.after.deepCopy());
        }
        result.set("keys",object.source.path("keys"));return result;
    }
    private ObjectNode reverse(ObjectNode destinationObject){ObjectNode result=destinationObject.deepCopy();if(!from.allSchemas())reverseNode(result);return result;}
    private void reverseNode(JsonNode node){
        if(node.isObject()){ObjectNode obj=(ObjectNode)node;List<String> fields=new ArrayList<>();obj.fieldNames().forEachRemaining(fields::add);for(String field:fields){JsonNode value=obj.get(field);if(value.isTextual()&&field.equals("schema")&&value.asText().equals(to.schema()))obj.put(field,from.schema());else if(value.isTextual()&&Set.of("ddl","nativeDdl","query","default","definition","type","table","ownedBy").contains(field))obj.put(field,CompareSql.remap(value.asText(),Map.of(to.schema(),from.schema())));else reverseNode(value);}}
        else if(node.isArray())for(JsonNode child:node)reverseNode(child);
    }
}
