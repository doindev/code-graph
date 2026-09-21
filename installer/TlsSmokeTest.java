import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real clients, expired/self-signed/wrong-host certificate, loopback only, no artifact downloads. */
public class TlsSmokeTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static int run(List<String> command,Path work,Path log)throws Exception {
        boolean cmd=CgraphInstaller.WINDOWS&&command.getFirst().endsWith(".cmd");
        var builder=new ProcessBuilder(cmd?CgraphInstaller.powershell("$a=@($env:CGRAPH_TOOL_ARGS|ConvertFrom-Json); & $env:CGRAPH_TOOL @a; exit $LASTEXITCODE"):command);
        var env=builder.environment();
        for(String key:List.copyOf(env.keySet()))if(key.toLowerCase(Locale.ROOT).endsWith("_proxy")||key.startsWith("GIT_CONFIG_")||Set.of("GIT_SSL_NO_VERIFY","GIT_ASKPASS","SSH_ASKPASS","MAVEN_ARGS","MAVEN_OPTS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS").contains(key))env.remove(key);
        env.put("JAVA_HOME",System.getProperty("java.home"));env.put("GIT_TERMINAL_PROMPT","0");
        if(cmd){env.put("CGRAPH_TOOL",command.getFirst());env.put("CGRAPH_TOOL_ARGS","["+String.join(",",command.subList(1,command.size()).stream().map(CgraphInstaller::json).toList())+"]");}
        var child=builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {check(child.waitFor(50,TimeUnit.SECONDS),"Client timed out: "+log);return child.exitValue();}
        finally {if(child.isAlive()){child.descendants().forEach(ProcessHandle::destroyForcibly);child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: TlsSmokeTest PATH_TO_GIT PATH_TO_MAVEN");
        Path work=Files.createTempDirectory("cgraph-build-");String owner=UUID.randomUUID().toString();Files.writeString(work.resolve(".cgraph-build-owner"),owner);
        HttpsServer server=null;var workers=Executors.newVirtualThreadPerTaskExecutor();
        try {
            Path keys=work.resolve("fixture.p12");char[] password="fixture-only".toCharArray();
            check(run(List.of(Path.of(System.getProperty("java.home"),"bin",CgraphInstaller.WINDOWS?"keytool.exe":"keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-keystore",keys.toString(),"-storetype","PKCS12","-storepass",new String(password),"-dname","CN=wrong-host.invalid","-startdate","2020/01/01 00:00:00","-validity","1","-noprompt"),work,work.resolve("keytool.log"))==0,"Certificate fixture generation");
            var store=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(keys)){store.load(input,password);}
            var managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());managers.init(store,password);
            var context=SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,null);
            server=HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);server.setHttpsConfigurator(new HttpsConfigurator(context));server.setExecutor(workers);
            AtomicInteger requests=new AtomicInteger();
            server.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(404,-1);exchange.close();});server.start();
            String url="https://127.0.0.1:"+server.getAddress().getPort();
            List<String> git=new ArrayList<>(List.of(args[0],"-c","http.proxy=","-c","http.sslVerify=true","-c","credential.helper=","-c","core.askPass="));
            git.addAll(List.of("ls-remote",url+"/fixture.git"));
            check(run(git,work,work.resolve("git-verified.log"))!=0&&requests.get()==0,"Git must reject untrusted/expired/wrong-host certificate by default");
            git.set(git.indexOf("http.sslVerify=true"),"http.sslVerify=false");
            check(run(git,work,work.resolve("git-install.log"))!=0&&requests.get()>0,"Git installer TLS bypass reaches fixture HTTP error");
            check(Files.readString(work.resolve("git-install.log")).contains("not found"),"Git server diagnostics remain visible");
            Files.writeString(work.resolve("global.xml"),"<settings/>");
            Files.writeString(work.resolve("settings.xml"),"<settings><mirrors><mirror><id>fixture</id><mirrorOf>*</mirrorOf><url>"+url+"/maven</url></mirror></mirrors></settings>");
            List<String> maven=new ArrayList<>(List.of(args[1],"-B","-ntp","-s",work.resolve("settings.xml").toString(),"-gs",work.resolve("global.xml").toString(),"-Dmaven.repo.local="+work.resolve("repository"),"-Dmaven.resolver.transport=wagon","-Dmaven.wagon.http.retryHandler.count=0"));
            maven.add("org.cgraph.fixture:nonexistent-plugin:1:probe");int before=requests.get();
            check(run(maven,work,work.resolve("maven-verified.log"))!=0&&requests.get()==before,"Maven must reject fixture certificate by default");
            maven.addAll(1,CgraphInstaller.installationTlsArguments());
            check(run(maven,work,work.resolve("maven-install.log"))!=0&&requests.get()>before,"Maven installer TLS bypass reaches fixture HTTP error");
            check(Files.readString(work.resolve("maven-install.log")).contains("[ERROR]"),"Maven failure diagnostics remain visible");
            Path validKeys=work.resolve("valid.p12");
            check(run(List.of(Path.of(System.getProperty("java.home"),"bin",CgraphInstaller.WINDOWS?"keytool.exe":"keytool").toString(),"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-keystore",validKeys.toString(),"-storetype","PKCS12","-storepass",new String(password),"-dname","CN=127.0.0.1","-ext","SAN=IP:127.0.0.1","-ext","BC=ca:true","-validity","2","-noprompt"),work,work.resolve("valid-keytool.log"))==0,"Valid PEM fixture generation");
            var validStore=KeyStore.getInstance("PKCS12");try(var input=Files.newInputStream(validKeys)){validStore.load(input,password);}
            String pem="-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(validStore.getCertificate("fixture").getEncoded())+"\n-----END CERTIFICATE-----\n";
            Path pemPath=work.resolve("corporate cert.pem");Files.writeString(pemPath,pem);
            managers.init(validStore,password);context=SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,null);
            server.stop(0);server=HttpsServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
            server.setHttpsConfigurator(new HttpsConfigurator(context));server.setExecutor(workers);
            server.createContext("/",exchange->{requests.incrementAndGet();exchange.sendResponseHeaders(404,-1);exchange.close();});server.start();
            url="https://127.0.0.1:"+server.getAddress().getPort();
            git.set(git.size()-1,url+"/fixture.git");git.set(git.indexOf("http.sslVerify=false"),"http.sslVerify=true");
            git.addAll(1,List.of("-c","http.sslCAInfo="+pemPath,"-c","http.schannelUseSSLCAInfo=true"));before=requests.get();
            check(run(git,work,work.resolve("git-pem.log"))!=0&&requests.get()>before,"Git verifies using supplied PEM without OS trust changes");
            List<String> trust=CgraphInstaller.installationTrustArguments(work,pemPath);
            check(!trust.contains("-Dmaven.wagon.http.ssl.insecure=true"),"PEM path must not bypass certificate validation");
            Files.writeString(work.resolve("settings.xml"),"<settings><mirrors><mirror><id>fixture-pem</id><mirrorOf>*</mirrorOf><url>"+url+"/maven</url></mirror></mirrors></settings>");
            maven.removeAll(CgraphInstaller.installationTlsArguments());maven.addAll(1,trust);maven.add(1,"-U");before=requests.get();
            check(run(maven,work,work.resolve("maven-pem.log"))!=0&&requests.get()>before,"Maven verifies using installer-only PEM trust store");
            check(Files.readString(pemPath).equals(pem),"User PEM remains unchanged");
            Files.delete(work.resolve("installer-trust.p12"));
            System.out.println("TLS smoke passed: real Git/Maven bypass invalid certificates by default, verify with supplied PEM, and retain HTTP errors. Public trust stores/fixtures cleaned; no external downloads or global trust changes.");
        } finally {if(server!=null)server.stop(0);workers.shutdownNow();workers.awaitTermination(5,TimeUnit.SECONDS);CgraphInstaller.removeOwnedWork(work,owner);}
    }
}
