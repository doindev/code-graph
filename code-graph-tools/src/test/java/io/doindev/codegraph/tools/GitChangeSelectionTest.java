package io.doindev.codegraph.tools;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.Metrics;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.model.SourceSpan;
import io.doindev.codegraph.model.SymbolId;
import io.doindev.codegraph.store.GraphDelta;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GitChangeSelectionTest {
    @TempDir Path root;

    @Test void workingTreeAndRevisionSelectionsRecordExactEvidence() throws Exception {
        git("init");
        git("config","user.email","test@example.invalid");
        git("config","user.name","Test");
        Files.writeString(root.resolve("Old.java"),"class Old {}\n");
        git("add","Old.java");git("commit","-m","initial");
        String initial=git("rev-parse","HEAD").trim();
        Files.move(root.resolve("Old.java"),root.resolve("New.java"));
        Files.writeString(root.resolve("Extra.java"),"class Extra {}\n");

        var graph=new InMemoryCodeGraph();
        graph.apply(new GraphDelta(1,List.of(),List.of(node("Old.java"),node("New.java"),node("Extra.java")),List.of(),List.of()));
        var tool=CodeGraphTools.standard(graph,CodeGraphConfig.defaults(),root,_->{ }).stream()
                .filter(candidate->candidate.spec().name().equals("analyze_change")).findFirst().orElseThrow();
        var working=ToolSupport.JSON.createObjectNode();
        working.putObject("git").put("kind","working_tree");
        var workingResult=ToolSupport.JSON.readTree(tool.call(working).json());
        assertEquals("git_selection_against_current_index",workingResult.path("analysisBasis").asText());
        assertEquals(initial,workingResult.path("git").path("baseCommit").asText());
        assertEquals("WORKING_TREE",workingResult.path("git").path("headCommit").asText());
        assertTrue(workingResult.path("git").path("changes").toString().contains("Extra.java"));

        git("add",".");git("commit","-m","second");
        String second=git("rev-parse","HEAD").trim();
        var revisions=ToolSupport.JSON.createObjectNode();
        revisions.putObject("git").put("kind","revisions").put("base",initial).put("head",second);
        var revisionResult=ToolSupport.JSON.readTree(tool.call(revisions).json());
        assertEquals(initial,revisionResult.path("git").path("baseCommit").asText());
        assertEquals(second,revisionResult.path("git").path("headCommit").asText());
        assertFalse(revisionResult.path("git").path("changes").isEmpty());
    }

    private Node node(String path){return new Node(new SymbolId("java",path,path,0),NodeKind.TYPE,path,path,new SourceSpan(path,1,1,1,5),Metrics.NONE,Map.of());}
    private String git(String... args)throws Exception{
        var command=new java.util.ArrayList<String>();command.add("git");command.addAll(List.of(args));
        var process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),output);return output;
    }
}
