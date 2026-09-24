package io.doindev.codegraph.dba;

import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import javax.net.ssl.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real installed Maven, synthetic loopback-only repository; no public artifact downloads. */
@EnabledIfSystemProperty(named="dba.maven.integration",matches="true")
class ExternalMavenTest {
    @TempDir Path root;
    String command(){return System.getProperty("dba.maven.command","mvn");}
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");}
    final class Mirror implements AutoCloseable {
        final HttpServer server;final ExecutorService executor=Executors.newCachedThreadPool();
        final Map<String,byte[]> files=new HashMap<>();final List<String> requests=new CopyOnWriteArrayList<>();
        final AtomicBoolean reject=new AtomicBoolean(),block=new AtomicBoolean();
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        final Path pem;boolean corrupt;
        Mirror(boolean tls)throws Exception{this(tls,"dns:localhost,ip:127.0.0.1");}
        Mirror(boolean tls,String certificateNames)throws Exception{
            if(tls){
                Path store=root.resolve("tls.p12");var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
                    "-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN="+certificateNames,
                    "-validity","2","-storetype","PKCS12","-keystore",store.toString(),"-storepass","fixture-password","-keypass","fixture-password","-noprompt")
                    .redirectErrorStream(true).start();
                try{assertTrue(p.waitFor(20,TimeUnit.SECONDS));assertEquals(0,p.exitValue(),new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8));}finally{if(p.isAlive())p.destroyForcibly();}
                var keyStore=KeyStore.getInstance(store.toFile(),"fixture-password".toCharArray());
                pem=root.resolve("corporate cert.pem");Files.writeString(pem,"-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(keyStore.getCertificate("fixture").getEncoded())+"\n-----END CERTIFICATE-----\n");
                var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(keyStore,"fixture-password".toCharArray());
                var context=SSLContext.getInstance("TLS");context.init(keys.getKeyManagers(),null,null);
                var https=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);https.setHttpsConfigurator(new HttpsConfigurator(context));server=https;
            }else{server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pem=null;}
            for(String name:List.of("driver","support","broken")){
                String dir="/repo/test/fixture/"+name+"/";
                files.put(dir+"maven-metadata.xml",bytes("<metadata><groupId>test.fixture</groupId><artifactId>"+name+"</artifactId><versioning><versions><version>1.0</version><version>2.0-rc1</version></versions><lastUpdated>20260921120000</lastUpdated></versioning></metadata>"));
                files.put(dir+"1.0/"+name+"-1.0.pom",bytes("<project><modelVersion>4.0.0</modelVersion><groupId>test.fixture</groupId><artifactId>"+name+"</artifactId><version>1.0</version>"
                    +(name.equals("driver")?"<dependencies><dependency><groupId>test.fixture</groupId><artifactId>support</artifactId><version>1.0</version><scope>runtime</scope></dependency></dependencies>":"")+"</project>"));
                var output=new ByteArrayOutputStream();try(var jar=new JarOutputStream(output)){jar.putNextEntry(new JarEntry("fixture.txt"));jar.write(bytes(name));jar.closeEntry();}
                files.put(dir+"1.0/"+name+"-1.0.jar",output.toByteArray());
            }
            server.createContext("/",x->{
                String path=x.getRequestURI().getPath();requests.add(path);
                String expected="Basic "+Base64.getEncoder().encodeToString(bytes("mirror-user:mirror-password-secret"));
                if(reject.get()||!expected.equals(x.getRequestHeaders().getFirst("Authorization"))){x.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"fixture\"");x.sendResponseHeaders(401,-1);x.close();return;}
                if(block.get()){entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                byte[] value=files.get(path);
                for(String algorithm:List.of("sha1","md5"))if(path.endsWith("."+algorithm)){
                    byte[] original=files.get(path.substring(0,path.length()-algorithm.length()-1));
                    if(original!=null)try{value=bytes(corrupt&&path.contains("/broken/")?"0".repeat(40):HexFormat.of().formatHex(MessageDigest.getInstance(algorithm.equals("sha1")?"SHA-1":"MD5").digest(original)));}catch(Exception e){throw new IOException(e);}
                }
                try{if(value==null)x.sendResponseHeaders(404,-1);else{x.sendResponseHeaders(200,value.length);x.getResponseBody().write(value);}}finally{x.close();}
            });server.setExecutor(executor);server.start();
        }
        Path settings()throws Exception{
            Path path=root.resolve("settings "+UUID.randomUUID()+".xml"),repo=root.resolve("cache-"+UUID.randomUUID());
            Files.writeString(path,"<settings><localRepository>"+xml(repo.toString())+"</localRepository><mirrors><mirror><id>corporate</id><mirrorOf>*</mirrorOf><url>"
                +(pem==null?"http":"https")+"://127.0.0.1:"+server.getAddress().getPort()+"/repo/</url></mirror></mirrors><servers><server><id>corporate</id><username>mirror-user</username><password>mirror-password-secret</password></server></servers></settings>");
            return path;
        }
        public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
    }
    com.fasterxml.jackson.databind.node.ObjectNode input(String artifact){return Profiles.JSON.createObjectNode().put("templateId","custom").put("groupId","test.fixture").put("artifactId",artifact).put("version","1.0");}
    DriverBundles bundles(Path settings,Path pem,boolean insecure)throws Exception{
        return new DriverBundles(root.resolve("app-"+UUID.randomUUID()),new DriverDownloadConfig("maven",command(),settings,pem,true,insecure));
    }
    @Test @Timeout(120) void mirrorAuthenticationMetadataOnlyDependencyBundleCacheAndDiagnostics()throws Exception{
        try(var mirror=new Mirror(false)){
            Path settings=mirror.settings();String original=Files.readString(settings);var bundles=bundles(settings,null,false);
            var status=bundles.status(input("driver"),()->false);assertTrue(status.path("latestAvailable").asBoolean(),status.toPrettyString());assertEquals("1.0",status.path("latestVersion").asText());
            assertTrue(mirror.requests.stream().noneMatch(p->p.endsWith(".jar")),"Version checks must not download drivers/plugins");
            var installed=bundles.install(input("driver"),()->false,p->{});assertEquals(2,installed.path("jars").size());DriverBundles.verify(installed);
            int count=mirror.requests.size();assertEquals(installed,bundles.install(input("driver"),()->false,p->{fail("Verified cache must not launch Maven");}));assertEquals(count,mirror.requests.size());
            assertTrue(installed.path("dependencies").get(0).asText().contains("driver"));assertEquals(original,Files.readString(settings));
            assertFalse(installed.toString().contains("mirror-password-secret"));
            Files.writeString(settings,original.replace("<settings>","<settings><offline>true</offline>"));
            var offline=bundles.status(input("driver"),()->false);assertFalse(offline.path("latestAvailable").asBoolean(),offline.toString());
            assertTrue(offline.path("diagnostic").path("details").asText().contains("offline"));assertEquals(count,mirror.requests.size());
            assertEquals(installed,bundles.install(input("driver"),()->false,p->{fail("Offline verified cache must be reusable");}));
            Files.writeString(settings,original);
            mirror.reject.set(true);
            assertFalse(bundles.status(input("driver"),()->false).path("latestAvailable").asBoolean(),"Cached metadata cannot hide a new remote failure");
            var denied=bundles(mirror.settings(),null,false).status(input("driver"),()->false);
            assertFalse(denied.path("latestAvailable").asBoolean(),denied.toString());assertEquals("authentication",denied.path("diagnostic").path("code").asText(),denied.toString());
            assertFalse(denied.toString().contains("mirror-password-secret"));assertFalse(denied.toString().contains("mirror-user"));
            mirror.reject.set(false);mirror.corrupt=true;
            var failure=assertThrows(DriverDiagnostics.Failure.class,()->bundles(mirror.settings(),null,false).install(input("broken"),()->false,p->{}));
            assertEquals("checksum",failure.diagnostic.path("code").asText(),failure.diagnostic.toString());
        }
    }
    @Test @Timeout(120) void selfSignedTlsRequiresExplicitPemOrInsecureOptIn()throws Exception{
        String before=System.getProperty("javax.net.ssl.trustStore");
        try(var mirror=new Mirror(true)){
            var strict=bundles(mirror.settings(),null,false).status(input("driver"),()->false);
            assertFalse(strict.path("latestAvailable").asBoolean());assertEquals("certificate",strict.path("diagnostic").path("code").asText(),strict.toString());
            String original=Files.readString(mirror.pem);var trusted=bundles(mirror.settings(),mirror.pem,false);
            assertTrue(trusted.status(input("driver"),()->false).path("latestAvailable").asBoolean());
            assertEquals(2,trusted.install(input("driver"),()->false,p->{}).path("jars").size());
            var insecure=bundles(mirror.settings(),null,true);var status=insecure.status(input("driver"),()->false);
            assertTrue(status.path("latestAvailable").asBoolean(),status.toString());assertTrue(status.path("insecureTls").asBoolean());
            assertEquals(2,insecure.install(input("driver"),()->false,p->{}).path("jars").size());
            assertEquals(original,Files.readString(mirror.pem));assertEquals(before,System.getProperty("javax.net.ssl.trustStore"));
            try(var files=Files.walk(root)){assertTrue(files.noneMatch(p->p.getFileName().toString().equals("ca-trust.p12")),"temporary trust stores removed");}
        }
    }
    @Test @Timeout(60) void cancellationKillsMavenAndCleansOnlyOwnedWork()throws Exception{
        try(var mirror=new Mirror(false);var workers=Executors.newSingleThreadExecutor()){
            mirror.block.set(true);var bundles=bundles(mirror.settings(),null,false);var cancelled=new AtomicBoolean();
            var task=workers.submit(()->assertThrows(CancellationException.class,()->bundles.status(input("driver"),cancelled::get)));
            assertTrue(mirror.entered.await(20,TimeUnit.SECONDS));cancelled.set(true);task.get(15,TimeUnit.SECONDS);
            try(var files=Files.list(bundles.root())){assertTrue(files.noneMatch(p->p.getFileName().toString().startsWith(".maven-")));}
        }
    }
}
