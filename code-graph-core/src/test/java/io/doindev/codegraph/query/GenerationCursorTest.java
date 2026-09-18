package io.doindev.codegraph.query;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class GenerationCursorTest {
    @Test void boundsScopeGenerationExpiryAndRestartWithoutRetainingSnapshots() {
        AtomicLong now = new AtomicLong(100);
        var cursors = new GenerationCursor(now::get, 50);
        var start = cursors.read(null, "project/query", 7);
        String token = cursors.issue("project/query", 7, new GenerationCursor.Position("last-id", 20, start.expiresAt()));
        assertEquals("last-id", cursors.read(token, "project/query", 7).key());
        assertEquals(20, cursors.read(token, "project/query", 7).seen());
        assertThrows(IllegalArgumentException.class, () -> cursors.read(token, "other", 7));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> cursors.read(token, "project/query", 8)).getMessage().startsWith("stale_cursor"));
        assertThrows(IllegalArgumentException.class, () -> new GenerationCursor().read(token, "project/query", 7));
        assertThrows(IllegalArgumentException.class, () -> cursors.read("x".repeat(20000), "project/query", 7));
        assertThrows(IllegalArgumentException.class, () -> cursors.read(token + "x", "project/query", 7));
        now.set(150);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> cursors.read(token, "project/query", 7)).getMessage().startsWith("expired_cursor"));
    }
}
