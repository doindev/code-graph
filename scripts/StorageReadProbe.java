import io.doindev.codegraph.model.*;
import io.doindev.codegraph.storage.RecordCodec;
import org.h2.mvstore.*;
import org.h2.mvstore.type.*;
import java.nio.file.*;
import java.util.*;

/** Controlled diagnostic only: same records/version semantics, vary channel and page split target. */
public class StorageReadProbe {
    record Variant(String channel,int split) {}
    public static void main(String[] args) throws Exception {
        var variants=List.of(new Variant("",4096),new Variant("async:",4096),
                new Variant("async:",16384),new Variant("async:",32768),new Variant("async:",65536));
        var results=new ArrayList<Map<String,Object>>();
        for(int run=0;run<3;run++)for(int j=0;j<variants.size();j++){
            var variant=variants.get((j+run*2)%variants.size());
            Path directory=Files.createTempDirectory("cgraph-page-probe-"),file=directory.resolve("owned.mv");
            var row=new LinkedHashMap<String,Object>();
            row.put("run",run+1);row.put("channel",variant.channel().isEmpty()?"sync":"async");row.put("splitBytes",variant.split());
            try(var store=new MVStore.Builder().fileName(variant.channel()+file).cacheSize(0)
                    .compress().autoCommitDisabled().pageSplitSize(variant.split()).open()){
                store.setRetentionTime(0);store.setVersionsToKeep(1);
                var writer=store.openMap("records",new MVMap.Builder<String,byte[]>()
                        .keyType(StringDataType.INSTANCE).valueType(ByteArrayDataType.INSTANCE));
                long start=System.nanoTime(),peakDirty=0;
                for(int n=0;n<6563;n++){
                    String name="someMethod"+n,path="src/main/java/com/example/namespace/Service"+n%100+".java";
                    Node node=new Node(new SymbolId("java",path,"com.example.namespace.Service"+n%100+"."+name,2),
                            NodeKind.FUNCTION,name,"public String "+name+"(String first, Integer second)",
                            new SourceSpan(path,n+1,1,n+5,10),Metrics.NONE,
                            Map.of("returnType","java.lang.String","qualifiedType","com.example.namespace.Service"+n%100,
                                    "declarationSpan","fixture","resolverEvidence","deterministic resolved receiver context"));
                    writer.put("n/"+String.format(Locale.ROOT,"%08d",n),RecordCodec.node(node));
                    peakDirty=Math.max(peakDirty,store.getUnsavedMemory());
                    if(store.getUnsavedMemory()>=1<<20)store.commit();
                }
                var lease=store.registerVersionUsage();store.commit();
                var map=writer.openVersion(lease.version);
                row.put("buildMs",(System.nanoTime()-start)/1e6);row.put("peakDirtyBytesEstimate",peakDirty);
                var scans=new ArrayList<Double>();var point=new ArrayList<Double>();
                long reads=store.getFileStore().getReadCount(),bytes=store.getFileStore().getReadBytes();
                for(int round=0;round<15;round++){
                    start=System.nanoTime();int count=0;
                    var cursor=map.cursor("n/");
                    while(cursor.hasNext()){
                        cursor.next();var node=RecordCodec.node(cursor.getValue());
                        if(node.name().contains("someMethod"))count++;
                    }
                    if(count!=6563)throw new AssertionError("scan mismatch");
                    if(round>=3)scans.add((System.nanoTime()-start)/1e6);
                }
                row.put("readsPerScan",(store.getFileStore().getReadCount()-reads)/15d);
                row.put("readBytesPerScan",(store.getFileStore().getReadBytes()-bytes)/15d);
                for(int n=0;n<120;n++){
                    String key="n/"+String.format(Locale.ROOT,"%08d",(n*541)%6563);
                    start=System.nanoTime();
                    if(RecordCodec.node(map.get(key))==null)throw new AssertionError();
                    if(n>=20)point.add((System.nanoTime()-start)/1e6);
                }
                row.put("scanSamplesMs",scans);row.put("uncachedPointSamplesMs",point);
                scans.sort(Double::compare);point.sort(Double::compare);
                row.put("scanP50Ms",scans.get(5));row.put("scanP95Ms",scans.get(11));
                row.put("uncachedPointP50Ms",point.get(49));row.put("diskBytes",Files.size(file));
                store.deregisterVersionUsage(lease);
            }finally{Files.deleteIfExists(file);Files.delete(directory);}
            row.put("ownedDirectoryRemoved",!Files.exists(directory));results.add(row);
            System.err.println("probe "+row.get("run")+" "+row.get("channel")+" "+variant.split()+" scan p50="+row.get("scanP50Ms")+" point="+row.get("uncachedPointP50Ms"));
        }
        System.out.println(RecordCodec.JSON.writeValueAsString(results));
    }
}
