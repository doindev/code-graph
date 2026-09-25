package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectCatalog.query;
import static io.doindev.codegraph.dba.TableDesigner.literal;

/** Oracle owns the durable job. Application cancellation never implies stopping its workers. */
final class OracleDataPump {
    static List<OracleAdminPlans.Action> actions(){return List.of(
        OracleAdminPlans.action("datapump_export","Export schema","datapump","",OracleAdminPlans.name(),OracleAdminPlans.owner(),OracleAdminPlans.text("directory","Oracle directory object"),OracleAdminPlans.text("dumpFile","Dump file name"),OracleAdminPlans.text("logFile","Log file name"),OracleAdminPlans.option("content","Content","ALL","METADATA_ONLY","DATA_ONLY")),
        OracleAdminPlans.action("datapump_import","Import schema","datapump","",OracleAdminPlans.name(),OracleAdminPlans.owner(),OracleAdminPlans.text("destinationOwner","Destination owner/schema"),OracleAdminPlans.text("directory","Oracle directory object"),OracleAdminPlans.text("dumpFile","Existing dump file name"),OracleAdminPlans.text("logFile","Log file name"),OracleAdminPlans.option("content","Content","ALL","METADATA_ONLY","DATA_ONLY"),OracleAdminPlans.option("tableExistsAction","Existing tables","SKIP","APPEND","TRUNCATE","REPLACE")),
        OracleAdminPlans.action("datapump_stop","Stop Data Pump job (keep restart state)","datapump","",OracleAdminPlans.name(),OracleAdminPlans.owner()),
        OracleAdminPlans.action("datapump_resume","Resume Data Pump job","datapump","",OracleAdminPlans.name(),OracleAdminPlans.owner())
    );}
    static String definition(QueryJobs.Job job,Connection c,ObjectNode request,OracleDialect.Target target,ArrayNode observed)throws Exception{
        String action=request.path("action").asText(),name=request.path("name").asText(),owner=request.path("owner").asText();
        OracleDialect.identifier(name);OracleDialect.identifier(owner);
        var roles=query(job,c,"SELECT ROLE FROM SYS.SESSION_ROLES WHERE ROLE IN ('DATAPUMP_EXP_FULL_DATABASE','DATAPUMP_IMP_FULL_DATABASE') ORDER BY ROLE");observed.add(roles);
        var enabled=new HashSet<String>();roles.forEach(row->enabled.add(row.path("role").asText()));
        boolean creating=action.equals("datapump_export")||action.equals("datapump_import"),export=action.equals("datapump_export");String sql;
        if(creating){
            String operation=export?"EXPORT":"IMPORT",required="DATAPUMP_"+(export?"EXP":"IMP")+"_FULL_DATABASE",destination=request.path("destinationOwner").asText();
            if((export?!owner.equals(target.user()):!destination.equals(target.user()))&&!enabled.contains(required))throw new IllegalArgumentException("Oracle role required for this schema: "+required);
            if(!query(job,c,"SELECT OBJECT_ID FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=?",target.user(),name).isEmpty())throw new IllegalArgumentException("The Data Pump job/master table name already exists; use a new job name");
            if(!query(job,c,"SELECT PRIVILEGE FROM SYS.SESSION_PRIVS WHERE PRIVILEGE='CREATE TABLE'").iterator().hasNext())throw new IllegalArgumentException("Data Pump requires CREATE TABLE and quota for its master table");
            String directory=request.path("directory").asText(),dump=fileName(request.path("dumpFile").asText()),log=fileName(request.path("logFile").asText());OracleDialect.identifier(directory);if(dump.equalsIgnoreCase(log))throw new IllegalArgumentException("Use different dump and log file names");
            var directories=query(job,c,"SELECT OWNER,DIRECTORY_NAME,DIRECTORY_PATH FROM SYS.ALL_DIRECTORIES WHERE DIRECTORY_NAME=?",directory);if(directories.isEmpty())throw new IllegalArgumentException("Oracle directory is not visible; verify its name and READ/WRITE grants");observed.add(directories);
            var directoryGrants=query(job,c,"SELECT GRANTEE,PRIVILEGE,GRANTABLE FROM SYS.ALL_TAB_PRIVS WHERE TABLE_NAME=? AND TYPE='DIRECTORY' AND (GRANTEE IN (?, 'PUBLIC') OR GRANTEE IN (SELECT ROLE FROM SYS.SESSION_ROLES)) ORDER BY GRANTEE,PRIVILEGE",directory,target.user());observed.add(directoryGrants);
            var directoryPrivileges=new HashSet<String>();directoryGrants.forEach(row->directoryPrivileges.add(row.path("privilege").asText()));
            if(!target.user().equals("SYS")&&!directoryPrivileges.containsAll(Set.of("READ","WRITE")))throw new IllegalArgumentException("Data Pump requires READ and WRITE on Oracle directory "+directory);
            boolean dumpExists=fileExists(job,c,directory,dump);if(export&&dumpExists)throw new IllegalArgumentException("Dump file already exists; use a new file name");if(!export&&!dumpExists)throw new IllegalArgumentException("Import dump file does not exist in the selected Oracle directory");
            if(fileExists(job,c,directory,log))throw new IllegalArgumentException("Log file already exists; use a new log file name");
            String schema=export?owner:destination;OracleDialect.identifier(schema);var schemaState=query(job,c,"SELECT USERNAME,USER_ID,CREATED FROM SYS.ALL_USERS WHERE USERNAME=?",schema);if(schemaState.isEmpty())throw new IllegalArgumentException("The "+(export?"source":"destination")+" owner must already exist");observed.add(schemaState);
            sql="h:=SYS.DBMS_DATAPUMP.OPEN(operation=>"+literal(operation)+",job_mode=>'SCHEMA',job_name=>"+literal(name)+",version=>'COMPATIBLE');\n";
            sql+="SYS.DBMS_DATAPUMP.ADD_FILE(h,"+literal(dump)+","+literal(directory)+",filetype=>1,reusefile=>0);\n";
            sql+="SYS.DBMS_DATAPUMP.ADD_FILE(h,"+literal(log)+","+literal(directory)+",filetype=>3);\n";
            sql+="SYS.DBMS_DATAPUMP.METADATA_FILTER(h,'SCHEMA_EXPR',"+literal("IN ("+literal(owner)+")")+");\n";
            sql+="SYS.DBMS_DATAPUMP.SET_PARAMETER(h,'KEEP_MASTER',1);\n";
            String content=request.path("content").asText();if(content.equals("METADATA_ONLY"))sql+="SYS.DBMS_DATAPUMP.DATA_FILTER(h,'INCLUDE_ROWS',0);\n";else if(content.equals("DATA_ONLY"))sql+="SYS.DBMS_DATAPUMP.SET_PARAMETER(h,'INCLUDE_METADATA',0);\n";
            if(export){if(enabled.contains(required))sql+="SYS.DBMS_DATAPUMP.SET_PARAMETER(h,'USER_METADATA',0);\n";}
            else{if(!owner.equals(destination))sql+="SYS.DBMS_DATAPUMP.METADATA_REMAP(h,'REMAP_SCHEMA',"+literal(owner)+","+literal(destination)+");\n";sql+="SYS.DBMS_DATAPUMP.SET_PARAMETER(h,'TABLE_EXISTS_ACTION',"+literal(request.path("tableExistsAction").asText())+");\n";}
            // Degree stays one on every edition; callers cannot silently enable Enterprise-only parallelism.
            sql+="SYS.DBMS_DATAPUMP.START_JOB(h,cluster_ok=>0);\nSYS.DBMS_DATAPUMP.DETACH(h); h:=NULL;";
        }else{
            var rows=roster(job,c,owner,name);if(rows.isEmpty())throw new IllegalArgumentException("Data Pump job is not visible; refresh jobs and check Data Pump roles");var row=rows.get(0);String required=row.path("operation").asText().equals("EXPORT")?"DATAPUMP_EXP_FULL_DATABASE":"DATAPUMP_IMP_FULL_DATABASE";
            if(!owner.equals(target.user())&&!enabled.contains(required))throw new IllegalArgumentException("Oracle role required to control this job: "+required);
            observed.add(query(job,c,"SELECT OBJECT_ID,CREATED FROM SYS.ALL_OBJECTS WHERE OWNER=? AND OBJECT_NAME=? AND OBJECT_TYPE='TABLE'",owner,name));
            observed.addObject().put("operation",row.path("operation").asText()).put("mode",row.path("job_mode").asText());
            if(!Set.of("EXPORT","IMPORT").contains(row.path("operation").asText()))throw new IllegalArgumentException("Only Data Pump export/import jobs are supported");
            if(action.equals("datapump_resume")&&!owner.equals(target.user()))throw new IllegalArgumentException("Oracle requires reconnecting as the original job owner to resume this Data Pump job");
            if(action.equals("datapump_resume")&&!Set.of("NOT RUNNING","STOPPED","IDLING").contains(row.path("state").asText()))throw new IllegalArgumentException("Only a stopped Data Pump job can be resumed");
            sql="h:=SYS.DBMS_DATAPUMP.ATTACH("+literal(name)+","+literal(owner)+");\n"+(action.equals("datapump_stop")?"SYS.DBMS_DATAPUMP.STOP_JOB(h,immediate=>1,keep_master=>1,delay=>0); h:=NULL;":"SYS.DBMS_DATAPUMP.START_JOB(h,cluster_ok=>0);\nSYS.DBMS_DATAPUMP.DETACH(h); h:=NULL;");
        }
        return "DECLARE h NUMBER; BEGIN\n"+sql+"\nEXCEPTION WHEN OTHERS THEN\n  IF h IS NOT NULL THEN BEGIN SYS.DBMS_DATAPUMP.DETACH(h); EXCEPTION WHEN OTHERS THEN NULL; END; END IF;\n  RAISE;\nEND;";
    }
    private static boolean fileExists(QueryJobs.Job job,Connection c,String directory,String name)throws SQLException{
        try(CallableStatement statement=c.prepareCall("DECLARE present BOOLEAN; length NUMBER; block_size BINARY_INTEGER; found NUMBER; BEGIN SYS.UTL_FILE.FGETATTR(?,?,present,length,block_size); IF present THEN found:=1; ELSE found:=0; END IF; ?:=found; END;")){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setString(1,directory);statement.setString(2,name);statement.registerOutParameter(3,Types.INTEGER);statement.execute();return statement.getInt(3)==1;
        }finally{job.statement=null;}
    }
    static String fileName(String value){if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,199}")||value.contains(".."))throw new IllegalArgumentException("Use a file name without a path, wildcard, or parent-directory segment");return value;}
    record Page(ArrayNode rows,boolean ownOnly){}
    static Page page(QueryJobs.Job job,Connection c,String filter,int offset,int limit)throws Exception{
        String suffix=" WHERE (? IS NULL OR INSTR(UPPER(OWNER_NAME),UPPER(?))>0) ORDER BY OWNER_NAME,JOB_NAME OFFSET ? ROWS FETCH NEXT ? ROWS ONLY";
        String columns="OWNER_NAME,JOB_NAME,TRIM(OPERATION) AS OPERATION,TRIM(JOB_MODE) AS JOB_MODE,STATE,DEGREE,ATTACHED_SESSIONS,DATAPUMP_SESSIONS";
        try{return new Page(query(job,c,"SELECT "+columns+" FROM SYS.DBA_DATAPUMP_JOBS"+suffix,filter,filter,offset,limit),false);}
        catch(SQLException e){if(e.getErrorCode()!=942&&e.getErrorCode()!=1031)throw e;return new Page(query(job,c,"SELECT * FROM (SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS OWNER_NAME,JOB_NAME,TRIM(OPERATION) AS OPERATION,TRIM(JOB_MODE) AS JOB_MODE,STATE,DEGREE,ATTACHED_SESSIONS,DATAPUMP_SESSIONS FROM SYS.USER_DATAPUMP_JOBS)"+suffix,filter,filter,offset,limit),true);}
    }
    static ArrayNode roster(QueryJobs.Job job,Connection c,String owner,String name)throws Exception{
        try{return query(job,c,"SELECT OWNER_NAME,JOB_NAME,TRIM(OPERATION) AS OPERATION,TRIM(JOB_MODE) AS JOB_MODE,STATE,DEGREE,ATTACHED_SESSIONS,DATAPUMP_SESSIONS FROM SYS.DBA_DATAPUMP_JOBS WHERE OWNER_NAME=? AND JOB_NAME=?",owner,name);}
        catch(SQLException e){if(e.getErrorCode()!=942&&e.getErrorCode()!=1031)throw e;return query(job,c,"SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS OWNER_NAME,JOB_NAME,TRIM(OPERATION) AS OPERATION,TRIM(JOB_MODE) AS JOB_MODE,STATE,DEGREE,ATTACHED_SESSIONS,DATAPUMP_SESSIONS FROM SYS.USER_DATAPUMP_JOBS WHERE SYS_CONTEXT('USERENV','SESSION_USER')=? AND JOB_NAME=?",owner,name);}
    }
    static ObjectNode status(QueryJobs.Job job,Connection c,JsonNode input)throws Exception{
        var target=OracleAdministration.target(job,c,input);String owner=input.path("owner").asText(),name=input.path("name").asText();OracleDialect.identifier(owner);OracleDialect.identifier(name);
        var result=Profiles.JSON.createObjectNode().put("owner",owner).put("name",name).put("observedAt",System.currentTimeMillis());result.set("target",target.json());
        var rows=roster(job,c,owner,name);if(rows.isEmpty())return result.put("available",false).put("state","UNKNOWN").put("message","Job is absent or not visible. Absence does not prove successful completion; inspect the Oracle log and previous observations.");
        result.set("catalog",rows.get(0));result.put("state",rows.get(0).path("state").asText());
        String sql="""
            DECLARE h NUMBER; s SYS.ku$_Status1220; state VARCHAR2(30); out_json SYS.JSON_OBJECT_T:=SYS.JSON_OBJECT_T();
              logs SYS.JSON_ARRAY_T:=SYS.JSON_ARRAY_T(); i PLS_INTEGER; item SYS.JSON_OBJECT_T; message VARCHAR2(4000);
            BEGIN
              h:=SYS.DBMS_DATAPUMP.ATTACH(?,?);
              SYS.DBMS_DATAPUMP.GET_STATUS(h,15,0,state,s);
              out_json.put('state',state);
              IF s.job_description IS NOT NULL THEN
                out_json.put('guid',RAWTOHEX(s.job_description.guid));
                out_json.put('started',TO_CHAR(s.job_description.start_time,'YYYY-MM-DD HH24:MI:SS'));
              END IF;
              IF s.job_status IS NOT NULL THEN
                out_json.put('percentDone',s.job_status.percent_done); out_json.put('errorCount',s.job_status.error_count);
                out_json.put('bytesProcessed',TO_CHAR(s.job_status.bytes_processed));out_json.put('totalBytes',TO_CHAR(s.job_status.total_bytes));
                out_json.put('phase',s.job_status.phase);out_json.put('restartCount',s.job_status.restart_count);
              END IF;
              IF s.error IS NOT NULL THEN
                i:=s.error.FIRST;
                WHILE i IS NOT NULL AND logs.get_size<20 LOOP
                  message:=SUBSTR(s.error(i).LogText,1,2000);
                  IF REGEXP_LIKE(message,'IDENTIFIED|PASSWORD|CREDENTIAL|SECRET|TOKEN','i') THEN message:='Sensitive Oracle diagnostic omitted; inspect the protected database log.'; END IF;
                  logs.append(message); i:=s.error.NEXT(i);
                END LOOP;
              END IF;
              out_json.put('errors',logs); ?:=out_json.to_clob();
              SYS.DBMS_DATAPUMP.DETACH(h); h:=NULL;
            EXCEPTION WHEN OTHERS THEN
              IF h IS NOT NULL THEN BEGIN SYS.DBMS_DATAPUMP.DETACH(h); EXCEPTION WHEN OTHERS THEN NULL; END; END IF;
              RAISE;
            END;
            """;
        try(CallableStatement statement=c.prepareCall(sql)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setString(1,name);statement.setString(2,owner);statement.registerOutParameter(3,Types.CLOB);statement.execute();Clob clob=statement.getClob(3);
            try{if(clob==null||clob.length()>65536)throw new SQLException("Data Pump status exceeded its bounded allowance");ObjectNode detail=(ObjectNode)Profiles.JSON.readTree(clob.getSubString(1,(int)clob.length()));result.setAll(detail);result.put("available",true);}
            finally{if(clob!=null)clob.free();}
        }catch(SQLException e){if(job.cancelled||e instanceof SQLTimeoutException)throw e;result.put("available",false).put("message","Oracle could not attach/read this Data Pump job (Oracle "+e.getErrorCode()+"). Check roles and the database log; the job may have completed or stopped.");}finally{job.statement=null;}
        return result;
    }
}
