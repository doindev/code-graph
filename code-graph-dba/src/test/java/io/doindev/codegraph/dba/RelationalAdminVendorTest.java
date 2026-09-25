package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DBA_COMPARE_DISPOSABLE",matches="cgraph-compare-qa-[a-f0-9]+")
class RelationalAdminVendorTest {
    @TempDir Path directory;
    @Test @Timeout(120) void staleReviewAndLeastPrivilegeAreExplicit()throws Exception {
        String engine=System.getenv("DBA_COMPARE_VENDOR"),schema=engine.equals("postgresql")?"public":"compare_test";boolean pg=engine.equals("postgresql");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,60),s->true)) {
            String id=profiles.put(null,CompareVendorIntegrationTest.profile(engine,"Admin safety",System.getenv("DBA_COMPARE_DESTINATION"))).path("id").asText();var admin=new RelationalAdministration(connections,jobs);
            try(var c=connections.open(id);var st=c.createStatement()) {
                c.setAutoCommit(true);st.execute("CREATE TABLE "+schema+".admin_stale(id int)");
                var input=Profiles.JSON.createObjectNode().put("connectionId",id).put("database","compare_test").put("action","analyze").put("schema",schema).put("object","admin_stale");var start=admin.prepare("human",input);assertEquals("complete",TableDesignerTest.waitRetained(jobs,"human",start).path("state").asText());st.execute("ALTER TABLE "+schema+".admin_stale ADD changed int");
                var result=DatabaseCompareTest.finish(jobs,admin.apply("human",Profiles.JSON.createObjectNode().put("planId",start.path("id").asText()).put("confirmed",true)));assertEquals("not_applied",result.path("outcome").asText(),result.toString());assertTrue(result.path("message").asText().contains("changed"));jobs.remove("human",start.path("id").asText());
                st.execute(pg?"CREATE ROLE native_reader_qa LOGIN PASSWORD 'Fixture-only-reader-123'":"CREATE USER 'native_reader_qa'@'%' IDENTIFIED BY 'Fixture-only-reader-123'");st.execute("GRANT SELECT ON "+schema+".admin_stale TO "+(pg?"native_reader_qa":"'native_reader_qa'@'%'") );
                var draft=CompareVendorIntegrationTest.profile(engine,"Limited reader",System.getenv("DBA_COMPARE_DESTINATION")).put("username","native_reader_qa").put("password","Fixture-only-reader-123");String reader=profiles.put(null,draft).path("id").asText();
                var request=Profiles.JSON.createObjectNode().put("connectionId",reader).put("database","compare_test").put("category","roles");var catalog=DatabaseCompareTest.finish(jobs,admin.read("human",request));if(!pg){assertFalse(catalog.path("available").asBoolean());assertTrue(catalog.path("message").asText().contains("privileges"));}
                request.remove("category");request.put("action","create_schema").put("schema","must_not_be_created");var denied=HumanSqlTest.finish(jobs,"human",admin.prepare("human",request));assertEquals("failed",denied.path("state").asText(),denied.toString());assertTrue(denied.path("error").asText().contains("privileges")||denied.path("error").asText().contains("grants")||denied.path("error").asText().contains("superuser"),denied.toString());
                System.out.println("RELATIONAL_ADMIN_SAFETY_VERIFIED "+engine);
            }
        }
    }
    @Test @Timeout(120) void catalogReviewApplyRevalidationAndSecretDisposal()throws Exception{
        String engine=System.getenv("DBA_COMPARE_VENDOR");
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,60),s->true)){
            String connection=profiles.put(null,CompareVendorIntegrationTest.profile(engine,"Administration",System.getenv("DBA_COMPARE_DESTINATION"))).path("id").asText();var admin=new RelationalAdministration(connections,jobs);var input=Profiles.JSON.createObjectNode().put("connectionId",connection).put("database","compare_test");
            var overview=DatabaseCompareTest.finish(jobs,admin.read("human",input));assertEquals(engine,overview.path("target").path("engine").asText());assertFalse(overview.path("categories").isEmpty());
            for(String category:java.util.List.of("roles","schemas","sessions","diagnostics","statistics")){input.put("category",category);var result=DatabaseCompareTest.finish(jobs,admin.read("human",input));assertTrue(result.path("available").asBoolean(),result.toString());}input.remove("category");
            input.put("action","create_user").put("name","native_admin_qa");if(!engine.equals("postgresql"))input.put("host","%");var preview=admin.prepare("human",input);var plan=TableDesignerTest.waitRetained(jobs,"human",preview);assertEquals("complete",plan.path("state").asText(),plan.toString());assertTrue(plan.path("result").path("requiresPassword").asBoolean());String password="Fixture-only-123-strong";assertFalse(plan.toString().contains(password));
            var apply=Profiles.JSON.createObjectNode().put("planId",preview.path("id").asText()).put("confirmed",true).put("password",password);assertThrows(SecurityException.class,()->admin.apply("agent:no",apply));var applied=DatabaseCompareTest.finish(jobs,admin.apply("human",apply));assertEquals("success",applied.path("status").asText(),applied.toString());assertFalse(applied.toString().contains(password));assertThrows(IllegalArgumentException.class,()->admin.apply("human",apply));jobs.remove("human",preview.path("id").asText());
            input.put("action","lock");preview=admin.prepare("human",input);plan=TableDesignerTest.waitRetained(jobs,"human",preview);assertEquals("complete",plan.path("state").asText(),plan.toString());apply.remove("password");apply.put("planId",preview.path("id").asText());applied=DatabaseCompareTest.finish(jobs,admin.apply("human",apply));assertEquals("success",applied.path("status").asText(),applied.toString());jobs.remove("human",preview.path("id").asText());
            try(var c=connections.open(connection);var st=c.createStatement()){c.setAutoCommit(true);st.execute(engine.equals("postgresql")?"CREATE TABLE public.admin_qa(id int)":"CREATE TABLE compare_test.admin_qa(id int)");input.remove(java.util.List.of("name","host"));input.put("action","analyze").put("schema",engine.equals("postgresql")?"public":"compare_test").put("object","admin_qa");preview=admin.prepare("human",input);plan=TableDesignerTest.waitRetained(jobs,"human",preview);assertEquals("complete",plan.path("state").asText(),plan.toString());apply.put("planId",preview.path("id").asText());applied=DatabaseCompareTest.finish(jobs,admin.apply("human",apply));assertEquals("success",applied.path("status").asText(),applied.toString());jobs.remove("human",preview.path("id").asText());}
            System.out.println("RELATIONAL_ADMIN_VERIFIED "+engine);
        }
    }
}
