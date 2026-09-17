package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.doindev.codegraph.dba.ObjectDesignerTest.*;

@EnabledIfEnvironmentVariable(named="DBA_TEST_DISPOSABLE",matches="code-graph-dba-test-[a-f0-9]+")
class ObjectDesignerPostgresTest {
    @TempDir Path root;
    @Test void nativeObjectFormsDefinitionsAndManualRefresh()throws Exception{
        String schema="objects_"+UUID.randomUUID().toString().replace("-","");
        String url=System.getenv("DBA_TEST_URL"),password=System.getenv("DBA_TEST_PASSWORD");
        try(var c=DriverManager.getConnection(url,"postgres",password);var st=c.createStatement();
            var p=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(p);
            var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,1000,100,20),o->true)){
            st.execute("CREATE SCHEMA "+schema);
            try{
                String id=p.put(null,Profiles.JSON.createObjectNode().put("name","native objects").put("url",url).put("username","postgres").put("password",password).put("driverClass","org.postgresql.Driver").put("jar",Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString())).path("id").asText();
                st.execute("CREATE TABLE "+schema+".source(id integer)");
                st.execute("INSERT INTO "+schema+".source VALUES(1)");
                for(String k:List.of("sequences","views","materialized_views","functions","procedures","indexes","types")){
                    var request=newObject(k,schema);var snapshot=load(jobs,id,request);var draft=draft(snapshot);ObjectNode f=(ObjectNode)draft.path("fields");f.put("name","test_"+k);
                    switch(k){
                        case "sequences"->f.put("start","9007199254740993").put("cache","12").put("increment","2");
                        case "views","materialized_views"->f.put("query","SELECT id FROM "+schema+".source");
                        case "functions"->f.put("parameters","x integer DEFAULT 7").put("configuration","search_path=pg_catalog, "+schema).put("returns","integer").put("body","SELECT x + 1").put("volatility","IMMUTABLE");
                        case "procedures"->f.put("language","plpgsql").put("body","BEGIN NULL; END");
                        case "indexes"->f.put("table",schema+".source").put("columns","id").put("unique",true);
                        case "types"->f.put("labels","small\nlarge");
                    }
                    var result=save(jobs,id,request,snapshot,draft);assertEquals("success",result.path("result").path("status").asText(),k+": "+result);
                    String display=k.equals("functions")?"test_functions(x integer)":k.equals("procedures")?"test_procedures()":"test_"+k;
                    var selection=MetadataActionsTest.selection(jobs,id,k,schema,display);var loaded=load(jobs,id,selection);
                    assertTrue(loaded.path("categories").toString().contains("DDL"));assertFalse(loaded.path("ddl").asText().isBlank(),k);
                    if(k.equals("materialized_views")){assertEquals("postgresql",loaded.path("refreshSchedule").path("provider").asText());assertTrue(loaded.path("categories").toString().contains("Refresh"));assertTrue(loaded.path("refreshSchedule").path("config").isObject());if(!loaded.path("refreshSchedule").path("editable").asBoolean())assertFalse(loaded.path("refreshSchedule").path("guidance").isEmpty());}
                    if(k.equals("indexes"))assertFalse(loaded.path("controls").findParents("id").stream().filter(x->x.path("id").asText().equals("owner")).findFirst().orElseThrow().path("editable").asBoolean());
                    if(k.equals("functions")){
                        var edit=draft(loaded);((ObjectNode)edit.path("fields")).put("body","SELECT x + 2");
                        result=save(jobs,id,selection,loaded,edit);assertEquals("success",result.path("result").path("status").asText(),result.toString());
                        try(var rs=st.executeQuery("SELECT "+schema+".test_functions(4)")){assertTrue(rs.next());assertEquals(6,rs.getInt(1));}
                    }
                    if(k.equals("sequences")){
                        var edit=draft(loaded);((ObjectNode)edit.path("fields")).put("cache","24");
                        result=save(jobs,id,selection,loaded,edit);assertEquals("success",result.path("result").path("status").asText(),result.toString());
                        try(var rs=st.executeQuery("SELECT nextval('"+schema+".test_sequences')")){assertTrue(rs.next());assertEquals(9007199254740993L,rs.getLong(1));}
                    }
                    if(k.equals("materialized_views")){
                        st.execute("INSERT INTO "+schema+".source VALUES(2)");
                        var plan=MetadataActionsTest.preview(jobs,id,selection);assertTrue(plan.path("canRefresh").asBoolean());
                        result=MetadataActionsTest.action(jobs,id,selection,"refresh","");assertEquals("complete",result.path("state").asText(),result.toString());
                        try(var rs=st.executeQuery("SELECT count(*) FROM "+schema+".test_materialized_views")){assertTrue(rs.next());assertEquals(2,rs.getInt(1));}
                        st.execute("CREATE INDEX keep_mat_index ON "+schema+".test_materialized_views(id)");
                        st.execute("COMMENT ON INDEX "+schema+".keep_mat_index IS 'keep this index comment'");
                        st.execute("COMMENT ON MATERIALIZED VIEW "+schema+".test_materialized_views IS 'keep this view comment'");
                        st.execute("GRANT SELECT ON "+schema+".test_materialized_views TO PUBLIC");
                        loaded=load(jobs,id,selection);var edit=draft(loaded);((ObjectNode)edit.path("fields")).put("query","SELECT id + 10 AS id FROM "+schema+".source");
                        result=save(jobs,id,selection,loaded,edit);assertEquals("success",result.path("result").path("status").asText(),result.toString());
                        try(var rs=st.executeQuery("SELECT min(id) FROM "+schema+".test_materialized_views")){assertTrue(rs.next());assertEquals(11,rs.getInt(1));}
                        try(var rs=st.executeQuery("SELECT obj_description('"+schema+".keep_mat_index'::regclass,'pg_class')")){assertTrue(rs.next());assertEquals("keep this index comment",rs.getString(1));}
                        try(var rs=st.executeQuery("SELECT relacl::text FROM pg_class WHERE oid='"+schema+".test_materialized_views'::regclass")){assertTrue(rs.next());assertTrue(rs.getString(1).contains("=r/"));}

                    }
                }
                st.execute("CREATE TYPE "+schema+".composite_value AS (id integer, label text)");
                var selection=MetadataActionsTest.selection(jobs,id,"types",schema,"composite_value");
                var loaded=load(jobs,id,selection);var edit=draft(loaded);
                assertFalse(loaded.path("controls").findParents("id").stream().filter(x->x.path("id").asText().equals("labels")).findFirst().orElseThrow().path("editable").asBoolean());
                ((ObjectNode)edit.path("fields")).put("comment","Composite attributes remain intact");
                var result=save(jobs,id,selection,loaded,edit);assertEquals("success",result.path("result").path("status").asText(),result.toString());
                try(var rs=st.executeQuery("SELECT obj_description('"+schema+".composite_value'::regtype,'pg_type')")){assertTrue(rs.next());assertEquals("Composite attributes remain intact",rs.getString(1));}
            }finally{st.execute("DROP SCHEMA "+schema+" CASCADE");}
        }
    }
}
