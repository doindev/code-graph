package io.doindev.codegraph.lang.scala;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScalaAnalyzerTest {

    private static final String CODE = """
            package com.acme.billing

            import com.acme.core.Repository
            import com.acme.core.Entity

            trait Payable {
              def total(): Int
            }

            class Invoice(id: String, amount: Int) extends Entity with Payable {
              val label: String = "inv"

              def save(repo: Repository, flush: Boolean): Unit = {
                if (amount > 0) {
                  repo.persist(this, flush)
                }
                log(amount)
              }

              def total(): Int = {
                amount
              }
            }

            object Registry {
              def lookup(key: String): Option[Invoice] = {
                None
              }
            }

            case class Money(cents: Int)
            """;

    private final FileFragment fragment =
            new ScalaAnalyzer().extract(new SourceFile("src/Invoice.scala", "scala", CODE));

    @Test
    void classesTraitsObjectsAndCaseClassesBecomeTypes() {
        declaration(NodeKind.TYPE, "com.acme.billing.Payable");
        Node invoice = declaration(NodeKind.TYPE, "com.acme.billing.Invoice");
        assertEquals("Invoice", invoice.name());
        assertEquals(2, invoice.metrics().methodCount());
        declaration(NodeKind.TYPE, "com.acme.billing.Registry");
        declaration(NodeKind.TYPE, "com.acme.billing.Money");
    }

    @Test
    void memberFunctionGetsPackageQualifiedNameAndArity() {
        Node save = declaration(NodeKind.FUNCTION, "com.acme.billing.Invoice.save");
        assertEquals("save", save.name());
        assertEquals(2, ((SymbolId) save.id()).arity());
        assertEquals(2, save.metrics().paramCount());

        Node lookup = declaration(NodeKind.FUNCTION, "com.acme.billing.Registry.lookup");
        assertEquals(1, ((SymbolId) lookup.id()).arity());
    }

    @Test
    void fieldBecomesVariable() {
        declaration(NodeKind.VARIABLE, "com.acme.billing.Invoice.label");
    }

    @Test
    void navigationCallIsCapturedWithNameReceiverAndArity() {
        RawRef persist = call("persist");
        assertEquals("repo", persist.receiverHint());
        assertEquals(2, persist.arity());
        assertEquals("com.acme.billing.Invoice.save", ((SymbolId) persist.from()).qualifiedName());
    }

    @Test
    void bareCallIsCapturedWithoutReceiver() {
        RawRef log = call("log");
        assertNull(log.receiverHint());
        assertEquals(1, log.arity());
    }

    @Test
    void extendsClauseTypesBecomeExtendsRefs() {
        List<String> supertypes = fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.EXTENDS && r.from() instanceof SymbolId s
                        && s.qualifiedName().equals("com.acme.billing.Invoice"))
                .map(RawRef::name)
                .toList();
        assertEquals(List.of("Entity", "Payable"), supertypes);
    }

    @Test
    void importsAreCollected() {
        assertEquals(List.of("com.acme.core.Repository", "com.acme.core.Entity"),
                fragment.imports());
    }

    @Test
    void extensionlessScAlsoHandled() {
        assertTrue(new ScalaAnalyzer().fileExtensions().contains("sc"));
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
