package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ReusableApprovalsTest {
    @TempDir Path directory;
    final AtomicLong clock=new AtomicLong(1000);
    ObjectNode scope(){return ReusableOperationTest.scope("mysql").put("profileRevision","r1");}
    ObjectNode request(String sql){ObjectNode n=Profiles.JSON.createObjectNode().put("sql",sql).put("autoCommit",false);n.putArray("parameters");return n;}
    ReusableApprovals policies()throws Exception{var p=new ReusableApprovals(directory,clock::get);p.sessions.register("one","agent",10000);p.sessions.register("two","agent",10000);return p;}
    @Test void sessionIsolationExpiryAndRestart()throws Exception{
        var q=request("SHOW CREATE TABLE app.items");var s=scope();var op=ReusableOperation.classify(q.path("sql").asText(),s);
        try(var p=policies()){
            p.grant("agent","one",ReusableApprovals.SESSION,q,s,op);
            assertNotNull(p.match("agent","one",q,s,op));assertNull(p.match("agent","two",q,s,op));
            p.sessions.remove("one");assertTrue(p.list("agent").isEmpty());
            assertThrows(IllegalArgumentException.class,()->p.grant("agent","one",ReusableApprovals.SESSION,q,s,op));
            p.grant("agent","two",ReusableApprovals.SESSION,q,s,op);clock.set(10001);assertNull(p.match("agent","two",q,s,op));
        }
        clock.set(1000);try(var p=policies()){assertTrue(p.list("agent").isEmpty());}
    }
    @Test void exactFingerprintIsSensitiveToTypesOptionsAndScopeNotPurpose()throws Exception{
        var q=request("SELECT ?");q.withArray("parameters").add(1);var s=scope();var op=ReusableOperation.classify("SELECT ?",s);
        try(var p=policies()){
            p.grant("agent","one",ReusableApprovals.EXACT,q,s,op);
            assertNotNull(p.match("agent","two",q.deepCopy().put("purpose","different").put("requestId","new"),s,op));
            ObjectNode typed=q.deepCopy();typed.putArray("parameters").add("1");assertNull(p.match("agent","one",typed,s,op));
            assertNull(p.match("agent","one",q.deepCopy().put("autoCommit",true),s,op));
            assertNull(p.match("agent","one",q.deepCopy().put("sql","SELECT  ?"),s,op));
            for(String field:List.of("database","schema","connectionId","profileRevision","projectId","environment","bindingId","role"))
                assertNull(p.match("agent","one",q,s.deepCopy().put(field,"other"),op),field);
            String stored=Files.readString(directory.resolve("reusable-approvals.json"));assertFalse(stored.contains("SELECT"));assertFalse(stored.contains("parameters"));assertFalse(stored.contains("\"session\""));
        }
        try(var p=policies()){assertNotNull(p.match("agent","two",q,s,op));}
    }
    @Test void categoryIndependenceRevocationAndDangerousForgery()throws Exception{
        var s=scope();var q=request("CREATE TABLE item(id INT)");var op=ReusableOperation.classify(q.path("sql").asText(),s);
        try(var p=policies()){
            var grant=p.grant("agent","one",ReusableApprovals.SIMILAR,q,s,op);String id=grant.path("id").asText();
            var other=request("CREATE TABLE other(id INT)");assertNotNull(p.match("agent","two",other,s,ReusableOperation.classify(other.path("sql").asText(),s)));
            var view=request("CREATE VIEW other AS SELECT 1");assertNull(p.match("agent","two",view,s,ReusableOperation.classify(view.path("sql").asText(),s)));
            p.change("agent",id,false);assertThrows(SecurityException.class,()->p.require(id,"agent","one",q,s,op));p.change("agent",id,true);
            p.change("agent",id,null);assertThrows(SecurityException.class,()->p.require(id,"agent","one",q,s,op));
            for(String action:ReusableApprovals.ACTIONS){var bad=request("DROP TABLE item");assertThrows(IllegalArgumentException.class,()->p.grant("agent","one",action,bad,s,ReusableOperation.classify(bad.path("sql").asText(),s)));}
            assertThrows(IllegalArgumentException.class,()->p.grant("agent","missing",ReusableApprovals.EXACT,q,s,op));
        }
    }
    @Test void invalidationBoundsAndAuditFailureAreFailClosed()throws Exception{
        var q=request("SHOW CREATE TABLE app.items");var s=scope();var op=ReusableOperation.classify(q.path("sql").asText(),s);
        try(var p=policies()){
            p.grant("agent","one",ReusableApprovals.SIMILAR,q,s,op);p.invalidateConnection(s.path("connectionId").asText());assertTrue(p.list("agent").isEmpty());
            var bound=s.deepCopy().put("bindingId","binding");p.grant("agent","one",ReusableApprovals.SESSION,q,bound,op);p.invalidateBinding("binding");assertTrue(p.list("agent").isEmpty());
            for(int i=0;i<128;i++)p.grant("agent","one",ReusableApprovals.SESSION,q,s,op);
            assertThrows(IllegalArgumentException.class,()->p.grant("agent","one",ReusableApprovals.SESSION,q,s,op));
            Files.createDirectory(directory.resolve("reusable-access.jsonl"));assertThrows(IllegalStateException.class,()->p.auditUse("policy","agent","ddl_inspection"));
        }
    }
    @Test void scopesAreCanonicalAndBindingTargetsCannotBeOverridden(){
        var profile=Profiles.JSON.createObjectNode().put("id","fixed").put("templateId","mysql").put("url","jdbc:mysql://localhost:3306/app");
        var resolved=ApprovalScope.resolve(profile,Profiles.JSON.createObjectNode(),Profiles.JSON.createObjectNode());assertEquals("app",resolved.path("database").asText());assertEquals("app",resolved.path("schema").asText());
        var binding=Profiles.JSON.createObjectNode().put("id","binding").put("database","app").put("schema","app").put("projectId","project").put("environment","prod").put("role","primary");
        assertThrows(IllegalArgumentException.class,()->ApprovalScope.resolve(profile,binding,Profiles.JSON.createObjectNode().put("database","other")));
        assertNotEquals(ApprovalScope.resolve(profile,binding,Profiles.JSON.createObjectNode()),ApprovalScope.resolve(profile,binding.deepCopy().put("role","reporting"),Profiles.JSON.createObjectNode()));
    }
}
