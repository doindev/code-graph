package io.doindev.codegraph.lang.swift;

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

class SwiftAnalyzerTest {

    private static final String CODE = """
            import Foundation
            import UIKit

            protocol Payable {
                func total() -> Int
            }

            class Invoice: Entity, Payable {
                var amount: Int

                init(amount: Int) {
                    self.amount = amount
                }

                func save(repo: Repository, flush: Bool) {
                    if amount > 0 {
                        repo.persist(self, flush)
                    }
                    log(amount)
                }

                func total() -> Int {
                    return amount
                }
            }

            struct Money {
                let cents: Int
            }
            """;

    private final FileFragment fragment =
            new SwiftAnalyzer().extract(new SourceFile("src/Invoice.swift", "swift", CODE));

    @Test
    void classesProtocolsAndStructsBecomeTypes() {
        declaration(NodeKind.TYPE, "Payable");
        Node invoice = declaration(NodeKind.TYPE, "Invoice");
        assertEquals("Invoice", invoice.name());
        assertEquals(3, invoice.metrics().methodCount());
        declaration(NodeKind.TYPE, "Money");
    }

    @Test
    void memberFunctionGetsTypeQualifiedNameAndArity() {
        Node save = declaration(NodeKind.FUNCTION, "Invoice.save");
        assertEquals("save", save.name());
        assertEquals(2, ((SymbolId) save.id()).arity());
        assertEquals(2, save.metrics().paramCount());

        Node init = declaration(NodeKind.FUNCTION, "Invoice.init");
        assertEquals(1, ((SymbolId) init.id()).arity());
    }

    @Test
    void propertyBecomesVariable() {
        declaration(NodeKind.VARIABLE, "Invoice.amount");
        declaration(NodeKind.VARIABLE, "Money.cents");
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
    }

    @Test
    void inheritanceSpecifiersBecomeExtendsRefs() {
        List<String> supertypes = fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.EXTENDS && r.from() instanceof SymbolId s
                        && s.qualifiedName().equals("Invoice"))
                .map(RawRef::name)
                .toList();
        assertEquals(List.of("Entity", "Payable"), supertypes);
    }

    @Test
    void importsAreCollected() {
        assertEquals(List.of("Foundation", "UIKit"), fragment.imports());
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
