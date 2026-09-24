package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Fixed catalog statements with prepared values; agents cannot supply executable fragments. */
final class TrustedCatalogRead {
    record Query(String sql, ArrayNode parameters) {}
    static Query prepare(String operation, JsonNode scope, JsonNode args) {
        String vendor=scope.path("vendor").asText(),database=scope.path("database").asText(),schema=scope.path("schema").asText();
        if(!Set.of("postgresql","mysql","mariadb","h2","sqlserver","azure-sql").contains(vendor)||database.isBlank()||schema.isBlank())
            throw new IllegalArgumentException("Use an explicit supported database/schema for catalog reads");
        String object=args.path("object").asText("");
        if(object.length()>128||object.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid object name");
        ArrayNode values=Profiles.JSON.createArrayNode();
        if(operation.equals("dba_get_metadata")) {
            String kind=args.path("kind").asText(object.isBlank()?"tables":"columns");
            if(args.has("objectType")||!object.isEmpty()&&Set.of("databases","schemas").contains(kind))throw new IllegalArgumentException("Object selection is incompatible with this metadata kind");
            int offset=ProjectContexts.number(args,"offset",0,0,1000000);
            boolean selectable=ReadQueries.VENDORS.contains(vendor);
            if(!selectable&&(!Set.of("tables","columns").contains(kind)||offset!=0))throw new IllegalArgumentException("This metadata option is unsupported for this vendor");
            String paging=selectable?" LIMIT 101 OFFSET "+offset:"";
            if(kind.equals("databases"))return new Query(vendor.equals("postgresql")?"SELECT datname AS database_name FROM pg_catalog.pg_database WHERE datallowconn AND NOT datistemplate ORDER BY datname LIMIT 101 OFFSET "+offset:"SELECT schema_name AS database_name FROM information_schema.schemata ORDER BY schema_name LIMIT 101 OFFSET "+offset,values);
            if(kind.equals("schemas")){
                if(vendor.equals("postgresql")){values.add(database);return new Query("SELECT catalog_name AS database_name, schema_name FROM information_schema.schemata WHERE catalog_name=? ORDER BY schema_name LIMIT 101 OFFSET "+offset,values);}
                values.add(database);return new Query("SELECT schema_name AS database_name, schema_name FROM information_schema.schemata WHERE schema_name=? ORDER BY schema_name LIMIT 101 OFFSET "+offset,values);
            }
            if(kind.equals("indexes")){
                if(object.isBlank())throw new IllegalArgumentException("Choose an exact table for indexes");values.add(schema).add(object);
                if(vendor.equals("postgresql"))return new Query("SELECT schemaname AS table_schema, tablename AS table_name, indexname, indexdef FROM pg_catalog.pg_indexes WHERE schemaname=? AND tablename=? ORDER BY indexname LIMIT 101 OFFSET "+offset,values);
                return new Query("SELECT table_schema, table_name, index_name, non_unique, seq_in_index, column_name FROM information_schema.statistics WHERE table_schema=? AND table_name=? ORDER BY index_name,seq_in_index LIMIT 101 OFFSET "+offset,values);
            }
            if(kind.equals("keys")){
                if(object.isBlank())throw new IllegalArgumentException("Choose an exact table for keys");values.add(schema).add(object);
                return new Query("SELECT tc.table_schema, tc.table_name, tc.constraint_name, tc.constraint_type, kc.column_name, kc.ordinal_position FROM information_schema.table_constraints tc LEFT JOIN information_schema.key_column_usage kc ON tc.constraint_catalog=kc.constraint_catalog AND tc.constraint_schema=kc.constraint_schema AND tc.constraint_name=kc.constraint_name AND tc.table_name=kc.table_name WHERE tc.table_schema=? AND tc.table_name=? ORDER BY tc.constraint_name,kc.ordinal_position LIMIT 101 OFFSET "+offset,values);
            }
            if(!Set.of("tables","columns").contains(kind))throw new IllegalArgumentException("Unsupported metadata kind");
            if(kind.equals("columns")&&object.isBlank())throw new IllegalArgumentException("Choose an exact table for columns");
            values.add(Set.of("mysql","mariadb").contains(vendor)?"def":database).add(schema);
            if(kind.equals("tables")){String where="";if(!object.isBlank()){values.add(object);where=" AND table_name = ?";}return new Query("SELECT table_catalog, table_schema, table_name, table_type FROM information_schema.tables WHERE table_catalog = ? AND table_schema = ?"+where+" ORDER BY table_name"+paging,values);}
            values.add(object);
            return new Query("SELECT table_catalog, table_schema, table_name, column_name, data_type, is_nullable, column_default, ordinal_position FROM information_schema.columns WHERE table_catalog = ? AND table_schema = ? AND table_name = ? ORDER BY ordinal_position"+paging,values);
        }
        String objectType=args.path("objectType").asText("table");
        if(!Set.of("table","view","function","procedure","database").contains(objectType))throw new IllegalArgumentException("Unsupported definition kind");
        if(objectType.equals("database")){
            if(Set.of("mysql","mariadb").contains(vendor))return new Query("SHOW CREATE DATABASE "+quote(database,(char)96),values);
            throw new IllegalArgumentException("Database definition inspection is not supported for this vendor");
        }
        if(object.isBlank())throw new IllegalArgumentException("An exact object name is required");
        if(Set.of("function","procedure").contains(objectType)){
            if(Set.of("mysql","mariadb").contains(vendor))return new Query("SHOW CREATE "+objectType.toUpperCase(Locale.ROOT)+" "+quote(schema,(char)96)+"."+quote(object,(char)96),values);
            if(vendor.equals("postgresql")){
                values.add(schema).add(object).add(objectType.equals("function")?"f":"p");
                return new Query("SELECT n.nspname AS schema_name, p.proname AS object_name, pg_catalog.pg_get_function_identity_arguments(p.oid) AS arguments, pg_catalog.pg_get_functiondef(p.oid) AS definition FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=? AND p.proname=? AND p.prokind=? ORDER BY p.oid",values);
            }
            throw new IllegalArgumentException("Routine definition inspection is not supported for this vendor");
        }
        if(Set.of("sqlserver","azure-sql").contains(vendor)){
            for(int i=0;i<4;i++)values.add(schema).add(object);
            return new Query("""
                SELECT o.type_desc AS kind, o.name, m.definition,
                  CASE WHEN m.definition IS NULL THEN 'Definition unavailable: encrypted or VIEW DEFINITION privilege required' ELSE 'Native module definition' END AS coverage
                FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id
                LEFT JOIN sys.sql_modules m ON m.object_id=o.object_id
                WHERE s.name=? AND o.name=? AND o.type IN ('V','P','FN','IF','TF','TR')
                UNION ALL
                SELECT 'COLUMN', c.name,
                  QUOTENAME(c.name)+' '+QUOTENAME(t.name)+
                  CASE WHEN t.name IN ('varchar','char','varbinary','binary','nvarchar','nchar') THEN '('+
                    CASE WHEN c.max_length=-1 THEN 'MAX' ELSE CONVERT(varchar(10),CASE WHEN t.name IN ('nvarchar','nchar') THEN c.max_length/2 ELSE c.max_length END) END+')'
                    WHEN t.name IN ('decimal','numeric') THEN '('+CONVERT(varchar(10),c.precision)+','+CONVERT(varchar(10),c.scale)+')' ELSE '' END+
                  CASE WHEN c.is_nullable=1 THEN ' NULL' ELSE ' NOT NULL' END,
                  'Column fragment only; use designer/catalog for identity, computed, keys, indexes and storage details'
                FROM sys.columns c JOIN sys.tables o ON o.object_id=c.object_id
                JOIN sys.schemas s ON s.schema_id=o.schema_id JOIN sys.types t ON t.user_type_id=c.user_type_id
                WHERE s.name=? AND o.name=?
                UNION ALL
                SELECT 'CHECK_CONSTRAINT', d.name, d.definition, 'Native check expression'
                FROM sys.check_constraints d JOIN sys.tables o ON o.object_id=d.parent_object_id
                JOIN sys.schemas s ON s.schema_id=o.schema_id WHERE s.name=? AND o.name=?
                UNION ALL
                SELECT 'DEFAULT_CONSTRAINT', d.name, d.definition, 'Native default expression'
                FROM sys.default_constraints d JOIN sys.tables o ON o.object_id=d.parent_object_id
                JOIN sys.schemas s ON s.schema_id=o.schema_id WHERE s.name=? AND o.name=?
                """,values);
        }
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
