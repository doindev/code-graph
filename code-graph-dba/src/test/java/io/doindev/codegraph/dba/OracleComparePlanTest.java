package io.doindev.codegraph.dba;
import com.fasterxml.jackson.databind.node.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OracleComparePlanTest {
    private static CompareSql.Plan plan()throws Exception{
        var source=new CompareCatalog.Inventory("oracle","PDB","19");var destination=new CompareCatalog.Inventory("oracle","PDB","19");
        source.schemas.add("SRC");destination.schemas.add("DST");
        return new CompareSql.Plan(source,destination,new CompareCatalog.Target("a","PDB","SRC",false,""),new CompareCatalog.Target("b","PDB","DST",false,""),Profiles.JSON.createObjectNode().put("dataMode","none").put("syncSequences",true));
    }
    private static ObjectNode sequence(String owner,String name,int value)throws Exception{
        ObjectNode object=CompareCatalog.item(owner,"sequences",name).put("supported",true);object.putArray("stateModes").add("advance");object.putObject("state").put("value",Integer.toString(value));
        object.putObject("fields").put("increment_by","-3").put("min_value","-100").put("max_value","-1");return object;
    }
    @Test void cachedDescendingAdvancementNeverMovesBackwards()throws Exception{
        var plan=plan();var source=sequence("SRC","COUNTER",-4);var destination=sequence("DST","COUNTER",-10);
        plan.selected.put(CompareCatalog.key("SRC","sequences","COUNTER"),new CompareSql.Choice(source,destination,Profiles.JSON.createObjectNode()));
        OracleCompare.prepare(plan);assertEquals(List.of("ALTER SEQUENCE \"DST\".\"COUNTER\" RESTART START WITH -10"),plan.state);assertTrue(plan.warnings.getFirst().contains("source NEXTVAL was never evaluated"));
    }
    @Test void unsupportedSequenceModeAndDependenciesBlockGeneration()throws Exception{
        var plan=plan();var source=sequence("SRC","COUNTER",-4);plan.selected.put(CompareCatalog.key("SRC","sequences","COUNTER"),new CompareSql.Choice(source,null,Profiles.JSON.createObjectNode().put("sequenceMode","exact")));
        assertThrows(IllegalArgumentException.class,()->OracleCompare.prepare(plan));
        source.putArray("dependencies").add("a");plan.selected.clear();plan.selected.put("a",new CompareSql.Choice(source,null,Profiles.JSON.createObjectNode()));
        assertTrue(assertThrows(IllegalArgumentException.class,()->OracleCompare.prepare(plan)).getMessage().contains("cycle"));
    }
    @Test void oracleServerCancellationIsFatalToCapture(){assertTrue(CompareCatalog.fatal(new java.sql.SQLException("cancelled","72000",1013)));}
}
