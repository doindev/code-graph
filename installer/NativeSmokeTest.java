import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Exercises a packaged application on unused ports with disposable state, never the user's live server. */
public class NativeSmokeTest {
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static int port()throws Exception{try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){return socket.getLocalPort();}}
    public static void main(String[] args)throws Exception{
        if(args.length<1)throw new IllegalArgumentException("Usage: NativeSmokeTest PATH_TO_NATIVE_EXECUTABLE [desktop|none] [INSTALL_DIR]");
        Path exe=Path.of(args[0]).toRealPath();
        Path work=Files.createTempDirectory("cgraph-build-");String owner=UUID.randomUUID().toString();Files.writeString(work.resolve(".cgraph-build-owner"),owner);
        Files.createDirectories(work.resolve("tmp"));Files.createDirectories(work.resolve("home"));
        Process app=null;
        try{
            int mcp=port(),ui=port();while(ui==mcp)ui=port();
            ProcessBuilder builder=new ProcessBuilder(exe.toString(),"--port",""+mcp,"--viz",""+ui,"--dba-dir",work.resolve("dba").toString(),"--dba-approval-mode",args.length>1?args[1]:"none");
            // Deliberately no system Java. jpackage must use its own runtime.
            for(String key:List.of("JAVA_HOME","CGRAPH_JAVA_HOME","CODE_GRAPH_ROOT","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","_JAVA_OPTIONS"))builder.environment().remove(key);
            builder.environment().put("JAVA_HOME",work.resolve("no-system-java").toString());
            builder.environment().put("JAVA_TOOL_OPTIONS","-Djava.io.tmpdir=\""+work.resolve("tmp")+"\" -Duser.home=\""+work.resolve("home")+"\"");
            builder.directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("server.log").toFile());app=builder.start();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector(){public List<Proxy>select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,java.io.IOException error){}}).build();
            String base="http://127.0.0.1:"+ui;
            boolean ready=false;
            for(int attempt=0;attempt<60;attempt++){
                if(!app.isAlive())throw new AssertionError("Native server exited: "+Files.readString(work.resolve("server.log")));
                try{ready=client.send(HttpRequest.newBuilder(URI.create(base+"/")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200;}catch(Exception ignored){}
                if(ready)break;Thread.sleep(500);
            }
            require(ready,"Native startup timeout");
            for(String page:List.of("/","/dba"))require(client.send(HttpRequest.newBuilder(URI.create(base+page)).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()==200,page+" unavailable");
            String info=client.send(HttpRequest.newBuilder(URI.create(base+"/api/server")).GET().build(),HttpResponse.BodyHandlers.ofString()).body();
            require(info.contains("\"mutable\":true")&&info.contains("\"dbaEnabled\":true")&&info.contains("hybrid")&&info.contains("1610612736"),"Default configuration mismatch: "+info);
            require(client.send(HttpRequest.newBuilder(URI.create(base+"/api/projects")).GET().build(),HttpResponse.BodyHandlers.ofString()).body().trim().equals("[]"),"Unexpected automatic project onboarding");
            URI endpoint=URI.create("http://127.0.0.1:"+mcp+"/mcp");
            String init="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"native-install-smoke\",\"version\":\"1\"}}}";
            var response=client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Accept","application/json, text/event-stream").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(init)).build(),HttpResponse.BodyHandlers.ofString());
            require(response.statusCode()==200&&response.body().contains("protocolVersion"),"MCP initialization failed");
            String session=response.headers().firstValue("Mcp-Session-Id").orElseThrow();
            client.send(HttpRequest.newBuilder(endpoint).header("Mcp-Session-Id",session).header("MCP-Protocol-Version","2025-06-18").DELETE().build(),HttpResponse.BodyHandlers.discarding());
            require(Files.readString(work.resolve("server.log")).contains("actions enabled"),"Admin actions not enabled");
            if(args.length>2){
                Path install=Path.of(args[2]).toRealPath();
                require(exe.startsWith(install)&&Files.readString(install.resolve(".cgraph-install")).equals(CgraphInstaller.MARKER),"Uninstall check must target this owned image");
                List<String> command=CgraphInstaller.WINDOWS
                    ? List.of(System.getenv("SystemRoot")+"/System32/WindowsPowerShell/v1.0/powershell.exe","-NoProfile","-File",install.resolve("uninstall.ps1").toString(),"-InstallDir",install.toString(),"-Check")
                    : List.of("bash",install.resolve("uninstall.sh").toString(),"--install-dir",install.toString(),"--check");
                Process check=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(work.resolve("uninstall-check.log").toFile()).start();
                if(!check.waitFor(20,TimeUnit.SECONDS)){check.destroyForcibly();throw new AssertionError("Uninstall running-process check timed out");}
                require(check.exitValue()!=0&&Files.readString(work.resolve("uninstall-check.log")).contains("Stop it first"),"Uninstaller must refuse the actual running image");
                require(Files.isRegularFile(exe)&&app.isAlive(),"Uninstaller changed a running image");
                System.out.println("Uninstaller refused the actual running native image without changing files or PATH.");
            }
            System.out.println("Native smoke passed: bundled Java; Graph/DBA HTTP 200; MCP initialized; admin; hybrid 1.5 GiB; empty workspace; isolated state/ports.");
        }finally{
            if(app!=null){app.destroy();if(!app.waitFor(10,TimeUnit.SECONDS)){app.destroyForcibly();app.waitFor(10,TimeUnit.SECONDS);}}
            CgraphInstaller.removeOwnedWork(work,owner);
        }
    }
}
