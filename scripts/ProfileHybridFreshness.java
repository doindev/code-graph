import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.*;
import io.doindev.codegraph.storage.*;
import java.lang.management.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import jdk.jfr.*;

/** Isolated diagnostic: copies source, edits only its own probe, and removes its own store/copy. */
public class ProfileHybridFreshness {
    static final ObjectMapper JSON=new ObjectMapper();
    static final String PROBE="__mixed_latency__/LatencyProbe.java";
    static final Set<String> EXCLUDED=Set.of(".git","target","node_modules",".idea",".code-graph");
    static String probe(int revision) {
        return "package latencyprobe; class LatencyProbe { static void entry(){ alpha(); } "
            +"static void alpha(){} static void beta(){} static void revision"+revision+"(){} }";
    }
    static Map<String,Object> sample(GraphStorage storage,AtomicLong peak) {
        var heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        peak.accumulateAndGet(heap.getUsed(),Math::max);
        var row=new LinkedHashMap<String,Object>();
        row.put("heapUsedBytes",heap.getUsed());row.put("heapCommittedBytes",heap.getCommitted());
        row.put("sampledPeakHeapBytes",peak.get());
        row.put("nonHeapUsedBytes",ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
        row.put("gcCount",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum());
        row.put("gcMillis",ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum());
        row.put("storage",storage.status());return row;
    }
    static void copy(Path source,Path target)throws Exception {
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws java.io.IOException {
                if(!dir.equals(source)&&EXCLUDED.contains(dir.getFileName().toString()))return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(target.resolve(source.relativize(dir)));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws java.io.IOException {
                if(attrs.isRegularFile()&&!attrs.isSymbolicLink())Files.copy(file,target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("FROZEN_SOURCE OUTPUT_DIRECTORY required");
        Path source=Path.of(args[0]).toRealPath(),output=Path.of(args[1]).toAbsolutePath().normalize();
        if(output.startsWith(source))throw new IllegalArgumentException("Output must be outside the measured source");
        Files.createDirectories(output);
        Path owned=Files.createTempDirectory("code-graph-freshness-profile-").toRealPath(),storePath=null;
        var report=new LinkedHashMap<String,Object>();var peak=new AtomicLong();var running=new AtomicBoolean(true);
        report.put("pid",ProcessHandle.current().pid());report.put("source",source.toString());
        report.put("java",System.getProperty("java.version"));report.put("graphBudgetBytes",32L<<20);
        report.put("heapMaxBytes",Runtime.getRuntime().maxMemory());
        Thread sampler=Thread.ofPlatform().daemon().name("profile-heap-sampler").start(()->{
            while(running.get()) {
                peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                try {Thread.sleep(100);}catch(InterruptedException done){return;}
            }
        });
        try {
            copy(source,owned);Path probe=owned.resolve(PROBE);Files.createDirectories(probe.getParent());Files.writeString(probe,probe(0));
            try(var storage=new GraphStorage(true,32L<<20)) {
                storePath=storage.directory();var graph=storage.create();
                var indexer=new IncrementalIndexer(owned,Analyzers.discover(),CodeGraphConfig.defaults(),graph);
                long start=System.nanoTime();indexer.fullIndex();
                long expectedSymbols=graph.status().symbolCount(),expectedEdges=graph.status().edgeCount();
                report.put("initialMillis",(System.nanoTime()-start)/1e6);
                report.put("initialFiles",graph.status().filesIndexed());report.put("initialSymbols",graph.status().symbolCount());
                report.put("initialEdges",graph.status().edgeCount());report.put("afterInitial",sample(storage,peak));
                System.out.println("INDEXED="+JSON.writeValueAsString(report));System.out.flush();
                try(var recording=new Recording(Configuration.getConfiguration("profile"))) {
                    recording.setMaxSize(64L<<20);
                    recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                    recording.enable("jdk.ThreadPark").withThreshold(Duration.ofMillis(1)).withStackTrace();
                    recording.enable("jdk.FileRead").withThreshold(Duration.ofMillis(1)).withStackTrace();
                    recording.enable("jdk.FileWrite").withThreshold(Duration.ofMillis(1)).withStackTrace();
                    recording.start();var updates=new ArrayList<Map<String,Object>>();
                    for(int i=1;i<=3;i++) {
                        Files.writeString(probe,probe(i));start=System.nanoTime();indexer.applyChanges(List.of(PROBE));
                        var row=new LinkedHashMap<String,Object>();row.put("iteration",i);
                        row.put("elapsedMillis",(System.nanoTime()-start)/1e6);row.put("work",indexer.lastHybridUpdate());
                        row.put("metrics",sample(storage,peak));updates.add(row);
                        if(graph.status().symbolCount()!=expectedSymbols||graph.status().edgeCount()!=expectedEdges)
                            throw new AssertionError("Declaration rename changed inventory counts");
                        System.out.println("UPDATE="+JSON.writeValueAsString(row));System.out.flush();
                    }
                    recording.stop();recording.dump(output.resolve("updates.jfr"));report.put("updates",updates);
                }
                report.put("final",sample(storage,peak));
            }
        } finally {
            running.set(false);sampler.interrupt();sampler.join(5000);
            try(var paths=Files.walk(owned)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) {
                    if(!path.normalize().startsWith(owned))throw new IllegalStateException("Cleanup escaped owned copy");
                    Files.delete(path);
                }
            }
            report.put("ownedCopyRemoved",!Files.exists(owned));
            report.put("ownedStoreRemoved",storePath==null||!Files.exists(storePath));
            Files.writeString(output.resolve("profile.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
