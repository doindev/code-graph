package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlScriptTest {
    @Test void splitsSemicolonsWithoutDamagingQuotedOrCommentedSource(){
        var units=SqlScript.extract("-- first ;\nSELECT ';', \"a;b\", `c;d`, [e;f], ?; /* nested /* ; */ ok */ SELECT $tag$x;y$tag$, ?;");
        assertEquals(2,units.size());assertEquals(1,units.get(0).parameters());assertEquals(1,units.get(1).parameters());
        assertTrue(units.get(0).sql().startsWith("-- first ;\nSELECT"));assertTrue(units.get(1).sql().contains("$tag$x;y$tag$"));
    }
    @Test void treatsClientMarkerBatchesAsExactUnits(){
        var go=SqlScript.extract("SELECT 1; SELECT 2;\nGO\nSELECT 3");assertEquals(2,go.size());assertEquals("SELECT 1; SELECT 2;",go.get(0).sql());
        var slash=SqlScript.extract("BEGIN\n NULL;\nEND;\n/\nSELECT 1 FROM dual");assertEquals(2,slash.size());assertTrue(slash.get(0).sql().contains("NULL;"));
        assertThrows(IllegalArgumentException.class,()->SqlScript.extract("SELECT 1\nGO 2"));assertThrows(IllegalArgumentException.class,()->SqlScript.extract("DELIMITER $$\nSELECT 1"));
    }
    @Test void skipsCommentOnlyUnitsAndBoundsCount(){
        assertEquals(1,SqlScript.extract("; -- comment\n; SELECT 1;;").size());
        assertThrows(IllegalArgumentException.class,()->SqlScript.extract("SELECT 1;".repeat(33)));
        assertThrows(IllegalArgumentException.class,()->SqlScript.extract("/* only comment */"));
    }

    @Test void oracleMixesSqlAndPlsqlWithAlternativeQuotingAndSlashTerminators(){
        String sql="SELECT q'[don't split; ? /]' FROM dual;\nDECLARE s VARCHAR2(100) := nq'{a'b;?}';\nBEGIN\n s := q'!x'y;?!'; NULL;\nEND;\n/\nSELECT ? FROM dual; SELECT 2 FROM dual;";
        var units=SqlScript.extract(sql,"oracle");assertEquals(4,units.size());assertEquals(0,units.get(0).parameters());assertEquals(0,units.get(1).parameters());assertTrue(units.get(1).sql().endsWith("END;"));assertEquals(1,units.get(2).parameters());
        assertEquals(1,SqlScript.extract("BEGIN NULL; END;","oracle").size());
        assertEquals(2,SqlScript.extract("CREATE /* comment */ OR REPLACE NONEDITIONABLE PACKAGE p AS PROCEDURE x; END;\n/\nSELECT 1 FROM dual;","oracle").size());
        assertThrows(IllegalArgumentException.class,()->SqlScript.extract("SELECT q'[unclosed' FROM dual;","oracle"));
        assertEquals(1,SqlScript.extract("SELECT nq'<first\n/\nsecond>' FROM dual;","oracle").size());
        assertEquals(2,SqlScript.extract("SELECT 1; SELECT 2;\nGO\nSELECT 3","sqlserver").size());
    }
}
