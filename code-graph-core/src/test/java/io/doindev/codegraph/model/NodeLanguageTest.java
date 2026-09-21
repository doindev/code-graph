package io.doindev.codegraph.model;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NodeLanguageTest {
    private static Node node(NodeId id,NodeKind kind,Map<String,String> attrs){
        return new Node(id,kind,"navigation","navigation",null,Metrics.NONE,attrs);
    }
    @Test void fileLanguageUsesIndexedMetadataWithoutGuessingExtensions(){
        assertEquals("js",node(new FileId("src/navigation.js"),NodeKind.FILE,Map.of("lang","js")).lang());
        assertEquals("ts",node(new FileId("src/navigation.unusual"),NodeKind.FILE,Map.of("lang","ts")).lang());
        assertNull(node(new FileId("src/navigation.js"),NodeKind.FILE,Map.of()).lang());
        assertNull(node(new RepoId("repo"),NodeKind.REPOSITORY,Map.of("lang","js")).lang());
    }
    @Test void symbolLanguageRemainsBoundToStableIdentity(){
        var id=new SymbolId("java","src/A.java","A.run",0);
        assertEquals("java",node(id,NodeKind.FUNCTION,Map.of("lang","js")).lang());
        assertEquals("java:src/A.java#A.run/0",id.value());
    }
}
