package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.codec.ByteArrayCodec;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in, disposable-fixture measurements. Run in three fresh JVMs; not a
 * production benchmark or a claim of cold operating-system caches.
 */
class NativePerformanceTest {
    @TempDir Path root;
    private static final int WARMUP=12, SAMPLES=120;
    private static final long BUDGET=128L<<20;

    @Test @Timeout(180) void boundedNativeReadLatencyAndResidency() throws Exception {
        String engine=System.getenv("NATIVE_TEST_ENGINE"), port=System.getenv("NATIVE_TEST_PORT");
        String owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue("true".equals(System.getenv("NATIVE_TEST_PERFORMANCE"))
                && Set.of("mongodb","redis").contains(Objects.toString(engine,""))
                && port!=null && owner.startsWith("cgraph-native-"),"Owned performance fixture required");
        assertTrue(Runtime.getRuntime().maxMemory()<=256L<<20,"Run the resource gate with -Dtest.jvm.args=-Xmx256m -XX:MaxDirectMemorySize=64m; an uncapped JVM is not valid evidence");
        String database=engine.equals("mongodb")?"perf_"+UUID.randomUUID().toString().replace("-",""):"0";
        String prefix="perf:"+UUID.randomUUID()+":";
        ObjectNode report=Profiles.JSON.createObjectNode().put("engine",engine).put("run",System.getenv("NATIVE_TEST_RUN"))
                .put("fixtureOwner",owner).put("image",System.getenv("NATIVE_TEST_IMAGE"))
                .put("java",System.getProperty("java.version")).put("os",System.getProperty("os.name"))
                .put("processors",Runtime.getRuntime().availableProcessors()).put("heapMaxBytes",Runtime.getRuntime().maxMemory())
                .put("dbaAllowanceBytes",BUDGET).put("concurrency",2).put("warmup",WARMUP).put("samplesPerPath",SAMPLES)
                .put("mongoBatchDocuments",NativeReadExecutor.MONGO_BATCH_DOCUMENTS)
                .put("cacheState","Fresh JVM/client; database and operating-system caches are not flushed")
                .put("coverage","Bounded single-client read pipeline; no transport, user approval, topology, or graph-load certification");
        long gcCount=gc(false),gcMillis=gc(true);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);
            var jobs=new QueryJobs(jdbc,new DbaConfig(root,BUDGET,2,1000,100,15),ignored->true);
            var clients=new NativeConnections(profiles);var operations=new NativeOperations(profiles,jobs)){
            ObjectNode draft=Profiles.JSON.createObjectNode().put("name","Owned performance fixture")
                    .put("templateId",engine+"-native").put("url",engine+"://127.0.0.1:"+port).put("readOnly",true);
            draft.putObject("nativeOptions").put("database",database).put("maximumPoolSize",2);
            String id=profiles.put(null,draft).path("id").asText();
            ObjectNode input=Profiles.JSON.createObjectNode().put("connectionId",id).put("connectionName",draft.path("name").asText()).put("database",database);
            if(engine.equals("mongodb"))input.put("collection","items");
            NativeTarget target=NativeTarget.resolve(profiles.get(id),input);
            try(var lease=clients.acquire(id)){
                fixture(lease,target,prefix,true);
                try{
                    List<JsonNode> commands=commands(engine,prefix);
                    var direct=new Measurements();
                    for(int index=-WARMUP;index<SAMPLES;index++){
                        JsonNode command=commands.get(Math.floorMod(index,commands.size()));
                        long start=System.nanoTime();
                        JsonNode result=NativeReadExecutor.execute(lease,target,command,new NativeReadExecutor.Limits(100,1<<20,15),()->false);
                        verify(result);
                        if(index>=0)direct.add(start,result);
                    }
                    report.set("directAdapter",direct.json());
                    var managed=new Measurements();
                    long peakReservations=0,peakRetained=0,heapBefore=heap();
                    for(int index=-WARMUP;index<SAMPLES;index++){
                        input.set("command",commands.get(Math.floorMod(index,commands.size())));
                        long start=System.nanoTime();
                        ObjectNode reviewed=operations.prepare(input);
                        ObjectNode submitted=operations.submit("agent:benchmark",reviewed,input,()->{});
                        peakReservations=Math.max(peakReservations,jobs.telemetry().path("reservedBytes").asLong());
                        JsonNode completed=await(jobs,submitted.path("id").asText());
                        JsonNode result=completed.path("result");verify(result);
                        peakRetained=Math.max(peakRetained,jobs.telemetry().path("retainedResultBytes").asLong());
                        if(index== -WARMUP)report.put("firstManagedOperationMs",(System.nanoTime()-start)/1e6);
                        if(index>=0)managed.add(start,result);
                        jobs.remove("agent:benchmark",submitted.path("id").asText());
                        assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
                        assertEquals(0,operations.telemetry().path("activeLeases").asInt());
                    }
                    report.set("managedJob",managed.json());
                    report.put("heapUsedBeforeManagedBytes",heapBefore).put("heapUsedAfterManagedBytes",heap())
                            .put("peakAccountedJobReservationBytes",peakReservations).put("peakRetainedResultBytes",peakRetained);
                    assertTrue(peakReservations<=BUDGET);
                    operations.remove(id);
                    assertEquals(0,operations.telemetry().path("clients").asInt());
                    assertEquals(0,operations.telemetry().path("activeLeases").asInt());
                    report.set("afterReleaseJobs",jobs.telemetry());report.set("afterRemovalClients",operations.telemetry());
                }finally{fixture(lease,target,prefix,false);}
            }
        }
        report.put("gcCollections",gc(false)-gcCount).put("gcTimeMillis",gc(true)-gcMillis)
                .put("heapPoolPeakSumUpperBoundBytes",ManagementFactory.getMemoryPoolMXBeans().stream()
                        .filter(pool->pool.getType()==java.lang.management.MemoryType.HEAP)
                        .mapToLong(pool->Math.max(0,pool.getPeakUsage().getUsed())).sum());
        // Native/direct accounting is observational, not a total-RAM guarantee.
        var buffers=report.putArray("bufferPools");
        for(var pool:ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class))
            buffers.addObject().put("name",pool.getName()).put("count",pool.getCount()).put("memoryUsedBytes",pool.getMemoryUsed());
        report.put("processRss","Not measured by this harness; heap and direct buffers do not represent total process RAM")
                .put("queueWait","Included in managed latency; not independently instrumented")
                .put("diskIo","Not measured in this developer-workflow smoke benchmark");
        Path output=Path.of("target","native-performance",owner,engine+"-"+System.getenv("NATIVE_TEST_RUN")+".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output,Profiles.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("Native performance evidence: "+output.toAbsolutePath());
    }

    private static List<JsonNode> commands(String engine,String prefix)throws Exception{
        if(engine.equals("mongodb"))return List.of(
                Profiles.JSON.readTree("{\"find\":\"items\",\"filter\":{\"_id\":17}}"),
                Profiles.JSON.readTree("{\"find\":\"items\",\"filter\":{},\"projection\":{\"_id\":1,\"value\":1},\"limit\":100}"),
                Profiles.JSON.readTree("{\"aggregate\":\"items\",\"pipeline\":[{\"$match\":{\"group\":2}},{\"$project\":{\"value\":1}}]}"));
        return List.of(Profiles.JSON.valueToTree(new String[]{"GET",prefix+"17"}),
                Profiles.JSON.valueToTree(new String[]{"SCAN","0","MATCH",prefix+"*"}),
                Profiles.JSON.valueToTree(new String[]{"LRANGE",prefix+"list","0","99"}));
    }
    private static void fixture(NativeConnections.Lease lease,NativeTarget target,String prefix,boolean create){
        if(lease.mongo!=null){
            var database=lease.mongo.getDatabase(target.database());
            if(!create){database.drop();return;}
            var documents=new ArrayList<Document>();
            for(int i=0;i<160;i++)documents.add(new Document("_id",i).append("group",i%4).append("value","fixture-"+i+"-"+"x".repeat(256)));
            database.getCollection("items").insertMany(documents);return;
        }
        try(var connection=lease.redis.connect(ByteArrayCodec.INSTANCE)){
            var redis=connection.sync();redis.select(Integer.parseInt(target.database()));
            for(int i=0;i<160;i++){
                byte[] key=(prefix+i).getBytes(StandardCharsets.UTF_8);
                if(create)redis.set(key,("fixture-"+i+"-"+"x".repeat(256)).getBytes(StandardCharsets.UTF_8));
                else redis.del(key);
            }
            byte[] list=(prefix+"list").getBytes(StandardCharsets.UTF_8);
            if(create)for(int i=0;i<100;i++)redis.rpush(list,("item-"+i).getBytes(StandardCharsets.UTF_8));
            else redis.del(list);
        }
    }
    private static JsonNode await(QueryJobs jobs,String id)throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){
            JsonNode state=jobs.status("agent:benchmark",id);
            if(state.path("finished").asLong()>0){
                assertEquals("complete",state.path("state").asText(),state.toString());return state;
            }
            Thread.sleep(1);
        }
        jobs.cancelOwner("agent:benchmark");fail("Native benchmark job timed out");return null;
    }
    private static void verify(JsonNode result)throws Exception{
        assertTrue(result.path("rowCount").asInt()>0,result.toString());
        assertTrue(result.path("rowCount").asInt()<=100);
        assertTrue(Profiles.JSON.writeValueAsBytes(result).length<=1<<20);
    }
    private static long heap(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    private static long gc(boolean time){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean->Math.max(0,time?bean.getCollectionTime():bean.getCollectionCount())).sum();}
    private static final class Measurements{
        private final List<Double> millis=new ArrayList<>();
        private long bytes,maxBytes;
        void add(long start,JsonNode result)throws Exception{
            millis.add((System.nanoTime()-start)/1e6);
            long size=Profiles.JSON.writeValueAsBytes(result).length;bytes+=size;maxBytes=Math.max(maxBytes,size);
        }
        ObjectNode json(){
            var ordered=new ArrayList<>(millis);Collections.sort(ordered);
            double total=millis.stream().mapToDouble(Double::doubleValue).sum();
            ObjectNode value=Profiles.JSON.createObjectNode().put("p50ms",percentile(ordered,.5)).put("p95ms",percentile(ordered,.95))
                    .put("p99ms",percentile(ordered,.99)).put("operationsPerSecond",millis.size()*1000d/total)
                    .put("meanResultBytes",bytes/millis.size()).put("maxResultBytes",maxBytes);
            value.set("rawLatencyMs",Profiles.JSON.valueToTree(millis));return value;
        }
        private static double percentile(List<Double> values,double fraction){return values.get(Math.min(values.size()-1,(int)Math.ceil(fraction*values.size())-1));}
    }
}
