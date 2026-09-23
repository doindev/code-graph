package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectDesigner.str;

/** Browser scheduler discovery and bounded catalog reads. Never starts a scheduler or a job. */
final class ScheduledJobs {
    record Sql(String text,List<Object> args){Sql(String text,Object... args){this(text,List.of(args));}}
    record Provider(String id,String engine,String label,String location,String probe){}
    static final Map<String,Provider> PROVIDERS=new LinkedHashMap<>();
    static {
        register("apoc_periodic","neo4j","APOC Background Jobs","root","/*+ NEO4J FORCE_CYPHER */ SHOW PROCEDURES YIELD name WHERE name = 'apoc.periodic.list' RETURN name");
        register("pg_cron","postgresql","pg_cron","database","");
        register("events","mysql","Event Scheduler","database","SELECT @@GLOBAL.event_scheduler AS scheduler_state");
        register("oracle_scheduler","oracle","Oracle Scheduler","schema","SELECT job_name FROM all_scheduler_jobs WHERE 1=0");
        register("oracle_legacy","oracle","Legacy DBMS_JOB","schema","SELECT job FROM all_jobs WHERE 1=0");
        register("sql_agent","sqlserver","SQL Server Agent","root","SELECT name FROM msdb.dbo.sysjobs WHERE 1=0");
        register("elastic_jobs","sqlserver","Azure Elastic Jobs","root","SELECT job_name FROM jobs.jobs WHERE 1=0");
        register("tasks","snowflake","Snowflake Tasks","schema","");
        register("db2_ats","db2","Db2 Administrative Task Scheduler","root","SELECT ROUTINENAME FROM SYSCAT.ROUTINES WHERE ROUTINESCHEMA='SYSPROC' AND ROUTINENAME='ADMIN_TASK_ADD'");
        register("hana_jobs","hana","SAP HANA Scheduler","schema","SELECT SCHEDULER_JOB_NAME FROM SYS.SCHEDULER_JOBS WHERE 1=0");
        register("hive_queries","hive","Hive Scheduled Queries","root","SELECT schedule_name FROM information_schema.scheduled_queries WHERE 1=0");
        register("cockroach_schedules","cockroachdb","CockroachDB Schedules","root","SELECT id FROM [SHOW SCHEDULES] WHERE false");
        register("ibmi_jobs","db2-i","IBM i Job Schedule","root","SELECT SCHEDULED_JOB_NAME FROM QSYS2.SCHEDULED_JOB_INFO WHERE 1=0");
        register("zos_tasks","db2-zos","Db2 z/OS Administrative Task Scheduler","root","SELECT TASK_NAME FROM TABLE(DSNADM.ADMIN_TASK_LIST()) AS T WHERE 1=0");
        register("informix_tasks","informix","Informix Scheduler","root","SELECT tk_id FROM sysadmin:ph_task WHERE 1=0");
        register("altibase_jobs","altibase","Altibase Jobs","root","SELECT JOB_NAME FROM SYSTEM_.SYS_JOBS_ WHERE 1=0");
        register("clickhouse_refreshes","clickhouse","Refreshable Materialized Views","root","SELECT database FROM system.view_refreshes WHERE 1=0");
        register("starrocks_tasks","starrocks","StarRocks Tasks","root","SELECT TASK_NAME FROM information_schema.tasks WHERE 1=0");
    }
    private static void register(String id,String engine,String label,String location,String probe){PROVIDERS.put(id,new Provider(id,engine,label,location,probe));}
    static String engine(QueryJobs.Job job,Connection c)throws Exception{
        String engine=ExplainPlans.engine(c.getMetaData());
        if(engine.equals("postgresql")){
            String version=str(read(job,c,new Sql("SELECT version() AS version"),1,0).path("rows").path(0),"version").toLowerCase(Locale.ROOT);
            if(version.contains("cockroach"))return "cockroachdb";
            if(version.contains("yugabyte"))return "yugabytedb";
            if(version.contains("greenplum"))return "greenplum";
            if(version.contains("redshift"))return "redshift";
        }
        return engine;
    }
    static boolean matches(Provider p,String engine){return p.engine.equals(engine)||p.id.equals("events")&&engine.equals("mariadb")||p.id.equals("pg_cron")&&Set.of("yugabytedb","greenplum").contains(engine);}
    static String coverage(String template){
        String e=template.equals("azure-sql")?"sqlserver":template;
        if(PROVIDERS.values().stream().anyMatch(p->matches(p,e)))return "native_catalog";
        if(!external(e).isBlank())return "separate_service_api";
        return switch(template){
            case "custom"->"detect_actual_product";
            case "calcite","cosmos-cassandra","cassandra","csv","elasticsearch","exasol","firebird","json","duckdb","h2","hsqldb","opensearch","presto","redis","sqlite","teradata","trino","mongodb-native","redis-native"->"no_job_catalog_in_configured_transport";
            default->throw new IllegalArgumentException("Scheduler capability must be documented for template: "+template);
        };
    }
    static boolean branch(String kind){return Set.of("scheduled_jobs","scheduled_job","external_scheduler").contains(kind);}
    static Provider provider(QueryJobs.Job job,Connection c,JsonNode target)throws Exception{
        Provider p=PROVIDERS.get(str(target,"scheduler"));
        if(p==null||!matches(p,engine(job,c)))throw new IllegalArgumentException("This scheduler does not belong to the connected database engine");
        return p;
    }
    static void warning(ObjectNode out,String message){out.put("warning",out.has("warning")?out.path("warning").asText()+" "+message:message);}
    static String error(SQLException ex){return "Scheduler metadata could not be read (SQLSTATE "+Objects.toString(ex.getSQLState(),"unknown")+"). "+Objects.toString(ex.getMessage(),"Check scheduler installation and catalog privileges.");}
    static boolean absent(SQLException ex){return Set.of("42P01","42704","42S02","42102","S0002").contains(Objects.toString(ex.getSQLState(),""))||Set.of(942,208,1146).contains(ex.getErrorCode());}
    static ObjectNode attach(QueryJobs.Job job,Connection c,JsonNode request,ObjectNode out)throws Exception{
        String kind=request.path("kind").asText("root");if(!Set.of("root","database","schema").contains(kind))return out;
        String engine=engine(job,c),database=Objects.toString(c.getCatalog(),str(request,"database")),schema=str(request,"schema");
        ArrayNode nodes=(ArrayNode)out.path("nodes");
        boolean rootWithoutDatabases=kind.equals("root");for(JsonNode node:nodes)if(str(node,"kind").equals("databases"))rootWithoutDatabases=false;
        for(Provider p:PROVIDERS.values()){
            if(!matches(p,engine)||!p.location.equals(kind)&&!(p.location.equals("database")&&rootWithoutDatabases))continue;
            // Catalog discovery is optional: a permission failure must not destroy other tree branches.
            Savepoint save=null;
            try{
                if(!c.getAutoCommit()&&c.getMetaData().supportsSavepoints())save=c.setSavepoint();
                String state="available",message="";
                if(p.id.equals("pg_cron")){
                    JsonNode info=read(job,c,new Sql("SELECT current_setting('shared_preload_libraries') AS preload, current_setting('cron.database_name',true) AS control_database, current_setting('cron.launch_active_jobs',true) AS launch, EXISTS(SELECT 1 FROM pg_catalog.pg_extension WHERE extname='pg_cron') AS installed"),1,0).path("rows").path(0);
                    if(!truth(str(info,"installed")))continue;
                    boolean preloaded=Arrays.stream(str(info,"preload").split(",")).map(String::strip).anyMatch(x->x.replace("\"","").equals("pg_cron")||x.endsWith("/pg_cron"));
                    if(!preloaded||str(info,"launch").equalsIgnoreCase("off")){state="disabled";message="pg_cron is installed, but job launching is disabled or it is not preloaded. Browsing does not enable it.";}
                }else if(!p.probe.isBlank()){
                    JsonNode probe=read(job,c,new Sql(p.probe),1,0);
                    if(Set.of("db2_ats","apoc_periodic").contains(p.id)&&probe.path("rows").isEmpty())continue;
                    if(p.id.equals("events")&&!str(probe.path("rows").path(0),"scheduler_state").equalsIgnoreCase("ON")){state="disabled";message="The Event Scheduler is disabled. Job definitions remain available; browsing does not start the scheduler.";}
                }
                ObjectNode node=group(nodes,p,database,schema).put("schedulerState",state);
                if(!message.isBlank())node.put("schedulerMessage",message);
            }catch(SQLException ex){
                if(save!=null)c.rollback(save);
                if(!absent(ex)){group(nodes,p,database,schema).put("schedulerState","unverified").put("schedulerMessage",error(ex));warning(out,p.label+": "+error(ex));}
            }finally{if(save!=null)try{c.releaseSavepoint(save);}catch(SQLException ignored){}}
        }
        if(kind.equals("root")&&!external(engine).isBlank())MetadataTree.node(nodes,"external_scheduler","Scheduled Jobs",true,database,"").put("scheduler",engine).put("canCreate",false);
        return out;
    }
    static ObjectNode group(ArrayNode nodes,Provider p,String database,String schema){return MetadataTree.node(nodes,"scheduled_jobs",p.id.equals("oracle_legacy")?"Legacy Jobs":p.id.equals("elastic_jobs")?"Scheduled Jobs (Elastic Jobs)":"Scheduled Jobs",true,database,schema).put("key","scheduled_jobs:"+p.id).put("scheduler",p.id).put("schedulerLabel",p.label).put("canCreate",true);}
    static String external(String engine){return switch(engine){
        case "bigquery"->"BigQuery scheduled queries are managed by the BigQuery Data Transfer Service API, which is not exposed by this JDBC connection. Use Google Cloud scheduled queries to inspect or manage them.";
        case "redshift"->"Redshift scheduled queries use Amazon EventBridge and the Redshift Data API. This JDBC connection does not include access to that scheduling service.";
        case "databricks"->"Databricks Jobs schedules use the workspace Jobs API. This JDBC SQL warehouse connection does not include Jobs API access.";
        case "mongodb"->"Atlas scheduled triggers are managed by the Atlas service, outside the MongoDB SQL interface.";
        default->"";
    };}
    static ObjectNode browse(QueryJobs.Job job,Connection c,JsonNode target)throws Exception{
        ObjectNode out=Profiles.JSON.createObjectNode().put("offset",target.path("offset").asInt());ArrayNode nodes=out.putArray("nodes");
        if(str(target,"kind").equals("external_scheduler")){String message=external(engine(job,c));if(message.isBlank())throw new IllegalArgumentException("No external scheduler is registered for this database");out.put("warning",message);return out;}
        Provider p=provider(job,c,target);target=context(job,c,p,target);out.put("engine",p.engine).put("scheduler",p.id);
        String database=Objects.toString(c.getCatalog(),str(target,"database")),schema=str(target,"schema");
        if(str(target,"kind").equals("scheduled_job")){
            ObjectNode selection=Profiles.JSON.createObjectNode();selection.set("parent",((ObjectNode)target).deepCopy().put("kind","scheduled_jobs"));selection.put("key",str(target,"key"));
            ObjectNode snapshot=load(job,c,selection);
            snapshot.path("fields").fields().forEachRemaining(entry->{String value=entry.getValue().asText();if(!value.isBlank())MetadataTree.node(nodes,"object",entry.getKey()+": "+(value.length()>180?value.substring(0,180)+"… (open job for full value)":value),false,database,schema).put("key","job_field:"+entry.getKey());});
            out.put("warning","Double-click the job name (or press Enter) to inspect its full definition, recent runs, or edit it.");return out;
        }
        int offset=target.path("offset").asInt();if(offset>2000)throw new IllegalArgumentException("Scheduled job browsing is limited to 2,000 preceding entries. Refine the database/schema.");
        ObjectNode page;
        try{page=read(job,c,list(p,target),MetadataTree.LIMIT,offset);}
        catch(SQLException ex){if(p.id.equals("db2_ats")&&absent(ex)){out.put("warning","Db2 creates SYSTOOLS.ADMIN_TASK_LIST when the first task is added. Scheduling also requires DB2_ATS_ENABLE and SYSTOOLSPACE.");return out;}throw ex;}
        for(JsonNode row:page.path("rows")){
            String id=first(row,"id",p.id.equals("tasks")?"name":""),name=first(row,"name","label");if(name.isBlank())name=id;
            if(id.isBlank()||name.isBlank()||id.length()>2048||name.length()>2048)throw new IllegalArgumentException("Scheduler returned an invalid job identity");
            MetadataTree.node(nodes,"scheduled_job",name,true,database,schema).put("key","scheduled_job:"+p.id+":"+id).put("oid",id).put("scheduler",p.id).put("objectName",name);
        }
        if(page.path("truncated").asBoolean())out.put("nextOffset",offset+MetadataTree.LIMIT);
        out.put("warning",p.label+" · Only jobs visible to this database account are listed.");return out;
    }
    static ObjectNode context(QueryJobs.Job job,Connection c,Provider p,JsonNode input)throws Exception{
        ObjectNode target=((ObjectNode)input).deepCopy();
        if(p.id.equals("hive_queries")){
            JsonNode row=read(job,c,new Sql("SET hive.scheduled.queries.namespace"),1,0).path("rows").path(0);
            String value=row.isObject()&&!row.isEmpty()?row.elements().next().asText():"";
            String prefix="hive.scheduled.queries.namespace=";if(value.startsWith(prefix))value=value.substring(prefix.length());
            if(value.isBlank()||value.length()>512||value.contains("undefined"))throw new IllegalArgumentException("Hive did not expose the active scheduled-query namespace; job changes cannot be targeted safely");
            target.put("schedulerNamespace",value);
        }
        return target;
    }
    static Sql list(Provider p,JsonNode t){String s=str(t,"schema");return switch(p.id){
        case "apoc_periodic"->new Sql("/*+ NEO4J FORCE_CYPHER */ CALL apoc.periodic.list() YIELD name RETURN name AS id, name ORDER BY name");
        case "pg_cron"->new Sql("SELECT jobid::text AS id,COALESCE(to_jsonb(j)->>'jobname','Job '||jobid::text) AS name FROM cron.job j ORDER BY lower(COALESCE(to_jsonb(j)->>'jobname','')),jobid");
        case "events"->new Sql("SELECT EVENT_NAME AS id,EVENT_NAME AS name FROM information_schema.events WHERE EVENT_SCHEMA=? ORDER BY EVENT_NAME",s.isBlank()?str(t,"database"):s);
        case "oracle_scheduler"->new Sql("SELECT JOB_NAME AS id,JOB_NAME AS name FROM ALL_SCHEDULER_JOBS WHERE OWNER=? ORDER BY JOB_NAME",s);
        case "oracle_legacy"->new Sql("SELECT TO_CHAR(JOB) AS id,TO_CHAR(JOB) AS name FROM ALL_JOBS WHERE SCHEMA_USER=? ORDER BY JOB",s);
        case "sql_agent"->new Sql("SELECT CONVERT(varchar(36),job_id) AS id,name FROM msdb.dbo.sysjobs ORDER BY name,job_id");
        case "elastic_jobs"->new Sql("SELECT job_name AS id,job_name AS name FROM jobs.jobs ORDER BY job_name");
        case "tasks"->new Sql("SHOW TASKS IN SCHEMA "+qualified("snowflake",str(t,"database"),s));
        case "db2_ats"->new Sql("SELECT NAME AS id,NAME AS name FROM SYSTOOLS.ADMIN_TASK_LIST ORDER BY NAME");
        case "hana_jobs"->new Sql("SELECT SCHEDULER_JOB_NAME AS id,SCHEDULER_JOB_NAME AS name FROM SYS.SCHEDULER_JOBS WHERE SCHEMA_NAME=? ORDER BY SCHEDULER_JOB_NAME",s);
        case "hive_queries"->new Sql("SELECT CAST(scheduled_query_id AS STRING) AS id,schedule_name AS name FROM information_schema.scheduled_queries WHERE cluster_namespace=? ORDER BY schedule_name,scheduled_query_id",str(t,"schedulerNamespace"));
        case "cockroach_schedules"->new Sql("SELECT id::STRING AS id,label AS name FROM [SHOW SCHEDULES] ORDER BY label,id");
        case "ibmi_jobs"->new Sql("SELECT VARCHAR(SCHEDULED_JOB_ENTRY_NUMBER) AS id,SCHEDULED_JOB_NAME AS name FROM QSYS2.SCHEDULED_JOB_INFO ORDER BY SCHEDULED_JOB_NAME,SCHEDULED_JOB_ENTRY_NUMBER");
        case "zos_tasks"->new Sql("SELECT TASK_NAME AS id,TASK_NAME AS name FROM TABLE(DSNADM.ADMIN_TASK_LIST()) AS T ORDER BY TASK_NAME");
        case "informix_tasks"->new Sql("SELECT CAST(tk_id AS VARCHAR(32)) AS id,tk_name AS name FROM sysadmin:ph_task ORDER BY tk_name,tk_id");
        case "altibase_jobs"->new Sql("SELECT JOB_NAME AS id,JOB_NAME AS name FROM SYSTEM_.SYS_JOBS_ ORDER BY JOB_NAME");
        case "clickhouse_refreshes"->new Sql("SELECT view AS id,view AS name FROM system.view_refreshes WHERE database=currentDatabase() ORDER BY view");
        case "starrocks_tasks"->new Sql("SELECT TASK_NAME AS id,TASK_NAME AS name FROM information_schema.tasks WHERE `DATABASE`=? ORDER BY TASK_NAME",str(t,"database"));
        default->throw new IllegalArgumentException("Unknown scheduler");
    };}
    static Sql definition(Provider p,JsonNode t,String id){String s=str(t,"schema");return switch(p.id){
        case "apoc_periodic"->new Sql("/*+ NEO4J FORCE_CYPHER */ CALL apoc.periodic.list() YIELD name,delay,rate,done,cancelled WHERE name=$1 RETURN name,delay,rate,done,cancelled",id);
        case "pg_cron"->new Sql("SELECT * FROM cron.job WHERE jobid=?::bigint",id);
        case "events"->new Sql("SELECT * FROM information_schema.events WHERE EVENT_SCHEMA=? AND EVENT_NAME=?",s.isBlank()?str(t,"database"):s,id);
        case "oracle_scheduler"->new Sql("SELECT * FROM ALL_SCHEDULER_JOBS WHERE OWNER=? AND JOB_NAME=?",s,id);
        case "oracle_legacy"->new Sql("SELECT * FROM ALL_JOBS WHERE SCHEMA_USER=? AND JOB=?",s,id);
        case "sql_agent"->new Sql("SELECT * FROM msdb.dbo.sysjobs WHERE job_id=CONVERT(uniqueidentifier,?)",id);
        case "elastic_jobs"->new Sql("SELECT * FROM jobs.jobs WHERE job_name=?",id);
        case "tasks"->new Sql("SHOW TASKS IN SCHEMA "+qualified("snowflake",str(t,"database"),s));
        case "db2_ats"->new Sql("SELECT * FROM SYSTOOLS.ADMIN_TASK_LIST WHERE NAME=?",id);
        case "hana_jobs"->new Sql("SELECT * FROM SYS.SCHEDULER_JOBS WHERE SCHEMA_NAME=? AND SCHEDULER_JOB_NAME=?",s,id);
        case "hive_queries"->new Sql("SELECT * FROM information_schema.scheduled_queries WHERE scheduled_query_id=? AND cluster_namespace=?",id,str(t,"schedulerNamespace"));
        case "cockroach_schedules"->new Sql("SELECT * FROM [SHOW SCHEDULES] WHERE id=?::INT8",id);
        case "ibmi_jobs"->new Sql("SELECT * FROM QSYS2.SCHEDULED_JOB_INFO WHERE SCHEDULED_JOB_ENTRY_NUMBER=?",id);
        case "zos_tasks"->new Sql("SELECT * FROM TABLE(DSNADM.ADMIN_TASK_LIST()) AS T WHERE TASK_NAME=?",id);
        case "informix_tasks"->new Sql("SELECT * FROM sysadmin:ph_task WHERE tk_id=?",id);
        case "altibase_jobs"->new Sql("SELECT * FROM SYSTEM_.SYS_JOBS_ WHERE JOB_NAME=?",id);
        case "clickhouse_refreshes"->new Sql("SELECT r.*,t.create_table_query FROM system.view_refreshes r JOIN system.tables t ON r.database=t.database AND r.view=t.name WHERE r.database=currentDatabase() AND r.view=?",id);
        case "starrocks_tasks"->new Sql("SELECT * FROM information_schema.tasks WHERE `DATABASE`=? AND TASK_NAME=?",str(t,"database"),id);
        default->throw new IllegalArgumentException("Unknown scheduler");
    };}
    static ObjectNode read(QueryJobs.Job job,Connection c,Sql sql,int limit,int offset)throws Exception{
        try(PreparedStatement statement=c.prepareStatement(sql.text)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setMaxRows(limit+offset+1);for(int i=0;i<sql.args.size();i++)statement.setObject(i+1,sql.args.get(i));
            try(ResultSet rs=statement.executeQuery()){
                int skip=offset;while(skip-->0&&rs.next())if(job.cancelled)throw new java.util.concurrent.CancellationException();
                ResultSetMetaData metadata=rs.getMetaData();int count=metadata.getColumnCount();
                if(count>256)throw new IllegalArgumentException("Scheduler catalog exceeds the 256-column limit");
                ObjectNode result=Profiles.JSON.createObjectNode();ArrayNode rows=result.putArray("rows");boolean truncated=false;long bytes=0;
                while(rs.next()){
                    if(job.cancelled)throw new java.util.concurrent.CancellationException();
                    if(rows.size()>=limit||(rows.size()+1L)*count>16384){truncated=true;break;}
                    ObjectNode row=Profiles.JSON.createObjectNode();
                    for(int i=1;i<=count;i++){
                        String label=metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);int type=metadata.getColumnType(i);
                        // JDBC drivers need getString for scalar/date values; text and LOBs stay streamed.
                        if(Set.of(Types.BOOLEAN,Types.BIT,Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.FLOAT,Types.REAL,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL,Types.DATE,Types.TIME,Types.TIMESTAMP,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP_WITH_TIMEZONE).contains(type)){
                            if(metadata.getPrecision(i)>8192)throw oversized();String value=rs.getString(i);if(value==null)row.putNull(label);else{if(value.length()>8192)throw oversized();row.put(label,value);}continue;
                        }
                        if(Set.of(Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY,Types.BLOB).contains(type)){
                            try(var stream=rs.getBinaryStream(i)){if(stream==null)row.putNull(label);else{byte[] value=stream.readNBytes(4097);if(value.length>4096)throw oversized();row.put(label,HexFormat.of().formatHex(value));}}
                        }else try(var reader=ExplainPlans.textReader(rs,i,8192)){
                            if(reader==null)row.putNull(label);else{char[] buffer=new char[8193];int length=0,n;while(length<buffer.length&&(n=reader.read(buffer,length,buffer.length-length))>0)length+=n;if(length>8192)throw oversized();row.put(label,new String(buffer,0,length));}
                        }
                    }
                    long size=Profiles.JSON.writeValueAsBytes(row).length;if(bytes+size>Math.min(job.byteLimit,1<<20)-32768){truncated=true;break;}bytes+=size;rows.add(row);
                }
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                return result.put("truncated",truncated).put("rowCount",rows.size());
            }
        }finally{job.statement=null;}
    }
    static IllegalArgumentException oversized(){return new IllegalArgumentException("Scheduler metadata exceeds the 8 KiB per-value limit; changes are disabled to prevent losing definition text");}
    static String first(JsonNode node,String... keys){for(String key:keys)if(!key.isBlank()&&!str(node,key).isBlank())return str(node,key);return "";}
    static boolean truth(String value){return Set.of("true","t","1","y","yes","on","enabled","started").contains(value.toLowerCase(Locale.ROOT));}
    static String qualified(String engine,String schema,String name){return ObjectForms.qualified(engine,schema,name);}
    static ObjectNode load(QueryJobs.Job job,Connection c,JsonNode input)throws Exception{return ScheduledJobEditor.load(job,c,input);}
    private ScheduledJobs(){}
}
