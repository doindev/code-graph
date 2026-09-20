package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.query.GraphQuery;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static io.doindev.codegraph.tools.CodeNavigationTest.*;
import static org.junit.jupiter.api.Assertions.*;

class SymbolContextToolTest {
    static final String A="java:src/Test.java#Base.a/0", B="java:src/Test.java#Base.b/0";
    static CodeGraphConfig config(int bytes) {
        return new CodeGraphConfig(null,null,new CodeGraphConfig.Limits(100,bytes),null,
                new CodeGraphConfig.Gating(70,false,List.of()),null,null,null).withDefaults();
    }
    static ObjectNode args(String... ids) {
        ObjectNode out=ToolSupport.JSON.createObjectNode();var array=out.putArray("symbol_ids");
        for(String id:ids)array.add(id);return out;
    }
    static JsonNode result(GraphTool tool, ObjectNode args) throws Exception {return call(tool,args.toString());}
    @Test void defaultsAreDeclarationsWithIndependentTargetErrors() throws Exception {
        var query=new SymbolContextTool(graph(),config(32768));
        var page=result(query,args(A,B,"not-a-symbol","java:missing.java#Missing/0"));
        assertEquals(4,page.path("items").size());
        assertEquals("declaration",page.path("items").get(0).path("section").asText());
        assertEquals("declaration",page.path("items").get(1).path("section").asText());
        assertEquals("invalid_symbol",page.path("items").get(2).path("code").asText());
        assertEquals("unknown_symbol",page.path("items").get(3).path("code").asText());
        assertEquals(0,page.path("edgeVisits").asInt());
        assertFalse(page.path("inventoryComplete").asBoolean());
        assertFalse(query.call(args(A).put("minConfidence","bogus")).json().isEmpty());
        assertTrue(query.call(args(A).put("minConfidence","bogus")).error());
        assertTrue(query.call(args(A,A)).error());
    }
    @Test void referencesMatchTheSingleToolAndCallersAggregateSites() throws Exception {
        var graph=graph();var a=node("Base.a",NodeKind.FUNCTION,4,8);var b=node("Base.b",NodeKind.FUNCTION,10,14);
        graph.apply(new GraphDelta(2,List.of(),List.of(),List.of(new Edge(b.id(),a.id(),EdgeKind.CALLS,.9f,
                Map.of("referencePath","src/Test.java","referencePrecision","reference_expression",
                        "referenceStartLine","13","referenceStartColumn","2","referenceEndLine","13","referenceEndColumn","5"))),List.of()));
        var query=new SymbolContextTool(graph,config(32768));var input=args(A).put("minConfidence",.8);
        input.putArray("include").add("references").add("callers");
        var page=result(query,input);var refs=new HashSet<JsonNode>();
        for(var row:page.path("items"))if(row.path("section").asText().equals("references")){
            ObjectNode copy=row.deepCopy();copy.remove("section");refs.add(copy);
        } else {
            assertEquals(2,row.path("occurrences").asInt());assertTrue(row.path("countsComplete").asBoolean());
            assertEquals(B,row.path("id").asText());
        }
        var standalone=call(tool(graph,"find_references"),"{\"symbol_id\":\""+A+"\",\"minConfidence\":0.8}");
        assertEquals(new HashSet<>(ToolSupport.JSON.convertValue(standalone.path("symbols"),new com.fasterxml.jackson.core.type.TypeReference<List<JsonNode>>(){})),refs);
    }
    @Test void pagesHaveNoGapsOrDuplicatesAcrossTargetsSectionsAndUnicodeByteLimits() throws Exception {
        for(String detail:List.of("full","locations")) {
        var graph=graph();var nodes=new ArrayList<Node>();var edges=new ArrayList<Edge>();
        for(int i=0;i<35;i++){
            var n=node("Caller"+String.format("%02d",i)+"雪".repeat(15),NodeKind.FUNCTION,80+i,80+i);
            nodes.add(n);edges.add(new Edge(n.id(),new SymbolId("java","src/Test.java","Base.a",0),EdgeKind.CALLS,1,Map.of("site",""+(80+i))));
        }
        graph.apply(new GraphDelta(2,List.of(),nodes,edges,List.of()));
        var input=args(A,B).put("detail",detail);input.putArray("include").add("declaration").add("references").add("callers").add("callees");
        var expected=result(new SymbolContextTool(graph,config(100_000)),input.deepCopy().put("limit",100)).path("items");
        var query=new SymbolContextTool(graph,config(3000));var collected=new ArrayList<JsonNode>();input.put("limit",7);
        for(int i=0;i<100;i++){
            var response=query.call(input);assertFalse(response.error(),response.json());
            assertTrue(response.json().getBytes(StandardCharsets.UTF_8).length<=3000);
            var page=ToolSupport.JSON.readTree(response.json());
            page.path("items").forEach(collected::add);
            if(!page.has("nextCursor"))break;
            assertFalse(page.path("items").isEmpty());input.put("cursor",page.path("nextCursor").asText());
            assertTrue(i<99,"continuation loop");
        }
        assertEquals(expected.size(),collected.size());assertEquals(new HashSet<>(collected).size(),collected.size());
        for(int i=0;i<expected.size();i++)assertEquals(expected.get(i),collected.get(i),"record "+i);
        }
    }
    @Test void cursorsRejectChangedTargetsTamperingAndGenerations() throws Exception {
        var graph=graph();var query=new SymbolContextTool(graph,config(32768));var input=args(A,B).put("limit",1);
        var first=result(query,input);input.put("cursor",first.path("nextCursor").asText());
        assertTrue(query.call(args(B,A).put("cursor",input.path("cursor").asText())).error());
        assertTrue(query.call(args(A,B).put("cursor",input.path("cursor").asText()+"bad")).error());
        graph.apply(new GraphDelta(2,List.of(),List.of(),List.of(),List.of()));
        assertTrue(query.call(input).json().contains("stale_cursor"));
    }
    @Test void oversizedRecordIsNotSilentlySkipped() {
        var graph=graph();var giant=node("雪".repeat(3500),NodeKind.FUNCTION,1,1);
        graph.apply(new GraphDelta(2,List.of(),List.of(giant),List.of(),List.of()));
        var result=new SymbolContextTool(graph,config(1024)).call(args(giant.id().value(),A));
        assertTrue(result.error());assertTrue(result.json().contains("item_too_large"));
    }
    @Test void sharedWorkCapUsesStreamingAndCancellationNotMaterializedAdjacency() throws Exception {
        var source=graph();AtomicInteger visits=new AtomicInteger();
        GraphQuery query=(GraphQuery)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GraphQuery.class},(proxy,method,values)->{
            if(method.getName().equals("read"))return ((java.util.function.Supplier<?>)values[0]).get();
            if(Set.of("edges","allNodes").contains(method.getName()))throw new AssertionError("unbounded materialization");
            if(method.getName().equals("scanEdges")){
                @SuppressWarnings("unchecked") Consumer<Edge> visitor=(Consumer<Edge>)values[3];
                var edge=new Edge(new SymbolId("java","src/Test.java","Base.b",0),new SymbolId("java","src/Test.java","Base.a",0),EdgeKind.CALLS,1,Map.of());
                for(int i=0;i<100_010;i++){visits.incrementAndGet();visitor.accept(edge);}return null;
            }
            return method.invoke(source,values);
        });
        var input=args(A);input.putArray("include").add("callers").add("callees");
        var tool=new SymbolContextTool(query,config(32768));var page=result(tool,input);
        assertEquals(100_000,page.path("edgeVisits").asInt());
        assertTrue(page.path("workLimitReached").asBoolean());assertTrue(page.path("truncated").asBoolean());
        assertEquals(100_000,page.path("items").get(0).path("occurrences").asInt());
        assertFalse(page.path("items").get(0).path("countsComplete").asBoolean());
        assertFalse(page.path("totalCountComplete").asBoolean());
        assertTrue(visits.get()<=100_001);
        Thread.currentThread().interrupt();
        try{assertTrue(tool.call(input).json().contains("cancelled"));}finally{Thread.interrupted();}
    }
    @Test void workspaceRequiresExplicitProjectWithoutChangingLegacyDefaults() throws Exception {
        try(var workspace=CodeGraphTools.workspace(List.of(new CodeGraphTools.ProjectTools("test",graph(),config(32768),null,_ -> {})),_ -> {})){
            var tool=workspace.tools().stream().filter(t->t.spec().name().equals("get_symbol_context")).findFirst().orElseThrow();
            assertTrue(tool.call(args(A)).error());assertFalse(tool.call(args(A).put("project","test")).error());
            assertTrue(ToolSupport.JSON.readTree(tool.spec().inputSchemaJson()).path("required").toString().contains("project"));
        }
    }

    @Test void locationsProjectionPreservesEveryEvidenceFieldAndScopesCursors() throws Exception {
        var tool=new SymbolContextTool(graph(),config(32768));
        var input=args(A,B);input.putArray("include").add("declaration").add("references").add("callers").add("callees");
        var full=result(tool,input);
        var projected=result(tool,input.deepCopy().put("detail","locations"));
        assertEquals(full.path("items").size(),projected.path("items").size());
        for(int i=0;i<full.path("items").size();i++){
            ObjectNode expected=full.path("items").get(i).deepCopy();
            expected.remove(List.of("name","kind","signature"));
            assertEquals(expected,projected.path("items").get(i));
        }
        assertTrue(projected.toString().length()<full.toString().length());
        var cursor=result(tool,input.deepCopy().put("limit",1)).path("nextCursor").asText();
        assertTrue(tool.call(input.deepCopy().put("detail","locations").put("cursor",cursor)).error());
        assertTrue(tool.call(input.deepCopy().put("detail","unknown")).error());
    }

    @Test void repeatedOccurrencesReuseNodesOnlyWithinOneRequest() throws Exception {
        var source=graph();var reads=new AtomicInteger();
        GraphQuery query=(GraphQuery)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{GraphQuery.class},(proxy,method,values)->{
            if(method.getName().equals("read"))return ((java.util.function.Supplier<?>)values[0]).get();
            if(method.getName().equals("node"))reads.incrementAndGet();
            return method.invoke(source,values);
        });
        var input=args(A,B);input.putArray("include").add("declaration").add("references").add("callers").add("callees");
        var tool=new SymbolContextTool(query,config(32768));
        result(tool,input);assertEquals(3,reads.get()); // A, B and the Child caller, once each.
        result(tool,input);assertEquals(6,reads.get(),"No cross-request cache may serve a stale generation");
    }
}
