package io.doindev.codegraph.bench;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.store.GraphDelta;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** One independent JVM trial. The PowerShell runner supplies process-level supervision and repeats. */
public final class BenchMain {
    private static volatile long blackhole;
    public static void main(String[] args)throws Exception {
        Map<String,String> options=new HashMap<>();
        for(int i=0;i<args.length;i+=2){if(i+1==args.length)throw new IllegalArgumentException("option requires value");options.put(args[i],args[i+1]);}
        String backend=options.getOrDefault("--backend","memory"),scenario=options.getOrDefault("--scenario","fit");
        long budget=Long.parseLong(options.getOrDefault("--budget-mib","1024"))<<20;
        int nodes=Integer.parseInt(options.getOrDefault("--nodes","100000"));
        int edges=Integer.parseInt(options.getOrDefault("--edges","1000000"));
        int queries=Integer.parseInt(options.getOrDefault("--queries","1000"));
        int projects=Integer.parseInt(options.getOrDefault("--projects","1"));
        if(nodes<10||edges<0||queries<10||projects<1)throw new IllegalArgumentException("invalid workload dimensions");
        Path output=Path.of(Objects.requireNonNull(options.get("--output"),"--output required")).toAbsolutePath();
        Path root=Path.of(options.getOrDefault("--root",".")).toAbsolutePath().normalize();
        Path temp=Files.createTempDirectory("code-graph-storage-bench-").toRealPath();
        if(temp.startsWith(root))throw new IllegalStateException("scratch directory must be outside indexed root");
        Map<String,Object> report=new LinkedHashMap<>();
        report.put("backend",backend);report.put("scenario",scenario);report.put("startedAt",Instant.now().toString());
        report.put("budgetBytes",budget);report.put("nodesPerProject",nodes);report.put("edgesPerProject",edges);report.put("projects",projects);
        report.put("seed",42);report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("scratchDirectory",temp.toString());
        System.out.println("Owned scratch directory: "+temp);
        report.put("coldCacheDefinition","Fresh engine in a fresh JVM/directory; OS file cache is NOT flushed. First touch may hit OS cache.");
        report.put("status","failed");
        report.put("prototypeRevision","bounded-removal-warm-scan-v3");
        try(Telemetry telemetry=new Telemetry();Engine engine=new Engine(backend,temp,budget)) {
            report.put("beforeLoadMemory",telemetry.sample());long start=System.nanoTime();
            Ingestion ingestion=new Ingestion();
            if(scenario.equals("repository"))engine.load("p0",l->ingestion.repository(root,l,engine.kv!=null));
            else for(int p=0;p<projects;p++){int current=p;engine.load("p"+p,l->Ingestion.synthetic(l,nodes,edges,42+current));}
            report.put("loadMs",(System.nanoTime()-start)/1e6);report.put("ingestion",ingestion.stats());
            report.put("afterLoadMemory",telemetry.sample());report.put("afterLoadStore",engine.stats());
            NodeId target=scenario.equals("repository")?engine.query("p0",q->q.findSymbols("main",Set.of(NodeKind.FUNCTION),null,1)).stream().findFirst()
                    .orElseThrow(()->new IllegalStateException("repository has no main symbol")).id():Ingestion.syntheticNode(0).id();
            var timings=new LinkedHashMap<String,Object>();report.put("timings",timings);
            IntConsumer point=i->{NodeId id=scenario.equals("repository")?target:Ingestion.syntheticNode(i%Math.min(nodes,100)).id();
                blackhole+=engine.query("p0",q->q.node(id).orElseThrow().name().length());};
            timings.put("firstTouchPoint",measure(100,point));
            for(int i=0;i<500;i++)point.accept(i);
            timings.put("hotPoint",measure(queries,point));
            IntConsumer callers=i->blackhole+=engine.query("p0",q->q.edges(target,Direction.IN,Set.of(EdgeKind.CALLS)).size());
            IntConsumer closure=i->blackhole+=engine.query("p0",q->q.closure(target,Direction.IN,null,3,500,0.6f).hits().size());
            for(int i=0;i<30;i++){callers.accept(i);closure.accept(i);}
            timings.put("callers",measure(Math.max(100,queries/2),callers));
            timings.put("impact",measure(Math.max(100,queries/5),closure));
            IntConsumer search=i->blackhole+=engine.query("p0",q->q.findSymbols(scenario.equals("repository")?"main":"m99",null,null,10).size());
            for(int i=0;i<3;i++)search.accept(i);
            timings.put("symbolSearch",measure(10,search));
            var recovery=new ArrayList<Object>();
            for(int cycle=0;cycle<3;cycle++){
                Map<String,Object> entry=new LinkedHashMap<>();
                for(int i=0;i<500;i++)point.accept(i); // Rewarm after substring search/previous scan before measuring pollution.
                entry.put("beforeScanHotWindow",measure(100,point));entry.put("beforeScanStore",engine.stats());
                long t=System.nanoTime();engine.scan("p0",n->blackhole+=n.name().length());
                entry.put("scanMs",(System.nanoTime()-t)/1e6);entry.put("afterScanStore",engine.stats());
                entry.put("immediatelyAfterScan",measure(100,point));entry.put("nextHotWindow",measure(queries,point));entry.put("recoveredStore",engine.stats());recovery.add(entry);
            }report.put("scanRecovery",recovery);
            if(projects>1){var shifts=new ArrayList<Object>();for(int p=0;p<projects;p++){String name="p"+p;
                shifts.add(measure(queries,i->blackhole+=engine.query(name,q->q.node(Ingestion.syntheticNode(i%100).id()).orElseThrow().name().length())));}report.put("projectShift",shifts);}
            report.put("beforeConcurrentStore",engine.stats());
            report.put("retainedBeforeResize",telemetry.retainedSample());
            java.lang.ref.Reference.reachabilityFence(ingestion);
            long t=System.nanoTime();concurrent(engine,target);report.put("concurrentReadUpdateMs",(System.nanoTime()-t)/1e6);
            var resize=new LinkedHashMap<String,Object>();report.put("resize",resize);
            try {engine.resize(Math.max(8L<<20,budget/4));resize.put("afterReduction",engine.stats());resize.put("queries",measure(queries,point));
                engine.resize(budget);resize.put("afterRestore",engine.stats());resize.put("status","passed");}
            catch(UnsupportedOperationException e){resize.put("status","unsupported");resize.put("reason",e.getMessage());}
            report.put("finalStore",engine.stats());report.put("finalMemory",telemetry.sample());
            report.put("queryFingerprint",fingerprint(engine,target,scenario.equals("repository")));
            report.put("finalGraphCounts",engine.query("p0",q->Map.of("files",q.status().filesIndexed(),"symbols",q.status().symbolCount(),"edges",q.status().edgeCount())));
            report.put("diskBytes",diskBytes(temp));
            report.put("retainedAfterQueries",telemetry.retainedSample());
            java.lang.ref.Reference.reachabilityFence(ingestion);
            report.put("phase","before-removal");
            checkpoint(output,report); // Preserve completed samples if removal or close exhausts resources.
            long removalStart=System.nanoTime();
            engine.remove("p0");
            report.put("removalMs",(System.nanoTime()-removalStart)/1e6);
            report.put("afterRemovalStore",engine.stats());
            report.put("retainedAfterRemoval",telemetry.retainedSample());
            try{engine.query("p0",q->q.status());throw new AssertionError("removed project remains readable");}catch(IllegalArgumentException expected){}
            if(projects>1)engine.query("p1",q->q.node(Ingestion.syntheticNode(0).id()).orElseThrow());
            report.put("removal","passed");report.put("status","completed");
        }catch(Throwable e){report.put("errorType",e.getClass().getName());report.put("error",String.valueOf(e.getMessage()));e.printStackTrace();}
        finally {
            try{cleanup(temp);report.put("cleanup","passed");}catch(Exception e){report.put("cleanup","failed: "+e);report.put("status","failed");}
            report.put("finishedAt",Instant.now().toString());Files.createDirectories(output.getParent());
            Codec.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        }
        if(!report.get("status").equals("completed"))System.exit(1);
    }
    static void checkpoint(Path output,Map<String,Object> report)throws IOException {
        Files.createDirectories(output.getParent());
        Codec.JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
    }
    static Map<String,Object> measure(int count,IntConsumer action){long[] samples=new long[count];for(int i=0;i<count;i++){long t=System.nanoTime();action.accept(i);samples[i]=System.nanoTime()-t;}return Telemetry.distribution(samples);}
    static String fingerprint(Engine engine,NodeId root,boolean repository)throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");
        for(int i=0;i<(repository?1:100);i++){
            NodeId id=repository?root:Ingestion.syntheticNode(i).id();
            engine.query("p0",q->{q.node(id).ifPresent(n->digest.update(Codec.node(n)));
                q.edges(id,Direction.BOTH,null).forEach(e->digest.update(Codec.edge(e)));
                var closure=q.closure(id,Direction.IN,null,2,50,.6f);
                closure.hits().forEach(h->{digest.update(Codec.node(h.node()));});return null;});
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void concurrent(Engine engine,NodeId target)throws Exception {
        try(var pool=Executors.newFixedThreadPool(3)){
            var readers=new ArrayList<Future<?>>();for(int i=0;i<2;i++)readers.add(pool.submit(()->{for(int n=0;n<200;n++)engine.query("p0",q->{
                long generation=q.status().generation();Node a=q.node(target).orElseThrow();Node b=q.node(target).orElseThrow();
                if(!a.equals(b)||q.status().generation()!=generation)throw new AssertionError("mixed-generation read");return null;});}));
            for(int i=0;i<20;i++){Node old=engine.query("p0",q->q.node(target).orElseThrow());long gen=engine.query("p0",q->q.status().generation());
                Node next=new Node(old.id(),old.kind(),old.name(),"generation "+(gen+1),old.span(),old.metrics(),old.attrs());
                engine.apply("p0",new GraphDelta(gen+1,List.of(),List.of(next),List.of(),List.of()));}
            for(Future<?> future:readers)future.get(30,TimeUnit.SECONDS);
        }
    }
    static long diskBytes(Path root)throws IOException {try(var paths=Files.walk(root)){return paths.filter(Files::isRegularFile).mapToLong(p->{try{return Files.size(p);}catch(IOException e){throw new java.io.UncheckedIOException(e);}}).sum();}}
    static void cleanup(Path root)throws IOException {
        Path tempRoot=Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path checked=root.toRealPath();
        if(!checked.getParent().equals(tempRoot)||!checked.getFileName().toString().startsWith("code-graph-storage-bench-"))throw new IOException("refusing unsafe cleanup: "+checked);
        Files.walkFileTree(checked,new SimpleFileVisitor<>(){
            public FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException{Files.delete(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
}
