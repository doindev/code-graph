package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.doindev.codegraph.dba.ExplainPlansTest.proxy;
import static io.doindev.codegraph.dba.ExplainPlansTest.zero;

class ScheduledJobsTest {
    @TempDir Path root;
    Profiles profiles;Connections connections;QueryJobs jobs;
    @BeforeEach void start()throws Exception{profiles=new Profiles(root,new DbaTest.MemoryVault());connections=new Connections(profiles);jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,1000,100,30),s->true);}
    @AfterEach void close()throws Exception{jobs.close();connections.close();profiles.close();}
    QueryJobs.Job job(){return jobs.new Job("human","fixture");}
    static ObjectNode row(String... pairs){var row=Profiles.JSON.createObjectNode();for(int i=0;i<pairs.length;i+=2)row.put(pairs[i],pairs[i+1]);return row;}
    static class Jdbc implements InvocationHandler {
        final String product;final List<String> sql=new ArrayList<>();final List<List<Object>> parameters=new ArrayList<>();
        boolean installed=true,launch=true,closed,rolledBack;String fail="",state="42501";
        java.util.function.Function<String,List<ObjectNode>> response=q->List.of();
        Jdbc(String product){this.product=product;}
        Connection connection(){return proxy(Connection.class,this);}
        public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
            return switch(method.getName()){
                case "getCatalog"->"app";case "getSchema"->"public";
                case "getMetaData"->proxy(DatabaseMetaData.class,(p,m,a)->switch(m.getName()){
                    case "getDatabaseProductName"->product;case "getDatabaseProductVersion"->"16.0";case "getDriverName"->"Fixture JDBC";case "getURL"->"jdbc:fixture:app";case "getUserName"->"fixture";case "getDatabaseMajorVersion"->16;case "supportsSavepoints"->true;default->zero(m.getReturnType());});
                case "setSavepoint"->proxy(Savepoint.class,(p,m,a)->m.getName().equals("getSavepointId")?1:"fixture");
                case "rollback"->{rolledBack=true;yield null;}
                case "prepareStatement"->{String query=args[0].toString();var bound=new TreeMap<Integer,Object>();yield proxy(PreparedStatement.class,(p,m,a)->{
                    if(m.getName().equals("setObject")){bound.put((Integer)a[0],a[1]);return null;}
                    if(m.getName().equals("executeQuery")){sql.add(query);parameters.add(new ArrayList<>(bound.values()));if(!fail.isBlank()&&query.contains(fail))throw new SQLException("fixture permission failure",state);
                        if(query.equals("SELECT version() AS version"))return rows(List.of(row("version",product+" 16")));
                        if(query.contains("AS preload"))return rows(List.of(row("installed",String.valueOf(installed),"preload","pg_cron","control_database","app","launch",launch?"on":"off")));
                        if(query.contains("@@GLOBAL.event_scheduler"))return rows(List.of(row("scheduler_state","ON")));
                        if(query.contains("@@SESSION.sql_mode"))return rows(List.of(row("mode","NO_BACKSLASH_ESCAPES")));
                        return rows(response.apply(query));}
                    if(m.getName().equals("execute"))throw new AssertionError("Discovery must not execute commands");if(m.getName().equals("close"))closed=true;return zero(m.getReturnType());});}
                default->zero(method.getReturnType());
            };
        }
    }
    static ResultSet rows(List<ObjectNode> values){var names=new ArrayList<String>();if(!values.isEmpty())values.get(0).fieldNames().forEachRemaining(names::add);int[] index={-1};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
        case "next"->++index[0]<values.size();case "getString"->values.get(index[0]).path(names.get((Integer)a[0]-1)).asText();
        case "getBinaryStream"->new java.io.ByteArrayInputStream(values.get(index[0]).path(names.get((Integer)a[0]-1)).asText().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        case "getCharacterStream"->{String name=names.get((Integer)a[0]-1);if(name.equals("run_at"))throw new SQLDataException("Temporal values have no character stream");yield new StringReader(values.get(index[0]).path(name).asText());}
        case "getMetaData"->proxy(ResultSetMetaData.class,(p2,m2,a2)->switch(m2.getName()){case "getColumnCount"->names.size();case "getColumnLabel","getColumnName"->names.get((Integer)a2[0]-1);case "getColumnType"->names.get((Integer)a2[0]-1).equals("owner_sid")?Types.VARBINARY:names.get((Integer)a2[0]-1).equals("run_at")?Types.TIMESTAMP:Types.VARCHAR;case "getColumnTypeName"->"varchar";default->zero(m2.getReturnType());});default->zero(m.getReturnType());});}
    @Test void everyTemplateHasADocumentedOutcomeAndDb2VariantsRemainDistinct()throws Exception{
        for(var template:DatabaseCatalog.ALL)assertFalse(ScheduledJobs.coverage(template.id()).isBlank(),template.id());for(String id:NativeCatalog.IDS)assertFalse(ScheduledJobs.coverage(id).isBlank());
        assertEquals("db2-i",ScheduledJobs.engine(job(),new Jdbc("AS/400").connection()));assertEquals("db2-zos",ScheduledJobs.engine(job(),new Jdbc("DSN").connection()));
        assertNotEquals(ScheduledJobs.PROVIDERS.get("db2_ats").probe(),ScheduledJobs.PROVIDERS.get("zos_tasks").probe());
    }
    @Test void postgresDiscoveryDoesNotReadJobsAndDistinguishesMissingAndDisabled(){
        assertDoesNotThrow(()->{Jdbc jdbc=new Jdbc("PostgreSQL");var request=row("kind","database","database","app");jdbc.installed=false;
            var result=MetadataTree.browse(job(),jdbc.connection(),request,30);assertFalse(result.path("nodes").toString().contains("scheduled_jobs"));
            jdbc.installed=true;result=MetadataTree.browse(job(),jdbc.connection(),request,30);assertTrue(result.path("nodes").toString().contains("scheduled_jobs"));
            jdbc.launch=false;result=MetadataTree.browse(job(),jdbc.connection(),request,30);assertTrue(result.path("nodes").toString().contains("disabled"));
            assertTrue(jdbc.sql.stream().noneMatch(q->q.contains("FROM cron.job")));assertTrue(jdbc.closed);
        });
    }
    @Test void permissionFailureIsVisibleAndNeverCachedAsUnsupported()throws Exception{
        Jdbc jdbc=new Jdbc("MySQL");jdbc.fail="@@GLOBAL";var request=row("kind","database","database","app");
        var result=MetadataTree.browse(job(),jdbc.connection(),request,30);assertTrue(result.path("warning").asText().contains("42501"));assertTrue(result.path("nodes").toString().contains("unverified"));assertTrue(jdbc.rolledBack);
        jdbc.fail="";result=MetadataTree.browse(job(),jdbc.connection(),request,30);assertFalse(result.has("warning"));assertTrue(result.path("nodes").toString().contains("scheduled_jobs"));
    }
    @Test void pagingIsBoundedAndNamesAreDataNotSql()throws Exception{
        Jdbc jdbc=new Jdbc("MySQL");var rows=new ArrayList<ObjectNode>();for(int i=0;i<201;i++)rows.add(row("id","job"+i,"name","job"+i));jdbc.response=q->rows;
        var request=row("kind","scheduled_jobs","scheduler","events","database","app","schema","x' OR 1=1 --");
        var result=ScheduledJobs.browse(job(),jdbc.connection(),request);assertEquals(200,result.path("nodes").size());assertEquals(200,result.path("nextOffset").asInt());
        assertFalse(jdbc.sql.get(0).contains("OR 1=1"));assertEquals(List.of("x' OR 1=1 --"),jdbc.parameters.get(0));
        request.put("offset",200);result=ScheduledJobs.browse(job(),jdbc.connection(),request);assertEquals(1,result.path("nodes").size());assertFalse(result.has("nextOffset"));
        request.put("scheduler","pg_cron");assertThrows(IllegalArgumentException.class,()->ScheduledJobs.browse(job(),jdbc.connection(),request));
    }
    static ObjectNode base(String provider,boolean create){var p=ScheduledJobs.PROVIDERS.get(provider);var b=row("engine",p.engine(),"scheduler",provider,"database","app","schedulerId","17","fingerprint","fixture","kind","scheduled_jobs");b.put("creation",create).put("formSupported",true);b.putObject("fields").put("name","nightly").put("schema",provider.equals("events")?"app":"public").put("enabled",false).put("schedule","0 0 1 1 *").put("command","SELECT 1").put("targetDatabase","app").put("description","");b.putArray("controls");b.putArray("categories");b.putArray("warnings");b.putObject("details");b.putObject("schedulerRecord");for(String key:List.of("name","enabled","schedule","command","targetDatabase","description"))b.withArray("controls").addObject().put("id",key).put("editable",!key.equals("name")||create);return b;}
    @Test void cronCreatesDisabledUsesLiteralsAndAltersOnlyRequestedProperties(){
        var base=base("pg_cron",true).put("cronCross",true);var draft=ObjectDesignerTest.draft(base);((ObjectNode)draft.path("fields")).put("command","SELECT 'O\\'Reilly'; -- inside stored command");
        String sql=String.join(";",ScheduledJobEditor.compile(base,draft));assertTrue(sql.contains("active := false"));assertTrue(sql.contains("cron.schedule_in_database"));assertTrue(sql.contains("E'SELECT ''O\\\\''Reilly''; -- inside stored command'"));
        base=base("pg_cron",false).put("cronAlter",true);draft=ObjectDesignerTest.draft(base);((ObjectNode)draft.path("fields")).put("enabled",true);
        assertEquals(List.of("SELECT cron.alter_job(17, active := true)"),ScheduledJobEditor.compile(base,draft));
        final var invalidBase=base;final var invalidDraft=draft;((ObjectNode)draft.path("fields")).put("name","other");assertThrows(IllegalArgumentException.class,()->ScheduledJobEditor.compile(invalidBase,invalidDraft));
    }
    @Test void mysqlChangesPreserveDefinerAndDoNotChangeSqlMode(){
        var base=base("events",false).put("mysqlNoBackslashEscapes",true);((ObjectNode)base.path("schedulerRecord")).put("definer","owner@localhost");((ObjectNode)base.path("fields")).put("timezone","SYSTEM");
        var draft=ObjectDesignerTest.draft(base);((ObjectNode)draft.path("fields")).put("description","owner's \\ path");
        var commands=ScheduledJobEditor.compile(base,draft);assertTrue(commands.stream().noneMatch(q->q.contains("sql_mode")));assertTrue(commands.get(1).startsWith("ALTER DEFINER=`owner`@`localhost` EVENT `app`.`nightly`"));assertTrue(commands.get(1).contains("owner''s \\ path"));assertFalse(commands.get(1).contains("ON SCHEDULE"));assertFalse(commands.get(1).contains(" DO "));
    }
    @Test void genericH2NeverDisplaysASchedulerAndAgentsCannotUseItsBrowserRoute()throws Exception{
        assertThrows(SecurityException.class,()->jobs.metadataTree("agent:fixture","unknown",row("kind","scheduled_jobs","scheduler","pg_cron")));
        var result=MetadataTree.browse(job(),new Jdbc("H2").connection(),row("kind","root"),30);assertFalse(result.path("nodes").toString().contains("scheduled_jobs"));
    }
    @Test void hiveUsesTheServerNamespaceAndRejectsAmbiguousIdentities()throws Exception{
        Jdbc jdbc=new Jdbc("Hive");jdbc.response=q->q.startsWith("SET ")?List.of(row("set","hive.scheduled.queries.namespace=warehouse")):q.contains("SELECT *")?List.of(row("schedule_name","nightly"),row("schedule_name","nightly")):List.of(row("id","17","name","nightly"));
        var target=row("kind","scheduled_jobs","scheduler","hive_queries","schedulerNamespace","forged","database","app");ScheduledJobs.browse(job(),jdbc.connection(),target);
        assertEquals(List.of("warehouse"),jdbc.parameters.get(jdbc.parameters.size()-1));
        var selection=row("key","scheduled_job:hive_queries:17");selection.set("parent",target);
        var error=assertThrows(IllegalArgumentException.class,()->ScheduledJobEditor.load(job(),jdbc.connection(),selection));assertTrue(error.getMessage().contains("ambiguous"));assertEquals(List.of("17","warehouse"),jdbc.parameters.get(jdbc.parameters.size()-1));
    }
    @Test void binaryOwnershipIsPreservedAndOversizedMetadataCannotBeEdited()throws Exception{
        Jdbc jdbc=new Jdbc("Microsoft SQL Server");jdbc.response=q->List.of(row("owner_sid","owner-one","name","nightly","run_at","2030-01-01 00:00:00"));
        var result=ScheduledJobs.read(job(),jdbc.connection(),new ScheduledJobs.Sql("SELECT fixture"),1,0);
        assertEquals(HexFormat.of().formatHex("owner-one".getBytes(java.nio.charset.StandardCharsets.UTF_8)),result.path("rows").path(0).path("owner_sid").asText());
        assertEquals("2030-01-01 00:00:00",result.path("rows").path(0).path("run_at").asText());
        jdbc.response=q->List.of(row("command","x".repeat(8193)));assertThrows(IllegalArgumentException.class,()->ScheduledJobs.read(job(),jdbc.connection(),new ScheduledJobs.Sql("SELECT fixture"),1,0));
        var cancelled=job();cancelled.cancelled=true;jdbc.response=q->List.of(row("command","SELECT 1"));assertThrows(java.util.concurrent.CancellationException.class,()->ScheduledJobs.read(cancelled,jdbc.connection(),new ScheduledJobs.Sql("SELECT fixture"),1,0));assertNull(cancelled.statement);assertTrue(jdbc.closed);
    }
    @Test void everyNativeProviderHasFixedCatalogStatementsAndBoundDefinitionIdentities(){
        var target=row("schema","public","database","app","schedulerNamespace","hive");
        for(var p:ScheduledJobs.PROVIDERS.values()){
            var list=ScheduledJobs.list(p,target);assertFalse(list.text().isBlank(),p.id());assertFalse(list.text().matches("(?is).*(EXECUTE |RUN_JOB|cron.schedule\\().*"),p.id());
            var definition=ScheduledJobs.definition(p,target,"x' --");assertFalse(definition.text().contains("x' --"),p.id());if(!p.id().equals("tasks"))assertTrue(definition.args().contains("x' --"),p.id());
        }
        assertTrue(ScheduledJobs.definition(ScheduledJobs.PROVIDERS.get("apoc_periodic"),target,"x").text().contains("name=$1"));
    }
    @Test void vendorFormsGenerateOnlyTheirNativeSchedulerChanges()throws Exception{
        for(String provider:List.of("oracle_scheduler","sql_agent","elastic_jobs","tasks","hana_jobs","hive_queries","cockroach_schedules","altibase_jobs","oracle_legacy")){
            var base=base(provider,false);var draft=ObjectDesignerTest.draft(base);((ObjectNode)draft.path("fields")).put("enabled",true);
            String sql=String.join(";",ScheduledJobEditor.compile(base,draft));
            String expected=switch(provider){case "oracle_scheduler"->"DBMS_SCHEDULER.ENABLE";case "sql_agent"->"sp_update_job";case "elastic_jobs"->"jobs.sp_update_job";case "tasks"->" RESUME";case "hana_jobs"->"ALTER SCHEDULER JOB";case "hive_queries"->"ALTER SCHEDULED QUERY";case "cockroach_schedules"->"RESUME SCHEDULE 17";case "altibase_jobs"->"ALTER JOB";default->"DBMS_JOB.BROKEN";};
            assertTrue(sql.contains(expected),provider+": "+sql);assertFalse(sql.contains("RUN_JOB")||sql.contains("sp_start_job")||sql.contains("EXECUTE TASK"),provider);
        }
        var create=base("sql_agent",true);((ObjectNode)create.path("fields")).put("startTime","063000");var commands=ScheduledJobEditor.compile(create,ObjectDesignerTest.draft(create));assertTrue(commands.get(0).contains("@enabled=0"));assertTrue(commands.stream().noneMatch(q->q.contains("@enabled=1")));
        create=base("oracle_scheduler",true);((ObjectNode)create.path("fields")).put("jobType","PLSQL_BLOCK");assertTrue(ScheduledJobEditor.compile(create,ObjectDesignerTest.draft(create)).get(0).contains("enabled => FALSE"));
        create=base("hive_queries",true);assertTrue(ScheduledJobEditor.compile(create,ObjectDesignerTest.draft(create)).get(0).contains(" DISABLED AS "));
    }
    @Test void runtimeHistoryDoesNotInvalidateSqlAgentEditsButOwnerChangesDo()throws Exception{
        Jdbc jdbc=new Jdbc("Microsoft SQL Server");var jobRow=row("job_id","17","name","nightly","enabled","1","owner_sid","owner-one");var step=row("step_id","1","subsystem","TSQL","command","SELECT 1","database_name","app","last_run_duration","3");
        jdbc.response=q->q.contains("sysjobsteps")?List.of(step):q.contains("FROM msdb.dbo.sysjobs WHERE")?List.of(jobRow):List.of();
        var selection=row("key","scheduled_job:sql_agent:17");selection.set("parent",row("kind","scheduled_jobs","scheduler","sql_agent","database","app"));
        var before=ScheduledJobEditor.load(job(),jdbc.connection(),selection);step.put("last_run_duration","9");var after=ScheduledJobEditor.load(job(),jdbc.connection(),selection);assertEquals(before.path("fingerprint"),after.path("fingerprint"));
        jobRow.put("owner_sid","owner-two");after=ScheduledJobEditor.load(job(),jdbc.connection(),selection);assertNotEquals(before.path("fingerprint"),after.path("fingerprint"));
    }

}
