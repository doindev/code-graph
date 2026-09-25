package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Connection;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Identity generators are paired by owning column and changed only through ALTER TABLE. */
final class OracleIdentitySequences {
    private OracleIdentitySequences(){}
    static void roster(QueryJobs.Job job,Connection c,String owner,Map<String,ObjectNode> objects,Map<String,String> aliases)throws Exception{
        for(JsonNode row:query(job,c,"SELECT table_name,column_name,sequence_name,generation_type FROM SYS.ALL_TAB_IDENTITY_COLS WHERE owner=? ORDER BY table_name,column_name",owner)){
            String physical=key(owner,"sequences",str(row,"sequence_name"));ObjectNode original=objects.remove(physical);
            if(original==null)throw new IllegalArgumentException("Identity sequence metadata is unavailable for "+owner+"."+str(row,"table_name"));
            String name="Identity "+OracleDialect.qualified(str(row,"table_name"),str(row,"column_name")),logical=key(owner,"sequences",name);
            if(objects.containsKey(logical))throw new IllegalArgumentException("Sequence name conflicts with the identity review name: "+name);
            original.put("name",name).put("identity",true).put("implicit",true).put("oracleSequenceName",str(row,"sequence_name"));
            original.putObject("ownership").put("schema",owner).put("table",str(row,"table_name")).put("column",str(row,"column_name")).put("generation",str(row,"generation_type"));
            original.withArray("dependencies").add(key(owner,"tables",str(row,"table_name")));objects.put(logical,original);aliases.put(physical,logical);
        }
    }
    static void capture(QueryJobs.Job job,Connection c,Inventory inventory,ObjectNode object)throws Exception{
        String owner=str(object,"schema"),name=str(object,"oracleSequenceName");
        var rows=query(job,c,"SELECT min_value,max_value,increment_by,cycle_flag,order_flag,cache_size,last_number,scale_flag,extend_flag,sharded_flag,session_flag,keep_value FROM SYS.ALL_SEQUENCES WHERE sequence_owner=? AND sequence_name=?",owner,name);
        if(rows.size()!=1)throw new IllegalArgumentException("Identity sequence metadata is unavailable");
        ObjectNode fields=rows.path(0).deepCopy();JsonNode boundary=fields.remove("last_number");object.set("fields",fields);
        // LAST_NUMBER is already returned by this catalog read; retain it so review can enable synchronization.
            object.putObject("state").set("value",boundary);object.withObject("state").put("observation",fields.path("cache_size").asInt()==0?"uncached_catalog_boundary":"cache_boundary");
        boolean ordinary=str(fields,"cycle_flag").equals("N")&&str(fields,"scale_flag").equals("N")&&str(fields,"sharded_flag").equals("N")&&str(fields,"session_flag").equals("N");
        var modes=object.putArray("stateModes");if(ordinary)modes.add("advance");
        object.put("stateReason",ordinary?"Advance the owning identity column to a catalog/cache boundary; source NEXTVAL is never evaluated":"Cyclic, scalable, sharded or session identity values require manual synchronization");
        object.put("supported",true).put("reason","Definition is managed by the owning table").put("ddl","-- Identity generator managed by "+OracleDialect.qualified(owner,str(object.path("ownership"),"table"))+" column "+OracleDialect.identifier(str(object.path("ownership"),"column")));
    }
    static String advance(CompareSql.Plan plan,CompareSql.Choice choice,java.math.BigInteger next){
        JsonNode source=choice.source(),ownership=source.path("ownership"),fields=source.path("fields");
        String tableKey=key(str(ownership,"schema"),"tables",str(ownership,"table"));ObjectNode table=plan.source.objects.get(tableKey);var selected=plan.selected.get(tableKey);
        if(table==null||!table.path("supported").asBoolean())throw new IllegalArgumentException("Identity synchronization requires the supported owning table");
        if(!table.path("oracleChanges").isEmpty()&&(selected==null||selected.source().path("oracleSelectedChanges").size()!=table.path("oracleChanges").size()))throw new IllegalArgumentException("Include all reviewed owning-table changes before synchronizing "+str(source,"name"));
        String cache=CompareSql.integer(str(fields,"cache_size")).signum()==0?" NOCACHE":" CACHE "+number(fields,"cache_size");
        return "ALTER TABLE "+OracleDialect.qualified(plan.schema(str(ownership,"schema")),str(ownership,"table"))+" MODIFY ("+OracleDialect.identifier(str(ownership,"column"))+" GENERATED AS IDENTITY (START WITH "+next+" INCREMENT BY "+number(fields,"increment_by")+" MINVALUE "+number(fields,"min_value")+" MAXVALUE "+number(fields,"max_value")+cache+(str(fields,"order_flag").equals("Y")?" ORDER":" NOORDER")+" NOCYCLE"+(str(fields,"keep_value").equals("Y")?" KEEP":" NOKEEP")+" NOSCALE))";
    }
    private static String number(JsonNode value,String key){return CompareSql.integer(str(value,key)).toString();}
}
