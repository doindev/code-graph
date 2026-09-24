package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import io.doindev.codegraph.store.DocumentStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses only disposable databases created and cleaned by test-compare-vendors.ps1. */
@EnabledIfEnvironmentVariable(named="DBA_COMPARE_DISPOSABLE",matches="cgraph-compare-qa-[a-f0-9]+")
class CatalogScanVendorIntegrationTest {
    @TempDir Path directory;
    @Test @Timeout(90) void catalogRunCompletesAndPublishesAfterRefresh()throws Exception{
        String engine=System.getenv("DBA_COMPARE_VENDOR"),schema=engine.equals("postgresql")?"scan_status_qa":"compare_test";
        try(Profiles profiles=new Profiles(directory,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);
            CatalogCache cache=new CatalogCache(profiles,connections,()->DocumentStore.memory(64L<<20),System::currentTimeMillis)){
            String id=profiles.put(null,CompareVendorIntegrationTest.profile(engine,"Scan fixture",System.getenv("DBA_COMPARE_SOURCE"))).path("id").asText();
            try(Connection connection=connections.open(id);Statement statement=connection.createStatement()){
                connection.setAutoCommit(true);if(engine.equals("postgresql"))statement.execute("CREATE SCHEMA "+schema);
                statement.execute("CREATE TABLE "+schema+".scan_status_items(id INT PRIMARY KEY, label VARCHAR(80))");
                var target=cache.target(Profiles.JSON.createObjectNode().put("connectionId",id).put("database","compare_test").put("schema",schema));
                cache.request(target,Long.MAX_VALUE);JsonNode first=finish(cache,target);record(cache,target,engine+"-first");assertEquals(1,first.path("generation").asInt());assertTrue(first.path("lastRun").path("objects").asLong()>0);
                statement.execute("ALTER TABLE "+schema+".scan_status_items ADD COLUMN extra INT");cache.request(target,Long.MAX_VALUE);JsonNode second=finish(cache,target);record(cache,target,engine+"-second");
                assertEquals(2,second.path("generation").asInt());assertNotEquals(first.path("lastRun").path("id"),second.path("lastRun").path("id"));assertTrue(second.path("snapshot").path("changes").path("modified").asInt()>0,second.toPrettyString());
                assertEquals("publishing",second.path("lastRun").path("phase").asText());
                Path evidence=Path.of(System.getenv("DBA_COMPARE_EVIDENCE"));Files.createDirectories(evidence);Files.writeString(evidence.resolve(engine+"-scan-status.json"),second.toPrettyString());
                statement.execute("DROP TABLE "+schema+".scan_status_items");if(engine.equals("postgresql"))statement.execute("DROP SCHEMA "+schema);
                System.out.println("CATALOG_SCAN_STATUS_VERIFIED "+engine+" "+connection.getMetaData().getDatabaseProductVersion());
            }
        }
    }
    private void record(CatalogCache cache,CatalogCache.Target target,String name)throws Exception{
        var evidence=cache.status(target);var objects=evidence.putArray("objects");cache.find(target).read(publication->{publication.store.scan("o/",(key,value)->objects.add(ProjectContexts.json(value)));return null;});
        Path root=Path.of(System.getenv("DBA_COMPARE_EVIDENCE"));Files.createDirectories(root);Files.writeString(root.resolve(name+"-catalog.json"),evidence.toPrettyString());
    }
    private JsonNode finish(CatalogCache cache,CatalogCache.Target target)throws Exception{
        long deadline=System.nanoTime()+30_000_000_000L;JsonNode status;
        do{status=cache.status(target);if(!status.path("scanInProgress").asBoolean()){
            assertTrue(java.util.Set.of("succeeded","partial").contains(status.path("lastRun").path("state").asText()),status.toPrettyString());
            assertTrue(status.path("lastRun").path("finishedAt").asLong()>=status.path("lastRun").path("startedAt").asLong());return status;
        }Thread.sleep(25);}while(System.nanoTime()<deadline);throw new AssertionError(status);
    }
}
