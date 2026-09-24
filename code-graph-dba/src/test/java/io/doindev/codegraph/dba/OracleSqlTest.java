package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleSqlTest {
    @Test void boundedRoutineParametersAreSeparateFromOrdinaryReadParameters()throws Exception{
        var values=Profiles.JSON.readTree("[{\"mode\":\"out\",\"type\":\"REF_CURSOR\"},{\"mode\":\"inout\",\"type\":\"NUMBER\",\"value\":\"12345678901234567890\"}]");
        assertDoesNotThrow(()->OracleSql.checkParameters(values));assertThrows(IllegalArgumentException.class,()->HumanSql.checkParameters(values));
        for(String invalid:new String[]{"[{\"mode\":\"out\",\"type\":\"UNKNOWN\"}]","[{\"mode\":\"out\",\"type\":\"NUMBER\",\"value\":1}]","[{\"mode\":\"inout\",\"type\":\"NUMBER\",\"value\":\"1e999999999\"}]","[{\"mode\":\"inout\",\"type\":\"REF_CURSOR\",\"value\":null}]"}){
            var parsed=Profiles.JSON.readTree(invalid);assertThrows(IllegalArgumentException.class,()->OracleSql.checkParameters(parsed));
        }
        assertTrue(OracleDialect.mayCommit("-- heading\n CREATE TABLE x(id NUMBER)"));
        assertTrue(OracleDialect.mayCommit("BEGIN NULL; END;"));assertFalse(OracleDialect.mayCommit("SELECT 1 FROM dual"));
        assertEquals("\"a\"\"b\"",OracleDialect.identifier("a\"b"));assertThrows(IllegalArgumentException.class,()->OracleDialect.identifier("\u03a9".repeat(65)));
    }
    @Test void retainedSelectInputsPreservePrecisionAndRejectCallableModes()throws Exception{
        var input=Profiles.JSON.createObjectNode().put("sql","SELECT ?, ? FROM dual").put("action","refresh");var values=input.putArray("parameters");
        values.addObject().put("mode","in").put("type","NUMBER").put("value","12345678901234567890123456789012345678");
        values.addObject().put("mode","in").put("type","TIMESTAMP").put("value","2026-09-24T12:34:00.123456789");
        var prepared=GridSql.prepare(input);assertEquals(values,prepared.path("parameters"));assertTrue(prepared.path("displaySql").asText().contains("12345678901234567890123456789012345678"));assertTrue(prepared.path("displaySql").asText().contains("TIMESTAMP '2026-09-24 12:34:00.123456789'"));
        for(String mode:new String[]{"out","inout"}){var invalid=Profiles.JSON.createArrayNode();invalid.addObject().put("mode",mode).put("type","NUMBER");if(mode.equals("inout"))((com.fasterxml.jackson.databind.node.ObjectNode)invalid.get(0)).put("value","1");assertThrows(IllegalArgumentException.class,()->OracleSql.checkInputParameters(invalid));}
        for(String value:new String[]{"123456789012345678901234567890123456789","1e126","1e-131","1); DROP TABLE ITEMS;--"}){
            ((com.fasterxml.jackson.databind.node.ObjectNode)values.get(0)).put("value",value);assertThrows(IllegalArgumentException.class,()->GridSql.prepare(input),value);
        }
        for(String value:new String[]{"1e125","1e-130","0","0e-999999999"}){
            ((com.fasterxml.jackson.databind.node.ObjectNode)values.get(0)).put("value",value);assertDoesNotThrow(()->GridSql.prepare(input),value);
        }
    }

}
