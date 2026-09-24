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
    @Test void dataDeletionRequiresCompleteIncomingDependencyVisibility()throws Exception{
        var base=plan();var plan=new CompareSql.Plan(base.source,base.destination,base.from,base.to,Profiles.JSON.createObjectNode().put("dataMode","mirror"));
        var source=CompareCatalog.item("SRC","tables","ITEM").put("supported",true).put("dataSupported",true);
        var destination=CompareCatalog.item("DST","tables","ITEM").put("supported",true).put("dataSupported",true).put("incomingCoverage","accessible_objects");
        plan.selected.put(CompareCatalog.key("SRC","tables","ITEM"),new CompareSql.Choice(source,destination,Profiles.JSON.createObjectNode().put("includeData",true)));
        assertTrue(assertThrows(IllegalArgumentException.class,()->OracleCompare.prepare(plan)).getMessage().contains("SELECT_CATALOG_ROLE"));assertTrue(plan.data.isEmpty());
        destination.put("incomingCoverage","catalog");OracleCompare.prepare(plan);assertEquals(1,plan.data.size());
    }
    @Test void blockedAndImplicitDefinitionsStillConsumeTheReviewAllowance()throws Exception{
        var object=CompareCatalog.item("SRC","tables","ITEM").put("implicit",true);long size=OracleCompare.reviewBudget(0,object);
        assertTrue(size>512);assertThrows(IllegalArgumentException.class,()->OracleCompare.reviewBudget(CompareCatalog.MAX_BYTES-size+1,object));
    }
    @Test void oracleServerCancellationIsFatalToCapture(){assertTrue(CompareCatalog.fatal(new java.sql.SQLException("cancelled","72000",1013)));}
}
