package io.doindev.codegraph.lang.rust;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.SourceFile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RustAnalyzerTest {

    private static final String CODE = """
            use std::collections::HashMap;
            use std::fmt::Display;

            pub struct Point {
                x: i32,
                y: i32,
            }

            impl Point {
                pub fn new(x: i32, y: i32) -> Point {
                    Point { x, y }
                }

                pub fn magnitude(&self) -> f64 {
                    ((self.x * self.x + self.y * self.y) as f64).sqrt()
                }
            }

            pub trait Shape {
                fn area(&self) -> f64;
            }

            fn helper(n: i32) -> i32 {
                if n > 0 { helper(n - 1) } else { 0 }
            }

            fn main() {
                let p = Point::new(1, 2);
                let m = p.magnitude();
                let mut map = HashMap::new();
                map.insert("k", helper(3));
                println!("{} {}", m, map.len());
            }
            """;

    private final FileFragment fragment =
            new RustAnalyzer().extract(new SourceFile("src/x.rs", "rs", CODE));

    @Test
    void declaresTypes() {
        assertNotNull(declaration(NodeKind.TYPE, "Point"),
                "TYPE Point not found in " + fragment.declarations());
        assertNotNull(declaration(NodeKind.TYPE, "Shape"),
                "TYPE Shape not found in " + fragment.declarations());
        // struct Point and impl Point both declare a TYPE; the second gets a collision hash
        long points = fragment.declarations().stream()
                .filter(n -> n.kind() == NodeKind.TYPE && n.id() instanceof SymbolId s
                        && s.qualifiedName().equals("Point"))
                .count();
        assertEquals(2, points);
    }

    @Test
    void implMethodsQualifyUnderTargetType() {
        Node magnitude = declaration(NodeKind.FUNCTION, "Point.magnitude");
        assertNotNull(magnitude, "FUNCTION Point.magnitude not found in " + fragment.declarations());
        assertEquals(0, ((SymbolId) magnitude.id()).arity(), "self must not count toward arity");
        Node constructor = declaration(NodeKind.FUNCTION, "Point.new");
        assertNotNull(constructor, "FUNCTION Point.new not found in " + fragment.declarations());
        assertEquals(2, ((SymbolId) constructor.id()).arity());
    }

    @Test
    void declaresFunctionsWithArity() {
        Node helper = declaration(NodeKind.FUNCTION, "helper");
        assertNotNull(helper, "FUNCTION helper not found in " + fragment.declarations());
        assertEquals(1, ((SymbolId) helper.id()).arity());
        Node area = declaration(NodeKind.FUNCTION, "Shape.area");
        assertNotNull(area, "FUNCTION Shape.area not found in " + fragment.declarations());
        assertEquals(0, ((SymbolId) area.id()).arity());
    }

    @Test
    void collectsCalls() {
        assertTrue(hasCall("new", "Point", 2), "call Point::new/2 not found in " + fragment.rawRefs());
        assertTrue(hasCall("new", "HashMap", 0), "call HashMap::new/0 not found in " + fragment.rawRefs());
        assertTrue(hasCall("insert", "map", 2), "call map.insert/2 not found in " + fragment.rawRefs());
        assertTrue(hasCall("magnitude", "p", 0), "call p.magnitude/0 not found in " + fragment.rawRefs());
        assertTrue(hasCall("helper", null, 1), "call helper/1 not found in " + fragment.rawRefs());
    }

    @Test
    void collectsImports() {
        assertEquals(List.of("std::collections::HashMap", "std::fmt::Display"), fragment.imports());
    }

    private Node declaration(NodeKind kind, String qualifiedName) {
        return fragment.declarations().stream()
                .filter(n -> n.kind() == kind && n.id() instanceof SymbolId s
                        && s.qualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
    }

    private boolean hasCall(String name, String receiverHint, int arity) {
        for (RawRef ref : fragment.rawRefs()) {
            if (ref.kind() == RefKind.CALL && ref.name().equals(name)
                    && Objects.equals(ref.receiverHint(), receiverHint) && ref.arity() == arity) {
                return true;
            }
        }
        return false;
    }
}
