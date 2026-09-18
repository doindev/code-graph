package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Fixed catalog statements with prepared values; agents cannot supply executable fragments. */
final class TrustedCatalogRead {
    record Query(String sql, ArrayNode parameters) {}
    static Query prepare(String operation, JsonNode scope, JsonNode args) {
        String vendor=scope.path("vendor").asText(),database=scope.path("database").asText(),schema=scope.path("schema").asText();
        if(!Set.of("postgresql","mysql","mariadb","h2").contains(vendor)||database.isBlank()||schema.isBlank())
            throw new IllegalArgumentException("Use an explicit supported database/schema for catalog reads");
        String object=args.path("object").asText("");
        if(object.length()>128||object.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid object name");
        ArrayNode values=Profiles.JSON.createArrayNode();
        if(operation.equals("dba_get_metadata")) {
            values.add(Set.of("mysql","mariadb").contains(vendor)?"def":database).add(schema);
            if(object.isBlank())return new Query("SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables WHERE table_catalog = ? AND table_schema = ? ORDER BY table_name",values);
            values.add(object);
            return new Query("SELECT table_catalog, table_schema, table_name, column_name, data_type, is_nullable, column_default, ordinal_position FROM information_schema.columns WHERE table_catalog = ? AND table_schema = ? AND table_name = ? ORDER BY ordinal_position",values);
        }
        if(object.isBlank())throw new IllegalArgumentException("An exact object name is required");
        if(vendor.equals("mysql")||vendor.equals("mariadb"))
            return new Query("SHOW CREATE TABLE "+quote(schema,(char)96)+"."+quote(object,(char)96),values);
        if(vendor.equals("postgresql")){
            for(int i=0;i<3;i++)values.add(schema).add(object);
            return new Query("""
                SELECT 'view' AS kind, c.relname AS name, pg_catalog.pg_get_viewdef(c.oid,true) AS definition
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('v','m')
                UNION ALL
                SELECT 'column', a.attname, pg_catalog.format('%I %s%s%s',a.attname,
                  pg_catalog.format_type(a.atttypid,a.atttypmod),
                  CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END,
                  CASE WHEN d.oid IS NOT NULL THEN ' DEFAULT ' || pg_catalog.pg_get_expr(d.adbin,d.adrelid) ELSE '' END)
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped AND c.relkind IN ('r','p')
                UNION ALL
                SELECT 'constraint', con.conname, pg_catalog.pg_get_constraintdef(con.oid,true)
                FROM pg_catalog.pg_constraint con JOIN pg_catalog.pg_class c ON c.oid=con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?
                """,values);
        }
        throw new IllegalArgumentException("DDL inspection is not verified for this vendor; use one-time SQL review");
    }
    private static String quote(String s,char q){return q+s.replace(String.valueOf(q),""+q+q)+q;}
}
