package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DriverDownloadTest {
    @TempDir Path root;
    @Test void configDefaultsOverridesAndInvalidSettings()throws Exception{
        var defaults=DbaConfig.parse(new String[]{"--dba"}).orElseThrow().driverDownloads();
        assertEquals("embedded",defaults.mode());assertFalse(defaults.insecureTls());assertNull(defaults.settings());
        assertTrue(DbaConfig.parse(new String[]{"--dba","--dba-maven-insecure-tls","true"}).orElseThrow().driverDownloads().insecureTls());
        Path embeddedPem=Files.writeString(root.resolve("public.pem"),"validated when used");
        assertEquals(embeddedPem,DbaConfig.parse(new String[]{"--dba","--dba-maven-cert",embeddedPem.toString()}).orElseThrow().driverDownloads().certPem());
        var maven=DbaConfig.parse(new String[]{"--dba","--dba-driver-download","maven"}).orElseThrow().driverDownloads();
        assertNull(maven.settings(),"leave default resolution to installed Maven");
        Path settings=root.resolve("settings.xml");Files.writeString(settings,"<settings/>");
        var explicit=DbaConfig.parse(new String[]{"--dba","--dba-driver-download","maven","--dba-maven-settings",settings.toString(),"--dba-maven-insecure-tls","true"}).orElseThrow().driverDownloads();
        assertEquals(settings,explicit.settings());assertTrue(explicit.insecureTls());
        assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-maven-settings",settings.toString()}));
        assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-driver-download","maven","--dba-maven-settings",root.resolve("missing").toString()}));
        assertThrows(IllegalArgumentException.class,()->DbaConfig.parse(new String[]{"--dba","--dba-driver-download","maven","--dba-maven-insecure-tls","yes"}));
    }
    @Test void settingsPersistWithoutSecretsAndPreserveOtherApplicationSections()throws Exception{
        Files.writeString(root.resolve("settings.json"),"{\"other\":{\"keep\":true}}");
        Path settings=root.resolve("settings.xml");Files.writeString(settings,"<settings><servers><server><password>DO-NOT-COPY</password></server></servers></settings>");
        var preferences=new DriverDownloadSettings(root,DriverDownloadConfig.embedded());
        var input=Profiles.JSON.createObjectNode().put("mode","maven").put("settings",settings.toString()).put("insecureTls",true);
        preferences.save(input);
        String saved=Files.readString(root.resolve("settings.json"));assertFalse(saved.contains("DO-NOT-COPY"));
        assertTrue(Profiles.JSON.readTree(saved).path("other").path("keep").asBoolean());
        var restored=new DriverDownloadSettings(root,DriverDownloadConfig.embedded());assertEquals(settings,restored.current().settings());assertTrue(restored.current().insecureTls());
        assertEquals("embedded",new DriverDownloadSettings(root,new DriverDownloadConfig("embedded","",null,null,true)).current().mode());
        Files.delete(settings);assertDoesNotThrow(()->new DriverDownloadSettings(root,DriverDownloadConfig.embedded()),"Unavailable mounted settings do not block app startup");
        assertThrows(IllegalArgumentException.class,()->restored.save(input),"Apply validates paths");
        assertThrows(IllegalArgumentException.class,()->preferences.save(input.deepCopy().put("password","no")));
        assertThrows(IllegalArgumentException.class,()->preferences.save(Profiles.JSON.createArrayNode()));
        assertThrows(IllegalArgumentException.class,()->preferences.save(input.deepCopy().put("mode",1)));
        preferences.save(Profiles.JSON.createObjectNode().put("mode","maven"));assertNull(preferences.current().settings());assertFalse(preferences.current().insecureTls());
    }
    @Test void diagnosticClassificationAndRedactionAreBounded()throws Exception{
        Path settings=root.resolve("settings.xml");Files.writeString(settings,"<settings><servers><server><password>really-secret-value</password></server></servers></settings>");
        var secrets=DriverDiagnostics.settingsSecrets(List.of(settings));
        var diagnostic=DriverDiagnostics.describe("install","maven",new java.io.IOException("PKIX path building failed"),
            "https://user:secret@mirror.example/a?token=abc password=hidden Authorization: Bearer hidden\nreally-secret-value",secrets,1);
        assertEquals("certificate",diagnostic.path("code").asText());assertEquals(1,diagnostic.path("exitCode").asInt());
        String output=diagnostic.toString();for(String secret:List.of("user:secret","token=abc","password=hidden","Bearer hidden","really-secret-value"))assertFalse(output.contains(secret),output);
        assertTrue(output.contains("mirror.example"));assertTrue(output.contains("PKIX"));
        assertEquals("authentication",DriverDiagnostics.describe("status","maven",new Exception("407 Proxy Authentication"),"",List.of(),1).path("code").asText());
        assertEquals("download_failed",DriverDiagnostics.describe("status","embedded",new Exception("Metadata not found in cache-140123"),"",List.of(),null).path("code").asText());
        assertTrue(DriverDiagnostics.redact("x".repeat(90000),List.of()).length()<16100);
        assertEquals(diagnostic,QueryJobs.exceptionInfo(new DriverDiagnostics.Failure(diagnostic)));
        assertFalse(QueryJobs.exceptionInfo(new java.sql.SQLException("password=hidden")).toString().contains("hidden"));
    }
    @Test void invalidPrivatePemAndOversizeInputsRejected()throws Exception{
        Path pem=root.resolve("certificate.pem");Files.writeString(pem,"-----BEGIN PRIVATE KEY-----\nnot-a-cert");
        assertThrows(IllegalArgumentException.class,()->ExternalMaven.certificates(pem));
        Files.writeString(pem,"x".repeat((1<<20)+1));assertThrows(IllegalArgumentException.class,()->ExternalMaven.certificates(pem));
    }
    @Test void fileSelectionHas90SecondDeadlineIndependentOfQueries()throws Exception{
        assertEquals(90,QueryJobs.FILE_SELECTION_TIMEOUT_SECONDS);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,100,100,1),o->true)){
            var browse=jobs.fileSelection("browser",j->{Thread.sleep(1400);return Profiles.JSON.createObjectNode().put("selected",true);},()->{});
            var query=jobs.local("browser",j->{Thread.sleep(1400);return Profiles.JSON.createObjectNode();},()->{});
            assertEquals("complete",ConnectionSetupTest.await(jobs,"browser",browse).path("state").asText());
            assertEquals("cancelled",ConnectionSetupTest.await(jobs,"browser",query).path("state").asText());
        }
    }
    @Test void mavenPickerRecognizesLaunchersAndRejectsOtherFilesWithoutRunningThem()throws Exception{
        for(String name:List.of("mvn","mvn.cmd","mvn.bat","mvn.exe","MVN.CMD")){
            assertTrue(ConnectionSetup.mavenLauncherName(name));
            Path file=Files.writeString(root.resolve(name),"not executed by file selection");
            assertDoesNotThrow(()->ConnectionSetup.validateMavenSelection(file));
        }
        for(String name:List.of("settings.xml","maven.jar","mvn.cmd.txt","mvn --version")){
            assertFalse(ConnectionSetup.mavenLauncherName(name));
            Path file=Files.writeString(root.resolve(name),"");
            assertThrows(IllegalArgumentException.class,()->ConnectionSetup.validateMavenSelection(file));
        }
        assertThrows(IllegalArgumentException.class,()->ConnectionSetup.validateMavenSelection(root));
        assertThrows(IllegalArgumentException.class,()->ConnectionSetup.validateMavenSelection(root.resolve("missing/mvn")));
    }
    @Test void headlessMavenPickerOffersManualPathWithoutSavingOrExecuting()throws Exception{
        Assumptions.assumeTrue(java.awt.GraphicsEnvironment.isHeadless(),"Headless fallback requires a headless test JVM");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);
            var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,100,100,10),o->true)){
            var setup=new ConnectionSetup(profiles,jobs);
            var state=ConnectionSetupTest.await(jobs,"browser",setup.operation("browser","file-select",Profiles.JSON.createObjectNode().put("kind","maven-executable")));
            assertEquals("complete",state.path("state").asText(),state.toString());
            assertFalse(state.path("result").path("available").asBoolean(true));
            assertTrue(state.path("result").path("message").asText().contains("manually"));
            assertFalse(Files.exists(root.resolve("settings.json")));
            assertEquals(0,connections.count());
        }
    }
    @Test void settingsApiRequiresSessionAndCsrfAndPreservesOtherRuntimeSettings()throws Exception{
        var config=new DbaConfig(root.resolve("runtime"),64L<<20,2,100,100,10);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0),0);
        try(var runtime=new DbaRuntime(config,new DbaTest.MemoryVault());var client=java.net.http.HttpClient.newHttpClient()){
            server.createContext("/",runtime::handle);server.start();String base="http://localhost:"+server.getAddress().getPort();
            assertEquals(200,DbaTest.request(client,base,"/dba/driver-download-settings.js","GET",null,null,null).statusCode());
            assertEquals(403,DbaTest.request(client,base,"/api/dba/settings/driver-downloads","GET",null,null,null).statusCode());
            var login=DbaTest.request(client,base,"/api/dba/bootstrap","POST",Profiles.JSON.createObjectNode(),null,null);
            String cookie=login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],csrf=Profiles.JSON.readTree(login.body()).path("csrf").asText();
            var input=Profiles.JSON.createObjectNode().put("mode","maven").put("insecureTls",true);
            assertEquals(403,DbaTest.request(client,base,"/api/dba/settings/driver-downloads","PUT",input,cookie,null).statusCode());
            assertEquals(200,DbaTest.request(client,base,"/api/dba/settings/driver-downloads","PUT",input,cookie,csrf).statusCode());
            var resource=Profiles.JSON.createObjectNode().put("timeoutSeconds",45);
            assertEquals(200,DbaTest.request(client,base,"/api/dba/settings","PUT",resource,cookie,csrf).statusCode());
            var saved=Profiles.JSON.readTree(DbaTest.request(client,base,"/api/dba/settings/driver-downloads","GET",null,cookie,null).body());
            assertEquals("maven",saved.path("mode").asText());assertTrue(saved.path("insecureTls").asBoolean());
            assertTrue(Files.isRegularFile(config.directory().resolve("settings.json")));
        }finally{server.stop(0);}
    }
    @Test void unavailableExecutableProducesVisibleStatusAndInstallFailureWithoutFallback()throws Exception{
        var config=new DriverDownloadConfig("maven",root.resolve("missing-mvn.cmd").toString(),null,null,true);
        var bundles=new DriverBundles(root,config);
        var input=Profiles.JSON.createObjectNode().put("templateId","custom").put("groupId","example").put("artifactId","driver").put("version","1");
        var status=bundles.status(input,()->false);
        assertFalse(status.path("latestAvailable").asBoolean());assertEquals("maven_setup",status.path("diagnostic").path("code").asText());
        var failure=assertThrows(DriverDiagnostics.Failure.class,()->bundles.install(input,()->false,p->{}));
        assertEquals("maven_setup",failure.diagnostic.path("code").asText());
        try(var paths=Files.list(root.resolve("drivers"))){assertTrue(paths.noneMatch(p->p.getFileName().toString().startsWith(".maven-")));}
    }
}
