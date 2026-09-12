package io.doindev.codegraph.dba;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="DBA_TEST_DISPOSABLE",matches="code-graph-dba-test-[a-f0-9]+")
class VisualPostgresTest {
 @TempDir Path root;
 @Test void functionsOverloadsParametersAndEstimatedPlans()throws Exception{
  String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),schema="visual_"+UUID.randomUUID().toString().replace("-","");
  try(var anchor=DriverManager.getConnection(url,"postgres",password);var st=anchor.createStatement();var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,100,100,15),o->true)){
   st.execute("CREATE SCHEMA "+schema);try{
    st.execute("CREATE TABLE "+schema+".item(id int, name text)");st.execute("INSERT INTO "+schema+".item VALUES(1,'a'),(2,'b')");st.execute("CREATE FUNCTION "+schema+".repeat_value(value text, copies integer DEFAULT 2) RETURNS text LANGUAGE SQL IMMUTABLE AS 'SELECT repeat(value,copies)'");st.execute("CREATE FUNCTION "+schema+".repeat_value(value integer) RETURNS integer LANGUAGE SQL IMMUTABLE AS 'SELECT value*2'");
    String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","visual").put("templateId","custom").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
    ObjectNode request=Profiles.JSON.createObjectNode().put("schema",schema).put("search","repeat_value");var functions=HumanSqlTest.finish(jobs,"human",jobs.functions("human",id,request));assertEquals("complete",functions.path("state").asText(),functions.toString());assertEquals(2,functions.path("result").path("functions").size());boolean optional=false;for(var f:functions.path("result").path("functions")){var signature=HumanSqlTest.finish(jobs,"human",jobs.functions("human",id,request.deepCopy().put("key",f.path("key").asText())));assertTrue(signature.path("result").path("functions").get(0).path("available").asBoolean(),signature.toString());for(var a:signature.path("result").path("functions").get(0).path("arguments"))optional|=a.path("optional").asBoolean();}assertTrue(optional);
    for(var listed:functions.path("result").path("functions")){var full=HumanSqlTest.finish(jobs,"human",jobs.functions("human",id,request.deepCopy().put("key",listed.path("key").asText()))).path("result").path("functions").get(0);var model=VisualQueryTest.model(1);((ObjectNode)model.path("sources").get(0)).put("reference",schema+".item");var call=VisualQuery.expression("function").put("name",full.path("name").asText()).put("schema",schema).put("catalogFunction",true);call.set("arguments",full.path("arguments"));boolean integer=full.path("arguments").get(0).path("valueType").asText().equals("integer");call.putArray("args").add(VisualQuery.expression("literal").put("type","text").put("value",integer?"7":"a"));((ObjectNode)model.path("detail").path("outputs").get(0)).set("expression",call);var compiledCall=VisualQueryTest.compile(model);assertTrue(compiledCall.path("valid").asBoolean(),compiledCall.toString());try(var rows=st.executeQuery(compiledCall.path("sql").asText())){assertTrue(rows.next());assertEquals(integer?"14":"aa",rows.getString(1));}}

    var imported=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,Profiles.JSON.createObjectNode().put("sql","SELECT name, COUNT(*) AS total FROM "+schema+".item WHERE id >= ? GROUP BY name HAVING COUNT(*) > ? ORDER BY total"),false));assertTrue(imported.path("result").path("editable").asBoolean(),imported.toString());ObjectNode compileRequest=Profiles.JSON.createObjectNode().put("quote","\"").put("engine","postgresql");compileRequest.set("model",imported.path("result").path("visualModel"));var compiled=VisualQuery.compile(compileRequest);assertTrue(compiled.path("valid").asBoolean(),compiled.toString());
    var explained=HumanSqlTest.finish(jobs,"human",jobs.browserExplain("human",id,compiled.path("sql").asText(),Profiles.JSON.createArrayNode().add(1).add(0),"postgres"));assertEquals("complete",explained.path("state").asText(),explained.toString());assertEquals("postgresql",explained.path("result").path("engine").asText());assertEquals("json",explained.path("result").path("format").asText());assertFalse(explained.path("result").path("nodes").isEmpty());assertFalse(explained.path("result").path("rawText").asText().contains("Actual Rows"));
   }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
  }
 }
}
