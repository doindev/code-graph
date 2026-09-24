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
    @Test void exactIntegersAndSchemaTokens(){
        assertEquals(new java.math.BigInteger("9007199254741003"),CompareSql.advance(new java.math.BigInteger("9007199254740993"),new java.math.BigInteger("9007199254741002"),java.math.BigInteger.valueOf(5)));
        assertEquals("SELECT \"dst\".t, 'src.t' -- src.t",CompareSql.remap("SELECT src.t, 'src.t' -- src.t",Map.of("src","dst")));
        assertEquals("nextval('\"dst\".seq'::regclass)",CompareSql.remap("nextval('src.seq'::regclass)",Map.of("src","dst")));
    }
}
