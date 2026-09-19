import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.index.*;
import io.doindev.codegraph.storage.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.mvstore.*;
import java.nio.file.*;
import java.util.*;

/** Diagnostic only: introspects its OWN disposable store, never a running server. */
public class InspectHybridResidency {
    static Object field(Object object,String name)throws Exception {
        var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    static Map<String,Object> stats(GraphStorage storage,MVStore store) {
        var info=new TreeMap<String,String>();store.populateInfo(info::put);
        return Map.of("storage",storage.status(),"fillRate",store.getFillRate(),"engine",info);
    }
    public static void main(String[] args)throws Exception {
        var json=new ObjectMapper();var report=new LinkedHashMap<String,Object>();Path owned;
        try(var storage=new GraphStorage(true,32L<<20)) {
            owned=storage.directory();var graph=(PagedGraph)storage.create();
            long start=System.nanoTime();
            new IncrementalIndexer(Path.of(args[0]),Analyzers.discover(),CodeGraphConfig.defaults(),graph).fullIndex();
            report.put("indexMs",(System.nanoTime()-start)/1e6);
            MVStore store=(MVStore)field(field(graph,"active"),"store");
            report.put("before",stats(storage,store));
            var records=new TreeMap<String,long[]>();
            graph.documents("",(key,value)->{
                String prefix=key.split("/",3)[0];
                if(prefix.equals("resolution"))prefix=key.substring(0,key.indexOf('/',11));
                long[] counts=records.computeIfAbsent(prefix,k->new long[3]);
                counts[0]++;counts[1]+=key.length();counts[2]+=value.length;
            });
            report.put("auxiliaryCountKeyCharsValueBytes",records);
            start=System.nanoTime();store.compactFile(2000);
            report.put("compactMs",(System.nanoTime()-start)/1e6);report.put("after",stats(storage,store));
            int[] count={0};graph.scanNodes(null,n->count[0]++);report.put("nodesAfter",count[0]);
        }
        report.put("ownedStoreRemoved",!Files.exists(owned));
        System.out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
