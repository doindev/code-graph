package io.doindev.codegraph.dba;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DBA_REUSABLE_DISPOSABLE",matches="cgraph-reusable-qa-[a-f0-9]+")
class ReadPermissionVendorIntegrationTest {
    @TempDir Path directory;
    QueryJobs activeJobs;
    @Test void scopedReadsMetadataAndRevocation()throws Exception{
        String vendor=System.getenv("DBA_REUSABLE_VENDOR"),database="approval_test",schema=vendor.equals("postgresql")?"public":database;
        String driver=vendor.equals("postgresql")?"org.postgresql.Driver":vendor.equals("mariadb")?"org.mariadb.jdbc.Driver":"com.mysql.cj.jdbc.Driver";
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles)){
            ObjectNode profile=Profiles.JSON.createObjectNode().put("name","Read permission fixture").put("templateId",vendor).put("url",System.getenv("DBA_REUSABLE_URL"))
                .put("driverClass",driver).put("jar",System.getenv("DBA_REUSABLE_JAR")).put("username",System.getenv("DBA_REUSABLE_USER")).put("password",System.getenv("DBA_REUSABLE_PASSWORD")).put("readOnly",false);
            String id=profiles.put(null,profile).path("id").asText();var agents=new AgentAccess(directory);String agent=agents.trustedLocal();
            try(var contexts=new ProjectContexts(profiles,connections,agents);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,100,15),agents::alive);var q=new ApprovalQueue(contexts,profiles,agents,jobs)){
                activeJobs=jobs;q.reusable.sessions.register("one",agent,System.currentTimeMillis()+120000);q.reusable.sessions.register("two",agent,System.currentTimeMillis()+120000);
                String items=schema+".rp_items",other=schema+".rp_other";
                try(Connection c=connections.open(id);Statement st=c.createStatement()){
                    st.execute("CREATE TABLE "+items+" (id INT PRIMARY KEY, name VARCHAR(40), amount DECIMAL(30,5))");st.execute("INSERT INTO "+items+" VALUES (1,'First',12345678901234567890.12345),(2,NULL,2)");
                    st.execute("CREATE TABLE "+other+" (id INT)");st.execute("INSERT INTO "+other+" VALUES (1),(2)");c.commit();
                }
                var pending=q.request(agent,"one",request(id,database,schema,"SELECT COUNT(*) FROM "+items));
                assertTrue(pending.path("selectPermission").path("eligible").asBoolean(),pending.toString());
                var grant=Profiles.JSON.createObjectNode().put("lifetime","mcp_session");grant.putArray("selectors").addObject().put("connectionId",id).put("level","object").put("database",database).put("schema",schema).put("object","rp_items");
                var options=Profiles.JSON.createObjectNode();options.set("readGrant",grant);
                q.decide("human",pending.path("id").asText(),ReadPermissions.ACTION,true,options);
                var complete=finish(q,agent,pending);assertEquals("2",complete.path("job").path("result").path("results").get(0).path("rows").get(0).get(0).asText());
                var secondSession=q.request(agent,"two",request(id,database,schema,"SELECT id FROM "+items));assertEquals("awaiting_approval",secondSession.path("state").asText());q.cancel(agent,secondSession.path("id").asText());
                for(String sql:List.of("WITH x AS (SELECT id FROM "+items+") SELECT id FROM x UNION ALL SELECT id FROM "+items,
                    "SELECT COALESCE(name,'empty'), ABS(id), amount FROM "+items+" ORDER BY id","SELECT id, SUM(amount) FROM "+items+" GROUP BY id HAVING SUM(amount)>0")){
                    finish(q,agent,q.request(agent,"one",request(id,database,schema,sql)));
                }
                var unknown=q.request(agent,"one",request(id,database,schema,"SELECT * FROM "+other));assertEquals("awaiting_approval",unknown.path("state").asText());q.cancel(agent,unknown.path("id").asText());
                // A view grant covers its output, while direct underlying-table reads remain uncovered.
                try(Connection c=connections.open(id);Statement st=c.createStatement()){
                    st.execute("CREATE VIEW "+schema+".rp_view AS SELECT * FROM "+other);c.commit();
                }
                var viewGrant=grant.deepCopy();((ObjectNode)viewGrant.withArray("selectors").get(0)).put("object","rp_view");
                q.reads.save("human",q.reads.prepare(agent,"one",viewGrant,null,null),null);
                finish(q,agent,q.request(agent,"one",request(id,database,schema,"SELECT * FROM "+schema+".rp_view")));
                var rawCatalog=q.request(agent,"one",request(id,database,schema,"SELECT table_name FROM information_schema.tables WHERE table_schema='"+schema+"'"));
                assertEquals("awaiting_approval",rawCatalog.path("state").asText());q.cancel(agent,rawCatalog.path("id").asText());
                ObjectNode scope=ApprovalScope.resolve(profiles.get(id),Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode().put("database",database).put("schema",schema));
                ObjectNode catalogArgs=Profiles.JSON.createObjectNode().put("kind","tables");var catalog=TrustedCatalogRead.prepare("dba_get_metadata",scope,catalogArgs);
                var inspection=request(id,database,schema,catalog.sql());inspection.set("parameters",catalog.parameters());inspection.set("catalogArguments",catalogArgs);
                var metadata=finish(q,agent,q.trustedCatalog(agent,"one",inspection));var rows=metadata.path("job").path("result").path("results").get(0).path("rows");
                assertEquals(2,rows.size(),metadata.toString());assertEquals("rp_items",rows.get(0).get(2).asText());
                for(String kind:List.of("columns","keys","indexes")){
                    ObjectNode args=Profiles.JSON.createObjectNode().put("kind",kind).put("object","rp_items");var query=TrustedCatalogRead.prepare("dba_get_metadata",scope,args);
                    var inspect=request(id,database,schema,query.sql());inspect.set("parameters",query.parameters());inspect.set("catalogArguments",args);finish(q,agent,q.trustedCatalog(agent,"one",inspect));
                }
                String extra="rp_extra",quoted=vendor.equals("postgresql")?"\"Odd.Name\"":"`Odd.Name`";
                try(Connection c=connections.open(id);Statement st=c.createStatement()){
                    st.execute((vendor.equals("postgresql")?"CREATE SCHEMA ":"CREATE DATABASE ")+extra);
                    st.execute("CREATE TABLE "+extra+"."+quoted+" (id INT)");st.execute("INSERT INTO "+extra+"."+quoted+" VALUES (1)");
                    if(vendor.equals("postgresql"))st.execute("CREATE FUNCTION public.abs(integer) RETURNS integer LANGUAGE SQL IMMUTABLE AS 'SELECT -777'");
                    c.commit();
                }
                String cross="SELECT a.id FROM "+items+" a JOIN "+extra+"."+quoted+" b ON a.id=b.id";
                var uncovered=q.request(agent,"one",request(id,database,schema,cross));assertEquals("awaiting_approval",uncovered.path("state").asText());q.cancel(agent,uncovered.path("id").asText());
                var crossGrant=Profiles.JSON.createObjectNode().put("lifetime","until_revoked");crossGrant.putArray("selectors").addObject().put("connectionId",id).put("level","schema").put("database",vendor.equals("postgresql")?database:extra).put("schema",extra);
                q.reads.save("human",q.reads.prepare(agent,"one",crossGrant,null,null),null);finish(q,agent,q.request(agent,"one",request(id,database,schema,cross)));
                var builtin=finish(q,agent,q.request(agent,"one",request(id,database,schema,"SELECT ABS(id),amount FROM "+items+" WHERE id=1")));
                var precise=builtin.path("job").path("result").path("results").get(0).path("rows").get(0);assertEquals("1",precise.get(0).asText());assertEquals("12345678901234567890.12345",precise.get(1).asText());

                if(vendor.equals("postgresql")){
                    try(Connection c=connections.open(id);Statement st=c.createStatement()){
                        st.execute("CREATE FUNCTION pg_catalog.abs(text) RETURNS integer LANGUAGE SQL IMMUTABLE AS 'SELECT -999'");c.commit();
                        try{
                            var custom=q.request(agent,"one",request(id,database,schema,"SELECT ABS(name) FROM "+items));JsonNode stopped=custom;long until=System.nanoTime()+5_000_000_000L;
                            while(System.nanoTime()<until){stopped=q.get(agent,custom.path("id").asText());if(stopped.path("state").asText().equals("failed"))break;Thread.sleep(10);}
                            assertEquals("failed",stopped.path("state").asText(),stopped.toString());assertTrue(stopped.path("job").path("error").asText().contains("overloads"));assertFalse(stopped.path("job").has("result"));jobs.remove("agent:"+agent,stopped.path("jobId").asText());
                        }finally{st.execute("DROP FUNCTION pg_catalog.abs(text)");c.commit();}
                    }
                }
                var otherGrant=grant.deepCopy().put("lifetime","until_revoked");((ObjectNode)otherGrant.withArray("selectors").get(0)).put("object","rp_other");
                q.reads.save("human",q.reads.prepare(agent,"one",otherGrant,null,null),null);
                finish(q,agent,q.request(agent,"one",request(id,database,schema,"SELECT a.id FROM "+items+" a JOIN "+other+" b ON a.id=b.id")));
                var read=q.request(agent,"one",request(id,database,schema,"SELECT * FROM "+items));var done=finish(q,agent,read,true);String job=done.path("jobId").asText();
                for(JsonNode p:q.reusable.list(agent))if(p.path("lifetime").asText().equals("mcp_session"))q.reusable.change(agent,p.path("id").asText(),null);
                assertThrows(SecurityException.class,()->jobs.status("agent:"+agent,job));
                var removed=q.get(agent,read.path("id").asText());assertFalse(removed.path("job").has("result"));
                var broad=Profiles.JSON.createObjectNode().put("lifetime","until_revoked");broad.putArray("selectors").addObject().put("connectionId",id).put("level","database").put("database",database);
                var broadPolicy=q.reads.save("human",q.reads.prepare(agent,"one",broad,null,null),null);
                try(Connection c=connections.open(id);Statement st=c.createStatement()){st.execute("CREATE TABLE "+schema+".rp_future (id INT)");c.commit();}
                finish(q,agent,q.request(agent,"two",request(id,database,schema,"SELECT * FROM "+schema+".rp_future")));
                if(!vendor.equals("postgresql"))finish(q,agent,q.request(agent,"two",request(id,database,schema,"SHOW CREATE DATABASE "+database)));
                var denied=q.request(agent,"one",request(id,database,schema,"UPDATE "+items+" SET name='bad'"));assertEquals("awaiting_approval",denied.path("state").asText());assertFalse(denied.path("selectPermission").path("eligible").asBoolean());q.cancel(agent,denied.path("id").asText());
                try(Connection blocker=connections.open(id);Statement lock=blocker.createStatement()){
                    blocker.setAutoCommit(!vendor.equals("postgresql"));
                    lock.execute(vendor.equals("postgresql")?"LOCK TABLE "+items+" IN ACCESS EXCLUSIVE MODE":"LOCK TABLES "+items+" WRITE");
                    try{
                        var running=q.request(agent,"two",request(id,database,schema,"SELECT COUNT(*) FROM "+items));String runningId=running.path("jobId").asText();assertFalse(runningId.isEmpty(),running.toString());
                        var active=jobs.require("agent:"+agent,runningId);long start=System.nanoTime();while(active.statement==null&&active.finished==0&&System.nanoTime()-start<3_000_000_000L)Thread.sleep(10);
                        assertEquals(0,active.finished,"Locked read must remain active before revocation: "+active.error);q.reusable.change(agent,broadPolicy.path("id").asText(),null);q.readPermissionsChanged(agent);
                        assertTrue(active.cancelled,"Revocation must request cancellation");assertThrows(SecurityException.class,()->jobs.status("agent:"+agent,runningId));
                        long cancelUntil=System.nanoTime()+8_000_000_000L;while(active.finished==0&&System.nanoTime()<cancelUntil)Thread.sleep(10);assertTrue(active.finished>0,"Revocation must stop the dependent read");
                    }finally{if(vendor.equals("postgresql"))blocker.rollback();else lock.execute("UNLOCK TABLES");}
                }
            }
        }
    }
    static ObjectNode request(String id,String database,String schema,String sql){return Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName","Read permission fixture").put("database",database).put("schema",schema).put("requestId",UUID.randomUUID().toString()).put("purpose","Owned disposable SELECT permission verification").put("sql",sql);}
    JsonNode finish(ApprovalQueue q,String agent,JsonNode first)throws Exception{return finish(q,agent,first,false);}
    JsonNode finish(ApprovalQueue q,String agent,JsonNode first,boolean retain)throws Exception{
        JsonNode done=first;long until=System.nanoTime()+20_000_000_000L;
        while(System.nanoTime()<until){done=q.get(agent,first.path("id").asText());if(Set.of("complete","failed","cancelled","result_expired").contains(done.path("state").asText()))break;Thread.sleep(20);}
        assertEquals("complete",done.path("state").asText(),done.toString());if(!retain)activeJobs.remove("agent:"+agent,done.path("jobId").asText());return done;
    }
}
