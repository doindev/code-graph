package io.doindev.codegraph.dba;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DBA_GRID_OWNER",matches="cgraph-grid-qa-[a-f0-9]+")
class GridVendorTest {
    @TempDir Path root;
    @Test void transactionalRowsPagingExportsAndViewCheckOptions()throws Exception{
        String vendor=System.getenv("DBA_GRID_VENDOR"),schema=vendor.equals("postgresql")?"public":vendor.equals("sqlserver")?"dbo":"grid_test";
        DbaConfig config=new DbaConfig(root,128L<<20,2,1000,100,30);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,o->true);var grids=new GridResults(jobs,connections,()->config,o->true)){
            jobs.grids=grids;
            var profile=Profiles.JSON.createObjectNode().put("name","Owned "+vendor+" grid fixture").put("templateId",vendor).put("jar",System.getenv("DBA_GRID_JAR")).put("driverClass",System.getenv("DBA_GRID_DRIVER")).put("url",System.getenv("DBA_GRID_URL")).put("username",System.getenv("DBA_GRID_USER")).put("password",System.getenv("DBA_GRID_PASSWORD"));
            String id=profiles.put(null,profile).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){
                c.setReadOnly(false);c.setAutoCommit(true);s.execute("CREATE TABLE "+schema+".grid_items(id INT PRIMARY KEY, label VARCHAR(80), amount DECIMAL(18,3) DEFAULT 4)");
                try(var insert=c.prepareStatement("INSERT INTO "+schema+".grid_items VALUES(?,?,?)")){for(int i=1;i<=451;i++){insert.setInt(1,i);insert.setString(2,"row-"+i);insert.setBigDecimal(3,new java.math.BigDecimal(i+".125"));insert.addBatch();}insert.executeBatch();}
                JsonNode result=finish(jobs,jobs.humanQuery("human",id,"SELECT * FROM "+schema+".grid_items",Profiles.JSON.createArrayNode(),false)).path("results").get(0),grid=result.path("grid");
                assertTrue(grid.path("capabilities").path("edit").asBoolean(),grid.path("capabilities").toString());assertTrue(grid.path("capabilities").path("page").asBoolean(),grid.path("capabilities").toString());
                var draft=Profiles.JSON.createObjectNode().put("revision",1);var changes=draft.putArray("changes");
                changes.addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","saved");
                changes.addObject().put("rowId",grid.path("rowIds").get(1).asText()).put("operation","delete").putObject("values");
                var insert=changes.addObject().put("rowId","new:1").put("operation","insert").putObject("values");insert.putObject("c1").put("kind","value").put("value","999");insert.putObject("c2").put("kind","null");insert.putObject("c3").put("kind","default");
                String gridId=grid.path("id").asText();JsonNode plan=finish(jobs,grids.operation("human",gridId,"prepare",draft));assertTrue(plan.path("deletes").asBoolean());finish(jobs,grids.operation("human",gridId,"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",plan.path("planId").asText()).put("confirmed",true)));
                try(var rs=s.executeQuery("SELECT label FROM "+schema+".grid_items WHERE id=1")){assertTrue(rs.next());assertEquals("saved",rs.getString(1));}
                JsonNode tail=finish(jobs,grids.operation("human",gridId,"page",Profiles.JSON.createObjectNode().put("revision",2).put("direction","last").put("limit",200)));assertEquals(200,tail.path("rows").size());assertEquals("999",tail.path("rows").get(199).get(0).asText());
                var exp=Profiles.JSON.createObjectNode().put("revision",3).put("scope","query").put("format","xlsx");exp.putArray("columns").add("c1").add("c2").add("c3");JsonNode export=finish(jobs,grids.operation("human",gridId,"export",exp));assertEquals(451,export.path("rows").asInt());grids.exports.remove("human",export.path("exportId").asText());
                s.execute("CREATE VIEW "+schema+".grid_view AS SELECT id,label,amount FROM "+schema+".grid_items WHERE id < 100 WITH CHECK OPTION");
                result=finish(jobs,jobs.humanQuery("human",id,"SELECT * FROM "+schema+".grid_view",Profiles.JSON.createArrayNode(),false)).path("results").get(0);grid=result.path("grid");
                if(!vendor.equals("sqlserver")){
                    assertTrue(grid.path("capabilities").path("edit").asBoolean(),grid.path("capabilities").toString());
                    draft=Profiles.JSON.createObjectNode().put("revision",1);draft.putArray("changes").addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject("c1").put("kind","value").put("value","888");
                    plan=finish(jobs,grids.operation("human",grid.path("id").asText(),"prepare",draft));assertTrue(plan.path("statements").get(0).path("sql").asText().contains("grid_view"));
                    var failed=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",plan.path("planId").asText())));assertEquals("failed",failed.path("state").asText(),failed.toString());
                    try(var rs=s.executeQuery("SELECT COUNT(*) FROM "+schema+".grid_items WHERE id=1")){rs.next();assertEquals(1,rs.getInt(1));}
                }else assertFalse(grid.path("capabilities").path("edit").asBoolean(),"Unverified SQL Server view mappings must remain read-only");
                // A concurrent change to the second edited row must roll back the first update.
                result=finish(jobs,jobs.humanQuery("human",id,"SELECT * FROM "+schema+".grid_items ORDER BY id",Profiles.JSON.createArrayNode(),false)).path("results").get(0);grid=result.path("grid");
                draft=Profiles.JSON.createObjectNode().put("revision",1);changes=draft.putArray("changes");
                for(int row:new int[]{1,0})changes.addObject().put("rowId",grid.path("rowIds").get(row).asText()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","must roll back");
                plan=finish(jobs,grids.operation("human",grid.path("id").asText(),"prepare",draft));
                s.executeUpdate("UPDATE "+schema+".grid_items SET label='external' WHERE id=1");
                var conflict=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",plan.path("planId").asText())));
                assertEquals("failed",conflict.path("state").asText(),conflict.toString());
                try(var rs=s.executeQuery("SELECT label FROM "+schema+".grid_items WHERE id=3")){rs.next();assertEquals("row-3",rs.getString(1));}
                // A separately selected database must never update the saved profile's default.
                s.execute("CREATE DATABASE grid_other");
                String otherSchema=vendor.equals("mysql")||vendor.equals("mariadb")?"grid_other":schema;
                try(var other=connections.target(id,"grid_other")){var oc=other.connection();oc.setReadOnly(false);oc.setAutoCommit(true);try(var os=oc.createStatement()){
                    os.execute("CREATE TABLE "+otherSchema+".grid_items(id INT PRIMARY KEY, label VARCHAR(80), amount DECIMAL(18,3))");
                    os.execute("INSERT INTO "+otherSchema+".grid_items VALUES(1,'other database',1)");
                }}
                result=finish(jobs,jobs.tableQuery("human",id,"SELECT * FROM "+otherSchema+".grid_items",Profiles.JSON.createArrayNode(),"grid_other")).path("results").get(0);grid=result.path("grid");
                assertEquals("grid_other",grid.path("database").asText(),grid.toString());
                draft=Profiles.JSON.createObjectNode().put("revision",1);draft.putArray("changes").addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","targeted");
                plan=finish(jobs,grids.operation("human",grid.path("id").asText(),"prepare",draft));finish(jobs,grids.operation("human",grid.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",plan.path("planId").asText())));
                try(var rs=s.executeQuery("SELECT label FROM "+schema+".grid_items WHERE id=1")){rs.next();assertEquals("external",rs.getString(1));}
                try(var other=connections.target(id,"grid_other");var os=other.connection().createStatement();var rs=os.executeQuery("SELECT label FROM "+otherSchema+".grid_items WHERE id=1")){rs.next();assertEquals("targeted",rs.getString(1));}
                System.out.println("GRID_VENDOR_VERIFIED "+vendor+" "+c.getMetaData().getDatabaseProductVersion()+" driver "+c.getMetaData().getDriverVersion());
            }
        }
    }
    static JsonNode finish(QueryJobs jobs,ObjectNode start)throws Exception{JsonNode result=HumanSqlTest.finish(jobs,"human",start);assertEquals("complete",result.path("state").asText(),result.toString());return result.path("result");}
}
