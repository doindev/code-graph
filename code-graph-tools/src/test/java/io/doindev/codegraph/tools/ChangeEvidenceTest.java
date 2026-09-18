package io.doindev.codegraph.tools;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChangeEvidenceTest {
    @Test void publicToolsFailClosedOnHistoricalInputsAndDoNotExecuteTests()throws Exception{
        var graph=new InMemoryCodeGraph();var node=node("tests/ExampleTest.java","test");graph.apply(new GraphDelta(1,List.of(),List.of(node),List.of(),List.of()));
        var tools=CodeGraphTools.standard(graph,io.doindev.codegraph.config.CodeGraphConfig.defaults(),_ ->{});
        for(String name:List.of("analyze_change","find_affected_tests")){
            var tool=tools.stream().filter(t->t.spec().name().equals(name)).findFirst().orElseThrow();
            var args=ToolSupport.JSON.createObjectNode();args.putArray("files").add("tests/ExampleTest.java");var response=tool.call(args);assertFalse(response.error(),response.json());
            var result=ToolSupport.JSON.readTree(response.json());assertEquals(1,result.path("candidateTests").size());assertEquals("current_index",result.path("analysisBasis").asText());
            args.putObject("git").put("kind","revisions").put("base","--bad").put("head","HEAD");assertTrue(tool.call(args).error());
        }
    }
    static Node node(String path,String name){return new Node(new SymbolId("java",path,name,0),NodeKind.FUNCTION,name,name,new SourceSpan(path,1,1,2,1),Metrics.NONE,Map.of());}
    @Test void pathsConfidenceCandidateTestsAndMissingDeletesAreExplicit(){
        var changed=node("src/Service.java","Service.run");var test=node("tests/ServiceTest.java","ServiceTest.test");var indirect=node("tests/SystemTest.java","SystemTest.test");
        var graph=new InMemoryCodeGraph();graph.apply(new GraphDelta(1,List.of(),List.of(changed,test,indirect),List.of(new Edge(test.id(),changed.id(),EdgeKind.CALLS,.95f,Map.of()),new Edge(indirect.id(),test.id(),EdgeKind.CALLS,.5f,Map.of())),List.of()));
        var args=ToolSupport.JSON.createObjectNode();args.putArray("files").add("src/Service.java").add("deleted.java");
        var result=ChangeEvidence.analyze(graph,args);
        assertEquals(3,result.path("affectedSymbols").size());assertEquals(2,result.path("candidateTests").size());
        assertEquals(3,result.path("candidateTests").get(1).path("evidencePath").size());
        assertEquals(.5,result.path("candidateTests").get(1).path("confidence").asDouble());
        assertEquals(1,result.path("unresolvedTargets").size());assertFalse(result.path("inventoryComplete").asBoolean());
        args.put("minConfidence",.8);assertEquals(1,ChangeEvidence.analyze(graph,args).path("candidateTests").size());
    }
    @Test void staticDatabaseEvidenceIsNotMistakenForLiveCatalogAuthorization(){
        var graph=new InMemoryCodeGraph();var code=node("src/Users.java","Users.load");
        var mapping=new Node(new SymbolId("dbmap","src/Users.java","mapping.x",0),NodeKind.DATABASE_MAPPING,"users","users",code.span(),Metrics.NONE,Map.of("schema","public","table","users","column","id","confidence","0.85"));
        graph.apply(new GraphDelta(1,List.of(),List.of(code,mapping),List.of(),List.of()));
        var args=ToolSupport.JSON.createObjectNode();args.putArray("databaseChanges").addObject().put("table","users").put("column","id");
        var result=ChangeEvidence.analyze(graph,args);assertEquals(1,result.path("databaseMappings").size());assertEquals(1,result.path("affectedSymbols").size());
        assertEquals("not_observed",result.path("databaseDependencies").path("state").asText());
        assertThrows(IllegalArgumentException.class,()->ChangeEvidence.analyze(graph,ToolSupport.JSON.createObjectNode().set("files",ToolSupport.JSON.createArrayNode().add("../other"))));
    }
}
