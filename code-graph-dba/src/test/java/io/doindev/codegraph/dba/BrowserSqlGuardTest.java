package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSqlGuardTest {
    @Test void humanFunctionsWorkWithoutOpeningTheAgentGrammar() {
        for(String sql:List.of("select version();", "SELECT pg_catalog.version()", "SELECT upper(lower(?))", "SELECT count(*) FROM public.items", "SELECT id FROM public.items WHERE lower(value) = ?")) {
            assertEquals(sql,SqlReadGuard.validateBrowser(sql));
            assertThrows(IllegalArgumentException.class,()->SqlReadGuard.validate(sql),sql);
            assertThrows(IllegalArgumentException.class,()->SqlReadGuard.validate(sql,(schema,name)->{}),sql);
        }
    }
    @Test void humanReadsStillRejectMultipleStatementsWritesAndUnsupportedNestedSyntax() {
        for(String sql:List.of("SELECT version(); DELETE FROM public.items", "DELETE FROM public.items", "SELECT version() INTO copied", "SELECT version() FROM public.items FOR UPDATE", "WITH deleted AS (DELETE FROM public.items RETURNING *) SELECT * FROM deleted", "SELECT version() FROM (SELECT id INTO copied FROM public.items) x"))
            assertThrows(IllegalArgumentException.class,()->SqlReadGuard.validateBrowser(sql),sql);
    }
}
