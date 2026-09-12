package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit network gate; disposable in-memory databases, no source or saved profile changes. */
@EnabledIfSystemProperty(named="dba.catalog.integration",matches="true")
class MetadataEmbeddedTest {
    @TempDir Path root;
    @Test @Timeout(240) void embeddedDriverCatalogs()throws Exception{
        for(String engine:List.of("hsqldb","sqlite","duckdb")){
            Path data=root.resolve(engine);var t=DatabaseCatalog.get(engine);
            try(var profiles=new Profiles(data,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(data,64L<<20,2,1000,100,30),o->true)){
                var bundles=new DriverBundles(data);var coordinates=Profiles.JSON.createObjectNode().put("templateId",engine).put("groupId",t.group()).put("artifactId",t.artifact());var status=bundles.status(coordinates,()->false);assertTrue(status.path("latestAvailable").asBoolean());coordinates.put("version",status.path("latestVersion").asText());var bundle=bundles.install(coordinates,()->false,p->{});
                var input=Profiles.JSON.createObjectNode().put("name",engine).put("templateId",engine).put("url",t.url()).put("driverClass",t.driver()).put("username",engine.equals("hsqldb")?"SA":"");input.set("jars",bundle.path("jars"));String id=profiles.put(null,input).path("id").asText();
                String schema=engine.equals("hsqldb")?"PUBLIC":"main";
                try(var c=connections.open(id);var st=c.createStatement()){c.setReadOnly(false);st.execute("CREATE TABLE catalog_item(id int PRIMARY KEY, other int UNIQUE, CHECK(id>0))");st.execute("CREATE TABLE catalog_child(a int REFERENCES catalog_item(id), b int REFERENCES catalog_item(other))");st.execute("CREATE VIEW catalog_view AS SELECT * FROM catalog_item");c.commit();}
                assertFalse(MetadataTreeTest.browse(jobs,id,"schemas","").path("nodes").isEmpty(),engine);
                for(String label:VendorMetadata.categories(engine)){var result=MetadataTreeTest.browse(jobs,id,VendorMetadata.kind(label),schema);if(label.equals("Tables"))assertTrue(result.toString().toLowerCase(Locale.ROOT).contains("catalog_item"),result.toString());var keys=new HashSet<String>();for(var node:result.path("nodes"))assertTrue(keys.add(node.path("key").asText()),engine+" duplicate identity: "+node);}
                var tables=MetadataTreeTest.browse(jobs,id,"tables",schema);for(var node:tables.path("nodes"))if(node.path("name").asText().equalsIgnoreCase("catalog_item")){
                    var relation=((com.fasterxml.jackson.databind.node.ObjectNode)node).deepCopy().put("relationType","table");
                    var groups=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,relation));assertEquals("complete",groups.path("state").asText(),groups.toString());
                    for(var group:groups.path("result").path("nodes")){var detail=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,group));assertEquals("complete",detail.path("state").asText(),engine+":"+group+":"+detail);if(group.path("kind").asText().equals("table_references"))assertEquals(2,detail.path("result").path("nodes").size(),engine+":"+detail);if(group.path("kind").asText().equals("table_constraints"))assertFalse(detail.path("result").path("nodes").isEmpty(),engine+":"+detail);}
                }
                var plan=HumanSqlTest.finish(jobs,"human",jobs.browserExplain("human",id,"SELECT id FROM catalog_item WHERE id > ?",Profiles.JSON.createArrayNode().add(0),""));assertEquals("complete",plan.path("state").asText(),engine+":"+plan);assertTrue(plan.path("result").path("estimated").asBoolean());System.out.println("EXPLAIN_LIVE_VERIFIED "+engine);
                System.out.println("CATALOG_VERIFIED "+engine+" "+coordinates.path("version").asText());
            }
        }
    }
}
