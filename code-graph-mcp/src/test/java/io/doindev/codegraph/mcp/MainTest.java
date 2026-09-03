package io.doindev.codegraph.mcp;

import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MainTest {

    @TempDir
    Path temp;

    @Test void idleTimeoutArgumentDefaultsAndValidation() {
        assertEquals(java.time.Duration.ofHours(1), Main.projectTtl(new String[] {}));
        assertEquals(java.time.Duration.ofMinutes(30), Main.projectTtl(new String[] {"--project-ttl", "30m"}));
        assertThrows(IllegalArgumentException.class, () -> Main.projectTtl(new String[] {"--project-ttl"}));
        assertThrows(IllegalArgumentException.class, () -> Main.projectTtl(new String[] {"--project-ttl", "0s"}));
    }

    @Test
    void emptyWorkspaceFileStartsWithoutProjects() throws Exception {
        Path file = temp.resolve("workspace.json");
        Files.writeString(file, "{\"projects\":[]}");

        try (Workspace workspace = Main.openWorkspace(
                new String[] {"--workspace", file.toString()}, Analyzers.of(List.of()))) {
            assertEquals(List.of(), workspace.projects());
        }
    }
}
