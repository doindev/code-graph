import io.doindev.codegraph.index.*;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.mcp.http.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.lang.management.*;

/** Owned benchmark host: stdin EOF closes the server/store, including session-only disk files. */
public class EfficiencyServer {
    public static void main(String[] args)throws Exception {
        var json=new ObjectMapper();long start=System.nanoTime();
        try(var workspace=Workspace.open(args[0].equals("--empty")?List.of():List.of(Path.of(args[0])),Analyzers.discover(),p->CodeGraphConfig.defaults(),true,32L<<20);
            var server=HttpServer.start(workspace,0,-1,false,java.time.Duration.ofHours(1))) {
            var report=json.createObjectNode().put("endpoint","http://127.0.0.1:"+server.port()+"/mcp")
                    .put("indexAndStartupMs",(System.nanoTime()-start)/1e6);
            report.set("storage",json.valueToTree(workspace.storageStatus()));
            System.out.println("READY="+report);System.out.flush();
            while(System.in.read()!=-1) {}
            var metrics=json.createObjectNode().put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed())
                    .put("heapPoolPeaksBytes",ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum())
                    .put("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum())
                    .put("gcTimeMs",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum());
            metrics.set("storage",json.valueToTree(workspace.storageStatus()));
            System.out.println("METRICS="+metrics);System.out.flush();
        }
    }
}
