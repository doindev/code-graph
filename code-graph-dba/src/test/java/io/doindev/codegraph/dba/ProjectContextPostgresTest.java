package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import io.doindev.codegraph.store.DocumentStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="DBA_TEST_DISPOSABLE",matches="code-graph-dba-test-[a-f0-9]+")
class ProjectContextPostgresTest {
 @TempDir Path root;
 @Test void nativeCatalogIncludesRoutinesDependenciesVersionsAndSchemaIsolation()throws Exception{
  String schema="ctx_"+UUID.randomUUID().toString().replace("-",""),other=schema+"_other";
  try(var connection=DriverManager.getConnection(System.getenv("DBA_TEST_URL"),"postgres",System.getenv("DBA_TEST_PASSWORD"));var st=connection.createStatement();var docs=DocumentStore.memory(64L<<20)){
   st.execute("CREATE SCHEMA "+schema);st.execute("CREATE SCHEMA "+other);
   try{
    st.execute("CREATE TABLE "+schema+".parent(id int PRIMARY KEY)");st.execute("CREATE TABLE "+schema+".child(id int REFERENCES "+schema+".parent(id))");st.execute("CREATE VIEW "+schema+".parent_view AS SELECT id FROM "+schema+".parent");st.execute("CREATE SEQUENCE "+schema+".seq");st.execute("CREATE FUNCTION "+schema+".answer() RETURNS integer LANGUAGE SQL AS 'SELECT 42'");st.execute("CREATE TABLE "+other+".secret(id int)");
    connection.setAutoCommit(false);var binding=Profiles.JSON.createObjectNode().put("database","postgres").put("schema",schema);var profile=Profiles.JSON.createObjectNode().put("templateId","postgresql");com.fasterxml.jackson.databind.node.ObjectNode[] snapshot={null};
    docs.replace(w->{try{snapshot[0]=new CatalogScanner(connection,profile,binding,w,()->false).scan();}catch(Exception e){throw new RuntimeException(e);}});assertEquals("PostgreSQL",snapshot[0].path("version").path("product").asText());assertTrue(snapshot[0].path("version").path("major").asInt()>=16);
    Set<String> kinds=new HashSet<>();docs.scan("o/",(k,v)->{var object=ProjectContexts.json(v);assertEquals(schema,object.path("schema").asText());assertNotEquals("secret",object.path("name").asText());kinds.add(object.path("kind").asText());if(object.path("name").asText().equals("answer"))assertTrue(object.path("ddl").asText().contains("SELECT 42"));});assertTrue(kinds.containsAll(Set.of("table","view","function","sequence","index","constraint")),kinds.toString());assertTrue(snapshot[0].path("dependencies").asInt()>=2,snapshot[0].toString());
    Connections.selectSchema(connection,schema);try(var r=st.executeQuery("SELECT current_schema()")){assertTrue(r.next());assertEquals(schema,r.getString(1));}connection.rollback();connection.setAutoCommit(true);
   }finally{if(!connection.getAutoCommit()){connection.rollback();connection.setAutoCommit(true);}st.execute("DROP SCHEMA "+schema+" CASCADE");st.execute("DROP SCHEMA "+other+" CASCADE");}
  }
 }
}
