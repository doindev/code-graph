package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class QueryBuilderTest {
    @TempDir Path root;
    @Test void importsOnlyLosslessModels(){
        assertTrue(QueryBuilder.importSql("SELECT a.ID FROM A a JOIN B b ON a.ID=b.ID").path("editable").asBoolean());
        assertTrue(QueryBuilder.importSql("SELECT a.ID FROM A a LEFT OUTER JOIN B b ON a.ID=b.ID").path("editable").asBoolean());
        for(String sql:new String[]{"SELECT a.ID, b.NAME FROM PUBLIC.A AS a INNER JOIN PUBLIC.B AS b ON a.ID = b.ID WHERE a.ID > 1 ORDER BY b.NAME DESC","SELECT DISTINCT ID FROM T","SELECT COUNT(*) FROM T GROUP BY ID HAVING COUNT(*) > 1"}){
            var imported=QueryBuilder.importSql(sql);assertTrue(imported.path("editable").asBoolean(),imported.toString());assertEquals(sql,imported.path("sql").asText());
        }
        for(String sql:new String[]{"SELECT * FROM T LIMIT 2","WITH t AS (SELECT 1) SELECT * FROM t","SELECT 1 UNION SELECT 2","SELECT * FROM (SELECT 1) q","DELETE FROM T","SELECT * FROM A NATURAL JOIN B","SELECT * FROM A LEFT SEMI JOIN B ON A.ID=B.ID","SELECT * FROM A; DROP TABLE A"}){
            var imported=QueryBuilder.importSql(sql);assertFalse(imported.path("editable").asBoolean(),sql);assertEquals(sql,imported.path("sql").asText());assertFalse(imported.has("model"));
        }
        assertThrows(IllegalArgumentException.class,()->QueryBuilder.importSql("x".repeat(16385)));
    }
    @Test void loadsViewsColumnsRelationshipsAndEnforcesTargets()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,100,100,10),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE TABLE PARENT(ID INT PRIMARY KEY, NAME VARCHAR(20))");st.execute("CREATE TABLE CHILD(ID INT, PARENT_ID INT REFERENCES PARENT(ID))");st.execute("CREATE VIEW V_PARENT AS SELECT ID, NAME FROM PARENT");
                var view=MetadataActionsTest.selection(jobs,id,"views","PUBLIC","V_PARENT");
                var prepared=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,view,true));assertEquals("complete",prepared.path("state").asText(),prepared.toString());assertEquals(2,prepared.path("result").path("columns").size());assertTrue(prepared.path("result").path("definition").asText().contains("PARENT"));
                var select=HumanSqlTest.finish(jobs,"human",jobs.tablePreparation("human",id,view));assertEquals("complete",select.path("state").asText());
                var table=MetadataActionsTest.selection(jobs,id,"tables","PUBLIC","PARENT");var relation=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,table,true));assertEquals("complete",relation.path("state").asText(),relation.toString());assertEquals("CHILD",relation.path("result").path("relationships").get(0).path("FKTABLE_NAME").asText());
                assertThrows(IllegalArgumentException.class,()->jobs.queryBuilder("human",id,table.deepCopy().put("sourceConnectionId","another"),true));
                var wrongDatabase=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,table.deepCopy().put("database","another"),true));assertEquals("failed",wrongDatabase.path("state").asText());assertTrue(wrongDatabase.path("error").asText().contains("same database"),wrongDatabase.toString());
                var explicitTarget=table.deepCopy().put("database","another");((com.fasterxml.jackson.databind.node.ObjectNode)explicitTarget.path("parent")).put("database","original");assertThrows(IllegalArgumentException.class,()->jobs.queryBuilder("human",id,explicitTarget,true));
                assertThrows(SecurityException.class,()->jobs.queryBuilder("agent:x",id,view,true));
                var imported=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,Profiles.JSON.createObjectNode().put("sql","SELECT p.NAME FROM PUBLIC.PARENT AS p"),false));assertEquals("complete",imported.path("state").asText(),imported.toString());assertTrue(imported.path("result").path("editable").asBoolean());assertEquals(2,imported.path("result").path("model").path("sources").get(0).path("columns").size());
                assertEquals(0,jobs.telemetry().path("retainedJobs").asInt());
            }
        }
    }
}
