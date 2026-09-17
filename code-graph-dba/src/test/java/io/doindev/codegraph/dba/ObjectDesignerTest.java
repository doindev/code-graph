package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ObjectDesignerTest {
    @TempDir Path root;
    ObjectNode input()throws Exception{return new DbaTest().input().put("url","jdbc:h2:file:"+root.resolve("objects").toAbsolutePath().toString().replace((char)92,(char)47));}
    static ObjectNode newObject(String kind,String schema){
        ObjectNode input=Profiles.JSON.createObjectNode().put("creation",true);
        input.putObject("target").put("kind",kind).put("schema",schema);return input;
    }
    static ObjectNode load(QueryJobs jobs,String id,ObjectNode request)throws Exception{
        JsonNode state=HumanSqlTest.finish(jobs,"human",jobs.objectProperties("human",id,request,false));
        assertEquals("complete",state.path("state").asText(),state.toString());return (ObjectNode)state.path("result");
    }
    static ObjectNode draft(ObjectNode snapshot){
        ObjectNode draft=Profiles.JSON.createObjectNode().put("sqlMode",false).put("sql","").put("splitSql",false);
        draft.set("fields",snapshot.path("fields").deepCopy());if(snapshot.has("refreshSchedule"))draft.set("schedule",snapshot.path("refreshSchedule").path("config").deepCopy());return draft;
    }
    static JsonNode save(QueryJobs jobs,String id,ObjectNode request,ObjectNode snapshot,ObjectNode draft)throws Exception{
        ObjectNode input=request.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());input.set("draft",draft);
        JsonNode review=TableDesignerTest.waitRetained(jobs,"human",jobs.objectProperties("human",id,input,true));
        assertEquals("complete",review.path("state").asText(),review.toString());
        try{return HumanSqlTest.finish(jobs,"human",jobs.applyObjectProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));}
        finally{jobs.remove("human",review.path("id").asText());}
    }
    @Test void sequencePropertiesRoundTripAndDoNotConsumeOrRestartValues()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,1000,100,15),s->true)){
            String id=profiles.put(null,input()).path("id").asText();
            var request=newObject("sequences","PUBLIC");var snapshot=load(jobs,id,request);var draft=draft(snapshot);
            var fields=(ObjectNode)draft.path("fields");fields.put("name","special \"sequence").put("start","9007199254740993").put("increment","3").put("minimum","1").put("maximum","9223372036854775807").put("cache","8").put("cycle",true);
            var result=save(jobs,id,request,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            request=MetadataActionsTest.selection(jobs,id,"sequences","PUBLIC","special \"sequence");snapshot=load(jobs,id,request);
            assertEquals("9007199254740993",snapshot.path("fields").path("start").asText());
            assertTrue(snapshot.path("categories").toString().contains("DDL"));assertTrue(snapshot.path("ddl").asText().contains("CACHE 8"));
            draft=draft(snapshot);((ObjectNode)draft.path("fields")).put("cache","16");
            result=save(jobs,id,request,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT NEXT VALUE FOR PUBLIC.\"special \"\"sequence\"")){
                assertTrue(rs.next());assertEquals(9007199254740993L,rs.getLong(1));
            }
        }
    }
    @Test void viewsUseOneEditorPreserveDataAndRejectStaleReviews()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,1000,100,15),s->true)){
            String id=profiles.put(null,input()).path("id").asText();
            var request=newObject("views","PUBLIC");var snapshot=load(jobs,id,request);var draft=draft(snapshot);
            ((ObjectNode)draft.path("fields")).put("name","V_EDITOR").put("query","SELECT 7 AS ID");
            var result=save(jobs,id,request,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            request=MetadataActionsTest.selection(jobs,id,"views","PUBLIC","V_EDITOR");snapshot=load(jobs,id,request);draft=draft(snapshot);
            assertTrue(snapshot.path("ddl").asText().contains("CREATE VIEW"));
            ((ObjectNode)draft.path("fields")).put("query","SELECT 9 AS ID");
            result=save(jobs,id,request,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            var stale=request.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());stale.set("draft",draft);
            assertEquals("failed",HumanSqlTest.finish(jobs,"human",jobs.objectProperties("human",id,stale,true)).path("state").asText());
            try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT ID FROM PUBLIC.V_EDITOR")){assertTrue(rs.next());assertEquals(9,rs.getInt(1));}
        }
    }
    @Test void reviewsRequireOwnerConfirmationAndAreSingleUse()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,1000,100,15),s->true)){
            String id=profiles.put(null,input()).path("id").asText();var request=newObject("schemas","");
            var snapshot=load(jobs,id,request);var draft=draft(snapshot);((ObjectNode)draft.path("fields")).put("name","EDITOR_SCHEMA");
            var input=request.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());input.set("draft",draft);
            var review=TableDesignerTest.waitRetained(jobs,"human",jobs.objectProperties("human",id,input,true));assertEquals("complete",review.path("state").asText(),review.toString());
            var apply=Profiles.JSON.createObjectNode().put("planId",review.path("id").asText());
            assertThrows(IllegalArgumentException.class,()->jobs.applyObjectProperties("human",apply));
            apply.put("confirmed",true);assertThrows(IllegalArgumentException.class,()->jobs.applyObjectProperties("other",apply));
            assertThrows(SecurityException.class,()->jobs.applyObjectProperties("agent:a",apply));
            assertEquals("success",HumanSqlTest.finish(jobs,"human",jobs.applyObjectProperties("human",apply)).path("result").path("status").asText());
            assertThrows(IllegalArgumentException.class,()->jobs.applyObjectProperties("human",apply));
            jobs.remove("human",review.path("id").asText());
        }
    }
    @Test void truncateRequiresConfirmationAndKeepsStructure()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,1000,100,15),s->true)){
            String id=profiles.put(null,input()).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE TABLE PUBLIC.TRUNCATE_EDITOR(ID INTEGER)");st.execute("INSERT INTO PUBLIC.TRUNCATE_EDITOR VALUES(1)");}
            var request=MetadataActionsTest.selection(jobs,id,"tables","PUBLIC","TRUNCATE_EDITOR");
            var preview=MetadataActionsTest.preview(jobs,id,request);assertTrue(preview.path("canTruncate").asBoolean());
            var action=request.deepCopy().put("action","truncate").put("fingerprint",preview.path("fingerprint").asText());
            assertThrows(IllegalArgumentException.class,()->jobs.metadataObject("human",id,action,true));
            action.put("confirmed",true);var result=HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,action,true));assertEquals("complete",result.path("state").asText(),result.toString());
            try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM PUBLIC.TRUNCATE_EDITOR")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
        }
    }
    @Test void nativeSqlReportsPartialCommitsAndNeverReplaysAPlan()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,1000,100,15),s->true)){
            String id=profiles.put(null,input()).path("id").asText();var request=newObject("views","PUBLIC");var snapshot=load(jobs,id,request);var draft=draft(snapshot);
            ((ObjectNode)draft.path("fields")).put("name","PARTIAL_VIEW");draft.put("sqlMode",true).put("splitSql",true).put("sql","CREATE VIEW PUBLIC.PARTIAL_VIEW AS SELECT 1 AS ID; ALTER VIEW PUBLIC.MISSING_VIEW RENAME TO BAD");
            var result=save(jobs,id,request,snapshot,draft);assertEquals("failed",result.path("result").path("status").asText());assertEquals("partial_or_unknown",result.path("result").path("outcome").asText(),result.toString());
            try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT ID FROM PUBLIC.PARTIAL_VIEW")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
        }
    }
}
