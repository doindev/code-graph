package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SchemaSnapshotsTest {
    @TempDir Path directory;

    private static QueryJobs.Job completed(QueryJobs jobs,String owner,JsonNode submitted)throws Exception{
        var job=jobs.require(owner,submitted.path("id").asText());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(job.finished==0&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals("complete",job.state,job.json().toString());assertTrue(job.finished>0);return job;
    }
    private static WorkflowTargets.Target target(ObjectNode profile,String database){
        ObjectNode scope=ApprovalScope.resolve(profile,Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database",database).put("schema","PUBLIC"));
        return new WorkflowTargets.Target(profile,scope,Profiles.JSON.createObjectNode(),"Test fixture authorization");
    }
    @Test void observesStructureWithoutRowsAndComparesWithoutInferringDrops()throws Exception{
        var config=new DbaConfig(directory,128L<<20,2,100,100,10);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,_ ->true)){
            String id=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(var c=connections.open(id);var statement=c.createStatement()){
                c.setAutoCommit(true);statement.execute("CREATE TABLE ITEMS(ID INT PRIMARY KEY, LABEL VARCHAR(40))");
                statement.execute("INSERT INTO ITEMS VALUES (1, 'record-data-must-not-be-captured')");
                var target=target(profiles.get(id),c.getCatalog());String owner="agent:fixture";
                var first=completed(jobs,owner,jobs.captureSchema(owner,target,()->{}));
                var same=completed(jobs,owner,jobs.captureSchema(owner,target,()->{}));
                assertEquals(first.result.path("fingerprint"),same.result.path("fingerprint"));
                assertFalse(first.result.toString().contains("record-data-must-not-be-captured"));
                assertEquals(0,SchemaSnapshots.compare(first.result,same.result,100).path("differences").size());
                statement.execute("ALTER TABLE ITEMS ADD EXTRA INTEGER DEFAULT 3");
                var changed=completed(jobs,owner,jobs.captureSchema(owner,target,()->{}));
                var comparison=SchemaSnapshots.compare(first.result,changed.result,100);
                assertTrue(java.util.stream.StreamSupport.stream(comparison.path("differences").spliterator(),false).anyMatch(d->d.path("name").asText().equals("ITEMS")&&d.path("kind").asText().equals("structural_difference")),comparison.toPrettyString());
                assertTrue(comparison.toString().contains("EXTRA"));assertFalse(comparison.path("migrationGenerated").asBoolean());
                statement.execute("DROP TABLE ITEMS");
                var removed=completed(jobs,owner,jobs.captureSchema(owner,target,()->{}));
                var missing=SchemaSnapshots.compare(changed.result,removed.result,100);
                assertEquals("observed_on_one_side_only",missing.path("differences").get(0).path("kind").asText());
                assertFalse(missing.path("inventoryComplete").asBoolean());
            }
        }
    }
    @Test void rowAllowanceProducesExplicitPartialSnapshot()throws Exception{
        var config=new DbaConfig(directory,64L<<20,1,100,5,10);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,_ ->true)){
            String id=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(var c=connections.open(id);var statement=c.createStatement()){
                c.setAutoCommit(true);statement.execute("CREATE TABLE WIDE(A INT,B INT,C INT,D INT,E INT,F INT)");
                var job=completed(jobs,"agent:a",jobs.captureSchema("agent:a",target(profiles.get(id),c.getCatalog()),()->{}));
                assertTrue(job.result.path("truncated").asBoolean());assertFalse(job.result.path("absenceProvesRemoval").asBoolean());
                assertTrue(job.bytes<1<<20);assertTrue(job.result.path("objects").isEmpty());
            }
        }
    }
    @Test void leasesRetainAccountedMemoryAcrossExpiryAndReleaseIdempotently()throws Exception{
        var config=new DbaConfig(directory,64L<<20,1,100,100,10);var alive=new AtomicBoolean(true);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,_ ->alive.get())){
            var snapshot=completed(jobs,"agent:a",jobs.local("agent:a",job->Profiles.JSON.createObjectNode().put("fixture",true),()->{}));
            assertThrows(IllegalArgumentException.class,()->jobs.retainResults("agent:b",List.of(snapshot.id)));
            Runnable release=jobs.retainResults("agent:a",List.of(snapshot.id,snapshot.id));
            long reserved=jobs.telemetry().path("reservedBytes").asLong();snapshot.finished=1;alive.set(false);jobs.reap();
            assertEquals(reserved,jobs.telemetry().path("reservedBytes").asLong());
            assertThrows(IllegalArgumentException.class,()->jobs.remove("agent:a",snapshot.id));
            release.run();release.run();jobs.reap();assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
    @Test void admissionFailureReleasesSnapshotLease()throws Exception{
        var config=new DbaConfig(directory,64L<<20,1,100,100,10);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,_ ->true)){
            var first=completed(jobs,"agent:a",jobs.local("agent:a",job->Profiles.JSON.createObjectNode(),()->{}));
            for(int i=0;i<4;i++)completed(jobs,"agent:a",jobs.local("agent:a",job->Profiles.JSON.createObjectNode(),()->{}));
            Runnable release=jobs.retainResults("agent:a",List.of(first.id));
            assertThrows(IllegalArgumentException.class,()->jobs.local("agent:a",job->Profiles.JSON.createObjectNode(),release));
            assertDoesNotThrow(()->jobs.remove("agent:a",first.id));
        }
    }
}
