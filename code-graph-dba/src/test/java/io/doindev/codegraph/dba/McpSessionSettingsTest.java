package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class McpSessionSettingsTest {
    @TempDir Path directory;
    @Test void defaultsBoundsPersistenceAndCorruptFileRecovery()throws Exception {
        var settings=new McpSessionSettings(directory);
        assertEquals(60,settings.idleTimeoutMinutes());assertEquals(3_600_000L,settings.idleTimeoutMillis());
        for(int minutes:new int[]{1,120,10080}){
            settings.save(Profiles.JSON.valueToTree(minutes));
            assertEquals(minutes*60_000L,new McpSessionSettings(directory).idleTimeoutMillis());
        }
        for(String invalid:new String[]{"0","-1","10081","null","1.5","\"60\"","true","9999999999999999999"})
            assertThrows(IllegalArgumentException.class,()->settings.save(Profiles.JSON.readTree(invalid)));
        assertEquals(10080,new McpSessionSettings(directory).idleTimeoutMinutes());
        Files.writeString(directory.resolve("mcp-session-settings.json"),"invalid");
        var fallback=new McpSessionSettings(directory);assertEquals(60,fallback.idleTimeoutMinutes());assertFalse(fallback.warning().isEmpty());
        assertEquals("invalid",Files.readString(directory.resolve("mcp-session-settings.json")));
        fallback.save(Profiles.JSON.valueToTree(30));assertTrue(fallback.warning().isEmpty());assertEquals(30,new McpSessionSettings(directory).idleTimeoutMinutes());
    }
    @Test void endpointPersistsAcrossRestartAndValidatesBeforeSavingEitherTimeout()throws Exception {
        for(int pass=0;pass<2;pass++){
            var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,100,100,15),new DbaTest.MemoryVault());var client=HttpClient.newHttpClient()){
                server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort(),path="/api/dba/settings";
                assertEquals(403,DbaTest.request(client,base,path,"PUT",Profiles.JSON.createObjectNode().put("mcpSessionIdleTimeoutMinutes",2),null,null).statusCode());
                var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
                var settings=Profiles.JSON.readTree(login.body());assertEquals(pass==0?60:120,settings.path("mcpSessionIdleTimeoutMinutes").asInt());
                assertEquals(300,settings.path("approvalTimeoutSeconds").asInt());assertEquals(60,settings.path("decisionTimeoutSeconds").asInt());
                assertEquals((pass==0?60:120)*60_000L,runtime.mcpSessionIdleTimeoutMillis());
                String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=settings.path("csrf").asText();
                var change=Profiles.JSON.createObjectNode().put("mcpSessionIdleTimeoutMinutes",120);
                assertEquals(403,DbaTest.request(client,base,path,"PUT",change,cookie,null).statusCode());
                assertEquals(200,DbaTest.request(client,base,path,"PUT",change,cookie,csrf).statusCode());assertEquals(7_200_000L,runtime.mcpSessionIdleTimeoutMillis());
                for(var invalid:new com.fasterxml.jackson.databind.node.ObjectNode[]{
                    change.deepCopy().put("concurrency",0),change.deepCopy().put("approvalTimeoutSeconds",9),
                    change.deepCopy().put("mcpSessionIdleTimeoutMinutes",0).put("approvalTimeoutSeconds",45),change.deepCopy().put("mcpSessionIdleTimeoutMinutes","120")})
                    assertEquals(400,DbaTest.request(client,base,path,"PUT",invalid,cookie,csrf).statusCode());
                assertEquals(120,new McpSessionSettings(directory).idleTimeoutMinutes());assertEquals(300,new ApprovalSettings(directory).timeoutSeconds());
                assertEquals(200,DbaTest.request(client,base,path,"PUT",Profiles.JSON.createObjectNode().put("uiRows",77),cookie,csrf).statusCode());
                var saved=Profiles.JSON.readTree(DbaTest.request(client,base,path,"GET",null,cookie,csrf).body());
                assertEquals(120,saved.path("mcpSessionIdleTimeoutMinutes").asInt());assertEquals(77,saved.path("uiRows").asInt());
            }finally{server.stop(0);}
        }
    }
}
