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
