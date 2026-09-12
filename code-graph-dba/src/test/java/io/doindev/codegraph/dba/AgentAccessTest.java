package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentAccessTest {
    @TempDir Path directory;
    static ObjectNode grant(String connection){var n=Profiles.JSON.createObjectNode().put("name","test agent");n.putArray("grants").addObject().put("connectionId",connection).putArray("objects").addObject().put("schema","public").put("name","items");return n;}
    @Test void tokensAreHashedGrantsPersistAndRevocationDenies()throws Exception{
        var vault=new DbaTest.MemoryVault();String token,id,connection;
        try(var p=new Profiles(directory,vault)){
            connection=p.put(null,new DbaTest().input()).path("id").asText();var agents=new AgentAccess(directory);
            var created=agents.create(grant(connection),p);token=created.path("token").asText();id=created.path("id").asText();
            assertEquals(id,agents.authenticate(token));assertFalse(agents.list().toString().contains("token"));assertFalse(Files.readString(directory.resolve("agents.json")).contains(token));
            assertThrows(SecurityException.class,()->agents.authenticate("x".repeat(43)));assertThrows(SecurityException.class,()->agents.requireObject(id,connection,"public","secret"));
            assertDoesNotThrow(()->agents.requireObject(id,connection,"public","items"));
        }
        try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,1000,1000,5),vault,false)){
            assertFalse(Files.exists(directory.resolve("browser-token")));assertEquals(id,runtime.authenticateAgent(token));
            assertEquals(1,runtime.agentCall(id,"dba_list_connections",Profiles.JSON.createObjectNode()).size());
            assertThrows(SecurityException.class,()->runtime.agentCall(null,"dba_list_connections",Profiles.JSON.createObjectNode()));
            var args=Profiles.JSON.createObjectNode().put("connectionId",connection).put("connectionName","test").put("sql","DELETE FROM public.items");
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(id,"dba_execute_read_query",args));
            new AgentAccess(directory).remove(id); // A runtime owns its snapshot; persistence is validated on the next open below.
        }
        var reloaded=new AgentAccess(directory);assertThrows(SecurityException.class,()->reloaded.authenticate(token));
    }
    @Test void allReferencedObjectsAreAuthorizedAndUnsupportedSyntaxFailsClosed(){
        var allowed=Set.of("public.items","public.other","Case.Mixed");
        java.util.function.BiConsumer<String,String> check=(s,n)->{if(!allowed.contains(s+"."+n))throw new IllegalArgumentException("denied");};
        for(String sql:List.of("SELECT * FROM public.items", "SELECT a.id FROM public.items a JOIN public.other b ON a.id=b.id", "SELECT * FROM \"Case\".\"Mixed\"", "SELECT * FROM public.items WHERE id IN (SELECT id FROM public.other)"))assertDoesNotThrow(()->SqlReadGuard.validate(sql,check),sql);
        for(String sql:List.of("SELECT * FROM items", "SELECT * FROM public.secret", "SELECT * FROM public.items WHERE id IN (SELECT id FROM public.secret)", "SELECT * FROM public.items JOIN public.secret ON true", "EXPLAIN ANALYZE SELECT * FROM public.items", "ANALYZE public.items", "SELECT * FROM public.items; DROP TABLE public.items", "WITH x AS (SELECT * FROM public.secret) SELECT * FROM x", "SELECT pg_sleep(1)", "SELECT * FROM public.items ORDER BY dangerous()"))assertThrows(IllegalArgumentException.class,()->SqlReadGuard.validate(sql,check),sql);
    }
    @Test void namesAndStableIdsBothConstrainGrantsAndRenamesFailClosed()throws Exception{
        var vault=new DbaTest.MemoryVault();String token,id,other;
        try(var profiles=new Profiles(directory,vault)){
            id=profiles.put(null,new DbaTest().input().put("name","Production")).path("id").asText();other=profiles.put(null,new DbaTest().input().put("name","Development")).path("id").asText();token=new AgentAccess(directory).create(grant(id),profiles).path("token").asText();
            assertThrows(IllegalArgumentException.class,()->profiles.put(null,new DbaTest().input().put("name"," production ")));
        }
        try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,100,100,5),vault,false)){
            String principal=runtime.authenticateAgent(token);var byName=Profiles.JSON.createObjectNode().put("connectionName","Production");assertEquals(1,runtime.agentCall(principal,"dba_get_metadata",byName).path("objects").size());
            assertThrows(IllegalArgumentException.class,()->runtime.agentCall(principal,"dba_get_metadata",Profiles.JSON.createObjectNode().put("connectionId",id)));
            assertThrows(SecurityException.class,()->runtime.agentCall(principal,"dba_get_metadata",byName.deepCopy().put("connectionId",other)));
            assertThrows(SecurityException.class,()->runtime.agentCall(principal,"dba_get_metadata",byName.deepCopy().put("connectionName","Development")));
        }
        try(var profiles=new Profiles(directory,vault)){profiles.put(id,new DbaTest().input().put("name","Renamed production"));profiles.put(null,new DbaTest().input().put("name","Production"));}
        try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,100,100,5),vault,false)){
            String principal=runtime.authenticateAgent(token);assertTrue(runtime.agentCall(principal,"dba_list_connections",Profiles.JSON.createObjectNode()).isEmpty());
            for(String name:List.of("Production","Renamed production"))assertThrows(SecurityException.class,()->runtime.agentCall(principal,"dba_get_metadata",Profiles.JSON.createObjectNode().put("connectionName",name)));
        }
    }
    @Test void agentByteAndCellLimitsAreIndependentOfRowLimit()throws Exception {
        try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:bytes_"+UUID.randomUUID());var s=c.createStatement();var rs=s.executeQuery("SELECT REPEAT('x',8192) A,REPEAT('y',8192) B FROM SYSTEM_RANGE(1,1000)")){
            var result=QueryJobs.rows(rs,100,1<<20);assertTrue(result.path("truncated").asBoolean());assertTrue(result.path("rowCount").asInt()<100);assertTrue(Profiles.JSON.writeValueAsBytes(result).length<1<<20);
        }
    }
}
