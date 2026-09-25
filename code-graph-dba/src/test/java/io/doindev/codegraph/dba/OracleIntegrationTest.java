package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Invoked only against the task-owned disposable Oracle container. */
@EnabledIfEnvironmentVariable(named="DBA_ORACLE_OWNER",matches="code-graph-oracle-[a-f0-9]+")
@Timeout(180)
class OracleIntegrationTest {
    @TempDir Path directory;
    private static JsonNode jars;
    @BeforeAll static void installDriver()throws Exception{
        var template=DatabaseCatalog.get("oracle");
        var bundles=new DriverBundles(Path.of(System.getenv("DBA_ORACLE_CACHE")),DriverDownloadConfig.embedded());
        var coordinates=Profiles.JSON.createObjectNode().put("groupId",template.group()).put("artifactId",template.artifact()).put("templateId","oracle");
        var status=bundles.status(coordinates,()->false);assertTrue(status.path("latestAvailable").asBoolean(),status.toString());
        coordinates.put("version",status.path("latestVersion").asText());jars=bundles.install(coordinates,()->false,p->{}).path("jars");
        System.out.println("ORACLE_JDBC_VERIFIED "+coordinates.path("version").asText());
    }
    private static ObjectNode draft(String user,String password){
        var result=Profiles.JSON.createObjectNode().put("name","Oracle disposable "+user).put("templateId","oracle").put("driverClass","oracle.jdbc.OracleDriver")
            .put("url",System.getenv("DBA_ORACLE_URL")).put("username",user).put("password",password).put("readOnly",false);
        result.set("jars",jars.deepCopy());return result;
    }
    private static ObjectNode systemDraft(){return draft("SYSTEM",System.getenv("DBA_ORACLE_PASSWORD"));}
    private QueryJobs jobs(Connections connections){return new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,30,100,30),s->true);}
    private static JsonNode complete(QueryJobs jobs,JsonNode submitted)throws Exception{
        var result=ConnectionSetupTest.await(jobs,"browser",submitted);assertEquals("complete",result.path("state").asText(),result.toString());return result.path("result");
    }
    @Test void resolvedPdbSchemaPrivilegesAndExplicitAdminRoles()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            var setup=new ConnectionSetup(profiles,jobs);var input=systemDraft();
            var test=complete(jobs,setup.operation("browser","draft-test",input));var target=test.path("oracle").path("target");
            assertEquals("FREEPDB1",target.path("container").asText());assertEquals("SYSTEM",target.path("schema").asText());assertTrue(test.path("oracle").path("supportedVersion").asBoolean());
            input.put("receipt",test.path("receipt").asText());String id=setup.save("browser",null,input).path("id").asText();
            try(var selected=connections.target(id,"FREEPDB1")){
                var c=selected.connection();Connections.selectSchema(c,"SYS");assertEquals("SYS",OracleDialect.target(c,10).schema());
                assertEquals("FREEPDB1",OracleDialect.target(c,10).database());
            }
            assertThrows(SQLException.class,()->connections.target(id,"OTHER_PDB"));
            var databases=complete(jobs,jobs.permissionTargets("browser",id,Profiles.JSON.createObjectNode().put("kind","databases")));
            assertEquals("FREEPDB1",databases.path("items").get(0).path("name").asText());
            var schemas=complete(jobs,jobs.permissionTargets("browser",id,Profiles.JSON.createObjectNode().put("kind","schemas").put("database","FREEPDB1")));
            assertFalse(schemas.path("items").isEmpty());
            var admin=draft("SYS",System.getenv("DBA_ORACLE_PASSWORD"));admin.putObject("properties").put("internal_logon","sysdba");
            var adminTest=complete(jobs,setup.operation("browser","draft-test",admin));assertEquals("SYS",adminTest.path("oracle").path("target").path("user").asText());
            String sys=profiles.put(null,admin).path("id").asText();try(var c=connections.open(sys)){
                assertTrue(assertThrows(IllegalArgumentException.class,()->OracleReads.verifyTarget(null,c,Profiles.JSON.createObjectNode().put("database","FREEPDB1").put("schema","SYS"))).getMessage().contains("SYS"));
            }
            admin.withObject("properties").put("internal_logon","invented_role");assertThrows(IllegalArgumentException.class,()->ConnectionDraft.create(admin,Profiles.JSON.createObjectNode()));
        }
    }
    @Test void leastPrivilegeMixedSqlPlsqlPrecisionAndQuotedDelimiters()throws Exception{
        String user="CG"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(),password="Cg"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){
                st.execute("CREATE USER "+OracleDialect.identifier(user)+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");
                st.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE TO "+OracleDialect.identifier(user));
            }
            String id=profiles.put(null,draft(user,password)).path("id").asText();
            var setup=new ConnectionSetup(profiles,jobs);var tested=complete(jobs,setup.operation("browser","draft-test",draft(user,password)));
            assertEquals(3,tested.path("oracle").path("sessionPrivileges").size(),tested.toString());assertTrue(tested.path("oracle").path("sessionRoles").isEmpty());
            try(var c=connections.open(id)){
                var job=jobs.new Job("browser",id);var other=new CompareCatalog.Target(id,"FREEPDB1","SYSTEM",false,"fixture");
                assertTrue(assertThrows(IllegalArgumentException.class,()->OracleCompare.capture(job,c,other,java.util.Set.of("tables"),false)).getMessage().contains("SELECT_CATALOG_ROLE"));
            }
            String script="""
                CREATE TABLE sample (id NUMBER(38), text_value NVARCHAR2(100), moment TIMESTAMP(9) WITH TIME ZONE);
                INSERT INTO sample VALUES (12345678901234567890123456789012345678, nq'[Unicode ; ? / '' Ω]', TO_TIMESTAMP_TZ('2026-09-24 12:34:56.123456789 +05:30','YYYY-MM-DD HH24:MI:SS.FF TZH:TZM'));
                BEGIN
                  INSERT INTO sample(id,text_value) VALUES (?,q'{second;?''}');
                END;
                /
                SELECT id,text_value,TO_CHAR(moment,'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM') AS moment FROM sample ORDER BY id DESC;
                """;
            var run=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,script,Profiles.JSON.createArrayNode().add(7),false));
            assertEquals("complete",run.path("state").asText(),run.toString());assertEquals(4,run.path("result").path("statements").size());
            var rows=run.path("result").path("rows");assertEquals("12345678901234567890123456789012345678",rows.get(0).get(0).asText());
            assertTrue(rows.get(0).get(1).asText().contains("Ω"));assertEquals("2026-09-24 12:34:56.123456789 +05:30",rows.get(0).get(2).asText());
            assertEquals("second;?''",rows.get(1).get(1).asText());assertTrue(rows.get(1).get(2).isNull());
            var objects=complete(jobs,jobs.permissionTargets("browser",id,Profiles.JSON.createObjectNode().put("kind","objects").put("database","FREEPDB1").put("schema",user)));
            assertTrue(objects.path("items").toString().contains("SAMPLE"));
        }
    }
    @Test void outputParametersRefCursorsServerOutputAndImplicitCommits()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String id=profiles.put(null,systemDraft()).path("id").asText();
            var parameters=Profiles.JSON.createArrayNode();
            parameters.addObject().put("mode","out").put("type","NUMBER");
            parameters.addObject().put("mode","out").put("type","REF_CURSOR");
            parameters.addObject().put("mode","out").put("type","CLOB");
            parameters.addObject().put("mode","out").put("type","DATE");
            String sql="""
                DECLARE n NUMBER:=12345678901234567890123456789012345670;
                BEGIN
                  ?:=n+1;
                  OPEN ? FOR SELECT LEVEL AS n FROM dual CONNECT BY LEVEL<=105;
                  ?:=TO_CLOB(RPAD('x',9000,'x'));
                  ?:=TO_DATE('2026-09-24 12:34:56','YYYY-MM-DD HH24:MI:SS');
                  FOR i IN 1..300 LOOP DBMS_OUTPUT.PUT_LINE('line '||i); END LOOP;
                END;
                """;
            var run=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,sql,parameters,false,null,true));
            assertEquals("complete",run.path("state").asText(),run.toString());var result=run.path("result");
            assertEquals("12345678901234567890123456789012345671",result.path("outputParameters").get(0).path("value").asText());
            assertEquals(8192,result.path("outputParameters").get(1).path("value").asText().length());assertTrue(result.path("outputParameters").get(1).path("truncated").asBoolean());
            assertEquals("2026-09-24T12:34:56",result.path("outputParameters").get(2).path("value").asText());
            assertEquals(30,result.path("rows").size());assertTrue(result.path("truncated").asBoolean());
            assertEquals(256,result.path("serverOutput").path("lines").size());assertTrue(result.path("serverOutput").path("truncated").asBoolean());
            var inout=Profiles.JSON.createArrayNode();inout.addObject().put("mode","inout").put("type","NUMBER").put("value","12345678901234567890123456789012345670");
            var echoed=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"DECLARE PROCEDURE increment_value(n IN OUT NUMBER) IS BEGIN n:=n+1; DBMS_OUTPUT.PUT_LINE(n); END; BEGIN DBMS_OUTPUT.PUT_LINE('next'); increment_value(?); END;",inout,false,null,true));
            assertEquals("complete",echoed.path("state").asText(),echoed.toString());assertEquals(2,echoed.path("result").path("serverOutput").path("lines").size());
            assertEquals("12345678901234567890123456789012345671",echoed.path("result").path("outputParameters").get(0).path("value").asText());
            var typed=Profiles.JSON.createArrayNode();
            typed.addObject().put("mode","in").put("type","NUMBER").put("value","12345678901234567890123456789012345678");
            typed.addObject().put("mode","in").put("type","TIMESTAMP_WITH_TIMEZONE").put("value","2026-09-24T12:34:56.123456789+05:30");
            typed.addObject().put("mode","in").put("type","DATE").put("value","2026-09-24T12:34:56");
            var typedResult=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT ? AS n, TO_CHAR(?,'YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM') AS tz,TO_CHAR(?,'YYYY-MM-DD HH24:MI:SS') AS d FROM dual",typed,false));
            assertEquals("complete",typedResult.path("state").asText(),typedResult.toString());
            var typedRows=typedResult.path("result").path("rows").get(0);assertEquals("12345678901234567890123456789012345678",typedRows.get(0).asText());
            assertEquals("2026-09-24 12:34:56.123456789 +05:30",typedRows.get(1).asText());assertEquals("2026-09-24 12:34:56",typedRows.get(2).asText());
            String table="CG"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase();
            var partial=HumanSqlTest.run(jobs,id,"CREATE TABLE "+table+" (id NUMBER); SELECT * FROM missing_oracle_validation_table",false);
            assertEquals("cancelled",partial.path("state").asText(),partial.toString());assertTrue(partial.path("result").path("changesMayAlreadyBeCommitted").asBoolean());
            try(var c=connections.open(id);var st=c.createStatement()){try(var rows=st.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(rows.next());}st.execute("DROP TABLE "+table+" PURGE");}
        }
    }

    @Test void nativePackageTypeSequenceDiagnosticsAndReviewedBodyChanges()throws Exception{
        String user="CG"+UUID.randomUUID().toString().replace("-","").substring(0,16).toUpperCase(),password="Cg"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){
                st.execute("CREATE USER "+OracleDialect.identifier(user)+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");
                st.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE PROCEDURE, CREATE TYPE, CREATE SEQUENCE, CREATE VIEW TO "+OracleDialect.identifier(user));
            }
            String id=profiles.put(null,draft(user,password)).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                st.execute("CREATE TABLE items(id NUMBER)");
                st.execute("CREATE PACKAGE sample_pkg AS FUNCTION amount RETURN NUMBER; END sample_pkg;");
                st.execute("CREATE PACKAGE BODY sample_pkg AS FUNCTION amount RETURN NUMBER IS n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM items; RETURN n+41; END; END sample_pkg;");
                st.execute("CREATE TYPE sample_type AS OBJECT(id NUMBER,MEMBER FUNCTION amount RETURN NUMBER)");
                st.execute("CREATE TYPE BODY sample_type AS MEMBER FUNCTION amount RETURN NUMBER IS BEGIN RETURN self.id; END; END;");
                st.execute("CREATE SEQUENCE counter START WITH 7 INCREMENT BY 3 CACHE 10");
                st.execute("CREATE FUNCTION broken_function RETURN NUMBER IS BEGIN RETURN missing_variable; END;");
            }
            var request=MetadataActionsTest.selection(jobs,id,"packages",user,"SAMPLE_PKG");var snapshot=ObjectDesignerTest.load(jobs,id,request);
            assertTrue(snapshot.path("ddlComplete").asBoolean(),snapshot.toString());assertTrue(snapshot.path("nativeMultiUnit").asBoolean());
            assertTrue(snapshot.path("ddl").asText().contains("PACKAGE BODY"));assertFalse(snapshot.path("details").path("Dependencies").isEmpty());assertFalse(snapshot.path("details").path("Parameters").isEmpty());
            assertEquals(1,snapshot.path("warnings").size(),snapshot.path("warnings").toString());assertEquals("FREEPDB1",snapshot.path("database").asText());
            var change=ObjectDesignerTest.draft(snapshot).put("sqlMode",true).put("splitSql",true).put("sql",snapshot.path("ddl").asText().replace("n+41","n+42"));
            var saved=ObjectDesignerTest.save(jobs,id,request,snapshot,change);assertEquals("success",saved.path("result").path("status").asText(),saved.toString());
            try(var c=connections.open(id);var st=c.createStatement();var rows=st.executeQuery("SELECT sample_pkg.amount FROM dual")){assertTrue(rows.next());assertEquals(42,rows.getInt(1));}
            var type=ObjectDesignerTest.load(jobs,id,MetadataActionsTest.selection(jobs,id,"types",user,"SAMPLE_TYPE"));assertTrue(type.path("nativeMultiUnit").asBoolean());assertTrue(type.path("ddl").asText().contains("TYPE BODY"));
            var sequence=ObjectDesignerTest.load(jobs,id,MetadataActionsTest.selection(jobs,id,"sequences",user,"COUNTER"));assertEquals(1,sequence.path("warnings").size(),sequence.path("warnings").toString());assertEquals("3",sequence.path("details").path("Advanced").get(0).path("increment_by").asText());
            var broken=ObjectDesignerTest.load(jobs,id,MetadataActionsTest.selection(jobs,id,"functions",user,"BROKEN_FUNCTION"));assertFalse(broken.path("details").path("Compilation errors").isEmpty());assertTrue(broken.path("details").path("Status").toString().contains("INVALID"));
            snapshot=ObjectDesignerTest.load(jobs,id,request);change=ObjectDesignerTest.draft(snapshot).put("sqlMode",true).put("splitSql",true).put("sql",snapshot.path("ddl").asText().replace("n+42","missing_variable"));
            var invalid=ObjectDesignerTest.save(jobs,id,request,snapshot,change);assertEquals("failed",invalid.path("result").path("status").asText(),invalid.toString());assertTrue(invalid.path("result").path("objectCommitted").asBoolean());assertFalse(invalid.path("result").path("compilationErrors").isEmpty());
            try(var c=connections.open(id);var docs=io.doindev.codegraph.store.DocumentStore.memory(64L<<20)){
                c.setAutoCommit(false);var scope=Profiles.JSON.createObjectNode().put("database","FREEPDB1").put("schema",user);ObjectNode[] observed={null};
                docs.replace(writer->{try{observed[0]=new CatalogScanner(c,profiles.get(id),scope,writer,()->false).scan();}catch(Exception failure){throw new RuntimeException(failure);}});
                assertEquals("FREEPDB1",observed[0].path("resolvedTarget").path("database").asText());assertTrue(observed[0].path("dependencies").asInt()>0,observed[0].toString());
                var kinds=new java.util.HashSet<String>();boolean[] badBody={false};
                docs.scan("o/",(key,value)->{JsonNode object=ProjectContexts.json(value);assertEquals(user,object.path("schema").asText());kinds.add(object.path("kind").asText());if(object.path("kind").asText().equals("package_body")){badBody[0]=true;assertEquals("INVALID",object.path("status").asText());assertFalse(object.path("compilationErrors").isEmpty());assertTrue(object.path("ddl").asText().contains("missing_variable"));}});
                assertTrue(kinds.containsAll(java.util.Set.of("table","package","package_body","type","type_body","function","sequence")),kinds.toString());assertTrue(badBody[0]);c.rollback();
            }
        }
    }

    @Test void nativeMetadataDocumentsGenerateDestinationAlterWithoutExecutingIt()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),a="CGS"+suffix,b="CGD"+suffix;
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){
                for(String owner:java.util.List.of(a,b))st.execute("CREATE USER "+OracleDialect.identifier(owner)+" NO AUTHENTICATION QUOTA 10M ON USERS");
                st.execute("CREATE TABLE "+OracleDialect.qualified(a,"ITEMS")+" (id NUMBER(18),label VARCHAR2(100 CHAR) DEFAULT 'source literal',extra TIMESTAMP(9))");
                st.execute("CREATE TABLE "+OracleDialect.qualified(b,"ITEMS")+" (id NUMBER(18),label VARCHAR2(30 CHAR))");
                try(var jobs=jobs(connections)){
                    var job=jobs.new Job("browser",admin);var from=new CompareCatalog.Target(admin,"FREEPDB1",a,false,"fixture");var to=new CompareCatalog.Target(admin,"FREEPDB1",b,false,"fixture");
                    var left=OracleCompare.capture(job,c,from,java.util.Set.of("tables"),false);var right=OracleCompare.capture(job,c,to,java.util.Set.of("tables"),false);
                    assertEquals(1,left.objects.size());assertTrue(left.objects.values().stream().allMatch(object->object.path("supported").asBoolean()),left.objects.toString());
                    OracleCompare.review(job,c,left,right,from,to);assertEquals(2,left.objects.values().iterator().next().path("oracleChanges").size(),left.objects.toString());
                }
                String source=OracleDocuments.capture(null,c,"TABLE",a,"ITEMS","SXML"),destination=OracleDocuments.capture(null,c,"TABLE",b,"ITEMS","SXML");
                String desired=OracleDocuments.remap(null,c,"TABLE",source,a,b,true,false);
                var changes=OracleDocuments.changes(null,c,"TABLE",destination,desired);assertEquals(2,changes.size());assertTrue(changes.stream().allMatch(change->change.blocker().isBlank()),changes.toString());
                try(var rows=st.executeQuery("SELECT COUNT(*) FROM all_tab_columns WHERE owner='"+b+"' AND table_name='ITEMS' AND column_name='EXTRA'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                for(var change:changes)for(String sql:change.statements())st.execute(sql);
                String changed=OracleDocuments.capture(null,c,"TABLE",b,"ITEMS","SXML");assertTrue(OracleDocuments.changes(null,c,"TABLE",changed,desired).isEmpty());
                st.execute("DROP TABLE "+OracleDialect.qualified(b,"ITEMS")+" PURGE");
                String xml=OracleDocuments.capture(null,c,"TABLE",a,"ITEMS","XML");String create=OracleDocuments.remap(null,c,"TABLE",xml,a,b,false,true);
                assertFalse(create.contains("\""+a+"\""));st.execute(create);
                assertTrue(OracleDocuments.changes(null,c,"TABLE",OracleDocuments.capture(null,c,"TABLE",b,"ITEMS","SXML"),desired).isEmpty());
            }
        }
    }

    @Test void applicationCompareAcrossIndependentOwnersGeneratesAndRevalidates()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),source="CGS"+suffix,destination="CGD"+suffix,password="Cg"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,120),s->true);var compare=new DatabaseCompare(profiles,connections,jobs,directory,s->true)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){
                for(String owner:java.util.List.of(source,destination)){
                    st.execute("CREATE USER "+OracleDialect.identifier(owner)+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");
                    st.execute("GRANT CREATE SESSION,CREATE TABLE,CREATE VIEW,CREATE PROCEDURE,CREATE SEQUENCE,CREATE TYPE TO "+OracleDialect.identifier(owner));
                }
            }
            String from=profiles.put(null,draft(source,password)).path("id").asText(),to=profiles.put(null,draft(destination,password)).path("id").asText();
            try(var c=connections.open(from);var st=c.createStatement()){
                st.execute("CREATE TABLE items(id NUMBER(18),label VARCHAR2(100 CHAR) DEFAULT 'source literal',extra TIMESTAMP(9),CONSTRAINT items_pk PRIMARY KEY(id))");
                st.execute("CREATE TABLE children(id NUMBER PRIMARY KEY,parent_id NUMBER(18),CONSTRAINT child_parent FOREIGN KEY(parent_id) REFERENCES items(id))");
                st.execute("CREATE SEQUENCE counter START WITH 7 INCREMENT BY 3 CACHE 10");
                st.execute("CREATE VIEW item_view AS SELECT id,label FROM items");
                st.execute("CREATE PACKAGE api AS FUNCTION total RETURN NUMBER; END;");
                st.execute("CREATE PACKAGE BODY api AS FUNCTION total RETURN NUMBER IS n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM "+OracleDialect.qualified(source,"ITEMS")+"; RETURN n; END; END;");
                st.execute("CREATE TYPE value_type AS OBJECT(id NUMBER)");
            }
            try(var c=connections.open(to);var st=c.createStatement()){st.execute("CREATE TABLE items(id NUMBER(18),label VARCHAR2(30 CHAR),CONSTRAINT items_pk PRIMARY KEY(id))");}
            var input=Profiles.JSON.createObjectNode().put("sourceReceipt",DatabaseCompareTest.receipt(compare,jobs,from,source)).put("destinationReceipt",DatabaseCompareTest.receipt(compare,jobs,to,destination)).put("dataMode","none").put("syncSequences",true);
            input.putArray("objectTypes").add("tables").add("views").add("packages").add("types").add("sequences");
            String id=compareFinished(jobs,compare.start("human",input)).path("comparisonId").asText();
            var results=compare.results("human",id,0,200,"","");assertEquals(0,results.path("counts").path("unsupported").asInt(),results.toPrettyString());assertEquals(6,results.path("objects").size(),results.toPrettyString());
            var selected=DatabaseCompareTest.select(compare,id).put("dataMode","none").put("syncSequences",true);
            var artifact=compareFinished(jobs,compare.generate("human",id,selected));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();
            try(var c=connections.open(from);var st=c.createStatement();var rows=st.executeQuery("SELECT last_number FROM user_sequences WHERE sequence_name='COUNTER'")){assertTrue(rows.next());assertEquals(7,rows.getInt(1),"Source NEXTVAL must not be consumed");}
            assertTrue(script.contains("RESTART START WITH 7"),script);assertFalse(script.contains("INSERT INTO"),script);assertFalse(script.contains("\""+source+"\"."),script);
            try(var c=connections.open(to);var st=c.createStatement()){
                try(var rows=st.executeQuery("SELECT COUNT(*) FROM user_views WHERE view_name='ITEM_VIEW'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1),"Generation must not execute its output");}
                for(var unit:SqlScript.extract(script,"oracle",1<<20))st.execute(unit.sql());
                try(var rows=st.executeQuery("SELECT api.total FROM dual")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                try(var rows=st.executeQuery("SELECT counter.NEXTVAL FROM dual")){assertTrue(rows.next());assertEquals(7,rows.getInt(1));}
            }
            var stale=compareAwait(jobs,compare.generate("human",id,selected));assertEquals("failed",stale.path("state").asText());assertTrue(stale.path("error").asText().contains("changed"),stale.toString());compare.remove("human",id);
            id=compareFinished(jobs,compare.start("human",input)).path("comparisonId").asText();results=compare.results("human",id,0,200,"","");assertEquals(6,results.path("counts").path("identical").asInt(),results.toPrettyString());compare.remove("human",id);
        }
    }

    @Test @Timeout(900) void allDataModesPreserveOracleScalarsLobsAndRejectStaleRows()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),source="CGS"+suffix,destination="CGD"+suffix,password="Cg"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,120),s->true);var compare=new DatabaseCompare(profiles,connections,jobs,directory,s->true)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            String definition=" (id NUMBER PRIMARY KEY,n NUMBER(38),label NVARCHAR2(1500),d DATE,ts TIMESTAMP(9),tz TIMESTAMP(9) WITH TIME ZONE,ltz TIMESTAMP(9) WITH LOCAL TIME ZONE,raw_value RAW(2000),body CLOB,nbody NCLOB,bytes BLOB,ym INTERVAL YEAR(9) TO MONTH,ds INTERVAL DAY(9) TO SECOND(9),bf BINARY_FLOAT,bd BINARY_DOUBLE,hidden_label VARCHAR2(30) INVISIBLE)";
            try(var c=connections.open(admin);var st=c.createStatement()){
                for(String owner:java.util.List.of(source,destination)){st.execute("CREATE USER "+OracleDialect.identifier(owner)+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION,CREATE TABLE TO "+OracleDialect.identifier(owner));}
                st.execute("GRANT SELECT_CATALOG_ROLE TO "+OracleDialect.identifier(destination));
            }
            String from=profiles.put(null,draft(source,password)).path("id").asText(),to=profiles.put(null,draft(destination,password)).path("id").asText();
            String text="Ω漢字😀'\\\n".repeat(1100);byte[] bytes=new byte[7001];new java.util.Random(7).nextBytes(bytes);
            for(String connection:java.util.List.of(from,to))try(var c=connections.open(connection);var st=c.createStatement()){st.execute("CREATE TABLE values_test"+definition);}
            try(var c=connections.open(from);var insert=c.prepareStatement("INSERT INTO values_test VALUES(1,12345678901234567890123456789012345678,?,TO_DATE('0099-12-31 23:59:58 AD','YYYY-MM-DD HH24:MI:SS AD'),TIMESTAMP '2026-09-24 12:34:56.123456789',TO_TIMESTAMP_TZ('2026-11-01 01:30:00.123456789 America/New_York EDT','YYYY-MM-DD HH24:MI:SS.FF9 TZR TZD'),TO_TIMESTAMP_TZ('2026-09-24 12:34:56.123456789 +05:30','YYYY-MM-DD HH24:MI:SS.FF9 TZH:TZM'),?,?,?,?,INTERVAL '+123456789-11' YEAR(9) TO MONTH,INTERVAL '-123456789 12:34:56.123456789' DAY(9) TO SECOND(9),1.2345678f,1.2345678901234567d)")){
                c.setAutoCommit(true);insert.setNString(1,"Ω漢字😀'".repeat(200));insert.setBytes(2,java.util.Arrays.copyOf(bytes,1500));insert.setCharacterStream(3,new java.io.StringReader(text));insert.setNCharacterStream(4,new java.io.StringReader(text));insert.setBinaryStream(5,new java.io.ByteArrayInputStream(bytes));insert.executeUpdate();
                try(var st=c.createStatement()){st.execute("UPDATE values_test SET hidden_label='hidden source' WHERE id=1");st.execute("INSERT INTO values_test(id,label,body,nbody,bytes) VALUES(2,'',EMPTY_CLOB(),EMPTY_CLOB(),EMPTY_BLOB())");}
            }
            var request=Profiles.JSON.createObjectNode().put("sourceReceipt",DatabaseCompareTest.receipt(compare,jobs,from,source)).put("destinationReceipt",DatabaseCompareTest.receipt(compare,jobs,to,destination));request.putArray("objectTypes").add("tables");
            var catalog=compareFinished(jobs,compare.catalog("human",request));assertEquals(1,catalog.path("objects").size());var table=catalog.path("objects").get(0);assertTrue(table.path("dataSupported").asBoolean(),catalog.toPrettyString());String tableId=table.path("id").asText();
            for(String mode:java.util.List.of("upsert","insert","replace","mirror")){
                try(var c=connections.open(to);var st=c.createStatement()){c.setAutoCommit(true);st.execute("TRUNCATE TABLE values_test");st.execute("INSERT INTO values_test(id,label) VALUES(1,'old')");st.execute("INSERT INTO values_test(id,label) VALUES(3,'retained')");}
                request.put("dataMode",mode);request.putArray("tableData").addObject().put("id",tableId).put("includeData",true);
                String id=compareFinished(jobs,compare.start("human",request)).path("comparisonId").asText();var results=compare.results("human",id,0,100,"","");assertEquals(0,results.path("counts").path("unsupported").asInt(),results.toPrettyString());
                var selection=DatabaseCompareTest.select(compare,id).put("dataMode",mode);for(JsonNode object:selection.path("objects"))((ObjectNode)object).put("includeData",true);
                var artifact=compareFinished(jobs,compare.generate("human",id,selection));String script=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();assertTrue(script.contains("ON COMMIT PRESERVE ROWS"));
                try(var c=connections.open(to);var st=c.createStatement()){
                    try(var rows=st.executeQuery("SELECT label FROM values_test WHERE id=1")){assertTrue(rows.next());assertEquals("old",rows.getString(1),"Generation must not execute data mutations");}
                    for(var unit:SqlScript.extract(script,"oracle",1<<20))st.execute(unit.sql());
                    try(var rows=st.executeQuery("SELECT COUNT(*) FROM values_test")){assertTrue(rows.next());assertEquals(mode.equals("upsert")||mode.equals("insert")?3:2,rows.getInt(1));}
                    try(var rows=st.executeQuery("SELECT label,body,nbody,bytes FROM values_test WHERE id=2")){assertTrue(rows.next());assertNull(rows.getString(1));assertEquals("",rows.getString(2));assertEquals("",rows.getString(3));assertArrayEquals(new byte[0],rows.getBytes(4));}
                    try(var rows=st.executeQuery("SELECT COUNT(*) FROM user_tables WHERE table_name LIKE 'cgraph_compare_%'")){assertTrue(rows.next());assertEquals(0,rows.getInt(1),"Generated staging objects must be disposed");}
                }
                compare.remove("human",id);id=compareFinished(jobs,compare.start("human",request)).path("comparisonId").asText();
                var data=compare.object("human",id,tableId).path("data").path("counts");assertEquals(mode.equals("insert")?1:2,data.path("identical").asInt(),data.toPrettyString());assertEquals(mode.equals("insert")?1:0,data.path("different").asInt(),data.toPrettyString());
                if(mode.equals("mirror")){
                    selection=DatabaseCompareTest.select(compare,id).put("dataMode",mode);for(JsonNode object:selection.path("objects"))((ObjectNode)object).put("includeData",true);
                    try(var c=connections.open(from);var st=c.createStatement()){c.setAutoCommit(true);st.execute("UPDATE values_test SET label='changed' WHERE id=2");}
                    var stale=compareAwait(jobs,compare.generate("human",id,selection));assertEquals("failed",stale.path("state").asText());assertTrue(stale.path("error").asText().contains("Source data changed"),stale.toPrettyString());
                }
                compare.remove("human",id);System.out.println("ORACLE_COMPARE_DATA_MODE_VERIFIED "+mode);
            }
        }
    }

    @Test void dataMirrorStagesAndRestoresForeignKeysWhenParentChanges()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),source="CGS"+suffix,destination="CGD"+suffix;
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections);var data=new CompareData(directory)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();var job=jobs.new Job("human",admin);
            try(var c=connections.open(admin);var st=c.createStatement()){
                c.setAutoCommit(true);for(String owner:java.util.List.of(source,destination)){
                    st.execute("CREATE USER "+OracleDialect.identifier(owner)+" NO AUTHENTICATION QUOTA 10M ON USERS");
                    st.execute("CREATE TABLE "+OracleDialect.qualified(owner,"PARENT")+" (id NUMBER PRIMARY KEY)");
                    st.execute("CREATE TABLE "+OracleDialect.qualified(owner,"CHILD")+" (id NUMBER PRIMARY KEY,parent_id NUMBER,CONSTRAINT child_parent FOREIGN KEY(parent_id) REFERENCES "+OracleDialect.qualified(owner,"PARENT")+"(id))");
                    int parent=owner.equals(source)?2:1;st.execute("INSERT INTO "+OracleDialect.qualified(owner,"PARENT")+" VALUES("+parent+")");st.execute("INSERT INTO "+OracleDialect.qualified(owner,"CHILD")+" VALUES(7,"+parent+")");
                }
                var left=new CompareCatalog.Inventory("oracle","FREEPDB1","23");var right=new CompareCatalog.Inventory("oracle","FREEPDB1","23");left.schemas.add(source);right.schemas.add(destination);
                for(var inventory:java.util.List.of(left,right))for(String table:java.util.List.of("PARENT","CHILD")){
                    ObjectNode object=CompareCatalog.item(inventory==left?source:destination,"tables",table).put("supported",true);OracleCompareData.metadata(job,c,object);assertTrue(object.path("dataSupported").asBoolean(),object.toPrettyString());inventory.add(object);
                }
                var from=new CompareCatalog.Target(admin,"FREEPDB1",source,false,"fixture");var to=new CompareCatalog.Target(admin,"FREEPDB1",destination,false,"fixture");
                var diff=new CompareDiff(left,right,from,to);var plan=new CompareSql.Plan(left,right,from,to,Profiles.JSON.createObjectNode().put("dataMode","mirror"));
                for(var object:diff.objects.values()){
                    var option=Profiles.JSON.createObjectNode().put("includeData",true);var table=data.table(object,option);table.left=data.capture(job,c,"oracle",table,true);table.right=data.capture(job,c,"oracle",table,false);data.compare(job,table);plan.data.add(new CompareSql.Choice(object.source,object.destination,option));
                }
                CompareDataSql.validate(plan,data);var writer=new java.io.StringWriter();plan.header(writer);CompareDataSql.write(job,plan,data,writer);plan.footer(writer);String script=writer.toString();
                assertTrue(script.contains("DISABLE CONSTRAINT"));assertTrue(script.contains("ENABLE VALIDATE CONSTRAINT"));for(var unit:SqlScript.extract(script,"oracle",1<<20))st.execute(unit.sql());
                try(var rows=st.executeQuery("SELECT p.id,c.parent_id FROM "+OracleDialect.qualified(destination,"PARENT")+" p JOIN "+OracleDialect.qualified(destination,"CHILD")+" c ON c.parent_id=p.id")){assertTrue(rows.next());assertEquals(2,rows.getInt(1));assertEquals(2,rows.getInt(2));assertFalse(rows.next());}
                try(var rows=st.executeQuery("SELECT status,validated FROM all_constraints WHERE owner='"+destination+"' AND constraint_name='CHILD_PARENT'")){assertTrue(rows.next());assertEquals("ENABLED",rows.getString(1));assertEquals("VALIDATED",rows.getString(2));}
            }
        }
    }

    @Test void oracleTableDdlCancellationKeepsOutcomeUncertain()throws Exception{
        String user="CG_CANCEL_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION,CREATE TABLE,CREATE TRIGGER TO "+user);}
            String id=profiles.put(null,draft(user,password)).path("id").asText();
            try{
                try(var c=connections.open(id);var st=c.createStatement()){st.execute("CREATE TRIGGER DELAY_CREATION AFTER CREATE ON SCHEMA BEGIN IF ORA_DICT_OBJ_NAME='DELAYED' THEN DBMS_APPLICATION_INFO.SET_ACTION('CG_DDL_DELAY'); DBMS_SESSION.SLEEP(60); END IF; END;");}
                var request=TableCreationTest.input(user);((ObjectNode)request.path("target")).put("database","FREEPDB1");var baseline=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,request,false));assertEquals("complete",baseline.path("state").asText(),baseline.toString());
                var snapshot=(ObjectNode)baseline.path("result");var draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("name","DELAYED");((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("columns")).addObject().put("id","new:id").put("name","ID").put("type","NUMBER").put("pk",0).put("nullable",true);request.put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
                var review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());
                var active=jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true));
                try(var c=connections.open(admin);var st=c.prepareStatement("SELECT COUNT(*) FROM SYS.V_$SESSION WHERE USERNAME=? AND ACTION='CG_DDL_DELAY'")){
                    st.setString(1,user);st.setQueryTimeout(5);boolean started=false;long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
                    while(System.nanoTime()<until){try(var rs=st.executeQuery()){assertTrue(rs.next());if(rs.getInt(1)>0){started=true;break;}}Thread.sleep(20);}assertTrue(started,"DDL must reach the server-side delay before cancellation");
                }
                jobs.cancel(jobs.require("human",active.path("id").asText()));var result=HumanSqlTest.finish(jobs,"human",active);assertEquals("cancelled",result.path("state").asText(),result.toString());assertEquals("unknown",result.path("result").path("outcome").asText(),result.toString());assertTrue(result.path("result").path("steps").isEmpty());System.out.println("ORACLE_DDL_CANCEL_OUTCOME_VERIFIED");jobs.remove("human",review.path("id").asText());
            }finally{
                connections.remove(id);
                // A cancelled client does not prove Oracle stopped its DDL trigger. Only this
                // ownership-gated fixture administratively disconnects its own sleeping sessions.
                try(var c=connections.open(admin);var select=c.prepareStatement("SELECT SID,SERIAL# FROM SYS.V_$SESSION WHERE USERNAME=?");var st=c.createStatement()){
                    select.setString(1,user);select.setQueryTimeout(5);var sessions=new java.util.ArrayList<String>();try(var rs=select.executeQuery()){while(rs.next())sessions.add(rs.getLong(1)+","+rs.getLong(2));}
                    for(String session:sessions)try{st.execute("ALTER SYSTEM DISCONNECT SESSION '"+session+"' IMMEDIATE");}catch(SQLException e){if(e.getErrorCode()!=30&&e.getErrorCode()!=31)throw e;}
                    long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);for(;;)try{st.execute("DROP USER "+user+" CASCADE");break;}catch(SQLException e){if(e.getErrorCode()!=1940||System.nanoTime()>=until)throw e;Thread.sleep(100);}
                }
            }
        }
    }

    @Test @Timeout(180) void oracleDataPumpLeastPrivilegeOwnSchemaAndPrivateHistory()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),user="CG_DPL_"+suffix,name="CG_DP_LONG_IDENTIFIER_FOR_ORACLE19_"+suffix,password="Cg"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,128L<<20,2,100,100,120),x->true)){
            String admin=profiles.put(null,systemDraft()).path("id").asText(),limited="";
            try(var c=connections.open(admin);var st=c.createStatement()){st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION,CREATE TABLE TO "+user);st.execute("GRANT READ,WRITE ON DIRECTORY DATA_PUMP_DIR TO "+user);}
            try{
                limited=profiles.put(null,draft(user,password)).path("id").asText();try(var c=connections.open(limited);var st=c.createStatement()){st.execute("CREATE TABLE ITEMS(id NUMBER)");}
                var service=new OracleAdministration(connections,jobs,directory);var catalog=dataPumpJob(jobs,service.read("human",Profiles.JSON.createObjectNode().put("connectionId",limited).put("category","datapump")));assertTrue(catalog.path("result").path("available").asBoolean(),catalog.toString());assertTrue(catalog.path("result").has("scopeNotice"));jobs.remove("human",catalog.path("id").asText());
                var request=Profiles.JSON.createObjectNode().put("connectionId",limited).put("action","datapump_export").put("owner","SYSTEM").put("name",name).put("directory","DATA_PUMP_DIR").put("dumpFile",name+".dmp").put("logFile",name+".log");
                var denied=dataPumpJob(jobs,service.prepare("human",request));assertEquals("failed",denied.path("state").asText());assertTrue(denied.path("error").asText().contains("DATAPUMP_EXP_FULL_DATABASE"));jobs.remove("human",denied.path("id").asText());
                request.put("owner",user);dataPumpApply(service,jobs,request);var status=Profiles.JSON.createObjectNode().put("connectionId",limited).put("owner",user).put("name",name);awaitDataPumpComplete(service,jobs,status);
                var restarted=new OracleAdministration(connections,jobs,directory);var observed=dataPumpJob(jobs,restarted.dataPumpStatus("human",status));assertEquals("COMPLETED",observed.path("result").path("previousObservation").path("state").asText(),observed.toString());jobs.remove("human",observed.path("id").asText());assertFalse(java.nio.file.Files.readString(directory.resolve("oracle-datapump-observations.json")).contains(password));
                System.out.println("ORACLE_DATAPUMP_LEAST_PRIVILEGE_VERIFIED");
            }finally{
                if(!limited.isEmpty())connections.remove(limited);try(var c=connections.open(admin);var st=c.createStatement()){
                    try{st.execute("DECLARE h NUMBER; BEGIN h:=SYS.DBMS_DATAPUMP.ATTACH('"+name+"','"+user+"'); SYS.DBMS_DATAPUMP.STOP_JOB(h,1,0,0); END;");}catch(SQLException ignored){}
                    for(String file:java.util.List.of(name+".dmp",name+".log"))try{st.execute("BEGIN SYS.UTL_FILE.FREMOVE('DATA_PUMP_DIR','"+file+"'); END;");}catch(SQLException ignored){}
                    st.execute("DROP USER "+user+" CASCADE");
                }
            }
        }
    }
    @Test @Timeout(300) void oracleDataPumpExportImportStopResumeAndRestartDiscovery()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),source="CG_DPS_"+suffix,destination="CG_DPD_"+suffix,export="CG_EXP_"+suffix,importJob="CG_IMP_"+suffix;
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,128L<<20,2,100,100,120),x->true)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();var service=new OracleAdministration(connections,jobs);
            try(var c=connections.open(admin);var st=c.createStatement()){c.setAutoCommit(true);for(String user:java.util.List.of(source,destination))st.execute("CREATE USER "+user+" NO AUTHENTICATION QUOTA 10M ON USERS");st.execute("CREATE TABLE "+source+".ITEMS(id NUMBER PRIMARY KEY,label VARCHAR2(50))");st.execute("INSERT INTO "+source+".ITEMS SELECT LEVEL,'row '||LEVEL FROM SYS.DUAL CONNECT BY LEVEL<=100");}
            try{
                var request=Profiles.JSON.createObjectNode().put("connectionId",admin).put("action","datapump_export").put("name",export).put("owner",source).put("directory","DATA_PUMP_DIR").put("dumpFile",export+".dmp").put("logFile",export+".log");
                dataPumpApply(service,jobs,request);dataPumpApply(service,jobs,Profiles.JSON.createObjectNode().put("connectionId",admin).put("action","datapump_stop").put("name",export).put("owner","SYSTEM"));var statusRequest=Profiles.JSON.createObjectNode().put("connectionId",admin).put("owner","SYSTEM").put("name",export);
                var restarted=new OracleAdministration(connections,jobs);var discovery=dataPumpJob(jobs,restarted.read("human",Profiles.JSON.createObjectNode().put("connectionId",admin).put("category","datapump").put("filter","SYSTEM")));assertTrue(discovery.path("result").path("rows").toString().contains(export),discovery.toString());
                var stopped=dataPumpJob(jobs,restarted.dataPumpStatus("human",statusRequest));assertEquals("complete",stopped.path("state").asText(),stopped.toString());assertTrue(stopped.path("result").path("available").asBoolean(),stopped.toString());
                dataPumpApply(restarted,jobs,Profiles.JSON.createObjectNode().put("connectionId",admin).put("action","datapump_resume").put("name",export).put("owner","SYSTEM"));
                awaitDataPumpComplete(restarted,jobs,statusRequest);
                request.put("action","datapump_import").put("name",importJob).put("destinationOwner",destination).put("logFile",importJob+".log");
                dataPumpApply(restarted,jobs,request);statusRequest.put("name",importJob);awaitDataPumpComplete(restarted,jobs,statusRequest);
                try(var c=connections.open(admin);var st=c.createStatement();var rows=st.executeQuery("SELECT COUNT(*),MAX(label) FROM "+destination+".ITEMS")){assertTrue(rows.next());assertEquals(100,rows.getInt(1));assertEquals("row 99",rows.getString(2));}
                statusRequest.put("name","CG_MISSING_"+suffix);var missing=dataPumpJob(jobs,restarted.dataPumpStatus("human",statusRequest));assertEquals("UNKNOWN",missing.path("result").path("state").asText());assertFalse(missing.path("result").path("available").asBoolean());
                System.out.println("ORACLE_DATAPUMP_LIFECYCLE_VERIFIED");
            }finally{
                try(var c=connections.open(admin);var st=c.createStatement()){
                    // Only unique task-owned jobs/files/users. A stopped job is intentionally discarded during fixture cleanup.
                    for(String name:java.util.List.of(export,importJob)){try{st.execute("DECLARE h NUMBER; BEGIN h:=SYS.DBMS_DATAPUMP.ATTACH('"+name+"','SYSTEM'); SYS.DBMS_DATAPUMP.STOP_JOB(h,1,0,0); END;");}catch(SQLException ignored){}try{st.execute("DROP TABLE SYSTEM."+name+" PURGE");}catch(SQLException e){if(e.getErrorCode()!=942)throw e;}}
                    for(String file:java.util.List.of(export+".dmp",export+".log",importJob+".log"))try{st.execute("BEGIN SYS.UTL_FILE.FREMOVE('DATA_PUMP_DIR','"+file+"'); END;");}catch(SQLException ignored){}
                    for(String user:java.util.List.of(source,destination))st.execute("DROP USER "+user+" CASCADE");
                }
            }
        }
    }
    private static JsonNode dataPumpJob(QueryJobs jobs,JsonNode submitted)throws Exception{
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(130);for(;;){var state=jobs.status("human",submitted.path("id").asText());if(state.path("finished").asLong()>0)return state;if(System.nanoTime()>until)throw new AssertionError("Data Pump application operation did not finish: "+state);Thread.sleep(50);}
    }
    private static JsonNode dataPumpApply(OracleAdministration service,QueryJobs jobs,ObjectNode request)throws Exception{
        var review=dataPumpJob(jobs,service.prepare("human",request));assertEquals("complete",review.path("state").asText(),review.toString());String id=review.path("id").asText();
        try{var result=dataPumpJob(jobs,service.apply("human",Profiles.JSON.createObjectNode().put("planId",id).put("confirmed",true)));assertEquals("success",result.path("result").path("status").asText(),result.toString());jobs.remove("human",result.path("id").asText());return result;}finally{jobs.remove("human",id);}
    }
    private static void awaitDataPumpComplete(OracleAdministration service,QueryJobs jobs,ObjectNode request)throws Exception{
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(100);JsonNode last=null;while(System.nanoTime()<until){last=dataPumpJob(jobs,service.dataPumpStatus("human",request));jobs.remove("human",last.path("id").asText());var result=last.path("result");if(result.path("state").asText().equals("COMPLETED")){assertEquals(0,result.path("errorCount").asInt(),last.toString());return;}Thread.sleep(250);}throw new AssertionError("Data Pump completion was not observed: "+last);
    }
    @Test @Timeout(120) void oracleAdministrationTargetsExactSessionsAndActiveSql()throws Exception{
        String marker="CG_ADM_SESSION_"+UUID.randomUUID().toString().replace("-","").substring(0,16);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();var service=new OracleAdministration(connections,jobs);
            try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
                for(String operation:java.util.List.of("cancel_sql","disconnect_session","kill_session")){
                    // A separate connection profile keeps the controlled session out of the administration pool.
                    String victim=profiles.put(null,systemDraft().put("name",marker+operation)).path("id").asText();boolean intentionallyClosed=false;
                    try(var c=connections.open(victim);var st=c.createStatement()){
                        c.setAutoCommit(true);st.execute("BEGIN SYS.DBMS_SESSION.SET_IDENTIFIER('"+marker+operation+"'); END;");
                        java.util.concurrent.Future<Integer> sleeping=operation.equals("cancel_sql")?executor.submit(()->{try{st.execute("BEGIN SYS.DBMS_SESSION.SLEEP(30); END;");return 0;}catch(SQLException e){return e.getErrorCode();}}):null;
                        int sid=0,serial=0;String sqlId="";long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                        try(var observer=connections.open(admin);var query=observer.prepareStatement("SELECT s.SID,s.SERIAL#,s.SQL_ID,q.SQL_TEXT FROM SYS.V_$SESSION s LEFT JOIN SYS.V_$SQL q ON q.SQL_ID=s.SQL_ID AND q.CHILD_NUMBER=s.SQL_CHILD_NUMBER WHERE s.CLIENT_IDENTIFIER=?")){
                            query.setString(1,marker+operation);query.setQueryTimeout(10);
                            while(System.nanoTime()<until){try(var rows=query.executeQuery()){if(rows.next()&&(!operation.equals("cancel_sql")||java.util.Objects.toString(rows.getString(4),"").contains("DBMS_SESSION.SLEEP"))){sid=rows.getInt(1);serial=rows.getInt(2);sqlId=java.util.Objects.toString(rows.getString(3),"");break;}}Thread.sleep(20);}
                        }assertTrue(sid>0,"Controlled session must become observable");
                        var request=Profiles.JSON.createObjectNode().put("action",operation).put("sid",sid).put("serial",serial);
                        if(operation.equals("cancel_sql"))request.put("sqlId",sqlId);if(operation.equals("disconnect_session"))request.put("mode","IMMEDIATE");
                        adminApply(service,jobs,admin,request);
                        if(sleeping!=null){assertEquals(1013,sleeping.get(10,java.util.concurrent.TimeUnit.SECONDS));try(var check=c.createStatement();var rows=check.executeQuery("SELECT 1 FROM SYS.DUAL")){assertTrue(rows.next());}}
                        else {assertThrows(SQLException.class,()->st.execute("SELECT 1 FROM SYS.DUAL"));intentionallyClosed=true;}
                    }catch(SQLException closed){if(!intentionallyClosed||closed.getErrorCode()!=17008)throw closed;}finally{connections.remove(victim);}
                }
            }System.out.println("ORACLE_ADMINISTRATION_SESSIONS_VERIFIED");
        }
    }
    @Test @Timeout(300) void oracleAdministrationCatalogsReviewedChangesAndPrivilegeFailures()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),user="CG_ADM_"+suffix,role="CG_ROLE_"+suffix,profile="CG_PROFILE_"+suffix,tablespace="CG_TS_"+suffix,password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,128L<<20,2,100,100,60),s->true)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();var service=new OracleAdministration(connections,jobs);String limited="";
            try{
                var overview=HumanSqlTest.finish(jobs,"human",service.read("human",Profiles.JSON.createObjectNode().put("connectionId",admin)));assertEquals("complete",overview.path("state").asText(),overview.toString());assertEquals("FREEPDB1",overview.path("result").path("target").path("database").asText());assertTrue(overview.path("result").path("actions").size()>25);
                for(var category:OracleAdministration.CATALOGS){var page=HumanSqlTest.finish(jobs,"human",service.read("human",Profiles.JSON.createObjectNode().put("connectionId",admin).put("category",category.id())));assertEquals("complete",page.path("state").asText(),page.toString());assertTrue(page.path("result").path("available").asBoolean(),page.toString());assertTrue(page.path("result").path("rows").size()<=100);}
                var create=Profiles.JSON.createObjectNode().put("connectionId",admin).put("database","FREEPDB1").put("action","create_user").put("name",user).put("tablespace","USERS").put("temporaryTablespace","TEMP").put("profile","DEFAULT");
                var review=TableDesignerTest.waitRetained(jobs,"human",service.prepare("human",create));assertEquals("complete",review.path("state").asText(),review.toString());assertFalse(review.toString().contains(password));
                var apply=Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true);assertThrows(IllegalArgumentException.class,()->service.apply("human",apply));apply.put("password",password);var created=HumanSqlTest.finish(jobs,"human",service.apply("human",apply));assertEquals("success",created.path("result").path("status").asText(),created.toString());assertFalse(created.toString().contains(password));assertThrows(IllegalArgumentException.class,()->service.apply("human",apply));jobs.remove("human",review.path("id").asText());
                for(String privilege:java.util.List.of("CREATE SESSION","CREATE TABLE","CREATE PROCEDURE"))adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","grant_system").put("name",user).put("privilege",privilege));
                adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","quota").put("name",user).put("tablespace","USERS").put("quotaMB",10));
                adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","create_role").put("name",role));adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","grant_role").put("name",user).put("role",role));
                adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","create_profile").put("name",profile).put("resource","FAILED_LOGIN_ATTEMPTS").put("limit","8"));adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","user_profile").put("name",user).put("profile",profile));adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","alter_profile").put("name",profile).put("resource","FAILED_LOGIN_ATTEMPTS").put("limit","7"));
                limited=profiles.put(null,draft(user,password)).path("id").asText();try(var c=connections.open(limited);var st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE TABLE ITEMS(ID NUMBER)");st.execute("INSERT INTO ITEMS VALUES(1)");st.execute("CREATE FUNCTION ANSWER RETURN NUMBER IS BEGIN RETURN 42; END;");}
                var denied=HumanSqlTest.finish(jobs,"human",service.read("human",Profiles.JSON.createObjectNode().put("connectionId",limited).put("category","users")));assertFalse(denied.path("result").path("available").asBoolean());assertTrue(denied.path("result").path("message").asText().contains("privileges"));
                var forbidden=HumanSqlTest.finish(jobs,"human",service.prepare("human",Profiles.JSON.createObjectNode().put("connectionId",limited).put("action","create_role").put("name","NOT_CREATED")));assertEquals("failed",forbidden.path("state").asText());assertTrue(forbidden.path("error").asText().contains("CREATE ROLE"));
                for(String operation:java.util.List.of("gather_statistics","lock_statistics","unlock_statistics"))adminApply(service,jobs,limited,Profiles.JSON.createObjectNode().put("action",operation).put("owner",user).put("name","ITEMS"));
                adminApply(service,jobs,limited,Profiles.JSON.createObjectNode().put("action","compile").put("owner",user).put("name","ANSWER").put("objectType","FUNCTION"));
                for(String operation:java.util.List.of("grant_object","revoke_object"))adminApply(service,jobs,limited,Profiles.JSON.createObjectNode().put("action",operation).put("name",role).put("owner",user).put("object","ITEMS").put("privilege","SELECT"));
                var lock=Profiles.JSON.createObjectNode().put("connectionId",admin).put("action","user_lock").put("name",user);review=TableDesignerTest.waitRetained(jobs,"human",service.prepare("human",lock));assertEquals("complete",review.path("state").asText(),review.toString());
                adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","user_profile").put("name",user).put("profile","DEFAULT"));var stale=HumanSqlTest.finish(jobs,"human",service.apply("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("not_applied",stale.path("result").path("outcome").asText());assertTrue(stale.path("result").path("message").asText().contains("changed"));jobs.remove("human",review.path("id").asText());
                adminApply(service,jobs,admin,lock);adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","user_unlock").put("name",user));
                String file="/opt/oracle/oradata/FREE/FREEPDB1/"+tablespace.toLowerCase()+".dbf";adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","create_tablespace").put("name",tablespace).put("fileName",file).put("sizeMB",10).put("maxMB",20));
                int fileId;try(var c=connections.open(admin);var st=c.prepareStatement("SELECT FILE_ID FROM SYS.DBA_DATA_FILES WHERE TABLESPACE_NAME=?")){st.setString(1,tablespace);try(var rs=st.executeQuery()){assertTrue(rs.next());fileId=rs.getInt(1);}}
                adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","file_resize").put("fileId",fileId).put("kind","DATAFILE").put("sizeMB",15));adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","file_autoextend").put("fileId",fileId).put("kind","DATAFILE").put("maxMB",30));
                for(String state:java.util.List.of("READ ONLY","READ WRITE"))adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","tablespace_status").put("name",tablespace).put("status",state));
                adminApply(service,jobs,admin,Profiles.JSON.createObjectNode().put("action","drop_tablespace").put("name",tablespace).put("includingContents",true).put("deleteFiles",true));
                System.out.println("ORACLE_ADMINISTRATION_REVIEW_VERIFIED");
            }finally{
                if(!limited.isEmpty())connections.remove(limited);
                try(var c=connections.open(admin);var st=c.createStatement()){
                    for(String sql:java.util.List.of("DROP USER "+user+" CASCADE","DROP ROLE "+role,"DROP PROFILE "+profile+" CASCADE","DROP TABLESPACE "+tablespace+" INCLUDING CONTENTS AND DATAFILES"))try{st.execute(sql);}catch(SQLException e){if(!java.util.Set.of(1918,1919,2380,959).contains(e.getErrorCode()))throw e;}
                }
            }
        }
    }
    private static JsonNode adminApply(OracleAdministration service,QueryJobs jobs,String connection,ObjectNode request)throws Exception{
        request.put("connectionId",connection).put("database","FREEPDB1");var plan=TableDesignerTest.waitRetained(jobs,"human",service.prepare("human",request));assertEquals("complete",plan.path("state").asText(),plan.toString());
        var result=HumanSqlTest.finish(jobs,"human",service.apply("human",Profiles.JSON.createObjectNode().put("planId",plan.path("id").asText()).put("confirmed",true)));assertEquals("success",result.path("result").path("status").asText(),result.toString());jobs.remove("human",plan.path("id").asText());return result;
    }

    @Test @Timeout(300) void oracleMigrationsReviewRehearsalStaleDefinitionsAndPartialCommits()throws Exception{
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,10).toUpperCase(),source="CG_MIG_S_"+suffix,destination="CG_MIG_D_"+suffix,password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){for(String user:java.util.List.of(source,destination)){st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION,CREATE TABLE,CREATE VIEW,CREATE PROCEDURE TO "+user);}}
            String from=profiles.put(null,draft(source,password)).path("id").asText(),to=profiles.put(null,draft(destination,password)).path("id").asText();
            try{
                try(var c=connections.open(from);var st=c.createStatement()){c.setAutoCommit(true);st.execute("CREATE TABLE ITEMS(ID NUMBER PRIMARY KEY)");}
                var agents=new AgentAccess(directory);String principal=agents.trustedLocal(),owner="agent:"+principal;
                try(var contexts=new ProjectContexts(profiles,connections,agents);var jobs=new QueryJobs(connections,new DbaConfig(directory,128L<<20,2,100,100,60),agents::alive);var plans=new MigrationPlans();var approvals=new ApprovalQueue(contexts,profiles,agents,jobs)){
                    approvals.migrations(plans);approvals.reusable.sessions.register("migration",principal,System.currentTimeMillis()+300000);
                    var sourceTarget=oracleMigrationTarget(profiles,from,source);var target=oracleMigrationTarget(profiles,to,destination);
                    var baseline=MigrationPlansTest.await(jobs,owner,jobs.captureSchema(owner,sourceTarget,()->{}));assertEquals("FREEPDB1",baseline.result.path("resolvedTarget").path("database").asText());
                    var preparation=Profiles.JSON.createObjectNode().put("snapshotId",baseline.id);preparation.putArray("changes").addObject().put("action","add_column").put("table","ITEMS").put("column","AMOUNT").put("type","NUMBER(38)");
                    var plan=plans.require(owner,plans.prepare(owner,baseline,preparation,()->{}).path("id").asText());assertFalse(plan.value.path("transactional").asBoolean());assertTrue(plan.value.path("statements").get(0).asText().contains(" ADD \"AMOUNT\" NUMBER(38)"));
                    var empty=MigrationPlansTest.await(jobs,owner,jobs.captureSchema(owner,target,()->{}));var rehearsal=Profiles.JSON.createObjectNode().put("planId",plan.id).put("rehearsalSnapshotId",empty.id).put("setupSql","CREATE TABLE ITEMS(ID NUMBER PRIMARY KEY)").put("fixtureSql","INSERT INTO ITEMS(ID) VALUES(1)");rehearsal.putArray("checks").add("SELECT ID,AMOUNT FROM ITEMS");
                    var rehearsed=plans.require(owner,plans.prepareRehearsal(owner,plan,empty,rehearsal,()->{}).path("id").asText());assertTrue(rehearsed.value.path("statements").get(2).asText().contains(OracleDialect.identifier(destination)));assertFalse(rehearsed.value.path("statements").get(2).asText().contains(OracleDialect.identifier(source)));
                    var completed=oracleApproveMigration(approvals,jobs,principal,rehearsed,"complete");assertTrue(completed.path("job").path("result").path("rehearsal").asBoolean());assertEquals("1",completed.path("job").path("result").path("validations").get(0).path("result").path("rows").get(0).get(0).asText());
                    try(var c=connections.open(from);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM USER_TAB_COLUMNS WHERE TABLE_NAME='ITEMS' AND COLUMN_NAME='AMOUNT'")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
                    oracleApproveMigration(approvals,jobs,principal,plan,"complete");assertThrows(IllegalArgumentException.class,()->plans.require(owner,plan.id));
                    var current=MigrationPlansTest.await(jobs,owner,jobs.captureSchema(owner,sourceTarget,()->{}));var ddl=Profiles.JSON.createObjectNode().put("snapshotId",current.id).put("sql","COMMENT ON TABLE ITEMS IS 'must not apply'");var stale=plans.require(owner,plans.prepare(owner,current,ddl,()->{}).path("id").asText());
                    try(var c=connections.open(from);var st=c.createStatement()){st.execute("ALTER TABLE ITEMS PCTFREE 21");}
                    var rejected=oracleApproveMigration(approvals,jobs,principal,stale,"failed");assertEquals("not_started",rejected.path("job").path("outcome").asText());assertTrue(rejected.path("job").path("error").asText().contains("changed"));
                    current=MigrationPlansTest.await(jobs,owner,jobs.captureSchema(owner,sourceTarget,()->{}));ddl.put("snapshotId",current.id).put("sql","ALTER TABLE ITEMS ADD COMMITTED_STEP NUMBER; ALTER TABLE ITEMS ADD AMOUNT NUMBER;");var partial=plans.require(owner,plans.prepare(owner,current,ddl,()->{}).path("id").asText());var failed=oracleApproveMigration(approvals,jobs,principal,partial,"failed");assertEquals("partial_or_unknown",failed.path("job").path("outcome").asText());assertEquals(1,failed.path("job").path("result").path("steps").size(),failed.toString());
                    try(var c=connections.open(from);var st=c.createStatement();var rs=st.executeQuery("SELECT COMMITTED_STEP FROM ITEMS")){assertEquals("COMMITTED_STEP",rs.getMetaData().getColumnName(1));}
                    current=MigrationPlansTest.await(jobs,owner,jobs.captureSchema(owner,sourceTarget,()->{}));ddl.put("snapshotId",current.id).put("sql","CREATE OR REPLACE FUNCTION ANSWER RETURN VARCHAR2 IS BEGIN RETURN q'[semi; literal]'; END;\n/\nCOMMENT ON TABLE ITEMS IS 'created function';");var routine=plans.require(owner,plans.prepare(owner,current,ddl,()->{}).path("id").asText());assertEquals(2,routine.value.path("statements").size());oracleApproveMigration(approvals,jobs,principal,routine,"complete");
                    try(var c=connections.open(from);var st=c.createStatement();var rs=st.executeQuery("SELECT ANSWER() FROM SYS.DUAL")){assertTrue(rs.next());assertEquals("semi; literal",rs.getString(1));}
                    current=MigrationPlansTest.await(jobs,owner,jobs.captureSchema(owner,sourceTarget,()->{}));ddl.put("snapshotId",current.id).put("sql","CREATE OR REPLACE FUNCTION BROKEN RETURN NUMBER IS BEGIN RETURN UNKNOWN_VALUE; END;\n/\n");var invalid=plans.require(owner,plans.prepare(owner,current,ddl,()->{}).path("id").asText());var invalidResult=oracleApproveMigration(approvals,jobs,principal,invalid,"failed");assertTrue(invalidResult.path("job").path("result").path("message").asText().contains("invalid"),invalidResult.toString());assertEquals(1,invalidResult.path("job").path("result").path("steps").size());
                    System.out.println("ORACLE_MIGRATION_REVIEW_REHEARSAL_VERIFIED");
                }
            }finally{connections.remove(from);connections.remove(to);try(var c=connections.open(admin);var st=c.createStatement()){for(String user:java.util.List.of(source,destination))st.execute("DROP USER "+user+" CASCADE");}}
        }
    }
    private static WorkflowTargets.Target oracleMigrationTarget(Profiles profiles,String id,String schema){
        var request=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName",profiles.get(id).path("name").asText()).put("database","FREEPDB1").put("schema",schema);
        return new WorkflowTargets.Target(profiles.get(id),ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),request),request,"Task-owned Oracle fixture");
    }
    private static JsonNode oracleApproveMigration(ApprovalQueue approvals,QueryJobs jobs,String principal,MigrationPlans.Plan plan,String expected)throws Exception{
        var request=Profiles.JSON.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("purpose","Validate the explicitly owned disposable Oracle migration fixture");
        var pending=approvals.migration(principal,"migration",request,plan);assertEquals("awaiting_approval",pending.path("state").asText(),pending.toString());
        approvals.decide("human",pending.path("id").asText(),"approve_once",true);long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(65);JsonNode result;
        do{result=approvals.get(principal,pending.path("id").asText());if(java.util.Set.of("complete","failed","cancelled").contains(result.path("state").asText()))break;Thread.sleep(20);}while(System.nanoTime()<until);
        assertEquals(expected,result.path("state").asText(),result.toString());jobs.remove("agent:"+principal,result.path("jobId").asText());return result;
    }

    @Test void oracleTreeActionsUseResolvedPdbAndRevalidateNativeObjects()throws Exception{
        String user="CG_ACTION_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION,CREATE TABLE,CREATE VIEW,CREATE MATERIALIZED VIEW,CREATE SEQUENCE,CREATE PROCEDURE,CREATE TYPE,CREATE SYNONYM,CREATE TRIGGER TO "+user);}
            String id=profiles.put(null,draft(user,password)).path("id").asText();
            try{
                try(var c=connections.open(id);var st=c.createStatement()){
                    c.setAutoCommit(true);st.execute("CREATE TABLE ITEMS(ID NUMBER, TITLE VARCHAR2(40))");st.execute("INSERT INTO ITEMS VALUES(1,'original')");st.execute("CREATE INDEX ITEM_INDEX ON ITEMS(ID)");
                    st.execute("CREATE MATERIALIZED VIEW M_ITEMS REFRESH COMPLETE ON DEMAND AS SELECT * FROM ITEMS");st.execute("INSERT INTO ITEMS VALUES(2,'added')");
                    st.execute("CREATE VIEW V_ITEMS AS SELECT * FROM ITEMS");st.execute("CREATE SEQUENCE COUNTER");st.execute("CREATE SYNONYM ALIAS_ITEMS FOR ITEMS");
                    st.execute("CREATE FUNCTION SAMPLE_F RETURN NUMBER IS BEGIN RETURN 1; END;");st.execute("CREATE PROCEDURE SAMPLE_P IS BEGIN NULL; END;");
                    st.execute("CREATE PACKAGE SAMPLE_PKG AS PROCEDURE RUN; END;");st.execute("CREATE PACKAGE BODY SAMPLE_PKG AS PROCEDURE RUN IS BEGIN NULL; END; END;");
                    st.execute("CREATE TYPE SAMPLE_TYPE AS OBJECT(ID NUMBER)");st.execute("CREATE TRIGGER SAMPLE_TRIGGER BEFORE INSERT ON ITEMS BEGIN NULL; END;");
                }
                for(String[] object:new String[][]{{"materialized_views","M_ITEMS"},{"tables","ITEMS"}}){
                    var selection=MetadataActionsTest.selection(jobs,id,Profiles.JSON.createObjectNode().put("kind",object[0]).put("database","FREEPDB1").put("schema",user),object[1]);
                    var preview=MetadataActionsTest.preview(jobs,id,selection);assertEquals("FREEPDB1",preview.path("database").asText());assertEquals("FREEPDB1",preview.path("deleteCommandDetails").get(0).path("database").asText());
                    if(object[0].equals("materialized_views")){assertTrue(preview.path("canRefresh").asBoolean());var refreshed=MetadataActionsTest.action(jobs,id,selection,"refresh","");assertEquals("complete",refreshed.path("state").asText(),refreshed.toString());try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM M_ITEMS")){assertTrue(rs.next());assertEquals(2,rs.getInt(1));}}
                    else{
                        try(var c=connections.open(id);var st=c.createStatement()){st.execute("ALTER TABLE ITEMS PCTFREE 19");}
                        var stale=selection.deepCopy().put("fingerprint",preview.path("fingerprint").asText()).put("action","truncate").put("confirmed",true);
                        var rejected=HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,stale,true));assertEquals("failed",rejected.path("state").asText());assertTrue(rejected.path("error").asText().contains("changed"),rejected.toString());
                        ((ObjectNode)stale.path("parent")).put("database","CDB$ROOT");var wrong=HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,stale,true));assertEquals("failed",wrong.path("state").asText());assertTrue(wrong.path("error").asText().contains("selected database"),wrong.toString());
                        var truncated=MetadataActionsTest.action(jobs,id,selection,"truncate","");assertEquals("complete",truncated.path("state").asText(),truncated.toString());try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT COUNT(*) FROM ITEMS")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
                    }
                }
                for(String[] rename:new String[][]{{"indexes","ITEM_INDEX","new index"},{"tables","ITEMS","new table"}}){var selected=MetadataActionsTest.selection(jobs,id,rename[0],user,rename[1]);assertTrue(MetadataActionsTest.preview(jobs,id,selected).path("canRename").asBoolean());var result=MetadataActionsTest.action(jobs,id,selected,"rename",rename[2]);assertEquals("complete",result.path("state").asText(),result.toString());}
                var parent=Profiles.JSON.createObjectNode().put("kind","relation").put("schema",user).put("database","FREEPDB1").put("name","new table");
                var column=MetadataActionsTest.selection(jobs,id,parent,"TITLE");var renamed=MetadataActionsTest.action(jobs,id,column,"rename","new title");assertEquals("complete",renamed.path("state").asText(),renamed.toString());
                for(String[] object:new String[][]{{"views","V_ITEMS"},{"materialized_views","M_ITEMS"},{"sequences","COUNTER"},{"functions","SAMPLE_F"},{"procedures","SAMPLE_P"},{"packages","SAMPLE_PKG"},{"types","SAMPLE_TYPE"},{"synonyms","ALIAS_ITEMS"},{"table_triggers","SAMPLE_TRIGGER"},{"indexes","new index"},{"tables","new table"}}){var selected=MetadataActionsTest.selection(jobs,id,object[0],user,object[1]);var deleted=MetadataActionsTest.action(jobs,id,selected,"delete","");assertEquals("complete",deleted.path("state").asText(),deleted.toString());}
            }catch(Throwable failure){System.out.println("ORACLE_ACTION_FAILURE "+failure);throw failure;}
            finally{connections.remove(id);try(var c=connections.open(admin);var st=c.createStatement()){long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(35);for(;;)try{st.execute("DROP USER "+user+" CASCADE");break;}catch(SQLException e){if(e.getErrorCode()!=1940||System.nanoTime()>=until)throw e;Thread.sleep(100);}}}
        }
    }

    @Test void oracleReviewedTableCreationAndIncrementalEditing()throws Exception{
        String user="CG_DESIGN_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION,CREATE TABLE,CREATE SEQUENCE TO "+user);}
            String id=profiles.put(null,draft(user,password)).path("id").asText();
            try{
                var request=TableCreationTest.input(user);((ObjectNode)request.path("target")).put("database","FREEPDB1");
                var baseline=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,request,false));assertEquals("complete",baseline.path("state").asText(),baseline.toString());
                var snapshot=(ObjectNode)baseline.path("result");assertEquals("FREEPDB1",snapshot.path("database").asText());var draft=TableDesignerTest.draft(snapshot);request.put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);((ObjectNode)draft.path("fields")).put("name","ITEMS").put("comment","Created Ω漢字");
                var columns=(com.fasterxml.jackson.databind.node.ArrayNode)draft.path("columns");
                columns.addObject().put("id","new:id").put("name","ID").put("type","NUMBER").put("pk",1).put("nullable",false).put("identity","a");
                columns.addObject().put("id","new:amount").put("name","AMOUNT").put("type","NUMBER(38)").put("pk",0).put("nullable",false).put("default","12345678901234567890123456789012345678").put("comment","Precise");
                columns.addObject().put("id","new:label").put("name","LABEL").put("type","NVARCHAR2(40)").put("pk",0).put("nullable",true);
                columns.addObject().put("id","new:derived").put("name","DERIVED").put("type","NUMBER").put("pk",0).put("nullable",true).put("generated","AMOUNT+1");
                var review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());assertFalse(review.path("result").path("atomic").asBoolean());assertTrue(review.path("result").path("risky").asBoolean());
                var apply=Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true);
                var created=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("success",created.path("result").path("status").asText(),created+" review="+review.path("result"));assertThrows(IllegalArgumentException.class,()->jobs.applyTableProperties("human",apply));jobs.remove("human",review.path("id").asText());
                try(var c=connections.open(id);var st=c.createStatement()){
                    c.setAutoCommit(true);st.execute("INSERT INTO ITEMS(LABEL) VALUES(N'Unicode Ω漢字')");
                    try(var rs=st.executeQuery("SELECT ID,AMOUNT,DERIVED,LABEL FROM ITEMS")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));assertEquals("12345678901234567890123456789012345678",rs.getString(2));assertEquals("12345678901234567890123456789012345679",rs.getString(3));assertEquals("Unicode Ω漢字",rs.getNString(4));}
                    st.execute("ALTER TABLE ITEMS ADD (HIDDEN_TEXT VARCHAR2(20) INVISIBLE)");
                }
                var selection=MetadataActionsTest.selection(jobs,id,"tables",user,"ITEMS");
                var loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));assertEquals("complete",loaded.path("state").asText(),loaded.toString());snapshot=(ObjectNode)loaded.path("result");assertTrue(snapshot.path("editable").asBoolean(),snapshot.toString());assertEquals("a",snapshot.path("columns").get(0).path("identity").asText());assertTrue(snapshot.path("ddl").asText().contains("INVISIBLE"));
                draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("comment","Changed comment");for(JsonNode col:draft.path("columns"))if(col.path("name").asText().equals("LABEL"))((ObjectNode)col).put("type","NVARCHAR2(80)").put("comment","Expanded");
                var changes=(com.fasterxml.jackson.databind.node.ArrayNode)draft.path("objects");changes.addObject().put("category","Indexes").put("action","add").put("name","ITEM_LABEL").putArray("columns").add("LABEL");
                var modify=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());modify.set("draft",draft);
                review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,modify,true));assertEquals("complete",review.path("state").asText(),review.toString());apply.put("planId",review.path("id").asText());
                var updated=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("success",updated.path("result").path("status").asText(),updated.toString());jobs.remove("human",review.path("id").asText());
                try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT HIDDEN_COLUMN FROM USER_TAB_COLS WHERE TABLE_NAME='ITEMS' AND COLUMN_NAME='HIDDEN_TEXT'")){assertTrue(rs.next());assertEquals("YES",rs.getString(1));}
                loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));snapshot=(ObjectNode)loaded.path("result");draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("comment","Must not apply");modify.put("fingerprint",snapshot.path("fingerprint").asText());modify.set("draft",draft);
                review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,modify,true));assertEquals("complete",review.path("state").asText(),review.toString());
                try(var c=connections.open(id);var st=c.createStatement()){st.execute("ALTER TABLE ITEMS PCTFREE 17");}
                apply.put("planId",review.path("id").asText());var stale=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("failed",stale.path("result").path("status").asText(),stale.toString());assertTrue(stale.path("result").path("message").asText().contains("changed"));jobs.remove("human",review.path("id").asText());
                loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));snapshot=(ObjectNode)loaded.path("result");draft=TableDesignerTest.draft(snapshot);((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("columns")).addObject().put("id","new:partial").put("name","ACKNOWLEDGED").put("type","NUMBER").put("pk",0).put("nullable",true);
                ((com.fasterxml.jackson.databind.node.ArrayNode)draft.path("objects")).addObject().put("category","Constraints").put("action","add").put("kind","CHECK").put("name","FAIL_EXISTING_ROWS").put("expression","AMOUNT<0");modify.put("fingerprint",snapshot.path("fingerprint").asText());modify.set("draft",draft);
                review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,modify,true));assertEquals("complete",review.path("state").asText(),review.toString());apply.put("planId",review.path("id").asText());var partial=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",apply));assertEquals("partial",partial.path("result").path("outcome").asText(),partial.toString());assertEquals("committed",partial.path("result").path("steps").get(0).path("status").asText());jobs.remove("human",review.path("id").asText());
                try(var c=connections.open(id);var st=c.createStatement();var rs=st.executeQuery("SELECT ACKNOWLEDGED FROM ITEMS")){assertTrue(rs.next());assertNull(rs.getString(1));}
            }finally{connections.remove(id);try(var c=connections.open(admin);var st=c.createStatement()){st.execute("DROP USER "+user+" CASCADE");}}
        }
    }

    @Test void oracleScopedReadPermissionsCatalogsAndTransactionEnforcement()throws Exception{
        String user="CG_READ_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");st.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE SYNONYM, CREATE SEQUENCE TO "+user);}
            String id=profiles.put(null,draft(user,password).put("name","Read permission fixture")).path("id").asText();
            try{
                try(var c=connections.open(id);var st=c.createStatement()){
                    c.setAutoCommit(true);st.execute("CREATE TABLE ITEMS(ID NUMBER PRIMARY KEY,LABEL VARCHAR2(40),AMOUNT NUMBER(38))");st.execute("INSERT INTO ITEMS VALUES (1,'Unicode Ω漢字',12345678901234567890123456789012345678)");
                    st.execute("CREATE TABLE SECRET(ID NUMBER)");st.execute("CREATE VIEW V_ITEMS AS SELECT * FROM ITEMS");st.execute("CREATE SYNONYM ALIAS_ITEMS FOR ITEMS");st.execute("CREATE TABLE VIRTUAL_ITEMS(ID NUMBER,DERIVED NUMBER GENERATED ALWAYS AS (ID+1) VIRTUAL)");st.execute("CREATE SEQUENCE SEQ_ITEMS START WITH 100 NOCACHE");
                    // Oracle can briefly reject a read-only snapshot of freshly created tables with ORA-01466.
                    // Finish fixture readiness before testing the production approval path; never retry user SQL.
                    c.setAutoCommit(false);long readyBy=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                    for(;;){
                        c.rollback();st.execute("SET TRANSACTION READ ONLY");
                        try(var rs=st.executeQuery("SELECT COUNT(*) FROM ITEMS")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));break;}
                        catch(java.sql.SQLException e){if(e.getErrorCode()!=1466||System.nanoTime()>=readyBy)throw e;Thread.sleep(250);}
                        finally{c.rollback();}
                    }
                }
                var agents=new AgentAccess(directory);String agent=agents.trustedLocal();
                try(var contexts=new ProjectContexts(profiles,connections,agents);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,30),agents::alive);var q=new ApprovalQueue(contexts,profiles,agents,jobs)){
                    q.reusable.sessions.register("one",agent,System.currentTimeMillis()+120000);q.reusable.sessions.register("two",agent,System.currentTimeMillis()+120000);
                    var helper=new ReadPermissionVendorIntegrationTest();helper.activeJobs=jobs;
                    var pending=q.request(agent,"one",ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,"SELECT COUNT(*) FROM items"));assertTrue(pending.path("selectPermission").path("eligible").asBoolean(),pending.toString());
                    var grant=Profiles.JSON.createObjectNode().put("lifetime","mcp_session");grant.putArray("selectors").addObject().put("connectionId",id).put("level","object").put("database","FREEPDB1").put("schema",user).put("object","ITEMS");var options=Profiles.JSON.createObjectNode();options.set("readGrant",grant);
                    q.decide("human",pending.path("id").asText(),ReadPermissions.ACTION,true,options);var result=helper.finish(q,agent,pending);assertEquals("1",result.path("job").path("result").path("results").get(0).path("rows").get(0).get(0).asText());
                    for(String sql:java.util.List.of("SELECT amount FROM items WHERE id=?","WITH chosen AS (SELECT id FROM items) SELECT * FROM chosen UNION ALL SELECT id FROM items","SELECT LENGTH(label),SUM(amount) FROM items GROUP BY label OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY")){
                        var request=ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,sql);if(sql.contains("?"))request.putArray("parameters").add(1);helper.finish(q,agent,q.request(agent,"one",request));
                    }
                    for(String sql:java.util.List.of("SELECT * FROM secret","SELECT seq_items.NEXTVAL FROM items","SELECT seq_items.\"NEXTVAL\" FROM items","UPDATE items SET id=2")){
                        var unapproved=q.request(agent,"one",ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,sql));assertEquals("awaiting_approval",unapproved.path("state").asText(),unapproved.toString());q.cancel(agent,unapproved.path("id").asText());
                    }
                    var scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database","FREEPDB1").put("schema",user));
                    for(String kind:java.util.List.of("tables","columns","keys","indexes")){
                        var args=Profiles.JSON.createObjectNode().put("kind",kind);if(!kind.equals("tables"))args.put("object","ITEMS");var query=TrustedCatalogRead.prepare("dba_get_metadata",scope,args);
                        var request=ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,query.sql());request.set("parameters",query.parameters());request.set("catalogArguments",args);var read=helper.finish(q,agent,q.trustedCatalog(agent,"one",request));
                        assertFalse(read.path("job").path("result").path("results").get(0).path("rows").isEmpty(),read.toString());if(kind.equals("tables")){var rows=read.path("job").path("result").path("results").get(0).path("rows");assertEquals(1,rows.size());assertEquals("ITEMS",rows.get(0).get(2).asText());}
                    }
                    var ddlArgs=Profiles.JSON.createObjectNode().put("object","ITEMS").put("objectType","table");var ddl=TrustedCatalogRead.prepare("dba_get_object_ddl",scope,ddlArgs);var inspection=ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,ddl.sql());inspection.set("parameters",ddl.parameters());inspection.set("catalogArguments",ddlArgs);assertTrue(helper.finish(q,agent,q.trustedCatalog(agent,"one",inspection)).toString().contains("CREATE TABLE"));
                    var explain=q.planTool(agent,"one",ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,"SELECT ID FROM ITEMS WHERE ID=1"),true,true);
                    assertEquals("awaiting_approval",explain.path("state").asText(),explain.toString());q.decide("human",explain.path("id").asText(),"approve_once",true);
                    var plan=helper.finish(q,agent,explain).path("job").path("result");assertFalse(plan.path("executed").asBoolean());assertTrue(plan.path("analysisIncluded").asBoolean());assertFalse(plan.path("rows").isEmpty(),plan.toString());
                    // Even an internal caller bypassing the positive SQL grammar cannot write in the verified read transaction.
                    var denied=Profiles.JSON.createObjectNode().put("connectionId",id).put("sql","UPDATE "+user+".ITEMS SET ID=2").put("reusableRead",true);denied.set("reusableScope",scope);denied.putArray("parameters");
                    var rejected=HumanSqlTest.finish(jobs,"agent:"+agent,jobs.approved(agent,denied,()->{},()->{}));assertEquals("failed",rejected.path("state").asText(),rejected.toString());assertTrue(rejected.path("exception").toString().contains("1456"),rejected.toString());
                    try(var c=connections.open(id)){
                        var job=jobs.new Job("agent:"+agent,id);var request=Profiles.JSON.createObjectNode();request.set("reusableScope",scope);
                        for(String object:java.util.List.of("V_ITEMS","ALIAS_ITEMS","VIRTUAL_ITEMS")){request.putArray("readRelations").addObject().put("database","FREEPDB1").put("schema",user).put("object",object);assertThrows(IllegalArgumentException.class,()->OracleReads.verifyReferences(job,c,request),object);}
                        try(var st=c.createStatement()){
                            st.execute("CREATE FUNCTION LOWER(v VARCHAR2) RETURN VARCHAR2 IS BEGIN RAISE_APPLICATION_ERROR(-20001,'Custom function must never run'); END;");
                            request.remove("readRelations");request.put("readFunctions",true);
                            assertThrows(IllegalArgumentException.class,()->OracleReads.verifyReferences(job,c,request));
                            st.execute("DROP FUNCTION LOWER");
                            st.execute("CREATE SYNONYM LENGTH FOR V_ITEMS");
                            assertThrows(IllegalArgumentException.class,()->OracleReads.verifyReferences(job,c,request));
                            st.execute("DROP SYNONYM LENGTH");
                        }
                        try(var st=c.createStatement();var rs=st.executeQuery("SELECT LAST_NUMBER FROM USER_SEQUENCES WHERE SEQUENCE_NAME='SEQ_ITEMS'")){assertTrue(rs.next());assertEquals(100,rs.getInt(1));}
                    }
                    var second=q.request(agent,"two",ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,"SELECT id FROM items"));assertEquals("awaiting_approval",second.path("state").asText());q.cancel(agent,second.path("id").asText());
                    var retained=helper.finish(q,agent,q.request(agent,"one",ReadPermissionVendorIntegrationTest.request(id,"FREEPDB1",user,"SELECT id FROM items")),true);for(JsonNode policy:q.reusable.list(agent))q.reusable.change(agent,policy.path("id").asText(),null);assertThrows(SecurityException.class,()->jobs.status("agent:"+agent,retained.path("jobId").asText()));
                }
            }finally{connections.remove(id);try(var c=connections.open(admin);var st=c.createStatement()){st.execute("DROP USER "+user+" CASCADE");}}
        }
    }

    @Test void oracleVisualQueriesNativeFunctionsAndEstimatedPlans()throws Exception{
        String user="CG_BUILDER_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(),password="Cg_"+UUID.randomUUID().toString().replace("-","");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=jobs(connections)){
            String admin=profiles.put(null,systemDraft()).path("id").asText();
            try(var c=connections.open(admin);var st=c.createStatement()){
                st.execute("CREATE USER "+user+" IDENTIFIED BY "+OracleDialect.identifier(password)+" QUOTA 10M ON USERS");
                st.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE MATERIALIZED VIEW, CREATE PROCEDURE TO "+user);
            }
            String id=profiles.put(null,draft(user,password)).path("id").asText();
            try{
                try(var c=connections.open(id);var st=c.createStatement()){
                    c.setAutoCommit(true);st.execute("CREATE TABLE ITEMS(ID NUMBER PRIMARY KEY, AMOUNT NUMBER(38), STAMP TIMESTAMP(9))");
                    st.execute("INSERT INTO ITEMS SELECT LEVEL,12345678901234567890123456789012345678,TIMESTAMP '2026-09-24 12:34:00.123456789' FROM dual CONNECT BY LEVEL<=65");
                    st.execute("CREATE VIEW V_ITEMS AS SELECT ID,AMOUNT FROM ITEMS");
                    st.execute("CREATE MATERIALIZED VIEW M_ITEMS AS SELECT ID,AMOUNT FROM ITEMS");
                    st.execute("CREATE FUNCTION PLUS_ONE(n NUMBER) RETURN NUMBER IS BEGIN RETURN n+1; END;");
                    st.execute("CREATE PACKAGE CALCULATIONS AS FUNCTION CONVERT_VALUE(n NUMBER,extra NUMBER DEFAULT 10) RETURN NUMBER; FUNCTION CONVERT_VALUE(n VARCHAR2) RETURN VARCHAR2; FUNCTION BAD(n OUT NUMBER) RETURN NUMBER; END;");
                    st.execute("CREATE PACKAGE BODY CALCULATIONS AS FUNCTION CONVERT_VALUE(n NUMBER,extra NUMBER DEFAULT 10) RETURN NUMBER IS BEGIN RETURN n+extra; END; FUNCTION CONVERT_VALUE(n VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN n||'!'; END; FUNCTION BAD(n OUT NUMBER) RETURN NUMBER IS BEGIN n:=1; RETURN 1; END; END;");
                    st.execute("CREATE FUNCTION NEVER_EXECUTE RETURN NUMBER IS BEGIN RAISE_APPLICATION_ERROR(-20001,'Explain executed its query'); END;");
                }
                for(String kind:java.util.List.of("views","materialized_views")){
                    var selected=MetadataActionsTest.selection(jobs,id,kind,user,kind.equals("views")?"V_ITEMS":"M_ITEMS");
                    var source=complete(jobs,jobs.queryBuilder("browser",id,selected,true));assertEquals("FREEPDB1",source.path("database").asText());assertTrue(source.path("definition").asText().contains("ITEMS"),source.toString());assertEquals(2,source.path("columns").size());
                }
                var imported=complete(jobs,jobs.queryBuilder("browser",id,Profiles.JSON.createObjectNode().put("sql","SELECT a.ID, b.AMOUNT FROM "+user+".ITEMS a JOIN "+user+".V_ITEMS b ON a.ID=b.ID"),false));
                assertTrue(imported.path("editable").asBoolean(),imported.toPrettyString());assertEquals("FREEPDB1",imported.path("database").asText());
                var request=Profiles.JSON.createObjectNode().put("engine","oracle").put("quote","\"");request.set("model",imported.path("visualModel"));var compiled=VisualQuery.compile(request);assertTrue(compiled.path("valid").asBoolean(),compiled.toString());
                try(var c=connections.open(id);var st=c.createStatement()){
                    c.setAutoCommit(false);try(var rs=st.executeQuery(compiled.path("sql").asText())){assertTrue(rs.next());}
                    var job=jobs.new Job("browser",id);var functions=OracleVisualQuery.functions(job,c,user,"CONVERT_VALUE","",0).path("functions");assertEquals(2,functions.size(),functions.toString());
                    for(var f:functions){
                        var full=OracleVisualQuery.functions(job,c,user,"",f.path("key").asText(),0).path("functions").get(0);assertTrue(full.path("available").asBoolean(),full.toString());assertEquals("CALCULATIONS",full.path("package").asText());
                        var model=VisualQueryTest.model(1);((ObjectNode)model.path("sources").get(0)).put("reference",user+".ITEMS");
                        var expression=VisualQuery.expression("function").put("name",full.path("name").asText()).put("schema",user).put("package","CALCULATIONS").put("catalogFunction",true);expression.set("arguments",full.path("arguments"));
                        boolean numeric=full.path("returnType").asText().equals("NUMBER");expression.putArray("args").add(VisualQuery.expression("literal").put("type",numeric?"number":"text").put("value",numeric?"12345678901234567890123456789012345670":"Unicode Ω漢字"));
                        ((ObjectNode)model.path("detail").path("outputs").get(0)).set("expression",expression);request.set("model",model);var functionQuery=VisualQuery.compile(request);assertTrue(functionQuery.path("valid").asBoolean(),functionQuery.toString());
                        try(var rs=st.executeQuery(functionQuery.path("sql").asText())){assertTrue(rs.next());assertEquals(numeric?"12345678901234567890123456789012345680":"Unicode Ω漢字!",rs.getString(1));}
                    }
                    var bad=OracleVisualQuery.functions(job,c,user,"BAD","",0).path("functions").get(0);assertFalse(OracleVisualQuery.functions(job,c,user,"",bad.path("key").asText(),0).path("functions").get(0).path("available").asBoolean());
                    var standalone=OracleVisualQuery.functions(job,c,user,"PLUS_ONE","",0).path("functions").get(0);assertTrue(OracleVisualQuery.functions(job,c,user,"",standalone.path("key").asText(),0).path("functions").get(0).path("available").asBoolean());
                    // Estimated planning must not invoke the selected function or retain PLAN_TABLE rows.
                    var plan=ExplainPlans.collect(job,c,"SELECT "+user+".NEVER_EXECUTE() FROM "+user+".ITEMS WHERE ID>?",Profiles.JSON.createArrayNode().add(1));assertFalse(plan.path("executed").asBoolean());assertFalse(plan.path("rows").isEmpty(),plan.toString());
                    try(var rs=st.executeQuery("SELECT COUNT(*) FROM PLAN_TABLE WHERE STATEMENT_ID='cg"+job.id.replace("-","").substring(0,26)+"'")){assertTrue(rs.next());assertEquals(0,rs.getInt(1));}
                    st.execute("ALTER SESSION SET NLS_DATE_FORMAT='DD-MON-RR'");st.execute("ALTER SESSION SET NLS_NUMERIC_CHARACTERS=',.'");
                    var values=Profiles.JSON.createArrayNode();values.addObject().put("mode","in").put("type","NUMBER").put("value","12345678901234567890123456789012345678");values.addObject().put("mode","in").put("type","DATE").put("value","2026-09-24T12:34:00");values.addObject().put("mode","in").put("type","TIMESTAMP").put("value","2026-09-24T12:34:00.123456789");
                    try(var statement=c.prepareStatement("SELECT ?,CAST(? AS DATE),CAST(? AS TIMESTAMP(9)) FROM dual")){GridPaging.bind(statement,values);try(var rs=statement.executeQuery()){assertTrue(rs.next());assertEquals(values.get(0).path("value").asText(),rs.getBigDecimal(1).toPlainString());assertEquals("2026-09-24T12:34",rs.getTimestamp(2).toLocalDateTime().toString());assertEquals("2026-09-24T12:34:00.123456789",rs.getTimestamp(3).toLocalDateTime().toString());}}
                }
                try(var grids=new GridResults(jobs,connections,()->new DbaConfig(directory,64L<<20,2,30,100,30),x->true)){
                    jobs.grids=grids;var values=Profiles.JSON.createArrayNode();values.addObject().put("mode","in").put("type","NUMBER").put("value","12345678901234567890123456789012345678");
                    var run=HumanSqlTest.finish(jobs,"human",jobs.tableQuery("human",id,"SELECT ID,AMOUNT FROM "+user+".ITEMS WHERE AMOUNT=?",values,"FREEPDB1",null));assertEquals("complete",run.path("state").asText(),run.toString());
                    var grid=run.path("result").path("results").get(0).path("grid");assertTrue(grid.path("capabilities").path("page").asBoolean(),grid.toString());
                    var next=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"page",Profiles.JSON.createObjectNode().put("revision",1).put("direction","next").put("limit",30)));assertEquals("complete",next.path("state").asText(),next.toString());assertEquals(31,next.path("result").path("rows").get(0).get(0).asInt());
                    var plan=HumanSqlTest.finish(jobs,"human",jobs.browserExplain("human",id,"SELECT ID FROM "+user+".ITEMS WHERE AMOUNT=?",values,"FREEPDB1"));assertEquals("complete",plan.path("state").asText(),plan.toString());
                }
            }finally{connections.remove(id);try(var c=connections.open(admin);var st=c.createStatement()){st.execute("DROP USER "+user+" CASCADE");}}
        }
    }

    @Test void oracleGridPagesFiltersAndEditsVerifiedScalarRows()throws Exception{
        DbaConfig config=new DbaConfig(directory,128L<<20,2,30,100,30);
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;String id=profiles.put(null,systemDraft()).path("id").asText(),table="CGGRID"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase();
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE TABLE "+table+" (id NUMBER PRIMARY KEY,amount NUMBER(38),label VARCHAR2(40),created DATE,moment TIMESTAMP(9))");
                try{
                    st.execute("INSERT INTO "+table+" SELECT LEVEL,12345678901234567890123456789012345678,'row-'||LEVEL,TO_DATE('2026-09-24 12:34:56','YYYY-MM-DD HH24:MI:SS'),TIMESTAMP '2026-09-24 12:34:56.123456789' FROM dual CONNECT BY LEVEL<=62");
                    var query=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT id AS row_key,amount,label,created,moment FROM "+table,Profiles.JSON.createArrayNode(),false));assertEquals("complete",query.path("state").asText(),query.toPrettyString());var result=query.path("result").path("results").get(0);var grid=result.path("grid");
                    assertTrue(grid.path("capabilities").path("page").asBoolean(),grid.toPrettyString());assertTrue(grid.path("capabilities").path("edit").asBoolean(),grid.toPrettyString());assertEquals("FREEPDB1",grid.path("database").asText());assertEquals(30,result.path("rows").size());assertEquals("12345678901234567890123456789012345678",result.path("rows").get(0).get(1).asText());
                    var page=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"page",Profiles.JSON.createObjectNode().put("revision",1).put("direction","next").put("limit",30)));assertEquals("complete",page.path("state").asText(),page.toPrettyString());assertEquals("31",page.path("result").path("rows").get(0).get(0).asText());
                    var filtered=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"values",Profiles.JSON.createObjectNode().put("revision",2).put("columnId","c3").put("search","row-3").put("showRowCount",true).put("showDistinctValuesCount",true)));assertEquals("complete",filtered.path("state").asText(),filtered.toPrettyString());assertEquals(11,filtered.path("result").path("values").size());
                    var request=Profiles.JSON.createObjectNode().put("revision",2);var values=request.putArray("changes").addObject().put("rowId",page.path("result").path("grid").path("rowIds").get(0).asText()).put("operation","update").putObject("values");values.putObject("c3").put("kind","value").put("value","updated Ω");values.putObject("c4").put("kind","value").put("value","2026-10-01T23:45:56");
                    var plan=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"prepare",request));assertEquals("complete",plan.path("state").asText(),plan.toPrettyString());
                    var saved=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",2).put("planId",plan.path("result").path("planId").asText())));assertEquals("complete",saved.path("state").asText(),saved.toPrettyString());
                    try(var rows=st.executeQuery("SELECT label,TO_CHAR(created,'YYYY-MM-DD HH24:MI:SS') FROM "+table+" WHERE id=31")){assertTrue(rows.next());assertEquals("updated Ω",rows.getString(1));assertEquals("2026-10-01 23:45:56",rows.getString(2));}
                    var exportedQuery=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM "+table+" WHERE id=31",Profiles.JSON.createArrayNode(),false));var exportGrid=exportedQuery.path("result").path("results").get(0).path("grid");
                    var exportRequest=Profiles.JSON.createObjectNode().put("revision",1).put("format","sql").put("scope","query");exportRequest.putArray("columns").add("c1").add("c2").add("c3").add("c4").add("c5");
                    var exported=HumanSqlTest.finish(jobs,"human",grids.operation("human",exportGrid.path("id").asText(),"export",exportRequest));assertEquals("complete",exported.path("state").asText(),exported.toPrettyString());
                    String exportId=exported.path("result").path("exportId").asText(),script=java.nio.file.Files.readString(directory.resolve("grid-exports/cgraph-grid-"+exportId+".sql"));st.execute("DELETE FROM "+table+" WHERE id=31");for(var unit:SqlScript.extract(script,"oracle",1<<20))st.execute(unit.sql());grids.exports.remove("human",exportId);
                    try(var rows=st.executeQuery("SELECT amount,label,TO_CHAR(created,'YYYY-MM-DD HH24:MI:SS'),TO_CHAR(moment,'YYYY-MM-DD HH24:MI:SS.FF9') FROM "+table+" WHERE id=31")){assertTrue(rows.next());assertEquals("12345678901234567890123456789012345678",rows.getString(1));assertEquals("updated Ω",rows.getString(2));assertEquals("2026-10-01 23:45:56",rows.getString(3));assertEquals("2026-09-24 12:34:56.123456789",rows.getString(4));}
                    var atomicQuery=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",id,"SELECT * FROM "+table+" WHERE id<=2",Profiles.JSON.createArrayNode(),false));var atomic=atomicQuery.path("result").path("results").get(0).path("grid");
                    var atomicRequest=Profiles.JSON.createObjectNode().put("revision",1);var changes=atomicRequest.putArray("changes");for(JsonNode row:atomic.path("rowIds"))changes.addObject().put("rowId",row.asText()).put("operation","update").putObject("values").putObject("c1").put("kind","value").put("value","99");
                    var atomicPlan=HumanSqlTest.finish(jobs,"human",grids.operation("human",atomic.path("id").asText(),"prepare",atomicRequest));assertEquals("complete",atomicPlan.path("state").asText(),atomicPlan.toPrettyString());
                    var failed=HumanSqlTest.finish(jobs,"human",grids.operation("human",atomic.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",atomicPlan.path("result").path("planId").asText())));assertEquals("failed",failed.path("state").asText(),failed.toPrettyString());
                    try(var rows=st.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE id IN (1,2)")){assertTrue(rows.next());assertEquals(2,rows.getInt(1),"Oracle must roll back the complete failed edit batch");}
                    var originalTarget=grids.require("human",atomic.path("id").asText()).oracleTarget;grids.require("human",atomic.path("id").asText()).oracleTarget="different PDB identity";
                    var moved=HumanSqlTest.finish(jobs,"human",grids.operation("human",atomic.path("id").asText(),"values",Profiles.JSON.createObjectNode().put("revision",1).put("columnId","c3")));assertEquals("failed",moved.path("state").asText());assertTrue(moved.path("error").asText().contains("identity changed"),moved.toPrettyString());grids.require("human",atomic.path("id").asText()).oracleTarget=originalTarget;
                    atomicRequest=Profiles.JSON.createObjectNode().put("revision",1);atomicRequest.putArray("changes").addObject().put("rowId",atomic.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject("c3").put("kind","value").put("value","draft");
                    atomicPlan=HumanSqlTest.finish(jobs,"human",grids.operation("human",atomic.path("id").asText(),"prepare",atomicRequest));st.execute("UPDATE "+table+" SET label='concurrent' WHERE id=1");
                    failed=HumanSqlTest.finish(jobs,"human",grids.operation("human",atomic.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",atomicPlan.path("result").path("planId").asText())));assertEquals("failed",failed.path("state").asText());assertTrue(failed.path("error").asText().contains("changed by another transaction"),failed.toPrettyString());
                }finally{st.execute("DROP TABLE "+table+" PURGE");}
            }
        }
    }

    private static ObjectNode compareAwait(QueryJobs jobs,ObjectNode submitted)throws Exception{
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(120);ObjectNode status;
        do{status=jobs.status("human",submitted.path("id").asText());if(status.path("finished").asLong()!=0)return status;Thread.sleep(100);}while(System.nanoTime()<deadline);
        throw new AssertionError("Oracle comparison did not finish: "+status);
    }
    private static JsonNode compareFinished(QueryJobs jobs,ObjectNode submitted)throws Exception{
        var status=compareAwait(jobs,submitted);try{assertEquals("complete",status.path("state").asText(),status.toPrettyString());return status.path("result").deepCopy();}finally{jobs.remove("human",submitted.path("id").asText());}
    }

}
