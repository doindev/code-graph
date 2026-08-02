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

class CppAnalyzerTest {

    private static final String CODE = """
            #include <vector>
            #include "acme/base.hpp"

            namespace ns {

            class Base {
            };

            class Widget : public Base {
            public:
                Widget(int w, int h) : width(w), height(h) {}

                int area(int scale, int pad) {
                    if (scale > 1) {
                        return compute(width * scale, pad);
                    }
                    return helper.measure(width, height);
                }

            private:
                int width;
                int height;
                Helper helper;
            };

            }

            int Acme::total(int amount) {
                return std::max(amount, 0);
            }
            """;

    private final FileFragment fragment =
            new CppAnalyzer().extract(new SourceFile("src/x.cpp", "cpp", CODE));

    @Test
    void namespaceQualifiesTypeNames() {
        Node ns = declaration(NodeKind.TYPE, "ns");
        assertNotNull(ns);
        Node widget = declaration(NodeKind.TYPE, "Widget");
        assertNotNull(widget);
        assertEquals("ns.Widget", ((SymbolId) widget.id()).qualifiedName());
    }

    @Test
    void inlineMethodQualifiedByNamespaceAndClass() {
        Node area = declaration(NodeKind.FUNCTION, "area");
        assertNotNull(area);
        assertEquals("ns.Widget.area", ((SymbolId) area.id()).qualifiedName());
        assertEquals(2, area.metrics().paramCount());
        assertEquals(2, ((SymbolId) area.id()).arity());

        Node ctor = declaration(NodeKind.FUNCTION, "Widget");
        assertNotNull(ctor);
        assertEquals("ns.Widget.Widget", ((SymbolId) ctor.id()).qualifiedName());
        assertEquals(2, ctor.metrics().paramCount());
    }

    @Test
    void qualifiedOutOfClassDefinitionUsesFullText() {
        Node total = declaration(NodeKind.FUNCTION, "Acme::total");
        assertNotNull(total);
        assertEquals(1, total.metrics().paramCount());
    }

    @Test
    void baseClassClauseYieldsExtends() {
        RawRef extendsRef = fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.EXTENDS)
                .findFirst().orElse(null);
        assertNotNull(extendsRef);
        assertEquals("Base", extendsRef.name());
        assertEquals("ns.Widget", ((SymbolId) extendsRef.from()).qualifiedName());
    }

    @Test
    void callsCapturedWithReceiverAndArity() {
        RawRef compute = call("compute");
        assertNotNull(compute);
        assertEquals(2, compute.arity());
        assertEquals("cpp:src/x.cpp#ns.Widget.area/2", compute.from().value());

        RawRef measure = call("measure");
        assertNotNull(measure);
        assertEquals("helper", measure.receiverHint());
        assertEquals(2, measure.arity());

        RawRef max = call("max"); // std::max — qualified_identifier callee
        assertNotNull(max);
        assertEquals("std", max.receiverHint());
        assertEquals(2, max.arity());
    }

    @Test
    void includesCollected() {
        assertEquals(List.of("vector", "acme/base.hpp"), fragment.imports());
    }

    @Test
    void classFieldsExtracted() {
        Node width = declaration(NodeKind.VARIABLE, "width");
        assertNotNull(width);
        assertEquals("ns.Widget.width", ((SymbolId) width.id()).qualifiedName());
        assertNotNull(declaration(NodeKind.VARIABLE, "height"));
        assertNotNull(declaration(NodeKind.VARIABLE, "helper"));
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
}
