package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

/** Oracle service/container identity and capabilities shared by DBA workflows. */
final class OracleDialect {
    private OracleDialect(){}
    record Target(String databaseUniqueName,String databaseName,String container,String containerId,String service,String user,String schema){
        String database(){return container.isBlank()?service:container;}
        boolean matches(String selected){return selected==null||selected.isBlank()||selected.equals(database())||selected.equals(service);}
        ObjectNode json(){return Profiles.JSON.createObjectNode().put("databaseUniqueName",databaseUniqueName).put("databaseName",databaseName)
            .put("container",container).put("containerId",containerId).put("service",service).put("user",user).put("schema",schema).put("database",database());}
    }
    static boolean isOracle(Connection c)throws SQLException{return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("oracle");}
    static String database(QueryJobs.Job job,Connection c)throws SQLException{return isOracle(c)?target(job,c,job.remainingSeconds()).database():Objects.toString(c.getCatalog(),"");}
    static Target target(Connection c,int timeout)throws SQLException{return target(null,c,timeout);}
    static Target target(QueryJobs.Job job,Connection c,int timeout)throws SQLException{
        if(!isOracle(c))throw new SQLException("Configured Oracle target is not an Oracle database");
        String sql="SELECT SYS_CONTEXT('USERENV','DB_UNIQUE_NAME'),SYS_CONTEXT('USERENV','DB_NAME'),SYS_CONTEXT('USERENV','CON_NAME'),SYS_CONTEXT('USERENV','CON_ID'),SYS_CONTEXT('USERENV','SERVICE_NAME'),SYS_CONTEXT('USERENV','SESSION_USER'),SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM SYS.DUAL";
        try(Statement statement=c.createStatement()){
            if(job!=null)job.statement=statement;statement.setQueryTimeout(job==null?Math.max(1,timeout):job.remainingSeconds());statement.setMaxRows(1);
            try(ResultSet result=statement.executeQuery(sql)){
                if(!result.next())throw new SQLException("Oracle did not report its current service/container target");
                return new Target(value(result,1),value(result,2),value(result,3),value(result,4),value(result,5),value(result,6),value(result,7));
            }
        }finally{if(job!=null)job.statement=null;}
    }
    private static String value(ResultSet result,int column)throws SQLException{return Objects.toString(result.getString(column),"");}
    static String identifier(String value){
        if(value==null||value.isBlank()||value.indexOf('\0')>=0||value.getBytes(StandardCharsets.UTF_8).length>128)throw new IllegalArgumentException("Oracle identifiers must contain 1..128 UTF-8 bytes");
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    static boolean mayCommit(String sql){
        if(SqlScript.oracleBlock(sql))return true;
        String prefix=sql.replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ").stripLeading();
        return prefix.matches("(?is)(?:CREATE|ALTER|DROP|TRUNCATE|GRANT|REVOKE|COMMENT|RENAME|PURGE|AUDIT|NOAUDIT|COMMIT)\\b.*");
    }
    static String qualified(String owner,String name){return identifier(owner)+"."+identifier(name);}
    static void selectSchema(Connection c,String schema,int timeout)throws SQLException{
        Target before=target(c,timeout);if(before.schema().equals(schema))return;
        try(Statement statement=c.createStatement()){statement.setQueryTimeout(Math.max(1,timeout));statement.execute("ALTER SESSION SET CURRENT_SCHEMA = "+identifier(schema));}
        Target after=target(c,timeout);if(!before.databaseUniqueName().equals(after.databaseUniqueName())||!before.containerId().equals(after.containerId())||!after.schema().equals(schema))
            throw new SQLException("Oracle did not retain the requested container/schema target");
    }
    static ObjectNode capabilities(QueryJobs.Job job,Connection c)throws SQLException{
        var metadata=c.getMetaData();Target target=target(job,c,job.remainingSeconds());String version=metadata.getDatabaseProductVersion(),lower=version.toLowerCase(Locale.ROOT);
        String edition=lower.contains("enterprise edition")?"enterprise":lower.contains("standard edition")?"standard":lower.contains("free")?"free":lower.contains("express edition")?"express":"unknown";
        ObjectNode result=Profiles.JSON.createObjectNode().put("engine","oracle").put("version",version).put("majorVersion",metadata.getDatabaseMajorVersion())
            .put("minorVersion",metadata.getDatabaseMinorVersion()).put("edition",edition).put("supportedVersion",metadata.getDatabaseMajorVersion()>=19)
            .put("ddlImplicitCommit",true).put("transactionalDdl",false).put("emptyStringIsNull",true);
        result.set("target",target.json());ArrayNode privileges=result.putArray("sessionPrivileges"),roles=result.putArray("sessionRoles");
        readNames(job,c,"SELECT privilege FROM session_privs ORDER BY privilege",privileges);
        readNames(job,c,"SELECT role FROM session_roles ORDER BY role",roles);
        return result;
    }
    private static void readNames(QueryJobs.Job job,Connection c,String sql,ArrayNode names)throws SQLException{
        try(Statement statement=c.createStatement()){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setMaxRows(2049);
            try(ResultSet rows=statement.executeQuery(sql)){while(rows.next()){if(job.cancelled)throw new java.util.concurrent.CancellationException();if(names.size()>=2048)throw new SQLException("Oracle privilege inventory exceeds its bounded allowance");names.add(rows.getString(1));}}
        }finally{job.statement=null;}
    }
}
