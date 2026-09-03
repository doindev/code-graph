package io.doindev.codegraph.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** One server's idle policy. Registration, activity and retirement share a single lock. */
public final class ProjectLifecycle implements AutoCloseable {
    public static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)([smhd])");

    public record Status(long instanceId, Instant lastActivityAt, Instant expiresAt,
                         long remainingSeconds, int activeOperations) { }

    private static final class Entry {
        final long id;
        long lastNanos;
        Instant lastAt;
        int active;
        Entry(long id) { this.id = id; }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final LongSupplier ticker;
    private final Supplier<Instant> clock;
    private final Consumer<String> onRemove;
    private Duration ttl = DEFAULT_TTL;
    private long ttlNanos = DEFAULT_TTL.toNanos();
    private long nextId;
    private boolean closed;
    private ScheduledExecutorService sweeper;

    public ProjectLifecycle(Consumer<String> onRemove) {
        this(onRemove, System::nanoTime, Instant::now);
    }

    /** Clock injection keeps expiry and race tests deterministic without long sleeps. */
    public ProjectLifecycle(Consumer<String> onRemove, LongSupplier ticker, Supplier<Instant> clock) {
        this.onRemove = onRemove;
        this.ticker = ticker;
        this.clock = clock;
    }

    public static Duration parseTtl(String value) {
        var matcher = DURATION.matcher(value == null ? "" : value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("project TTL must be a positive duration, e.g. 90s, 30m or 1h");
        }
        try {
            long amount = Long.parseLong(matcher.group(1));
            Duration duration = switch (matcher.group(2)) {
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
            duration.toNanos(); // reject overflow before startup or a settings change
            return duration;
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("project TTL is too large", e);
        }
    }

    public synchronized Duration ttl() { return ttl; }

    /** Policy changes do not count as activity; shorter policies may expire projects next sweep. */
    public synchronized void setTtl(Duration duration) {
        validateTtl(duration);
        ttl = duration;
        ttlNanos = duration.toNanos();
    }

    public static void validateTtl(Duration duration) {
        long nanos;
        try {
            nanos = duration.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("project TTL is too large", e);
        }
        if (nanos <= 0) throw new IllegalArgumentException("project TTL must be positive");
    }

    /** Publish only after indexing finishes. The callback must not perform a long-running scan. */
    public synchronized long register(String name, Runnable publish) {
        if (closed) throw new IllegalStateException("project lifecycle is closed");
        if (entries.containsKey(name)) throw new IllegalArgumentException("project already registered: " + name);
        publish.run();
        Entry entry = new Entry(++nextId);
        touch(entry);
        entries.put(name, entry);
        return entry.id;
    }

    public synchronized Lease use(String name) { return use(name, 0); }

    /** An expected instance prevents stale browser requests or jobs renewing a re-added project. */
    public synchronized Lease use(String name, long expectedInstance) {
        Entry entry = entries.get(name);
        if (entry == null || closed || (expectedInstance != 0 && entry.id != expectedInstance)) return null;
        touch(entry);
        entry.active++;
        return new Lease(name, entry);
    }

    public final class Lease implements AutoCloseable {
        private final String name;
        private final Entry entry;
        private boolean released;
        private Lease(String name, Entry entry) { this.name = name; this.entry = entry; }
        public long instanceId() { return entry.id; }
        @Override public void close() {
            synchronized (ProjectLifecycle.this) {
                if (released) return;
                released = true;
                entry.active--;
                if (entries.get(name) == entry) touch(entry);
            }
        }
    }

    private void touch(Entry entry) {
        entry.lastNanos = ticker.getAsLong();
        entry.lastAt = clock.get();
    }

    /** A passive snapshot; listing and displaying countdowns never renew a project. */
    public synchronized Status status(String name) {
        Entry entry = entries.get(name);
        if (entry == null) return null;
        long remaining = Math.max(0, ttlNanos - (ticker.getAsLong() - entry.lastNanos));
        long seconds = remaining / 1_000_000_000L + (remaining % 1_000_000_000L == 0 ? 0 : 1);
        return new Status(entry.id, entry.lastAt, entry.lastAt.plus(ttl), seconds, entry.active);
    }

    public synchronized boolean remove(String name) {
        if (entries.remove(name) == null) return false;
        onRemove.accept(name);
        return true;
    }

    public synchronized boolean remove(String name, long expectedInstance) {
        Entry entry = entries.get(name);
        return entry != null && entry.id == expectedInstance && remove(name);
    }

    public synchronized int expireIdle() {
        int removed = 0;
        long now = ticker.getAsLong();
        for (String name : List.copyOf(entries.keySet())) {
            Entry entry = entries.get(name);
            if (entry.active == 0 && now - entry.lastNanos >= ttlNanos) {
                remove(name);
                removed++;
                System.err.println("code-graph: expired idle project '" + name + "'");
            }
        }
        return removed;
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("project lifecycle is closed");
        if (sweeper != null) return;
        sweeper = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("code-graph-project-expiry").factory());
        sweeper.scheduleWithFixedDelay(() -> {
            try { expireIdle(); }
            catch (RuntimeException e) { System.err.println("code-graph: project expiry failed: " + e); }
        }, 5, 5, TimeUnit.SECONDS);
    }

    @Override public synchronized void close() {
        closed = true;
        if (sweeper != null) sweeper.shutdownNow();
        for (String name : List.copyOf(entries.keySet())) remove(name);
    }
}
