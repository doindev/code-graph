package io.doindev.codegraph.lifecycle;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ProjectLifecycleTest {
    private final AtomicLong nanos = new AtomicLong();
    private final List<String> removed = new ArrayList<>();
    private final ProjectLifecycle lifecycle = new ProjectLifecycle(removed::add, nanos::get,
            () -> Instant.EPOCH.plusNanos(nanos.get()));

    private void advance(long seconds) { nanos.addAndGet(Duration.ofSeconds(seconds).toNanos()); }
    private long add(String name) { return lifecycle.register(name, () -> {}); }

    @Test void parsesDefaultsDurationsAndRejectsBadValues() {
        assertEquals(Duration.ofHours(1), lifecycle.ttl());
        assertEquals(Duration.ofMinutes(30), ProjectLifecycle.parseTtl("30m"));
        assertEquals(Duration.ofSeconds(90), ProjectLifecycle.parseTtl("90s"));
        assertEquals(Duration.ofDays(2), ProjectLifecycle.parseTtl("2d"));
        for (String input : List.of("", "0s", "-1h", "1", "1.5h", "forever", "999999999999999999999h", "9223372036854775807s")) {
            assertThrows(IllegalArgumentException.class, () -> ProjectLifecycle.parseTtl(input), input);
        }
        assertThrows(IllegalArgumentException.class, () -> lifecycle.setTtl(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> lifecycle.setTtl(Duration.ofDays(Long.MAX_VALUE / 86400)));
        assertEquals(Duration.ofHours(1), lifecycle.ttl());
    }

    @Test void listingsArePassiveAndOnlyTheUsedProjectIsRenewed() {
        add("a"); add("b");
        advance(1800);
        for (int i = 0; i < 10; i++) assertEquals(1800, lifecycle.status("b").remainingSeconds());
        try (var use = lifecycle.use("a")) { assertNotNull(use); }
        advance(1800);
        assertEquals(1, lifecycle.expireIdle());
        assertNull(lifecycle.status("b"));
        assertEquals(1800, lifecycle.status("a").remainingSeconds());
        advance(1800);
        assertEquals(1, lifecycle.expireIdle());
        assertEquals(List.of("b", "a"), removed);
    }

    @Test void activeOperationPinsUntilCompletionAndThenGetsAFullTimeout() throws Exception {
        add("a");
        var use = lifecycle.use("a");
        advance(7200);
        assertEquals(0, CompletableFuture.supplyAsync(lifecycle::expireIdle).get());
        assertEquals(1, lifecycle.status("a").activeOperations());
        use.close(); use.close();
        assertEquals(0, lifecycle.status("a").activeOperations());
        assertEquals(3600, lifecycle.status("a").remainingSeconds());
        advance(3600);
        assertEquals(1, lifecycle.expireIdle());
    }

    @Test void oldLeasesAndJobIdsCannotRenewOrRemoveReplacements() {
        long oldId = add("a");
        var oldUse = lifecycle.use("a");
        lifecycle.remove("a");
        long replacement = add("a");
        assertNotEquals(oldId, replacement);
        advance(100);
        oldUse.close();
        assertEquals(3500, lifecycle.status("a").remainingSeconds());
        assertNull(lifecycle.use("a", oldId));
        assertFalse(lifecycle.remove("a", oldId));
        assertNotNull(lifecycle.status("a"));
    }

    @Test void settingsRecomputeDeadlinesWithoutTouchingProjects() {
        add("a"); advance(1800);
        lifecycle.setTtl(Duration.ofHours(2));
        assertEquals(5400, lifecycle.status("a").remainingSeconds());
        assertEquals(Instant.EPOCH, lifecycle.status("a").lastActivityAt());
        lifecycle.setTtl(Duration.ofMinutes(10));
        assertEquals(1, lifecycle.expireIdle());
    }

    @Test void wallClockChangesDoNotChangeIdleDecisionsAndCloseReleasesEntries() {
        AtomicLong wall = new AtomicLong();
        try (var policy = new ProjectLifecycle(removed::add, nanos::get, () -> Instant.ofEpochSecond(wall.get()))) {
            policy.register("a", () -> {});
            wall.set(1_000_000);
            assertEquals(0, policy.expireIdle());
            advance(3600);
            wall.set(-1_000_000);
            assertEquals(1, policy.expireIdle());
            policy.register("b", () -> {});
        }
        assertEquals(List.of("a", "b"), removed);
    }
}
