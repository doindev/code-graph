package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class GridSafetyTest {
    @TempDir Path root;
    final AtomicBoolean alive=new AtomicBoolean(true);
    final DbaConfig config(){return new DbaConfig(root,128L<<20,2,1000,100,15);}
    @Test void vendorTypesWithUnverifiedPrecisionRemainReadOnly(){
        assertFalse(GridRelation.scalarTypeSupported("sqlserver",Types.TIMESTAMP,"datetime",23));
        assertFalse(GridRelation.scalarTypeSupported("sqlserver",Types.TIMESTAMP,"smalldatetime",16));
        assertTrue(GridRelation.scalarTypeSupported("sqlserver",Types.TIMESTAMP,"datetime2",27));
        assertFalse(GridRelation.scalarTypeSupported("mysql",Types.BIGINT,"BIGINT UNSIGNED",20));
        assertFalse(GridRelation.scalarTypeSupported("mariadb",Types.TIME,"TIME",17));
        assertFalse(GridRelation.scalarTypeSupported("mysql",Types.BIT,"BIT",8));
        assertTrue(GridRelation.scalarTypeSupported("sqlserver",Types.TINYINT,"tinyint",3));
        assertTrue(GridRelation.scalarTypeSupported("postgresql",Types.TIME,"time",15));
    }
    @Test void oracleUnconstrainedNumbersAndDatePrecisionAreValidatedWithoutRounding(){
        var number=new GridRelation.Column("c1","VALUE",Types.NUMERIC,38,Integer.MIN_VALUE,true,false,false,0);
        for(String value:new String[]{"0","1E-130","1E125","12345678901234567890123456789012345678"})GridRelation.validate(number,TextNode.valueOf(value));
        for(String value:new String[]{"1E-131","1E126","123456789012345678901234567890123456789"})assertThrows(IllegalArgumentException.class,()->GridRelation.validate(number,TextNode.valueOf(value)));
        var fixed=new GridRelation.Column("c1","FIXED",Types.NUMERIC,38,0,true,false,false,0);assertThrows(IllegalArgumentException.class,()->GridRelation.validate(fixed,TextNode.valueOf("1E2147483647")));
        assertFalse(GridRelation.scalarTypeSupported("oracle",Types.NUMERIC,"FLOAT",126));assertFalse(GridRelation.scalarTypeSupported("oracle",Types.TIMESTAMP,"TIMESTAMP WITH TIME ZONE",35));assertTrue(GridRelation.scalarTypeSupported("oracle",Types.TIMESTAMP,"TIMESTAMP(9)",11));
        var date=new GridRelation.Column("c1","DATE_VALUE",Types.TIMESTAMP,7,0,true,false,false,0);GridRelation.validate(date,TextNode.valueOf("2026-09-24T23:45:56"));assertThrows(IllegalArgumentException.class,()->GridRelation.validate(date,TextNode.valueOf("2026-09-24T23:45:56.1")));
    }
    class Fixture implements AutoCloseable {
        final Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());
        final Connections connections=new Connections(profiles);
        final QueryJobs jobs=new QueryJobs(connections,config(),s->alive.get());
        final GridResults grids=new GridResults(jobs,connections,GridSafetyTest.this::config,s->alive.get());
        final String id=profiles.put(null,new DbaTest().input()).path("id").asText();
        final Connection c=connections.open(id);
        Fixture()throws Exception{jobs.grids=grids;c.setAutoCommit(true);try(var s=c.createStatement()){
            s.execute("CREATE TABLE SAFE_GRID(ID INT PRIMARY KEY, VALUE_TEXT VARCHAR(20), AMOUNT DECIMAL(10,2))");
            s.execute("INSERT INTO SAFE_GRID SELECT X, 'row-'||X, X FROM SYSTEM_RANGE(1,451)");
        }}
        JsonNode query(String sql)throws Exception{
            JsonNode started=jobs.humanQuery("human",id,sql,Profiles.JSON.createArrayNode(),false),done=HumanSqlTest.finish(jobs,"human",started);
            assertEquals("complete",done.path("state").asText(),done.toString());return done.path("result").path("results").get(0);
        }
        JsonNode op(JsonNode grid,String action,ObjectNode body)throws Exception{body.put("revision",grid.path("revision").asLong());return HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),action,body));}
        ObjectNode draft(JsonNode grid,String column,String value){var body=Profiles.JSON.createObjectNode();body.putArray("changes").addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject(column).put("kind","value").put("value",value);return body;}
        public void close()throws Exception{c.close();grids.close();jobs.close();connections.close();profiles.close();}
    }
    @Test void queryLimitsAmbiguityAndGeneratedColumnsStayHonest()throws Exception{
        try(var f=new Fixture()){
            var limited=f.query("SELECT * FROM SAFE_GRID ORDER BY ID LIMIT 23").path("grid");
            var tail=f.op(limited,"page",Profiles.JSON.createObjectNode().put("direction","last").put("limit",200));
            assertEquals(23,tail.path("result").path("rows").size());assertEquals("23",tail.path("result").path("rows").get(22).get(0).asText());
            for(String sql:new String[]{"SELECT ID, ID FROM SAFE_GRID","SELECT COUNT(*) FROM SAFE_GRID","SELECT A.ID, A.VALUE_TEXT FROM SAFE_GRID A JOIN SAFE_GRID B ON A.ID=B.ID"}){
                var cap=f.query(sql).path("grid").path("capabilities");assertFalse(cap.path("edit").asBoolean(),sql);assertFalse(cap.path("page").asBoolean(),sql);
            }
            try(var s=f.c.createStatement()){s.execute("CREATE TABLE GENERATED_GRID(ID INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, NAME VARCHAR(20))");s.execute("INSERT INTO GENERATED_GRID(NAME) VALUES('one')");}
            var generated=f.query("SELECT * FROM GENERATED_GRID").path("grid");
            assertTrue(generated.path("capabilities").path("edit").asBoolean());assertFalse(generated.path("columns").get(0).path("editable").asBoolean());assertFalse(generated.path("capabilities").path("sqlExport").asBoolean());
            var original=f.query("SELECT * FROM SAFE_GRID").path("grid");
            assertEquals("failed",f.op(original,"prepare",f.draft(original,"c3","1.234")).path("state").asText());
            assertEquals("failed",f.op(original,"prepare",f.draft(original,"c2","x".repeat(21))).path("state").asText());
        }
    }
    @Test void staleSchemasExpiredReviewsAndAuditFailureNeverWrite()throws Exception{
        try(var f=new Fixture()){
            var grid=f.query("SELECT * FROM SAFE_GRID").path("grid");
            var prepared=f.op(grid,"prepare",f.draft(grid,"c2","changed"));assertEquals("complete",prepared.path("state").asText());
            var context=f.grids.require("human",grid.path("id").asText());var plan=context.plan;
            context.plan=new GridResults.Plan(plan.id(),plan.revision(),0,plan.commands(),false);
            assertEquals("failed",f.op(grid,"apply",Profiles.JSON.createObjectNode().put("planId",plan.id())).path("state").asText());
            prepared=f.op(grid,"prepare",f.draft(grid,"c2","changed"));Files.createDirectory(root.resolve("browser-grid-audit.jsonl"));
            assertEquals("failed",f.op(grid,"apply",Profiles.JSON.createObjectNode().put("planId",prepared.path("result").path("planId").asText())).path("state").asText());
            try(var s=f.c.createStatement();var rs=s.executeQuery("SELECT VALUE_TEXT FROM SAFE_GRID WHERE ID=1")){rs.next();assertEquals("row-1",rs.getString(1));}
            try(var s=f.c.createStatement()){s.execute("ALTER TABLE SAFE_GRID ADD EXTRA INT");}
            assertEquals("failed",f.op(grid,"prepare",f.draft(grid,"c2","changed")).path("state").asText());
            assertThrows(SecurityException.class,()->f.grids.status("other",context.id));
            alive.set(false);f.grids.reap();assertEquals(0,f.grids.contexts.size());
        }
    }
    @Test void exportAdmissionAndSessionCleanupStayBounded()throws Exception{
        try(var f=new Fixture()){
            var grid=f.query("SELECT * FROM SAFE_GRID").path("grid");var request=Profiles.JSON.createObjectNode().put("scope","selected").put("format","csv");
            request.putArray("columns").add("c2");request.putArray("selectedRows").add(grid.path("rowIds").get(1).asText());
            for(int i=0;i<2;i++){var exported=f.op(grid,"export",request);assertEquals("complete",exported.path("state").asText());assertEquals(1,exported.path("result").path("rows").asInt());}
            assertEquals("failed",f.op(grid,"export",request).path("state").asText());assertEquals(128L<<20,f.grids.exports.telemetry().path("reservedDiskBytes").asLong());
            alive.set(false);f.grids.reap();assertEquals(0,f.grids.exports.telemetry().path("reservedDiskBytes").asLong());
            try(var files=Files.list(root.resolve("grid-exports"))){assertEquals(0,files.count());}
        }
    }
    @Test void lostCommitAcknowledgementBlocksReplayUntilExplicitReconciliation()throws Exception{
        try(var f=new Fixture()){
            var grid=f.query("SELECT * FROM SAFE_GRID").path("grid");var prepared=f.op(grid,"prepare",f.draft(grid,"c2","committed"));
            var context=f.grids.require("human",grid.path("id").asText());f.c.setAutoCommit(false);
            Connection loss=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try{Object value=method.invoke(f.c,args);if(method.getName().equals("commit"))throw new SQLException("Simulated lost commit acknowledgement");return value;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
            var job=f.jobs.new Job("human",f.id);
            assertThrows(IllegalArgumentException.class,()->f.grids.apply(context,job,loss,Profiles.JSON.createObjectNode().put("planId",prepared.path("result").path("planId").asText())));
            assertTrue(context.uncertain);assertEquals("unknown",job.outcome);
            assertThrows(IllegalArgumentException.class,()->f.grids.operation("human",context.id,"prepare",f.draft(grid,"c2","replayed").put("revision",1)));
            var reconciled=f.op(grid,"reconcile",Profiles.JSON.createObjectNode().put("direction","first"));
            assertEquals("complete",reconciled.path("state").asText(),reconciled.toString());assertEquals("committed",reconciled.path("result").path("rows").get(0).get(1).asText());assertFalse(context.uncertain);
        }
    }
    @Test void cancellationAfterTheFirstDmlRollsBackEverything()throws Exception{interruptedSave(false);}
    @Test void ownerLossAfterTheFirstDmlRollsBackEverything()throws Exception{interruptedSave(true);}
    private void interruptedSave(boolean loseOwner)throws Exception{
        try(var f=new Fixture()){
            var grid=f.query("SELECT * FROM SAFE_GRID").path("grid");var draft=f.draft(grid,"c2","not committed");
            ((ArrayNode)draft.path("changes")).addObject().put("rowId",grid.path("rowIds").get(1).asText()).put("operation","delete").putObject("values");
            var prepared=f.op(grid,"prepare",draft);var context=f.grids.require("human",grid.path("id").asText());f.c.setAutoCommit(false);
            var job=f.jobs.new Job("human",f.id);
            Connection cancel=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(proxy,method,args)->{
                try{Object result=method.invoke(f.c,args);
                    if(result instanceof PreparedStatement statement)return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{
                        try{Object value=m.invoke(statement,a);if(m.getName().equals("executeLargeUpdate")){if(loseOwner)alive.set(false);else job.cancelled=true;}return value;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
                    });
                    return result;
                }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
            });
            assertThrows(java.util.concurrent.CancellationException.class,()->f.grids.apply(context,job,cancel,Profiles.JSON.createObjectNode().put("planId",prepared.path("result").path("planId").asText()).put("confirmed",true)));
            assertEquals("rolled_back",job.outcome);
            try(var s=f.c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM SAFE_GRID")){rs.next();assertEquals(451,rs.getInt(1));}
            try(var s=f.c.createStatement();var rs=s.executeQuery("SELECT VALUE_TEXT FROM SAFE_GRID WHERE ID=1")){rs.next();assertEquals("row-1",rs.getString(1));}
        }
    }
    @Test void cascadingDeleteRollsBackWithALaterConstraintFailureAndPreviewsStayReadOnly()throws Exception{
        try(var f=new Fixture()){
            try(var s=f.c.createStatement()){
                s.execute("CREATE TABLE CHILD_GRID(ID INT PRIMARY KEY, PARENT_ID INT REFERENCES SAFE_GRID(ID) ON DELETE CASCADE)");
                s.execute("INSERT INTO CHILD_GRID VALUES(1,1)");
                s.execute("CREATE TABLE TRUNCATED_GRID(ID INT PRIMARY KEY, CONTENT VARCHAR(20000))");
                s.execute("INSERT INTO TRUNCATED_GRID VALUES(1,REPEAT('x',12000))");
            }
            var preview=f.query("SELECT * FROM TRUNCATED_GRID");assertTrue(preview.path("cellsTruncated").asBoolean());assertFalse(preview.path("grid").path("capabilities").path("edit").asBoolean());
            var grid=f.query("SELECT * FROM SAFE_GRID").path("grid");var draft=Profiles.JSON.createObjectNode();
            var changes=draft.putArray("changes");changes.addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","delete").putObject("values");
            changes.addObject().put("rowId",grid.path("rowIds").get(2).asText()).put("operation","update").putObject("values").putObject("c1").put("kind","value").put("value","2");
            var prepared=f.op(grid,"prepare",draft);
            var failed=f.op(grid,"apply",Profiles.JSON.createObjectNode().put("planId",prepared.path("result").path("planId").asText()).put("confirmed",true));
            assertEquals("failed",failed.path("state").asText());
            try(var s=f.c.createStatement();var rs=s.executeQuery("SELECT COUNT(*) FROM CHILD_GRID")){rs.next();assertEquals(1,rs.getInt(1));}
        }
    }
}
