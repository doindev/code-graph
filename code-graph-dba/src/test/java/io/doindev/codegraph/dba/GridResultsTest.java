package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GridResultsTest {
    @TempDir Path root;
    @Test void pagesAreOrderedBoundedAndLastIsAFullTail()throws Exception{
        DbaConfig config=new DbaConfig(root,128L<<20,2,1000,100,15);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;String id=profiles.put(null,new DbaTest().input().deepCopy()).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){
                c.setAutoCommit(true);s.execute("CREATE TABLE GRID_PAGE(ID INT PRIMARY KEY, LABEL VARCHAR(50))");s.execute("INSERT INTO GRID_PAGE SELECT X, 'row-'||X FROM SYSTEM_RANGE(1,451)");
                var query=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM PUBLIC.GRID_PAGE",Profiles.JSON.createArrayNode(),false));assertEquals("complete",query.path("state").asText(),query.toString());
                var result=query.path("result").path("results").get(0);assertEquals(200,result.path("rows").size());assertEquals("1",result.path("rows").get(0).get(0).asText());assertTrue(result.path("sourceSql").asText().contains("ORDER BY"));
                String grid=result.path("grid").path("id").asText();long revision=1;
                var next=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid,"page",Profiles.JSON.createObjectNode().put("revision",revision++).put("direction","next").put("limit",200)));assertEquals("complete",next.path("state").asText(),next.toString());assertEquals("201",next.path("result").path("rows").get(0).get(0).asText());
                var last=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid,"page",Profiles.JSON.createObjectNode().put("revision",revision).put("direction","last").put("limit",200)));assertEquals("complete",last.path("state").asText(),last.toString());assertEquals(200,last.path("result").path("rows").size());assertEquals("252",last.path("result").path("rows").get(0).get(0).asText());assertEquals("451",last.path("result").path("rows").get(199).get(0).asText());
                var export=Profiles.JSON.createObjectNode().put("revision",3).put("format","csv").put("scope","query").put("spreadsheetSafe",true);export.putArray("columns").add("c2").add("c1");
                var complete=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid,"export",export));assertEquals("complete",complete.path("state").asText(),complete.toString());assertEquals(451,complete.path("result").path("rows").asInt());String exportId=complete.path("result").path("exportId").asText();
                assertThrows(SecurityException.class,()->grids.exports.remove("other",exportId));grids.exports.remove("human",exportId);try(var files=java.nio.file.Files.list(root.resolve("grid-exports"))){assertEquals(0,files.count());}
            }
        }
    }
    @Test void failedBatchRollsBackAllRowsAndUnknownColumnsAreRejected()throws Exception{
        DbaConfig config=new DbaConfig(root,128L<<20,2,1000,100,15);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){
                c.setAutoCommit(true);s.execute("CREATE TABLE GRID_ATOMIC(ID INT PRIMARY KEY, LABEL VARCHAR(40) UNIQUE)");s.execute("INSERT INTO GRID_ATOMIC VALUES(1,'a'),(2,'b')");
                var query=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM PUBLIC.GRID_ATOMIC ORDER BY ID",Profiles.JSON.createArrayNode(),false));
                var grid=query.path("result").path("results").get(0).path("grid");assertTrue(grid.path("capabilities").path("edit").asBoolean(),grid.toString());
                var request=Profiles.JSON.createObjectNode().put("revision",1);var changes=request.putArray("changes");
                for(var row:grid.path("rowIds"))changes.addObject().put("rowId",row.asText()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","duplicate");
                var prepared=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"prepare",request));assertEquals("complete",prepared.path("state").asText(),prepared.toString());
                var failed=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",prepared.path("result").path("planId").asText())));
                assertEquals("failed",failed.path("state").asText(),failed.toString());try(var rs=s.executeQuery("SELECT LABEL FROM GRID_ATOMIC ORDER BY ID")){rs.next();assertEquals("a",rs.getString(1));rs.next();assertEquals("b",rs.getString(1));}
                ((ObjectNode)changes.get(0).path("values")).set("not_a_column",Profiles.JSON.createObjectNode().put("kind","value").put("value","bad"));
                var invalid=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"prepare",request));assertEquals("failed",invalid.path("state").asText());assertTrue(invalid.path("error").asText().contains("Unknown"));
            }
        }
    }
    @Test void ownedSnapshotStagesAtomicChangesAndDetectsConflicts()throws Exception {
        DbaConfig config=new DbaConfig(root,128L<<20,2,1000,100,15);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){
                c.setAutoCommit(true);s.execute("CREATE TABLE PUBLIC.GRID_TEST(ID INT PRIMARY KEY, NAME VARCHAR(40), AMOUNT DECIMAL(18,3) DEFAULT 1)");
                s.execute("INSERT INTO PUBLIC.GRID_TEST VALUES(1,'first',1.234),(2,'second',2.345)");
                var output=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM PUBLIC.GRID_TEST ORDER BY ID",Profiles.JSON.createArrayNode(),false));
                assertEquals("complete",output.path("state").asText(),output.toString());
                var grid=output.path("result").path("results").get(0).path("grid");assertTrue(grid.path("capabilities").path("edit").asBoolean(),grid.toString());
                String gridId=grid.path("id").asText();assertThrows(SecurityException.class,()->grids.require("other",gridId));
                var request=Profiles.JSON.createObjectNode().put("revision",grid.path("revision").asLong());var changes=request.putArray("changes");
                changes.addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","updated");
                var prepared=HumanSqlTest.finish(jobs,"human",grids.operation("human",gridId,"prepare",request));assertEquals("complete",prepared.path("state").asText(),prepared.toString());
                try(var rs=s.executeQuery("SELECT NAME FROM PUBLIC.GRID_TEST WHERE ID=1")){rs.next();assertEquals("first",rs.getString(1));}
                var apply=Profiles.JSON.createObjectNode().put("revision",grid.path("revision").asLong()).put("planId",prepared.path("result").path("planId").asText());
                var saved=HumanSqlTest.finish(jobs,"human",grids.operation("human",gridId,"apply",apply));assertEquals("complete",saved.path("state").asText(),saved.toString());
                try(var rs=s.executeQuery("SELECT NAME FROM PUBLIC.GRID_TEST WHERE ID=1")){rs.next();assertEquals("updated",rs.getString(1));}
                var replay=apply;assertThrows(IllegalArgumentException.class,()->grids.operation("human",gridId,"apply",replay));
                var refreshed=HumanSqlTest.finish(jobs,"human",grids.operation("human",gridId,"page",Profiles.JSON.createObjectNode().put("revision",2).put("direction","first").put("limit",1)));
                assertEquals("complete",refreshed.path("state").asText(),refreshed.toString());assertEquals(1,refreshed.path("result").path("rows").size());
                grid=refreshed.path("result").path("grid");request=Profiles.JSON.createObjectNode().put("revision",grid.path("revision").asLong());request.putArray("changes").addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","delete").putObject("values");
                prepared=HumanSqlTest.finish(jobs,"human",grids.operation("human",gridId,"prepare",request));assertEquals("complete",prepared.path("state").asText(),prepared.toString());s.executeUpdate("UPDATE PUBLIC.GRID_TEST SET NAME='concurrent' WHERE ID=1");
                apply=Profiles.JSON.createObjectNode().put("revision",grid.path("revision").asLong()).put("planId",prepared.path("result").path("planId").asText()).put("confirmed",true);
                saved=HumanSqlTest.finish(jobs,"human",grids.operation("human",gridId,"apply",apply));assertEquals("failed",saved.path("state").asText());
                try(var rs=s.executeQuery("SELECT COUNT(*) FROM PUBLIC.GRID_TEST")){rs.next();assertEquals(2,rs.getInt(1));}
                grids.release("human",gridId);assertThrows(SecurityException.class,()->grids.require("human",gridId));
            }
        }
    }
}
