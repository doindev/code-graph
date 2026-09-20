package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MigrationPlansTest {
    @TempDir Path directory;

    @Test void structuredPlanIsBoundedPinnedAndExecutesOnceAgainstUnchangedFingerprint()throws Exception{
        var config=new DbaConfig(directory,128L<<20,2,100,100,10);var released=new AtomicBoolean();
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,_ ->true);var plans=new MigrationPlans()){
            ObjectNode profileInput=new DbaTest().input().put("templateId","h2");profileInput.put("url",profileInput.path("url").asText()+";DB_CLOSE_DELAY=-1");
            String id=profiles.put(null,profileInput).path("id").asText();
            ObjectNode scope;QueryJobs.Job snapshot;
            try(var c=connections.open(id)){
                c.setAutoCommit(true);scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database",c.getCatalog()).put("schema","PUBLIC"));
            }
            var target=new WorkflowTargets.Target(profiles.get(id),scope,Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","test"),"fixture");
            snapshot=await(jobs,"agent:a",jobs.captureSchema("agent:a",target,()->{}));
            ObjectNode input=Profiles.JSON.createObjectNode().put("snapshotId",snapshot.id).put("name","add status");
            input.putArray("changes").addObject().put("action","add_column").put("table","ITEMS").put("column","STATUS").put("type","VARCHAR(20)").put("nullable",true);
            // The table must be part of the fingerprint used by the plan.
            try(var c=connections.open(id);var statement=c.createStatement()){c.setAutoCommit(true);statement.execute("CREATE TABLE ITEMS(ID INT PRIMARY KEY)");}
            snapshot=await(jobs,"agent:a",jobs.captureSchema("agent:a",target,()->{}));
            input.put("snapshotId",snapshot.id);
            ObjectNode prepared=plans.prepare("agent:a",snapshot,input,()->released.set(true));
            assertEquals("codegraph-migration-v1",prepared.path("format").asText());assertEquals(1,prepared.path("statements").size());
            assertFalse(released.get());QueryJobs.Job plannedSnapshot=snapshot;
            assertThrows(IllegalArgumentException.class,()->plans.prepare("agent:a",plannedSnapshot,Profiles.JSON.createObjectNode().put("snapshotId",plannedSnapshot.id).put("sql","DROP SCHEMA PUBLIC"),()->{}));

            MigrationPlans.Plan retained=plans.require("agent:a",prepared.path("id").asText());
            ObjectNode request=Profiles.JSON.createObjectNode().put("connectionId",id).put("migrationPlanId",retained.id)
                    .put("migrationSchemaFingerprint",prepared.path("schemaFingerprint").asText()).put("migrationTransactional",false);
            request.set("migrationScope",scope.deepCopy());request.set("migrationStatements",prepared.path("statements").deepCopy());
            QueryJobs.Job applied=await(jobs,"agent:a",jobs.approved("a",request,()->{},()->{}));
            assertTrue(applied.result.path("applied").asBoolean(),applied.json().toString());
            try(var c=connections.open(id);var statement=c.createStatement();var rows=statement.executeQuery("SELECT STATUS FROM ITEMS WHERE 1=0")){assertEquals("STATUS",rows.getMetaData().getColumnName(1));}
            plans.consume(retained);assertThrows(IllegalArgumentException.class,()->plans.require("agent:a",retained.id));
        }
        assertTrue(released.get());
    }

    @Test void suppliedSqlRejectsDmlAndParameterMarkers()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,1,100,100,10),_ ->true);var plans=new MigrationPlans()){
            String id=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();ObjectNode scope;
            try(var c=connections.open(id)){scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database",c.getCatalog()).put("schema","PUBLIC"));}
            var target=new WorkflowTargets.Target(profiles.get(id),scope,Profiles.JSON.createObjectNode(),"fixture");
            QueryJobs.Job snapshot=await(jobs,"agent:a",jobs.captureSchema("agent:a",target,()->{}));
            for(String sql:List.of("INSERT INTO T VALUES (1)","CREATE TABLE X(ID INT DEFAULT ?)")){
                ObjectNode input=Profiles.JSON.createObjectNode().put("snapshotId",snapshot.id).put("sql",sql);
                assertThrows(IllegalArgumentException.class,()->plans.prepare("agent:a",snapshot,input,()->{}));
            }
        }
    }

    @Test void rehearsalUsesDistinctSnapshotSyntheticFixturesAndBoundedChecks()throws Exception{
        var config=new DbaConfig(directory,128L<<20,2,100,100,10);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,_ ->true);var plans=new MigrationPlans()){
            ObjectNode sourceInput=new DbaTest().input().put("name","source").put("templateId","h2");sourceInput.put("url","jdbc:h2:mem:source;DB_CLOSE_DELAY=-1");
            ObjectNode rehearsalInput=new DbaTest().input().put("name","rehearsal").put("templateId","h2");rehearsalInput.put("url","jdbc:h2:mem:rehearsal;DB_CLOSE_DELAY=-1");
            String sourceId=profiles.put(null,sourceInput).path("id").asText(),rehearsalId=profiles.put(null,rehearsalInput).path("id").asText();
            WorkflowTargets.Target sourceTarget=target(profiles,connections,sourceId),rehearsalTarget=target(profiles,connections,rehearsalId);
            try(var c=connections.open(sourceId);var statement=c.createStatement()){statement.execute("CREATE TABLE ITEMS(ID INT PRIMARY KEY)");}
            QueryJobs.Job sourceSnapshot=await(jobs,"agent:a",jobs.captureSchema("agent:a",sourceTarget,()->{}));
            ObjectNode migrationInput=Profiles.JSON.createObjectNode().put("snapshotId",sourceSnapshot.id);migrationInput.putArray("changes").addObject().put("action","add_column").put("table","ITEMS").put("column","STATUS").put("type","VARCHAR(20)");
            MigrationPlans.Plan source=plans.require("agent:a",plans.prepare("agent:a",sourceSnapshot,migrationInput,()->{}).path("id").asText());
            QueryJobs.Job rehearsalSnapshot=await(jobs,"agent:a",jobs.captureSchema("agent:a",rehearsalTarget,()->{}));
            ObjectNode rehearsal=Profiles.JSON.createObjectNode().put("planId",source.id).put("rehearsalSnapshotId",rehearsalSnapshot.id)
                    .put("setupSql","CREATE TABLE ITEMS(ID INT PRIMARY KEY)").put("fixtureSql","INSERT INTO ITEMS(ID) VALUES (1)");
            rehearsal.putArray("checks").add("SELECT ID, STATUS FROM ITEMS WHERE ID=1").add("SELECT STATUS FROM ITEMS WHERE ID=1");
            MigrationPlans.Plan retained=plans.require("agent:a",plans.prepareRehearsal("agent:a",source,rehearsalSnapshot,rehearsal,()->{}).path("id").asText());
            assertTrue(retained.value.path("rehearsal").asBoolean());assertEquals(3,retained.value.path("statements").size());
            ObjectNode request=Profiles.JSON.createObjectNode().put("connectionId",rehearsalId).put("migrationPlanId",retained.id)
                    .put("migrationSchemaFingerprint",retained.value.path("schemaFingerprint").asText()).put("migrationTransactional",false).put("migrationRehearsal",true);
            request.set("migrationScope",retained.sourceScope.deepCopy());request.set("migrationStatements",retained.value.path("statements").deepCopy());request.set("migrationChecks",retained.value.path("checks").deepCopy());
            QueryJobs.Job result=await(jobs,"agent:a",jobs.approved("a",request,()->{},()->{}));
            assertTrue(result.result.path("rehearsal").asBoolean());assertEquals(2,result.result.path("validations").size());assertTrue(result.result.path("postconditionObserved").asBoolean());
        }
    }

    private static WorkflowTargets.Target target(Profiles profiles,Connections connections,String id)throws Exception{
        ObjectNode scope;try(var c=connections.open(id)){scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database",c.getCatalog()).put("schema","PUBLIC"));}
        return new WorkflowTargets.Target(profiles.get(id),scope,Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName",profiles.get(id).path("name").asText()),"fixture");
    }

    static QueryJobs.Job await(QueryJobs jobs,String owner,JsonNode submitted)throws Exception{
        QueryJobs.Job job=jobs.require(owner,submitted.path("id").asText());long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(job.finished==0&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals("complete",job.state,job.json().toString());return job;
    }
}
