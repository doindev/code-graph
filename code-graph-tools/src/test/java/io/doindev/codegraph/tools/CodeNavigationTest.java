package io.doindev.codegraph.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CodeNavigationTest {
    @Test void referenceEvidenceAndCoverageAreExplicit()throws Exception {
        var graph=graph();
        var exact=new Edge(new SymbolId("java","src/Test.java","Base.b",0),
                new SymbolId("java","src/Test.java","Base.a",0),EdgeKind.CALLS,1f,
                Map.of("resolution","java-bound","resolutionStatus","resolved","dispatch","static-binding","candidateCount","1","omittedCandidates","0"));
        graph.apply(new GraphDelta(2,List.of(),List.of(),List.of(exact),List.of()));
        var response=call(tool(graph,"find_references"),"{\"symbol_id\":\"java:src/Test.java#Base.a/0\",\"limit\":20}");
        assertEquals("not_guaranteed",response.path("referenceCompleteness").asText());
        assertFalse(response.path("inventoryComplete").asBoolean());
        boolean found=false;
        for(var row:response.path("symbols"))if(row.path("resolutionEvidence").path("resolutionStatus").asText().equals("resolved")){
            found=true;assertEquals("static-binding",row.path("resolutionEvidence").path("dispatch").asText());
        }
        assertTrue(found);
    }
    static Node node(String name,NodeKind kind,int start,int end){
        return new Node(new SymbolId("java","src/Test.java",name,0),kind,name,name,
                new SourceSpan("src/Test.java",start,1,end,20),Metrics.NONE,Map.of());
    }
    static InMemoryCodeGraph graph(){
        var graph=new InMemoryCodeGraph();
        var base=node("Base",NodeKind.TYPE,1,40);var child=node("Child",NodeKind.TYPE,42,60);
        var a=node("Base.a",NodeKind.FUNCTION,4,8);var b=node("Base.b",NodeKind.FUNCTION,10,14);
        graph.apply(new GraphDelta(1,List.of(),List.of(base,child,a,b),List.of(
                new Edge(child.id(),base.id(),EdgeKind.EXTENDS,.95f,Map.of()),
                new Edge(b.id(),a.id(),EdgeKind.CALLS,.95f,Map.of("site","12")),
                new Edge(child.id(),a.id(),EdgeKind.CALLS,.25f,Map.of())),List.of()));
        return graph;
    }
    static GraphTool tool(InMemoryCodeGraph graph,String name){return CodeGraphTools.standard(graph,CodeGraphConfig.defaults(),_ -> {}).stream().filter(t->t.spec().name().equals(name)).findFirst().orElseThrow();}
    static JsonNode call(GraphTool tool,String args)throws Exception{
        var result=tool.call(ToolSupport.JSON.readTree(args));assertFalse(result.error(),result.json());return ToolSupport.JSON.readTree(result.json());
    }
    @Test void outlinePagesStableDeclarationsAndRejectsStaleOrForeignCursors()throws Exception{
        var graph=graph();var tool=tool(graph,"get_file_outline");
        var args=ToolSupport.JSON.createObjectNode().put("file","src/Test.java").put("limit",1);
        var ids=new HashSet<String>();
        for(int i=0;i<4;i++){
            var page=call(tool,args.toString());assertEquals(4,page.path("total").asInt());
            assertTrue(ids.add(page.path("symbols").get(0).path("id").asText()));
            if(i<3)args.put("cursor",page.path("nextCursor").asText());else assertFalse(page.has("nextCursor"));
        }
        args.put("file","elsewhere.java");assertTrue(tool.call(args).error());
        args.put("file","src/Test.java");graph.apply(new GraphDelta(2,List.of(),List.of(),List.of(),List.of()));
        assertTrue(tool.call(args).json().contains("stale_cursor"));
        assertTrue(tool.call(ToolSupport.JSON.createObjectNode().put("file","../escape.java")).error());
    }
    @Test void positionReportsContainingCandidatesNotFabricatedIdentifierResolution()throws Exception{
        var result=call(tool(graph(),"resolve_symbol_at_position"),"{\"file\":\"src/Test.java\",\"line\":6,\"column\":2}");
        assertEquals(2,result.path("symbols").size());assertFalse(result.path("exactIdentifierResolved").asBoolean());
        assertEquals("containing_symbol_only",result.path("resolution").asText());
    }
    @Test void referencesPreserveConfidenceAndDistinguishLegacyLineFromContainingLocation()throws Exception{
        var result=call(tool(graph(),"find_references"),"{\"symbol_id\":\"java:src/Test.java#Base.a/0\",\"minConfidence\":0.8}");
        assertEquals(1,result.path("symbols").size());var ref=result.path("symbols").get(0);
        assertEquals("calls",ref.path("relationship").asText());assertEquals("line_only",ref.path("locationPrecision").asText());
        assertEquals(12,ref.path("occurrence").path("line").asInt());assertFalse(result.path("inventoryComplete").asBoolean());
    }
    @Test void implementationsOnlyReturnVerifiedDirectTypeEdges()throws Exception{
        var tool=tool(graph(),"find_implementations");
        var result=call(tool,"{\"symbol_id\":\"java:src/Test.java#Base/0\"}");
        assertEquals(1,result.path("symbols").size());assertEquals("Child",result.path("symbols").get(0).path("name").asText());
        assertTrue(tool.call(ToolSupport.JSON.createObjectNode().put("symbol_id","java:src/Test.java#Base.a/0")).error());
    }
    @Test void positionCanResolveExpressionTargetsWithoutClaimingExactTokenResolution()throws Exception{
        var graph=graph();
        graph.apply(new GraphDelta(2,List.of(),List.of(),List.of(new Edge(node("Base.b",NodeKind.FUNCTION,10,14).id(),node("Base.a",NodeKind.FUNCTION,4,8).id(),EdgeKind.CALLS,.95f,
                Map.of("referencePath","src/Test.java","referencePrecision","reference_expression","referenceStartLine","12","referenceStartColumn","3","referenceEndLine","12","referenceEndColumn","6"))),List.of()));
        var result=call(tool(graph,"resolve_symbol_at_position"),"{\"file\":\"src/Test.java\",\"line\":12,\"column\":4}");
        assertEquals("reference_expression_candidates",result.path("resolution").asText());
        assertEquals("Base.a",result.path("symbols").get(0).path("name").asText());
        assertFalse(result.path("exactIdentifierResolved").asBoolean());
    }
}
