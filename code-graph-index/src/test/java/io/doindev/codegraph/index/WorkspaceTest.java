package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceTest {

    @TempDir
    Path temp;

    private final Analyzers analyzers = Analyzers.of(List.of());

    private Workspace emptyWorkspace() {
        return Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults());
    }

    private Workspace.Project add(Workspace workspace, Path root) {
        return workspace.add(root.getFileName().toString(), root, analyzers,
                p -> CodeGraphConfig.defaults());
    }

    @Test
    void rejectsChildAndDuplicateRootsButAllowsSiblingWithSamePrefix() throws IOException {
        Path parent = Files.createDirectory(temp.resolve("repo"));
        Path child = Files.createDirectories(parent.resolve("src/nested"));
        Path sibling = Files.createDirectory(temp.resolve("repo-other"));
        try (Workspace workspace = emptyWorkspace()) {
            workspace.watchAll();
            add(workspace, parent);
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> add(workspace, child));
            assertTrue(error.getMessage().contains("child path"));
            assertTrue(error.getMessage().contains("repo"));
            assertThrows(IllegalArgumentException.class,
                    () -> add(workspace, child.resolve("../..")));
            assertThrows(IllegalArgumentException.class,
                    () -> add(workspace, parent.resolve("../repo/src")));
            add(workspace, sibling);
            assertEquals(2, workspace.projects().size());
            assertEquals(2, workspace.activeWatcherCount());

            workspace.remove("repo");
            add(workspace, child); // removing a parent releases the restriction
            assertEquals(2, workspace.projects().size());
        }
    }

    @Test
    void missingDirectoriesAndFilesLeaveNoProjectsOrWatchers() throws IOException {
        Path file = Files.writeString(temp.resolve("file.txt"), "hello");
        try (Workspace workspace = emptyWorkspace()) {
            workspace.watchAll();
            assertThrows(IllegalArgumentException.class, () -> add(workspace, file));
            assertThrows(IllegalArgumentException.class,
                    () -> add(workspace, temp.resolve("missing")));
            assertTrue(workspace.projects().isEmpty());
            assertEquals(0, workspace.activeWatcherCount());
        }
    }

    @Test
    void startupRootsUseTheSameSafetyCheck() throws IOException {
        Path child = Files.createDirectory(temp.resolve("child"));
        assertThrows(IllegalArgumentException.class,
                () -> Workspace.open(List.of(temp, child), analyzers, p -> CodeGraphConfig.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> Workspace.open(List.of(temp, temp), analyzers, p -> CodeGraphConfig.defaults()));
    }

    @Test
    void addingAParentAfterItsChildIsAllowedByTheDirectionalRule() throws IOException {
        Path child = Files.createDirectory(temp.resolve("child"));
        try (Workspace workspace = emptyWorkspace()) {
            add(workspace, child);
            add(workspace, temp);
            assertEquals(2, workspace.projects().size());
        }
    }

    @Test
    void symlinkAliasesCannotBypassChildOrDuplicateChecks() throws IOException {
        Path parent = Files.createDirectory(temp.resolve("repo"));
        Files.createDirectory(parent.resolve("child"));
        Path alias = temp.resolve("alias");
        try {
            Files.createSymbolicLink(alias, parent);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }
        try (Workspace workspace = emptyWorkspace()) {
            add(workspace, parent);
            assertThrows(IllegalArgumentException.class, () -> add(workspace, alias));
            assertThrows(IllegalArgumentException.class, () -> add(workspace, alias.resolve("child")));
        }
    }

    @Test
    void windowsCaseAliasesCannotBypassTheCheck() throws IOException {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path parent = Files.createDirectory(temp.resolve("MixedCase"));
        Path child = Files.createDirectory(parent.resolve("Child"));
        try (Workspace workspace = emptyWorkspace()) {
            add(workspace, parent);
            assertThrows(IllegalArgumentException.class,
                    () -> add(workspace, Path.of(child.toString().toUpperCase(java.util.Locale.ROOT))));
        }
    }

    @Test
    @Timeout(15)
    void inFlightParentReservesItsPathAndFailureReleasesTheReservation() throws Exception {
        Path child = Files.createDirectory(temp.resolve("child"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Workspace workspace = emptyWorkspace()) {
            CompletableFuture<Workspace.Project> parent = CompletableFuture.supplyAsync(() ->
                    workspace.add("parent", temp, analyzers, p -> {
                        entered.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test latch timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        throw new IllegalStateException("simulated config failure");
                    }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(IllegalArgumentException.class, () -> add(workspace, child));
                assertThrows(IllegalArgumentException.class, () -> add(workspace, temp));
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> parent.get(5, TimeUnit.SECONDS));
            assertTrue(workspace.projects().isEmpty());
            add(workspace, child);
            assertEquals(1, workspace.projects().size());
        }
    }

    @Test
    void emptyWorkspaceCanWatchAddRemoveLastAndAddAgain() throws IOException {
        Analyzers analyzers = Analyzers.of(List.of());
        Files.createDirectories(temp.resolve("first"));
        Files.createDirectories(temp.resolve("second"));
        try (Workspace workspace = Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults())) {
            assertEquals(List.of(), workspace.projects());
            assertNull(workspace.defaultProject());

            workspace.watchAll();
            Workspace.Project first = workspace.add("first", temp.resolve("first"), analyzers,
                    p -> CodeGraphConfig.defaults());
            assertEquals("first", workspace.defaultProject().name());
            assertEquals(1, workspace.activeWatcherCount());

            workspace.remove(first.name());
            assertNull(workspace.defaultProject());
            assertEquals(0, workspace.activeWatcherCount());

            workspace.add("second", temp.resolve("second"), analyzers,
                    p -> CodeGraphConfig.defaults());
            assertEquals(1, workspace.activeWatcherCount());
        }
    }
}
