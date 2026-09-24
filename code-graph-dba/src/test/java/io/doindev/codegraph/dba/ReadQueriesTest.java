package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadQueriesTest {
    @Test void commonReadsAndCteScopes(){
        for(String vendor:List.of("mysql","mariadb","postgresql")){
            var scope=ReusableOperationTest.scope(vendor);
            for(String sql:List.of(
                "SELECT COUNT(*) FROM items",
                "SELECT name, SUM(amount) FROM items GROUP BY name HAVING SUM(amount)>?",
                "WITH first AS (SELECT id FROM items), second AS (SELECT id FROM first) SELECT * FROM second UNION ALL SELECT id FROM other",
                "SELECT CASE WHEN id>1 THEN LOWER(name) ELSE UPPER(name) END FROM items",
                "SELECT COALESCE(name, 'empty'), NULLIF(id,0), ABS(id), CHAR_LENGTH(name) FROM items",
                "SELECT id FROM items WHERE EXISTS (SELECT 1 FROM other WHERE other.id=items.id)"
            )){
                var a=ReadQueries.analyze(sql,scope);assertFalse(a.relations().isEmpty(),sql);
                assertTrue(a.relations().stream().noneMatch(r->Set.of("first","second").contains(r.object())),sql);
            }
            assertEquals(1,ReadQueries.analyze("WITH items AS (SELECT id FROM actual) SELECT * FROM items",scope).relations().size());
        }
    }
    @Test void rejectsUnsafeSyntax(){
        for(String sql:List.of("DELETE FROM items","SELECT 1; SELECT 2","SELECT * FROM items FOR UPDATE","SELECT * FROM items FOR SHARE","SELECT * FROM items LOCK IN SHARE MODE","SELECT * FROM items INTO DUMPFILE '/tmp/copy'","SELECT * INTO copy FROM items",
            "SELECT * FROM items INTO OUTFILE '/tmp/copy'","SELECT evil(id) FROM items","SELECT ABS(id,id) FROM items","SELECT LOWER() FROM items","SELECT pg_sleep(3)",
            "WITH RECURSIVE x AS (SELECT 1 UNION ALL SELECT 1 FROM x) SELECT * FROM x",
            "WITH x AS (DELETE FROM items RETURNING *) SELECT * FROM x",
            "SELECT @secret","SELECT /*!50000 evil() */ 1","SELECT other.abs(1)","SELECT CAST(id AS customtype) FROM items",
            "SELECT ROW_NUMBER() OVER () FROM items","SELECT * FROM app.public.items")){
            assertThrows(IllegalArgumentException.class,()->ReadQueries.analyze(sql,ReusableOperationTest.scope("mysql")),sql);
        }
    }
    @Test void quotedNamesCrossSchemasAndAliases(){
        var mysql=ReadQueries.analyze("SELECT a.id FROM app.items a JOIN other.items b ON a.id=b.id",ReusableOperationTest.scope("mysql"));
        assertEquals(Set.of("app","other"),new HashSet<>(mysql.relations().stream().map(ReadQueries.Relation::database).toList()));
        var pg=ReadQueries.analyze("SELECT x.\"ID\" FROM \"Odd.Schema\".\"A.B\" x JOIN public.items y ON x.\"ID\"=y.id",ReusableOperationTest.scope("postgresql"));
        assertTrue(pg.relations().contains(new ReadQueries.Relation("app","Odd.Schema","A.B")));
        assertEquals(2,pg.relations().size());
        assertTrue(ReadQueries.analyze("SELECT ABS(id) FROM items",ReusableOperationTest.scope("postgresql")).sql().contains("pg_catalog.abs"));
    }
    @Test void inspectionAndNestedAliasesDoNotBroadenRelations(){
        var mysql=ReusableOperationTest.scope("mysql");
        assertEquals(List.of(new ReadQueries.Relation("other","","")),ReadQueries.inspection("SHOW CREATE DATABASE `other`",mysql).relations());
        assertEquals(List.of(new ReadQueries.Relation("other","other","a.b")),ReadQueries.inspection("SHOW CREATE TABLE `other`.`a.b`",mysql).relations());
        assertThrows(IllegalArgumentException.class,()->ReadQueries.inspection("SHOW CREATE TABLE a; SELECT * FROM secret",mysql));
        assertThrows(IllegalArgumentException.class,()->ReadQueries.analyze("SELECT /*m! evil() */ 1",mysql));
        var nested=ReadQueries.analyze("WITH x AS (SELECT * FROM first_table) SELECT * FROM x WHERE EXISTS (WITH x AS (SELECT * FROM second_table) SELECT * FROM x)",mysql);
        assertEquals(Set.of("first_table","second_table"),new HashSet<>(nested.relations().stream().map(ReadQueries.Relation::object).toList()));
        var shadow=ReadQueries.analyze("WITH x AS (SELECT * FROM x) SELECT * FROM x",mysql);
        assertEquals("x",shadow.relations().getFirst().object());
    }
    @Test void legacyGrammarDoesNotExpand(){
        for(String sql:List.of("SELECT COUNT(*) FROM items","WITH x AS (SELECT * FROM items) SELECT * FROM x","SELECT 1 UNION SELECT 2")){
            assertThrows(IllegalArgumentException.class,()->SqlReadGuard.validate(sql));
            assertFalse(ReusableOperation.classify(sql,ReusableOperationTest.scope("mysql")).eligible());
        }
    }
}