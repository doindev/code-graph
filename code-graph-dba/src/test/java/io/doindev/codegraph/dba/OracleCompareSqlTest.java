package io.doindev.codegraph.dba;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleCompareSqlTest {
    @Test void remapsOnlyOwnerIdentifiersWithOracleCaseAndLiteralRules(){
        String sql="select src.t, SRC /*owner*/ .t, \"SRC\".t, \"src\".t, q'[src.t '' \"SRC\".t]',nq'{src.t}', 'src.t' from src.t -- SRC.t\n";
        String result=OracleCompareSql.remap(sql,Map.of("SRC","Dest"));
        assertEquals("select \"Dest\".t, \"Dest\" /*owner*/ .t, \"Dest\".t, \"src\".t, q'[src.t '' \"SRC\".t]',nq'{src.t}', 'src.t' from \"Dest\".t -- SRC.t\n",result);
    }
    @Test void rejectsAmbiguousAliasesRatherThanRewritingColumnQualifiers(){
        assertThrows(IllegalArgumentException.class,()->OracleCompareSql.remap("SELECT src.id FROM actual_table src",Map.of("SRC","DST")));
        assertEquals("SELECT src.id FROM actual_table src",OracleCompareSql.remap("SELECT src.id FROM actual_table src",Map.of("SRC","SRC")));
    }
    @Test void distinguishesDynamicCodeFromCommentsAndLiterals(){
        assertTrue(OracleCompareSql.dynamic("BEGIN EXECUTE /*x*/ IMMEDIATE sql; END;"));
        assertTrue(OracleCompareSql.dynamic("BEGIN OPEN c FOR text_value; END;"));
        assertTrue(OracleCompareSql.dynamic("BEGIN \"DBMS_SQL\".PARSE(c,s,1); END;"));
        assertFalse(OracleCompareSql.dynamic("BEGIN OPEN c FOR SELECT q'[EXECUTE IMMEDIATE x]' FROM dual; -- DBMS_SQL\nEND;"));
        assertEquals(OracleCompareSql.canonical("SELECT x FROM t"),OracleCompareSql.canonical("select \"X\" /*comment*/ from \"T\""));
        assertNotEquals(OracleCompareSql.canonical("select 'x' from t"),OracleCompareSql.canonical("select 'X' from t"));
    }
}
