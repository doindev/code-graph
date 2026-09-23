package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VisualJoinTypesLiveTest {
 @TempDir Path root;
 @Test void h2ConversionsRunExplainAndReopen()throws Exception{exercise(new DbaTest().input(),"h2");}
 @Test @EnabledIfEnvironmentVariable(named="DBA_TEST_DISPOSABLE",matches="code-graph-dba-test-[a-f0-9]+")
 void postgresConversionsRunExplainAndReopen()throws Exception{
  exercise(Profiles.JSON.createObjectNode().put("name","joins").put("templateId","custom").put("url",System.getenv("DBA_TEST_URL")).put("username","postgres").put("password",System.getenv("DBA_TEST_PASSWORD")).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()),"postgresql");
 }
 void exercise(ObjectNode profile,String engine)throws Exception{
  String schema="join_"+UUID.randomUUID().toString().replace("-","");
  try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,100,100,15),o->true)){
   String id=profiles.put(null,profile).path("id").asText();
   try(var c=connections.open(id);var st=c.createStatement()){
    c.setAutoCommit(true);st.execute("CREATE SCHEMA "+schema);
    try{
     st.execute("CREATE TABLE "+schema+".numbers(\"ID\" INT)");st.execute("CREATE TABLE "+schema+".texts(\"TXT\" VARCHAR(40))");st.execute("INSERT INTO "+schema+".numbers VALUES(1),(2)");st.execute("INSERT INTO "+schema+".texts VALUES('1'),('2')");
     String sql="SELECT n.\"ID\", t.\"TXT\" FROM "+schema+".numbers n LEFT JOIN "+schema+".texts t ON n.\"ID\"=t.\"TXT\" ORDER BY n.\"ID\"";
     var imported=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,Profiles.JSON.createObjectNode().put("sql",sql),false));assertTrue(imported.path("result").path("editable").asBoolean(),imported.toString());
     var model=(ObjectNode)imported.path("result").path("visualModel");assertEquals(40,model.path("sources").get(1).path("columns").get(0).path("precision").asInt());assertFalse(VisualJoinTypesTest.compile(model,engine).path("valid").asBoolean());
     var p=(ObjectNode)model.path("roots").get(0).path("pairs").get(0);p.put("rightCast","INTEGER");var compiled=VisualJoinTypesTest.compile(model,engine);assertTrue(compiled.path("valid").asBoolean(),compiled.toString());String converted=compiled.path("sql").asText();
     var rows=HumanSqlTest.run(jobs,id,converted,true);assertEquals("complete",rows.path("state").asText(),rows.toString());assertEquals(2,rows.path("result").path("rows").size());
     var plan=HumanSqlTest.finish(jobs,"human",jobs.browserExplain("human",id,converted,Profiles.JSON.createArrayNode(),""));assertEquals("complete",plan.path("state").asText(),plan.toString());assertEquals(engine,plan.path("result").path("engine").asText());assertFalse(plan.path("result").path("executed").asBoolean());
     st.execute("CREATE VIEW "+schema+".joined AS "+converted);
     String definition;
     if(engine.equals("postgresql")){try(var r=st.executeQuery("SELECT pg_get_viewdef('"+schema+".joined'::regclass,true)")){assertTrue(r.next());definition=r.getString(1);}}
     else {try(var r=st.executeQuery("SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA='"+schema.toUpperCase()+"' AND TABLE_NAME='JOINED'")){assertTrue(r.next());definition=r.getString(1);}}
     var reopened=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,Profiles.JSON.createObjectNode().put("sql",definition),false));assertTrue(reopened.path("result").path("editable").asBoolean(),reopened.toString());var recovered=(ObjectNode)reopened.path("result").path("visualModel");var recompiled=VisualJoinTypesTest.compile(recovered,engine);assertTrue(recompiled.path("valid").asBoolean(),recompiled.toString());assertTrue(recompiled.path("sql").asText().contains("CAST("));assertEquals(recovered,VisualQuery.validateDraft(recovered));
     st.execute("INSERT INTO "+schema+".texts VALUES('invalid integer')");assertThrows(SQLException.class,()->{try(var r=st.executeQuery(converted)){while(r.next())r.getObject(1);}});
     var failed=HumanSqlTest.run(jobs,id,converted,true);assertNotEquals("complete",failed.path("state").asText(),failed.toString());assertTrue(failed.path("result").path("errors").size()>0||!failed.path("error").asText().isBlank(),failed.toString());
    }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
   }
  }
 }
}
