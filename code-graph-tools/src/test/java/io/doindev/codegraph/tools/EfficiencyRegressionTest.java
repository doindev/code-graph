package io.doindev.codegraph.tools;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EfficiencyRegressionTest {
    private static Node node(int n, String signature) {
        var id = new SymbolId("java", "Test.java", String.format(Locale.ROOT, "f%03d", n), 0);
        return new Node(id, NodeKind.FUNCTION, id.qualifiedName(), signature,
                new SourceSpan("Test.java", n + 1, 1, n + 2, 20), Metrics.NONE, Map.of());
    }
    private static CodeGraphConfig config(int bytes) {
        return new CodeGraphConfig(null,null,new CodeGraphConfig.Limits(100,bytes),null,
                new CodeGraphConfig.Gating(100,false,List.of()),null,null,null).withDefaults();
    }
    @Test void repeatedOccurrencesDoNotConsumeDistinctNeighborCapacity() throws Exception {
        var nodes = List.of(node(0,"root"), node(1,"one"), node(2,"two"), node(3,"three"),node(4,"four"));
        var edges = new ArrayList<Edge>();
        for (int i=0;i<25;i++) edges.add(new Edge(nodes.get(1).id(),nodes.get(0).id(),EdgeKind.CALLS,1,Map.of("site",""+i)));
        for (int i=2;i<5;i++) edges.add(new Edge(nodes.get(i).id(),nodes.get(0).id(),EdgeKind.CALLS));
        var graph = new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1,List.of(),nodes,edges,List.of()));
        var tool = new GetCallGraphTool(graph,config(32768));
        var result = tool.call(ToolSupport.JSON.createObjectNode().put("function",nodes.getFirst().id().value()).put("direction","up").put("depth",1));
        assertFalse(result.error(),result.json());
        var json = ToolSupport.JSON.readTree(result.json());
        assertEquals(4,json.path("up").size());
        assertEquals(5,json.path("nodes").size());
        assertEquals(25,json.path("upOccurrences").get(0).asInt());
        assertFalse(json.path("truncated").asBoolean());
        var references = new CodeNavigationTool(graph,config(32768),CodeNavigationTool.Operation.REFERENCES);
        assertEquals(28,ToolSupport.JSON.readTree(references.call(ToolSupport.JSON.createObjectNode().put("symbol_id",nodes.getFirst().id().value())).json()).path("total").asInt());
    }
    @Test void automaticBytePagesPreserveAllUnicodeRecordsWithoutSmallerLimitRetries() throws Exception {
        var graph = new InMemoryCodeGraph();
        var nodes = new ArrayList<Node>();
        for(int i=0;i<35;i++) nodes.add(node(i,"漢字🙂 \\\\\"".repeat(14)));
        graph.apply(new GraphDelta(1,List.of(),nodes,List.of(),List.of()));
        for (GraphTool tool : List.of(new SearchSymbolsTool(graph,config(2048)),
                new CodeNavigationTool(graph,config(2048),CodeNavigationTool.Operation.OUTLINE))) {
            var args = ToolSupport.JSON.createObjectNode().put("query","f").put("file","Test.java").put("limit",100);
            var ids = new HashSet<String>(); int pages=0;
            while (true) {
                var result=tool.call(args);
                assertFalse(result.error(),result.json());
                assertTrue(result.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=2048);
                var page=ToolSupport.JSON.readTree(result.json());
                assertEquals(100,page.path("requestedCount").asInt());
                assertTrue(page.path("returnedCount").asInt()>0);
                for(var row:page.path("symbols")) assertTrue(ids.add(row.path("id").asText()));
                assertTrue(++pages<100,"No empty continuation loops");
                if(!page.has("nextCursor"))break;
                args.put("cursor",page.path("nextCursor").asText());
            }
            assertEquals(35,ids.size()); assertTrue(pages>1);
        }
    }
    @Test void oversizedFirstRecordIsNeverSkipped() {
        var graph=new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1,List.of(),List.of(node(0,"X".repeat(6000)),node(1,"small")),List.of(),List.of()));
        var result=new SearchSymbolsTool(graph,config(1024)).call(ToolSupport.JSON.createObjectNode().put("query","f"));
        assertTrue(result.error());assertTrue(result.json().contains("item_too_large"),result.json());
    }
    @Test void allNavigationOperationsAutomaticallyPage() throws Exception {
        var graph=new InMemoryCodeGraph();var nodes=new ArrayList<Node>();var edges=new ArrayList<Edge>();
        var target=new Node(new SymbolId("java","Test.java","Root",0),NodeKind.TYPE,"Root","Root",
                new SourceSpan("Test.java",1,1,100,80),Metrics.NONE,Map.of());
        nodes.add(target);
        for(int i=0;i<35;i++) {
            var original=node(i,"列🙂".repeat(25));
            var child=new Node(original.id(),NodeKind.TYPE,original.name(),original.displaySignature(),
                    new SourceSpan("Test.java",1,1,100,80),Metrics.NONE,Map.of());
            nodes.add(child);edges.add(new Edge(child.id(),target.id(),EdgeKind.IMPLEMENTS));
            edges.add(new Edge(child.id(),target.id(),EdgeKind.REFERENCES));
        }
        graph.apply(new GraphDelta(1,List.of(),nodes,edges,List.of()));
        for(var operation:List.of(CodeNavigationTool.Operation.POSITION,CodeNavigationTool.Operation.REFERENCES,CodeNavigationTool.Operation.IMPLEMENTATIONS)) {
            var tool=new CodeNavigationTool(graph,config(3072),operation);
            var args=ToolSupport.JSON.createObjectNode().put("symbol_id",target.id().value()).put("file","Test.java").put("line",50).put("limit",100);
            var ids=new HashSet<String>();int pages=0;
            while(true) {
                var response=tool.call(args);assertFalse(response.error(),response.json());
                assertTrue(response.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=3072);
                var page=ToolSupport.JSON.readTree(response.json());
                assertTrue(++pages<100);
                for(var row:page.path("symbols"))assertTrue(ids.add(row.path("id").asText()));
                if(!page.has("nextCursor"))break;
                args.put("cursor",page.path("nextCursor").asText());
            }
            assertEquals(operation==CodeNavigationTool.Operation.POSITION?36:35,ids.size());
        }
    }
    @Test void nodeCapDoesNotSuppressLinksBetweenAlreadyIncludedNodes()throws Exception {
        var graph=new InMemoryCodeGraph();var nodes=new ArrayList<Node>();var edges=new ArrayList<Edge>();
        for(int i=0;i<101;i++)nodes.add(node(i,"n"+i));
        for(int i=1;i<=20;i++)edges.add(new Edge(nodes.get(0).id(),nodes.get(i).id(),EdgeKind.CALLS));
        for(int i=21;i<101;i++) {
            edges.add(new Edge(nodes.get(1+(i-21)/4).id(),nodes.get(i).id(),EdgeKind.CALLS));
            edges.add(new Edge(nodes.get(i).id(),nodes.get(0).id(),EdgeKind.CALLS));
        }
        graph.apply(new GraphDelta(1,List.of(),nodes,edges,List.of()));
        var response=new GetCallGraphTool(graph,config(65536)).call(ToolSupport.JSON.createObjectNode()
                .put("function",nodes.getFirst().id().value()).put("direction","down").put("depth",3));
        var data=ToolSupport.JSON.readTree(response.json());
        assertEquals(100,data.path("nodes").size());
        assertTrue(data.path("truncated").asBoolean());
        int backLinks=0;for(var pair:data.path("down"))if(pair.get(1).asInt()==0)backLinks++;
        assertEquals(79,backLinks);
    }
    @Test void selfCallsAndCyclesSurviveAggregationInBothDirections() throws Exception {
        var a=node(0,"a");var b=node(1,"b");var graph=new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1,List.of(),List.of(a,b),List.of(new Edge(a.id(),a.id(),EdgeKind.CALLS),
                new Edge(a.id(),b.id(),EdgeKind.CALLS),new Edge(b.id(),a.id(),EdgeKind.CALLS)),List.of()));
        var result=new GetCallGraphTool(graph,config(32768)).call(ToolSupport.JSON.createObjectNode().put("function",a.id().value()));
        var json=ToolSupport.JSON.readTree(result.json());
        assertEquals(3,json.path("up").size());assertEquals(3,json.path("down").size());
        assertEquals(2,json.path("nodes").size());
    }
    @Test void workCapReportsLowerBoundAndCancellationRemainsAnError() throws Exception {
        var a=node(0,"a");var graph=new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1,List.of(),List.of(a),List.of(),List.of()));
        var streamed=(io.doindev.codegraph.query.GraphQuery)java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),new Class[]{io.doindev.codegraph.query.GraphQuery.class},(proxy,method,arguments)->{
                    if(method.getName().equals("scanEdges")){
                        @SuppressWarnings("unchecked") var visitor=(java.util.function.Consumer<Edge>)arguments[3];
                        for(int i=0;i<100_001;i++)visitor.accept(new Edge(a.id(),a.id(),EdgeKind.CALLS));
                        return null;
                    }
                    if(method.getName().equals("edges"))throw new AssertionError("Must stream");
                    try { return method.invoke(graph,arguments); }
                    catch(java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        var tool=new GetCallGraphTool(streamed,config(32768));
        var args=ToolSupport.JSON.createObjectNode().put("function",a.id().value()).put("direction","down");
        var json=ToolSupport.JSON.readTree(tool.call(args).json());
        assertEquals(100000,json.path("edgeVisits").asInt());
        assertTrue(json.path("workLimitReached").asBoolean());
        assertEquals("lower_bound",json.path("countCompleteness").asText());
        var gated=new GetCallGraphTool(streamed,CodeGraphConfig.defaults());
        var withRisk=ToolSupport.JSON.readTree(gated.call(args).json());
        assertEquals(100000,withRisk.path("edgeVisits").asInt());
        assertEquals("unavailable_work_limit",withRisk.path("risk").path("completeness").asText());
        Thread.currentThread().interrupt();
        try {assertTrue(tool.call(args).error());} finally {Thread.interrupted();}
    }
}
