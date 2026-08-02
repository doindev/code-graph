package io.doindev.codegraph.lang.go;

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

class GoAnalyzerTest {

    private static final String CODE = """
            package main

            import (
            \t"fmt"
            \t"strings"
            )

            type User struct {
            \tName string
            \tAge  int
            }

            func (u User) Greet(prefix string) string {
            \treturn prefix + u.Name
            }

            func Add(a int, b int) int {
            \tif a > 0 {
            \t\treturn Add(a-1, b+1)
            \t}
            \treturn a + b
            }

            func main() {
            \tu := User{Name: "x"}
            \tfmt.Println(u.Greet("hi"), strings.ToUpper("y"))
            }
            """;

    private final FileFragment fragment =
            new GoAnalyzer().extract(new SourceFile("src/x.go", "go", CODE));

    @Test
    void declaresPackageQualifiedType() {
        Node type = declaration(NodeKind.TYPE, "main.User");
        assertNotNull(type, "TYPE main.User not found in " + fragment.declarations());
        assertEquals("User", type.name());
    }

    @Test
    void declaresFunctionsWithArity() {
        Node add = declaration(NodeKind.FUNCTION, "main.Add");
        assertNotNull(add, "FUNCTION main.Add not found in " + fragment.declarations());
        assertEquals(2, ((SymbolId) add.id()).arity());
        Node greet = declaration(NodeKind.FUNCTION, "main.Greet");
        assertNotNull(greet, "FUNCTION main.Greet not found in " + fragment.declarations());
        assertEquals(1, ((SymbolId) greet.id()).arity());
    }

    @Test
    void collectsCalls() {
        assertTrue(hasCall("Println", "fmt", 2), "call fmt.Println/2 not found in " + fragment.rawRefs());
        assertTrue(hasCall("Greet", "u", 1), "call u.Greet/1 not found in " + fragment.rawRefs());
        assertTrue(hasCall("Add", null, 2), "call Add/2 not found in " + fragment.rawRefs());
        assertTrue(hasCall("ToUpper", "strings", 1), "call strings.ToUpper/1 not found in " + fragment.rawRefs());
    }

    @Test
    void collectsImports() {
        assertEquals(List.of("fmt", "strings"), fragment.imports());
    }

    @Test
    void declaresStructFields() {
        assertNotNull(declaration(NodeKind.VARIABLE, "main.User.Name"),
                "field main.User.Name not found in " + fragment.declarations());
        assertNotNull(declaration(NodeKind.VARIABLE, "main.User.Age"),
                "field main.User.Age not found in " + fragment.declarations());
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
