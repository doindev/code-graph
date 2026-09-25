package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DBA_COMPARE_DISPOSABLE",matches="cgraph-compare-qa-[a-f0-9]+")
class ObjectEditorMetadataVendorTest {
    @TempDir Path root;
    @Test @Timeout(120) void mysqlFamilyEditorsNeverLoadUnrelatedDriverDatatypes()throws Exception{
        String engine=System.getenv("DBA_COMPARE_VENDOR");Assumptions.assumeTrue(MysqlDialect.supports(engine));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,60),o->true)){
            String id=profiles.put(null,CompareVendorIntegrationTest.profile(engine,"Editor metadata",System.getenv("DBA_COMPARE_SOURCE"))).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE TABLE compare_test.editor_metadata_table(id int PRIMARY KEY)");st.execute("CREATE VIEW compare_test.editor_metadata_view AS SELECT id FROM compare_test.editor_metadata_table");
                var job=jobs.new Job("human",id);job.beginActiveBudget(60);var observed=CompareMetadataTest.withoutEditorCatalogs(c);
                for(String kind:List.of("views","indexes","functions","procedures","events","table_columns","table_constraints","table_foreign_keys","table_triggers")){
                    var request=ObjectDesignerTest.newObject(kind,"compare_test");((ObjectNode)request.path("target")).put("database","compare_test").put("table","editor_metadata_table");
                    var snapshot=ObjectDesigner.load(job,observed,request,false);assertEquals(engine,snapshot.path("engine").asText());assertTrue(snapshot.path("controls").size()>0,kind);assertFalse(snapshot.path("details").has("Datatypes"),kind);
                }
                var request=MetadataActionsTest.selection(jobs,id,"views","compare_test","editor_metadata_view");((ObjectNode)request.path("parent")).put("database","compare_test");var snapshot=ObjectDesigner.load(job,observed,request,false);assertTrue(snapshot.path("ddl").asText().toLowerCase().contains("editor_metadata_view"),snapshot.toString());assertFalse(snapshot.path("details").has("Datatypes"));
                System.out.println("MYSQL_FAMILY_OBJECT_EDITOR_HINTS_VERIFIED "+engine+" 9 creation categories and native view reopen");
            }finally{try(var c=connections.open(id);var st=c.createStatement()){c.setAutoCommit(true);st.execute("DROP VIEW IF EXISTS compare_test.editor_metadata_view");st.execute("DROP TABLE IF EXISTS compare_test.editor_metadata_table");}}
        }
    }
    @Test @Timeout(180) void postgresEditorsOpenAndSchemasSaveWithAnOversizedDriverDatatypeCatalog()throws Exception{
        Assumptions.assumeTrue("postgresql".equals(System.getenv("DBA_COMPARE_VENDOR")));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,384L<<20,2,100,100,60),o->true)){
            String id=profiles.put(null,CompareVendorIntegrationTest.profile("postgresql","Editor metadata",System.getenv("DBA_COMPARE_SOURCE"))).path("id").asText();
            try(var c=connections.open(id);var st=c.createStatement()){
                c.setAutoCommit(true);st.execute("CREATE SCHEMA editor_datatype_noise");
                for(int i=0;i<650;i++)st.execute("CREATE TYPE editor_datatype_noise.unrelated_long_named_application_datatype_for_regression_"+i+" AS ENUM ('value')");
                var job=jobs.new Job("human",id);job.beginActiveBudget(60);
                try(var types=c.getMetaData().getTypeInfo()){var failure=assertThrows(IllegalArgumentException.class,()->ObjectCatalog.rows(job,types));assertTrue(failure.getMessage().contains("1 MiB editor limit"),failure.getMessage());}
                for(String kind:List.of("schemas","sequences","views","materialized_views","functions","procedures","indexes","types","domains","roles","extensions","foreign_servers","event_triggers","table_columns","table_constraints","table_foreign_keys","table_triggers","table_policies","table_rules")){
                    var request=ObjectDesignerTest.newObject(kind,"public");((ObjectNode)request.path("target")).put("table","fixture");
                    var snapshot=ObjectDesignerTest.load(jobs,id,request);assertTrue(snapshot.path("controls").size()>0,kind);assertFalse(snapshot.path("details").has("Datatypes"),kind);
                    if(List.of("sequences","functions","procedures","domains","table_columns").contains(kind))assertTrue(snapshot.path("warnings").toString().contains("Optional suggestions omitted"),kind+snapshot);
                }
                var request=ObjectDesignerTest.newObject("schemas","");var snapshot=ObjectDesignerTest.load(jobs,id,request);assertTrue(snapshot.path("choices").has("owner"));assertEquals(1,snapshot.path("choices").size());
                var draft=ObjectDesignerTest.draft(snapshot);((ObjectNode)draft.path("fields")).put("name","editor_created");
                var saved=ObjectDesignerTest.save(jobs,id,request,snapshot,draft);assertEquals("success",saved.path("result").path("status").asText(),saved.toString());
                var existing=MetadataActionsTest.selection(jobs,id,"schemas","","editor_created");snapshot=ObjectDesignerTest.load(jobs,id,existing);assertEquals("editor_created",snapshot.path("fields").path("name").asText());
                st.execute("CREATE SEQUENCE editor_created.counter START WITH 23");existing=MetadataActionsTest.selection(jobs,id,"sequences","editor_created","counter");snapshot=ObjectDesignerTest.load(jobs,id,existing);assertEquals("23",snapshot.path("fields").path("start").asText());assertTrue(snapshot.path("warnings").toString().contains("Optional suggestions omitted"));
                System.out.println("POSTGRES_OBJECT_EDITORS_LARGE_DATATYPES_VERIFIED 19 creation categories, schema reviewed apply/reopen, sequence reopen");
            }finally{try(var c=connections.open(id);var st=c.createStatement()){c.setAutoCommit(true);st.execute("DROP SCHEMA IF EXISTS editor_created CASCADE;DROP SCHEMA IF EXISTS editor_datatype_noise CASCADE");}}
        }
    }
}
