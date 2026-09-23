package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalSettingsTest {
    @TempDir Path directory;
    @Test void persistedTimeoutHasSafeDefaultsValidationAndCorruptFileRecovery()throws Exception {
        var settings=new ApprovalSettings(directory);assertEquals(300,settings.timeoutSeconds());
        settings.save(Profiles.JSON.readTree("600"));assertEquals(600,new ApprovalSettings(directory).timeoutSeconds());
        for(String invalid:new String[]{"9","3601","null","1.5","\"60\"","true","99999999999999"})
            assertThrows(IllegalArgumentException.class,()->settings.save(Profiles.JSON.readTree(invalid)));
        assertEquals(600,new ApprovalSettings(directory).timeoutSeconds());
        Files.writeString(directory.resolve("approval-settings.json"),"invalid");var fallback=new ApprovalSettings(directory);
        assertEquals(300,fallback.timeoutSeconds());assertFalse(fallback.warning().isEmpty());
        assertEquals("invalid",Files.readString(directory.resolve("approval-settings.json")));
    }
    @Test void browserSettingRequiresCsrfAndSurvivesRuntimeRestart()throws Exception {
        for(int pass=0;pass<2;pass++){
            var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,100,100,15),new DbaTest.MemoryVault());var client=HttpClient.newHttpClient()){
                server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort(),path="/api/dba/settings";
                var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
                var settings=Profiles.JSON.readTree(login.body());assertEquals(pass==0?300:45,settings.path("approvalTimeoutSeconds").asInt());
                assertEquals(60,settings.path("decisionTimeoutSeconds").asInt());
                String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=settings.path("csrf").asText();
                var change=Profiles.JSON.createObjectNode().put("approvalTimeoutSeconds",45);
                assertEquals(403,DbaTest.request(client,base,path,"PUT",change,cookie,null).statusCode());
                assertEquals(200,DbaTest.request(client,base,path,"PUT",change,cookie,csrf).statusCode());
                assertEquals(400,DbaTest.request(client,base,path,"PUT",change.deepCopy().put("concurrency",0).put("approvalTimeoutSeconds",60),cookie,csrf).statusCode());
                assertEquals(45,new ApprovalSettings(directory).timeoutSeconds());
            }finally{server.stop(0);}
        }
    }
}
