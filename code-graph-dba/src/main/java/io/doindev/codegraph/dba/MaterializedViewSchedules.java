package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.DayOfWeek;
import java.util.*;

import static io.doindev.codegraph.dba.ObjectDesigner.str;

/** Database-owned materialized-view refresh schedules. Volatile run state never enters a review fingerprint. */
final class MaterializedViewSchedules {
    record Command(String sql,String database,String phase,String purpose) {}
    private static final Set<String> DRAFT_KEYS=Set.of("enabled","preset","intervalMinutes","minute","hour","dayOfWeek","dayOfMonth","expression","method","refreshMode","beginAt","endAt","maxInvocations");
    private static final String MARKER="code-graph:managed-materialized-view-refresh";

    static void populate(QueryJobs.Job job,Connection c,ObjectNode out)throws Exception {
        if(!str(out,"kind").equals("materialized_views"))return;
        ObjectDesigner.category(out,"Refresh");
        ObjectNode schedule=out.putObject("refreshSchedule");
        String provider=str(out,"engine");schedule.put("provider",provider).put("editable",false).put("state","unsupported")
                .put("database",str(out,"database")).put("timezone","Database server timezone");
        schedule.putArray("relatedJobs");schedule.putArray("history");schedule.putArray("guidance");
        ObjectNode capabilities=schedule.putObject("capabilities");
        capabilities.putArray("presets").add("interval").add("hourly").add("daily").add("weekly").add("monthly").add("advanced");
        ObjectNode config=defaultConfig(provider);schedule.set("config",config);
        switch(provider){
            case "postgresql"->postgres(job,c,out,schedule);
            case "oracle"->oracle(job,c,out,schedule);
            case "db2"->db2(job,c,out,schedule);
            case "snowflake"->snowflake(job,c,out,schedule);
            default->guidance(schedule,"Scheduling is unavailable","This database adapter has no reviewed materialized-view scheduling implementation.","");
        }
    }

    static ObjectNode stable(JsonNode schedule){
        ObjectNode out=Profiles.JSON.createObjectNode();if(!schedule.isObject())return out;
        for(String key:List.of("provider","state","editable","database","controlDatabase","timezone","managedName","managedJobId","sharedGroup","automatic"))
            if(schedule.has(key))out.set(key,schedule.path(key).deepCopy());
        if(schedule.has("config"))out.set("config",schedule.path("config").deepCopy());
        if(schedule.has("signatures"))out.set("signatures",schedule.path("signatures").deepCopy());
        return out;
    }

    static List<Command> compile(ObjectNode snapshot,JsonNode draft){
        if(!str(snapshot,"kind").equals("materialized_views"))return List.of();
        JsonNode schedule=snapshot.path("refreshSchedule"),requested=draft.path("schedule");
        if(!requested.isObject())throw new IllegalArgumentException("Materialized View drafts require a refresh schedule configuration");
        requested.fieldNames().forEachRemaining(key->{if(!DRAFT_KEYS.contains(key))throw new IllegalArgumentException("Unsupported refresh schedule property: "+key);});
        ObjectNode normalized=normalize(schedule,requested);String provider=str(schedule,"provider");
        boolean managedRename=schedule.has("managedJob")&&Set.of("postgresql","db2").contains(provider)
                &&(!str(snapshot.path("fields"),"schema").equals(str(draft.path("fields"),"schema"))||!str(snapshot.path("fields"),"name").equals(str(draft.path("fields"),"name")));
        if(normalized.equals(schedule.path("config"))&&!managedRename)return List.of();
        if(provider.equals("snowflake"))throw new IllegalArgumentException("Snowflake maintains materialized views automatically; a custom schedule cannot be saved");
        if(!schedule.path("editable").asBoolean())throw new IllegalArgumentException(schedule.path("message").asText("Refresh scheduling is unavailable for this object and account"));
        return switch(provider){case "postgresql"->postgresCommands(snapshot,schedule,normalized,draft);case "oracle"->oracleCommands(snapshot,schedule,normalized,draft);case "db2"->db2Commands(snapshot,schedule,normalized,draft);default->throw new IllegalArgumentException("Refresh scheduling is unsupported for this database");};
    }

    static List<Command> deleteCleanup(QueryJobs.Job job,Connection c,String engine,String database,String schema,String name)throws Exception {
        String managed=managedName(engine,database,schema,name),target=ObjectForms.qualified(engine,schema,name);
        if(engine.equals("postgresql")){
            JsonNode installed=safeRows(job,c,"SELECT extversion FROM pg_extension WHERE extname='pg_cron'");if(installed.isEmpty())return List.of();
            JsonNode jobs=cronJobs(job,c);for(JsonNode row:jobs)if(str(row,"jobname").equals(managed)||managedCommand(row,target))
                return List.of(new Command("SELECT cron.unschedule("+longValue(str(row,"jobid"),"pg_cron job id")+")",Objects.toString(c.getCatalog(),database),"scheduler","Remove the code-graph-managed refresh job"));
        }
        if(engine.equals("db2")){
            String task=managed,helper=managedHelper(database,schema,name);List<Command> commands=new ArrayList<>();
            JsonNode tasks=safeRows(job,c,"SELECT * FROM TABLE(SYSPROC.ADMIN_TASK_LIST()) AS T");
            for(JsonNode row:tasks)if(first(row,"name","taskname","task_name").equals(task)){commands.add(new Command("CALL SYSPROC.ADMIN_TASK_REMOVE("+literal(task)+", NULL)",database,"scheduler","Remove the code-graph-managed DB2 task"));break;}
            JsonNode procedures=safeRows(job,c,"SELECT 1 AS FOUND FROM SYSCAT.ROUTINES WHERE ROUTINESCHEMA=? AND ROUTINENAME=? FETCH FIRST 1 ROW ONLY",schema,helper);
            if(!procedures.isEmpty())commands.add(new Command("DROP PROCEDURE "+ObjectForms.qualified("db2",schema,helper),database,"scheduler","Remove the code-graph-managed DB2 refresh helper"));
            return commands;
        }
        return List.of();
    }

    static String postgresControlDatabase(QueryJobs.Job job,Connection c,String fallback)throws Exception {
        JsonNode row=safeRows(job,c,"SELECT current_setting('cron.database_name',true) AS control_database").path(0);
        String control=str(row,"control_database");return control.isBlank()?fallback:control;
    }
    private static ObjectNode defaultConfig(String provider){
        ObjectNode c=Profiles.JSON.createObjectNode().put("enabled",false).put("preset","daily").put("intervalMinutes",60)
                .put("minute",0).put("hour",2).put("dayOfWeek","MON").put("dayOfMonth",1).put("expression","")
                .put("method",provider.equals("oracle")?"FORCE":"standard").put("refreshMode","on_demand")
                .put("beginAt","").put("endAt","").put("maxInvocations",0);
        return c;
    }

    private static void postgres(QueryJobs.Job job,Connection c,ObjectNode out,ObjectNode schedule)throws Exception {
        ObjectNode caps=(ObjectNode)schedule.path("capabilities");caps.putArray("methods").add("standard").add("concurrent");
        String current=Objects.toString(c.getCatalog(),str(out,"database"));
        JsonNode settings=safeRows(job,c,"SELECT current_setting('cron.database_name',true) AS control_database,current_setting('cron.timezone',true) AS timezone,current_setting('shared_preload_libraries',true) AS preload").path(0);
        String control=str(settings,"control_database");if(control.isBlank())control=current;
        schedule.put("controlDatabase",control).put("timezone",str(settings,"timezone").isBlank()?"GMT":str(settings,"timezone"));
        JsonNode available=safeRows(job,c,"SELECT default_version,installed_version FROM pg_available_extensions WHERE name='pg_cron'");
        JsonNode installed=safeRows(job,c,"SELECT extversion FROM pg_extension WHERE extname='pg_cron'");
        boolean isInstalled=!installed.isEmpty(),isAvailable=!available.isEmpty();
        boolean preloaded=Arrays.stream(str(settings,"preload").split(",")).map(String::strip).anyMatch("pg_cron"::equals);
        schedule.put("packageAvailable",isAvailable).put("extensionInstalled",isInstalled).put("preloaded",preloaded);
        boolean populated=out.path("fields").path("populate").asBoolean();
        boolean unique=truth(safeRows(job,c,"SELECT EXISTS(SELECT 1 FROM pg_index WHERE indrelid=?::oid AND indisunique AND indisvalid AND indpred IS NULL AND indexprs IS NULL) AS eligible",out.path("node").path("oid").asText("0")).path(0).path("eligible"));
        caps.put("concurrentEligible",!out.path("creation").asBoolean()&&populated&&unique);
        if(!control.equals(current)){
            schedule.put("state","control_database").put("message","pg_cron is configured in "+control+". Open or save a connection targeting that database to inspect and manage its jobs.");
            pgGuidance(schedule,out,available,preloaded,true);return;
        }
        if(!isInstalled){
            schedule.put("state",isAvailable?"extension_not_created":"package_missing")
                    .put("message",isAvailable?"pg_cron is available on this server but is not created in the control database.":"pg_cron is neither installed nor available on this PostgreSQL server.");
            pgGuidance(schedule,out,available,preloaded,false);return;
        }
        JsonNode signatures=safeRows(job,c,"SELECT p.proname AS name,pg_get_function_identity_arguments(p.oid) AS arguments FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='cron' AND p.proname IN ('schedule','schedule_in_database','alter_job','unschedule') ORDER BY p.proname,p.oid");
        schedule.set("signatures",signatures);
        boolean usage=truth(safeRows(job,c,"SELECT has_schema_privilege(current_user,'cron','USAGE') AND bool_and(has_function_privilege(p.oid,'EXECUTE')) AS allowed FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='cron' AND p.proname IN ('schedule','unschedule')").path(0).path("allowed"));
        boolean canSchedule=usage&&containsFunction(signatures,"schedule")&&containsFunction(signatures,"unschedule");
        schedule.put("editable",canSchedule).put("state",canSchedule?"available":"permission_missing")
                .put("message",canSchedule?"pg_cron is available. code-graph manages one named job for this materialized view.":"pg_cron is installed, but this account cannot use the required cron functions.");
        if(!canSchedule)guidance(schedule,"Grant scheduler access","Grant USAGE on schema cron and EXECUTE on the installed cron functions to this database role.","GRANT USAGE ON SCHEMA cron TO "+ObjectForms.q("postgresql",Objects.toString(c.getMetaData().getUserName(),"role"))+";");
        caps.put("scheduleInDatabase",containsFunction(signatures,"schedule_in_database"));
        String schema=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name"),target=name.isBlank()?"":ObjectForms.qualified("postgresql",schema,name);
        String managed=managedName("postgresql",str(out,"database"),schema,name);schedule.put("managedName",managed);
        JsonNode jobs=cronJobs(job,c),managedJob=null;ArrayNode related=(ArrayNode)schedule.path("relatedJobs");
        for(JsonNode row:jobs){if(str(row,"jobname").equals(managed)||!target.isBlank()&&managedCommand(row,target)){managedJob=row;continue;}if(!target.isBlank()&&targets(row,target))related.add(row.deepCopy());}
        if(managedJob!=null){
            schedule.put("managedJobId",str(managedJob,"jobid"));ObjectNode config=(ObjectNode)schedule.path("config");
            config.put("enabled",true).put("preset","advanced").put("expression",str(managedJob,"schedule"))
                    .put("method",str(managedJob,"command").toUpperCase(Locale.ROOT).contains(" CONCURRENTLY ")?"concurrent":"standard");
            schedule.set("managedJob",managedJob.deepCopy());
            JsonNode history=safeRows(job,c,"SELECT status,return_message,start_time,end_time FROM cron.job_run_details WHERE jobid=?::bigint ORDER BY runid DESC LIMIT 25",str(managedJob,"jobid"));
            schedule.set("history",history);if(!history.isEmpty())schedule.set("lastRun",history.path(0).deepCopy());
        }
        if(!related.isEmpty())schedule.put("externalWarning","Other visible pg_cron jobs appear to target this view. They are read-only and will not be changed.");
    }

    private static void pgGuidance(ObjectNode schedule,ObjectNode out,JsonNode available,boolean preloaded,boolean otherControl){
        String version=str(out,"version"),major=version.replaceFirst("[^0-9].*$","");if(major.isBlank())major="<major>";
        if(available.isEmpty())guidance(schedule,"Install the matching server package","Install pg_cron for this PostgreSQL server build. On Debian/Ubuntu packages this is commonly postgresql-"+major+"-cron; use the package supplied for the actual server distribution.","sudo apt install postgresql-"+major+"-cron");
        if(!preloaded)guidance(schedule,"Preload pg_cron","Add pg_cron to shared_preload_libraries and choose the single database in which the extension and job catalog will live.","shared_preload_libraries = 'pg_cron'\ncron.database_name = '"+str(schedule,"controlDatabase").replace("'","''")+"'");
        guidance(schedule,"Restart PostgreSQL","A server restart is required after changing shared_preload_libraries.","");
        if(!otherControl)guidance(schedule,"Create and grant the extension","Run this in the configured cron.database_name after the package is installed and PostgreSQL has restarted.","CREATE EXTENSION pg_cron;\nGRANT USAGE ON SCHEMA cron TO <role>;");
    }

    private static void oracle(QueryJobs.Job job,Connection c,ObjectNode out,ObjectNode schedule)throws Exception {
        ObjectNode caps=(ObjectNode)schedule.path("capabilities");caps.putArray("methods").add("FORCE").add("FAST").add("COMPLETE");ArrayNode modes=caps.putArray("refreshModes");modes.add("on_demand").add("scheduled").add("on_commit");String version=c.getMetaData().getDatabaseProductVersion();if(c.getMetaData().getDatabaseMajorVersion()>12||version.matches("(?s)^12\\.[2-9].*"))modes.add("on_statement");
        schedule.put("state","available").put("editable",true).put("timezone","Oracle database session timezone")
                .put("message","Oracle stores materialized-view refresh method and timing with the materialized view.");
        if(out.path("creation").asBoolean())return;
        String schema=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name");
        JsonNode mview=safeRows(job,c,"SELECT BUILD_MODE,REFRESH_MODE,REFRESH_METHOD,LAST_REFRESH_TYPE,LAST_REFRESH_DATE,STALENESS,COMPILE_STATE FROM ALL_MVIEWS WHERE OWNER=? AND MVIEW_NAME=?",schema,name).path(0);
        if(!mview.isMissingNode()){schedule.set("status",mview.deepCopy());ObjectNode config=(ObjectNode)schedule.path("config");config.put("method",str(mview,"refresh_method").isBlank()?"FORCE":str(mview,"refresh_method").toUpperCase(Locale.ROOT));String mode=str(mview,"refresh_mode").toUpperCase(Locale.ROOT);config.put("refreshMode",mode.contains("COMMIT")?"on_commit":mode.contains("STATEMENT")?"on_statement":"on_demand");}
        JsonNode children=safeRows(job,c,"SELECT REFGROUP,JOB,NEXT_DATE,INTERVAL,BROKEN FROM ALL_REFRESH_CHILDREN WHERE ROWNER=? AND RNAME=?",schema,name);
        if(!children.isEmpty()){
            JsonNode child=children.path(0);schedule.set("nativeSchedule",child.deepCopy());ObjectNode config=(ObjectNode)schedule.path("config");
            config.put("enabled",!truth(child.path("broken"))).put("refreshMode","scheduled").put("preset","advanced").put("expression",str(child,"interval"));
            String group=str(child,"refgroup");schedule.put("sharedGroup",group);
            JsonNode members=safeRows(job,c,"SELECT ROWNER,RNAME FROM ALL_REFRESH_CHILDREN WHERE REFGROUP=?",group);schedule.set("relatedJobs",members.deepCopy());
            if(members.size()>1){schedule.put("editable",false).put("state","shared_refresh_group").put("message","This materialized view belongs to a shared Oracle refresh group. Its schedule is read-only here because changing it would affect other objects.");}
        }
    }

    private static void db2(QueryJobs.Job job,Connection c,ObjectNode out,ObjectNode schedule)throws Exception {
        ObjectNode caps=(ObjectNode)schedule.path("capabilities");caps.putArray("methods").add("deferred");caps.put("minimumIntervalMinutes",5);
        schedule.put("timezone","DB2 server local timezone");String schema=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name");
        boolean ats=!safeRows(job,c,"SELECT 1 AS AVAILABLE FROM SYSCAT.ROUTINES WHERE ROUTINESCHEMA='SYSPROC' AND ROUTINENAME='ADMIN_TASK_ADD' FETCH FIRST 1 ROW ONLY").isEmpty();
        String refresh="";if(!out.path("creation").asBoolean()){JsonNode row=safeRows(job,c,"SELECT REFRESH,STATUS,CREATE_TIME,ALTER_TIME FROM SYSCAT.TABLES WHERE TABSCHEMA=? AND TABNAME=?",schema,name).path(0);if(!row.isMissingNode()){schedule.set("status",row.deepCopy());refresh=str(row,"refresh");}}
        boolean immediate=refresh.equalsIgnoreCase("I")||refresh.equalsIgnoreCase("IMMEDIATE");
        schedule.put("atsAvailable",ats).put("state",immediate?"refresh_immediate":ats?"available":"ats_unavailable")
                .put("editable",ats&&!immediate).put("message",immediate?"REFRESH IMMEDIATE MQTs cannot use an Administrative Task Scheduler refresh job.":ats?"A code-graph helper procedure and one Administrative Task Scheduler task can refresh this deferred MQT.":"DB2 Administrative Task Scheduler is disabled, unavailable, or hidden from this account.");
        String managed=managedName("db2",str(out,"database"),schema,name),helper=managedHelper(str(out,"database"),schema,name);schedule.put("managedName",managed).put("helperName",helper);
        if(ats&&!out.path("creation").asBoolean()){
            JsonNode tasks=safeRows(job,c,"SELECT * FROM TABLE(SYSPROC.ADMIN_TASK_LIST()) AS T");ArrayNode related=(ArrayNode)schedule.path("relatedJobs");
            for(JsonNode task:tasks){String taskName=first(task,"name","taskname","task_name");if(taskName.equals(managed)){schedule.set("managedJob",task.deepCopy());ObjectNode config=(ObjectNode)schedule.path("config");config.put("enabled",true).put("preset","advanced").put("expression",first(task,"schedule","task_schedule"));}else if(task.toString().contains(helper)||task.toString().contains(name))related.add(task.deepCopy());}
            JsonNode runs=safeRows(job,c,"SELECT * FROM TABLE(SYSPROC.ADMIN_TASK_STATUS(NULL,NULL,NULL,NULL)) AS T FETCH FIRST 25 ROWS ONLY");schedule.set("history",runs.deepCopy());
        }
        if(!ats){guidance(schedule,"Enable the Administrative Task Scheduler","Set DB2_ATS_ENABLE, activate the database, and ensure SYSTOOLSPACE exists. The account also needs privileges for ADMIN_TASK_ADD/UPDATE/REMOVE and for the managed helper procedure.","db2set DB2_ATS_ENABLE=YES\nACTIVATE DATABASE <database>");guidance(schedule,"Prepare scheduler storage","Create SYSTOOLSPACE when it is absent, using storage appropriate for this database.","CREATE TABLESPACE SYSTOOLSPACE IN IBMCATGROUP MANAGED BY AUTOMATIC STORAGE");}
    }

    private static void snowflake(QueryJobs.Job job,Connection c,ObjectNode out,ObjectNode schedule)throws Exception {
        schedule.put("automatic",true).put("state","automatic").put("editable",false).put("timezone","Snowflake service time")
                .put("message","Snowflake maintains materialized views automatically. Refresh work can consume credits; no custom cadence is exposed.");
        ((ObjectNode)schedule.path("capabilities")).remove("presets");((ObjectNode)schedule.path("config")).put("refreshMode","automatic");
        if(out.path("creation").asBoolean())return;
        String schema=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name");
        String sql="SHOW MATERIALIZED VIEWS LIKE "+literal(name)+" IN SCHEMA "+ObjectForms.q("snowflake",schema);
        JsonNode state=safeRows(job,c,sql).path(0);if(!state.isMissingNode())schedule.set("status",state.deepCopy());
    }

    private static List<Command> postgresCommands(ObjectNode snapshot,JsonNode schedule,ObjectNode config,JsonNode draft){
        String database=str(snapshot,"database"),control=str(schedule,"controlDatabase");if(control.isBlank())control=database;
        String schema=str(snapshot.path("fields"),"schema"),oldName=str(snapshot.path("fields"),"name");JsonNode fields=draft.path("fields");
        String newSchema=str(fields,"schema"),newName=str(fields,"name");if(newSchema.isBlank())newSchema=schema;if(newName.isBlank())newName=oldName;
        String target=ObjectForms.qualified("postgresql",newSchema,newName),managed=managedName("postgresql",database,newSchema,newName);
        String existing=str(schedule,"managedJobId"),oldManaged=str(schedule,"managedName");List<Command> out=new ArrayList<>();
        if(!config.path("enabled").asBoolean()){
            if(!existing.isBlank())out.add(new Command("SELECT cron.unschedule("+longValue(existing,"pg_cron job id")+")",control,"scheduler","Remove the code-graph-managed refresh job"));return out;
        }
        String expression=cronExpression(config,false),method=str(config,"method");if(!Set.of("standard","concurrent").contains(method))throw new IllegalArgumentException("PostgreSQL refresh method must be standard or concurrent");
        if(method.equals("concurrent")&&!schedule.path("capabilities").path("concurrentEligible").asBoolean())throw new IllegalArgumentException("Concurrent refresh requires a populated materialized view with a valid, unconditional unique index over ordinary columns");
        String command="REFRESH MATERIALIZED VIEW "+(method.equals("concurrent")?"CONCURRENTLY ":"")+target+" /* "+MARKER+" */";
        boolean renamed=!oldManaged.isBlank()&&!oldManaged.equals(managed);
        if(!existing.isBlank()&&!renamed&&containsFunction(schedule.path("signatures"),"alter_job")){
            out.add(new Command("SELECT cron.alter_job(job_id := "+longValue(existing,"pg_cron job id")+", schedule := "+literal(expression)+", command := "+literal(command)+", database := "+literal(database)+", active := true)",control,"scheduler","Update the code-graph-managed refresh job"));return out;
        }
        if(!existing.isBlank())out.add(new Command("SELECT cron.unschedule("+longValue(existing,"pg_cron job id")+")",control,"scheduler","Replace the previous code-graph-managed refresh job"));
        boolean cross=!control.equals(database);String sql;
        if(cross){if(!schedule.path("capabilities").path("scheduleInDatabase").asBoolean())throw new IllegalArgumentException("This pg_cron version cannot schedule jobs in a different database");sql="SELECT cron.schedule_in_database("+literal(managed)+", "+literal(expression)+", "+literal(command)+", "+literal(database)+")";}
        else sql="SELECT cron.schedule("+literal(managed)+", "+literal(expression)+", "+literal(command)+")";
        out.add(new Command(sql,control,"scheduler","Create the code-graph-managed refresh job"));return out;
    }

    private static List<Command> oracleCommands(ObjectNode snapshot,JsonNode schedule,ObjectNode config,JsonNode draft){
        JsonNode fields=draft.path("fields");String schema=str(fields,"schema"),name=str(fields,"name");String target=ObjectForms.qualified("oracle",schema,name);
        String method=str(config,"method").toUpperCase(Locale.ROOT);if(!Set.of("FORCE","FAST","COMPLETE").contains(method))throw new IllegalArgumentException("Oracle refresh method must be FORCE, FAST, or COMPLETE");
        String mode=str(config,"refreshMode");if(mode.equals("on_statement")&&!containsText(schedule.path("capabilities").path("refreshModes"),"on_statement"))throw new IllegalArgumentException("This Oracle version does not support ON STATEMENT materialized-view refresh");String clause=switch(mode){
            case "on_demand"->" REFRESH "+method+" ON DEMAND";
            case "on_commit"->" REFRESH "+method+" ON COMMIT";
            case "on_statement"->" REFRESH "+method+" ON STATEMENT";
            case "scheduled"->" REFRESH "+method+" START WITH SYSDATE NEXT "+oracleExpression(config);
            default->throw new IllegalArgumentException("Unsupported Oracle refresh mode");
        };
        return List.of(new Command("ALTER MATERIALIZED VIEW "+target+clause,str(snapshot,"database"),"scheduler","Configure Oracle materialized-view refresh"));
    }

    private static List<Command> db2Commands(ObjectNode snapshot,JsonNode schedule,ObjectNode config,JsonNode draft){
        String database=str(snapshot,"database"),schema=str(draft.path("fields"),"schema"),name=str(draft.path("fields"),"name");
        String task=managedName("db2",database,schema,name),helper=managedHelper(database,schema,name),target=ObjectForms.qualified("db2",schema,name),procedure=ObjectForms.qualified("db2",schema,helper);
        List<Command> out=new ArrayList<>();boolean existed=schedule.has("managedJob");String oldSchema=str(snapshot.path("fields"),"schema"),oldHelper=str(schedule,"helperName");
        if(existed)out.add(new Command("CALL SYSPROC.ADMIN_TASK_REMOVE("+literal(str(schedule,"managedName")) +", NULL)",database,"scheduler","Remove the previous managed DB2 task"));
        if(existed&&!oldHelper.isBlank()&&(!oldHelper.equals(helper)||!oldSchema.equals(schema)))out.add(new Command("DROP PROCEDURE "+ObjectForms.qualified("db2",oldSchema,oldHelper),database,"scheduler","Remove the previous managed DB2 refresh helper"));
        if(!config.path("enabled").asBoolean()){if(existed&&oldHelper.equals(helper)&&oldSchema.equals(schema))out.add(new Command("DROP PROCEDURE "+ObjectForms.qualified("db2",oldSchema,oldHelper),database,"scheduler","Remove the managed DB2 refresh helper"));return out;}
        String expression=cronExpression(config,true);String body="CREATE OR REPLACE PROCEDURE "+procedure+"() LANGUAGE SQL MODIFIES SQL DATA BEGIN ATOMIC EXECUTE IMMEDIATE "+literal("REFRESH TABLE "+target)+"; END";
        out.add(new Command(body,database,"scheduler","Create the managed DB2 refresh helper"));
        String begin=nullableTimestamp(str(config,"beginAt")),end=nullableTimestamp(str(config,"endAt"));int max=boundedInt(config,"maxInvocations",0,1_000_000);
        String add="CALL SYSPROC.ADMIN_TASK_ADD("+literal(task)+", "+begin+", "+end+", "+(max==0?"NULL":Integer.toString(max))+", "+literal(expression)+", "+literal(schema)+", "+literal(helper)+", NULL, NULL)";
        out.add(new Command(add,database,"scheduler","Create the code-graph-managed DB2 task"));return out;
    }

    private static ObjectNode normalize(JsonNode provider,JsonNode requested){
        ObjectNode out=defaultConfig(str(provider,"provider"));for(String key:DRAFT_KEYS)if(requested.has(key))out.set(key,requested.path(key).deepCopy());
        if(!out.path("enabled").isBoolean())throw new IllegalArgumentException("Schedule enabled must be true or false");
        String preset=str(out,"preset");if(!Set.of("interval","hourly","daily","weekly","monthly","advanced").contains(preset))throw new IllegalArgumentException("Unsupported schedule preset");
        boundedInt(out,"minute",0,59);boundedInt(out,"hour",0,23);boundedInt(out,"dayOfMonth",1,31);boundedInt(out,"intervalMinutes",1,43_200);boundedInt(out,"maxInvocations",0,1_000_000);
        try{day(str(out,"dayOfWeek"));}catch(Exception e){throw new IllegalArgumentException("Day of week must be MON through SUN");}
        expression(str(out,"expression"));timestamp(str(out,"beginAt"));timestamp(str(out,"endAt"));return out;
    }

    static String cronExpression(JsonNode c,boolean db2){
        String preset=str(c,"preset");int minute=boundedInt(c,"minute",0,59),hour=boundedInt(c,"hour",0,23),dom=boundedInt(c,"dayOfMonth",1,31);String value=switch(preset){
            case "interval"->{int n=boundedInt(c,"intervalMinutes",db2?5:1,43_200);if(db2&&n<5)throw new IllegalArgumentException("DB2 Administrative Task Scheduler intervals must be at least five minutes");if(n<60&&60%n==0)yield "*/"+n+" * * * *";if(n%60==0&&n/60<=23&&24%(n/60)==0)yield minute+" */"+(n/60)+" * * *";if(n==1440)yield minute+" "+hour+" * * *";throw new IllegalArgumentException("This interval cannot be represented exactly by a five-field scheduler expression; use Advanced");}
            case "hourly"->minute+" * * * *";case "daily"->minute+" "+hour+" * * *";
            case "weekly"->minute+" "+hour+" * * "+dayNumber(str(c,"dayOfWeek"));case "monthly"->minute+" "+hour+" "+dom+" * *";
            case "advanced"->expression(str(c,"expression"));default->throw new IllegalArgumentException("Unsupported schedule preset");};
        if(value.isBlank())throw new IllegalArgumentException("Enter a scheduler expression");if(!db2&&value.matches("(?:[1-9]|[1-5][0-9]) seconds"))return value;String[] fields=value.trim().split("\\s+");if(fields.length!=5)throw new IllegalArgumentException("Enter a five-field minute hour day-of-month month day-of-week expression");for(String field:fields)if(!field.matches("[0-9A-Za-z*?/$,\\-]+"))throw new IllegalArgumentException("Scheduler expression contains an unsupported field character");
        if(db2&&value.matches("^\\*/?[1-4](?:\\s|$).*"))throw new IllegalArgumentException("DB2 schedules cannot run more often than every five minutes");return value;
    }

    static String oracleExpression(JsonNode c){
        String preset=str(c,"preset");int minute=boundedInt(c,"minute",0,59),hour=boundedInt(c,"hour",0,23),dom=boundedInt(c,"dayOfMonth",1,31);return switch(preset){
            case "interval"->"SYSDATE + NUMTODSINTERVAL("+boundedInt(c,"intervalMinutes",1,43_200)+", 'MINUTE')";
            case "hourly"->"TRUNC(SYSDATE, 'HH24') + 1/24 + "+minute+"/1440";
            case "daily"->"TRUNC(SYSDATE) + 1 + ("+hour+"*60+"+minute+")/1440";
            case "weekly"->"NEXT_DAY(TRUNC(SYSDATE), '"+day(str(c,"dayOfWeek"))+"') + ("+hour+"*60+"+minute+")/1440";
            case "monthly"->"ADD_MONTHS(TRUNC(SYSDATE, 'MM'), 1) + "+(dom-1)+" + ("+hour+"*60+"+minute+")/1440";
            case "advanced"->ObjectForms.fragment(expression(str(c,"expression")));default->throw new IllegalArgumentException("Unsupported schedule preset");};
    }

    static String managedName(String engine,String database,String schema,String name){return "codegraph_mv_"+digest(engine+"\u0000"+database+"\u0000"+schema+"\u0000"+name).substring(0,24);}
    static String managedHelper(String database,String schema,String name){return "cg_mv_refresh_"+digest(database+"\u0000"+schema+"\u0000"+name).substring(0,18);}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static JsonNode cronJobs(QueryJobs.Job job,Connection c)throws Exception {
        JsonNode rows=safeRows(job,c,"SELECT jobid::text,schedule,command,database,username,active::text,COALESCE(jobname,'') AS jobname FROM cron.job ORDER BY jobid");
        if(rows.isEmpty())rows=safeRows(job,c,"SELECT jobid::text,schedule,command,database,username,active::text,'' AS jobname FROM cron.job ORDER BY jobid");return rows;
    }
    private static boolean managedCommand(JsonNode row,String target){return str(row,"command").contains(MARKER)&&targets(row,target);}
    private static boolean targets(JsonNode row,String target){String command=str(row,"command");return command.contains(target)||command.toLowerCase(Locale.ROOT).contains(target.replace("\"","").toLowerCase(Locale.ROOT));}
    private static boolean containsFunction(JsonNode rows,String name){for(JsonNode row:rows)if(str(row,"name").equals(name))return true;return false;}
    private static boolean containsText(JsonNode rows,String value){for(JsonNode row:rows)if(row.asText().equals(value))return true;return false;}
    private static boolean truth(JsonNode n){return n.asBoolean()||Set.of("true","t","yes","y","1","on").contains(n.asText("").toLowerCase(Locale.ROOT));}
    private static String first(JsonNode n,String... keys){for(String key:keys)if(n.hasNonNull(key))return n.path(key).asText();return "";}
    private static DayOfWeek day(String value){return switch(value.toUpperCase(Locale.ROOT)){case "MON"->DayOfWeek.MONDAY;case "TUE"->DayOfWeek.TUESDAY;case "WED"->DayOfWeek.WEDNESDAY;case "THU"->DayOfWeek.THURSDAY;case "FRI"->DayOfWeek.FRIDAY;case "SAT"->DayOfWeek.SATURDAY;case "SUN"->DayOfWeek.SUNDAY;default->throw new IllegalArgumentException("Invalid day");};}
    private static int dayNumber(String value){return day(value).getValue()%7;}
    private static int boundedInt(JsonNode n,String key,int min,int max){if(!n.path(key).canConvertToInt())throw new IllegalArgumentException(key+" must be an integer");int v=n.path(key).asInt();if(v<min||v>max)throw new IllegalArgumentException(key+" must be between "+min+" and "+max);return v;}
    private static String expression(String value){if(value.length()>512||value.chars().anyMatch(Character::isISOControl)||value.contains(";")||value.contains("--")||value.contains("/*")||value.contains("*/"))throw new IllegalArgumentException("Scheduler expressions are limited to 512 characters and cannot contain controls, comments, or statement separators");return value.strip();}
    private static void timestamp(String value){if(value.isBlank())return;try{java.time.OffsetDateTime.parse(value);}catch(Exception e){try{java.time.LocalDateTime.parse(value);}catch(Exception ignored){throw new IllegalArgumentException("Begin and end times must use ISO date/time format");}}}
    private static String nullableTimestamp(String value){if(value.isBlank())return "NULL";timestamp(value);return "TIMESTAMP("+literal(value)+")";}
    private static String literal(String value){return TableDesigner.literal(value);}
    private static String longValue(String value,String label){try{return Long.toString(Long.parseLong(value));}catch(Exception e){throw new IllegalArgumentException("Invalid "+label);}}
    private static void guidance(ObjectNode schedule,String title,String detail,String sql){ObjectNode item=((ArrayNode)schedule.path("guidance")).addObject().put("title",title).put("detail",detail);if(!sql.isBlank())item.put("sql",sql);}
    private static ArrayNode safeRows(QueryJobs.Job job,Connection c,String sql,Object... args)throws Exception {
        Savepoint save=null;try{if(!c.getAutoCommit()&&c.getMetaData().supportsSavepoints())save=c.setSavepoint();return ObjectCatalog.query(job,c,sql,args);}
        catch(SQLException|UnsupportedOperationException e){if(save!=null)c.rollback(save);return Profiles.JSON.createArrayNode();}
        finally{if(save!=null)try{c.releaseSavepoint(save);}catch(SQLException ignored){}}
    }
    private MaterializedViewSchedules(){}
}
