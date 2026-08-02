package io.doindev.codegraph.lang.sql;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.SourceFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SqlAnalyzerTest {

    private static final String SCHEMA = """
            CREATE TABLE users (
              id INT PRIMARY KEY,
              name VARCHAR(100)
            );

            CREATE TABLE orders (
              id INT PRIMARY KEY,
              user_id INT
            );

            CREATE VIEW active_users AS
              SELECT id, name FROM users WHERE active = 1;

            CREATE FUNCTION add_one(x INT) RETURNS INT
              RETURN x + 1;
            """;

    private final FileFragment fragment =
            new SqlAnalyzer().extract(new SourceFile("db/schema.sql", "sql", SCHEMA));

    @Test
    void tablesAndViewsBecomeTypeNodes() {
        assertNotNull(type("users"));
        assertNotNull(type("orders"));
        assertNotNull(type("active_users"));
    }

    @Test
    void routineBecomesFunctionNode() {
        Node fn = declaration(NodeKind.FUNCTION, "add_one");
        assertNotNull(fn);
        assertEquals(1, fn.metrics().paramCount());
    }

    @Test
    void fileNodeCarriesLanguage() {
        Node file = fragment.declarations().stream()
                .filter(n -> n.kind() == NodeKind.FILE).findFirst().orElseThrow();
        assertEquals("sql", file.attrs().get("lang"));
    }

    private Node type(String name) {
        return declaration(NodeKind.TYPE, name);
    }

    private Node declaration(NodeKind kind, String name) {
        return fragment.declarations().stream()
                .filter(n -> n.kind() == kind && n.name().equals(name))
                .findFirst().orElse(null);
    }
}
