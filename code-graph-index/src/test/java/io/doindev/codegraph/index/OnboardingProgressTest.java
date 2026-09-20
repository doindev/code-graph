package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.lang.java.JavaAnalyzer;
import io.doindev.codegraph.parse.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OnboardingProgressTest {
    @TempDir Path root;
    @Test @Timeout(30) void pendingScansRemainObservableButNotQueryableInBothStores() throws Exception {
        Files.writeString(root.resolve("One.java"),"class One {}");
        for(boolean hybrid:List.of(false,true)){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var analyzers=Analyzers.of(List.of(new LanguageAnalyzer(){
                public String languageId(){return "java";}
                public Set<String> fileExtensions(){return Set.of("java");}
                public FileFragment extract(SourceFile source){
                    entered.countDown();
                    try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    return new JavaAnalyzer().extract(source);
                }
            }));
            try(var workspace=Workspace.open(List.of(),analyzers,p->CodeGraphConfig.defaults(),hybrid,32L<<20)){
                var pending=CompletableFuture.supplyAsync(()->workspace.add("test",root,analyzers,p->CodeGraphConfig.defaults()));
                try {
                    assertTrue(entered.await(10,TimeUnit.SECONDS));
                    assertTrue(workspace.projects().isEmpty());
                    var rows=workspace.onboardingStatus();assertEquals(1,rows.size());
                    assertEquals(false,rows.getFirst().get("queryable"));
                    Map<?,?> progress=(Map<?,?>)rows.getFirst().get("progress");
                    assertEquals(hybrid?"scanning_and_parsing":"parsing",progress.get("phase"));
                    assertEquals(1L,progress.get("discoveredFiles"));
                } finally {release.countDown();}
                var project=pending.get(10,TimeUnit.SECONDS);
                assertTrue(workspace.onboardingStatus().isEmpty());
                var done=project.indexer().fullIndexProgress();
                assertEquals("ready",done.get("phase"));assertEquals(1L,done.get("parsedFiles"));
                assertEquals(1L,done.get("resolvedFiles"));assertEquals(true,done.get("inventoryComplete"));
            }
        }
    }

    @Test @Timeout(20) void configurationFailureAndAdmissionLimitsReleaseAllReservations() throws Exception {
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        var analyzers=Analyzers.of(List.of());
        try(var workspace=Workspace.open(List.of(),analyzers,p->CodeGraphConfig.defaults())){
            var tasks=new ArrayList<CompletableFuture<Workspace.Project>>();
            for(int i=0;i<4;i++){
                Path directory=Files.createDirectory(root.resolve("project"+i));
                tasks.add(CompletableFuture.supplyAsync(()->workspace.add("test",directory,analyzers,p->{
                    entered.countDown();
                    try {release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}
                    throw new IllegalArgumentException("bad configuration");
                })));
            }
            try {
                assertTrue(entered.await(10,TimeUnit.SECONDS));
                assertEquals(4,workspace.onboardingStatus().size());
                Path fifth=Files.createDirectory(root.resolve("fifth"));
                assertTrue(assertThrows(IllegalStateException.class,()->workspace.add("test",fifth,analyzers,p->CodeGraphConfig.defaults())).getMessage().contains("onboarding_busy"));
            } finally {release.countDown();}
            for(var task:tasks)assertThrows(ExecutionException.class,()->task.get(10,TimeUnit.SECONDS));
            assertTrue(workspace.onboardingStatus().isEmpty());
        }
    }
}
