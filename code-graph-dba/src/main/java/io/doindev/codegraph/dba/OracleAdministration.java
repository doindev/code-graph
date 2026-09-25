package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Browser-owned Oracle administration. Every mutation starts from an expiring reviewed job. */
final class OracleAdministration {
    private final Connections connections;private final QueryJobs jobs;
    OracleAdministration(Connections connections,QueryJobs jobs){this.connections=connections;this.jobs=jobs;}
    record Catalog(String id,String label,String sql,String filter,String order){}
    static final List<Catalog> CATALOGS=List.of(
        new Catalog("users","Users","SELECT USERNAME,USER_ID,ACCOUNT_STATUS,LOCK_DATE,EXPIRY_DATE,DEFAULT_TABLESPACE,TEMPORARY_TABLESPACE,PROFILE,AUTHENTICATION_TYPE,COMMON,ORACLE_MAINTAINED FROM SYS.DBA_USERS","USERNAME","USERNAME"),
        new Catalog("roles","Roles","SELECT ROLE,PASSWORD_REQUIRED,AUTHENTICATION_TYPE,COMMON,ORACLE_MAINTAINED FROM SYS.DBA_ROLES","ROLE","ROLE"),
        new Catalog("profiles","Profiles","SELECT PROFILE,RESOURCE_NAME,RESOURCE_TYPE,LIMIT,COMMON FROM SYS.DBA_PROFILES","PROFILE","PROFILE,RESOURCE_NAME"),
        new Catalog("grants","Grants","SELECT GRANTEE,'SYSTEM' AS KIND,PRIVILEGE AS PRIVILEGE_NAME,CAST(NULL AS VARCHAR2(128)) AS OBJECT_OWNER,CAST(NULL AS VARCHAR2(128)) AS OBJECT_NAME,ADMIN_OPTION AS GRANTABLE FROM SYS.DBA_SYS_PRIVS UNION ALL SELECT GRANTEE,'ROLE',GRANTED_ROLE,NULL,NULL,ADMIN_OPTION FROM SYS.DBA_ROLE_PRIVS UNION ALL SELECT GRANTEE,'OBJECT',PRIVILEGE,OWNER,TABLE_NAME,GRANTABLE FROM SYS.DBA_TAB_PRIVS","GRANTEE","GRANTEE,KIND,PRIVILEGE_NAME,OBJECT_OWNER,OBJECT_NAME"),
        new Catalog("quotas","Quotas","SELECT USERNAME,TABLESPACE_NAME,BYTES,MAX_BYTES,BLOCKS,MAX_BLOCKS FROM SYS.DBA_TS_QUOTAS","USERNAME","USERNAME,TABLESPACE_NAME"),
        new Catalog("tablespaces","Tablespaces","SELECT TABLESPACE_NAME,STATUS,CONTENTS,BLOCK_SIZE,EXTENT_MANAGEMENT,ALLOCATION_TYPE,SEGMENT_SPACE_MANAGEMENT,BIGFILE,ENCRYPTED FROM SYS.DBA_TABLESPACES","TABLESPACE_NAME","TABLESPACE_NAME"),
        new Catalog("files","Files","SELECT 'DATAFILE' AS KIND,FILE_ID,FILE_NAME,TABLESPACE_NAME,BYTES,AUTOEXTENSIBLE,MAXBYTES,INCREMENT_BY FROM SYS.DBA_DATA_FILES UNION ALL SELECT 'TEMPFILE',FILE_ID,FILE_NAME,TABLESPACE_NAME,BYTES,AUTOEXTENSIBLE,MAXBYTES,INCREMENT_BY FROM SYS.DBA_TEMP_FILES","TABLESPACE_NAME","KIND,FILE_ID"),
        new Catalog("sessions","Sessions","SELECT SID,SERIAL#,USERNAME,STATUS,TYPE,SERVICE_NAME,MACHINE,PROGRAM,SQL_ID,LOGON_TIME,EVENT,WAIT_CLASS,BLOCKING_SESSION,STATE FROM SYS.V_$SESSION WHERE USERNAME IS NOT NULL","USERNAME","SID,SERIAL#"),
        new Catalog("locks","Locks","SELECT l.SID,s.SERIAL#,s.USERNAME,l.TYPE,l.ID1,l.ID2,l.LMODE,l.REQUEST,l.CTIME,l.BLOCK,s.BLOCKING_SESSION FROM SYS.V_$LOCK l LEFT JOIN SYS.V_$SESSION s ON s.SID=l.SID WHERE s.USERNAME IS NOT NULL","USERNAME","SID,TYPE,ID1,ID2"),
        new Catalog("active_sql","Active SQL","SELECT SQL_ID,CHILD_NUMBER,PARSING_SCHEMA_NAME,SQL_TEXT,EXECUTIONS,ELAPSED_TIME,CPU_TIME,BUFFER_GETS,DISK_READS,ROWS_PROCESSED,LAST_ACTIVE_TIME FROM SYS.V_$SQL WHERE USERS_EXECUTING>0","PARSING_SCHEMA_NAME","SQL_ID,CHILD_NUMBER"),
        new Catalog("diagnostics","Diagnostics","SELECT NAME,VALUE FROM SYS.V_$DIAG_INFO","NAME","NAME"),
        new Catalog("compilation","Compilation","SELECT OWNER,NAME,TYPE,SEQUENCE,LINE,POSITION,ATTRIBUTE,MESSAGE_NUMBER,TEXT FROM SYS.ALL_ERRORS","OWNER","OWNER,NAME,TYPE,SEQUENCE"),
        new Catalog("statistics","Statistics","SELECT OWNER,TABLE_NAME,PARTITION_NAME,OBJECT_TYPE,NUM_ROWS,BLOCKS,AVG_ROW_LEN,LAST_ANALYZED,STALE_STATS,STATTYPE_LOCKED FROM SYS.ALL_TAB_STATISTICS","OWNER","OWNER,TABLE_NAME,OBJECT_TYPE,PARTITION_NAME"),
        new Catalog("datapump","Data Pump","SELECT OWNER_NAME,JOB_NAME,OPERATION,JOB_MODE,STATE,DEGREE,ATTACHED_SESSIONS,DATAPUMP_SESSIONS FROM SYS.DBA_DATAPUMP_JOBS","OWNER_NAME","OWNER_NAME,JOB_NAME"),
        new Catalog("directories","Directories","SELECT OWNER,DIRECTORY_NAME,DIRECTORY_PATH FROM SYS.ALL_DIRECTORIES","DIRECTORY_NAME","DIRECTORY_NAME")
    );
    static ArrayNode categories(){var result=Profiles.JSON.createArrayNode();CATALOGS.forEach(c->result.addObject().put("id",c.id()).put("label",c.label()));return result;}
    private String connection(String owner,JsonNode input){if(owner.startsWith("agent:"))throw new SecurityException("Oracle Administration is browser-only");String id=Profiles.text(input,"connectionId",36);if(connections.genericOnly(id))throw new IllegalArgumentException("Select a native Oracle connection");return id;}
    static OracleDialect.Target target(QueryJobs.Job job,Connection c,JsonNode input)throws SQLException{
        if(!OracleDialect.isOracle(c)||c.getMetaData().getDatabaseMajorVersion()<19)throw new IllegalArgumentException("Oracle Administration requires Oracle 19c or newer");
        var target=OracleDialect.target(job,c,job.remainingSeconds());if(!target.matches(input.path("database").asText()))throw new IllegalArgumentException("Oracle service/PDB changed; reload Administration before continuing");return target;
    }
    ObjectNode read(String owner,JsonNode input){String id=connection(owner,input);JsonNode request=input.deepCopy();return jobs.local(owner,id,job->{
        try(var selected=connections.target(id,request.path("database").asText())){
            Connection c=selected.connection();var target=target(job,c,request);String category=request.path("category").asText("overview");job.progress="Reading Oracle "+category+" in "+target.database();
            if(category.equals("overview")){ObjectNode result=OracleDialect.capabilities(job,c);result.set("categories",categories());result.set("actions",OracleAdminPlans.actions());result.put("observedAt",System.currentTimeMillis());return result;}
            Catalog spec=CATALOGS.stream().filter(v->v.id().equals(category)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown Oracle administration category"));
            int offset=number(request,"offset",0,0,100000),limit=Math.min(100,Math.max(1,job.rowLimit));String filter=request.path("filter").asText();if(filter.length()>128)throw new IllegalArgumentException("Filter exceeds 128 characters");
            var result=Profiles.JSON.createObjectNode().put("category",category).put("label",spec.label()).put("offset",offset).put("pageSize",limit).put("observedAt",System.currentTimeMillis());result.set("target",target.json());
            try{var rows=ObjectCatalog.query(job,c,"SELECT * FROM ("+spec.sql()+") WHERE (? IS NULL OR INSTR(UPPER("+spec.filter()+"),UPPER(?))>0) ORDER BY "+spec.order()+" OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",filter,filter,offset,limit+1);boolean more=rows.size()>limit;if(more)rows.remove(rows.size()-1);result.set("rows",rows);return result.put("available",true).put("hasMore",more);}
            catch(SQLException unavailable){return result.put("available",false).put("hasMore",false).put("message","Oracle could not read "+spec.label()+". Verify catalog privileges on the required SYS views (SQLSTATE "+Objects.toString(unavailable.getSQLState(),"unknown")+", Oracle "+unavailable.getErrorCode()+").");}
        }
    },()->{});}
    ObjectNode prepare(String owner,JsonNode input){String id=connection(owner,input);ObjectNode request=OracleAdminPlans.request(input);return jobs.local(owner,id,job->{
        try(var selected=connections.target(id,request.path("database").asText())){var c=selected.connection();target(job,c,request);job.progress="Preparing Oracle administration review";var plan=OracleAdminPlans.prepare(job,c,request);plan.put("profileRevision",ProjectContexts.profileRevision(connections.profile(id)));return plan;}
    },()->{});}
    ObjectNode apply(String owner,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Oracle Administration is browser-only");if(!input.path("confirmed").isBoolean()||!input.path("confirmed").asBoolean())throw new IllegalArgumentException("Review and acknowledge this Oracle administration operation before applying it");
        var preview=jobs.require(owner,Profiles.text(input,"planId",36));ObjectNode plan;String password;
        synchronized(preview){if(!preview.state.equals("complete")||preview.result==null||!preview.result.path("oracleAdminPlan").asBoolean()||preview.designerPlanUsed||preview.result.path("expiresAt").asLong()<System.currentTimeMillis())throw new IllegalArgumentException("Oracle administration review expired or was used; prepare a new review");plan=((ObjectNode)preview.result).deepCopy();password=plan.path("requiresPassword").asBoolean()?OracleAdminPlans.password(input.path("password").asText()):"";preview.designerPlanUsed=true;}
        String id=preview.connection;
        return jobs.local(owner,id,job->{
            ObjectNode report=Profiles.JSON.createObjectNode().put("status","failed").put("atomic",false);var steps=report.putArray("steps");job.result=report;boolean executing=false;
            try(var selected=connections.target(id,plan.path("target").path("database").asText())){
                Connection c=selected.connection();c.setReadOnly(false);c.setAutoCommit(true);
                if(!plan.path("profileRevision").asText().equals(ProjectContexts.profileRevision(connections.profile(id))))throw new IllegalArgumentException("Connection changed; review the administration operation again");
                ObjectNode checked=OracleAdminPlans.prepare(job,c,(ObjectNode)plan.path("request"));if(!plan.path("fingerprint").equals(checked.path("fingerprint")))throw new IllegalArgumentException("Oracle target, privileges or selected object changed; review the operation again");
                for(JsonNode command:plan.path("commands")){
                    if(job.cancelled)throw new CancellationException();job.progress="Applying Oracle administration step "+(steps.size()+1)+" of "+plan.path("commands").size();
                    String sql=command.has("secretPrefix")?command.path("secretPrefix").asText()+OracleDialect.identifier(password)+command.path("secretSuffix").asText():command.path("sql").asText();
                    try(Statement statement=c.createStatement()){job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());executing=true;statement.execute(sql);executing=false;}finally{job.statement=null;}
                    steps.addObject().put("index",steps.size()).put("status","acknowledged");
                }
                if(plan.path("action").asText().equals("compile")){
                    var request=plan.path("request");var errors=ObjectCatalog.query(job,c,"SELECT TYPE,LINE,POSITION,ATTRIBUTE,TEXT FROM SYS.ALL_ERRORS WHERE OWNER=? AND NAME=? ORDER BY TYPE,SEQUENCE",request.path("owner").asText(),request.path("name").asText());report.set("compilationErrors",errors);
                    if(!errors.isEmpty()){job.outcome="steps_acknowledged";return report.put("outcome",job.outcome).put("message","Oracle completed compilation with diagnostics. The object may still be invalid; inspect its errors.");}
                }
                report.put("status","success").put("outcome","steps_acknowledged").put("message","Oracle acknowledged the reviewed operation. DDL commits implicitly.");job.outcome="steps_acknowledged";
            }catch(Exception failure){
                boolean uncertain=executing&&(job.cancelled||failure instanceof CancellationException||failure instanceof SQLTimeoutException||failure instanceof SQLException e&&(e.getErrorCode()==1013||Objects.toString(e.getSQLState(),"").startsWith("08")));
                job.outcome=uncertain?"unknown":steps.isEmpty()?"not_applied":"partial";
                String message=password.isEmpty()?connections.humanError(id,failure):"Oracle rejected the password operation. Check account policy, connection and privileges; the password was not retained in the plan.";
                report.put("status","failed").put("outcome",job.outcome).put("message",message+(uncertain?" The server may still be executing the operation; inspect Oracle before retrying.":""));
            }finally{job.statement=null;}return report;
        },()->{});
    }
    static int number(JsonNode input,String key,int fallback,int min,int max){JsonNode value=input.path(key);if(value.isMissingNode())return fallback;if(!value.isIntegralNumber()||!value.canConvertToInt()||value.asInt()<min||value.asInt()>max)throw new IllegalArgumentException(key+" must be an integer from "+min+" to "+max);return value.asInt();}
}
