package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TableCreationTest {
    @TempDir Path root;
    static ObjectNode input(String schema){return Profiles.JSON.createObjectNode().put("creation",true).set("target",Profiles.JSON.createObjectNode().put("kind","tables").put("schema",schema));}
    @Test void createThroughDesignerAndRejectInvalidOrStalePlans()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,15),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);var request=input("PUBLIC");
                var initialized=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,request,false));assertEquals("complete",initialized.path("state").asText(),initialized.toString());var baseline=(ObjectNode)initialized.path("result");
                var draft=TableDesignerTest.draft(baseline);request.put("fingerprint",baseline.path("fingerprint").asText());request.set("draft",draft);
                assertThrows(Exception.class,()->TableCreation.prepare(c,baseline,request));
                ((ObjectNode)draft.path("fields")).put("name","Draft table").put("comment","Created in Properties");
                var columns=(ArrayNode)draft.path("columns");columns.addObject().put("id","new:id").put("name","id").put("type","integer").put("pk",1).put("nullable",false).put("identity","d");
                columns.addObject().put("id","new:value").put("name","value").put("type","integer").put("pk",0).put("nullable",true).put("default","5").put("comment","Defaulted");
                columns.addObject().put("id","new:calc").put("name","calculated").put("type","integer").put("pk",0).put("nullable",true).put("generated","\"value\" * 2");
                var reviewed=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",reviewed.path("state").asText(),reviewed.toString());assertTrue(reviewed.path("result").path("commands").get(0).path("sql").asText().startsWith("CREATE TABLE"));
                assertThrows(java.sql.SQLException.class,()->st.executeQuery("SELECT * FROM PUBLIC.\"Draft table\""));
                var apply=Profiles.JSON.createObjectNode().put("planId",reviewed.path("id").asText()).put("confirmed",true);
                assertThrows(IllegalArgumentException.class,()->jobs.applyTableProperties("other",apply));assertThrows(SecurityException.class,()->jobs.tableProperties("agent:x",id,request,true));
                var result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("success",result.path("result").path("status").asText(),result.toString());jobs.remove("human",reviewed.path("id").asText());
                st.execute("INSERT INTO PUBLIC.\"Draft table\" DEFAULT VALUES");try(var rs=st.executeQuery("SELECT \"id\",\"value\",\"calculated\" FROM PUBLIC.\"Draft table\"")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));assertEquals(5,rs.getInt(2));assertEquals(10,rs.getInt(3));}
                assertThrows(Exception.class,()->TableCreation.prepare(c,baseline,request));
                ((ObjectNode)draft.path("fields")).put("name","Other");var bad=draft.deepCopy();((ObjectNode)bad.path("columns").get(1)).put("generated","1");var badRequest=request.deepCopy();badRequest.set("draft",bad);assertThrows(Exception.class,()->TableCreation.prepare(c,baseline,badRequest));
                reviewed=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));st.execute("CREATE TABLE PUBLIC.\"Other\"(preserved INT)");apply.put("planId",reviewed.path("id").asText());result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("failed",result.path("result").path("status").asText());jobs.remove("human",reviewed.path("id").asText());
                try(var rs=st.executeQuery("SELECT preserved FROM PUBLIC.\"Other\"")){assertFalse(rs.next());}
            }
        }
    }
}
