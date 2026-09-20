package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static io.doindev.codegraph.tools.CodeNavigationTest.call;
import static org.junit.jupiter.api.Assertions.*;

class IndexFreshnessTest {
    @TempDir Path root;
    static final String HASH="ab".repeat(32);
    static Node file(String hash){
        return new Node(new FileId("A.java"),NodeKind.FILE,"A.java","A.java",new SourceSpan("A.java",1,1,1,1),
                Metrics.NONE,Map.of("indexedContentHash",hash,"contentHashAlgorithm","sha256-utf8-decoded-content"));
    }
    WorkspaceTools workspace(InMemoryCodeGraph graph){
        return CodeGraphTools.workspace(List.of(new CodeGraphTools.ProjectTools("test",graph,CodeGraphConfig.defaults(),root,_ -> {})),_ -> {});
    }
    static GraphTool tool(WorkspaceTools registry,String name){return registry.tools().stream().filter(t->t.spec().name().equals(name)).findFirst().orElseThrow();}
    static ObjectNode args(){return ToolSupport.JSON.createObjectNode().put("project","test");}
    static JsonNode result(GraphTool tool,ObjectNode args)throws Exception{return call(tool,args.toString());}

    @Test void waitsForExactHashAndPublishedGenerationWithoutBlockingWriter()throws Exception{
        var graph=new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1,List.of(),List.of(file("cd".repeat(32))),List.of(),List.of()));
        try(var registry=workspace(graph);var executor=Executors.newVirtualThreadPerTaskExecutor()){
            GraphTool status=tool(registry,"index_status");String instance=result(status,args()).path("indexInstanceId").asText();
            var input=args().put("waitMillis",3000).put("minGeneration",2).put("indexInstanceId",instance);
            input.putArray("files").addObject().put("path","A.java").put("sha256",HASH);
            Future<JsonNode> waited=executor.submit(()->result(status,input));
            Thread.sleep(75);
            var write=executor.submit(()->graph.apply(new GraphDelta(2,List.of(new FileId("A.java")),List.of(file(HASH)),List.of(),List.of())));
            write.get(1,TimeUnit.SECONDS);
            var out=waited.get(1,TimeUnit.SECONDS);
            assertTrue(out.path("freshnessSatisfied").asBoolean());assertFalse(out.path("timedOut").asBoolean());
            assertEquals(2,out.path("generation").asInt());assertTrue(out.path("fileRevisions").get(0).path("matched").asBoolean());
            var query=args().put("query","A").put("expectedGeneration",2).put("expectedIndexInstance",instance);
            assertFalse(tool(registry,"search_symbols").call(query).error());
            graph.apply(new GraphDelta(3,List.of(),List.of(),List.of(),List.of()));
            assertTrue(tool(registry,"search_symbols").call(query).json().contains("stale_generation"));
        }
    }
    @Test void timeoutsCancellationInvalidArgumentsAndReplacementAreExplicit()throws Exception{
        var graph=new InMemoryCodeGraph();
        try(var registry=workspace(graph)){
            var status=tool(registry,"index_status");String instance=result(status,args()).path("indexInstanceId").asText();
            var input=args().put("minGeneration",99).put("indexInstanceId",instance).put("waitMillis",30);
            var timed=result(status,input);assertTrue(timed.path("timedOut").asBoolean());assertFalse(timed.path("freshnessSatisfied").asBoolean());
            assertTrue(status.call(args().put("waitMillis",5001)).error());
            assertTrue(status.call(args().put("waitMillis","5")).error());
            assertTrue(status.call(args().put("minGeneration",99)).error());
            var bad=args();bad.putArray("files").addObject().put("path","../A.java").put("sha256",HASH);assertTrue(status.call(bad).error());
            Thread.currentThread().interrupt();
            try{assertTrue(status.call(input).json().contains("cancelled"));}finally{Thread.interrupted();}
            registry.removeProject("test");
            registry.addProject(new CodeGraphTools.ProjectTools("test",graph,CodeGraphConfig.defaults(),root,_ -> {}));
            assertTrue(status.call(input).json().contains("stale_index_instance"));
            assertTrue(tool(registry,"search_symbols").call(args().put("query","A").put("expectedGeneration",0).put("expectedIndexInstance",instance)).error());
        }
    }
    @Test void indexAbsenceIsNotDeletionWhenSourceStillExists()throws Exception{
        var graph=new InMemoryCodeGraph();Files.writeString(root.resolve("A.java"),"malformed source");
        try(var registry=workspace(graph)){
            var input=args();input.putArray("files").addObject().put("path","A.java").put("deleted",true);
            assertFalse(result(tool(registry,"index_status"),input).path("freshnessSatisfied").asBoolean());
            Files.delete(root.resolve("A.java"));
            assertTrue(result(tool(registry,"index_status"),input).path("freshnessSatisfied").asBoolean());
            graph.apply(new GraphDelta(1,List.of(),List.of(file(HASH)),List.of(),List.of()));
            assertFalse(result(tool(registry,"index_status"),input).path("freshnessSatisfied").asBoolean(),"stale indexed file is not removed yet");
        }
    }
    @Test void waitsNeverRenewOrPinProjectLifetime()throws Exception{
        var time=new AtomicLong();var graph=new InMemoryCodeGraph();
        var project=new CodeGraphTools.ProjectTools("test",graph,CodeGraphConfig.defaults(),root,_ -> {});
        try(var registry=new WorkspaceTools(List.of(project),_ -> {},time::get,()->Instant.EPOCH.plusNanos(time.get()));
            var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var status=tool(registry,"index_status");String instance=result(status,args()).path("indexInstanceId").asText();
            Future<ToolResponse> wait=executor.submit(()->status.call(args().put("minGeneration",999).put("indexInstanceId",instance).put("waitMillis",100)));
            Thread.sleep(25);time.set(Duration.ofHours(2).toNanos());
            assertEquals(0,registry.lifecycle().status("test").activeOperations());
            assertEquals(Instant.EPOCH,registry.lifecycle().status("test").lastActivityAt());
            assertEquals(1,registry.lifecycle().expireIdle());
            assertTrue(wait.get(1,TimeUnit.SECONDS).json().contains("stale_index_instance"));
        }
    }
    @Test void generationPreconditionsAreAdvertisedAndRequireInstance()throws Exception{
        try(var registry=workspace(new InMemoryCodeGraph())){
            var query=tool(registry,"search_symbols");
            assertTrue(query.call(args().put("query","x").put("expectedGeneration",0)).error());
            var schema=ToolSupport.JSON.readTree(query.spec().inputSchemaJson());
            assertTrue(schema.path("properties").has("expectedGeneration"));
            var statusSchema=ToolSupport.JSON.readTree(tool(registry,"index_status").spec().inputSchemaJson());
            assertEquals(5000,statusSchema.path("properties").path("waitMillis").path("maximum").asInt());
        }
    }
}

