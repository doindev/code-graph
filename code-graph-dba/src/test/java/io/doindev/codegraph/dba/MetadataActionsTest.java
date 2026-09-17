package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MetadataActionsTest {
    @TempDir Path root;
    static ObjectNode selection(QueryJobs jobs,String id,String group,String schema,String name)throws Exception{
        var parent=Profiles.JSON.createObjectNode().put("kind",group).put("schema",schema);
        return selection(jobs,id,parent,name);
    }
    static ObjectNode selection(QueryJobs jobs,String id,ObjectNode parent,String name)throws Exception{
        JsonNode page=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,parent));assertEquals("complete",page.path("state").asText(),page.toString());
        for(JsonNode n:page.path("result").path("nodes"))if(n.path("name").asText().equals(name)){ObjectNode result=Profiles.JSON.createObjectNode().put("key",n.path("key").asText());result.set("parent",parent);return result;}
        throw new AssertionError("Missing object "+name+": "+page);
    }
    static JsonNode preview(QueryJobs jobs,String id,ObjectNode selection)throws Exception{
        JsonNode result=HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,selection,false));assertEquals("complete",result.path("state").asText(),result.toString());return result.path("result");
    }
    static JsonNode action(QueryJobs jobs,String id,ObjectNode selection,String action,String name)throws Exception{
        var input=selection.deepCopy().put("action",action).put("newName",name).put("confirmed",true).put("fingerprint",preview(jobs,id,selection).path("fingerprint").asText());
        return HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,input,true));
    }
    @Test void h2RenameDeleteQuotedNamesColumnsDependenciesAndStaleSelection()throws Exception{
        try(var p=new Profiles(root,new DbaTest.MemoryVault());var c=new Connections(p);var jobs=new QueryJobs(c,new DbaConfig(root,64L<<20,2,1000,100,10),s->true)){
            String id=p.put(null,new DbaTest().input()).path("id").asText();
            try(var db=c.open(id);var st=db.createStatement()){
            st.execute("CREATE SCHEMA ACTIONS");st.execute("CREATE TABLE ACTIONS.ITEMS(ID int, TITLE varchar)");st.execute("CREATE VIEW ACTIONS.DEPENDENT AS SELECT * FROM ACTIONS.ITEMS");db.commit();
            st.execute("CREATE SEQUENCE ACTIONS.NO_RENAME");db.commit();var sequence=selection(jobs,id,"sequences","ACTIONS","NO_RENAME");assertFalse(preview(jobs,id,sequence).path("canRename").asBoolean());assertEquals("failed",action(jobs,id,sequence,"rename","should_not_work").path("state").asText());var deletedSequence=action(jobs,id,sequence,"delete","");assertEquals("complete",deletedSequence.path("state").asText(),deletedSequence.toString());
            st.execute("CREATE DOMAIN ACTIONS.D AS INTEGER");st.execute("CREATE INDEX ACTIONS.I ON ACTIONS.ITEMS(ID)");st.execute("CREATE VIEW ACTIONS.V AS SELECT ID FROM ACTIONS.ITEMS");
            for(String[] object:new String[][]{{"domains","D"},{"indexes","I"},{"views","V"}}){var target=selection(jobs,id,object[0],"ACTIONS",object[1]);assertTrue(preview(jobs,id,target).path("canRename").asBoolean());assertEquals("complete",action(jobs,id,target,"rename",object[1]+"_NEW").path("state").asText());assertEquals("complete",action(jobs,id,selection(jobs,id,object[0],"ACTIONS",object[1]+"_NEW"),"delete","").path("state").asText());}
            st.execute("CREATE SCHEMA OLD_NAME");assertEquals("complete",action(jobs,id,selection(jobs,id,"schemas","","OLD_NAME"),"rename","NEW_NAME").path("state").asText());assertEquals("complete",action(jobs,id,selection(jobs,id,"schemas","","NEW_NAME"),"delete","").path("state").asText());
            var selected=selection(jobs,id,"tables","ACTIONS","ITEMS");assertEquals("ITEMS",preview(jobs,id,selected).path("name").asText());
            assertThrows(SecurityException.class,()->jobs.metadataObject("agent:a",id,selected,false));
            assertThrows(IllegalArgumentException.class,()->jobs.metadataObject("human",id,selected.deepCopy().put("action","delete"),true));
            var failed=action(jobs,id,selected,"delete","");assertEquals("failed",failed.path("state").asText());assertTrue(failed.path("error").asText().contains("DEPENDENT"),failed.toString());
            String weird="renamed\"; DROP SCHEMA ACTIONS; --";
            var renamed=action(jobs,id,selected,"rename",weird);assertEquals("complete",renamed.path("state").asText(),renamed.toString());
            var stale=selected.deepCopy().put("action","delete").put("confirmed",true).put("fingerprint","stale");assertEquals("failed",HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,stale,true)).path("state").asText());
            assertEquals("complete",action(jobs,id,selection(jobs,id,"views","ACTIONS","DEPENDENT"),"delete","").path("state").asText());
            var relation=Profiles.JSON.createObjectNode().put("kind","relation").put("schema","ACTIONS").put("name",weird);
            assertEquals("complete",action(jobs,id,selection(jobs,id,relation,"TITLE"),"rename","new title").path("state").asText());
            assertEquals("complete",action(jobs,id,selection(jobs,id,relation,"new title"),"delete","").path("state").asText());
            assertEquals("complete",action(jobs,id,selection(jobs,id,"tables","ACTIONS",weird),"delete","").path("state").asText());
            assertEquals("complete",action(jobs,id,selection(jobs,id,"schemas","","ACTIONS"),"delete","").path("state").asText());
            assertThrows(IllegalArgumentException.class,()->MetadataActions.command(new MetadataActions.Plan("x","TABLE","x","DROP TABLE x","ALTER TABLE x RENAME TO ","","ok"),"postgresql","rename",Profiles.JSON.createObjectNode().put("fingerprint","ok").put("newName","x".repeat(64))));
            }
        }
    }
    @Test void materializedViewDeletePreviewGroupsCrossDatabaseManagedCleanup(){
        var base=new MetadataActions.Plan("mv","MATERIALIZED VIEW","\"reporting\".\"mv\"","DROP MATERIALIZED VIEW \"reporting\".\"mv\" RESTRICT","","reason","base","","",java.util.List.of(),java.util.List.of("External schedules remain unchanged."));
        var cleanup=new MaterializedViewSchedules.Command("SELECT cron.unschedule(42)","cron_control","scheduler","Remove the code-graph-managed refresh job");
        var plan=MetadataActions.withDeleteCleanup(base,java.util.List.of(cleanup),java.util.List.of("Cleanup and drop cannot be atomic."));
        var json=plan.json("app");
        assertNotEquals("base",plan.fingerprint());assertEquals(2,json.path("deleteCommands").size());assertEquals("cron_control",json.path("deleteCommandDetails").path(0).path("database").asText());assertEquals("scheduler",json.path("deleteCommandDetails").path(0).path("phase").asText());assertEquals("app",json.path("deleteCommandDetails").path(1).path("database").asText());assertTrue(json.path("deleteSql").asText().contains("cron_control"));assertEquals(2,json.path("deleteWarnings").size());
    }
    @Test void connectionRenamePreservesIdColorCredentialsAndRejectsStaleOrDuplicateNames()throws Exception{
        var vault=new DbaTest.MemoryVault();try(var p=new Profiles(root,vault)){
            var input=new DbaTest().input().put("name","original").put("password","secret").put("color","#123456");String id=p.put(null,input).path("id").asText();
            int secrets=vault.secrets.size();var renamed=p.rename(id,Profiles.JSON.createObjectNode().put("expectedName","original").put("name","New name"));
            assertEquals(id,renamed.path("id").asText());assertEquals("#123456",renamed.path("color").asText());assertEquals(secrets,vault.secrets.size());assertFalse(renamed.has("credentialRefs"));
            assertThrows(IllegalArgumentException.class,()->p.rename(id,Profiles.JSON.createObjectNode().put("expectedName","original").put("name","stale")));
            String second=p.put(null,new DbaTest().input().put("name","second")).path("id").asText();assertThrows(IllegalArgumentException.class,()->p.rename(second,Profiles.JSON.createObjectNode().put("expectedName","second").put("name"," new NAME ")));
        }
    }
}
