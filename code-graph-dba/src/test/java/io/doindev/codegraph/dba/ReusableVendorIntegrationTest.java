package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Only used with an explicitly owned disposable container; never targets saved user connections. */
@EnabledIfEnvironmentVariable(named="DBA_REUSABLE_DISPOSABLE",matches="cgraph-reusable-qa-[a-f0-9]+")
class ReusableVendorIntegrationTest {
    @TempDir Path directory;
    QueryJobs activeJobs;
    @Test void createReadExplainInspectAndRevoke()throws Exception{
        String vendor=System.getenv("DBA_REUSABLE_VENDOR"),database="approval_test",schema=vendor.equals("postgresql")?"public":database;
        String driver=vendor.equals("postgresql")?"org.postgresql.Driver":vendor.equals("mariadb")?"org.mariadb.jdbc.Driver":"com.mysql.cj.jdbc.Driver";
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles)){
            ObjectNode draft=Profiles.JSON.createObjectNode().put("name","Owned approval fixture").put("templateId",vendor).put("url",System.getenv("DBA_REUSABLE_URL"))
                    .put("driverClass",driver).put("jar",System.getenv("DBA_REUSABLE_JAR")).put("username",System.getenv("DBA_REUSABLE_USER")).put("password",System.getenv("DBA_REUSABLE_PASSWORD")).put("readOnly",false);
            String connection=profiles.put(null,draft).path("id").asText();AgentAccess agents=new AgentAccess(directory);String principal=agents.trustedLocal();
            try(var contexts=new ProjectContexts(profiles,connections,agents);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,15),agents::alive);var approvals=new ApprovalQueue(contexts,profiles,agents,jobs)){
                activeJobs=jobs;
                approvals.reusable.sessions.register("one",principal,System.currentTimeMillis()+120000);approvals.reusable.sessions.register("two",principal,System.currentTimeMillis()+120000);
                String table=schema+".qa_items";
                submit(approvals,principal,connection,"CREATE TABLE "+table+"(id INT PRIMARY KEY, name VARCHAR(80))",ReusableApprovals.SIMILAR,"one");
                submit(approvals,principal,connection,"CREATE TABLE "+schema+".qa_other(id INT)",null,"two");
                submit(approvals,principal,connection,"INSERT INTO "+table+" VALUES(1,'retained')","approve_once","one");
                submit(approvals,principal,connection,"CREATE VIEW "+schema+".qa_view AS SELECT id, name FROM "+table,ReusableApprovals.SIMILAR,"one");
                submit(approvals,principal,connection,"SELECT id, name FROM "+table,ReusableApprovals.SIMILAR,"one");
                submit(approvals,principal,connection,"SELECT id FROM "+table+" WHERE id=1",null,"two");
                submit(approvals,principal,connection,"SELECT TRUE AS enabled FROM "+table+" WHERE FALSE OR id=1",null,"two");
                submit(approvals,principal,connection,"SELECT FALSE",null,"two");
                if(vendor.equals("postgresql")){
                    var outside=approvals.request(principal,"one",base(connection,"SELECT oid FROM pg_class"));assertTrue(outside.has("matchedPolicy"));
                    JsonNode stopped=outside;long until=System.nanoTime()+10_000_000_000L;
                    while(System.nanoTime()<until){stopped=approvals.get(principal,outside.path("id").asText());if(Set.of("failed","complete").contains(stopped.path("state").asText()))break;Thread.sleep(20);}
                    assertEquals("failed",stopped.path("state").asText(),stopped.toString());assertEquals("java.lang.SecurityException",stopped.path("job").path("exception").path("type").asText(),stopped.toString());
                    activeJobs.remove("agent:"+principal,stopped.path("jobId").asText());
                }
                submit(approvals,principal,connection,"EXPLAIN SELECT id FROM "+table,ReusableApprovals.SESSION,"one");
                String inspection;
                if(vendor.equals("postgresql")){
                    var request=base(connection,"SELECT 1");var scope=ApprovalScope.resolve(profiles.get(connection),Profiles.JSON.createObjectNode(),request);
                    var q=TrustedCatalogRead.prepare("dba_get_object_ddl",scope,Profiles.JSON.createObjectNode().put("object","qa_view"));request.put("sql",q.sql());request.set("parameters",q.parameters());
                    var pending=approvals.trustedCatalog(principal,"one",request);approvals.decide("human",pending.path("id").asText(),ReusableApprovals.SIMILAR,true);finish(approvals,principal,pending);
                    request.put("requestId",UUID.randomUUID().toString());var again=approvals.trustedCatalog(principal,"two",request);assertTrue(again.has("matchedPolicy"));finish(approvals,principal,again);
                    inspection="CREATE FUNCTION public.qa_plus(x INT) RETURNS INT LANGUAGE SQL SECURITY INVOKER AS $$ SELECT x+1 $$";
                }else{
                    submit(approvals,principal,connection,"SHOW CREATE TABLE "+table,ReusableApprovals.SIMILAR,"one");
                    submit(approvals,principal,connection,"SHOW CREATE VIEW "+schema+".qa_view",null,"two");
                    inspection="CREATE FUNCTION "+schema+".qa_plus(x INT) RETURNS INT DETERMINISTIC NO SQL SQL SECURITY INVOKER RETURN x+1";
                }
                submit(approvals,principal,connection,inspection,ReusableApprovals.SIMILAR,"one");
                String procedure=vendor.equals("postgresql")?"CREATE PROCEDURE public.qa_proc() LANGUAGE SQL AS $$ SELECT 1 $$":"CREATE PROCEDURE "+schema+".qa_proc() READS SQL DATA SQL SECURITY INVOKER SELECT id FROM "+table;
                submit(approvals,principal,connection,procedure,ReusableApprovals.SIMILAR,"one");
                for(String sql:List.of("DROP TABLE "+table,"SELECT "+schema+".qa_plus(1)","CALL "+schema+".qa_proc()")){
                    var pending=approvals.request(principal,"one",base(connection,sql));assertEquals("awaiting_approval",pending.path("state").asText());assertFalse(pending.path("operation").path("eligible").asBoolean());approvals.cancel(principal,pending.path("id").asText());
                }
                for(JsonNode p:approvals.reusable.list(principal))approvals.reusable.change(principal,p.path("id").asText(),null);
                assertEquals("awaiting_approval",approvals.request(principal,"one",base(connection,"SELECT id FROM "+table)).path("state").asText());
            }
        }
    }
    static ObjectNode base(String id,String sql){return Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Owned approval fixture").put("requestId",UUID.randomUUID().toString()).put("purpose","Disposable reusable approval verification").put("sql",sql);}
    void submit(ApprovalQueue q,String principal,String connection,String sql,String action,String session)throws Exception{
        JsonNode r=q.request(principal,session,base(connection,sql));
        if(action==null)assertTrue(r.has("matchedPolicy"),r.toString());else{assertEquals("awaiting_approval",r.path("state").asText());q.decide("human",r.path("id").asText(),action,true);}
        finish(q,principal,r);
    }
    void finish(ApprovalQueue q,String principal,JsonNode initial)throws Exception{
        JsonNode done=initial;long until=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<until){done=q.get(principal,initial.path("id").asText());if(Set.of("complete","failed","cancelled").contains(done.path("state").asText()))break;Thread.sleep(20);}
        assertEquals("complete",done.path("state").asText(),done.toString());
        activeJobs.remove("agent:"+principal,done.path("jobId").asText());
    }
}
