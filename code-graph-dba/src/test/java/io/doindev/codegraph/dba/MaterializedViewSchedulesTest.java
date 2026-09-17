package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MaterializedViewSchedulesTest {
    private static ObjectNode config(String provider){
        return Profiles.JSON.createObjectNode().put("enabled",false).put("preset","daily").put("intervalMinutes",60)
                .put("minute",0).put("hour",2).put("dayOfWeek","MON").put("dayOfMonth",1).put("expression","")
                .put("method",provider.equals("oracle")?"FORCE":"standard").put("refreshMode","on_demand")
                .put("beginAt","").put("endAt","").put("maxInvocations",0);
    }
    private static ObjectNode snapshot(String provider){
        ObjectNode snapshot=Profiles.JSON.createObjectNode().put("kind","materialized_views").put("engine",provider).put("database","app");
        snapshot.putObject("fields").put("schema","reporting").put("name","sales");
        ObjectNode schedule=snapshot.putObject("refreshSchedule").put("provider",provider).put("state","available").put("editable",true)
                .put("database","app").put("controlDatabase","app").put("timezone","UTC").put("managedName",MaterializedViewSchedules.managedName(provider,"app","reporting","sales"));
        schedule.set("config",config(provider));ObjectNode caps=schedule.putObject("capabilities");caps.put("concurrentEligible",true).put("scheduleInDatabase",true);
        caps.putArray("methods").add(provider.equals("oracle")?"FORCE":"standard").add(provider.equals("oracle")?"FAST":"concurrent");
        schedule.putArray("signatures").addObject().put("name","schedule").put("arguments","text,text,text");
        ((ArrayNode)schedule.path("signatures")).addObject().put("name","unschedule").put("arguments","bigint");
        ((ArrayNode)schedule.path("signatures")).addObject().put("name","alter_job").put("arguments","bigint,text,text,text,text,boolean");
        return snapshot;
    }
    private static ObjectNode draft(ObjectNode snapshot){
        ObjectNode draft=Profiles.JSON.createObjectNode();draft.set("fields",snapshot.path("fields").deepCopy());draft.set("schedule",snapshot.path("refreshSchedule").path("config").deepCopy());return draft;
    }

    @Test void deterministicNamesAreStableAndLengthSafe(){
        String one=MaterializedViewSchedules.managedName("postgresql","app","résumé","daily sales");
        assertEquals(one,MaterializedViewSchedules.managedName("postgresql","app","résumé","daily sales"));
        assertNotEquals(one,MaterializedViewSchedules.managedName("postgresql","app","résumé","other"));
        assertTrue(one.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=63);
        assertTrue(MaterializedViewSchedules.managedHelper("app","résumé","daily sales").length()<=128);
    }

    @Test void presetsTranslateToProviderExpressionsAndEnforceDb2Minimum(){
        ObjectNode c=config("postgresql");c.put("preset","daily").put("hour",14).put("minute",35);
        assertEquals("35 14 * * *",MaterializedViewSchedules.cronExpression(c,false));
        c.put("preset","weekly").put("dayOfWeek","SUN");assertEquals("35 14 * * 0",MaterializedViewSchedules.cronExpression(c,false));
        c.put("preset","interval").put("intervalMinutes",5);assertEquals("*/5 * * * *",MaterializedViewSchedules.cronExpression(c,true));
        c.put("intervalMinutes",4);assertThrows(IllegalArgumentException.class,()->MaterializedViewSchedules.cronExpression(c,true));
        c.put("preset","advanced").put("expression","* * * * *; DROP TABLE x");assertThrows(IllegalArgumentException.class,()->MaterializedViewSchedules.cronExpression(c,false));
    }

    @Test void oraclePresetsProduceReviewedNextExpressions(){
        ObjectNode c=config("oracle");c.put("preset","monthly").put("dayOfMonth",12).put("hour",3).put("minute",15);
        String expression=MaterializedViewSchedules.oracleExpression(c);
        assertTrue(expression.contains("ADD_MONTHS"));assertTrue(expression.contains("+ 11"));
    }

    @Test void postgresScheduleCommandsCarryDatabasePhasePurposeAndEscapedTarget(){
        ObjectNode snapshot=snapshot("postgresql"),draft=draft(snapshot);ObjectNode schedule=(ObjectNode)draft.path("schedule");
        schedule.put("enabled",true).put("preset","daily").put("hour",4).put("minute",5);
        ((ObjectNode)draft.path("fields")).put("name","sales' daily");
        List<MaterializedViewSchedules.Command> commands=MaterializedViewSchedules.compile(snapshot,draft);
        assertEquals(1,commands.size());assertEquals("app",commands.get(0).database());assertEquals("scheduler",commands.get(0).phase());
        assertTrue(commands.get(0).purpose().contains("managed"));assertTrue(commands.get(0).sql().contains("cron.schedule"));
        assertTrue(commands.get(0).sql().contains("\"sales'' daily\""));
    }

    @Test void providerCapabilityAndDraftShapeAreEnforcedServerSide(){
        ObjectNode firstSnapshot=snapshot("postgresql"),firstDraft=draft(firstSnapshot);((ObjectNode)firstSnapshot.path("refreshSchedule")).put("editable",false).put("message","pg_cron missing");
        ((ObjectNode)firstDraft.path("schedule")).put("enabled",true);
        assertEquals("pg_cron missing",assertThrows(IllegalArgumentException.class,()->MaterializedViewSchedules.compile(firstSnapshot,firstDraft)).getMessage());
        ObjectNode snapshot=snapshot("postgresql"),draft=draft(snapshot);((ObjectNode)draft.path("schedule")).put("craftedSql","DROP");
        ObjectNode finalSnapshot=snapshot,finalDraft=draft;
        assertThrows(IllegalArgumentException.class,()->MaterializedViewSchedules.compile(finalSnapshot,finalDraft));
    }

    @Test void managedRenameRetargetsPostgresJobEvenWhenCadenceIsUnchanged(){
        ObjectNode snapshot=snapshot("postgresql"),draft=draft(snapshot);ObjectNode schedule=(ObjectNode)snapshot.path("refreshSchedule");
        schedule.put("managedJobId","7").putObject("managedJob").put("jobid","7");((ObjectNode)schedule.path("config")).put("enabled",true);
        ((ObjectNode)draft.path("schedule")).put("enabled",true);((ObjectNode)draft.path("fields")).put("name","renamed_sales");
        List<MaterializedViewSchedules.Command> commands=MaterializedViewSchedules.compile(snapshot,draft);
        assertEquals(2,commands.size());assertTrue(commands.get(0).sql().contains("unschedule"));assertTrue(commands.get(1).sql().contains("renamed_sales"));
    }

    @Test void reviewedCommandsExposeCrossDatabasePhasesAndAtomicity(){
        ObjectNode snapshot=snapshot("postgresql");snapshot.put("fingerprint","fp").put("label","Materialized View").put("creation",false).put("formSupported",true);
        ((ObjectNode)snapshot.path("refreshSchedule")).put("controlDatabase","scheduler");((ObjectNode)snapshot.path("refreshSchedule").path("capabilities")).put("scheduleInDatabase",true);
        ((ArrayNode)snapshot.path("refreshSchedule").path("signatures")).addObject().put("name","schedule_in_database").put("arguments","text,text,text,text");
        ObjectNode draft=draft(snapshot);draft.put("sqlMode",false).put("sql","").put("splitSql",false);((ObjectNode)draft.path("schedule")).put("enabled",true);
        ObjectNode request=Profiles.JSON.createObjectNode().put("fingerprint","fp");request.set("draft",draft);
        ObjectNode plan=assertDoesNotThrow(()->ObjectDesigner.prepare(snapshot,request));
        assertFalse(plan.path("atomic").asBoolean());assertTrue(plan.has("partialCommitWarning"));
        assertEquals("scheduler",plan.path("commands").path(0).path("phase").asText());assertEquals("scheduler",plan.path("commands").path(0).path("database").asText());
    }

    @Test void stableFingerprintStateExcludesRunHistory(){
        ObjectNode snapshot=snapshot("postgresql");ObjectNode schedule=(ObjectNode)snapshot.path("refreshSchedule");
        schedule.putArray("history").addObject().put("status","running");schedule.putObject("lastRun").put("status","succeeded");
        ObjectNode stable=MaterializedViewSchedules.stable(schedule);assertFalse(stable.has("history"));assertFalse(stable.has("lastRun"));assertTrue(stable.has("config"));
    }
}
