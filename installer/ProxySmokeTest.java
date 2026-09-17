import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Git/Maven clients against a loopback-only, rejecting Basic-auth proxy. No Internet or package installation. */
public class ProxySmokeTest {
    static final String USER="cgraph-proxy-fixture",PASSWORD="test-only+password";
    static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static class ProxyStub implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));
        final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger authenticated=new AtomicInteger(),requests=new AtomicInteger();
        ProxyStub()throws IOException{workers.submit(()->{while(!listener.isClosed())try{Socket s=listener.accept();workers.submit(()->serve(s));}catch(IOException ignored){}});}
        void serve(Socket socket){try(socket){
            socket.setSoTimeout(5000);var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
            String expected="Proxy-Authorization: Basic "+Base64.getEncoder().encodeToString((USER+":"+PASSWORD).getBytes(StandardCharsets.UTF_8));
            boolean auth=false;String line;int count=0;
            while((line=reader.readLine())!=null&&!line.isEmpty()&&++count<100)if(line.equalsIgnoreCase(expected))auth=true;
            requests.incrementAndGet();if(auth)authenticated.incrementAndGet();
            // Never forward requests. A deliberate failure proves routing without contacting any external host.
            String response=auth?"HTTP/1.1 502 Fixture End\r\n":"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"cgraph-fixture\"\r\n";
            socket.getOutputStream().write((response+"Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        }catch(IOException ignored){}}
        public void close()throws Exception{listener.close();workers.shutdownNow();workers.awaitTermination(6,TimeUnit.SECONDS);}
    }
    static void run(List<String>command,Map<String,String>env,Path cwd,Path log)throws Exception{
        boolean cmd=CgraphInstaller.WINDOWS&&command.getFirst().endsWith(".cmd");
        var b=new ProcessBuilder(cmd?CgraphInstaller.powershell("$a=@($env:CGRAPH_TOOL_ARGS|ConvertFrom-Json); & $env:CGRAPH_TOOL @a; exit $LASTEXITCODE"):command);
        b.environment().putAll(env);b.environment().put("JAVA_HOME",System.getProperty("java.home"));
        if(cmd){b.environment().put("CGRAPH_TOOL",command.getFirst());b.environment().put("CGRAPH_TOOL_ARGS","["+String.join(",",command.subList(1,command.size()).stream().map(CgraphInstaller::json).toList())+"]");}
        Process process=b.directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{require(process.waitFor(45,TimeUnit.SECONDS),"Proxy client timed out");require(process.exitValue()!=0,"Rejecting proxy unexpectedly permitted access");}
        finally{if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
    }
    public static void main(String[]args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("Usage: ProxySmokeTest PATH_TO_GIT PATH_TO_MAVEN");
        Path work=Files.createTempDirectory("cgraph-build-");String owner=UUID.randomUUID().toString();Files.writeString(work.resolve(".cgraph-build-owner"),owner);
        try(var proxy=new ProxyStub()){
            String address="http://127.0.0.1:"+proxy.listener.getLocalPort();
            var env=new HashMap<String,String>();env.put("CGRAPH_PROXY_USER",USER);env.put("CGRAPH_PROXY_PASSWORD",PASSWORD);
            env.put("NO_PROXY","");env.put("no_proxy","");env.put("GIT_CONFIG_COUNT","2");env.put("GIT_CONFIG_KEY_0","http.proxy");
            env.put("GIT_CONFIG_VALUE_0",address.replace("http://","http://"+USER+":"+URLEncoder.encode(PASSWORD,StandardCharsets.UTF_8)+"@"));
            env.put("GIT_CONFIG_KEY_1","http.proxyAuthMethod");env.put("GIT_CONFIG_VALUE_1","basic");env.put("GIT_TERMINAL_PROMPT","0");
            run(List.of(args[0],"ls-remote","https://cgraph-proxy-fixture.invalid/repository.git"),env,work,work.resolve("git.log"));
            require(proxy.authenticated.get()>0,"Git did not authenticate to the local proxy");int gitCount=proxy.authenticated.get();
            String settings=CgraphInstaller.proxySettings(address,"localhost",env).replace("</settings>","<mirrors><mirror><id>fixture</id><mirrorOf>*</mirrorOf><url>https://cgraph-proxy-fixture.invalid/maven</url></mirror></mirrors></settings>");
            require(!settings.contains(PASSWORD),"Proxy credentials persisted in Maven settings");
            Files.writeString(work.resolve("settings.xml"),settings);Files.writeString(work.resolve("global.xml"),"<settings/>");
            run(List.of(args[1],"-B","-ntp","-s",work.resolve("settings.xml").toString(),"-gs",work.resolve("global.xml").toString(),"-Dmaven.repo.local="+work.resolve("repository"),"org.cgraph.fixture:nonexistent-plugin:1:probe"),env,work,work.resolve("maven.log"));
            require(proxy.authenticated.get()>gitCount,"Maven did not authenticate to the local proxy");
            require(!Files.readString(work.resolve("maven.log")).contains(PASSWORD),"Maven printed proxy password");
            System.out.println("Proxy smoke passed: Git HTTPS and Maven HTTPS both authenticate through a configured HTTP proxy; environment credentials resolved; failures remain closed; no external requests forwarded.");
        }finally{CgraphInstaller.removeOwnedWork(work,owner);}
    }
}
