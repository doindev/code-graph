package io.doindev.codegraph.dba;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class CompareSettingsTest {
    @TempDir Path directory;
    @Test void persistedLimitHasDefaultsValidationAndCorruptFileRecovery()throws Exception {
        var settings=new CompareSettings(directory);assertEquals(16L<<20,settings.bytes());assertNull(settings.metadataMiB());
        settings.save(Profiles.JSON.readTree("32"));assertEquals(32L<<20,new CompareSettings(directory).bytes());
        for(String invalid:new String[]{"0","257","1.5","\"32\"","true","99999999999999"})
            assertThrows(IllegalArgumentException.class,()->settings.save(Profiles.JSON.readTree(invalid)));
        assertEquals(32L<<20,new CompareSettings(directory).bytes());
        settings.save(Profiles.JSON.nullNode());assertNull(new CompareSettings(directory).metadataMiB());assertEquals(16L<<20,new CompareSettings(directory).bytes());
        Files.writeString(directory.resolve("compare-settings.json"),"invalid");var fallback=new CompareSettings(directory);
        assertEquals(16L<<20,fallback.bytes());assertFalse(fallback.warning().isEmpty());
        assertEquals("invalid",Files.readString(directory.resolve("compare-settings.json")));
    }
    @Test void browserSettingRequiresCsrfAndSurvivesRuntimeRestart()throws Exception {
        for(int pass=0;pass<2;pass++){
            var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
            try(var runtime=new DbaRuntime(new DbaConfig(directory,64L<<20,2,100,100,15),new DbaTest.MemoryVault());var client=HttpClient.newHttpClient()){
                server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort(),path="/api/dba/settings";
                var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
                var settings=Profiles.JSON.readTree(login.body());assertEquals(pass==0?16:32,settings.path("effectiveCompareMetadataMiB").asInt());
                assertEquals(60,settings.path("decisionTimeoutSeconds").asInt());
                String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=settings.path("csrf").asText();
                var change=Profiles.JSON.createObjectNode().put("compareMetadataMiB",32);
                assertEquals(403,DbaTest.request(client,base,path,"PUT",change,cookie,null).statusCode());
                assertEquals(200,DbaTest.request(client,base,path,"PUT",change,cookie,csrf).statusCode());
                assertEquals(400,DbaTest.request(client,base,path,"PUT",change.deepCopy().put("concurrency",0).put("compareMetadataMiB",64),cookie,csrf).statusCode());
                assertEquals(32L<<20,new CompareSettings(directory).bytes());
            }finally{server.stop(0);}
        }
    }
}
