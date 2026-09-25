package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Connection;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** One bounded dependency read replaces an individual pg_depend query for every object. */
final class ComparePostgresScope {
    final Map<String,SortedSet<String>> edges=new TreeMap<>();
    final Map<String,String> outside=new HashMap<>();
    final Map<String,SortedSet<String>> scopeEdges=new TreeMap<>();
    /** Only identities are enumerated here; native definitions are read after dependency closure. */
    static void roster(QueryJobs.Job job,Connection c,Inventory inventory,Map<String,CatalogObject> catalog)throws Exception{
        for(String schema:inventory.schemas){
            job.comparisonProgress("Listing object identities",job.comparisonProgress==null?"":job.comparisonProgress.path("side").asText(),schema,catalog.size(),0);
            for(JsonNode row:query(job,c,"""
                SELECT c.oid::text AS oid,c.relname AS name,CASE c.relkind
                  WHEN 'r' THEN 'tables' WHEN 'p' THEN 'tables' WHEN 'f' THEN 'foreign_tables'
                  WHEN 'v' THEN 'views' WHEN 'm' THEN 'materialized_views' WHEN 'S' THEN 'sequences'
                  ELSE 'indexes' END AS kind,
                  EXISTS(SELECT 1 FROM pg_constraint k WHERE k.conindid=c.oid) AS managed
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relkind IN ('r','p','f','v','m','S','i','I')
                UNION ALL
                SELECT p.oid::text,p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')',
                  CASE p.prokind WHEN 'p' THEN 'procedures' WHEN 'a' THEN 'aggregates' ELSE 'functions' END,false
                FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=?
                UNION ALL
                SELECT t.oid::text,t.typname,'types',t.typtype IN ('c','b') OR t.typelem<>0 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace
                WHERE n.nspname=? AND t.typisdefined
                """,schema,schema,schema)){
                if(catalog.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Comparison identity scope exceeds 10,000 objects; select fewer schemas");
                String kind=str(row,"kind"),name=str(row,"name"),oid=str(row,"oid"),nodeKind=Set.of("tables","views","materialized_views","foreign_tables").contains(kind)?"relation":"object";
                ObjectNode node=Profiles.JSON.createObjectNode().put("kind",nodeKind).put("schema",schema).put("name",name).put("oid",oid).put("key",nodeKind+":"+oid);
                ObjectNode selection=Profiles.JSON.createObjectNode().put("key",str(node,"key"));selection.putObject("parent").put("kind",kind).put("database",inventory.database).put("schema",schema);
                ObjectNode object=item(schema,kind,name).put("oid",oid);if(truth(row.path("managed")))object.put("implicit",true).put("reason","Implicit object is managed with its owning table or type");object.set("selection",selection);
                catalog.put(key(schema,kind,name),new CatalogObject(object,selection,node));
            }
        }
    }
    static ComparePostgresScope read(QueryJobs.Job job,Connection connection,Map<String,CatalogObject> catalog)throws Exception {
        ComparePostgresScope scope=new ComparePostgresScope();Map<String,String> identities=new HashMap<>();
        Map<String,List<String>> ids=new HashMap<>();for(String kind:List.of("pg_class","pg_proc","pg_type"))ids.put(kind,new ArrayList<>());
        for(var entry:catalog.entrySet()){
            ObjectNode object=entry.getValue().object();String oid=str(object,"oid"),kind=pgClass(object);
            if(!oid.matches("[0-9]+"))continue;identities.put(kind+":"+oid,entry.getKey());ids.get(kind).add(oid);
        }
        var classes=connection.createArrayOf("oid",ids.get("pg_class").toArray());
        var routines=connection.createArrayOf("oid",ids.get("pg_proc").toArray());
        var types=connection.createArrayOf("oid",ids.get("pg_type").toArray());
        try {
            ArrayNode rows=query(job,connection,"""
                WITH dependencies AS (
                  SELECT CASE WHEN d.classid IN ('pg_rewrite'::regclass,'pg_attrdef'::regclass,'pg_constraint'::regclass,'pg_trigger'::regclass)
                              THEN 'pg_class'::regclass ELSE d.classid END AS owner_class,
                         COALESCE(r.ev_class,a.adrelid,NULLIF(k.conrelid,0),g.tgrelid,d.objid) AS owner_oid,d.refclassid,d.refobjid,d.deptype
                  FROM pg_depend d
                  LEFT JOIN pg_rewrite r ON d.classid='pg_rewrite'::regclass AND r.oid=d.objid
                  LEFT JOIN pg_attrdef a ON d.classid='pg_attrdef'::regclass AND a.oid=d.objid
                  LEFT JOIN pg_constraint k ON d.classid='pg_constraint'::regclass AND k.oid=d.objid
                  LEFT JOIN pg_trigger g ON d.classid='pg_trigger'::regclass AND g.oid=d.objid
                  WHERE d.deptype IN ('n','a') AND d.refclassid IN ('pg_class'::regclass,'pg_proc'::regclass,'pg_type'::regclass)
                    AND ((d.classid='pg_class'::regclass AND d.objid=ANY(?))
                      OR (d.classid='pg_proc'::regclass AND d.objid=ANY(?))
                      OR (d.classid='pg_type'::regclass AND d.objid=ANY(?))
                      OR r.ev_class=ANY(?) OR a.adrelid=ANY(?) OR k.conrelid=ANY(?) OR g.tgrelid=ANY(?)
                      OR (d.refclassid='pg_class'::regclass AND d.refobjid=ANY(?))
                      OR (d.refclassid='pg_proc'::regclass AND d.refobjid=ANY(?))
                      OR (d.refclassid='pg_type'::regclass AND d.refobjid=ANY(?)))
                )
                SELECT DISTINCT d.owner_class::regclass::text AS owner_class,d.owner_oid::text AS owner_oid,
                  d.refclassid::regclass::text AS catalog,d.refobjid::text AS oid,
                  COALESCE(cn.nspname,pn.nspname,tn.nspname) AS schema,d.deptype::text AS dependency_type
                FROM dependencies d
                LEFT JOIN pg_class c ON d.refclassid='pg_class'::regclass AND c.oid=d.refobjid
                LEFT JOIN pg_namespace cn ON cn.oid=c.relnamespace
                LEFT JOIN pg_proc p ON d.refclassid='pg_proc'::regclass AND p.oid=d.refobjid
                LEFT JOIN pg_namespace pn ON pn.oid=p.pronamespace
                LEFT JOIN pg_type t ON d.refclassid='pg_type'::regclass AND t.oid=d.refobjid
                LEFT JOIN pg_namespace tn ON tn.oid=t.typnamespace
                """,classes,routines,types,classes,classes,classes,classes,classes,routines,types);
            for(JsonNode row:rows){
                String owner=identities.get(str(row,"owner_class")+":"+str(row,"owner_oid"));
                String reference=identities.get(str(row,"catalog")+":"+str(row,"oid"));
                if(owner==null){
                    if(reference!=null&&Set.of("pg_class","pg_proc","pg_type").contains(str(row,"owner_class")))scope.outside.put(reference,"incoming "+str(row,"owner_class")+" object "+str(row,"owner_oid"));
                    continue;
                }
                if(reference==null){if(!system(str(row,"schema")))scope.outside.put(owner,str(row,"schema"));}
                else if(!owner.equals(reference)){
                    scope.scopeEdges.computeIfAbsent(owner,k->new TreeSet<>()).add(reference);
                    // Ownership and FK edges expand evidence; FK staging and sequence ownership
                    // are already ordered separately by the generator and must not create false cycles.
                    if(str(row,"dependency_type").equals("n")&&!(str(catalog.get(owner).object(),"kind").equals("tables")&&str(catalog.get(reference).object(),"kind").equals("tables")))
                        scope.edges.computeIfAbsent(owner,k->new TreeSet<>()).add(reference);
                }
            }
        }finally{classes.free();routines.free();types.free();}
        return scope;
    }
    Set<String> closure(Map<String,CatalogObject> catalog,Set<String> selectedKinds){
        return closure(catalog,entry->selectedKinds.isEmpty()||selectedKinds.contains(str(entry.object(),"kind")));
    }
    Set<String> closure(Map<String,CatalogObject> catalog,java.util.function.Predicate<CatalogObject> selected){
        Map<String,Set<String>> adjacent=new HashMap<>();
        java.util.stream.Stream.of(edges,scopeEdges).forEach(graph->graph.forEach((owner,refs)->{for(String ref:refs){adjacent.computeIfAbsent(owner,k->new HashSet<>()).add(ref);adjacent.computeIfAbsent(ref,k->new HashSet<>()).add(owner);}}));
        SortedSet<String> included=new TreeSet<>();ArrayDeque<String> pending=new ArrayDeque<>();
        catalog.forEach((identity,entry)->{if(selected.test(entry)){included.add(identity);pending.add(identity);}});
        while(!pending.isEmpty())for(String dependency:adjacent.getOrDefault(pending.removeFirst(),Set.of()))if(included.add(dependency))pending.addLast(dependency);
        return included;
    }
    void apply(Inventory inventory){
        for(var entry:inventory.objects.entrySet()){
            ObjectNode object=entry.getValue();if(object.path("implicit").asBoolean())continue;
            ArrayNode dependencies=object.putArray("dependencies");edges.getOrDefault(entry.getKey(),new TreeSet<>()).forEach(dependencies::add);
            if(outside.containsKey(entry.getKey()))object.put("supported",false).put("reason","Dependency outside the comparison scope: "+outside.get(entry.getKey())+" (include its schema)");
        }
    }
}
