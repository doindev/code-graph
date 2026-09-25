package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectDesigner.*;
import io.doindev.codegraph.dba.ScheduledJobs.Provider;
import io.doindev.codegraph.dba.ScheduledJobs.Sql;
import static io.doindev.codegraph.dba.ScheduledJobs.provider;
import static io.doindev.codegraph.dba.ScheduledJobs.read;
import static io.doindev.codegraph.dba.ScheduledJobs.definition;
import static io.doindev.codegraph.dba.ScheduledJobs.first;
import static io.doindev.codegraph.dba.ScheduledJobs.truth;

/** Scheduler forms feed the existing expiring, single-use object review/apply workflow. */
final class ScheduledJobEditor {
    static ObjectNode load(QueryJobs.Job job,Connection c,JsonNode input)throws Exception{
        boolean create=input.path("creation").asBoolean();ObjectNode target=MetadataTree.request(input.path(create?"target":"parent"));
        Provider p=provider(job,c,target);target=ScheduledJobs.context(job,c,p,target);String database=Objects.toString(c.getCatalog(),str(target,"database"));
        target.put("database",database);String schema=str(target,"schema");if(p.id().equals("events")&&schema.isBlank())target.put("schema",schema=database);
        ObjectNode out=Profiles.JSON.createObjectNode().put("engine",p.engine()).put("kind","scheduled_jobs").put("scheduler",p.id()).put("label",p.label()+" Job").put("database",database).put("creation",create).put("version",c.getMetaData().getDatabaseProductVersion()).put("ddl","").put("ddlComplete",false).put("formSupported",true);
        out.set("target",target);out.putArray("categories").add("General");out.putArray("controls");out.putObject("details");out.putArray("warnings");
        ObjectNode fields=out.putObject("fields");fields.put("name","").put("schema",schema).put("enabled",false).put("description","").put("schedule","").put("command","").put("targetDatabase",database);
        ObjectNode record=Profiles.JSON.createObjectNode();
        if(!create){
            out.set("selection",MetadataActions.request(input));String prefix="scheduled_job:"+p.id()+":";String key=str(input,"key");
            if(!key.startsWith(prefix)||key.length()==prefix.length())throw new IllegalArgumentException("Invalid scheduled job identity");String id=key.substring(prefix.length());out.put("schedulerId",id);
            ObjectNode result=read(job,c,definition(p,target,id),p.id().equals("tasks")?2001:2,0);
            if(result.path("truncated").asBoolean())throw new IllegalArgumentException("Scheduler definition is incomplete; narrow its schema before editing");
            for(JsonNode row:result.path("rows"))if(!p.id().equals("tasks")||str(row,"name").equals(id)){if(!record.isEmpty())throw new IllegalArgumentException("Scheduler returned an ambiguous job identity; refresh before editing");record=(ObjectNode)row;}
            if(record.isEmpty())throw new IllegalArgumentException("Job is no longer visible. Refresh the Scheduled Jobs folder.");
            fields.put("name",first(record,"jobname","event_name","job_name","name","scheduler_job_name","schedule_name","scheduled_job_name","task_name","tk_name","view","label","job"));
            if(fields.path("name").asText().isBlank())fields.put("name",id);
            fields.put("description",first(record,"description","comments","comment","event_comment","remarks","tk_description"));
            fields.put("enabled",truth(first(record,"active","enabled","is_enabled","is_enable","tk_enable"))||first(record,"status","state").equalsIgnoreCase("ENABLED")||str(record,"state").equalsIgnoreCase("started"));
            fields.put("schedule",first(record,"schedule","repeat_interval","cron"));fields.put("command",first(record,"command","event_definition","job_action","definition","query","tk_execute","exec_query","what"));
            fields.put("targetDatabase",first(record,"database","tk_dbs").isBlank()?database:first(record,"database","tk_dbs"));
            out.set("node",Profiles.JSON.createObjectNode().put("key",key).put("oid",id).put("name",str(fields,"name")));
            detail(out,"Details",record.deepCopy());
        }
        out.set("schedulerRecord",record);ObjectNode stable=record.deepCopy();
        for(String key:List.of("last_run_duration","last_start_date","next_run_date","run_count","failure_count","retry_count","last_executed","last_run","next_run","last_run_status","job_running","next_execution","active_execution_id","last_refresh_time","last_refresh_replica","last_success_time","progress","elapsed","read_rows","read_bytes","written_rows","written_bytes","total_rows","last_successful_submission","last_attempted_submission","next_refresh_time","last_exec_time","exec_count","error_code","tk_next_execution","tk_total_executions","tk_total_time","tk_last_execution","next_scheduled_date","next_scheduled_time","tk_sequence"))stable.remove(key);
        if(Set.of("oracle_scheduler","altibase_jobs","clickhouse_refreshes").contains(p.id()))stable.remove("state");
        if(p.id().equals("oracle_legacy"))for(String key:List.of("last_date","last_sec","this_date","this_sec","next_date","next_sec","total_time","failures"))stable.remove(key);
        if(p.id().equals("clickhouse_refreshes")){
            stable.removeAll();for(String key:List.of("database","view","uuid","create_table_query"))if(record.has(key))stable.set(key,record.path(key));
            fields.put("enabled",!str(record,"status").equalsIgnoreCase("Disabled"));fields.put("command",str(record,"create_table_query"));
        }
        out.set("schedulerConfig",stable);
        if(create&&p.id().equals("pg_cron")&&!str(input.path("draft").path("fields"),"name").isBlank()){
            JsonNode existing=read(job,c,new Sql("SELECT jobid FROM cron.job WHERE to_jsonb(job)->>'jobname'=? AND username=current_user",str(input.path("draft").path("fields"),"name")),1,0).path("rows");
            if(!existing.isEmpty())throw new IllegalArgumentException("A pg_cron job with this name already exists. Open it to edit; creating a job never replaces an existing schedule.");
        }
        ObjectForms.field(out,"General","name","Job name","text","",create);
        if(!schema.isBlank())ObjectForms.field(out,"General","schema","Schema","text",schema,false);
        configure(job,c,out,p,record);
        if(!create)recent(job,c,out,p,target,str(out,"schedulerId"));
        category(out,"DDL");ObjectDesigner.fingerprint(c,out);
        if(Profiles.JSON.writeValueAsBytes(out).length>1<<20)throw new IllegalArgumentException("Scheduled job metadata exceeds the 1 MiB editor allowance");return out;
    }
    static void field(ObjectNode out,String category,String id,String label,String type,String fallback,boolean edit,String... choices){ObjectForms.field(out,category,id,label,type,fallback,edit,choices);}
    static void configure(QueryJobs.Job job,Connection c,ObjectNode out,Provider p,ObjectNode r)throws Exception{
        ObjectNode f=(ObjectNode)out.path("fields");boolean create=out.path("creation").asBoolean();
        if(!str(f,"description").isBlank()||Set.of("events","oracle_scheduler","sql_agent","elastic_jobs","tasks","informix_tasks").contains(p.id()))field(out,"General","description","Description","textarea","",Set.of("events","oracle_scheduler","sql_agent","elastic_jobs","tasks","informix_tasks").contains(p.id()));
        switch(p.id()){
            case "apoc_periodic"->{
                out.put("formSupported",false);f.put("enabled",!truth(str(r,"done"))&&!truth(str(r,"cancelled")));field(out,"General","enabled","Active","boolean","false",false);
                warning(out,"APOC exposes runtime job names and intervals, but not their original Cypher or restartable definitions. Use reviewed native Cypher on DDL to create/replace jobs; Delete cancels the selected job. APOC repeat starts work immediately and jobs do not persist across server restarts.");
            }
            case "pg_cron"->{
                JsonNode functions=read(job,c,new Sql("SELECT p.proname, pg_catalog.pg_get_function_identity_arguments(p.oid) AS arguments, pg_catalog.has_function_privilege(p.oid,'EXECUTE') AS executable FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='cron' AND p.proname IN ('schedule','schedule_in_database','alter_job','unschedule') ORDER BY p.oid"),32,0).path("rows");
                out.set("schedulerFunctions",functions);boolean alter=false,named=false,cross=false;
                for(JsonNode row:functions)if(truth(str(row,"executable"))){switch(str(row,"proname")){case "alter_job"->alter=true;case "schedule"->named|=str(row,"arguments").contains("job_name");case "schedule_in_database"->cross=true;default->{}}}
                out.put("cronAlter",alter).put("cronNamed",named).put("cronCross",cross);
                field(out,"General","enabled","Enabled","boolean","false",alter||create&&cross);
                field(out,"Schedule","schedule","Cron schedule","text","0 * * * *",alter||create&&named);
                field(out,"Definition","command","SQL command","sql","",alter||create&&named);
                field(out,"General","targetDatabase","Execution database","text",str(out,"database"),alter||create&&cross);
                if(create&&(!named&&!cross||!alter&&!cross))warning(out,"This pg_cron installation lacks a callable named-job API that can create a disabled job. Use native SQL only after reviewing its activation behavior.");
                warning(out,"Cron expressions use the server's cron.timezone. Stored SQL runs as the job owner when enabled; saving does not invoke it, but enabling a due schedule can start work.");
            }
            case "events"->{
                out.put("mysqlNoBackslashEscapes",str(read(job,c,new Sql("SELECT @@SESSION.sql_mode AS mode"),1,0).path("rows").path(0),"mode").toUpperCase(Locale.ROOT).contains("NO_BACKSLASH_ESCAPES"));
                f.put("eventType",create?"RECURRING":str(r,"event_type")).put("interval",create?"1":str(r,"interval_value")).put("unit",create?"HOUR":str(r,"interval_field"));
                f.put("executeAt",str(r,"execute_at")).put("starts",str(r,"starts")).put("ends",str(r,"ends")).put("timezone",create?"SYSTEM":str(r,"time_zone"));
                field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Schedule","eventType","Schedule type","select","RECURRING",true,"RECURRING","ONE TIME");
                field(out,"Schedule","interval","Repeat interval","text","1",true);field(out,"Schedule","unit","Interval unit","select","HOUR",true,"YEAR","QUARTER","MONTH","WEEK","DAY","HOUR","MINUTE","SECOND","YEAR_MONTH","DAY_HOUR","DAY_MINUTE","DAY_SECOND","HOUR_MINUTE","HOUR_SECOND","MINUTE_SECOND");
                field(out,"Schedule","executeAt","Run once at","text","",true);field(out,"Schedule","starts","Starts at (optional)","text","",true);field(out,"Schedule","ends","Ends at (optional)","text","",true);field(out,"Schedule","timezone","Event time zone","text","SYSTEM",true);
                field(out,"Definition","command","SQL command","sql","",true);warning(out,"Times use the displayed event time zone. Existing definers and completion behavior are preserved. New events are preserved after completion.");
            }
            case "oracle_scheduler"->{
                f.put("jobType",create?"PLSQL_BLOCK":str(r,"job_type"));boolean direct=create||str(r,"program_name").isBlank()&&Set.of("PLSQL_BLOCK","STORED_PROCEDURE").contains(str(r,"job_type"));
                out.put("directJob",direct);field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Definition","jobType","Job type","select","PLSQL_BLOCK",create,"PLSQL_BLOCK","STORED_PROCEDURE");
                field(out,"Definition","command","Job action","sql","",direct);field(out,"Schedule","schedule","Calendar repeat interval","text","FREQ=HOURLY",true);
                if(!direct)warning(out,"This job references a program or uses a different job type. Its action is shown without flattening it; use DDL for program/chain changes.");
            }
            case "sql_agent"->{
                ArrayNode steps=create?Profiles.JSON.createArrayNode():(ArrayNode)read(job,c,new Sql("SELECT * FROM msdb.dbo.sysjobsteps WHERE job_id=CONVERT(uniqueidentifier,?) ORDER BY step_id",str(out,"schedulerId")),100,0).path("rows");
                ObjectNode schedules=create?Profiles.JSON.createObjectNode().set("rows",Profiles.JSON.createArrayNode()):read(job,c,new Sql("SELECT s.*, (SELECT COUNT(*) FROM msdb.dbo.sysjobschedules x WHERE x.schedule_id=s.schedule_id) AS attached_jobs FROM msdb.dbo.sysschedules s JOIN msdb.dbo.sysjobschedules j ON j.schedule_id=s.schedule_id WHERE j.job_id=CONVERT(uniqueidentifier,?) ORDER BY s.schedule_id",str(out,"schedulerId")),100,0);
                if(schedules.path("truncated").asBoolean()||steps.size()>=100)throw new IllegalArgumentException("Job steps or schedules exceed the editing limit");
                ArrayNode stableSteps=steps.deepCopy();for(JsonNode step:stableSteps)for(String key:List.of("last_run_outcome","last_run_duration","last_run_retries","last_run_date","last_run_time"))((ObjectNode)step).remove(key);out.set("schedulerSteps",stableSteps);out.set("schedulerSchedules",schedules.path("rows"));detail(out,"Steps",steps);detail(out,"Schedules",schedules.path("rows"));
                boolean simple=create||steps.size()==1&&str(steps.path(0),"subsystem").equals("TSQL");out.put("singleSqlStep",simple);
                if(!create&&simple){f.put("command",str(steps.path(0),"command")).put("targetDatabase",str(steps.path(0),"database_name"));}
                f.put("startTime",create?"000000":schedules.path("rows").size()==1?String.format("%06d",schedules.path("rows").path(0).path("active_start_time").asInt()):"");
                field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Definition","command","T-SQL job step","sql","",simple);field(out,"Definition","targetDatabase","Step database","text",str(out,"database"),simple);field(out,"Schedule","startTime","Daily start time (HHmmss)","text","000000",create);
                warning(out,"New jobs use one T-SQL step and a daily schedule. Existing step graphs and shared schedules are retained; edit their native details through DDL. SQL Server Agent must be running separately.");
            }
            case "elastic_jobs"->{field(out,"General","enabled","Enabled","boolean","false",true);out.put("formSupported",!create);warning(out,"Elastic-job creation and step/target changes require native jobs.sp_add_job / sp_add_jobstep SQL on DDL. Existing target groups and job versions are retained.");}
            case "tasks"->{
                f.put("warehouse",str(r,"warehouse"));field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Definition","command","Task SQL","sql","",true);field(out,"Schedule","schedule","Schedule (minutes or USING CRON)","text","60 MINUTES",true);field(out,"General","warehouse","Warehouse (blank for serverless)","text","",create);
                warning(out,"Edits suspend an active task before changing it, then restore the chosen state. Task graphs may require suspending their root; such errors are reported without modifying other tasks.");
            }
            case "hana_jobs"->{
                f.put("procedureSchema",str(r,"procedure_schema_name")).put("procedure",str(r,"procedure_name"));field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Schedule","schedule","HANA cron schedule","text","* * * * 0 0 0",true);field(out,"Definition","procedureSchema","Procedure schema","text",str(f,"schema"),create);field(out,"Definition","procedure","Procedure name","text","",create);
            }
            case "hive_queries"->{f.put("namespace",str(out.path("target"),"schedulerNamespace"));field(out,"General","namespace","Scheduler namespace","text","",false);field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Schedule","schedule","Quartz cron schedule","text","0 0 * * * ?",true);field(out,"Definition","command","Hive query","sql","",true);}
            case "oracle_legacy"->{
                f.put("enabled",!str(r,"broken").equalsIgnoreCase("Y"));f.put("schedule",str(r,"interval"));out.put("formSupported",!create);
                field(out,"General","enabled","Enabled (not broken)","boolean","false",!create);field(out,"Definition","command","PL/SQL block","sql","",!create);field(out,"Schedule","schedule","Next-run interval expression","sql","",!create);
                warning(out,"Legacy DBMS_JOB creation uses its native SUBMIT call with an output job number on DDL. Existing jobs keep their owner and next-run date.");
            }
            case "altibase_jobs"->{
                out.put("formSupported",!create);field(out,"General","enabled","Enabled","boolean","false",!create);field(out,"Definition","command","Procedure invocation","sql","",false);
                warning(out,"Enable/disable requires Altibase 6.5.1 or later. Create and schedule changes use native CREATE/ALTER JOB on DDL; scheduler-wide settings are never changed.");
            }
            case "informix_tasks"->{
                f.put("frequency",create?"1 00:00:00":str(r,"tk_frequency")).put("startTime",create?"08:00:00":str(r,"tk_start_time")).put("stopTime",create?"19:00:00":str(r,"tk_stop_time"));
                field(out,"General","enabled","Enabled","boolean","false",true);field(out,"Definition","command","SQL command","sql","",true);field(out,"Definition","targetDatabase","Execution database","text",str(out,"database"),true);
                field(out,"Schedule","frequency","Repeat interval (days HH:mm:ss; blank once)","text","1 00:00:00",true);field(out,"Schedule","startTime","Starts at (HH:mm:ss)","text","08:00:00",true);field(out,"Schedule","stopTime","Stops at (HH:mm:ss; optional)","text","19:00:00",true);
                warning(out,"New entries are TASKs in the MISC group. Existing task/sensor types, weekday choices and result retention are preserved; advanced changes use DDL.");
            }
            case "cockroach_schedules"->{f.put("enabled",!str(r,"schedule_status").equalsIgnoreCase("PAUSED"));field(out,"General","enabled","Enabled","boolean","false",!create);field(out,"Schedule","schedule","Cron schedule","text","",false);out.put("formSupported",!create);warning(out,"Use DDL for CREATE SCHEDULE FOR BACKUP/CHANGEFEED and native ALTER BACKUP SCHEDULE. Existing backup chains are never reconstructed as arbitrary SQL jobs.");}
            default->{out.put("formSupported",false);warning(out,"Inspect the native definition here. Use DDL to submit this scheduler's CREATE/ALTER or administrative calls for review; the editor does not guess unsupported argument signatures.");}
        }
        if(str(f,"schedule").isBlank()&&create){for(JsonNode control:out.path("controls"))if(str(control,"id").equals("schedule")){String fallback=switch(p.id()){case "pg_cron"->"0 * * * *";case "oracle_scheduler"->"FREQ=HOURLY";case "tasks"->"60 MINUTES";case "hana_jobs"->"* * * * 0 0 0";case "hive_queries"->"0 0 * * * ?";default->"";};f.put("schedule",fallback);}}
        out.put("template","");
    }
    static void recent(QueryJobs.Job job,Connection c,ObjectNode out,Provider p,JsonNode target,String id)throws Exception{
        Sql sql=switch(p.id()){
            case "pg_cron"->new Sql("SELECT * FROM cron.job_run_details WHERE jobid=?::bigint ORDER BY runid DESC",id);
            case "oracle_scheduler"->new Sql("SELECT * FROM SYS.ALL_SCHEDULER_JOB_RUN_DETAILS WHERE OWNER=? AND JOB_NAME=? ORDER BY LOG_ID DESC",str(target,"schema"),id);
            case "sql_agent"->new Sql("SELECT * FROM msdb.dbo.sysjobhistory WHERE job_id=CONVERT(uniqueidentifier,?) ORDER BY instance_id DESC",id);
            case "elastic_jobs"->new Sql("SELECT * FROM jobs.job_executions WHERE job_name=? AND step_id IS NULL ORDER BY start_time DESC",id);
            case "db2_ats"->new Sql("SELECT * FROM SYSTOOLS.ADMIN_TASK_STATUS WHERE NAME=? ORDER BY BEGIN_TIME DESC",id);
            case "informix_tasks"->new Sql("SELECT * FROM sysadmin:ph_run WHERE run_task_id=? ORDER BY run_id DESC",id);
            default->null;
        };
        if(sql==null)return;ObjectCatalog.optional(c,out,"Recent runs",()->{ObjectNode rows=read(job,c,sql,25,0);detail(out,"Recent runs",rows.path("rows"));if(rows.path("truncated").asBoolean())warning(out,"Recent runs shows the newest 25 records only.");});
    }
    static String literal(String engine,String value){
        if(value.indexOf('\0')>=0||value.length()>65536)throw new IllegalArgumentException("Invalid or oversized scheduler value");
        if(engine.equals("postgresql"))return "E'"+value.replace("\\","\\\\").replace("'","''")+"'";
        if(Set.of("hive","neo4j").contains(engine))return "'"+value.replace("\\","\\\\").replace("'","\\'")+"'";
        if(engine.equals("mysql"))return "CONVERT(X'"+HexFormat.of().formatHex(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"' USING utf8mb4)";
        return (engine.equals("sqlserver")?"N":"")+"'"+value.replace("'","''")+"'";
    }
    static String required(JsonNode f,String key){String s=str(f,key);if(s.isBlank()||s.length()>65536||s.indexOf('\0')>=0)throw new IllegalArgumentException("Enter a valid "+key);return s;}
    static String number(String s){if(!s.matches("[0-9]{1,19}"))throw new IllegalArgumentException("Invalid numeric job identity");return s;}
    static boolean changed(JsonNode old,JsonNode f,String... keys){for(String key:keys)if(!Objects.equals(old.path(key),f.path(key)))return true;return false;}
    static List<String> compile(ObjectNode base,JsonNode draft){
        JsonNode f=draft.path("fields"),old=base.path("fields");boolean create=base.path("creation").asBoolean();String p=str(base,"scheduler"),e=str(base,"engine"),name=create?required(f,"name"):str(old,"name"),schema=str(old,"schema"),id=str(base,"schedulerId");boolean enabled=f.path("enabled").asBoolean();
        if(!base.path("formSupported").asBoolean())throw new IllegalArgumentException("Use the native SQL editor on DDL for this scheduler");
        var editable=new HashSet<String>();for(JsonNode control:base.path("controls"))if(control.path("editable").asBoolean())editable.add(str(control,"id"));
        var names=f.fieldNames();while(names.hasNext()){String key=names.next();if(changed(old,f,key)&&!editable.contains(key))throw new IllegalArgumentException("The "+key+" property cannot be changed by this scheduler form");}
        List<String> commands=new ArrayList<>();java.util.function.Function<String,String> lit=v->literal(e,v);
        String target=ObjectForms.qualified(e,schema,name);
        switch(p){
            case "pg_cron"->{
                if(create){
                    String args=lit.apply(name)+", "+lit.apply(required(f,"schedule"))+", "+lit.apply(required(f,"command"));
                    if(base.path("cronCross").asBoolean())commands.add("SELECT cron.schedule_in_database("+args+", "+lit.apply(required(f,"targetDatabase"))+", active := "+enabled+")");
                    else if(base.path("cronNamed").asBoolean()&&base.path("cronAlter").asBoolean()&&str(f,"targetDatabase").equals(str(base,"database")))commands.add("SELECT cron.alter_job(cron.schedule("+args+"), active := "+enabled+")");
                    else throw new IllegalArgumentException("This pg_cron version/account cannot create a named job with the selected database and enabled state");
                }else{
                    if(!base.path("cronAlter").asBoolean())throw new IllegalArgumentException("cron.alter_job is unavailable or EXECUTE permission is missing");
                    var changes=new ArrayList<String>();for(String key:List.of("schedule","command","targetDatabase"))if(changed(old,f,key))changes.add((key.equals("targetDatabase")?"database":key)+" := "+lit.apply(required(f,key)));
                    if(changed(old,f,"enabled"))changes.add("active := "+enabled);if(!changes.isEmpty())commands.add("SELECT cron.alter_job("+number(id)+", "+String.join(", ",changes)+")");
                }
            }
            case "events"->{
                if(!create&&!changed(old,f,"description","enabled","eventType","interval","unit","executeAt","starts","ends","timezone","command"))break;
                String prefix=create?"CREATE EVENT ":"ALTER "+definer(str(base.path("schedulerRecord"),"definer"))+"EVENT ";StringBuilder sql=new StringBuilder(prefix).append(target);
                if(create||changed(old,f,"eventType","interval","unit","executeAt","starts","ends","timezone"))sql.append(" ON SCHEDULE ").append(eventSchedule(f));
                if(create)sql.append(" ON COMPLETION PRESERVE");if(create||changed(old,f,"enabled"))sql.append(enabled?" ENABLE":" DISABLE");
                if(create||changed(old,f,"description"))sql.append(" COMMENT '").append(mysqlQuoted(str(f,"description"),base.path("mysqlNoBackslashEscapes").asBoolean())).append("'");
                if(create||changed(old,f,"command"))sql.append(" DO ").append(required(f,"command"));
                commands.add("SET SESSION time_zone = "+lit.apply(required(f,"timezone")));commands.add(sql.toString());
            }
            case "oracle_scheduler"->{
                String jobName=lit.apply(target);boolean attributes=changed(old,f,"schedule","command","description");
                if(create){String type=required(f,"jobType");if(!Set.of("PLSQL_BLOCK","STORED_PROCEDURE").contains(type))throw new IllegalArgumentException("Unsupported Oracle job type");commands.add("BEGIN SYS.DBMS_SCHEDULER.CREATE_JOB(job_name => "+jobName+", job_type => "+lit.apply(type)+", job_action => "+lit.apply(required(f,"command"))+", repeat_interval => "+lit.apply(required(f,"schedule"))+", enabled => FALSE, auto_drop => FALSE, comments => "+lit.apply(str(f,"description"))+"); END;");}
                else{
                    if(attributes&&old.path("enabled").asBoolean())commands.add("BEGIN SYS.DBMS_SCHEDULER.DISABLE("+jobName+"); END;");
                    for(String key:List.of("schedule","command","description"))if(changed(old,f,key))commands.add("BEGIN SYS.DBMS_SCHEDULER.SET_ATTRIBUTE("+jobName+", "+lit.apply(switch(key){case "schedule"->"repeat_interval";case "command"->"job_action";default->"comments";})+", "+lit.apply(str(f,key))+"); END;");
                }
                if(enabled&&(create||attributes||changed(old,f,"enabled")))commands.add("BEGIN SYS.DBMS_SCHEDULER.ENABLE("+jobName+"); END;");
                else if(!enabled&&!create&&old.path("enabled").asBoolean()&&!attributes)commands.add("BEGIN SYS.DBMS_SCHEDULER.DISABLE("+jobName+"); END;");
            }
            case "sql_agent"->{
                String job=create?"@job_name="+lit.apply(name):"@job_id="+lit.apply(id);
                if(create){
                    String time=required(f,"startTime");if(!time.matches("(?:[01][0-9]|2[0-3])[0-5][0-9][0-5][0-9]"))throw new IllegalArgumentException("Daily start time must be HHmmss");
                    commands.add("EXEC msdb.dbo.sp_add_job "+job+", @enabled=0, @description="+lit.apply(str(f,"description")));
                    commands.add("EXEC msdb.dbo.sp_add_jobstep "+job+", @step_name=N'SQL', @subsystem=N'TSQL', @command="+lit.apply(required(f,"command"))+", @database_name="+lit.apply(required(f,"targetDatabase")));
                    commands.add("EXEC msdb.dbo.sp_add_jobschedule "+job+", @name="+lit.apply(name+" schedule")+", @freq_type=4, @freq_interval=1, @active_start_time="+Integer.parseInt(time));commands.add("EXEC msdb.dbo.sp_add_jobserver "+job);
                }else if(changed(old,f,"command","targetDatabase"))commands.add("EXEC msdb.dbo.sp_update_jobstep "+job+", @step_id="+number(str(base.path("schedulerSteps").path(0),"step_id"))+", @command="+lit.apply(required(f,"command"))+", @database_name="+lit.apply(required(f,"targetDatabase")));
                if(create&&enabled||!create&&changed(old,f,"enabled","description"))commands.add("EXEC msdb.dbo.sp_update_job "+job+", @enabled="+(enabled?1:0)+", @description="+lit.apply(str(f,"description")));
            }
            case "elastic_jobs"->{if(changed(old,f,"enabled","description"))commands.add("EXEC jobs.sp_update_job @job_name="+lit.apply(name)+", @enabled="+(enabled?1:0)+", @description="+lit.apply(str(f,"description")));}
            case "tasks"->{
                String task=ObjectForms.q(e,str(base,"database"))+"."+target;boolean alters=changed(old,f,"schedule","command","description");
                if(create)commands.add("CREATE TASK "+task+(str(f,"warehouse").isBlank()?"":" WAREHOUSE = "+ObjectForms.q(e,str(f,"warehouse")))+" SCHEDULE = "+lit.apply(required(f,"schedule"))+" COMMENT = "+lit.apply(str(f,"description"))+" AS "+required(f,"command"));
                else{
                    if(alters&&old.path("enabled").asBoolean())commands.add("ALTER TASK "+task+" SUSPEND");
                    if(changed(old,f,"schedule"))commands.add("ALTER TASK "+task+" SET SCHEDULE = "+lit.apply(required(f,"schedule")));
                    if(changed(old,f,"description"))commands.add("ALTER TASK "+task+" SET COMMENT = "+lit.apply(str(f,"description")));
                    if(changed(old,f,"command"))commands.add("ALTER TASK "+task+" MODIFY AS "+required(f,"command"));
                }
                if(enabled&&(create||alters||changed(old,f,"enabled")))commands.add("ALTER TASK "+task+" RESUME");else if(!create&&!enabled&&old.path("enabled").asBoolean()&&!alters)commands.add("ALTER TASK "+task+" SUSPEND");
            }
            case "hana_jobs"->{if(create)commands.add("CREATE SCHEDULER JOB "+target+" CRON "+lit.apply(required(f,"schedule"))+(enabled?" ENABLE":" DISABLE")+" PROCEDURE "+ObjectForms.qualified(e,required(f,"procedureSchema"),required(f,"procedure")));else if(changed(old,f,"schedule","enabled"))commands.add("ALTER SCHEDULER JOB "+target+" CRON "+lit.apply(required(f,"schedule"))+(enabled?" ENABLE":" DISABLE"));}
            case "hive_queries"->{
                String job="`"+name.replace("`","``")+"`";
                if(create)commands.add("CREATE SCHEDULED QUERY "+job+" CRON "+lit.apply(required(f,"schedule"))+(enabled?" ENABLED":" DISABLED")+" AS "+required(f,"command"));
                else{if(changed(old,f,"schedule"))commands.add("ALTER SCHEDULED QUERY "+job+" CRON "+lit.apply(required(f,"schedule")));if(changed(old,f,"command"))commands.add("ALTER SCHEDULED QUERY "+job+" AS "+required(f,"command"));if(changed(old,f,"enabled"))commands.add("ALTER SCHEDULED QUERY "+job+(enabled?" ENABLED":" DISABLED"));}
            }
            case "oracle_legacy"->{
                if(changed(old,f,"command"))commands.add("BEGIN SYS.DBMS_JOB.WHAT("+number(id)+", "+lit.apply(required(f,"command"))+"); END;");
                if(changed(old,f,"schedule"))commands.add("BEGIN SYS.DBMS_JOB.INTERVAL("+number(id)+", "+lit.apply(str(f,"schedule"))+"); END;");
                if(changed(old,f,"enabled"))commands.add("DECLARE v_next DATE; BEGIN SELECT next_date INTO v_next FROM SYS.USER_JOBS WHERE job="+number(id)+"; SYS.DBMS_JOB.BROKEN("+number(id)+", "+(!enabled?"TRUE":"FALSE")+", v_next); END;");
            }
            case "altibase_jobs"->{if(changed(old,f,"enabled"))commands.add("ALTER JOB "+ObjectForms.q(e,name)+" SET "+(enabled?"ENABLE":"DISABLE"));}
            case "informix_tasks"->{
                if(required(f,"command").getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2048)throw new IllegalArgumentException("Informix task commands are limited to 2048 bytes");
                LinkedHashMap<String,String> values=new LinkedHashMap<>();values.put("tk_name",lit.apply(name));values.put("tk_type","'TASK'");values.put("tk_enable",enabled?"'t'":"'f'");values.put("tk_description",lit.apply(str(f,"description")));values.put("tk_dbs",lit.apply(required(f,"targetDatabase")));values.put("tk_execute",lit.apply(required(f,"command")));
                String frequency=str(f,"frequency").strip();if(!frequency.isEmpty()&&!frequency.matches("[0-9]{1,2} [0-2][0-9]:[0-5][0-9]:[0-5][0-9](?:\\.[0-9]+)?"))throw new IllegalArgumentException("Enter a repeat interval as days HH:mm:ss");
                values.put("tk_frequency",frequency.isEmpty()?"NULL":"INTERVAL ("+frequency+") DAY TO SECOND");
                for(String key:List.of("startTime","stopTime")){String time=str(f,key).strip();if(!time.isBlank()&&!time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"))throw new IllegalArgumentException("Enter a time as HH:mm:ss");values.put(key.equals("startTime")?"tk_start_time":"tk_stop_time",time.isBlank()?"NULL":"DATETIME("+time+") HOUR TO SECOND");}
                if(create)commands.add("INSERT INTO sysadmin:ph_task ("+String.join(", ",values.keySet())+") VALUES ("+String.join(", ",values.values())+")");
                else{var assignments=new ArrayList<String>();String[][] mapping={{"enabled","tk_enable"},{"description","tk_description"},{"targetDatabase","tk_dbs"},{"command","tk_execute"},{"frequency","tk_frequency"},{"startTime","tk_start_time"},{"stopTime","tk_stop_time"}};for(String[] pair:mapping)if(changed(old,f,pair[0]))assignments.add(pair[1]+"="+values.get(pair[1]));if(!assignments.isEmpty())commands.add("UPDATE sysadmin:ph_task SET "+String.join(", ",assignments)+" WHERE tk_id="+number(id));}
            }
            case "cockroach_schedules"->{if(changed(old,f,"enabled"))commands.add((enabled?"RESUME":"PAUSE")+" SCHEDULE "+number(id));}
            default->throw new IllegalArgumentException("Use native SQL for this scheduler");
        }
        if(commands.isEmpty())throw new IllegalArgumentException("There are no changes to save");return commands;
    }
    static String mysqlQuoted(String value,boolean noBackslash){return (noBackslash?value:value.replace("\\","\\\\")).replace("'","''");}
    static String definer(String value){int at=value.lastIndexOf('@');if(at<1)throw new IllegalArgumentException("The event definer is unavailable; use native SQL after reviewing ownership");return "DEFINER="+ObjectForms.q("mysql",value.substring(0,at))+"@"+ObjectForms.q("mysql",value.substring(at+1))+" ";}
    static String eventSchedule(JsonNode f){
        java.util.function.Function<String,String> timestamp=key->{String value=required(f,key);if(!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?"))throw new IllegalArgumentException("Use YYYY-MM-DD HH:mm:ss for "+key);return "'"+value+"'";};
        if(str(f,"eventType").equals("ONE TIME"))return "AT "+timestamp.apply("executeAt");
        if(!str(f,"eventType").equals("RECURRING")||!str(f,"interval").matches("[0-9][0-9 :.-]{0,39}")||!Set.of("YEAR","QUARTER","MONTH","WEEK","DAY","HOUR","MINUTE","SECOND","YEAR_MONTH","DAY_HOUR","DAY_MINUTE","DAY_SECOND","HOUR_MINUTE","HOUR_SECOND","MINUTE_SECOND").contains(str(f,"unit")))throw new IllegalArgumentException("Choose a valid event interval and unit");
        return "EVERY '"+str(f,"interval")+"' "+str(f,"unit")+(str(f,"starts").isBlank()?"":" STARTS "+timestamp.apply("starts"))+(str(f,"ends").isBlank()?"":" ENDS "+timestamp.apply("ends"));
    }
    static MetadataActions.Plan actionPlan(QueryJobs.Job job,Connection c,JsonNode selection)throws Exception{
        ObjectNode snapshot=load(job,c,selection);String e=str(snapshot,"engine"),p=str(snapshot,"scheduler"),name=str(snapshot.path("fields"),"name"),id=str(snapshot,"schedulerId"),target=ObjectForms.qualified(e,str(snapshot.path("fields"),"schema"),name);
        String drop=switch(p){
            case "apoc_periodic"->"/*+ NEO4J FORCE_CYPHER */ CALL apoc.periodic.cancel("+literal(e,name)+")";
            case "pg_cron"->"SELECT cron.unschedule("+number(id)+")";
            case "events"->"DROP EVENT "+target;
            case "oracle_scheduler"->"BEGIN SYS.DBMS_SCHEDULER.DROP_JOB("+literal(e,target)+", force => FALSE); END;";
            case "oracle_legacy"->"BEGIN SYS.DBMS_JOB.REMOVE("+number(id)+"); END;";
            case "sql_agent"->"EXEC msdb.dbo.sp_delete_job @job_id="+literal(e,id)+", @delete_unused_schedule=0";
            case "elastic_jobs"->"EXEC jobs.sp_delete_job @job_name="+literal(e,name);
            case "tasks"->"DROP TASK "+ObjectForms.q(e,str(snapshot,"database"))+"."+target;
            case "hana_jobs"->"DROP SCHEDULER JOB "+target;
            case "hive_queries"->"DROP SCHEDULED QUERY `"+name.replace("`","``")+"`";
            case "cockroach_schedules"->"DROP SCHEDULE "+number(id);
            case "altibase_jobs"->"DROP JOB "+ObjectForms.q(e,name);
            case "informix_tasks"->"DELETE FROM sysadmin:ph_task WHERE tk_id="+number(id);
            case "db2_ats"->"CALL SYSPROC.ADMIN_TASK_REMOVE("+literal(e,name)+", NULL)";
            default->"";
        };
        return new MetadataActions.Plan(name,"SCHEDULED JOB",target,drop,"",drop.isBlank()?"Use reviewed native DDL for this scheduler's removal API.":"Rename jobs through their native scheduler API. Running-job behavior during removal follows the database scheduler API.",str(snapshot,"fingerprint"));
    }
    private ScheduledJobEditor(){}
}
