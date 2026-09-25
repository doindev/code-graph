package io.doindev.codegraph.dba;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleCompareSchedulerTest {
    private static com.fasterxml.jackson.databind.node.ObjectNode job(){return CompareCatalog.item("SRC","scheduler","jobs / Work job").put("objectName","Work job").put("schedulerCategory","jobs");}
    @Test void remapsOnlyTypedNamesPreservesLiteralsAndDefersEnable(){
        var object=job();String sql="BEGIN dbms_scheduler.create_job('\"Work job\"',program_name=>'\"Program one\"',schedule_name=>'CAL',enabled=>FALSE,auto_drop=>FALSE,comments=>'SRC.PROC remains a comment'); sys.dbms_scheduler.set_attribute('\"Work job\"','MAX_RUNS',10);dbms_scheduler.enable('\"Work job\"'); COMMIT; END;";
        String output=OracleCompareScheduler.rewrite(sql,object,"DST",Map.of("SRC","DST"));assertTrue(output.contains("'\"DST\".\"Work job\"'"),output);assertTrue(output.contains("'\"DST\".\"Program one\"'"),output);assertTrue(output.contains("SRC.PROC remains a comment"),output);assertTrue(output.contains("'MAX_RUNS',10"),output);assertFalse(output.contains(".ENABLE("));assertFalse(output.contains("ENABLED=>TRUE"));
    }
    @Test void rejectsOpaqueCallsExpressionsAndMismatchedIdentities(){
        assertThrows(IllegalArgumentException.class,()->OracleCompareScheduler.rewrite("BEGIN dbms_scheduler.run_job('\"Work job\"'); END;",job(),"DST",Map.of()));
        assertThrows(IllegalArgumentException.class,()->OracleCompareScheduler.rewrite("BEGIN dbms_scheduler.create_job('OTHER',program_name=>'P'); END;",job(),"DST",Map.of()));
        assertThrows(IllegalArgumentException.class,()->OracleCompareScheduler.rewrite("BEGIN dbms_scheduler.create_job('\"Work job\"',program_name=>'P',comments=>evil()); END;",job(),"DST",Map.of()));
    }
    @Test void reviewKeepsRequiredAndIncomingObjectsSelectable()throws Exception{
        var source=new CompareCatalog.Inventory("oracle","PDB","19");var destination=new CompareCatalog.Inventory("oracle","PDB","19");
        var table=CompareCatalog.item("SRC","tables","T").put("supported",true);var procedure=CompareCatalog.item("SRC","procedures","P").put("supported",true);procedure.withArray("dependencies").add(CompareCatalog.key("SRC","tables","T"));
        var program=CompareCatalog.item("SRC","scheduler","programs / S").put("supported",true);program.withArray("dependencies").add(CompareCatalog.key("SRC","procedures","P"));
        var implicit=CompareCatalog.item("SRC","indexes","SYSTEM_PK").put("supported",true).put("implicit",true);implicit.withArray("dependencies").add(CompareCatalog.key("SRC","tables","T"));source.add(implicit);
        source.add(table);source.add(procedure);source.add(program);source.add(CompareCatalog.item("SRC","tables","UNRELATED").put("supported",true));
        var diff=new CompareDiff(source,destination,new CompareCatalog.Target("s","PDB","SRC",false,""),new CompareCatalog.Target("d","PDB","DST",false,""));OracleCompare.retainReviewScope(diff,java.util.Set.of("scheduler"),java.util.Set.of(program.path("id").asText()));
        assertEquals(3,diff.objects.size());assertTrue(diff.objects.containsKey(table.path("id").asText()));assertTrue(diff.objects.containsKey(procedure.path("id").asText()));
    }
    @Test void nativeScheduleKeepsDateAndCalendarWithoutInternalBypass(){
        var object=CompareCatalog.item("SRC","scheduler","schedules / CAL").put("objectName","CAL").put("schedulerCategory","schedules");
        String output=OracleCompareScheduler.rewrite("BEGIN dbms_scheduler.disable1_calendar_check();dbms_scheduler.create_schedule('CAL',TO_TIMESTAMP_TZ('2099-01-01 +00:00','YYYY-MM-DD TZH:TZM'),'FREQ=DAILY',NULL,'SRC text'); COMMIT; END;",object,"DST",Map.of("SRC","DST"));assertFalse(output.contains("DISABLE1"));assertTrue(output.contains("TO_TIMESTAMP_TZ('2099-01-01 +00:00','YYYY-MM-DD TZH:TZM')"),output);assertTrue(output.contains("'SRC text'"));
    }
}
