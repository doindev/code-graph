package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ObjectCreationTest {
    @TempDir Path root;
    @Test void reviewedCreationIsOwnedSingleUseAndDoesNotReplace()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,15),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);
                var draft=Profiles.JSON.createObjectNode().put("kind","tables").put("schema","PUBLIC").put("name","New \"table");
                draft.putArray("columns").addObject().put("name","id").put("type","integer");
                var review=TableDesignerTest.waitRetained(jobs,"human",jobs.prepareCreation("human",id,draft));
                assertEquals("complete",review.path("state").asText(),review.toString());assertTrue(review.path("result").path("sql").asText().contains("\"New \"\"table\""));
                assertThrows(java.sql.SQLException.class,()->st.executeQuery("SELECT * FROM \"PUBLIC\".\"New \"\"table\""));
                var apply=Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true);
                assertThrows(IllegalArgumentException.class,()->jobs.applyCreation("other",apply));assertThrows(SecurityException.class,()->jobs.applyCreation("agent:test",apply));
                var result=HumanSqlTest.finish(jobs,"human",jobs.applyCreation("human",apply));assertEquals("success",result.path("result").path("status").asText(),result.toString());
                assertThrows(IllegalArgumentException.class,()->jobs.applyCreation("human",apply));jobs.remove("human",review.path("id").asText());
                st.execute("INSERT INTO \"PUBLIC\".\"New \"\"table\" VALUES(7)");
                review=TableDesignerTest.waitRetained(jobs,"human",jobs.prepareCreation("human",id,draft));apply.put("planId",review.path("id").asText());
                result=HumanSqlTest.finish(jobs,"human",jobs.applyCreation("human",apply));assertEquals("failed",result.path("result").path("status").asText());jobs.remove("human",review.path("id").asText());
                try(var rs=st.executeQuery("SELECT * FROM \"PUBLIC\".\"New \"\"table\"")){assertTrue(rs.next());assertEquals(7,rs.getInt(1));}
                for(String kind:new String[]{"schemas","sequences","views","indexes"}){
                    var d=Profiles.JSON.createObjectNode().put("kind",kind).put("schema","PUBLIC").put("name","NEW_"+kind.toUpperCase()).put("query","SELECT 1 AS ID").put("table","New \"table");d.putArray("indexColumns").add("id");
                    var p=ObjectCreation.prepare(c,d,false);st.execute(p.path("sql").asText());
                }
                assertFalse(ObjectCreation.supports("oracle","schemas"));assertFalse(ObjectCreation.supports("jdbc","tables"));assertTrue(ObjectCreation.supports("postgresql","materialized_views"));
                try(var other=java.sql.DriverManager.getConnection("jdbc:h2:mem:other_creation_target")){
                    assertNotEquals(ObjectCreation.prepare(c,draft,false).path("targetFingerprint"),ObjectCreation.prepare(other,draft,false).path("targetFingerprint"));
                }
                assertThrows(Exception.class,()->ObjectCreation.prepare(c,draft,true));
                draft.put("kind","views").put("query","SELECT 1; DELETE FROM something");assertThrows(Exception.class,()->ObjectCreation.prepare(c,draft,false));
                var tree=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,Profiles.JSON.createObjectNode().put("kind","schema").put("schema","PUBLIC")));
                assertTrue(java.util.stream.StreamSupport.stream(tree.path("result").path("nodes").spliterator(),false).anyMatch(n->n.path("kind").asText().equals("tables")&&n.path("canCreate").asBoolean()));
            }
        }
    }
}
