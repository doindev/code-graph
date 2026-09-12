package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedDirectoryTest {
    @TempDir Path root;
    @Test void existingAndFutureRootsCannotOverlapPrivateData()throws Exception{
        var analyzers=Analyzers.of(List.of());
        try(var workspace=Workspace.open(List.of(root),analyzers,p->CodeGraphConfig.defaults())){
            assertThrows(IllegalArgumentException.class,()->workspace.protectDirectory(root.resolve("dba")));
            assertThrows(IllegalArgumentException.class,()->workspace.protectDirectory(root.getParent()));
        }
        try(var workspace=Workspace.open(List.of(),analyzers,p->CodeGraphConfig.defaults())){
            Path data=root.resolve("dba");workspace.protectDirectory(data);Files.createDirectories(data);
            assertThrows(IllegalArgumentException.class,()->workspace.add("parent",root,analyzers,p->CodeGraphConfig.defaults()));
            assertThrows(IllegalArgumentException.class,()->workspace.add("same",data,analyzers,p->CodeGraphConfig.defaults()));
            Path child=Files.createDirectory(data.resolve("child"));
            assertThrows(IllegalArgumentException.class,()->workspace.add("child",child,analyzers,p->CodeGraphConfig.defaults()));
            Path other=Files.createDirectory(root.resolve("project"));assertNotNull(workspace.add("ok",other,analyzers,p->CodeGraphConfig.defaults()));
        }
    }
}
