package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Verified Oracle SELECT sessions and fixed, owner-scoped catalog statements. */
final class OracleReads {
    private OracleReads(){}
    static void verifyTarget(QueryJobs.Job job,Connection c,JsonNode scope)throws SQLException{
        if(!OracleDialect.isOracle(c)||c.getMetaData().getDatabaseMajorVersion()<19)throw new SecurityException("Oracle SELECT permissions require an observed Oracle 19c or newer connection");
        OracleDialect.Target target=OracleDialect.target(job,c,job==null?5:job.remainingSeconds());
        if(target.user().equals("SYS"))throw new IllegalArgumentException("Oracle SYS does not support read-only transactions; use an ordinary account or exact one-time review");
        if(!target.database().equals(scope.path("database").asText())||!target.schema().equals(scope.path("schema").asText()))throw new SecurityException("Resolved Oracle PDB/owner differs from the SELECT permission target");
    }
    static void verifyReferences(QueryJobs.Job job,Connection c,JsonNode request)throws SQLException{
        if(request.has("readCatalog"))return; // Fixed SYS catalog reads are validated and filtered separately.
        JsonNode scope=request.path("reusableScope");
        if(request.path("readFunctions").asBoolean()){
            String names="('COUNT','SUM','AVG','MIN','MAX','COALESCE','NULLIF','ABS','LOWER','UPPER','LENGTH')";
            String sql="SELECT 1 FROM SYS.ALL_PROCEDURES WHERE OWNER=? AND OBJECT_NAME IN "+names+" UNION ALL SELECT 1 FROM SYS.ALL_SYNONYMS WHERE OWNER IN (?, 'PUBLIC') AND SYNONYM_NAME IN "+names;
            try(PreparedStatement st=c.prepareStatement(sql)){
                job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setMaxRows(1);st.setString(1,scope.path("schema").asText());st.setString(2,scope.path("schema").asText());
                try(ResultSet rs=st.executeQuery()){if(rs.next())throw new IllegalArgumentException("Custom Oracle function resolution requires exact one-time review");}
            }finally{job.statement=null;}
        }
        for(JsonNode relation:request.path("readRelations")){
            job.progress="Validating Oracle SELECT relation "+relation.path("schema").asText()+"."+relation.path("object").asText();
            if(job.cancelled)throw new java.util.concurrent.CancellationException();
            if(!scope.path("database").equals(relation.path("database")))throw new SecurityException("Oracle relation belongs to a different PDB");
            String owner=relation.path("schema").asText(),name=relation.path("object").asText();
            String sql="""
                SELECT 1 FROM SYS.ALL_TABLES t WHERE t.OWNER=? AND t.TABLE_NAME=? AND t.TEMPORARY='N' AND t.NESTED='NO' AND t.SECONDARY='N'
                  AND NOT EXISTS (SELECT 1 FROM SYS.ALL_EXTERNAL_TABLES e WHERE e.OWNER=t.OWNER AND e.TABLE_NAME=t.TABLE_NAME)
                  AND NOT EXISTS (SELECT 1 FROM SYS.ALL_TAB_COLS c WHERE c.OWNER=t.OWNER AND c.TABLE_NAME=t.TABLE_NAME AND (c.DATA_TYPE_OWNER IS NOT NULL OR c.VIRTUAL_COLUMN='YES'))
                  AND NOT EXISTS (SELECT 1 FROM SYS.ALL_INDEXES i WHERE i.TABLE_OWNER=t.OWNER AND i.TABLE_NAME=t.TABLE_NAME AND i.INDEX_TYPE LIKE 'DOMAIN%')
                  AND NOT EXISTS (SELECT 1 FROM SYS.ALL_POLICIES p WHERE p.OBJECT_OWNER=t.OWNER AND p.OBJECT_NAME=t.TABLE_NAME AND p.ENABLE='YES')
                """;
            try(PreparedStatement st=c.prepareStatement(sql)){
                job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setMaxRows(1);st.setString(1,owner);st.setString(2,name);
                try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Oracle SELECT permission requires an exact ordinary table; synonyms, views, virtual/custom columns, external tables, domain indexes and row policies require one-time review");}
            }finally{job.statement=null;}
        }
    }
    static TrustedCatalogRead.Query catalog(String operation,JsonNode scope,JsonNode args){
        String database=scope.path("database").asText(),owner=scope.path("schema").asText(),object=args.path("object").asText();
        ArrayNode values=Profiles.JSON.createArrayNode();
        if(operation.equals("dba_get_metadata")){
            String kind=args.path("kind").asText(object.isBlank()?"tables":"columns");int offset=ProjectContexts.number(args,"offset",0,0,1000000);
            if(args.has("objectType")||!object.isEmpty()&&Set.of("databases","schemas").contains(kind))throw new IllegalArgumentException("Object selection is incompatible with this metadata kind");
            String paging=" OFFSET "+offset+" ROWS FETCH NEXT 101 ROWS ONLY";
            if(kind.equals("databases")){values.add(database);return query("SELECT ? AS database_name FROM SYS.DUAL ORDER BY database_name"+paging,values);}
            if(kind.equals("schemas")){values.add(database);return query("SELECT ? AS database_name, USERNAME AS schema_name FROM SYS.ALL_USERS ORDER BY USERNAME"+paging,values);}
            if(!Set.of("tables","columns","indexes","keys").contains(kind))throw new IllegalArgumentException("Unsupported Oracle metadata kind");
            if(!kind.equals("tables")&&object.isBlank())throw new IllegalArgumentException("Choose an exact Oracle table for "+kind);
            values.add(database).add(owner);
            if(kind.equals("tables")){
                String filter="";if(!object.isBlank()){values.add(object);filter=" AND o.OBJECT_NAME=?";}
                return query("SELECT ? AS database_name,o.OWNER AS table_schema,o.OBJECT_NAME AS table_name,o.OBJECT_TYPE AS table_type FROM SYS.ALL_OBJECTS o WHERE o.OWNER=? AND o.OBJECT_TYPE IN ('TABLE','VIEW','MATERIALIZED VIEW') AND (o.OBJECT_TYPE<>'TABLE' OR NOT EXISTS (SELECT 1 FROM SYS.ALL_MVIEWS m WHERE m.OWNER=o.OWNER AND m.MVIEW_NAME=o.OBJECT_NAME))"+filter+" ORDER BY o.OBJECT_NAME,o.OBJECT_TYPE"+paging,values);
            }
            values.add(object);
            if(kind.equals("columns"))return query("SELECT ? AS database_name,OWNER AS table_schema,TABLE_NAME,COLUMN_NAME,DATA_TYPE,NULLABLE AS is_nullable,DATA_DEFAULT AS column_default,COLUMN_ID AS ordinal_position,HIDDEN_COLUMN,IDENTITY_COLUMN,VIRTUAL_COLUMN FROM SYS.ALL_TAB_COLS WHERE OWNER=? AND TABLE_NAME=? AND (HIDDEN_COLUMN='NO' OR USER_GENERATED='YES') ORDER BY INTERNAL_COLUMN_ID"+paging,values);
            if(kind.equals("indexes"))return query("SELECT ? AS database_name,i.TABLE_OWNER AS table_schema,i.TABLE_NAME,i.INDEX_NAME,i.INDEX_TYPE,i.UNIQUENESS,c.COLUMN_NAME,c.COLUMN_POSITION AS ordinal_position,c.DESCEND FROM SYS.ALL_INDEXES i LEFT JOIN SYS.ALL_IND_COLUMNS c ON c.INDEX_OWNER=i.OWNER AND c.INDEX_NAME=i.INDEX_NAME WHERE i.TABLE_OWNER=? AND i.TABLE_NAME=? ORDER BY i.INDEX_NAME,c.COLUMN_POSITION"+paging,values);
            return query("SELECT ? AS database_name,k.OWNER AS table_schema,k.TABLE_NAME,k.CONSTRAINT_NAME,k.CONSTRAINT_TYPE,k.STATUS,k.VALIDATED,k.R_OWNER,k.R_CONSTRAINT_NAME,c.COLUMN_NAME,c.POSITION AS ordinal_position FROM SYS.ALL_CONSTRAINTS k LEFT JOIN SYS.ALL_CONS_COLUMNS c ON c.OWNER=k.OWNER AND c.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND c.TABLE_NAME=k.TABLE_NAME WHERE k.OWNER=? AND k.TABLE_NAME=? ORDER BY k.CONSTRAINT_NAME,c.POSITION"+paging,values);
        }
        if(object.isBlank())throw new IllegalArgumentException("An exact Oracle object name is required");
        String type=switch(args.path("objectType").asText("table")){
            case "table"->"TABLE";case "view"->"VIEW";case "materialized_view"->"MATERIALIZED_VIEW";case "index"->"INDEX";case "sequence"->"SEQUENCE";
            case "function"->"FUNCTION";case "procedure"->"PROCEDURE";case "package"->"PACKAGE_SPEC";case "package_body"->"PACKAGE_BODY";
            case "type"->"TYPE_SPEC";case "type_body"->"TYPE_BODY";case "trigger"->"TRIGGER";case "synonym"->"SYNONYM";
            default->throw new IllegalArgumentException("Unsupported Oracle native definition kind");
        };
        values.add(owner).add(object).add(type).add(object).add(owner);
        return query("SELECT ? AS schema_name,? AS object_name,SYS.DBMS_METADATA.GET_DDL(?,?,?) AS definition FROM SYS.DUAL",values);
    }
    private static TrustedCatalogRead.Query query(String sql,ArrayNode values){return new TrustedCatalogRead.Query(sql,values);}
}
