package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Deliberately gated: the test harness provisions and destroys this database, never a user profile. */
@EnabledIfEnvironmentVariable(named="DBA_REUSABLE_DISPOSABLE",matches="cgraph-reusable-qa-[a-f0-9]+")
class WorkflowVendorIntegrationTest {
    @TempDir Path directory;
    @Test @Timeout(180) void schemaObservationsMigrationRehearsalContractsAndPlansUseDisposableTargets()throws Exception{
        String vendor=System.getenv("DBA_REUSABLE_VENDOR"),schema=vendor.equals("postgresql")?"public":"approval_test";
        var template=DatabaseCatalog.get(vendor);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,128L<<20,2,100,100,20),_ ->true);var plans=new MigrationPlans()){
            String id=profiles.put(null,draft(template,vendor,"Owned workflow source",System.getenv("DBA_REUSABLE_URL"))).path("id").asText();
            String rehearsalId=profiles.put(null,draft(template,vendor,"Owned workflow rehearsal",System.getenv("DBA_REUSABLE_REHEARSAL_URL"))).path("id").asText();
            ObjectNode scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("schema",schema).put("database","approval_test"));
            ObjectNode rehearsalScope=ApprovalScope.resolve(profiles.get(rehearsalId),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("schema",schema).put("database","approval_test"));
            var target=new WorkflowTargets.Target(profiles.get(id),scope,Profiles.JSON.createObjectNode(),"Owned disposable fixture");
            var rehearsalTarget=new WorkflowTargets.Target(profiles.get(rehearsalId),rehearsalScope,Profiles.JSON.createObjectNode(),"Independent disposable rehearsal fixture");
            String table=schema+".workflow_items";
            try(var c=connections.open(id);var statement=c.createStatement()){
                c.setAutoCommit(true);statement.execute("CREATE TABLE "+table+"(id INT PRIMARY KEY, title VARCHAR(80) NOT NULL)");
                statement.execute("INSERT INTO "+table+" VALUES(1,'synthetic-value-must-not-be-in-snapshot')");
                statement.execute("CREATE VIEW "+schema+".workflow_view AS SELECT id, title FROM "+table);
                var before=finish(jobs,jobs.captureSchema("agent:workflow",target,()->{}));
                assertFalse(before.result.toString().contains("synthetic-value-must-not-be-in-snapshot"));
                assertFalse(before.result.path("coverage").path("warnings").toString().contains("PRIMARY"),before.result.path("coverage").toString());
                assertTrue(java.util.stream.StreamSupport.stream(before.result.path("objects").spliterator(),false).anyMatch(o->o.path("name").asText().equals("workflow_view")),before.result.toString());
                statement.execute("ALTER TABLE "+table+" ADD COLUMN optional_value INTEGER DEFAULT 7");
                var after=finish(jobs,jobs.captureSchema("agent:workflow",target,()->{}));
                var compared=SchemaSnapshots.compare(before.result,after.result,100);
                assertTrue(java.util.stream.StreamSupport.stream(compared.path("differences").spliterator(),false).anyMatch(d->d.path("name").asText().equals("workflow_items")&&d.path("kind").asText().equals("structural_difference")),compared.toString());
                try(var rows=statement.executeQuery("SELECT title FROM "+table+" WHERE id=1")){assertTrue(rows.next());assertEquals("synthetic-value-must-not-be-in-snapshot",rows.getString(1));}
                assertFalse(compared.path("migrationGenerated").asBoolean());
                ObjectNode migrationInput=Profiles.JSON.createObjectNode().put("snapshotId",after.id).put("name","add workflow state");
                migrationInput.putArray("changes").addObject().put("action","add_column").put("table","workflow_items").put("column","workflow_state").put("type","VARCHAR(24)").put("nullable",true);
                MigrationPlans.Plan sourcePlan=plans.require("agent:workflow",plans.prepare("agent:workflow",after,migrationInput,()->{}).path("id").asText());

                QueryJobs.Job emptyRehearsal=finish(jobs,jobs.captureSchema("agent:workflow",rehearsalTarget,()->{}));
                ObjectNode rehearsal=Profiles.JSON.createObjectNode().put("planId",sourcePlan.id).put("rehearsalSnapshotId",emptyRehearsal.id)
                        .put("setupSql","CREATE TABLE "+table+"(id INT PRIMARY KEY, title VARCHAR(80) NOT NULL, optional_value INTEGER DEFAULT 7)")
                        .put("fixtureSql","INSERT INTO "+table+"(id,title) VALUES(1,'synthetic-rehearsal-value')");
                rehearsal.putArray("checks").add("SELECT id, workflow_state FROM "+table+" WHERE id=1");
                MigrationPlans.Plan rehearsalPlan=plans.require("agent:workflow",plans.prepareRehearsal("agent:workflow",sourcePlan,emptyRehearsal,rehearsal,()->{}).path("id").asText());
                QueryJobs.Job rehearsed=finish(jobs,jobs.approved("workflow",migrationRequest(rehearsalId,rehearsalPlan),()->{},()->{}));
                assertTrue(rehearsed.result.path("rehearsal").asBoolean(),rehearsed.json().toString());
                assertEquals(1,rehearsed.result.path("validations").size());

                QueryJobs.Job applied=finish(jobs,jobs.approved("workflow",migrationRequest(id,sourcePlan),()->{},()->{}));
                assertTrue(applied.result.path("applied").asBoolean(),applied.json().toString());
                try(var verify=statement.executeQuery("SELECT workflow_state FROM "+table+" WHERE 1=0")){assertEquals("workflow_state",verify.getMetaData().getColumnName(1));}
                QueryJobs.Job migrated=finish(jobs,jobs.captureSchema("agent:workflow",target,()->{}));
                JsonNode observedTable=java.util.stream.StreamSupport.stream(migrated.result.path("objects").spliterator(),false)
                        .filter(o->o.path("schema").asText().equalsIgnoreCase(schema)&&o.path("name").asText().equalsIgnoreCase("workflow_items")&&o.path("columns").isArray()).findFirst().orElse(Profiles.JSON.createObjectNode());
                assertTrue(java.util.stream.StreamSupport.stream(observedTable.path("columns").spliterator(),false).anyMatch(cn->cn.path("name").asText().equalsIgnoreCase("workflow_state")),observedTable.toString());
                var mappings=Profiles.JSON.createArrayNode();mappings.addObject().put("id","live-contract").put("generation",17).put("schema",schema)
                        .put("table","workflow_items").put("column","workflow_state").put("databaseType","VARCHAR").put("confidence",1.0);
                ObjectNode contract=ContractValidation.validate(migrated.result,mappings,10);assertEquals(0,contract.path("totalIssues").asInt(),contract.toString());

                ObjectNode planRequest=Profiles.JSON.createObjectNode().put("connectionId",id).put("database","approval_test").put("schema",schema);
                QueryJobs.Job estimated=finish(jobs,jobs.estimatedPlan("agent:workflow",id,scope,planRequest,"SELECT id, title FROM "+table+" WHERE title = ?",Profiles.JSON.createArrayNode().add("synthetic-value-must-not-be-in-snapshot"),false));
                QueryJobs.Job analyzed=finish(jobs,jobs.estimatedPlan("agent:workflow",id,scope,planRequest,"SELECT id, title FROM "+table+" WHERE title = ?",Profiles.JSON.createArrayNode().add("synthetic-value-must-not-be-in-snapshot"),true));
                assertFalse(estimated.result.path("analysisIncluded").asBoolean());assertTrue(analyzed.result.path("analysisIncluded").asBoolean());
                assertFalse(QueryPlanAnalysis.compare(estimated.id,estimated.result,analyzed.id,analyzed.result,100).path("costsCompared").asBoolean());
                System.out.println("WORKFLOW_FULL_VERIFIED "+vendor+" "+migrated.result.path("version").toString());
            }
        }
    }
    private static ObjectNode draft(DatabaseCatalog.Template template,String vendor,String name,String url){
        return Profiles.JSON.createObjectNode().put("name",name).put("templateId",vendor).put("url",url).put("driverClass",template.driver())
                .put("jar",System.getenv("DBA_REUSABLE_JAR")).put("username",System.getenv("DBA_REUSABLE_USER")).put("password",System.getenv("DBA_REUSABLE_PASSWORD")).put("readOnly",false);
    }
    private static ObjectNode migrationRequest(String connectionId,MigrationPlans.Plan plan){
        ObjectNode request=Profiles.JSON.createObjectNode().put("connectionId",connectionId).put("migrationPlanId",plan.id)
                .put("migrationSchemaFingerprint",plan.value.path("schemaFingerprint").asText()).put("migrationTransactional",plan.value.path("transactional").asBoolean())
                .put("migrationRehearsal",plan.value.path("rehearsal").asBoolean());
        request.set("migrationScope",plan.sourceScope.deepCopy());request.set("migrationStatements",plan.value.path("statements").deepCopy());request.set("migrationChecks",plan.value.path("checks").deepCopy());return request;
    }
    private static QueryJobs.Job finish(QueryJobs jobs,JsonNode submitted)throws Exception{
        var job=jobs.require("agent:workflow",submitted.path("id").asText());long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(job.finished==0&&System.nanoTime()<end)Thread.sleep(20);assertTrue(job.finished>0);assertEquals("complete",job.state,job.json().toString());return job;
    }
}
