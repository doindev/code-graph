package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import javax.net.ssl.SSLContext;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import static org.junit.jupiter.api.Assertions.*;

/** Real embedded Resolver downloads from an isolated HTTPS Maven repository. */
class EmbeddedMavenTest {
    @TempDir Path root;
    ExternalMavenTest fixture(){var fixture=new ExternalMavenTest();fixture.root=root;return fixture;}
    DriverBundles bundles(ExternalMavenTest.Mirror mirror,Path pem,boolean insecure)throws Exception {
        var repository=new RemoteRepository.Builder("central","default","https://127.0.0.1:"+mirror.server.getAddress().getPort()+"/repo/")
            .setReleasePolicy(new RepositoryPolicy(true,RepositoryPolicy.UPDATE_POLICY_ALWAYS,RepositoryPolicy.CHECKSUM_POLICY_FAIL))
            .setAuthentication(new AuthenticationBuilder().addUsername("mirror-user").addPassword("mirror-password-secret").build()).build();
        return new DriverBundles(root.resolve("embedded-"+UUID.randomUUID()),new DriverDownloadConfig("embedded","",null,pem,true,insecure),List.of(repository));
    }
    @Test @Timeout(60) void certificateAndBypassWorkForMetadataAndDependenciesWithoutChangingGlobalTrust()throws Exception {
        SSLContext originalContext=SSLContext.getDefault();String originalStore=System.getProperty("javax.net.ssl.trustStore");
        var fixture=fixture();
        try(var mirror=fixture.new Mirror(true)){
            var strict=bundles(mirror,null,false);var rejected=strict.status(fixture.input("driver"),()->false);
            assertFalse(rejected.path("latestAvailable").asBoolean(),rejected.toString());assertEquals("certificate",rejected.path("diagnostic").path("code").asText(),rejected.toString());
            String originalPem=Files.readString(mirror.pem);var trusted=bundles(mirror,mirror.pem,false);
            var status=trusted.status(fixture.input("driver"),()->false);assertTrue(status.path("latestAvailable").asBoolean(),status.toString());assertEquals("1.0",status.path("latestVersion").asText());
            assertTrue(mirror.requests.stream().noneMatch(path->path.endsWith(".jar")),"Version lookup downloads metadata only");
            var installed=trusted.install(fixture.input("driver"),()->false,p->{});assertEquals(2,installed.path("jars").size());DriverBundles.verify(installed);
            var bypass=bundles(mirror,null,true);status=bypass.status(fixture.input("driver"),()->false);
            assertTrue(status.path("latestAvailable").asBoolean(),status.toString());assertTrue(status.path("insecureTls").asBoolean());
            assertEquals(2,bypass.install(fixture.input("driver"),()->false,p->{}).path("jars").size());
            // A preference change applies to the next resolution, even on the same downloader.
            bypass.settings.save(Profiles.JSON.createObjectNode().put("mode","embedded").put("insecureTls",false));
            assertFalse(bypass.status(fixture.input("driver"),()->false).path("latestAvailable").asBoolean(),"Re-enabling verification must reject the untrusted endpoint");
            assertFalse(strict.status(fixture.input("driver"),()->false).path("latestAvailable").asBoolean(),"Trust must not leak to another downloader");
            mirror.corrupt=true;
            for(boolean insecure:List.of(false,true)){
                var bad=bundles(mirror,mirror.pem,insecure);
                var failure=assertThrows(DriverDiagnostics.Failure.class,()->bad.install(fixture.input("broken"),()->false,p->{}));
                assertEquals("checksum",failure.diagnostic.path("code").asText(),failure.diagnostic.toString());
            }
            assertEquals(originalPem,Files.readString(mirror.pem));assertSame(originalContext,SSLContext.getDefault());assertEquals(originalStore,System.getProperty("javax.net.ssl.trustStore"));
            try(var paths=Files.walk(root)){assertTrue(paths.noneMatch(path->path.getFileName().toString().equals("ca-trust.p12")),"Embedded trust is in memory only");}
        }
    }
    @Test @Timeout(60) void addedCertificateStillChecksHostnameAndOnlyExplicitBypassSkipsIt()throws Exception {
        var fixture=fixture();try(var mirror=fixture.new Mirror(true,"dns:wrong.example")){
            var trusted=bundles(mirror,mirror.pem,false);var rejected=trusted.status(fixture.input("driver"),()->false);
            assertFalse(rejected.path("latestAvailable").asBoolean(),"Adding the CA must retain hostname verification");
            assertEquals(0,mirror.requests.size(),"Mismatched hostname must fail before an HTTP request");
            Path ignored=Files.writeString(root.resolve("ignored.pem"),"This is deliberately not a certificate");
            var bypass=bundles(mirror,ignored,true);var status=bypass.status(fixture.input("driver"),()->false);
            assertTrue(status.path("latestAvailable").asBoolean(),status.toString());
            assertEquals(2,bypass.install(fixture.input("driver"),()->false,p->{}).path("jars").size());
        }
    }
    @Test @Timeout(60) void embeddedCertificateSettingsPersistAndMissingPemDoesNotFallBack()throws Exception {
        var fixture=fixture();try(var mirror=fixture.new Mirror(true)){
            Path data=root.resolve("preferences");Files.createDirectories(data);
            var settings=new DriverDownloadSettings(data,DriverDownloadConfig.embedded());
            var request=Profiles.JSON.createObjectNode().put("mode","embedded").put("certPem",mirror.pem.toString()).put("insecureTls",false);
            settings.save(request);var restored=new DriverDownloadSettings(data,DriverDownloadConfig.embedded());
            assertEquals(mirror.pem,restored.current().certPem());assertFalse(restored.current().insecureTls());
            settings.save(request.deepCopy().put("insecureTls",true));assertTrue(new DriverDownloadSettings(data,DriverDownloadConfig.embedded()).current().insecureTls());
            var bundles=bundles(mirror,mirror.pem,false);Files.delete(mirror.pem);
            assertFalse(bundles.status(fixture.input("driver"),()->false).path("latestAvailable").asBoolean());assertEquals(0,mirror.requests.size());
            assertThrows(Exception.class,()->settings.save(request));
            assertDoesNotThrow(()->new DriverDownloadSettings(data,DriverDownloadConfig.embedded()),"Missing mounted certificate should not prevent startup");
        }
    }
}
