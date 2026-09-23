package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Owned disposable database only. No saved connection or user scheduler is touched. */
@EnabledIfEnvironmentVariable(named="DBA_SCHEDULER_DISPOSABLE",matches="cgraph-scheduler-qa-[a-f0-9]+")
class ScheduledJobsIntegrationTest {
    @TempDir Path root;
    @Test void detectBrowseCreateDisableEditConflictAndDeleteWithoutRunningStoredCommands()throws Exception{
        String vendor=System.getenv("DBA_SCHEDULER_VENDOR"),database="scheduler_test",schema=vendor.equals("postgresql")?"public":database;
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,1000,100,30),s->true)){
            String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Owned scheduler fixture").put("templateId",vendor).put("url",System.getenv("DBA_SCHEDULER_URL")).put("driverClass",vendor.equals("postgresql")?"org.postgresql.Driver":vendor.equals("mariadb")?"org.mariadb.jdbc.Driver":"com.mysql.cj.jdbc.Driver").put("jar",System.getenv("DBA_SCHEDULER_JAR")).put("username",System.getenv("DBA_SCHEDULER_USER")).put("password","scheduler-fixture-only").put("readOnly",false)).path("id").asText();
            var databaseTarget=Profiles.JSON.createObjectNode().put("kind","database").put("database",database);
            if(vendor.equals("postgresql")){
                assertFalse(tree(jobs,id,databaseTarget).path("nodes").toString().contains("scheduled_jobs"),"Do not show pg_cron before the extension exists");
                try(var c=connections.open(id);var st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE EXTENSION pg_cron");}
            }
            ObjectNode group=null;for(JsonNode node:tree(jobs,id,databaseTarget).path("nodes"))if(node.path("kind").asText().equals("scheduled_jobs"))group=(ObjectNode)node;
            assertNotNull(group);assertTrue(group.path("canCreate").asBoolean());
            assertTrue(tree(jobs,id,group).path("nodes").isEmpty());
            try(var c=connections.open(id);var st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE TABLE scheduler_effects(id INT)");}
            ObjectNode create=Profiles.JSON.createObjectNode().put("creation",true);create.set("target",group);
            ObjectNode snapshot=ObjectDesignerTest.load(jobs,id,create),draft=ObjectDesignerTest.draft(snapshot),fields=(ObjectNode)draft.path("fields");
            assertFalse(fields.path("enabled").asBoolean());fields.put("name","scheduler_qa").put("command","INSERT INTO scheduler_effects VALUES (1)");
            if(vendor.equals("postgresql"))fields.put("schedule","0 0 1 1 *");else fields.put("eventType","ONE TIME").put("executeAt","2030-01-01 00:00:00");
            JsonNode result=ObjectDesignerTest.save(jobs,id,create,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            ObjectNode node=(ObjectNode)tree(jobs,id,group).path("nodes").get(0),selection=Profiles.JSON.createObjectNode().put("key",node.path("key").asText());selection.set("parent",group);
            snapshot=ObjectDesignerTest.load(jobs,id,selection);assertFalse(snapshot.path("fields").path("enabled").asBoolean());assertEquals("INSERT INTO scheduler_effects VALUES (1)",snapshot.path("fields").path("command").asText());
            assertFalse(tree(jobs,id,node).path("nodes").isEmpty(),"Expanding a job displays its properties");
            draft=ObjectDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("enabled",true);
            result=ObjectDesignerTest.save(jobs,id,selection,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            snapshot=ObjectDesignerTest.load(jobs,id,selection);assertTrue(snapshot.path("fields").path("enabled").asBoolean());
            draft=ObjectDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("enabled",false).put("command","INSERT INTO scheduler_effects VALUES (2)");
            result=ObjectDesignerTest.save(jobs,id,selection,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),result.toString());
            snapshot=ObjectDesignerTest.load(jobs,id,selection);assertFalse(snapshot.path("fields").path("enabled").asBoolean());
            draft=ObjectDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("command","SELECT 3");
            ObjectNode request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
            JsonNode review=TableDesignerTest.waitRetained(jobs,"human",jobs.objectProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);st.execute(vendor.equals("postgresql")?"SELECT cron.alter_job("+node.path("oid").asText()+",command:='SELECT 4')":"ALTER EVENT scheduler_test.scheduler_qa DO SELECT 4");
            }
            result=HumanSqlTest.finish(jobs,"human",jobs.applyObjectProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));jobs.remove("human",review.path("id").asText());
            assertEquals("failed",result.path("result").path("status").asText());assertTrue(result.path("result").path("message").asText().contains("changed"),result.toString());
            if(vendor.equals("postgresql")){
                snapshot=ObjectDesignerTest.load(jobs,id,create);draft=ObjectDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("name","scheduler_qa").put("command","SELECT 99");
                ObjectNode duplicate=create.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());duplicate.set("draft",draft);
                JsonNode rejected=HumanSqlTest.finish(jobs,"human",jobs.objectProperties("human",id,duplicate,true));assertEquals("failed",rejected.path("state").asText());assertTrue(rejected.toString().contains("already exists"));
            }
            try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM scheduler_effects")){assertTrue(rs.next());assertEquals(0,rs.getInt(1),"No stored job command was run by browsing or saving");}
            try(var c=connections.open(id)){
                var plan=ScheduledJobEditor.actionPlan(jobs.new Job("human",id),c,selection);assertFalse(plan.drop().isBlank());
                // The contract checks generated removal SQL on this isolated fixture only.
                try(var st=c.createStatement()){c.setAutoCommit(true);st.execute(plan.drop());}
            }
            assertTrue(tree(jobs,id,group).path("nodes").isEmpty());
        }
    }
    static ObjectNode tree(QueryJobs jobs,String id,ObjectNode target)throws Exception{var done=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,target));assertEquals("complete",done.path("state").asText(),done.toString());return (ObjectNode)done.path("result");}
}
