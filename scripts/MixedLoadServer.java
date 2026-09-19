import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.*;
import io.doindev.codegraph.mcp.http.HttpServer;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.storage.*;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Benchmark-only host. Writes only the probe inside an explicitly owned disposable copy. */
public class MixedLoadServer {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String PROBE = "__mixed_latency__/LatencyProbe.java";
    static final long START = System.nanoTime();
    static double now() { return (System.nanoTime()-START)/1e6; }
    static synchronized void emit(String event, Object value) {
        try { System.out.println(event+"="+JSON.writeValueAsString(value)); System.out.flush(); }
        catch(IOException e) { throw new UncheckedIOException(e); }
    }
    static Map<String,Object> metrics(Workspace workspace, AtomicLong peakHeap) {
        var result = new LinkedHashMap<String,Object>();
        result.put("atMs",now());
        result.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("sampledPeakHeapBytes",peakHeap.get());
        result.put("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum());
        result.put("gcTimeMs",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum());
        result.put("processCpuNanos",((com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime());
        result.put("storage",workspace.storageStatus());
        return result;
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toRealPath();
        if(!Files.readString(root.resolve(".mixed-load-owner")).equals(args[1]))
            throw new IllegalArgumentException("Not an owned benchmark copy");
        Path probe=root.resolve(PROBE);
        if(!Files.isRegularFile(probe,LinkOption.NOFOLLOW_LINKS)||!probe.toRealPath().startsWith(root))
            throw new IllegalArgumentException("Invalid owned probe");
        var peakHeap=new AtomicLong(); var running=new AtomicBoolean(true);
        var observerError=new AtomicReference<Throwable>();
        try(var workspace=Workspace.open(List.of(root),Analyzers.discover(),p->CodeGraphConfig.defaults(),true,32L<<20);
            var server=HttpServer.start(workspace,0,-1,false,java.time.Duration.ofHours(1))) {
            var project=workspace.projects().getFirst();
            var graph=(PagedGraph)project.graph();
            Thread observer=Thread.ofPlatform().daemon().name("benchmark-publication-observer").start(()->{
                long last=project.indexer().lastHybridUpdate().generation();
                try {
                    while(running.get()) {
                        peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                        var work=project.indexer().lastHybridUpdate();
                        if(work.generation()!=last) {
                            var result=graph.read(()->{
                                var fragment=RecordCodec.decode(graph.document("fragment/"+PROBE),RecordCodec.F.class);
                                var id=new SymbolId("java",PROBE,"latencyprobe.LatencyProbe.entry",0);
                                var calls=new ArrayList<String>();
                                graph.scanEdges(id,Direction.OUT,Set.of(EdgeKind.CALLS),e->calls.add(e.to().value()));
                                var row=new LinkedHashMap<String,Object>();
                                row.put("observedAtMs",now()); row.put("generation",graph.generation());
                                row.put("hash",fragment.hash()); row.put("calls",calls);
                                row.put("work",work); return row;
                            });
                            emit("PUBLICATION",result); last=((Number)result.get("generation")).longValue();
                        }
                        Thread.sleep(10);
                    }
                } catch(InterruptedException stop) { Thread.currentThread().interrupt(); }
                catch(Throwable failure) { observerError.set(failure); emit("OBSERVER_ERROR",failure.toString()); }
            });
            emit("READY",Map.of("endpoint","http://127.0.0.1:"+server.port()+"/mcp","pid",ProcessHandle.current().pid(),
                "indexAndStartupMs",now(),"java",System.getProperty("java.version"),"os",System.getProperty("os.name"),
                "processors",Runtime.getRuntime().availableProcessors(),"metrics",metrics(workspace,peakHeap)));
            try(var input=new BufferedReader(new InputStreamReader(System.in))) {
                for(String line;(line=input.readLine())!=null;) {
                    var command=JSON.readTree(line);var ack=new LinkedHashMap<String,Object>();ack.put("id",command.path("id").asInt());
                    if(command.path("action").asText().equals("edit")) {
                        String content=command.path("content").asText();
                        if(content.length()>4096)throw new IllegalArgumentException("Oversized probe");
                        ack.put("writeStartedMs",now());Files.writeString(probe,content);ack.put("writeFinishedMs",now());
                    } else if(command.path("action").asText().equals("mark")) {
                        ack.put("metrics",metrics(workspace,peakHeap));
                        var status=graph.status();
                        ack.put("status",Map.of("generation",status.generation(),"files",status.filesIndexed(),
                            "symbols",status.symbolCount(),"edges",status.edgeCount(),"dirtyPending",status.dirtyPending()));
                    } else throw new IllegalArgumentException("Unknown benchmark command");
                    emit("ACK",ack);
                }
            } finally { running.set(false);observer.interrupt();observer.join(5000); }
            if(observerError.get()!=null)throw new AssertionError("Publication observer failed",observerError.get());
            emit("METRICS",metrics(workspace,peakHeap));
        }
    }
}
