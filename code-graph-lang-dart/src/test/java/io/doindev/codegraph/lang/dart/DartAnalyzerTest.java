package io.doindev.codegraph.lang.dart;

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

class DartAnalyzerTest {

    private static final String CODE = """
            import 'dart:math';
            import 'package:foo/bar.dart';

            class Animal {
              String name;
              Animal(this.name);
              void speak() {
                print(name);
              }
            }

            class Dog extends Animal implements Comparable<Dog> {
              Dog(String name) : super(name);
              void speak() {
                makeSound();
                var x = compute(1, 2);
              }
            }

            mixin Swimmer {
              void swim() {}
            }

            enum Color { red, green, blue }

            int add(int a, int b) => a + b;
            """;

    private final FileFragment fragment =
            new DartAnalyzer().extract(new SourceFile("src/x.dart", "dart", CODE));

    @Test
    void typeDeclarationsExtracted() {
        assertNotNull(type("Animal"));
        assertNotNull(type("Dog"));
        assertNotNull(type("Swimmer"));   // mixin_declaration has no `name` field
        assertNotNull(type("Color"));     // enum_declaration
        assertEquals("dart:src/x.dart#Animal/0", type("Animal").id().value());
    }

    @Test
    void functionNameAndArity() {
        Node add = function("add");
        assertNotNull(add);
        assertEquals(2, add.metrics().paramCount());
        assertEquals("add", ((SymbolId) add.id()).qualifiedName());
        assertEquals(2, ((SymbolId) add.id()).arity());

        Node speak = function("speak");
        assertNotNull(speak);
        assertEquals(0, speak.metrics().paramCount());
    }

    @Test
    void callsCaptured() {
        RawRef compute = call("compute");
        assertNotNull(compute);
        assertEquals(2, compute.arity());

        assertNotNull(call("print"));
        assertNotNull(call("makeSound"));
    }

    @Test
    void importsCollected() {
        assertEquals(List.of("dart:math", "package:foo/bar.dart"), fragment.imports());
    }

    @Test
    void supertypesCaptured() {
        assertTrue(superRef(RefKind.EXTENDS, "Animal"), "Dog extends Animal");
        assertTrue(superRef(RefKind.IMPLEMENTS, "Comparable"), "Dog implements Comparable");
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
