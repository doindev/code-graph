package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Connection;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectDesigner.*;

/** Transactional, RESTRICT-only replacement with catalog-derived attributes restored explicitly. */
final class MaterializedViewChanges {
    static void capture(QueryJobs.Job job,Connection c,ObjectNode out,String oid)throws Exception{
        capture(job,c,out,oid,(sql,args)->ObjectCatalog.query(job,c,sql,args));
    }
    static void capture(QueryJobs.Job job,Connection c,ObjectNode out,String oid,ObjectCatalog.Query query)throws Exception{
        JsonNode f=out.path("fields");
        ObjectNode retained=out.putObject("replacement");
        retained.put("currentUser",query.read("SELECT current_user AS name").path(0).path("name").asText());
        retained.put("specialized",ObjectCatalog.truth(query.read("SELECT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=?::oid AND (attacl IS NOT NULL OR attoptions IS NOT NULL)) OR EXISTS(SELECT 1 FROM pg_statistic_ext WHERE stxrelid=?::oid) OR EXISTS(SELECT 1 FROM pg_depend WHERE objid=?::oid AND classid='pg_class'::regclass AND refclassid='pg_extension'::regclass) OR EXISTS(SELECT 1 FROM pg_seclabel WHERE objoid=?::oid AND classoid='pg_class'::regclass AND objsubid>0) AS specialized",oid,oid,oid,oid).path(0).path("specialized")));
        retained.set("indexAttributes",query.read("SELECT ci.relname AS name,COALESCE(obj_description(ci.oid,'pg_class'),'') AS comment,i.indisclustered AS clustered FROM pg_index i JOIN pg_class ci ON ci.oid=i.indexrelid WHERE i.indrelid=?::oid",oid));
        retained.set("columns",query.read("SELECT attname AS name,attstattarget::text AS statistics,attstorage::text AS storage,attcompression::text AS compression,COALESCE(col_description(attrelid,attnum),'') AS comment FROM pg_attribute WHERE attrelid=?::oid AND attnum>0 AND NOT attisdropped ORDER BY attnum",oid));
        retained.set("indexes",out.path("details").path("Indexes"));
        retained.set("grants",query.read("SELECT CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END AS grantee,pg_get_userbyid(a.grantor) AS grantor,a.privilege_type,a.is_grantable FROM pg_class c CROSS JOIN LATERAL aclexplode(COALESCE(c.relacl,acldefault('r',c.relowner))) a WHERE c.oid=?::oid",oid));
        retained.set("defaultGrantees",query.read("SELECT DISTINCT CASE WHEN a.grantee=0 THEN 'PUBLIC' ELSE pg_get_userbyid(a.grantee) END AS grantee FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) a WHERE d.defaclrole=(SELECT oid FROM pg_roles WHERE rolname=current_user) AND d.defaclobjtype='r' AND (d.defaclnamespace=0 OR d.defaclnamespace=(SELECT oid FROM pg_namespace WHERE nspname=?))",str(f,"schema")));
        retained.set("securityLabels",query.read("SELECT provider,label FROM pg_seclabel WHERE classoid='pg_class'::regclass AND objoid=?::oid AND objsubid=0",oid));
        retained.set("relation",query.read("SELECT am.amname AS method,c.relispopulated,c.reloptions::text AS options FROM pg_class c LEFT JOIN pg_am am ON am.oid=c.relam WHERE c.oid=?::oid",oid).path(0));
        ((ObjectNode)f).put("accessMethod",retained.path("relation").path("method").asText("heap"));
        ((ObjectNode)f).put("columnNames",String.join("\n",retained.path("columns").findValuesAsText("name")));
        detail(out,"Permissions",retained.path("grants"));
        detail(out,"Storage",retained.path("relation"));
    }
    static List<String> replace(ObjectNode snapshot,JsonNode f){
        JsonNode retained=snapshot.path("replacement");if(!retained.isObject())throw new IllegalArgumentException("Reload the materialized view before replacing its definition");
        if(retained.path("specialized").asBoolean())throw new IllegalArgumentException("This view has column grants/options, extended statistics, extension dependencies or column security labels. Use DDL to preserve these specialized attributes during replacement.");
        if(!str(retained,"currentUser").equals(str(snapshot.path("fields"),"owner")))throw new IllegalArgumentException("Use DDL to replace a materialized view owned by a different role without changing grant ownership.");
        for(JsonNode grant:retained.path("grants"))if(!str(grant,"grantor").equals(str(retained,"currentUser")))throw new IllegalArgumentException("This view has grants from another grantor; use DDL to preserve their ownership.");
        String target=ObjectForms.qualified("postgresql",str(snapshot.path("fields"),"schema"),str(snapshot.path("fields"),"name"));
        List<String> sql=new ArrayList<>();sql.add("DROP MATERIALIZED VIEW "+target+" RESTRICT");
        String columns=String.join(", ",str(f,"columnNames").lines().map(x->ObjectForms.q("postgresql",x)).toList());
        sql.add("CREATE MATERIALIZED VIEW "+target+(columns.isBlank()?"":" ("+columns+")")+" USING "+ObjectForms.q("postgresql",str(f,"accessMethod").isBlank()?"heap":str(f,"accessMethod"))
                +(str(f,"options").isBlank()?"":" WITH ("+ObjectForms.options(str(f,"options"))+")")
                +(str(f,"tablespace").isBlank()?"":" TABLESPACE "+ObjectForms.q("postgresql",str(f,"tablespace")))
                +" AS\n"+ObjectForms.fragment(str(f,"query"))+(f.path("populate").asBoolean()?"\nWITH DATA":"\nWITH NO DATA"));
        for(JsonNode index:retained.path("indexes"))sql.add(str(index,"definition"));
        for(JsonNode index:retained.path("indexAttributes")){
            if(!str(index,"comment").isBlank())sql.add("COMMENT ON INDEX "+ObjectForms.qualified("postgresql",str(snapshot.path("fields"),"schema"),str(index,"name"))+" IS "+TableDesigner.literal(str(index,"comment")));
            if(ObjectCatalog.truth(index.path("clustered")))sql.add("ALTER MATERIALIZED VIEW "+target+" CLUSTER ON "+ObjectForms.q("postgresql",str(index,"name")));
        }
        for(JsonNode column:retained.path("columns")){
            String name=ObjectForms.q("postgresql",str(column,"name"));
            if(!str(column,"comment").isBlank())sql.add("COMMENT ON COLUMN "+target+"."+name+" IS "+TableDesigner.literal(str(column,"comment")));
            if(!str(column,"statistics").equals("-1"))sql.add("ALTER MATERIALIZED VIEW "+target+" ALTER COLUMN "+name+" SET STATISTICS "+ObjectForms.integer(str(column,"statistics")));
            String storage=Map.of("p","PLAIN","e","EXTERNAL","m","MAIN","x","EXTENDED").get(str(column,"storage"));
            if(storage!=null)sql.add("ALTER MATERIALIZED VIEW "+target+" ALTER COLUMN "+name+" SET STORAGE "+storage);
            if(!str(column,"compression").isBlank())sql.add("ALTER MATERIALIZED VIEW "+target+" ALTER COLUMN "+name+" SET COMPRESSION "+(str(column,"compression").equals("l")?"lz4":"pglz"));
        }
        Set<String> revoke=new LinkedHashSet<>();revoke.add("PUBLIC");revoke.add(str(retained,"currentUser"));for(JsonNode grant:retained.path("defaultGrantees"))revoke.add(str(grant,"grantee"));
        for(String grantee:revoke)sql.add("REVOKE ALL ON TABLE "+target+" FROM "+role(grantee));
        for(JsonNode grant:retained.path("grants")){
            // Preserve explicit owner revocations too; ownership itself always retains grant authority.
            String privilege=str(grant,"privilege_type");
            if(!Set.of("SELECT","INSERT","UPDATE","DELETE","TRUNCATE","REFERENCES","TRIGGER","MAINTAIN").contains(privilege))throw new IllegalArgumentException("This server returned an unfamiliar privilege; use native DDL to preserve it");
            sql.add("GRANT "+privilege+" ON TABLE "+target+" TO "+role(str(grant,"grantee"))+(ObjectCatalog.truth(grant.path("is_grantable"))?" WITH GRANT OPTION":""));
        }
        for(JsonNode label:retained.path("securityLabels"))sql.add("SECURITY LABEL FOR "+ObjectForms.q("postgresql",str(label,"provider"))+" ON MATERIALIZED VIEW "+target+" IS "+TableDesigner.literal(str(label,"label")));
        if(!str(f,"comment").isBlank())sql.add("COMMENT ON MATERIALIZED VIEW "+target+" IS "+TableDesigner.literal(str(f,"comment")));
        return sql;
    }
    static String role(String role){return role.equals("PUBLIC")?"PUBLIC":ObjectForms.q("postgresql",role);}
    private MaterializedViewChanges(){}
}
