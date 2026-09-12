package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Bounded, on-demand function signatures. No catalog cache is sent to the compiler. */
final class VisualFunctions {
    static ObjectNode read(QueryJobs.Job job, Connection c, JsonNode request) throws Exception {
        String schema = request.path("schema").asText(""), search = request.path("search").asText(""), key = request.path("key").asText("");
        if (schema.length() > 2048 || search.length() > 256 || key.length() > 2048) throw VisualQuery.invalid("Function search is too long");
        int offset = request.path("offset").asInt(0); if (offset < 0 || offset > 100000) throw VisualQuery.invalid("Invalid function page");
        if (c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL") && c.getMetaData().getDatabaseMajorVersion() >= 11) return postgres(job, c, schema, search, key, offset);
        ObjectNode out = Profiles.JSON.createObjectNode(); ArrayNode functions = out.putArray("functions"); DatabaseMetaData m = c.getMetaData();
        try (ResultSet rs = m.getFunctions(c.getCatalog(), schema.isBlank() ? null : pattern(m, schema), "%" + pattern(m, search) + "%")) {
            int skipped = 0;
            while (rs.next()) {
                if (job.cancelled) throw new java.util.concurrent.CancellationException();
                String specific = rs.getString("SPECIFIC_NAME"), name = rs.getString("FUNCTION_NAME"), namespace = Objects.toString(rs.getString("FUNCTION_SCHEM"), "");
                String identity = namespace + ":" + Objects.toString(specific, name);
                if (!key.isEmpty() && !key.equals(identity)) continue;
                if (key.isEmpty() && skipped++ < offset) continue;
                if (functions.size() >= 200) { out.put("truncated", true).put("nextOffset", offset + 200); break; }
                ObjectNode f = functions.addObject().put("key", identity).put("schema", namespace).put("name", name).put("signature", Objects.toString(specific, name)).put("aggregate", false);
                boolean available = rs.getShort("FUNCTION_TYPE") == DatabaseMetaData.functionNoTable;
                f.put("available", available).put("signatureRequired", true);
                if (!available) f.put("reason", "The driver does not identify this as a scalar function. Table-returning functions and unknown kinds are unavailable.");
                if (!key.isEmpty() && available) signature(m, c.getCatalog(), namespace, name, specific, f);
            }
        } catch (SQLFeatureNotSupportedException | AbstractMethodError e) { out.put("notice", "This driver does not expose JDBC function signatures. Common visual functions remain available."); }
        return out;
    }
    static String pattern(DatabaseMetaData m, String value) throws SQLException { String esc=m.getSearchStringEscape(); if(esc==null||esc.isEmpty()) { if(value.contains("%")||value.contains("_")) throw VisualQuery.invalid("Driver cannot escape this metadata search"); return value; } return value.replace(esc,esc+esc).replace("%",esc+"%").replace("_",esc+"_"); }
    static void signature(DatabaseMetaData m, String catalog, String schema, String name, String specific, ObjectNode f) throws SQLException {
        TreeMap<Integer,ObjectNode> args = new TreeMap<>(); boolean returns = false, valid = true;
        try(ResultSet rs=m.getFunctionColumns(catalog, schema.isBlank()?null:pattern(m,schema), pattern(m,name), "%")) {
            while(rs.next()) {
                if(!Objects.equals(specific,rs.getString("SPECIFIC_NAME"))) continue;
                int kind=rs.getInt("COLUMN_TYPE"), type=rs.getInt("DATA_TYPE"); String typeName=Objects.toString(rs.getString("TYPE_NAME"), "unknown");
                if(kind==DatabaseMetaData.functionReturn) { f.put("returnType",typeName); returns=true; valid &= supported(type) && castType(typeName)!=null; }
                else if(kind==DatabaseMetaData.functionColumnIn) {
                    if(args.size()>=128) { valid=false; break; }
                    args.put(rs.getInt("ORDINAL_POSITION"), Profiles.JSON.createObjectNode().put("name",Objects.toString(rs.getString("COLUMN_NAME"),"argument")).put("type",typeName).put("jdbcType",type).put("valueType",valueType(type)).put("optional",false)); valid &= supported(type) && castType(typeName)!=null;
                } else valid=false;
            }
        }
        ArrayNode controls=f.putArray("arguments"); args.values().forEach(controls::add); f.put("signatureRequired",false).put("available",valid&&returns);
        if(!valid||!returns) f.put("reason","The driver did not provide a complete supported scalar signature. Output, table, array, object and unknown argument types are unavailable.");
    }
    static ObjectNode postgres(QueryJobs.Job job,Connection c,String schema,String search,String key,int offset)throws Exception {
        ObjectNode out=Profiles.JSON.createObjectNode(); ArrayNode functions=out.putArray("functions");
        String sql="SELECT p.oid::text,n.nspname,p.proname,pg_catalog.pg_get_function_identity_arguments(p.oid),pg_catalog.format_type(p.prorettype,NULL),p.prokind,p.proretset,p.pronargdefaults,p.provariadic::text,COALESCE(g.aggkind='n',true) FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace LEFT JOIN pg_catalog.pg_aggregate g ON g.aggfnoid=p.oid WHERE (?='' OR n.nspname=?) AND (?='' OR p.proname ILIKE ?) AND (?='' OR p.oid::text=?) AND pg_catalog.has_schema_privilege(n.oid,'USAGE') AND pg_catalog.has_function_privilege(p.oid,'EXECUTE') ORDER BY n.nspname,p.proname,p.oid LIMIT 201 OFFSET ?";
        try(PreparedStatement st=c.prepareStatement(sql)) { job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setString(1,schema);st.setString(2,schema);st.setString(3,search);st.setString(4,"%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");st.setString(5,key);st.setString(6,key);st.setInt(7,offset);
            try(ResultSet rs=st.executeQuery()) { while(rs.next()) { if(functions.size()==200){out.put("truncated",true).put("nextOffset",offset+200);break;} String kind=rs.getString(6);boolean usable=!rs.getBoolean(7)&&rs.getBoolean(10)&&Set.of("f","a").contains(kind);
                ObjectNode f=functions.addObject().put("key",rs.getString(1)).put("schema",rs.getString(2)).put("name",rs.getString(3)).put("signature",rs.getString(4)).put("returnType",rs.getString(5)).put("aggregate",kind.equals("a")).put("available",usable).put("defaultCount",rs.getInt(8)).put("variadic",!rs.getString(9).equals("0")).put("signatureRequired",true);
                if(!usable)f.put("reason","Procedures, window functions, ordered-set aggregates and table-returning functions are outside this visual editor.");
            }}
        }finally{job.statement=null;}
        if(!key.isEmpty()&&functions.size()==1&&functions.get(0).path("available").asBoolean()) {
            ObjectNode f=(ObjectNode)functions.get(0);ArrayNode args=f.putArray("arguments");
            String detail="SELECT COALESCE(p.proargnames[a.ordinality], 'argument '||a.ordinality),pg_catalog.format_type(CASE WHEN a.ordinality=p.pronargs AND p.provariadic<>0 THEN p.provariadic ELSE a.oid END,NULL),a.ordinality,p.pronargs,p.pronargdefaults,p.provariadic<>0 FROM pg_catalog.pg_proc p CROSS JOIN LATERAL unnest(p.proargtypes::oid[]) WITH ORDINALITY a(oid,ordinality) WHERE p.oid::text=? ORDER BY a.ordinality";
            try(PreparedStatement st=c.prepareStatement(detail)){job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setString(1,key);try(ResultSet rs=st.executeQuery()){while(rs.next()){if(args.size()>=128)throw VisualQuery.invalid("Function exceeds 128 arguments");String type=rs.getString(2),value=pgType(type);args.addObject().put("name",rs.getString(1)).put("type",type).put("valueType",value).put("optional",rs.getInt(3)>rs.getInt(4)-rs.getInt(5)).put("variadic",rs.getBoolean(6)&&rs.getInt(3)==rs.getInt(4));if(value.equals("unsupported")||castType(type)==null)f.put("available",false).put("reason","Unsupported argument type: "+type);}}}finally{job.statement=null;}
            f.put("signatureRequired",false);
        }
        return out;
    }
    static String castType(String name) {
        String type=name.trim().toUpperCase(Locale.ROOT);
        if(!type.matches("(?:SMALLINT|INTEGER|INT|BIGINT|TINYINT|INT2|INT4|INT8|NUMBER|NUMERIC|DECIMAL|REAL|FLOAT|DOUBLE(?: PRECISION)?|TEXT|VARCHAR2?|NVARCHAR2?|CHARACTER(?: VARYING)?|CHAR|NCHAR|NAME|BOOLEAN|BOOL|BIT|DATE|TIME(?:STAMP)?(?: WITHOUT TIME ZONE)?|DATETIME2?)(?:\\([0-9]{1,4}(?:,[0-9]{1,4})?\\))?"))return null;
        return type;
    }
    static String pgType(String t){return switch(t){case "smallint","integer","bigint"->"integer";case "numeric","real","double precision"->"number";case "text","character varying","character","name","\"char\""->"text";case "boolean"->"boolean";case "date"->"date";case "time without time zone"->"time";case "timestamp without time zone"->"timestamp";default->"unsupported";};}
    static boolean supported(int type){return !valueType(type).equals("unsupported");}
    static String valueType(int type){return switch(type){case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT->"integer";case Types.NUMERIC,Types.DECIMAL,Types.REAL,Types.FLOAT,Types.DOUBLE->"number";case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR->"text";case Types.BOOLEAN,Types.BIT->"boolean";case Types.DATE->"date";case Types.TIME->"time";case Types.TIMESTAMP->"timestamp";default->"unsupported";};}
}
