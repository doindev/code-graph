package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Native table evidence for comparison. No property editor limits or form catalogs. */
final class CompareTableMetadata {
    static ObjectNode capture(QueryJobs.Job job,Connection c,ObjectNode selection,JsonNode node)throws Exception{
        ObjectNode identity;
        if(node==null)identity=TableQueries.prepare(job,c,selection,job.remainingSeconds());
        else identity=Profiles.JSON.createObjectNode().put("database",Objects.toString(c.getCatalog(),""))
            .put("schema",node.path("schema").asText()).put("name",node.path("objectName").asText(node.path("name").asText())).put("kind","tables");
        String schema=str(identity,"schema"),name=str(identity,"name"),product=c.getMetaData().getDatabaseProductName(),engine=product.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(product);
        ObjectNode out=identity.deepCopy();out.put("engine",engine);out.set("selection",selection.deepCopy());out.put("editable",Set.of("postgresql","h2").contains(engine));out.put("reason","Editing is enabled only for validated PostgreSQL/H2 operations. Other drivers and unsupported fields remain read-only.");
        ObjectNode fields=out.putObject("fields");fields.put("name",name).put("schema",schema).put("owner","").put("comment","").put("tablespace","");
        ArrayNode columns=out.putArray("columns");String pkName="";Map<String,Integer> pk=new HashMap<>();
        try(var rs=c.getMetaData().getPrimaryKeys(c.getCatalog(),schema,name)){while(rs.next()){pk.put(rs.getString("COLUMN_NAME"),rs.getInt("KEY_SEQ"));pkName=Objects.toString(rs.getString("PK_NAME"),"");}}
        out.put("primaryKeyName",pkName);
        if(engine.equals("postgresql")){
            var table=query(job,c,"SELECT c.oid::text AS identity,c.relkind::text AS kind,c.relispartition,pg_get_userbyid(c.relowner) AS owner,COALESCE(obj_description(c.oid,'pg_class'),'') AS comment,COALESCE(t.spcname,'') AS tablespace FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_tablespace t ON t.oid=c.reltablespace WHERE n.nspname=? AND c.relname=?",schema,name);
            if(table.size()!=1)throw new IllegalArgumentException("Table no longer exists");out.put("objectIdentity",table.get(0).path("identity").asText());for(String key:List.of("owner","comment","tablespace"))fields.put(key,str(table.get(0),key));
            if(!Set.of("r","p").contains(str(table.get(0),"kind")))out.put("editable",false).put("reason","Partitioned, inherited and foreign tables require a dedicated alteration adapter.");
            if(!query(job,c,"SELECT 1 AS inherited FROM pg_inherits i JOIN pg_class child ON child.oid=i.inhrelid WHERE (inhrelid=?::oid OR inhparent=?::oid) AND NOT child.relispartition",str(out,"objectIdentity"),str(out,"objectIdentity")).isEmpty())out.put("editable",false).put("reason","Inherited/partition tables are read-only in the designer.");
            for(JsonNode col:query(job,c,"SELECT a.attnum::text AS id,a.attname AS name,format_type(a.atttypid,a.atttypmod) AS type,NOT a.attnotnull AS nullable,COALESCE(pg_get_expr(d.adbin,d.adrelid),'') AS default,COALESCE(col_description(a.attrelid,a.attnum),'') AS comment,a.attidentity::text AS identity,a.attgenerated::text AS generated FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid=?::oid AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum",str(out,"objectIdentity"))){ObjectNode row=((ObjectNode)col).deepCopy();row.put("nullable",str(col,"nullable").equals("t")||str(col,"nullable").equals("true"));row.put("pk",pk.getOrDefault(str(col,"name"),0));columns.add(row);}
            out.set("constraints",query(job,c,"SELECT oid::text AS id,conname AS name,contype::text AS kind,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid=?::oid ORDER BY oid",str(out,"objectIdentity")));
            out.set("indexes",query(job,c,"SELECT i.indexrelid::text AS id,ci.relname AS name,pg_get_indexdef(i.indexrelid) AS definition,EXISTS(SELECT 1 FROM pg_constraint k WHERE k.conindid=i.indexrelid) AS constrained FROM pg_index i JOIN pg_class ci ON ci.oid=i.indexrelid WHERE i.indrelid=?::oid ORDER BY i.indexrelid",str(out,"objectIdentity")));
            out.set("triggers",query(job,c,"SELECT oid::text AS id,tgname AS name,pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE tgrelid=?::oid AND NOT tgisinternal ORDER BY oid",str(out,"objectIdentity")));
            out.set("policies",query(job,c,"SELECT oid::text AS id,polname AS name,polcmd::text AS command,COALESCE(pg_get_expr(polqual,polrelid),'') AS using,COALESCE(pg_get_expr(polwithcheck,polrelid),'') AS check FROM pg_policy WHERE polrelid=?::oid ORDER BY oid",str(out,"objectIdentity")));
            out.set("rules",query(job,c,"SELECT oid::text AS id,rulename AS name,pg_get_ruledef(oid) AS definition FROM pg_rewrite WHERE ev_class=?::oid ORDER BY oid",str(out,"objectIdentity")));
            out.set("acl",query(job,c,"SELECT COALESCE(relacl::text,'') AS acl,relrowsecurity::text AS rowsecurity,relforcerowsecurity::text AS forced FROM pg_class WHERE oid=?::oid",str(out,"objectIdentity")));
        }else{
            String escape=c.getMetaData().getSearchStringEscape();String schemaPattern=schema.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%"),namePattern=name.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");
            try(var rs=c.getMetaData().getColumns(c.getCatalog(),schemaPattern,namePattern,null)){while(rs.next()){check(job);if(columns.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Table exceeds the comparison column allowance");String type=rs.getString("TYPE_NAME");int size=rs.getInt("COLUMN_SIZE"),scale=rs.getInt("DECIMAL_DIGITS");if(Set.of("CHARACTER VARYING","VARCHAR","CHARACTER","CHAR","BINARY VARYING","VARBINARY").contains(type)&&size>0)type+="("+size+")";else if(Set.of("NUMERIC","DECIMAL").contains(type)&&size>0)type+="("+size+","+scale+")";String col=rs.getString("COLUMN_NAME");columns.addObject().put("id",Integer.toString(rs.getInt("ORDINAL_POSITION"))).put("name",col).put("type",type).put("nullable",rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls).put("pk",pk.getOrDefault(col,0)).put("default",Objects.toString(rs.getString("COLUMN_DEF"),"")).put("comment",Objects.toString(rs.getString("REMARKS"),"")).put("identity","YES".equals(rs.getString("IS_AUTOINCREMENT"))?"d":"").put("generated","YES".equals(rs.getString("IS_GENERATEDCOLUMN"))?"s":"");}}
            if(engine.equals("h2")){
                var table=query(job,c,"SELECT REMARKS AS comment FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",schema,name);if(!table.isEmpty())fields.put("comment",str(table.get(0),"comment"));
                out.set("constraints",query(job,c,"SELECT CONSTRAINT_NAME AS id,CONSTRAINT_NAME AS name,CONSTRAINT_TYPE AS kind FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME",schema,name));
            }
        }
        limit(Profiles.JSON.writeValueAsBytes(out).length*2L,job.comparisonMetadataBytes,"Comparison table metadata");return out;
    }
    private CompareTableMetadata(){}
}
