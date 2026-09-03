package io.doindev.codegraph.bench;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.ObjectName;

final class Telemetry implements AutoCloseable {
    private final AtomicLong peakHeap=new AtomicLong();
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"bench-memory");t.setDaemon(true);return t;});
    Telemetry(){sampler.scheduleAtFixedRate(()->peakHeap.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,20,TimeUnit.MILLISECONDS);}
    Map<String,Object> sample(){
        var s=new LinkedHashMap<String,Object>();var memory=ManagementFactory.getMemoryMXBean();
        s.put("heapUsedBytes",memory.getHeapMemoryUsage().getUsed());s.put("heapCommittedBytes",memory.getHeapMemoryUsage().getCommitted());
        s.put("heapMaxBytes",memory.getHeapMemoryUsage().getMax());s.put("peakSampledHeapBytes",peakHeap.get());
        s.put("nonHeapUsedBytes",memory.getNonHeapMemoryUsage().getUsed());
        long gcCount=0,gcMs=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){gcCount+=Math.max(0,gc.getCollectionCount());gcMs+=Math.max(0,gc.getCollectionTime());}
        s.put("gcCount",gcCount);s.put("gcTimeMs",gcMs);
        var pools=new LinkedHashMap<String,Long>();for(var pool:ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class))pools.put(pool.getName(),pool.getMemoryUsed());
        s.put("bufferPoolsBytes",pools);
        try {Object nmt=ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "vmNativeMemory",new Object[]{new String[]{"summary","scale=KB"}},new String[]{"[Ljava.lang.String;"});s.put("jdkNativeMemoryTracking",nmt);}
        catch(Exception e){s.put("jdkNativeMemoryTracking",null);}
        s.put("nativeMemoryNote","JDK NMT does not account for every third-party native allocation; RocksDB cache/memtable stats and external process samples are separate.");
        return s;
    }
    Map<String,Object> retainedSample() {
        // Diagnostic full GC is outside timed phases. Histogram sizes are SHALLOW, not a dominator tree.
        var result = new LinkedHashMap<String,Object>();
        try {
            Object histogram=ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram",new Object[]{new String[0]},new String[]{"[Ljava.lang.String;"});
            result.put("liveClassHistogram",histogram);
            result.put("histogramNote","Live objects after diagnostic full GC; per-class bytes are shallow and cannot attribute all retained graph ownership.");
        } catch(Exception e) { result.put("histogramUnavailable",e.toString()); }
        result.put("memory",sample());
        return result;
    }
    static Map<String,Object> distribution(long[] ns){
        long sum=0;for(long n:ns)sum+=n;long[] sorted=ns.clone();Arrays.sort(sorted);
        return Map.of("samples",ns.length,"p50Us",percentile(sorted,.50)/1000.0,"p95Us",percentile(sorted,.95)/1000.0,
                "p99Us",percentile(sorted,.99)/1000.0,"operationsPerSecond",ns.length*1e9/Math.max(1,sum),"rawNanoseconds",ns);
    }
    private static long percentile(long[] n,double p){return n[Math.max(0,(int)Math.ceil(n.length*p)-1)];}
    public void close(){sampler.shutdownNow();}
}
