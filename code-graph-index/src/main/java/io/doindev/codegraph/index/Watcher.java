package io.doindev.codegraph.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * Filesystem watcher feeding the {@link IncrementalIndexer}: events are debounced per path
 * (quiescence window with a max coalesce deadline) so IDE save-storms and branch switches
 * produce one batch. On Windows a single recursive registration is used
 * ({@code ExtendedWatchEventModifier.FILE_TREE}); elsewhere directories are registered
 * individually and new directories are registered on CREATE.
 */
public final class Watcher implements AutoCloseable {

    private static final long QUIESCENCE_MS = 250;
    private static final long MAX_COALESCE_MS = 1000;

    private final Path root;
    private final IncrementalIndexer indexer;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keyDirs = new HashMap<>();
    private final ConcurrentHashMap<String, long[]> pending = new ConcurrentHashMap<>(); // [first, last] nanos
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile boolean running = true;
    private final Thread pumpThread;
    private final Thread debounceThread;

    public Watcher(Path root, IncrementalIndexer indexer) {
        this.root = root.toAbsolutePath().normalize();
        this.indexer = indexer;
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            register(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to start watcher on " + root, e);
        }
        this.pumpThread = Thread.ofVirtual().name("code-graph-watch-pump").start(this::pump);
        this.debounceThread = Thread.ofVirtual().name("code-graph-watch-debounce").start(this::debounce);
    }

    /** Blocks until the watcher is receiving events (useful for tests). */
    public void awaitStarted() throws InterruptedException {
        started.await();
    }

    private void register(Path dir) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            WatchKey key = dir.register(watchService,
                    new WatchEvent.Kind<?>[] {StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE},
                    com.sun.nio.file.ExtendedWatchEventModifier.FILE_TREE);
            keyDirs.put(key, dir);
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path sub, BasicFileAttributes attrs) throws IOException {
                if (FullIndexer.ALWAYS_IGNORED_DIRS.contains(sub.getFileName() == null
                        ? "" : sub.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                WatchKey key = sub.register(watchService, StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                keyDirs.put(key, sub);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void pump() {
        started.countDown();
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            Path dir = keyDirs.get(key);
            for (WatchEvent<?> event : key.pollEvents()) {
                if (!(event.context() instanceof Path context) || dir == null) {
                    continue;
                }
                Path absolute = dir.resolve(context);
                String relPath = root.relativize(absolute).toString().replace('\\', '/');
                if (relPath.isEmpty() || crossesIgnoredDir(relPath)) {
                    continue;
                }
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                        && Files.isDirectory(absolute)
                        && !System.getProperty("os.name", "").toLowerCase().contains("win")) {
                    try {
                        register(absolute);
                    } catch (IOException ignored) {
                        // directory vanished between event and registration
                    }
                }
                long now = System.nanoTime();
                pending.compute(relPath, (k, window) -> window == null
                        ? new long[] {now, now} : new long[] {window[0], now});
            }
            key.reset();
        }
    }

    private static boolean crossesIgnoredDir(String relPath) {
        for (String segment : relPath.split("/")) {
            if (FullIndexer.ALWAYS_IGNORED_DIRS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private void debounce() {
        while (running) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            List<String> ready = new ArrayList<>();
            pending.forEach((relPath, window) -> {
                long sinceLastMs = (now - window[1]) / 1_000_000;
                long sinceFirstMs = (now - window[0]) / 1_000_000;
                if (sinceLastMs >= QUIESCENCE_MS || sinceFirstMs >= MAX_COALESCE_MS) {
                    ready.add(relPath);
                }
            });
            if (ready.isEmpty()) {
                indexer.graph().dirtyPending(pending.size());
                continue;
            }
            ready.forEach(pending::remove);
            indexer.graph().dirtyPending(pending.size());
            try {
                indexer.applyChanges(ready);
            } catch (RuntimeException e) {
                System.err.println("code-graph: incremental index failed for " + ready + ": " + e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        pumpThread.interrupt();
        debounceThread.interrupt();
        try {
            watchService.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
