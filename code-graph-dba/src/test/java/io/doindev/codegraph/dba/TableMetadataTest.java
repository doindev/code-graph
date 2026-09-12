package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TableMetadataTest {
    @TempDir Path root;
    @Test void capabilitiesOnlyAdvertiseImplementedCategories(){
        assertEquals(10,TableMetadata.categories("postgresql",16).size());
        assertFalse(TableMetadata.categories("h2",2).contains("Partitions"));
        assertFalse(TableMetadata.categories("mysql",8).contains("Rules"));
        assertFalse(TableMetadata.categories("sqlserver",12).contains("Policies"));
        assertTrue(TableMetadata.categories("sqlserver",16).contains("Policies"));
        assertFalse(TableMetadata.categories("snowflake",9).contains("Indexes"));
        for(String engine:List.of("h2","hsqldb","oracle","db2","mysql","mariadb","sqlserver","duckdb","snowflake"))
            for(String label:TableMetadata.categories(engine,16))if(!Set.of("Columns","Foreign Keys","References","Indexes").contains(label)&&!(engine.equals("snowflake")&&label.equals("Policies")))assertNotNull(TableMetadata.vendorSql(engine,VendorMetadata.kind(label)),engine+":"+label);
    }
    @Test void h2CategoriesAreLazyScopedSortedAndPopulated()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,20,5,10),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var anchor=connections.open(id);var s=anchor.createStatement()){
                anchor.setAutoCommit(true);s.execute("CREATE SCHEMA DETAIL");s.execute("CREATE TABLE DETAIL.PARENT(Z INT PRIMARY KEY, A VARCHAR(20) UNIQUE, CONSTRAINT POSITIVE CHECK(Z>0))");s.execute("CREATE TABLE DETAIL.CHILD(ID INT PRIMARY KEY, P INT, CONSTRAINT PARENT_REF FOREIGN KEY(P) REFERENCES DETAIL.PARENT(Z))");s.execute("CREATE VIEW DETAIL.V AS SELECT * FROM DETAIL.PARENT");
                var relation=Profiles.JSON.createObjectNode().put("kind","relation").put("relationType","table").put("name","PARENT").put("schema","DETAIL");
                var groups=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,relation));assertEquals("complete",groups.path("state").asText(),groups.toString());
                var labels=new ArrayList<String>();for(var node:groups.path("result").path("nodes")){labels.add(node.path("name").asText());assertTrue(node.path("branch").asBoolean());assertEquals("PARENT",node.path("table").asText());
                    var details=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,node));assertEquals("complete",details.path("state").asText(),node+":"+details);
                    if(node.path("kind").asText().equals("table_columns")){assertEquals("A · CHARACTER VARYING",details.path("result").path("nodes").get(0).path("name").asText());assertFalse(details.path("result").path("nodes").get(0).path("branch").asBoolean());}
                    if(Set.of("table_constraints","table_references","table_indexes","table_dependencies").contains(node.path("kind").asText()))assertFalse(details.path("result").path("nodes").isEmpty(),node.toString());
                }
                assertEquals(TableMetadata.categories("h2",2),labels);
                var columns=relation.deepCopy().put("kind","table_columns").put("table","PARENT");
                var selected=MetadataActionsTest.selection(jobs,id,columns,"A · CHARACTER VARYING");assertEquals("A",MetadataActionsTest.preview(jobs,id,selected).path("name").asText(),"Column actions use raw names, not the type annotation");
            }
        }
    }
}
