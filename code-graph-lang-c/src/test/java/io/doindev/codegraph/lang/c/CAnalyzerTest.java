package io.doindev.codegraph.lang.c;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CAnalyzerTest {

    private static final String CODE = """
            #include <stdio.h>
            #include "acme/order.h"

            struct order {
                int id;
                char *label;
            };

            enum status { OK, FAILED };

            union value {
                int i;
                float f;
            };

            static int order_total(struct order *o, int tax) {
                int total = o->id + tax;
                for (int i = 0; i < tax; i++) {
                    total += compute_line(o, i);
                }
                if (total > 100) {
                    printf("big order %d\\n", total);
                }
                return total;
            }
            """;

    private final FileFragment fragment =
            new CAnalyzer().extract(new SourceFile("src/x.c", "c", CODE));

    @Test
    void typeDeclarationsExtracted() {
        assertNotNull(type("order"));
        assertNotNull(type("status"));
        assertNotNull(type("value"));
        // `struct order *o` in the parameter list is a reference, not a second declaration
        long orderDecls = fragment.declarations().stream()
                .filter(n -> n.kind() == NodeKind.TYPE && n.name().equals("order"))
                .count();
        assertEquals(1, orderDecls);
    }

    @Test
    void functionNameAndArity() {
        Node fn = function("order_total");
        assertNotNull(fn);
        assertEquals(2, fn.metrics().paramCount());
        assertEquals("order_total", ((SymbolId) fn.id()).qualifiedName());
        assertEquals(2, ((SymbolId) fn.id()).arity());
    }

    @Test
    void callsCapturedWithArity() {
        RawRef computeLine = call("compute_line");
        assertNotNull(computeLine);
        assertEquals(2, computeLine.arity());
        assertEquals("c:src/x.c#order_total/2", computeLine.from().value());

        RawRef printf = call("printf");
        assertNotNull(printf);
        assertEquals(2, printf.arity());
    }

    @Test
    void includesCollected() {
        assertEquals(List.of("stdio.h", "acme/order.h"), fragment.imports());
    }

    @Test
    void structFieldsExtracted() {
        Node id = variable("id");
        assertNotNull(id);
        assertEquals("c:src/x.c#order.id/0", id.id().value());
        assertNotNull(variable("label")); // drilled through the pointer_declarator
    }

    private Node type(String name) {
        return declaration(NodeKind.TYPE, name);
    }

    private Node function(String name) {
        return declaration(NodeKind.FUNCTION, name);
    }

    private Node variable(String name) {
        return declaration(NodeKind.VARIABLE, name);
    }

    private Node declaration(NodeKind kind, String name) {
        return fragment.declarations().stream()
                .filter(n -> n.kind() == kind && n.name().equals(name))
                .findFirst().orElse(null);
    }

    private RawRef call(String name) {
        return fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.CALL && r.name().equals(name))
                .findFirst().orElse(null);
    }

    @Test
    void anonymousStructSkippedButFieldsStillWalked() {
        String code = """
                typedef struct {
                    int width;
                } box_t;
                """;
        FileFragment anon = new CAnalyzer().extract(new SourceFile("src/anon.c", "c", code));
        assertTrue(anon.declarations().stream().noneMatch(n -> n.kind() == NodeKind.TYPE),
                "anonymous struct must not declare a TYPE node");
    }
}
