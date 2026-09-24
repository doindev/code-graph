package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** The harness owns both containers and requires an explicit disposable-fixture marker. */
@EnabledIfEnvironmentVariable(named="DBA_COMPARE_DISPOSABLE",matches="cgraph-compare-qa-[a-f0-9]+")
class CompareVendorIntegrationTest {
    @TempDir Path directory;
    @Test @Timeout(180) void generatedScriptRestoresDefinitionsRowsAndSequences()throws Exception{
        String engine=System.getenv("DBA_COMPARE_VENDOR"),sourceSchema=engine.equals("postgresql")?"src":"compare_test",destSchema=engine.equals("postgresql")?"dst":"compare_test";
        try(Profiles profiles=new Profiles(directory,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,120),s->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,directory,s->true)){
            String source=profiles.put(null,profile(engine,"Source",System.getenv("DBA_COMPARE_SOURCE"))).path("id").asText();
            String destination=profiles.put(null,profile(engine,"Destination",System.getenv("DBA_COMPARE_DESTINATION"))).path("id").asText();
            try(Connection src=connections.open(source);Connection dst=connections.open(destination);Statement a=src.createStatement();Statement b=dst.createStatement()){
                src.setAutoCommit(true);dst.setAutoCommit(true);
                if(engine.equals("postgresql")){a.execute("CREATE SCHEMA src");b.execute("CREATE SCHEMA dst");}
                String sq=sourceSchema+".",dq=destSchema+".";
                a.execute("CREATE TABLE "+sq+"parent(id BIGINT PRIMARY KEY, title VARCHAR(80), amount DECIMAL(25,4))");
                a.execute("CREATE TABLE "+sq+"child(id INT PRIMARY KEY, parent_id BIGINT, CONSTRAINT child_parent FOREIGN KEY(parent_id) REFERENCES "+sq+"parent(id))");
                a.execute("CREATE INDEX parent_title ON "+sq+"parent(title)");
                a.execute("CREATE "+(engine.equals("postgresql")?"":"SQL SECURITY INVOKER ")+"VIEW "+sq+"names(person_id,person_title) AS SELECT id,title FROM "+sq+"parent WITH CASCADED CHECK OPTION");
                a.execute("INSERT INTO "+sq+"parent VALUES(1,'O''Brien',123456789012345.1250),(9007199254740993,'precise',NULL)");
                a.execute("INSERT INTO "+sq+"child VALUES(1,1)");
                b.execute("CREATE TABLE "+dq+"unrelated(id INT PRIMARY KEY)");b.execute("INSERT INTO "+dq+"unrelated VALUES(7)");
                if(engine.equals("postgresql")){a.execute("CREATE SEQUENCE src.counter START WITH 9007199254740993 INCREMENT BY 5 CACHE 1");a.execute("SELECT nextval('src.counter')");
                    a.execute("CREATE TABLE src.identity_rows(id BIGINT GENERATED ALWAYS AS IDENTITY (SEQUENCE NAME src.named_generator START WITH 10 INCREMENT BY 5 CACHE 1) PRIMARY KEY, title TEXT)");
                    a.execute("INSERT INTO src.identity_rows(id,title) OVERRIDING SYSTEM VALUE VALUES(1000,'manual')");
                    a.execute("CREATE SEQUENCE src.descending_counter INCREMENT BY -5 MINVALUE -1000 MAXVALUE -1 START WITH -3 CACHE 1");
                    a.execute("CREATE SEQUENCE src.cached_counter INCREMENT BY 5 START WITH 3 CACHE 4");a.execute("SELECT nextval('src.cached_counter')");
                    a.execute("CREATE TYPE src.ticket_state AS ENUM ('open','closed')");a.execute("CREATE TABLE src.tickets(id INT PRIMARY KEY,state src.ticket_state DEFAULT 'open'::src.ticket_state)");
                    a.execute("CREATE FUNCTION src.default_title() RETURNS text LANGUAGE sql AS 'SELECT ''created''::text'");
                    a.execute("CREATE TABLE src.defaulted(id INT PRIMARY KEY,title TEXT DEFAULT src.default_title())");}
                ObjectNode input=Profiles.JSON.createObjectNode().put("sourceReceipt",DatabaseCompareTest.receipt(compare,jobs,source,sourceSchema)).put("destinationReceipt",DatabaseCompareTest.receipt(compare,jobs,destination,destSchema));
                ArrayNode types=input.putArray("objectTypes").add("tables").add("views");if(engine.equals("postgresql"))types.add("sequences").add("indexes").add("functions").add("types");
                JsonNode catalog=DatabaseCompareTest.finish(jobs,compare.catalog("human",input));ArrayNode data=input.putArray("tableData");
                for(JsonNode o:catalog.path("objects"))if(o.path("kind").asText().equals("tables")&&!o.path("name").asText().equals("tickets"))data.addObject().put("id",o.path("id").asText()).put("includeData",true);
                String id=DatabaseCompareTest.finish(jobs,compare.start("human",input)).path("comparisonId").asText();ObjectNode selection=DatabaseCompareTest.select(compare,id);
                for(JsonNode option:selection.path("objects")){JsonNode o=compare.object("human",id,option.path("id").asText());if(o.path("kind").asText().equals("tables")&&!o.path("name").asText().equals("tickets"))((ObjectNode)option).put("includeData",true);if(o.path("kind").asText().equals("sequences"))((ObjectNode)option).put("syncValues",true);}
                JsonNode artifact=DatabaseCompareTest.finish(jobs,compare.generate("human",id,selection));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();
                Path evidence=Path.of(System.getenv("DBA_COMPARE_EVIDENCE"));Files.createDirectories(evidence);Files.writeString(evidence.resolve(engine+"-generated.sql"),script);
                try(ResultSet rows=b.executeQuery("SELECT COUNT(*) FROM "+dq+"unrelated")){assertTrue(rows.next());assertEquals(1,rows.getInt(1));}
                if(engine.equals("postgresql"))b.execute(script);else for(String command:script.split(";\\s*(?:\\r?\\n|$)"))if(!command.isBlank())b.execute(command);
                try(ResultSet rows=b.executeQuery("SELECT id,title,amount FROM "+dq+"parent ORDER BY id")){assertTrue(rows.next());assertEquals("O'Brien",rows.getString(2));assertEquals("123456789012345.1250",rows.getBigDecimal(3).toPlainString());assertTrue(rows.next());assertEquals("9007199254740993",rows.getString(1));}
                try(ResultSet rows=b.executeQuery("SELECT person_id,person_title FROM "+dq+"names")){assertTrue(rows.next());}
                try(ResultSet rows=b.executeQuery("SELECT COUNT(*) FROM "+dq+"names")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));}
                if(engine.equals("postgresql"))try(ResultSet rows=b.executeQuery("SELECT nextval('dst.counter')")){assertTrue(rows.next());assertEquals("9007199254740998",rows.getString(1));}
                if(engine.equals("postgresql")){
                    try(ResultSet rows=b.executeQuery("SELECT nextval('dst.named_generator')")){assertTrue(rows.next());assertEquals("1005",rows.getString(1));}
                    try(ResultSet rows=b.executeQuery("SELECT nextval('dst.descending_counter')")){assertTrue(rows.next());assertEquals("-3",rows.getString(1));}
                    try(ResultSet rows=b.executeQuery("SELECT nextval('dst.cached_counter')")){assertTrue(rows.next());assertEquals("23",rows.getString(1));}
                    b.execute("INSERT INTO dst.tickets(id) VALUES(99)");try(ResultSet rows=b.executeQuery("SELECT state FROM dst.tickets WHERE id=99")){assertTrue(rows.next());assertEquals("open",rows.getString(1));}
                    b.execute("INSERT INTO dst.defaulted(id) VALUES(99)");try(ResultSet rows=b.executeQuery("SELECT title FROM dst.defaulted WHERE id=99")){assertTrue(rows.next());assertEquals("created",rows.getString(1));}
                }
                compare.remove("human",id);
                // Exercise existing identical table definitions and all data modes against fresh observations.
                for(String mode:List.of("insert","upsert","mirror","replace")){
                    b.execute("DELETE FROM "+dq+"child");b.execute("DELETE FROM "+dq+"parent");b.execute("INSERT INTO "+dq+"parent VALUES(1,'old',0),(3,'destination-only',1)");
                    input.put("sourceReceipt",DatabaseCompareTest.receipt(compare,jobs,source,sourceSchema)).put("destinationReceipt",DatabaseCompareTest.receipt(compare,jobs,destination,destSchema));
                    id=DatabaseCompareTest.finish(jobs,compare.start("human",input)).path("comparisonId").asText();selection=DatabaseCompareTest.select(compare,id).put("dataMode",mode).put("syncSequences",true);
                    for(JsonNode option:selection.path("objects")){JsonNode object=compare.object("human",id,option.path("id").asText());if(object.path("kind").asText().equals("tables")&&!object.path("name").asText().equals("tickets"))((ObjectNode)option).put("includeData",true);}
                    artifact=DatabaseCompareTest.finish(jobs,compare.generate("human",id,selection));script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();
                    Files.writeString(evidence.resolve(engine+"-"+mode+".sql"),script);if(engine.equals("postgresql"))b.execute(script);else for(String command:script.split(";\\s*(?:\\r?\\n|$)"))if(!command.isBlank())b.execute(command);
                    try(ResultSet rows=b.executeQuery("SELECT COUNT(*) FROM "+dq+"parent")){assertTrue(rows.next());assertEquals(Set.of("mirror","replace").contains(mode)?2:3,rows.getInt(1));}
                    try(ResultSet rows=b.executeQuery("SELECT title FROM "+dq+"parent WHERE id=1")){assertTrue(rows.next());assertEquals(mode.equals("insert")?"old":"O'Brien",rows.getString(1));}
                    compare.remove("human",id);
                }
                System.out.println("COMPARE_GENERATED_SCRIPT_VERIFIED "+engine+" "+src.getMetaData().getDatabaseProductVersion());
            }
        }
    }
    @Test @Timeout(180) void postgresStructureOnlyUsesScopedMetadataWithoutSequenceConsumerScans()throws Exception {
        Assumptions.assumeTrue("postgresql".equals(System.getenv("DBA_COMPARE_VENDOR")));
        try(Profiles profiles=new Profiles(directory,new DbaTest.MemoryVault());Connections connections=new Connections(profiles);QueryJobs jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,30),owner->true);DatabaseCompare compare=new DatabaseCompare(profiles,connections,jobs,directory,owner->true)){
            String source=profiles.put(null,profile("postgresql","Source scope",System.getenv("DBA_COMPARE_SOURCE"))).path("id").asText();
            String destination=profiles.put(null,profile("postgresql","Destination scope",System.getenv("DBA_COMPARE_DESTINATION"))).path("id").asText();
            try(Connection a=connections.open(source);Connection b=connections.open(destination);Statement left=a.createStatement();Statement right=b.createStatement()){
                a.setAutoCommit(true);b.setAutoCommit(true);left.execute("CREATE SCHEMA src_scope");right.execute("CREATE SCHEMA dst_scope");
                for(int i=0;i<150;i++)left.execute("CREATE TABLE src_scope.item_"+i+"(id INT PRIMARY KEY,note TEXT)");
                left.execute("CREATE SEQUENCE src_scope.counter;CREATE TABLE src_scope.consumer(id BIGINT DEFAULT nextval('src_scope.counter'));CREATE VIEW src_scope.unrelated_view AS SELECT 1 AS untouched");
                var target=compare.target(Profiles.JSON.createObjectNode().put("connectionId",source).put("schema","src_scope"));
                java.util.concurrent.atomic.AtomicInteger bulkReads=new java.util.concurrent.atomic.AtomicInteger();
                DatabaseCompareTest.finish(jobs,jobs.comparison("human",Set.of(source),job->compare.read(job,target,connection->{
                    Connection observed=(Connection)java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                        if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql){
                            if(sql.contains("WITH dependencies AS"))bulkReads.incrementAndGet();
                            if(sql.matches("(?is)^\\s*SELECT\\s+(MAX|MIN)\\(.*"))throw new IllegalArgumentException("Structure-only must not scan sequence consumers");
                        }
                        try{return method.invoke(connection,args);}catch(java.lang.reflect.InvocationTargetException error){throw error.getCause();}
                    });
                    var inventory=CompareCatalog.capture(job,observed,target,Set.of("tables","sequences"),false);
                    assertFalse(inventory.objects.containsKey(CompareCatalog.key("src_scope","views","unrelated_view")));
                    assertTrue(inventory.objects.containsKey(CompareCatalog.key("src_scope","sequences","counter")));
                    return Profiles.JSON.createObjectNode().put("objects",inventory.objects.size());
                }),()->{}));
                assertEquals(1,bulkReads.get());
                ObjectNode input=Profiles.JSON.createObjectNode().put("sourceReceipt",DatabaseCompareTest.receipt(compare,jobs,source,"src_scope")).put("destinationReceipt",DatabaseCompareTest.receipt(compare,jobs,destination,"dst_scope")).put("dataMode","none").put("syncSequences",false);
                input.putArray("objectTypes").add("tables").add("sequences");
                long started=System.nanoTime();String id=DatabaseCompareTest.finish(jobs,compare.start("human",input)).path("comparisonId").asText();
                left.execute("CREATE OR REPLACE VIEW src_scope.unrelated_view AS SELECT 2 AS untouched");
                ObjectNode selection=DatabaseCompareTest.select(compare,id).put("dataMode","none").put("syncSequences",false);
                JsonNode artifact=DatabaseCompareTest.finish(jobs,compare.generate("human",id,selection));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();
                assertFalse(script.contains("unrelated_view"));assertFalse(script.matches("(?s).*\\b(INSERT INTO|UPDATE |DELETE FROM|MERGE INTO|TRUNCATE )\\b.*"));right.execute(script);
                try(ResultSet rows=right.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema='dst_scope' AND table_type='BASE TABLE'")){assertTrue(rows.next());assertEquals(151,rows.getInt(1));}
                System.out.println("COMPARE_SCOPED_STRUCTURE_VERIFIED 151 tables, bulk dependencies, no consumer scans; elapsedMs="+java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            }
        }
    }
    static ObjectNode profile(String engine,String name,String url){return Profiles.JSON.createObjectNode().put("name",name).put("templateId",engine).put("driverClass",DatabaseCatalog.get(engine).driver()).put("jar",System.getenv("DBA_COMPARE_JAR")).put("url",url).put("username",System.getenv("DBA_COMPARE_USER")).put("password","compare-fixture-only").put("readOnly",false);}
}
