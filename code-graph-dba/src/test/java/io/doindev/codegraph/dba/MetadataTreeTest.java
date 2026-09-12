package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MetadataTreeTest {
    @TempDir Path root;
    static JsonNode browse(QueryJobs jobs,String id,String kind,String schema)throws Exception{
        var status=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,Profiles.JSON.createObjectNode().put("kind",kind).put("schema",schema)));
        assertEquals("complete",status.path("state").asText(),status.toString());return status.path("result");
    }
    @Test void h2LazyHierarchyLiteralNamesPaginationAndBrowserOnly()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,1000,100,10),o->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                st.execute("CREATE SCHEMA \"test_%\"");st.execute("CREATE TABLE \"test_%\".items(id int primary key, title varchar(50))");st.execute("CREATE VIEW \"test_%\".example AS SELECT * FROM \"test_%\".items");st.execute("CREATE SEQUENCE \"test_%\".counter");
                for(int i=0;i<205;i++)st.execute("CREATE TABLE \"test_%\".page_"+String.format("%03d",i)+"(id int)");
                st.execute("CREATE TABLE \"test_%\".\"a\"(id int)");st.execute("CREATE TABLE \"test_%\".\"Z\"(id int)");
            }
            assertEquals("Schemas",browse(jobs,id,"root","").path("nodes").get(0).path("name").asText());
            assertTrue(browse(jobs,id,"schemas","").toString().contains("test_%"));
            assertEquals(VendorMetadata.categories("h2").length,browse(jobs,id,"schema","test_%").path("nodes").size());
            for(String category:VendorMetadata.categories("h2"))browse(jobs,id,VendorMetadata.kind(category),"test_%");
            var tables=browse(jobs,id,"tables","test_%");assertEquals(200,tables.path("nodes").size());assertEquals(200,tables.path("nextOffset").asInt());assertEquals("a",tables.path("nodes").get(0).path("name").asText());
            var status=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,Profiles.JSON.createObjectNode().put("kind","tables").put("schema","test_%").put("offset",200)));assertEquals("complete",status.path("state").asText());assertEquals(8,status.path("result").path("nodes").size());assertEquals("Z",status.path("result").path("nodes").get(7).path("name").asText());
            status=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,Profiles.JSON.createObjectNode().put("kind","relation").put("schema","test_%").put("name","ITEMS")));assertEquals(2,status.path("result").path("nodes").size());assertFalse(status.path("result").path("nodes").get(0).path("branch").asBoolean());
            assertTrue(browse(jobs,id,"tables","test_' OR 1=1 --").path("nodes").isEmpty());
            assertThrows(SecurityException.class,()->jobs.metadataTree("agent:test",id,Profiles.JSON.createObjectNode()));
            assertThrows(IllegalArgumentException.class,()->MetadataTree.request(Profiles.JSON.createObjectNode().put("offset",-1)));
        }
    }
    @Test void allNamedEnginesHaveDistinctApplicableGroups(){
        assertEquals(List.of("Database Links","Functions","Indexes","Java","Jobs","Materialized Views","Packages","Procedures","Queues","Scheduler","Schema Triggers","Sequences","Synonyms","Table Triggers","Tables","Types","Views"),List.of(VendorMetadata.categories("oracle")));
        for(String e:List.of("oracle","db2","sqlserver","mysql","mariadb","snowflake","sqlite","duckdb","h2","hsqldb","jdbc")){var groups=List.of(VendorMetadata.categories(e));assertEquals(groups.size(),new HashSet<>(groups).size());assertTrue(groups.contains("Tables"));assertTrue(groups.contains("Views"));}
        assertEquals("sqlserver",VendorMetadata.engine("Microsoft SQL Server"));assertEquals("hsqldb",VendorMetadata.engine("HSQL Database Engine"));assertEquals("db2",VendorMetadata.engine("DB2/LINUXX8664"));
    }
    @Test void postgresDatabaseUrlsPreserveEndpointAndProperties(){
        assertEquals("jdbc:postgresql://localhost:5432/another%20db?sslmode=require",Connections.postgresDatabaseUrl("jdbc:postgresql://localhost:5432/original?sslmode=require","another db"));
        assertEquals("jdbc:postgresql:next",Connections.postgresDatabaseUrl("jdbc:postgresql:previous","next"));
        assertEquals("jdbc:postgresql://[::1]:5432,a:5432/a%2Fb",Connections.postgresDatabaseUrl("jdbc:postgresql://[::1]:5432,a:5432/original","a/b"));
        assertThrows(IllegalArgumentException.class,()->Connections.postgresDatabaseUrl("jdbc:mysql://localhost/db","x"));
    }
}
