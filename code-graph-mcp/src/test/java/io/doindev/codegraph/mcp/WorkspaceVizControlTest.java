package io.doindev.codegraph.mcp;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.Analyzers;
import io.doindev.codegraph.index.Workspace;
import io.doindev.codegraph.tools.CodeGraphTools;
import io.doindev.codegraph.tools.WorkspaceTools;
import io.doindev.codegraph.viz.VizControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceVizControlTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(10)
    void cancellingTheFirstProjectLeavesAnEmptyWorkspace() throws Exception {
        Analyzers analyzers = Analyzers.of(List.of());
        try (Workspace workspace = Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults())) {
            workspace.watchAll();
            WorkspaceTools registry = CodeGraphTools.workspace(List.of(), workspace::remove);
            WorkspaceVizControl control = new WorkspaceVizControl(workspace, registry, analyzers,
                    "stdio", true);
            VizControl.AddJob job = control.startAdd(temp.toString());
            assertTrue(control.cancelAdd(job.id()));
            assertEquals("cancelled", awaitTerminal(control, job.id()).state());
            assertTrue(workspace.projects().isEmpty());
            assertTrue(registry.projectNames().isEmpty());
        }
    }

    @Test
    @Timeout(10)
    void failedOnboardingDoesNotLeaveAGhostProject() throws Exception {
        Files.writeString(temp.resolve("code-graph.json"), "{invalid-json");
        Analyzers analyzers = Analyzers.of(List.of());
        try (Workspace workspace = Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults())) {
            workspace.watchAll();
            WorkspaceTools registry = CodeGraphTools.workspace(List.of(), workspace::remove);
            WorkspaceVizControl control = new WorkspaceVizControl(workspace, registry, analyzers,
                    "stdio", true);
            VizControl.AddJob job = control.startAdd(temp.toString());
            assertEquals("error", awaitTerminal(control, job.id()).state());
            assertTrue(workspace.projects().isEmpty());
            assertTrue(registry.projectNames().isEmpty());
        }
    }

    @Test
    @Timeout(10)
    void uiOnboardingRejectsChildrenOfMcpOnboardedProjects() throws Exception {
        Path child = Files.createDirectory(temp.resolve("child"));
        Analyzers analyzers = Analyzers.of(List.of());
        try (Workspace workspace = Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults())) {
            workspace.watchAll();
            WorkspaceTools registry = CodeGraphTools.workspace(List.of(), workspace::remove);
            new ProjectOnboarding(workspace, registry, analyzers).add(temp.toString());
            WorkspaceVizControl control = new WorkspaceVizControl(workspace, registry, analyzers,
                    "stdio", true);
            VizControl.AddJob job = control.startAdd(child.toString());
            VizControl.AddJob result = awaitTerminal(control, job.id());
            assertEquals("error", result.state());
            assertTrue(result.error().contains("child path"));
            assertEquals(1, workspace.projects().size());
            assertEquals(1, registry.projectNames().size());
        }
    }

    private static VizControl.AddJob awaitTerminal(WorkspaceVizControl control, String id)
            throws InterruptedException {
        VizControl.AddJob result;
        do {
            Thread.sleep(10);
            result = control.addStatus(id);
        } while (result.state().equals("indexing"));
        return result;
    }

    @Test @Timeout(20)
    void uiAndMcpObserveTheSamePendingScanWithoutPublishingIt() throws Exception {
        Files.writeString(temp.resolve("Hello.java"),"class Hello {}");
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var analyzer=new io.doindev.codegraph.parse.LanguageAnalyzer(){
            public String languageId(){return "java";}
            public java.util.Set<String> fileExtensions(){return java.util.Set.of("java");}
            public io.doindev.codegraph.parse.FileFragment extract(io.doindev.codegraph.parse.SourceFile source){
                entered.countDown();
                try {if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("timeout");}
                catch(InterruptedException e){throw new IllegalStateException(e);}
                return new io.doindev.codegraph.lang.java.JavaAnalyzer().extract(source);
            }
        };
        var analyzers=Analyzers.of(List.of(analyzer));
        try(var workspace=Workspace.open(List.of(),analyzers,p->CodeGraphConfig.defaults());
            var registry=CodeGraphTools.workspace(List.of(),workspace::remove)){
            var control=new WorkspaceVizControl(workspace,registry,analyzers,"stdio",true);
            var job=control.startAdd(temp.toString());
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var roster=registry.tools().stream().filter(t->t.spec().name().equals("list_projects")).findFirst().orElseThrow();
            try {
                assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
                var status=json.readTree(roster.call(json.createObjectNode()).json());
                assertEquals(0,status.path("projects").size());assertEquals(1,status.path("onboarding").size());
                assertEquals("parsing",status.path("onboarding").get(0).path("progress").path("phase").asText());
                assertEquals("parsing",control.addStatus(job.id()).progress().get("phase"));
            } finally {release.countDown();}
            var ready=awaitTerminal(control,job.id());
            assertEquals("ready",ready.state());assertEquals("ready",ready.progress().get("phase"));
            assertEquals(0,json.readTree(roster.call(json.createObjectNode()).json()).path("onboarding").size());
            registry.removeProject(ready.name());
            // Retained job snapshots must remain readable without retaining an active graph/indexer.
            assertEquals("ready",ready.progress().get("phase"));
        }
    }

    @Test
    @Timeout(20)
    void explicitUiAndMcpReindexJobsHoldTheirLeaseUntilTheScanFinishes() throws Exception {
        Files.writeString(temp.resolve("Hello.java"), "class Hello {}\n");
        for (boolean ui : List.of(true, false)) {
            var block = new java.util.concurrent.atomic.AtomicBoolean();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var javaAnalyzer = new io.doindev.codegraph.lang.java.JavaAnalyzer();
            var blocking = new io.doindev.codegraph.parse.LanguageAnalyzer() {
                public String languageId() { return "java"; }
                public java.util.Set<String> fileExtensions() { return java.util.Set.of("java"); }
                public io.doindev.codegraph.parse.FileFragment extract(io.doindev.codegraph.parse.SourceFile file) {
                    if (block.get()) {
                        entered.countDown();
                        try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    }
                    return javaAnalyzer.extract(file);
                }
            };
            Analyzers analyzers = Analyzers.of(List.of(blocking));
            try (Workspace workspace = Workspace.open(List.of(), analyzers, p -> CodeGraphConfig.defaults());
                 WorkspaceTools registry = CodeGraphTools.workspace(List.of(), workspace::remove)) {
                String name = new ProjectOnboarding(workspace, registry, analyzers).add(temp.toString());
                WorkspaceVizControl control = new WorkspaceVizControl(workspace, registry, analyzers, "stdio", true);
                block.set(true);
                try {
                    if (ui) {
                        control.startReindex(name);
                    } else {
                        var tool = registry.tools().stream().filter(t -> t.spec().name().equals("reindex")).findFirst().orElseThrow();
                        var response = tool.call(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("project", name));
                        org.junit.jupiter.api.Assertions.assertFalse(response.error());
                    }
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                    registry.lifecycle().setTtl(java.time.Duration.ofNanos(1));
                    assertEquals(0, registry.lifecycle().expireIdle());
                    assertTrue(registry.lifecycle().status(name).activeOperations() > 0);
                } finally {
                    registry.lifecycle().setTtl(java.time.Duration.ofHours(1));
                    release.countDown();
                }
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
                while (registry.lifecycle().status(name).activeOperations() != 0 && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, registry.lifecycle().status(name).activeOperations());
                registry.lifecycle().setTtl(java.time.Duration.ofNanos(1));
                assertEquals(1, registry.lifecycle().expireIdle());
                assertTrue(workspace.projects().isEmpty());
            }
        }
    }
}
