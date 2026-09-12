package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only the script-created disposable PostgreSQL instance may be used by this fixture. */
@EnabledIfEnvironmentVariable(named="DBA_TEST_DISPOSABLE",matches="code-graph-dba-test-[a-f0-9]+")
class PostgresIntegrationTest {
    @Test void propertiesCreatesAllSupportedCategoriesAtomically()throws Exception{
        String schema="newdesigner_"+UUID.randomUUID().toString().replace("-","");String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD");
        try(var c=DriverManager.getConnection(url,"postgres",password);var st=c.createStatement();var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,15),o->true)){
            st.execute("CREATE SCHEMA "+schema);try{
                st.execute("CREATE TABLE "+schema+".parent(id integer primary key)");st.execute("INSERT INTO "+schema+".parent VALUES(1)");st.execute("CREATE FUNCTION "+schema+".on_insert() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
                String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","create").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
                var request=TableCreationTest.input(schema);var loaded=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,request,false));var baseline=(com.fasterxml.jackson.databind.node.ObjectNode)loaded.path("result");var draft=TableDesignerTest.draft(baseline);request.put("fingerprint",baseline.path("fingerprint").asText());request.set("draft",draft);
                ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("fields")).put("name","created").put("owner","postgres").put("comment","Created by Properties");
                var columns=(com.fasterxml.jackson.databind.node.ArrayNode)draft.path("columns");columns.addObject().put("id","new:id").put("name","id").put("type","integer").put("pk",1).put("nullable",false).put("identity","d");columns.addObject().put("id","new:parent").put("name","parent_id").put("type","integer").put("pk",0).put("nullable",true).put("default","1").put("comment","FK default");
                var objects=(com.fasterxml.jackson.databind.node.ArrayNode)draft.path("objects");objects.addObject().put("category","Constraints").put("action","add").put("kind","CHECK").put("name","positive").put("expression","id > 0");
                var fk=objects.addObject().put("category","Foreign Keys").put("action","add").put("kind","FOREIGN KEY").put("name","parent_fk").put("schema",schema).put("table","parent");fk.putArray("columns").add("parent_id");fk.putArray("references").add("id");
                objects.addObject().put("category","Indexes").put("action","add").put("name","parent_idx").putArray("columns").add("parent_id");objects.addObject().put("category","Policies").put("action","add").put("name","visible").put("expression","id > 0");objects.addObject().put("category","Rules").put("action","add").put("name","keep").put("event","DELETE");
                var trigger=objects.addObject().put("category","Triggers").put("action","add").put("name","inserted").put("timing","BEFORE").put("event","INSERT").put("level","ROW").put("functionSchema",schema).put("functionName","on_insert");
                objects.addObject().put("category","Permissions").put("action","grant").put("role","postgres").put("privilege","SELECT");objects.addObject().put("category","Statistics").put("action","collect");
                var review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());var commands=review.path("result").path("commands");assertTrue(commands.get(commands.size()-1).path("sql").asText().contains("OWNER TO"));
                var result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("success",result.path("result").path("status").asText(),result.toString());jobs.remove("human",review.path("id").asText());
                st.execute("INSERT INTO "+schema+".created DEFAULT VALUES");try(var rs=st.executeQuery("SELECT parent_id FROM "+schema+".created")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
                ((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("fields")).put("name","rollback_me");trigger.put("functionName","missing_function");((com.fasterxml.jackson.databind.node.ObjectNode)objects.get(2)).put("name","other_idx");
                review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("rolled_back",result.path("result").path("outcome").asText(),result.toString());jobs.remove("human",review.path("id").asText());
                try(var rs=st.executeQuery("SELECT to_regclass('"+schema+".rollback_me')")){assertTrue(rs.next());assertNull(rs.getString(1));}
            }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    @Test void nativeObjectCreation()throws Exception{
        String schema="creation_"+UUID.randomUUID().toString().replace("-","");
        try(var c=DriverManager.getConnection(System.getenv("DBA_TEST_URL"),"postgres",System.getenv("DBA_TEST_PASSWORD"));var st=c.createStatement()){
            try{
                for(String kind:List.of("schemas","tables","views","materialized_views","sequences","indexes")){
                    var d=Profiles.JSON.createObjectNode().put("kind",kind).put("schema",schema).put("name",kind.equals("schemas")?schema:kind+"_new").put("query","SELECT 1 AS id").put("table","tables_new");
                    d.putArray("columns").addObject().put("name","id").put("type","integer");d.putArray("indexColumns").add("id");
                    st.execute(ObjectCreation.prepare(c,d,false).path("sql").asText());
                }
                try(var rs=st.executeQuery("SELECT id FROM "+schema+".materialized_views_new")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
            }finally{st.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
        }
    }
    @Test void builderViewsAndMaterializedViews()throws Exception{
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),schema="builder_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(url,"postgres",password);Statement st=c.createStatement();var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,100,100,15),o->true)){
            st.execute("CREATE SCHEMA "+schema);try{
                st.execute("CREATE TABLE "+schema+".source(id int PRIMARY KEY,name text)");st.execute("INSERT INTO "+schema+".source VALUES(1,'unchanged')");st.execute("CREATE VIEW "+schema+".v AS SELECT id,name FROM "+schema+".source");st.execute("CREATE MATERIALIZED VIEW "+schema+".mv AS SELECT id,name FROM "+schema+".source");
                String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","builder").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
                for(String kind:List.of("views","materialized_views")){
                    var selection=MetadataActionsTest.selection(jobs,id,kind,schema,kind.equals("views")?"v":"mv");
                    var loaded=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,selection,true));assertEquals("complete",loaded.path("state").asText(),loaded.toString());var source=loaded.path("result");assertEquals(2,source.path("columns").size());assertTrue(source.path("definition").asText().contains("source"));
                    var imported=HumanSqlTest.finish(jobs,"human",jobs.queryBuilder("human",id,Profiles.JSON.createObjectNode().put("sql",source.path("definition").asText()),false));assertEquals("complete",imported.path("state").asText(),imported.toString());assertTrue(imported.path("result").path("editable").asBoolean(),imported.toString());
                    var rows=HumanSqlTest.finish(jobs,"human",jobs.tableQuery("human",id,source.path("sql").asText(),Profiles.JSON.createArrayNode(),"postgres"));assertEquals("complete",rows.path("state").asText(),rows.toString());assertEquals(1,rows.path("result").path("rowCount").asInt());
                }
                try(var rs=st.executeQuery("SELECT name FROM "+schema+".source")){assertTrue(rs.next());assertEquals("unchanged",rs.getString(1));}
            }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    @TempDir Path root;
    @Test void designerAtomicChangesAndStaleReview()throws Exception{
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),schema="designer_"+UUID.randomUUID().toString().replace("-","");
        try(Connection anchor=DriverManager.getConnection(url,"postgres",password);Statement st=anchor.createStatement();var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,256L<<20,2,100,100,15),o->true)){
            st.execute("CREATE SCHEMA "+schema);try{
                st.execute("CREATE TABLE "+schema+".items(id int PRIMARY KEY,title text)");st.execute("INSERT INTO "+schema+".items VALUES(1,'kept')");
                String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","designer").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
                var selection=MetadataActionsTest.selection(jobs,id,"tables",schema,"items");
                var state=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));assertEquals("complete",state.path("state").asText(),state.toString());var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)state.path("result");assertTrue(snapshot.path("editable").asBoolean(),snapshot.toString());
                var draft=TableDesignerTest.draft(snapshot);((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("columns").get(1)).put("name","description").put("comment","Saved comment");
                var request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);
                var review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());
                var result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("success",result.path("result").path("status").asText(),result.toString());jobs.remove("human",review.path("id").asText());
                try(var rs=st.executeQuery("SELECT description FROM "+schema+".items")){assertTrue(rs.next());assertEquals("kept",rs.getString(1));}
                st.execute("CREATE FUNCTION "+schema+".before_item() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");
                state=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)state.path("result");draft=TableDesignerTest.draft(snapshot);var objects=(com.fasterxml.jackson.databind.node.ArrayNode)draft.path("objects");
                objects.addObject().put("category","Constraints").put("action","add").put("kind","CHECK").put("name","description_present").put("expression","length(description) > 0");
                objects.addObject().put("category","Indexes").put("action","add").put("name","description_idx").putArray("columns").add("description");
                objects.addObject().put("category","Policies").put("action","add").put("name","positive_ids").put("expression","id > 0");
                objects.addObject().put("category","Rules").put("action","add").put("name","keep_rows").put("event","DELETE");
                objects.addObject().put("category","Triggers").put("action","add").put("name","before_item").put("event","INSERT").put("timing","BEFORE").put("level","ROW").put("functionSchema",schema).put("functionName","before_item");
                objects.addObject().put("category","Permissions").put("action","grant").put("role","postgres").put("privilege","SELECT");objects.addObject().put("category","Statistics").put("action","collect");
                request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));assertEquals("complete",review.path("state").asText(),review.toString());
                result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("success",result.path("result").path("status").asText(),result.toString());jobs.remove("human",review.path("id").asText());
                state=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));assertEquals(1,state.path("result").path("triggers").size());assertEquals(1,state.path("result").path("policies").size());assertEquals(1,state.path("result").path("rules").size());
                state=HumanSqlTest.finish(jobs,"human",jobs.tableProperties("human",id,selection,false));snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)state.path("result");draft=TableDesignerTest.draft(snapshot);((com.fasterxml.jackson.databind.node.ObjectNode)draft.path("columns").get(1)).put("comment","Must rollback").put("type","integer");
                request=selection.deepCopy().put("fingerprint",snapshot.path("fingerprint").asText());request.set("draft",draft);review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));
                result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertEquals("rolled_back",result.path("result").path("outcome").asText(),result.toString());jobs.remove("human",review.path("id").asText());
                review=TableDesignerTest.waitRetained(jobs,"human",jobs.tableProperties("human",id,request,true));st.execute("ALTER TABLE "+schema+".items ADD COLUMN external_change int");
                result=HumanSqlTest.finish(jobs,"human",jobs.applyTableProperties("human",Profiles.JSON.createObjectNode().put("planId",review.path("id").asText()).put("confirmed",true)));assertTrue(result.path("result").path("message").asText().contains("changed after review"),result.toString());jobs.remove("human",review.path("id").asText());
            }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    @Test void quotedTableDateFilterExecutesAgainstPostgres()throws Exception{
        try(Connection c=DriverManager.getConnection(System.getenv("DBA_TEST_URL"),"postgres",System.getenv("DBA_TEST_PASSWORD"));Statement setup=c.createStatement()){
            setup.execute("CREATE TABLE public.myusers(signup_date DATE)");
            try{
                setup.execute("INSERT INTO public.myusers VALUES(DATE '2026-09-04'),(DATE '2026-09-05')");
                for(String from:List.of("\"public\".\"myusers\"", "\"public\".\"myusers\" AS \"user_alias\"")){
                    var request=Profiles.JSON.createObjectNode().put("sql","SELECT * FROM "+from).put("action","filter").put("columnIndex",0).put("columnLabel","signup_date").put("operator","=").put("jdbcType",Types.DATE).put("value","2026-09-04");
                    request.putArray("parameters");request.putArray("columns").addObject().put("name","signup_date").put("label","signup_date").put("table","myusers").put("schema","public");
                    var edited=GridSql.prepare(request);
                    try(var query=c.prepareStatement(edited.path("sql").asText())){
                        query.setString(1,edited.path("parameters").get(0).asText());
                        try(var rows=query.executeQuery()){assertTrue(rows.next());assertEquals("2026-09-04",rows.getString(1));assertFalse(rows.next());}
                    }
                    try(var rows=setup.executeQuery(edited.path("displaySql").asText())){assertTrue(rows.next());assertEquals("2026-09-04",rows.getString(1));assertFalse(rows.next());}
                }
            }finally{setup.execute("DROP TABLE public.myusers");}
        }
    }
    @Test void tableDetailsAndExplicitOtherDatabaseQueries()throws Exception{
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),database="table_tab_"+UUID.randomUUID().toString().replace("-","");
        try(Connection fixture=DriverManager.getConnection(url,"postgres",password);Statement st=fixture.createStatement()){
            st.execute("CREATE DATABASE "+database);
            try(Connection other=DriverManager.getConnection(Connections.postgresDatabaseUrl(url,database),"postgres",password);Statement setup=other.createStatement();var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,10,5,10),o->true)){
                setup.execute("CREATE TABLE public.items(id int PRIMARY KEY, title text CHECK(length(title)>0)) PARTITION BY RANGE(id)");setup.execute("CREATE TABLE public.items_part PARTITION OF public.items FOR VALUES FROM (0) TO (100)");setup.execute("INSERT INTO public.items VALUES(42,'target database')");setup.execute("CREATE TABLE public.child(id int REFERENCES public.items(id))");setup.execute("CREATE VIEW public.dependent AS SELECT * FROM public.items");setup.execute("CREATE POLICY visible ON public.items USING(true)");setup.execute("CREATE RULE nothing AS ON DELETE TO public.items DO INSTEAD NOTHING");setup.execute("CREATE FUNCTION public.before_item() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'");setup.execute("CREATE TRIGGER before_item BEFORE INSERT ON public.items FOR EACH ROW EXECUTE FUNCTION public.before_item()");
                String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","table targets").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
                var parent=Profiles.JSON.createObjectNode().put("kind","tables").put("database",database).put("schema","public");var selection=MetadataActionsTest.selection(jobs,id,parent,"items");
                var prepared=HumanSqlTest.finish(jobs,"human",jobs.tablePreparation("human",id,selection));assertEquals("complete",prepared.path("state").asText(),prepared.toString());String sql=prepared.path("result").path("sql").asText();
                var result=HumanSqlTest.finish(jobs,"human",jobs.tableQuery("human",id,sql,Profiles.JSON.createArrayNode(),database));assertEquals("complete",result.path("state").asText(),result.toString());assertEquals("target database",result.path("result").path("results").get(0).path("rows").get(0).get(1).asText());
                try(var original=connections.open(id)){assertEquals("postgres",original.getCatalog(),"Table reads must not retarget the saved connection");}
                var relation=Profiles.JSON.createObjectNode().put("kind","relation").put("relationType","table").put("name","items").put("schema","public").put("database",database);
                var groups=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,relation));assertEquals(10,groups.path("result").path("nodes").size(),groups.toString());
                for(var group:groups.path("result").path("nodes")){var leaves=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,group));assertEquals("complete",leaves.path("state").asText(),group+":"+leaves);if(!group.path("kind").asText().equals("table_foreign_keys"))assertFalse(leaves.path("result").path("nodes").isEmpty(),group.toString());}
                var fk=relation.deepCopy().put("kind","table_foreign_keys").put("table","child");assertFalse(HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,fk)).path("result").path("nodes").isEmpty());
                var slow=jobs.tableQuery("human",id,"SELECT pg_sleep(10)",Profiles.JSON.createArrayNode(),database);Thread.sleep(300);jobs.cancel(jobs.require("human",slow.path("id").asText()));assertEquals("cancelled",HumanSqlTest.finish(jobs,"human",slow).path("state").asText());
            }finally{st.execute("DROP DATABASE "+database+" WITH (FORCE)");}
        }
    }
    @Test void catalogObjectActionsResolveIdentitiesAndConfirmDestruction()throws Exception{
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),schema="actions_"+UUID.randomUUID().toString().replace("-","");
        try(Connection fixture=DriverManager.getConnection(url,"postgres",password);Statement st=fixture.createStatement()){
            st.execute("CREATE SCHEMA "+schema);
            try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,1000,100,15),o->true)){
                String id=profiles.put(null,Profiles.JSON.createObjectNode().put("name","object actions").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
                st.execute("CREATE TABLE "+schema+".items(id int, title text)");st.execute("CREATE VIEW "+schema+".dependent AS SELECT * FROM "+schema+".items");
                st.execute("CREATE FUNCTION "+schema+".calc(integer) RETURNS integer LANGUAGE SQL AS 'SELECT $1'");st.execute("CREATE FUNCTION "+schema+".calc(text) RETURNS text LANGUAGE SQL AS 'SELECT $1'");
                var item=MetadataActionsTest.selection(jobs,id,"tables",schema,"items");
                assertEquals("failed",MetadataActionsTest.action(jobs,id,item,"delete","").path("state").asText());
                var function=MetadataActionsTest.selection(jobs,id,"functions",schema,"calc(integer)");assertEquals("calc",MetadataActionsTest.preview(jobs,id,function).path("name").asText());
                assertEquals("complete",MetadataActionsTest.action(jobs,id,function,"rename","changed").path("state").asText());
                assertEquals("complete",MetadataActionsTest.action(jobs,id,MetadataActionsTest.selection(jobs,id,"functions",schema,"changed(integer)"),"delete","").path("state").asText());
                assertEquals("calc",MetadataActionsTest.preview(jobs,id,MetadataActionsTest.selection(jobs,id,"functions",schema,"calc(text)")).path("name").asText());
                assertEquals("complete",MetadataActionsTest.action(jobs,id,MetadataActionsTest.selection(jobs,id,"views",schema,"dependent"),"delete","").path("state").asText());
                var page=MetadataTreeTest.browse(jobs,id,"tables",schema);var row=page.path("nodes").get(0);var relation=((com.fasterxml.jackson.databind.node.ObjectNode)row).deepCopy();relation.put("kind","relation");
                var column=MetadataActionsTest.selection(jobs,id,relation,"title · text");assertEquals("title",MetadataActionsTest.preview(jobs,id,column).path("name").asText());
                assertEquals("complete",MetadataActionsTest.action(jobs,id,column,"rename","display name").path("state").asText());
                var oldPreview=MetadataActionsTest.preview(jobs,id,item);st.execute("DROP TABLE "+schema+".items");st.execute("CREATE TABLE "+schema+".items(id int)");
                var stale=item.deepCopy().put("action","delete").put("confirmed",true).put("fingerprint",oldPreview.path("fingerprint").asText());assertEquals("failed",HumanSqlTest.finish(jobs,"human",jobs.metadataObject("human",id,stale,true)).path("state").asText());
                assertEquals("complete",MetadataActionsTest.action(jobs,id,MetadataActionsTest.selection(jobs,id,"tables",schema,"items"),"delete","").path("state").asText());
                assertThrows(SecurityException.class,()->jobs.metadataObject("agent:blocked",id,item,true));
            }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    @Test void metadataTreeAllGroupsAndCrossDatabaseIsolation()throws Exception{
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),database="catalog_"+UUID.randomUUID().toString().replace("-","");
        try(Connection fixture=DriverManager.getConnection(url,"postgres",password);Statement st=fixture.createStatement()){
            st.execute("CREATE DATABASE "+database);
            try(Connection other=DriverManager.getConnection(Connections.postgresDatabaseUrl(url,database),"postgres",password);Statement setup=other.createStatement()){
                setup.execute("CREATE TABLE public.only_in_second(id int, value text)");setup.execute("CREATE VIEW public.test_view AS SELECT * FROM public.only_in_second");setup.execute("CREATE MATERIALIZED VIEW public.test_materialized AS SELECT * FROM public.only_in_second");setup.execute("CREATE INDEX test_idx ON public.only_in_second(id)");setup.execute("CREATE SEQUENCE public.test_seq");
                var input=Profiles.JSON.createObjectNode().put("name","catalog fixture").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
                try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,1000,100,15),o->true)){
                    String id=profiles.put(null,input).path("id").asText();
                    assertTrue(MetadataTreeTest.browse(jobs,id,"databases","").toString().contains(database));
                    for(String kind:List.of("database","schemas","schema","tables","foreign_tables","views","materialized_views","indexes","functions","procedures","sequences","types","aggregates","event_triggers","extensions","storage","system","roles","tablespaces","foreign_servers")){
                        var request=Profiles.JSON.createObjectNode().put("kind",kind).put("database",database).put("schema","public");
                        var status=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,request));assertEquals("complete",status.path("state").asText(),kind+": "+status);
                        if(kind.equals("tables")){assertEquals("only_in_second",status.path("result").path("nodes").get(0).path("name").asText());var node=(com.fasterxml.jackson.databind.node.ObjectNode)status.path("result").path("nodes").get(0);var columns=HumanSqlTest.finish(jobs,"human",jobs.metadataTree("human",id,node));assertEquals(2,columns.path("result").path("nodes").size(),columns.toString());}
                    }
                    assertFalse(MetadataTreeTest.browse(jobs,id,"tables","public").toString().contains("only_in_second"));
                    assertEquals(url,profiles.get(id).path("url").asText());assertEquals(1,connections.count());
                }
            }finally{st.execute("DROP DATABASE "+database);}
        }
    }
    @Test void headlessAgentsReadExplainDdlAndEnforceLimits()throws Exception {
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD"),schema="dba_agent_"+UUID.randomUUID().toString().replace("-","");
        try(Connection fixture=DriverManager.getConnection(url,"postgres",password);Statement setup=fixture.createStatement()){
            setup.execute("CREATE SCHEMA "+schema);
            try{
                setup.execute("CREATE TABLE "+schema+".items (id int primary key, value text)");
                setup.execute("INSERT INTO "+schema+".items SELECT n, repeat('x',8192) FROM generate_series(1,1000) n");
                String connection,token;var vault=new DbaTest.MemoryVault();
                try(var profiles=new Profiles(root,vault)){
                    var input=Profiles.JSON.createObjectNode().put("name","disposable").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
                    connection=profiles.put(null,input).path("id").asText();var grant=AgentAccessTest.grant(connection);((com.fasterxml.jackson.databind.node.ObjectNode)grant.path("grants").get(0).path("objects").get(0)).put("schema",schema);
                    token=new AgentAccess(root).create(grant,profiles).path("token").asText();
                }
                try(var runtime=new DbaRuntime(new DbaConfig(root,64L<<20,2,1000,1000,10),vault,false)){
                    String principal=runtime.authenticateAgent(token);
                    var args=Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName","disposable").put("sql","SELECT id FROM "+schema+".items ORDER BY id");
                    var rows=agentAwait(runtime,principal,"dba_execute_read_query",args);assertEquals(100,rows.path("rowCount").asInt());assertTrue(rows.path("truncated").asBoolean());
                    args.put("sql","SELECT value AS a, value AS a FROM "+schema+".items");var big=agentAwait(runtime,principal,"dba_execute_read_query",args);assertTrue(big.path("truncated").asBoolean());assertTrue(big.path("rowCount").asInt()<100);assertTrue(Profiles.JSON.writeValueAsBytes(big).length<1<<20);
                    args.put("sql","SELECT id FROM "+schema+".items WHERE id = ?");args.putArray("parameters").add(42);
                    assertEquals("42",agentAwait(runtime,principal,"dba_execute_read_query",args).path("rows").get(0).get(0).asText());
                    for(String operation:List.of("dba_explain_query","dba_analyze_query_plan")){var plan=agentAwait(runtime,principal,operation,args);assertFalse(plan.path("executed").asBoolean(true));assertTrue(plan.path("rows").toString().contains("Plan"));assertFalse(plan.path("observations").isEmpty());assertTrue(plan.path("observations").get(0).has("Total Cost"));}
                    args.put("schema",schema).put("object","items");assertEquals(2,agentAwait(runtime,principal,"dba_get_metadata",args).path("rowCount").asInt());
                    var ddl=agentAwait(runtime,principal,"dba_get_object_ddl",args);assertFalse(ddl.path("executableScript").asBoolean(true));assertTrue(ddl.toString().contains("PRIMARY KEY"));
                    args.put("object","secret");assertThrows(SecurityException.class,()->runtime.agentCall(principal,"dba_get_object_ddl",args));
                    args.put("sql","SELECT * FROM "+schema+".secret");assertThrows(RuntimeException.class,()->runtime.agentCall(principal,"dba_execute_read_query",args));
                    args.put("sql","DELETE FROM "+schema+".items");assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,"dba_execute_read_query",args));
                    args.put("sql","select version();");
                    for(String operation:List.of("dba_execute_read_query","dba_explain_query","dba_analyze_query_plan"))assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,operation,args));
                    args.put("sql","SELECT a.id FROM "+schema+".items a CROSS JOIN "+schema+".items b CROSS JOIN "+schema+".items c ORDER BY a.id+b.id+c.id");
                    var pending=runtime.agentCall(principal,"dba_execute_read_query",args);var jobArgs=Profiles.JSON.createObjectNode().put("jobId",pending.path("id").asText());
                    runtime.agentCall(principal,"dba_cancel_job",jobArgs);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);com.fasterxml.jackson.databind.JsonNode status;
                    do{status=runtime.agentCall(principal,"dba_job_status",jobArgs);if(status.path("finished").asLong()>0)break;Thread.sleep(20);}while(System.nanoTime()<deadline);
                    assertEquals("cancelled",status.path("state").asText());runtime.agentCall(principal,"dba_release_job",jobArgs);
                }
            }finally{setup.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode agentAwait(DbaRuntime runtime,String principal,String operation,com.fasterxml.jackson.databind.JsonNode args)throws Exception {
        var submitted=runtime.agentCall(principal,operation,args);var job=Profiles.JSON.createObjectNode().put("jobId",submitted.path("id").asText());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);com.fasterxml.jackson.databind.JsonNode status;
        do{status=runtime.agentCall(principal,"dba_job_status",job);if(status.path("finished").asLong()>0)break;Thread.sleep(20);}while(System.nanoTime()<deadline);
        assertEquals("complete",status.path("state").asText(),status.toString());runtime.agentCall(principal,"dba_release_job",job);return status.path("result");
    }
    @Test void postgresBoundedReadsMetadataAndReadOnlyEnforcement()throws Exception {
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD");
        String schema="dba_test_"+UUID.randomUUID().toString().replace("-","");
        try(Connection fixture=DriverManager.getConnection(url,"postgres",password);Statement setup=fixture.createStatement()){
            setup.execute("CREATE SCHEMA "+schema);
            try {
                setup.execute("CREATE TABLE "+schema+".items (id int primary key, value text)");
                setup.execute("INSERT INTO "+schema+".items SELECT n, repeat('x',100) FROM generate_series(1,10000) AS n");
                var vault=new DbaTest.MemoryVault();var cfg=new DbaConfig(root,64L<<20,2,100,10,5);
                try(var p=new Profiles(root,vault);var connections=new Connections(p);var jobs=new QueryJobs(connections,cfg,s->true)){
                    var b=Profiles.JSON.createObjectNode().put("name","disposable PostgreSQL").put("driverClass","org.postgresql.Driver")
                            .put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())
                            .put("url",url).put("username","postgres").put("password",password).put("readOnly",true);
                    String id=p.put(null,b).path("id").asText();
                    var create=HumanSqlTest.run(jobs,id,"CREATE TABLE "+schema+".myusers (id SERIAL PRIMARY KEY, name varchar(50), signup_date date default current_date)",false);assertEquals("complete",create.path("state").asText(),create.toString());
                    var badType=HumanSqlTest.run(jobs,id,"CREATE TABLE "+schema+".badusers (signup_date data)",false);assertEquals("cancelled",badType.path("state").asText());assertTrue(badType.path("result").path("errors").get(0).path("message").asText().contains("type \"data\" does not exist"),badType.toString());
                    assertEquals("complete",HumanSqlTest.run(jobs,id,"INSERT INTO "+schema+".myusers(name) VALUES ('Jane')",false).path("state").asText());
                    var multi=HumanSqlTest.run(jobs,id,"SELECT name FROM "+schema+".myusers; SELECT version();",false);assertEquals("complete",multi.path("state").asText(),multi.toString());assertEquals(2,multi.path("result").path("results").size());
                    var failing=HumanSqlTest.run(jobs,id,"INSERT INTO "+schema+".myusers(name) VALUES ('rollback'); SELECT * FROM "+schema+".absent",false);assertEquals("cancelled",failing.path("state").asText());assertEquals("rollback_requested",failing.path("outcome").asText());
                    try(var rs=setup.executeQuery("SELECT count(*) FROM "+schema+".myusers")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
                    var vacuum=HumanSqlTest.run(jobs,id,"VACUUM "+schema+".myusers",true);assertEquals("complete",vacuum.path("state").asText(),vacuum.toString());
                    var noVacuum=HumanSqlTest.run(jobs,id,"VACUUM "+schema+".myusers",false);assertTrue(noVacuum.path("error").asText().contains("auto-commit"),noVacuum.toString());
                    var sessionChange=HumanSqlTest.run(jobs,id,"SET application_name = 'human-isolated-test'",true);assertEquals("complete",sessionChange.path("state").asText());
                    try(var check=connections.open(id);var statement=check.createStatement();var rs=statement.executeQuery("SHOW application_name")){assertTrue(rs.next());assertNotEquals("human-isolated-test",rs.getString(1));}
                    var version=await(jobs,jobs.query("test",id,"select version();",Profiles.JSON.createArrayNode()).path("id").asText());
                    assertTrue(version.path("result").path("rows").get(0).get(0).asText().startsWith("PostgreSQL"));
                    jobs.remove("test",version.path("id").asText());
                    var versionPlan=await(jobs,jobs.explain("test",id,"select version();",Profiles.JSON.createArrayNode()).path("id").asText());
                    assertFalse(versionPlan.path("result").path("executed").asBoolean(true));jobs.remove("test",versionPlan.path("id").asText());
                    assertThrows(IllegalArgumentException.class,()->jobs.query("agent:restricted",id,"select version();",Profiles.JSON.createArrayNode()));
                    var query=await(jobs,jobs.query("test",id,"SELECT id, value FROM "+schema+".items ORDER BY id",Profiles.JSON.createArrayNode()).path("id").asText());
                    assertEquals(100,query.path("result").path("rowCount").asInt());assertTrue(query.path("result").path("truncated").asBoolean());
                    var metadata=await(jobs,jobs.metadata("test",id,schema,"items").path("id").asText());assertEquals(2,metadata.path("result").path("rowCount").asInt());
                    try(Connection c=connections.open(id);Statement s=c.createStatement()) {c.setAutoCommit(false);c.setReadOnly(true);assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM "+schema+".items"));c.rollback();}
                    try(var rs=setup.executeQuery("SELECT count(*) FROM "+schema+".items")){assertTrue(rs.next());assertEquals(10000,rs.getInt(1));}
                    var delayed=jobs.submit("test",id,(job,c)->{try(var s=c.createStatement()){job.statement=s;s.execute("SELECT pg_sleep(20)");return Profiles.JSON.createObjectNode();}});
                    String delayedId=delayed.path("id").asText();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                    while(jobs.require("test",delayedId).statement==null&&System.nanoTime()<until)Thread.sleep(10);
                    jobs.cancel(jobs.require("test",delayedId));
                    until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                    while(jobs.status("test",delayedId).path("finished").asLong()==0&&System.nanoTime()<until)Thread.sleep(20);
                    assertEquals("cancelled",jobs.status("test",delayedId).path("state").asText());
                }
            } finally {setup.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
    private static com.fasterxml.jackson.databind.JsonNode await(QueryJobs jobs,String id)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);com.fasterxml.jackson.databind.JsonNode result;
        do{result=jobs.status("test",id);if(result.path("finished").asLong()>0)break;Thread.sleep(20);}while(System.nanoTime()<deadline);
        assertEquals("complete",result.path("state").asText(),result.toString());return result;
    }
}
