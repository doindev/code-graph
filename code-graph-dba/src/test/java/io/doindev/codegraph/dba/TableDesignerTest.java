package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TableDesignerTest {
    @TempDir Path root;
    static ObjectNode waitRetained(QueryJobs jobs,String owner,ObjectNode job)throws Exception{for(int i=0;i<300;i++){var state=jobs.status(owner,job.path("id").asText());if(state.path("finished").asLong()!=0)return state;Thread.sleep(50);}throw new AssertionError("Job timeout");}
    static ObjectNode draft(ObjectNode snapshot){ObjectNode out=Profiles.JSON.createObjectNode();out.set("fields",snapshot.path("fields").deepCopy());out.set("columns",snapshot.path("columns").deepCopy());out.putArray("objects");return out;}
    @Test void reviewApplyOwnershipStalenessAndPreservedRows()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,15),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var anchor=connections.open(id);var st=anchor.createStatement()){
                anchor.setAutoCommit(true);st.execute("CREATE TABLE PUBLIC.DESIGN_TEST(ID INT PRIMARY KEY, NAME VARCHAR(30))");st.execute("INSERT INTO PUBLIC.DESIGN_TEST VALUES(1,'preserved')");
                var selection=MetadataActionsTest.selection(jobs,id,"tables","PUBLIC","DESIGN_TEST");
                var loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));assertEquals("complete",loaded.path("state").asText(),loaded.toString());var snapshot=(ObjectNode)loaded.path("result");assertTrue(snapshot.path("editable").asBoolean());
                var draft=draft(snapshot);((ObjectNode)draft.path("columns").get(1)).put("name","DISPLAY_NAME");((ArrayNode)draft.path("columns")).addObject().put("id","new:date").put("name","JOINED").put("type","date").put("nullable",true).put("pk",0).put("default","DATE '2026-09-04'").put("comment","Date comment").put("identity","").put("generated","");
                var request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
                var review=waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());assertFalse(review.path("result").path("atomic").asBoolean());
                var apply=Profiles.JSON.createObjectNode().put("planId",review.path("id").asText());assertThrows(IllegalArgumentException.class,()->jobs.applyTableProperties("other",apply));assertThrows(IllegalArgumentException.class,()->jobs.applyTableProperties("human",apply));apply.put("confirmed",true);
                var saved=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("success",saved.path("result").path("status").asText(),saved.toString());assertThrows(IllegalArgumentException.class,()->jobs.applyTableProperties("human",apply));jobs.remove("human",review.path("id").asText());
                try(var rs=st.executeQuery("SELECT DISPLAY_NAME,JOINED FROM PUBLIC.DESIGN_TEST")){assertTrue(rs.next());assertEquals("preserved",rs.getString(1));assertEquals("2026-09-04",rs.getString(2));}
                var stale=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("failed",stale.path("state").asText());
                loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));snapshot=(ObjectNode)loaded.path("result");draft=draft(snapshot);((ObjectNode)draft.path("columns").get(1)).put("name","PARTIAL_NAME").put("type","integer");request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
                review=waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());
                var partial=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("partial",partial.path("result").path("outcome").asText(),partial.toString());jobs.remove("human",review.path("id").asText());
                try(var rs=st.executeQuery("SELECT PARTIAL_NAME FROM PUBLIC.DESIGN_TEST")){assertTrue(rs.next());assertEquals("preserved",rs.getString(1));}
                assertThrows(SecurityException.class,()->jobs.tableProperties("agent:test",id,selection,false));
            }
        }
    }
    @Test void maliciousTypesAndExpressionsAreRejected()throws Exception{
        assertEquals("numeric(10,2)",TableDesigner.type("numeric(10,2)"));assertThrows(IllegalArgumentException.class,()->TableDesigner.type("int; DROP TABLE users"));
        assertThrows(Exception.class,()->TableDesigner.expression("1; DROP TABLE users"));assertThrows(IllegalArgumentException.class,()->TableDesigner.q("bad\nname"));
    }
}
