package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.util.function.LongSupplier;

/** Disposable, session-context-scoped read windows; never stores pending edits. */
final class GridPageCache implements AutoCloseable {
    static final long MAX_BYTES=16L<<20, TTL_MILLIS=60_000;
    static final int PER_CONTEXT=3;
    record Key(String context,long offset,int limit){}
    record Page(ObjectNode result,long offset,int limit,boolean more,long capturedAt){}
    private record Entry(Page page,long bytes,QueryJobs.RetainedReservation lease){}
    private final LinkedHashMap<Key,Entry> entries=new LinkedHashMap<>(16,.75f,true);
    private final QueryJobs jobs;
    private final LongSupplier now,budget;
    private long bytes,hits,misses;
    GridPageCache(QueryJobs jobs,LongSupplier budget){this(jobs,budget,System::currentTimeMillis);}
    GridPageCache(QueryJobs jobs,LongSupplier budget,LongSupplier now){this.jobs=jobs;this.budget=budget;this.now=now;}
    synchronized Page get(String context,long offset,int limit){
        reap();Entry entry=entries.get(new Key(context,offset,limit));
        if(entry==null){misses++;return null;}hits++;return entry.page;
    }
    synchronized void put(GridResults.Context context){
        if(context.disposed||context.orderedSql==null||context.uncertain)return;
        reap();Key key=new Key(context.id,context.offset,context.limit);remove(key);
        try{
            long charge=Profiles.JSON.writeValueAsBytes(context.result).length*3L+1024;
            long ceiling=Math.min(MAX_BYTES,budget.getAsLong()/8);
            if(charge>ceiling)return;
            while(entries.keySet().stream().filter(k->k.context.equals(context.id)).count()>=PER_CONTEXT)
                remove(entries.keySet().stream().filter(k->k.context.equals(context.id)).findFirst().orElseThrow());
            while(!entries.isEmpty()&&(bytes+charge>ceiling||jobs.availableRetainedBytes()<charge+(12L<<20)))remove(entries.keySet().iterator().next());
            if(jobs.availableRetainedBytes()<charge+(12L<<20))return; // Cache is optional; leave one JDBC job's admission headroom.
            var lease=jobs.retainAllowance(charge);
            entries.put(key,new Entry(new Page(context.result,context.offset,context.limit,context.hasMore,context.capturedAt),charge,lease));bytes+=charge;
        }catch(IllegalArgumentException unavailable){/* Never fail a successful read because caching cannot fit. */}
        catch(java.io.IOException invalid){/* A non-cacheable result remains the current page. */}
    }
    synchronized void removeContext(String id){for(Key key:new ArrayList<>(entries.keySet()))if(key.context.equals(id))remove(key);}
    synchronized void reap(){
        long cutoff=now.getAsLong()-TTL_MILLIS;
        for(var entry:new ArrayList<>(entries.entrySet()))if(entry.getValue().page.capturedAt<cutoff)remove(entry.getKey());
        long ceiling=Math.min(MAX_BYTES,budget.getAsLong()/8);
        while(!entries.isEmpty()&&(bytes>ceiling||jobs.availableRetainedBytes()<(12L<<20)))remove(entries.keySet().iterator().next());
    }
    private void remove(Key key){Entry e=entries.remove(key);if(e!=null){bytes-=e.bytes;e.lease.close();}}
    synchronized ObjectNode telemetry(){reap();return Profiles.JSON.createObjectNode().put("windows",entries.size()).put("accountedBytes",bytes).put("maximumBytes",Math.min(MAX_BYTES,budget.getAsLong()/8)).put("windowsPerGrid",PER_CONTEXT).put("ttlSeconds",TTL_MILLIS/1000).put("hits",hits).put("misses",misses);}
    public synchronized void close(){for(Key key:new ArrayList<>(entries.keySet()))remove(key);}
}
