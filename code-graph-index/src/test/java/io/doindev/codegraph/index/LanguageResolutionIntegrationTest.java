package io.doindev.codegraph.index;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.store.GraphDelta;
import io.doindev.codegraph.lang.javascript.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LanguageResolutionIntegrationTest {
    @TempDir Path root;
    void write(String file,String text)throws Exception {
        Path p=root.resolve(file);Files.createDirectories(p.getParent());Files.writeString(p,text);
    }
    static List<Edge> calls(io.doindev.codegraph.store.ManagedGraph graph, String language, String path,String name) {
        return graph.edges(new SymbolId(language,path,name,0),Direction.OUT,Set.of(EdgeKind.CALLS));
    }
    void fixture()throws Exception {
        write("jsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"allowJs\":true}}");
        write("ui/use.js","import { helper } from '../lib/helper'; function caller(){helper(1);}");
        write("ui/widget.tsx","export function widget(){helper(2); return <div/>;}");
        write("ui/top.cjs","ghost(1);");
        write("lib/helper.ts","export function helper(n:number){}");
        write("server/Service.java","class Service {static void helper(int n){} static void ghost(int n){}}");
        write("server/helper.py","def helper(n):\n    pass\n");
    }
    @Test void realAdaptersHaveMemoryHybridParityIncludingTsxAndFileLevelCalls()throws Exception {
        fixture();
        try(var memory=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults());
            var hybrid=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),true,32L<<20)) {
            memory.fullIndexAll();hybrid.fullIndexAll();
            var expected=memory.defaultProject().graph();var actual=hybrid.defaultProject().graph();
            var helper=new SymbolId("ts","lib/helper.ts","helper",1);
            assertEquals(helper,calls(actual,"js","ui/use.js","caller").getFirst().to());
            assertEquals(helper,calls(actual,"ts","ui/widget.tsx","widget").getFirst().to());
            assertTrue(actual.edges(new FileId("ui/top.cjs"),Direction.OUT,Set.of(EdgeKind.CALLS)).isEmpty());
            for(var n:expected.allNodes(null))
                assertEquals(new HashSet<>(expected.edges(n.id(),Direction.OUT,null)),
                        new HashSet<>(actual.edges(n.id(),Direction.OUT,null)),n.id().value());
        }
    }
    @Test void incrementalRenameDeleteAndAdditionNeverHealIntoAnUnrelatedLanguage()throws Exception {
        fixture();
        for(boolean disk:List.of(false,true)) {
            write("lib/helper.ts","export function helper(n:number){}");
            try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),disk,32L<<20)) {
                workspace.fullIndexAll();var project=workspace.defaultProject();var graph=project.graph();
                assertEquals(1,calls(graph,"js","ui/use.js","caller").size());
                write("lib/helper.ts","export function renamed(n:number){}");
                project.indexer().applyChanges(List.of("lib/helper.ts"));
                assertTrue(calls(graph,"js","ui/use.js","caller").isEmpty());
                write("lib/other.mjs","export function helper(n){}");
                project.indexer().applyChanges(List.of("lib/other.mjs"));
                assertEquals("js",((SymbolId)calls(graph,"ts","ui/widget.tsx","widget").getFirst().to()).lang());
                Files.delete(root.resolve("lib/other.mjs"));project.indexer().applyChanges(List.of("lib/other.mjs"));
                assertTrue(calls(graph,"js","ui/use.js","caller").isEmpty());
            }
        }
    }
    @Test void fullReindexRemovesPreviouslyPublishedFalseEdges()throws Exception {
        fixture();var graph=new InMemoryCodeGraph();
        var indexer=new IncrementalIndexer(root,Analyzers.discover(),CodeGraphConfig.defaults(),graph);
        indexer.fullIndex();
        var falseEdge=new Edge(new FileId("ui/top.cjs"),new SymbolId("java","server/Service.java","Service.ghost",1),
                EdgeKind.CALLS,.1f,Map.of("resolution","heuristic"));
        graph.apply(new GraphDelta(graph.generation()+1,List.of(),List.of(),List.of(falseEdge),List.of()));
        assertEquals(1,graph.edges(falseEdge.from(),Direction.OUT,Set.of(EdgeKind.CALLS)).size());
        indexer.fullIndex();
        assertTrue(graph.edges(falseEdge.from(),Direction.OUT,Set.of(EdgeKind.CALLS)).isEmpty());
    }
    @Test void foreignNamesCannotExhaustHybridSimpleOrQualifiedLookupBudgets()throws Exception {
        write("jsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"allowJs\":true}}");
        // Spread 10,010 foreign declarations across small fragments. The old
        // unscoped disk lookup crosses its 10,000-entry limit for helper.
        LanguageAnalyzer foreign=new LanguageAnalyzer() {
            public String languageId(){return "java";}
            public Set<String> fileExtensions(){return Set.of("foreign");}
            public FileFragment extract(SourceFile source) {
                var nodes=new ArrayList<Node>();String file=source.relPath();
                nodes.add(new Node(new FileId(file),NodeKind.FILE,file,file,null,Metrics.NONE,Map.of()));
                for(int i=0;i<1001;i++)nodes.add(new Node(
                        new SymbolId("java",file,"helper",1,String.valueOf(i)),NodeKind.FUNCTION,"helper","helper(n)",
                        new SourceSpan(file,1,1,1,1),Metrics.NONE,Map.of()));
                return new FileFragment(new FileId(file),"java",file,nodes,List.of(),List.of(),List.of());
            }
        };
        for(int i=0;i<10;i++)write("foreign/"+i+".foreign","fixture");
        write("ui/import.js","import { helper } from '../lib/helper'; function imported(){helper(1);}");
        write("ui/simple.js","function simple(){helper(1);}");
        write("lib/helper.ts","export function helper(n:number){}");
        var analyzers=Analyzers.of(List.of(foreign,new JavaScriptAnalyzer(),new TypeScriptAnalyzer()));
        for(boolean disk:List.of(false,true))try(var workspace=Workspace.open(List.of(root),analyzers,p->CodeGraphConfig.defaults(),disk,32L<<20)) {
            workspace.fullIndexAll();var graph=workspace.defaultProject().graph();
            assertEquals(new SymbolId("ts","lib/helper.ts","helper",1),calls(graph,"js","ui/import.js","imported").getFirst().to());
            assertEquals(new SymbolId("ts","lib/helper.ts","helper",1),calls(graph,"js","ui/simple.js","simple").getFirst().to());
        }
    }
}
