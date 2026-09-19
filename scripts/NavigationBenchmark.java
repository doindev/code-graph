import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.*;
import io.doindev.codegraph.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.lang.management.*;
import java.util.*;

/**
 * Run against either packaged application, in a fresh JVM, on exactly the same source snapshot.
 * java -Xmx768m --enable-native-access=ALL-UNNAMED -cp "target/lib/*" scripts/NavigationBenchmark.java ROOT hybrid
 * The graph store is session-owned and closes/deletes its temporary disk records on exit.
 */
public class NavigationBenchmark {
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var report=json.createObjectNode();
        Path root=Path.of(args[0]);boolean hybrid=args.length<2||args[1].equals("hybrid");
        report.put("root",root.toString()).put("mode",hybrid?"hybrid":"memory").put("java",System.getProperty("java.version"));
        report.put("maxHeapBytes",Runtime.getRuntime().maxMemory()).put("cacheAllowanceBytes",32L<<20);
        report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        report.put("processors",Runtime.getRuntime().availableProcessors());
        report.put("cacheState","Fresh JVM/application store; operating-system file cache is NOT cleared");
        long start=System.nanoTime();
        try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),hybrid,32L<<20)) {
            workspace.fullIndexAll();
            report.put("indexMs",(System.nanoTime()-start)/1_000_000d);
            var graph=workspace.defaultProject().graph();
            var status=graph.status();
            report.putObject("index").put("generation",status.generation()).put("files",status.filesIndexed())
                    .put("symbols",status.symbolCount()).put("edges",status.edgeCount()).put("engine",status.engineVersion());
            var symbol=graph.findSymbols("NativeRedisArguments.bytes",Set.of(NodeKind.FUNCTION),"java",10).getFirst();
            var edges=graph.edges(symbol.id(),Direction.IN,Set.of(EdgeKind.CALLS));
            report.put("callerOccurrences",edges.size());
            report.set("distinctCallers",json.valueToTree(edges.stream().map(e->e.from().value()).distinct().sorted().toList()));
            var samples=new ArrayList<Double>();
            for(int i=0;i<33;i++) {
                long tick=System.nanoTime();
                graph.findSymbols("NativeRedisArguments",null,"java",20);
                graph.edges(symbol.id(),Direction.IN,Set.of(EdgeKind.CALLS));
                graph.closure(symbol.id(),Direction.IN,Set.of(EdgeKind.CALLS),2,100,0f);
                if(i>=3)samples.add((System.nanoTime()-tick)/1_000_000d);
            }
            report.set("querySequenceMs",json.valueToTree(samples));samples.sort(Double::compare);
            report.put("queryP50Ms",samples.get(14)).put("queryP95Ms",samples.get(28)).put("queryP99Ms",samples.get(29));
            report.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            report.put("heapPoolPeaksBytes",ManagementFactory.getMemoryPoolMXBeans().stream()
                    .filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum());
            report.put("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum());
            report.put("gcTimeMs",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum());
            report.set("storage",json.valueToTree(workspace.storageStatus()));
        }
        System.out.println(report);
    }
}
