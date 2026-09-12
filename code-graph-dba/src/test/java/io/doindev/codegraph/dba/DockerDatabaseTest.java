package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the owned-container script supplies these environment variables. */
@EnabledIfEnvironmentVariable(named="DBA_MATRIX_OWNER",matches="code-graph-dba-matrix-[a-f0-9]+")
class DockerDatabaseTest {
    @TempDir Path root;
    @Test @Timeout(120) void ephemeralTestVersionQueryPoolReadMetadataAndCleanup()throws Exception{
        String template=System.getenv("DBA_MATRIX_TEMPLATE");var t=DatabaseCatalog.get(template);var vault=new DbaTest.MemoryVault();
        try(var profiles=new Profiles(root,vault);var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,10,100,30),o->true)){
            var setup=new ConnectionSetup(profiles,jobs);var coordinates=Profiles.JSON.createObjectNode().put("groupId",t.group()).put("artifactId",t.artifact()).put("templateId",template);
            var status=setup.bundles.status(coordinates,()->false);assertTrue(status.path("latestAvailable").asBoolean());coordinates.put("version",status.path("latestVersion").asText());var bundle=setup.bundles.install(coordinates,()->false,p->{});
            var input=Profiles.JSON.createObjectNode().put("name","disposable "+template).put("templateId",template).put("driverClass",t.driver()).put("url",System.getenv("DBA_MATRIX_URL")).put("username",System.getenv("DBA_MATRIX_USER")).put("password",System.getenv("DBA_MATRIX_PASSWORD"));input.set("jars",bundle.path("jars"));
            var tested=ConnectionSetupTest.await(jobs,"browser",setup.operation("browser","draft-test",input));assertEquals("complete",tested.path("state").asText(),tested.toString());assertTrue(tested.path("result").path("connected").asBoolean());assertFalse(tested.path("result").path("versionResult").path("rows").isEmpty());assertTrue(vault.secrets.isEmpty());assertTrue(profiles.publicList().isEmpty());
            input.put("receipt",tested.path("result").path("receipt").asText());String id=setup.save("browser",null,input).path("id").asText();
            var query=ConnectionSetupTest.await(jobs,"browser",jobs.query("browser",id,"SELECT ? AS answer"+(template.equals("oracle")?" FROM dual":""),Profiles.JSON.createArrayNode().add(42)));assertEquals("complete",query.path("state").asText(),query.toString());assertEquals("42",query.path("result").path("rows").get(0).get(0).asText());
            var metadata=ConnectionSetupTest.await(jobs,"browser",jobs.metadata("browser",id,null,null));assertEquals("complete",metadata.path("state").asText(),metadata.toString());
            MetadataTreeTest.browse(jobs,id,"root","");MetadataTreeTest.browse(jobs,id,VendorMetadata.catalogs(template)?"databases":"schemas","");
            String catalogSchema=template.equals("oracle")?"SYSTEM":template.equals("postgresql")?"public":"mysql";
            MetadataTreeTest.browse(jobs,id,"schema",catalogSchema);
            if(!template.equals("postgresql"))for(String label:VendorMetadata.categories(template))MetadataTreeTest.browse(jobs,id,VendorMetadata.kind(label),catalogSchema);
            if(template.equals("oracle"))for(String kind:java.util.List.of("scheduler_jobs","scheduler_programs","scheduler_schedules","scheduler_chains"))MetadataTreeTest.browse(jobs,id,kind,catalogSchema);
            var plan=HumanSqlTest.finish(jobs,"human",jobs.browserExplain("human",id,"SELECT ? AS answer"+(template.equals("oracle")?" FROM dual":""),Profiles.JSON.createArrayNode().add(42),""));assertEquals("complete",plan.path("state").asText(),plan.toString());assertTrue(plan.path("result").path("estimated").asBoolean());assertFalse(plan.path("result").path("raw").path("rows").isEmpty());System.out.println("EXPLAIN_LIVE_VERIFIED "+template);
            connections.remove(id);profiles.remove(id);assertEquals(0,connections.count());assertTrue(vault.secrets.isEmpty());
            System.out.println("DATABASE_VERIFIED "+template+" "+tested.path("result").path("version").asText());
        }
    }
}
