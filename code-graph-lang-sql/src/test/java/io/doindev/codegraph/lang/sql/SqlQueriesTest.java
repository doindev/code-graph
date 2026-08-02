package io.doindev.codegraph.lang.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlQueriesTest {

    @Test
    void looksLikeSqlAcceptsRealQueries() {
        assertTrue(SqlQueries.looksLikeSql("SELECT id, name FROM users WHERE id = 1"));
        assertTrue(SqlQueries.looksLikeSql("insert into accounts (id) values (1)"));
        assertTrue(SqlQueries.looksLikeSql("UPDATE users SET name = 'x' WHERE id = 2"));
        assertTrue(SqlQueries.looksLikeSql("DELETE FROM sessions WHERE expired = 1"));
        assertTrue(SqlQueries.looksLikeSql("CREATE TABLE t (id int)"));
        assertTrue(SqlQueries.looksLikeSql("ALTER TABLE t ADD COLUMN c int"));
        assertTrue(SqlQueries.looksLikeSql("DROP VIEW active_users"));
        assertTrue(SqlQueries.looksLikeSql("MERGE INTO target USING source ON target.id = source.id"));
        assertTrue(SqlQueries.looksLikeSql("WITH recent AS (SELECT * FROM logs) SELECT * FROM recent"));
    }

    @Test
    void looksLikeSqlRejectsProse() {
        assertFalse(SqlQueries.looksLikeSql("Please select an option from the menu below."));
        assertFalse(SqlQueries.looksLikeSql("We will update the records set aside for review."));
        assertFalse(SqlQueries.looksLikeSql("id"));
        assertFalse(SqlQueries.looksLikeSql(""));
        assertFalse(SqlQueries.looksLikeSql(null));
        assertFalse(SqlQueries.looksLikeSql("the quick brown fox"));
    }

    @Test
    void statementKindClassifies() {
        assertEquals("select", SqlQueries.statementKind("SELECT * FROM t"));
        assertEquals("insert", SqlQueries.statementKind("INSERT INTO t VALUES (1)"));
        assertEquals("update", SqlQueries.statementKind("update t set x = 1"));
        assertEquals("delete", SqlQueries.statementKind("DELETE FROM t"));
        assertEquals("create", SqlQueries.statementKind("CREATE TABLE t (id int)"));
        assertEquals("alter", SqlQueries.statementKind("ALTER TABLE t ADD c int"));
        assertEquals("drop", SqlQueries.statementKind("DROP TABLE t"));
        assertEquals("merge", SqlQueries.statementKind("MERGE INTO t USING s ON t.id = s.id"));
        assertEquals("with", SqlQueries.statementKind("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertEquals("other", SqlQueries.statementKind("EXPLAIN SELECT 1"));
    }

    @Test
    void referencedTablesFromSelectWithJoin() {
        assertEquals(List.of("users", "orders"),
                SqlQueries.referencedTables("SELECT a, b FROM users u JOIN orders o ON u.id = o.uid"));
    }

    @Test
    void referencedTablesFromInsert() {
        assertEquals(List.of("accounts"),
                SqlQueries.referencedTables("INSERT INTO accounts (id, name) VALUES (?, ?)"));
    }

    @Test
    void referencedTablesFromParameterizedUpdate() {
        assertEquals(List.of("users"),
                SqlQueries.referencedTables("UPDATE users SET x = ? WHERE id = :id"));
    }

    @Test
    void referencedTablesKeepsSchemaQualifier() {
        assertEquals(List.of("sales.orders"),
                SqlQueries.referencedTables("SELECT * FROM sales.orders WHERE total > 0"));
    }

    @Test
    void referencedTablesNeverThrows() {
        assertTrue(SqlQueries.referencedTables("this is not sql at all").isEmpty());
        assertTrue(SqlQueries.referencedTables("").isEmpty());
        assertTrue(SqlQueries.referencedTables(null).isEmpty());
    }
}
