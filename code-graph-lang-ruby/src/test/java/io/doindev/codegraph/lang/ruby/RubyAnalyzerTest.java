package io.doindev.codegraph.lang.ruby;

import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.parse.FileFragment;
import io.doindev.codegraph.parse.RawRef;
import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.SourceFile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubyAnalyzerTest {

    private static final String CODE = """
            require "json"

            module Billing
              class Invoice < ApplicationRecord
                def save(repo, flush)
                  if @amount > 0
                    repo.persist(self, flush)
                  end
                  total = compute_total(1, 2)
                  repo.save!
                end

                def self.find(id)
                  lookup(id)
                end
              end

              class Refund < Billing::Invoice
                def total
                  0
                end
              end
            end
            """;

    private final FileFragment fragment =
            new RubyAnalyzer().extract(new SourceFile("src/billing.rb", "rb", CODE));

    @Test
    void nestedModuleAndClassGetDotJoinedQualifiedNames() {
        Node module = declaration(NodeKind.TYPE, "Billing");
        assertEquals("Billing", module.name());
        Node invoice = declaration(NodeKind.TYPE, "Billing.Invoice");
        assertEquals("Invoice", invoice.name());
        assertEquals(2, invoice.metrics().methodCount());
        declaration(NodeKind.TYPE, "Billing.Refund");
    }

    @Test
    void methodsCarryNameAndArity() {
        Node save = declaration(NodeKind.FUNCTION, "Billing.Invoice.save");
        assertEquals("save", save.name());
        assertEquals(2, ((SymbolId) save.id()).arity());
        assertEquals(2, save.metrics().paramCount());
        assertEquals("save(repo, flush)", save.displaySignature());

        Node find = declaration(NodeKind.FUNCTION, "Billing.Invoice.find");
        assertEquals(1, ((SymbolId) find.id()).arity());

        Node total = declaration(NodeKind.FUNCTION, "Billing.Refund.total");
        assertEquals(0, ((SymbolId) total.id()).arity());
    }

    @Test
    void receiverCallIsCapturedWithNameReceiverAndArity() {
        RawRef persist = call("persist");
        assertEquals("repo", persist.receiverHint());
        assertEquals(2, persist.arity());
        assertEquals("Billing.Invoice.save", ((SymbolId) persist.from()).qualifiedName());
    }

    @Test
    void bareCallIsCapturedWithoutReceiver() {
        RawRef computeTotal = call("compute_total");
        assertNull(computeTotal.receiverHint());
        assertEquals(2, computeTotal.arity());

        RawRef saveBang = call("save!");
        assertEquals("repo", saveBang.receiverHint());
        assertEquals(0, saveBang.arity());
    }

    @Test
    void superclassBecomesExtendsRef() {
        RawRef invoiceSuper = superRef("Billing.Invoice");
        assertEquals("ApplicationRecord", invoiceSuper.name());
        // scoped superclass Billing::Invoice resolves to its simple name
        RawRef refundSuper = superRef("Billing.Refund");
        assertEquals("Invoice", refundSuper.name());
    }

    @Test
    void rubyHasNoStaticImports() {
        assertTrue(fragment.imports().isEmpty());
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

    private RawRef superRef(String fromQualifiedName) {
        return fragment.rawRefs().stream()
                .filter(r -> r.kind() == RefKind.EXTENDS && r.from() instanceof SymbolId s
                        && s.qualifiedName().equals(fromQualifiedName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no EXTENDS from " + fromQualifiedName
                        + " in " + fragment.rawRefs()));
    }
}
