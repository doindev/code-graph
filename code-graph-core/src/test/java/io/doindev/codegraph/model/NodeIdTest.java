package io.doindev.codegraph.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeIdTest {

    @Test
    void symbolIdRoundTrips() {
        SymbolId id = new SymbolId("java", "src/main/java/com/acme/AuthService.java",
                "AuthService.validateToken", 1);
        assertEquals("java:src/main/java/com/acme/AuthService.java#AuthService.validateToken/1", id.value());
        assertEquals(id, NodeId.parse(id.value()));
    }

    @Test
    void symbolIdWithCollisionHashRoundTrips() {
        SymbolId id = new SymbolId("cs", "src/Billing.cs", "Billing.Charge", 2, "a3f9");
        assertEquals("cs:src/Billing.cs#Billing.Charge/2~a3f9", id.value());
        assertEquals(id, NodeId.parse(id.value()));
    }

    @Test
    void qualifiedNameMayContainDotsAndColons() {
        SymbolId id = new SymbolId("cpp", "src/order.cpp", "acme::Order::persist", 0);
        assertEquals(id, NodeId.parse(id.value()));
    }

    @Test
    void fileModuleRepoIdsRoundTrip() {
        assertEquals(new FileId("web/src/api/client.ts"), NodeId.parse("file:web/src/api/client.ts"));
        assertEquals(new ModuleId("web"), NodeId.parse("mod:web"));
        assertEquals(new RepoId("acme"), NodeId.parse("repo:acme"));
    }

    @Test
    void rejectsBackslashPaths() {
        assertThrows(IllegalArgumentException.class, () -> new FileId("src\\A.java"));
        assertThrows(IllegalArgumentException.class,
                () -> new SymbolId("java", "src\\A.java", "A", 0));
    }

    @Test
    void rejectsMalformedIds() {
        assertThrows(IllegalArgumentException.class, () -> NodeId.parse(""));
        assertThrows(IllegalArgumentException.class, () -> NodeId.parse("java:src/A.java"));       // no '#'
        assertThrows(IllegalArgumentException.class, () -> NodeId.parse("java:src/A.java#A"));     // no arity
        assertThrows(IllegalArgumentException.class, () -> NodeId.parse("java:src/A.java#A/x"));   // bad arity
    }
}
