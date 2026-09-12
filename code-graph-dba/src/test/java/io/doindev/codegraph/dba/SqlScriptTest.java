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
}
