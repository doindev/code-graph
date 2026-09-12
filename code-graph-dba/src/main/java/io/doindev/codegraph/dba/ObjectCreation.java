package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;

/** Deliberately small native CREATE adapter. Catalog visibility is not DDL permission. */
final class ObjectCreation {
    static String engine(Connection c)throws SQLException {String p=c.getMetaData().getDatabaseProductName();return p.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(p);}
    static boolean supports(String engine,String kind){return Set.of("postgresql","h2").contains(engine)&&(Set.of("schemas","tables","views","sequences","indexes").contains(kind)||engine.equals("postgresql")&&kind.equals("materialized_views"));}
    static ObjectNode annotate(Connection c,ObjectNode result,boolean generic)throws SQLException {
        String engine=engine(c);
        for(JsonNode node:result.path("nodes"))if(!generic&&node.path("branch").asBoolean()&&supports(engine,node.path("kind").asText()))((ObjectNode)node).put("canCreate",true);
        return result;
    }
    static ObjectNode prepare(Connection c,JsonNode input,boolean generic)throws Exception {
        String engine=engine(c),kind=input.path("kind").asText();
        if(generic||!supports(engine,kind))throw new IllegalArgumentException("Creation is not supported for this database/category. Use the SQL editor with verified vendor syntax.");
        String schema=input.path("schema").asText(),name=Profiles.text(input,"name",128),quoted=TableDesigner.q(name);
        if(!kind.equals("schemas")){
            TableDesigner.q(schema);boolean found=false;
            try(var rs=c.getMetaData().getSchemas()){while(rs.next())if(schema.equals(rs.getString("TABLE_SCHEM")))found=true;}
            if(!found)throw new IllegalArgumentException("Selected schema is no longer available; refresh the tree.");
        }
        String target=kind.equals("schemas")?quoted:TableDesigner.q(schema)+"."+quoted,sql;
        switch(kind){
            case "schemas" -> sql="CREATE SCHEMA "+target;
            case "sequences" -> sql="CREATE SEQUENCE "+target;
            case "tables" -> {
                JsonNode columns=input.path("columns");if(!columns.isArray()||columns.isEmpty()||columns.size()>128)throw new IllegalArgumentException("Specify 1–128 columns");
                List<String> defs=new ArrayList<>();Set<String> names=new HashSet<>();
                for(JsonNode column:columns){String n=Profiles.text(column,"name",128);if(!names.add(n))throw new IllegalArgumentException("Duplicate column name: "+n);defs.add(TableDesigner.q(n)+" "+TableDesigner.type(Profiles.text(column,"type",128))+(column.path("nullable").asBoolean(true)?"":" NOT NULL"));}
                sql="CREATE TABLE "+target+" (\n  "+String.join(",\n  ",defs)+"\n)";
            }
            case "views","materialized_views" -> {
                String query=Profiles.text(input,"query",16000);var units=SqlScript.extract(query);
                if(units.size()!=1||units.getFirst().parameters()!=0||!(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(query) instanceof net.sf.jsqlparser.statement.select.Select))throw new IllegalArgumentException("Supply one SELECT without prepared parameters");
                sql="CREATE "+(kind.equals("views")?"VIEW ":"MATERIALIZED VIEW ")+target+" AS\n"+units.getFirst().sql();
            }
            case "indexes" -> {
                String table=Profiles.text(input,"table",128);String columns=TableDesigner.columnList(input.path("indexColumns"));
                sql="CREATE INDEX "+(engine.equals("postgresql")?quoted:target)+" ON "+TableDesigner.q(schema)+"."+TableDesigner.q(table)+" ("+columns+")";
            }
            default -> throw new IllegalArgumentException("Unsupported category");
        }
        ObjectNode out=Profiles.JSON.createObjectNode().put("creationPlan",true).put("engine",engine).put("database",c.getCatalog()).put("schema",schema).put("name",name).put("kind",kind).put("sql",sql).put("expiresAt",System.currentTimeMillis()+300000).put("atomic",engine.equals("postgresql"));
        String identity=c.getMetaData().getURL()+"\n"+c.getMetaData().getUserName()+"\n"+c.getCatalog();
        out.put("targetFingerprint",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        out.set("draft",input.deepCopy());return out;
    }
}
