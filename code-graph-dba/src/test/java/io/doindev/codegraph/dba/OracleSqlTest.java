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
}
