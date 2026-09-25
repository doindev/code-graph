package io.doindev.codegraph.dba;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseCompareTest {
    @TempDir Path root;
    static JsonNode finish(QueryJobs jobs,ObjectNode submitted)throws Exception{
        JsonNode result=TableDesignerTest.waitRetained(jobs,"human",submitted);
        try{assertEquals("complete",result.path("state").asText(),result.toPrettyString());return result.path("result").deepCopy();}
        finally{jobs.remove("human",submitted.path("id").asText());}
    }
    static String receipt(DatabaseCompare compare,QueryJobs jobs,String connection,String schema)throws Exception{
        return finish(jobs,compare.test("human",Profiles.JSON.createObjectNode().put("connectionId",connection).put("schema",schema))).path("receipt").asText();
    }
    static ObjectNode request(DatabaseCompare compare,QueryJobs jobs,String connection,String source,String destination)throws Exception{
        ObjectNode r=Profiles.JSON.createObjectNode().put("sourceReceipt",receipt(compare,jobs,connection,source)).put("destinationReceipt",receipt(compare,jobs,connection,destination));
        r.putArray("objectTypes").add("tables").add("views").add("sequences");return r;
    }
    static ObjectNode select(DatabaseCompare compare,String id)throws Exception{
        ObjectNode results=compare.results("human",id,0,200,"",""),request=Profiles.JSON.createObjectNode().put("revision",results.path("revision").asText());ArrayNode objects=request.putArray("objects");
        for(JsonNode o:results.path("objects"))if(o.path("supported").asBoolean()&&!o.path("status").asText().equals("destination_only")){
            ObjectNode chosen=objects.addObject().put("id",o.path("id").asText());ArrayNode ids=chosen.putArray("changes");o.path("changes").forEach(change->ids.add(change.path("id").asText()));
        }return request;
    }
    @Test void generatedH2ScriptPreservesDestinationAndSupportsIndividualChanges()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,120),s->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,s->true)){
            String connection=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(Connection anchor=connections.open(connection);Statement st=anchor.createStatement()){
                anchor.setAutoCommit(true);
                st.execute("CREATE SCHEMA SRC; CREATE SCHEMA DST; CREATE TABLE SRC.PARENT(ID INT PRIMARY KEY, NAME VARCHAR(30), NOTE VARCHAR(20), CONSTRAINT PARENT_NAME UNIQUE(NAME)); CREATE INDEX USER_INDEX_NOTE ON SRC.PARENT(NOTE); COMMENT ON COLUMN SRC.PARENT.NOTE IS 'a note'; CREATE TABLE SRC.CHILD(ID INT PRIMARY KEY, PARENT_ID INT, CONSTRAINT CHILD_PARENT FOREIGN KEY(PARENT_ID) REFERENCES SRC.PARENT(ID)); CREATE VIEW SRC.NAMES AS SELECT NAME FROM SRC.PARENT; CREATE SEQUENCE SRC.COUNTER START WITH 10 INCREMENT BY 5; CREATE TABLE DST.EXTRA(ID INT);");
                ObjectNode input=request(compare,jobs,connection,"SRC","DST");String id=finish(jobs,compare.start("human",input)).path("comparisonId").asText();
                ObjectNode selection=select(compare,id);assertFalse(selection.path("objects").isEmpty());
                for(JsonNode row:selection.path("objects"))((ObjectNode)row).put("syncValues",true);
                JsonNode artifact=finish(jobs,compare.generate("human",id,selection));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();
                Files.writeString(root.resolve("h2-generated.sql"),script);
                org.h2.tools.RunScript.execute(anchor,new StringReader(script));
                try(ResultSet r=st.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA='DST' AND CONSTRAINT_TYPE='UNIQUE'")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
                try(ResultSet r=anchor.getMetaData().getColumns(anchor.getCatalog(),"DST","PARENT","NOTE")){assertTrue(r.next());assertEquals("a note",r.getString("REMARKS"));}
                try(ResultSet r=st.executeQuery("SELECT COUNT(*) FROM DST.EXTRA")){assertTrue(r.next());}
                try(ResultSet r=st.executeQuery("SELECT NEXT VALUE FOR DST.COUNTER")){assertTrue(r.next());assertEquals(10,r.getLong(1));}
                assertEquals(script,compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText());
                String firstId=id;String firstArtifact=artifact.path("artifactId").asText();assertThrows(SecurityException.class,()->compare.results("other",firstId,0,100,"",""));compare.remove("human",id);
                assertThrows(SecurityException.class,()->compare.artifacts.preview("human",firstArtifact));
                st.execute("DROP VIEW SRC.NAMES; DROP VIEW DST.NAMES; ALTER TABLE SRC.PARENT ADD A INT; ALTER TABLE SRC.PARENT ADD B INT;");
                id=finish(jobs,compare.start("human",request(compare,jobs,connection,"SRC","DST"))).path("comparisonId").asText();
                selection=select(compare,id);
                for(JsonNode row:selection.path("objects")){JsonNode obj=compare.object("human",id,row.path("id").asText());ArrayNode ids=((ObjectNode)row).putArray("changes");for(JsonNode change:obj.path("changes"))if(change.path("name").asText().equals("A"))ids.add(change.path("id").asText());}
                artifact=finish(jobs,compare.generate("human",id,selection));script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();org.h2.tools.RunScript.execute(anchor,new StringReader(script));
                try(ResultSet r=anchor.getMetaData().getColumns(anchor.getCatalog(),"DST","PARENT","A")){assertTrue(r.next());}
                try(ResultSet r=anchor.getMetaData().getColumns(anchor.getCatalog(),"DST","PARENT","B")){assertFalse(r.next());}
            }
        }
    }
    @Test void rowReviewGenerationAndDrift()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,120),s->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,s->true)){
            String connection=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(Connection anchor=connections.open(connection);Statement st=anchor.createStatement()){
                anchor.setAutoCommit(true);st.execute("CREATE SCHEMA SRC; CREATE SCHEMA DST; CREATE TABLE SRC.ITEM(ID BIGINT PRIMARY KEY, VALUE_TEXT VARCHAR(60)); CREATE TABLE DST.ITEM(ID BIGINT PRIMARY KEY, VALUE_TEXT VARCHAR(60)); INSERT INTO SRC.ITEM VALUES(1,'new'),(9007199254740993,'precise'); INSERT INTO DST.ITEM VALUES(1,'old'),(3,'retained');");
                ObjectNode input=request(compare,jobs,connection,"SRC","DST");JsonNode catalog=finish(jobs,compare.catalog("human",input));String tableId="";for(JsonNode o:catalog.path("objects"))if(o.path("name").asText().equals("ITEM"))tableId=o.path("id").asText();
                input.putArray("tableData").addObject().put("id",tableId).put("includeData",true);
                String id=finish(jobs,compare.start("human",input)).path("comparisonId").asText();
                JsonNode page=compare.rows("human",id,tableId,0,50,"");assertEquals(1,page.path("counts").path("different").asInt());assertEquals(1,page.path("counts").path("source_only").asInt());assertEquals(1,page.path("counts").path("destination_only").asInt());
                ObjectNode selection=select(compare,id);for(JsonNode object:selection.path("objects"))if(object.path("id").asText().equals(tableId))((ObjectNode)object).put("includeData",true);
                JsonNode artifact=finish(jobs,compare.generate("human",id,selection));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();org.h2.tools.RunScript.execute(anchor,new StringReader(script));
                try(ResultSet rows=st.executeQuery("SELECT COUNT(*) FROM DST.ITEM")){assertTrue(rows.next());assertEquals(3,rows.getInt(1));}
                JsonNode failed=TableDesignerTest.waitRetained(jobs,"human",compare.generate("human",id,selection));assertEquals("failed",failed.path("state").asText());assertTrue(failed.path("error").asText().contains("changed"),failed.toString());
                jobs.remove("human",failed.path("id").asText());
                st.execute("DELETE FROM DST.ITEM; INSERT INTO DST.ITEM VALUES(1,'old'),(3,'retained')");
                assertTrue(finish(jobs,compare.generate("human",id,selection)).has("artifactId"),"A failed generation must preserve the comparison for retry");
            }
        }
    }
    @Test void externalDependentsAndFailedJobCleanup()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,120),s->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,s->true)){
            String connection=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(Connection c=connections.open(connection);Statement st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE SCHEMA SRC;CREATE SCHEMA DST;CREATE SCHEMA OUTSIDE;CREATE TABLE SRC.ITEM(ID INT PRIMARY KEY,A INT);CREATE TABLE DST.ITEM(ID INT PRIMARY KEY);CREATE TABLE OUTSIDE.CHILD(ID INT REFERENCES DST.ITEM(ID))");
                String id=finish(jobs,compare.start("human",request(compare,jobs,connection,"SRC","DST"))).path("comparisonId").asText();
                var selection=select(compare,id);assertTrue(assertThrows(IllegalArgumentException.class,()->compare.generate("human",id,selection)).getMessage().contains("outside the comparison scope"));compare.remove("human",id);
                ObjectNode invalid=request(compare,jobs,connection,"SRC","DST");invalid.putArray("tableData").addObject().put("id","missing").put("includeData",true);
                ObjectNode submitted=compare.start("human",invalid);JsonNode job=TableDesignerTest.waitRetained(jobs,"human",submitted);assertEquals("failed",job.path("state").asText());jobs.remove("human",submitted.path("id").asText());assertEquals(0,compare.telemetry().path("comparisons").asInt());assertEquals(0,compare.telemetry().path("dataDiskBytes").asLong());
                assertThrows(SecurityException.class,()->compare.test("agent:test",Profiles.JSON.createObjectNode()));
            }
        }
    }
    @Test void allSchemasPaginationAndSingleConnectionPools()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,120),s->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,s->true)){
            ObjectNode a=new DbaTest().input().put("name","Source").put("templateId","h2").put("url","jdbc:h2:"+root.resolve("source").toAbsolutePath());a.putObject("pool").put("maximumPoolSize",1);
            ObjectNode b=new DbaTest().input().put("name","Destination").put("templateId","h2").put("url","jdbc:h2:"+root.resolve("destination").toAbsolutePath());b.putObject("pool").put("maximumPoolSize",1);
            String source=profiles.put(null,a).path("id").asText(),destination=profiles.put(null,b).path("id").asText();
            try(Connection c=connections.open(source);Statement st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE SCHEMA INVENTORY");for(int i=0;i<125;i++)st.execute("CREATE TABLE INVENTORY.T"+i+"(ID INT)");}
            String sr=finish(jobs,compare.test("human",Profiles.JSON.createObjectNode().put("connectionId",source).put("allSchemas",true))).path("receipt").asText();
            String dr=finish(jobs,compare.test("human",Profiles.JSON.createObjectNode().put("connectionId",destination).put("allSchemas",true))).path("receipt").asText();
            ObjectNode request=Profiles.JSON.createObjectNode().put("sourceReceipt",sr).put("destinationReceipt",dr);request.putArray("objectTypes").add("tables");
            String id=finish(jobs,compare.start("human",request)).path("comparisonId").asText();assertEquals(125,compare.results("human",id,0,100,"","").path("total").asInt());assertEquals(25,compare.results("human",id,100,100,"","").path("objects").size());
            JsonNode artifact=finish(jobs,compare.generate("human",id,select(compare,id)));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();assertTrue(script.contains("CREATE SCHEMA \"INVENTORY\""));
            try(Connection c=connections.open(destination)){c.setAutoCommit(true);org.h2.tools.RunScript.execute(c,new StringReader(script));try(ResultSet tables=c.getMetaData().getTables(c.getCatalog(),"INVENTORY","%",new String[]{"TABLE"})){int count=0;while(tables.next())count++;assertEquals(125,count);}}
        }
    }
    @Test void disposalWinsPublicationAndBudgetsAreShared()throws Exception{
        try(CompareArtifacts artifacts=new CompareArtifacts(root,s->true);CompareArtifacts.Draft draft=artifacts.create("owner","compare")){
            draft.writer.write("SELECT 1;");artifacts.discard("owner","compare");assertThrows(IllegalArgumentException.class,draft::publish);
        }
        try(var files=Files.list(root.resolve("compare-exports"))){assertEquals(0,files.count());}
        var budget=new CompareData.Budget(10);try(CompareData a=new CompareData(root,budget);CompareData b=new CompareData(root,budget)){
            a.add(6);assertThrows(IllegalArgumentException.class,()->b.add(5));b.add(4);assertEquals(10,budget.used());
        }assertEquals(0,budget.used());
        assertEquals(new java.math.BigInteger("-23"),CompareSql.advance(new java.math.BigInteger("-3"),new java.math.BigInteger("-22"),java.math.BigInteger.valueOf(-5)));
    }
    @Test void structureOnlySuppressesStaleDataSelectionsAndAllowsGenerationRetry()throws Exception {
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,30),s->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,s->true)){
            String connection=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(Connection c=connections.open(connection);Statement st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE SCHEMA SRC;CREATE SCHEMA DST;CREATE TABLE SRC.ITEM(ID INT PRIMARY KEY,NOTE VARCHAR(20));CREATE TABLE DST.ITEM(ID INT PRIMARY KEY);INSERT INTO SRC.ITEM VALUES(1,'source');INSERT INTO DST.ITEM VALUES(2)");
                ObjectNode input=request(compare,jobs,connection,"SRC","DST").put("dataMode","none").put("syncSequences",false);
                input.putArray("tableData").addObject().put("id","stale selection").put("includeData",true);
                String id=finish(jobs,compare.start("human",input)).path("comparisonId").asText();assertEquals(0,compare.telemetry().path("dataDiskBytes").asLong());
                ObjectNode selection=select(compare,id).put("dataMode","upsert");for(JsonNode object:selection.path("objects"))((ObjectNode)object).put("includeData",true);
                ObjectNode invalid=selection.deepCopy().put("revision","stale");assertThrows(IllegalArgumentException.class,()->compare.generate("human",id,invalid));
                JsonNode artifact=finish(jobs,compare.generate("human",id,selection));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();
                assertTrue(script.contains("ADD"),script);assertFalse(script.matches("(?s).*\\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE)\\b.*"),script);org.h2.tools.RunScript.execute(c,new StringReader(script));
                try(ResultSet rows=st.executeQuery("SELECT ID FROM DST.ITEM")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));assertFalse(rows.next());}
            }
        }
    }
    @Test void connectionFailureDuringObjectInspectionStopsInsteadOfPublishingPartialResults()throws Exception {
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,30),owner->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,owner->true)){
            String connection=profiles.put(null,new DbaTest().input().put("templateId","h2")).path("id").asText();
            try(Connection c=connections.open(connection);Statement st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE SCHEMA SRC;CREATE TABLE SRC.ITEM(ID INT PRIMARY KEY)");}
            var target=compare.target(Profiles.JSON.createObjectNode().put("connectionId",connection).put("schema","SRC"));
            ObjectNode submitted=jobs.comparison("human",Set.of(connection),job->compare.read(job,target,c->{
                DatabaseMetaData metadata=(DatabaseMetaData)java.lang.reflect.Proxy.newProxyInstance(DatabaseMetaData.class.getClassLoader(),new Class<?>[]{DatabaseMetaData.class},(proxy,method,args)->{
                    if(method.getName().equals("getPrimaryKeys"))throw new SQLNonTransientConnectionException("fixture connection lost","08006");
                    try{return method.invoke(c.getMetaData(),args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                });
                Connection failing=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                    if(method.getName().equals("getMetaData"))return metadata;
                    try{return method.invoke(c,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                });
                CompareCatalog.capture(job,failing,target,Set.of("tables"),false);return Profiles.JSON.createObjectNode().put("partialPublished",true);
            }),()->{});
            JsonNode failed=TableDesignerTest.waitRetained(jobs,"human",submitted);assertEquals("failed",failed.path("state").asText());assertEquals("database_connection_failure",failed.path("errorCode").asText());assertFalse(failed.has("result"));
        }
    }
    @Test void exactIntegersAndSchemaTokens(){
        assertEquals(new java.math.BigInteger("9007199254741003"),CompareSql.advance(new java.math.BigInteger("9007199254740993"),new java.math.BigInteger("9007199254741002"),java.math.BigInteger.valueOf(5)));
        assertEquals("SELECT \"dst\".t, 'src.t' -- src.t",CompareSql.remap("SELECT src.t, 'src.t' -- src.t",Map.of("src","dst")));
        assertEquals("nextval('\"dst\".seq'::regclass)",CompareSql.remap("nextval('src.seq'::regclass)",Map.of("src","dst")));
    }
    @Test void selectedObjectsRetainTheirBudgetAndOnlyRevalidateTheirDependencyClosure()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,192L<<20,2,100,100,30),o->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,o->true)){
            String connection=profiles.put(null,new DbaTest().input()).path("id").asText();try(Connection c=connections.open(connection);Statement st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE SCHEMA ONLY_SRC;CREATE SCHEMA ONLY_DST;CREATE TABLE ONLY_SRC.PARENT(ID INT PRIMARY KEY);CREATE TABLE ONLY_SRC.CHILD(ID INT PRIMARY KEY,PID INT REFERENCES ONLY_SRC.PARENT(ID));CREATE TABLE ONLY_SRC.UNRELATED(ID INT)");
                var input=request(compare,jobs,connection,"ONLY_SRC","ONLY_DST").put("dataMode","none").put("syncSequences",false);input.putArray("objectTypes").add("tables");
                String objectId=TableDesigner.hash(Profiles.JSON.getNodeFactory().textNode(CompareCatalog.key("ONLY_SRC","tables","PARENT"))).substring(0,32);input.putArray("objectIds").add(objectId);
                jobs.comparisonMetadataBytes(2L<<20);String id=finish(jobs,compare.start("human",input)).path("comparisonId").asText();jobs.comparisonMetadataBytes(1L<<20);
                st.execute("ALTER TABLE ONLY_SRC.UNRELATED ADD NOTE TEXT");var selected=select(compare,id);assertTrue(finish(jobs,compare.generate("human",id,selected)).has("artifactId"));
                st.execute("ALTER TABLE ONLY_SRC.CHILD ADD NOTE TEXT");var stale=TableDesignerTest.waitRetained(jobs,"human",compare.generate("human",id,selected));assertEquals("failed",stale.path("state").asText());assertTrue(stale.path("error").asText().contains("definitions changed"),stale.toString());jobs.remove("human",stale.path("id").asText());compare.remove("human",id);
                jobs.comparisonMetadataBytes(64L<<20);long before=jobs.telemetry().path("reservedBytes").asLong();assertThrows(IllegalArgumentException.class,()->compare.start("human",input));assertEquals(before,jobs.telemetry().path("reservedBytes").asLong());
            }
        }
    }

    @Test void dependencyCounterpartsAreCapturedBeforeDefinitionsAndBudgetSurvivesSettingsChange()throws Exception{
        try(Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(root,192L<<20,2,100,100,30),o->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,root,o->true)){
            String connection=profiles.put(null,new DbaTest().input()).path("id").asText();try(Connection c=connections.open(connection);Statement st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE SCHEMA DEP_SRC;CREATE SCHEMA DEP_DST;CREATE TABLE DEP_SRC.BASE(ID INT);CREATE TABLE DEP_DST.BASE(ID INT)");
                st.execute("CREATE VIEW DEP_SRC.LARGE_VIEW AS SELECT ID, '"+"x".repeat(600000)+"' AS NOTE FROM DEP_SRC.BASE");
                var input=request(compare,jobs,connection,"DEP_SRC","DEP_DST").put("dataMode","none").put("syncSequences",false);input.putArray("objectTypes").add("views");
                jobs.comparisonMetadataBytes(8L<<20);String id=finish(jobs,compare.start("human",input)).path("comparisonId").asText();jobs.comparisonMetadataBytes(1L<<20);
                var artifact=finish(jobs,compare.generate("human",id,select(compare,id)));assertTrue(artifact.has("artifactId"));compare.remove("human",id);
                var failed=TableDesignerTest.waitRetained(jobs,"human",compare.start("human",input));assertEquals("failed",failed.path("state").asText());assertTrue(failed.path("error").asText().contains("1 MiB"),failed.toPrettyString());jobs.remove("human",failed.path("id").asText());
            }
        }
    }

}
