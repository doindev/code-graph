package io.doindev.codegraph.lang.objc;

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

class ObjcAnalyzerTest {

    private static final String CODE = """
            #import <Foundation/Foundation.h>
            #import "MyHeader.h"

            @protocol Drawable
            - (void)draw;
            @end

            @interface Animal : NSObject <Drawable>
            - (void)speak;
            - (int)addValue:(int)a withValue:(int)b;
            @end

            @implementation Animal
            - (void)speak {
                NSLog(@"hi");
                [self draw];
                [self addValue:1 withValue:2];
                int r = compute(3, 4);
            }
            - (int)addValue:(int)a withValue:(int)b {
                return a + b;
            }
            - (void)draw {
            }
            @end
            """;

    private final FileFragment fragment =
            new ObjcAnalyzer().extract(new SourceFile("src/x.m", "objc", CODE));

    @Test
    void typeDeclarationsExtracted() {
        assertNotNull(type("Animal"));    // class_interface / class_implementation
        assertNotNull(type("Drawable"));  // protocol_declaration
    }

    @Test
    void unarySelectorFunction() {
        Node speak = function("speak");
        assertNotNull(speak);
        assertEquals(0, speak.metrics().paramCount());
        assertEquals(0, ((SymbolId) speak.id()).arity());
    }

    @Test
    void keywordSelectorFunctionNameAndArity() {
        // selector-naming scheme: full ObjC selector with trailing colons on each keyword part
        Node addValue = function("addValue:withValue:");
        assertNotNull(addValue);
        assertEquals(2, addValue.metrics().paramCount());
        assertEquals(2, ((SymbolId) addValue.id()).arity());
    }

    @Test
    void messageSendsAndCCallsCaptured() {
        // message send with a keyword selector — same naming scheme as the declaration
        RawRef addValue = call("addValue:withValue:");
        assertNotNull(addValue);
        assertEquals(2, addValue.arity());
        assertEquals("self", addValue.receiverHint());

        // unary message send
        assertNotNull(call("draw"));

        // embedded C call_expression
        RawRef compute = call("compute");
        assertNotNull(compute);
        assertEquals(2, compute.arity());
        assertNotNull(call("NSLog"));
    }

    @Test
    void importsCollected() {
        assertEquals(List.of("Foundation/Foundation.h", "MyHeader.h"), fragment.imports());
    }

    @Test
    void supertypesCaptured() {
        assertTrue(superRef(RefKind.EXTENDS, "NSObject"), "Animal : NSObject");
        assertTrue(superRef(RefKind.IMPLEMENTS, "Drawable"), "Animal <Drawable>");
    }

    private boolean superRef(RefKind kind, String name) {
        return fragment.rawRefs().stream()
                .anyMatch(r -> r.kind() == kind && r.name().equals(name));
    }

    private Node type(String name) {
        return declaration(NodeKind.TYPE, name);
    }

    private Node function(String name) {
        return declaration(NodeKind.FUNCTION, name);
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
