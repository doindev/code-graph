import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.*;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.storage.*;
import io.doindev.codegraph.graph.InMemoryCodeGraph;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.lang.management.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * One independent run against a frozen packaged build. Copies only the named
 * repository indexing stack's main sources, then adds a deterministic edit fixture.
 * No production server, database or source file is modified.
 */
public class IncrementalBenchmark {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String[] MODULES = {"code-graph-core","code-graph-parsers","code-graph-index",
            "code-graph-lang-java","code-graph-lang-javascript"};
    static final AtomicInteger parsed = new AtomicInteger();
    static final AtomicLong peakHeap = new AtomicLong();
    static volatile boolean sampling = true;
    static final List<Map<String,Object>> operations = new ArrayList<>();
    static Map<String,Object> work(IncrementalIndexer indexer) {
        try {
            Object result = IncrementalIndexer.class.getMethod("lastHybridUpdate").invoke(indexer);
            return result == null ? Map.of() : JSON.convertValue(result,Map.class);
        } catch (NoSuchMethodException oldBuild) { return Map.of("resolvedFiles","unavailable in baseline"); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }
    static long gc() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum(); }
    static void measure(String name, IncrementalIndexer indexer, GraphStorage storage, Runnable action) {
        parsed.set(0);long gc=gc(),start=System.nanoTime();action.run();double millis=(System.nanoTime()-start)/1e6;
        var row=new LinkedHashMap<String,Object>();
        row.put("operation",name);row.put("milliseconds",millis);row.put("parsedFiles",parsed.get());row.put("indexerWork",work(indexer));
        row.put("generation",indexer.graph().generation());row.put("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        row.put("gcMillis",gc()-gc);row.put("storage",storage.status());operations.add(row);
        System.err.println(name+" "+Math.round(millis)+"ms parsed="+parsed.get());
    }
    static void write(Path root,String name,String text) throws Exception {
        Path path=root.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,text);
    }
    static Map<Edge,Integer> counts(List<Edge> edges) {
        var counts=new HashMap<Edge,Integer>();for(var edge:edges)counts.merge(edge,1,Integer::sum);return counts;
    }
    static void verify(IncrementalIndexer indexer,Path root) {
        var fresh=new InMemoryCodeGraph();
        new IncrementalIndexer(root,Analyzers.discover(),CodeGraphConfig.defaults(),fresh).fullIndex();
        var graph=indexer.graph();var expected=new HashMap<NodeId,Node>();fresh.scanNodes(null,n->expected.put(n.id(),n));
        var actual=new HashMap<NodeId,Node>();graph.scanNodes(null,n->actual.put(n.id(),n));
        if(!expected.equals(actual))throw new AssertionError("fresh-index node mismatch");
        for(var id:expected.keySet())if(!counts(fresh.edges(id,Direction.OUT,null)).equals(counts(graph.edges(id,Direction.OUT,null))))
            throw new AssertionError("fresh-index edge mismatch: "+id);
        if(fresh.status().edgeCount()!=graph.status().edgeCount())throw new AssertionError("edge count mismatch");
    }
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]).toAbsolutePath().normalize();
        Path scratch=Files.createTempDirectory("code-graph-incremental-benchmark-").toRealPath();
        var digest=MessageDigest.getInstance("SHA-256");var inventory=new TreeMap<String,String>();
        Path ownedStore=null;
        try {
            for(String module:MODULES) {
                Path directory=source.resolve(module+"/src/main/java");
                try(var stream=Files.walk(directory)) {
                    for(Path path:stream.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                        String relative=source.relativize(path).toString().replace('\\','/');
                        byte[] bytes=Files.readAllBytes(path);Path destination=scratch.resolve(relative);
                        Files.createDirectories(destination.getParent());Files.write(destination,bytes);
                        inventory.put(relative,HexFormat.of().formatHex(digest.digest(bytes)));
                    }
                }
            }
            write(scratch,"probe/Probe.java","class Probe { static void call(){ call(); } }");
            write(scratch,"probe/Use.java","class Use { void run(){ Probe.call(); } }");
            write(scratch,"probe/main.js","import {go} from '@api'; go();");
            write(scratch,"probe/one.js","export function go(){}");
            write(scratch,"probe/two.js","export function go(){}");
            write(scratch,"probe/tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":[\"./one.js\"]}}}");
            var report=new LinkedHashMap<String,Object>();
            report.put("build",args.length>1?args[1]:"unspecified");report.put("sourceRoot",source.toString());
            report.put("dataset","frozen indexing-stack main sources plus 5 deterministic probe source files");
            report.put("sourceFiles",inventory.size());report.put("datasetSha256",HexFormat.of().formatHex(digest.digest(JSON.writeValueAsBytes(inventory))));
            report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));report.put("java",System.getProperty("java.version"));
            report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
            report.put("graphCacheAllowanceBytes",32L<<20);report.put("cacheState","fresh JVM/store; OS file cache is NOT flushed");
            var wrappers=new ArrayList<LanguageAnalyzer>();
            for(var original:ServiceLoader.load(LanguageAnalyzer.class))wrappers.add(new LanguageAnalyzer() {
                public String languageId(){return original.languageId();}
                public Set<String> fileExtensions(){return original.fileExtensions();}
                public FileFragment extract(SourceFile file){parsed.incrementAndGet();return original.extract(file);}
            });
            var sample=Thread.ofPlatform().daemon().start(()->{
                while(sampling) {
                    peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                    try{Thread.sleep(25);}catch(InterruptedException stop){return;}
                }
            });
            try(var storage=new GraphStorage(true,32L<<20)) {
                ownedStore=storage.directory();
                var graph=storage.create();var indexer=new IncrementalIndexer(scratch,Analyzers.of(wrappers),CodeGraphConfig.defaults(),graph);
                measure("initial",indexer,storage,indexer::fullIndex);
                measure("unchanged-save",indexer,storage,()->indexer.applyChanges(List.of("probe/Probe.java")));
                write(scratch,"probe/Probe.java","class Probe { static void call(){ call(); call(); call(); } }");
                measure("body-edit",indexer,storage,()->indexer.applyChanges(List.of("probe/Probe.java")));
                write(scratch,"probe/Probe.java","class Probe { static void renamed(){ renamed(); } }");
                measure("declaration-edit",indexer,storage,()->indexer.applyChanges(List.of("probe/Probe.java")));
                write(scratch,"probe/tsconfig.json","{\"compilerOptions\":{\"moduleResolution\":\"bundler\",\"paths\":{\"@api\":[\"./two.js\"]}}}");
                measure("config-edit",indexer,storage,()->indexer.applyChanges(List.of("probe/tsconfig.json")));
                var symbol=graph.findSymbols("Probe.renamed",Set.of(NodeKind.FUNCTION),"java",5).getFirst();
                var latency=new ArrayList<Double>();
                for(int i=0;i<33;i++) {
                    long start=System.nanoTime();graph.node(symbol.id());graph.edges(symbol.id(),Direction.IN,Set.of(EdgeKind.CALLS));
                    if(i>=3)latency.add((System.nanoTime()-start)/1e6);
                }
                report.put("warmQuerySamplesMs",List.copyOf(latency));latency.sort(Double::compare);
                report.put("queryP50Ms",latency.get(14));report.put("queryP95Ms",latency.get(28));report.put("queryP99Ms",latency.get(29));
                report.put("sampledPeakHeapBeforeOracle",peakHeap.get());report.put("heapUsedBeforeOracle",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                report.put("gcMillisBeforeOracle",gc());report.put("operations",operations);
                report.put("graph",Map.of("files",graph.status().filesIndexed(),"symbols",graph.status().symbolCount(),"edges",graph.status().edgeCount()));
                report.put("storageBeforeOracle",storage.status());
                sampling=false;sample.join();verify(indexer,scratch);report.put("freshInMemoryOracle","passed; not included in measurements");
            } finally {sampling=false;sample.interrupt();}
            report.put("ownedStoreRemoved",!Files.exists(ownedStore));
            System.out.println(JSON.writeValueAsString(report));
        } finally {
            // Only this uniquely created directory, no followed links, no caller-supplied deletion path.
            Files.walkFileTree(scratch,new SimpleFileVisitor<>() {
                public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws java.io.IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
                public FileVisitResult postVisitDirectory(Path dir,java.io.IOException error)throws java.io.IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
            });
        }
    }
}
