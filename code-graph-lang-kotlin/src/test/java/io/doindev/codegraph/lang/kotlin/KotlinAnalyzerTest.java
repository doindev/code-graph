package io.doindev.codegraph.lang.kotlin;

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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * All snippets use block-body functions: the fwcd tree-sitter-kotlin grammar (0.3.8.1) cannot
 * parse expression-body functions ({@code fun f() = x}).
 */
class KotlinAnalyzerTest {

    private static final String CODE = """
            import com.acme.core.Repository
            import com.acme.core.Entity

            interface Payable {
                fun total(): Int
            }

            open class Invoice(val id: String, var amount: Int) : Entity(), Payable {
                fun save(repo: Repository, flush: Boolean) {
                    if (amount > 0) {
                        repo.persist(this, flush)
                    }
                    log(amount)
                }

                override fun total(): Int {
                    return amount
                }
            }

            object Registry {
                fun lookup(key: String): Invoice? {
                    return null
                }
            }

            fun log(level: Int) {
                println(level)
            }
            """;

    private final FileFragment fragment =
            new KotlinAnalyzer().extract(new SourceFile("src/Invoice.kt", "kt", CODE));

    @Test
    void classesInterfacesAndObjectsBecomeTypes() {
        declaration(NodeKind.TYPE, "Payable");
        Node invoice = declaration(NodeKind.TYPE, "Invoice");
        assertEquals("Invoice", invoice.name());
        assertEquals(2, invoice.metrics().methodCount());
        declaration(NodeKind.TYPE, "Registry");
    }

    @Test
    void memberFunctionGetsClassQualifiedNameAndArity() {
        Node save = declaration(NodeKind.FUNCTION, "Invoice.save");
        assertEquals("save", save.name());
        assertEquals(2, ((SymbolId) save.id()).arity());
        assertEquals(2, save.metrics().paramCount());
        assertEquals("save(repo: Repository, flush: Boolean)", save.displaySignature());

        Node lookup = declaration(NodeKind.FUNCTION, "Registry.lookup");
        assertEquals(1, ((SymbolId) lookup.id()).arity());

        Node log = declaration(NodeKind.FUNCTION, "log");
        assertEquals(1, ((SymbolId) log.id()).arity());
    }

    @Test
    void navigationCallIsCapturedWithNameReceiverAndArity() {
        RawRef persist = call("persist");
        assertEquals("repo", persist.receiverHint());
        assertEquals(2, persist.arity());
        assertEquals("Invoice.save", ((SymbolId) persist.from()).qualifiedName());
    }

    @Test
    void bareCallIsCapturedWithoutReceiver() {
        RawRef log = call("log");
        assertNull(log.receiverHint());
        assertEquals(1, log.arity());
        assertEquals("Invoice.save", ((SymbolId) log.from()).qualifiedName());
    }

    @Test
    void delegationSpecifiersBecomeExtendsRefs() {
        List<String> supertypes = fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.EXTENDS && r.from() instanceof SymbolId s
                        && s.qualifiedName().equals("Invoice"))
                .map(RawRef::name)
                .toList();
        assertEquals(List.of("Entity", "Payable"), supertypes);
    }

    @Test
    void importHeadersAreCollected() {
        assertEquals(List.of("com.acme.core.Repository", "com.acme.core.Entity"),
                fragment.imports());
    }

    @Test
    void packageHeaderPrefixesQualifiedNames() {
        FileFragment packaged = new KotlinAnalyzer().extract(new SourceFile("src/K.kt", "kt", """
                package com.acme.billing

                class Klass {
                    fun method(x: Int) {
                        println(x)
                    }
                }
                """));
        boolean found = packaged.declarations().stream()
                .anyMatch(n -> n.kind() == NodeKind.FUNCTION && n.id() instanceof SymbolId s
                        && s.qualifiedName().equals("com.acme.billing.Klass.method"));
        assertEquals(true, found, "expected com.acme.billing.Klass.method in "
                + packaged.declarations());
    }

    private Node declaration(NodeKind kind, String qualifiedName) {
        return fragment.declarations().stream()
                .filter(n -> n.kind() == kind && n.id() instanceof SymbolId s
                        && s.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " " + qualifiedName
                        + " in " + fragment.declarations()));
    }

    private RawRef call(String name) {
        return fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.CALL && r.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no CALL " + name + " in " + fragment.rawRefs()));
    }
}
