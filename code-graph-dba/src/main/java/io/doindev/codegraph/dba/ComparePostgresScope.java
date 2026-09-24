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
                  SELECT CASE WHEN d.classid IN ('pg_rewrite'::regclass,'pg_attrdef'::regclass)
                              THEN 'pg_class'::regclass ELSE d.classid END AS owner_class,
                         COALESCE(r.ev_class,a.adrelid,d.objid) AS owner_oid,d.refclassid,d.refobjid
                  FROM pg_depend d
                  LEFT JOIN pg_rewrite r ON d.classid='pg_rewrite'::regclass AND r.oid=d.objid
                  LEFT JOIN pg_attrdef a ON d.classid='pg_attrdef'::regclass AND a.oid=d.objid
                  WHERE d.deptype='n' AND d.refclassid IN ('pg_class'::regclass,'pg_proc'::regclass,'pg_type'::regclass)
                    AND ((d.classid='pg_class'::regclass AND d.objid=ANY(?))
                      OR (d.classid='pg_proc'::regclass AND d.objid=ANY(?))
                      OR (d.classid='pg_type'::regclass AND d.objid=ANY(?))
                      OR r.ev_class=ANY(?) OR a.adrelid=ANY(?))
                )
                SELECT DISTINCT d.owner_class::regclass::text AS owner_class,d.owner_oid::text AS owner_oid,
                  d.refclassid::regclass::text AS catalog,d.refobjid::text AS oid,
                  COALESCE(cn.nspname,pn.nspname,tn.nspname) AS schema
                FROM dependencies d
                LEFT JOIN pg_class c ON d.refclassid='pg_class'::regclass AND c.oid=d.refobjid
                LEFT JOIN pg_namespace cn ON cn.oid=c.relnamespace
                LEFT JOIN pg_proc p ON d.refclassid='pg_proc'::regclass AND p.oid=d.refobjid
                LEFT JOIN pg_namespace pn ON pn.oid=p.pronamespace
                LEFT JOIN pg_type t ON d.refclassid='pg_type'::regclass AND t.oid=d.refobjid
                LEFT JOIN pg_namespace tn ON tn.oid=t.typnamespace
                """,classes,routines,types,classes,classes);
            for(JsonNode row:rows){
                String owner=identities.get(str(row,"owner_class")+":"+str(row,"owner_oid"));if(owner==null)continue;
                String reference=identities.get(str(row,"catalog")+":"+str(row,"oid"));
                if(reference==null){if(!system(str(row,"schema")))scope.outside.put(owner,str(row,"schema"));}
                else if(!owner.equals(reference))scope.edges.computeIfAbsent(owner,k->new TreeSet<>()).add(reference);
            }
        }finally{classes.free();routines.free();types.free();}
        return scope;
    }
    Set<String> closure(Map<String,CatalogObject> catalog,Set<String> selectedKinds){
        if(selectedKinds.isEmpty())return new TreeSet<>(catalog.keySet());
        Map<String,Set<String>> adjacent=new HashMap<>();
        edges.forEach((owner,refs)->{for(String ref:refs){adjacent.computeIfAbsent(owner,k->new HashSet<>()).add(ref);adjacent.computeIfAbsent(ref,k->new HashSet<>()).add(owner);}});
        SortedSet<String> included=new TreeSet<>();ArrayDeque<String> pending=new ArrayDeque<>();
        catalog.forEach((identity,entry)->{if(selectedKinds.contains(str(entry.object(),"kind"))){included.add(identity);pending.add(identity);}});
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
