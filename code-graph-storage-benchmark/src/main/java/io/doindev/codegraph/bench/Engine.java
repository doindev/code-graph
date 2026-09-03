package io.doindev.codegraph.bench;

import io.doindev.codegraph.graph.InMemoryCodeGraph;
import io.doindev.codegraph.model.*;
import io.doindev.codegraph.query.*;
import io.doindev.codegraph.store.GraphDelta;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;

/** Experimental facade only. No changes to production GraphQuery or server wiring. */
final class Engine implements AutoCloseable {
    final Kv kv;
    private final Map<String, InMemoryCodeGraph> memory = new HashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private long budget;
    private long maxBatchBytes;
    private long submittedBytes;
    static final int BATCH_BYTES = 1 << 20;
    static final int UPDATE_BYTES = 16 << 20;
    private static final Runnable NO_FAILURE = () -> {};

    Engine(String backend, Path dir, long budget) {
        if (budget < (8L << 20) || budget % (1 << 20) != 0) throw new IllegalArgumentException("budget must be whole MiB, >= 8 MiB");
        this.budget = budget;
        kv = switch (backend) { case "memory" -> null; case "mvstore" -> new MvKv(dir,budget);
            case "rocksdb" -> new RocksKv(dir,budget); default -> throw new IllegalArgumentException("unknown backend: " + backend); };
    }
    static String enc(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    static String prefix(String project) { return "p/" + enc(project) + "/"; }
    static String seq(long n) { return String.format(Locale.ROOT, "%020d", n); }
    private static String nodeKey(String p, NodeId id) { return prefix(p) + "n/" + enc(id.value()); }
    private static String edgePrefix(String p, NodeId id, boolean out) { return prefix(p) + (out ? "o/" : "i/") + enc(id.value()) + "/"; }
    private static String edgeSuffix(Edge e, long n) { return String.format(Locale.ROOT,"%02d/",e.kind().ordinal()) + seq(n); }
    private static String metaKey(String p) { return prefix(p) + "meta"; }
    record Meta(long generation, long sequence, int nodes, long edges, int files, int symbols) {}
    private Meta meta(String p) {
        byte[] bytes = kv.get(metaKey(p));
        if (bytes == null) throw new IllegalArgumentException("unknown project: " + p);
        return Codec.decode(bytes, Meta.class);
    }
    <T> T query(String project, Function<GraphQuery,T> query) {
        lock.readLock().lock();
        try {
            GraphQuery graph = kv == null ? memory.get(project) : new Paged(project, meta(project));
            if (graph == null) throw new IllegalArgumentException("unknown project: " + project);
            return query.apply(graph);
        } finally { lock.readLock().unlock(); }
    }
    void scan(String project, Consumer<Node> visitor) {
        query(project, q -> { if (kv == null) q.allNodes(null).forEach(visitor);
            else kv.scan(prefix(project)+"n/", (k,v) -> { visitor.accept(Codec.node(v)); return true; }); return null; });
    }
    void load(String project, Consumer<Loader> producer) {
        lock.writeLock().lock();
        try {
            if (kv == null ? memory.containsKey(project) : kv.get(metaKey(project)) != null)
                throw new IllegalArgumentException("duplicate project");
            Loader loader = new Loader(project);
            try { producer.accept(loader); loader.finish(); }
            catch (RuntimeException | Error e) { if (kv != null) kv.removePrefix(prefix(project)); throw e; }
        } finally { lock.writeLock().unlock(); }
    }
    /** Bounded disk batches; memory baseline deliberately uses the production bulk-delta path. */
    final class Loader {
        private final String project;
        private final List<Node> nodes = kv == null ? new ArrayList<>() : null;
        private final List<Edge> edges = kv == null ? new ArrayList<>() : null;
        private final List<Kv.Put> batch = new ArrayList<>();
        private int bytes;
        private long sequence;
        Loader(String project) { this.project = project; }
        void put(String k, byte[] v) {
            int size = k.length()*2 + (v == null ? 0 : v.length);
            if (size > UPDATE_BYTES) throw new IllegalArgumentException("record exceeds 16 MiB prototype safety bound");
            if (bytes + size > BATCH_BYTES) flush();
            batch.add(new Kv.Put(k,v)); bytes += size;
        }
        void flush() {
            if (batch.isEmpty()) return;
            maxBatchBytes = Math.max(maxBatchBytes, bytes); submittedBytes += bytes;
            kv.write(batch, NO_FAILURE); batch.clear(); bytes = 0;
        }
        void node(Node n) {
            if (kv == null) nodes.add(n);
            else { put(nodeKey(project,n.id()),Codec.node(n)); if(n.relPath()!=null) put(prefix(project)+"f/"+enc(n.relPath()),new byte[0]); }
        }
        void edge(Edge e) {
            if (kv == null) edges.add(e);
            else {
                byte[] v = Codec.edge(e); String suffix = edgeSuffix(e,sequence++);
                put(edgePrefix(project,e.from(),true)+suffix,v); put(edgePrefix(project,e.to(),false)+suffix,v);
            }
        }
        void auxiliary(String key, byte[] value) { if(kv==null) throw new IllegalStateException("disk staging requires disk backend"); put(prefix(project)+"a/"+key,value); }
        byte[] auxiliary(String key) { flush(); return kv.get(prefix(project)+"a/"+key); }
        void scanAuxiliary(String key, Kv.Visitor visitor) { flush(); kv.scan(prefix(project)+"a/"+key,visitor); }
        void finish() {
            if (kv == null) {
                InMemoryCodeGraph graph = new InMemoryCodeGraph();
                graph.apply(new GraphDelta(1,List.of(),nodes,edges,List.of())); memory.put(project,graph);
            } else { flush(); put(metaKey(project),Codec.encode(count(project,1,sequence))); flush(); }
        }
    }
    private Meta count(String p, long generation, long sequence) {
        int[] n = {0,0,0}; long[] e = {0};
        kv.scan(prefix(p)+"n/", (k,v)->{ n[0]++; if(Codec.node(v).id() instanceof SymbolId)n[1]++; return true; });
        kv.scan(prefix(p)+"f/", (k,v)->{ n[2]++; return true; });
        kv.scan(prefix(p)+"o/", (k,v)->{ e[0]++; return true; });
        return new Meta(generation,sequence,n[0],e[0],n[2],n[1]);
    }
    void apply(String project, GraphDelta delta) { apply(project,delta,NO_FAILURE); }
    void apply(String project, GraphDelta delta, Runnable beforeCommit) {
        lock.writeLock().lock();
        try {
            if (kv == null) { beforeCommit.run(); Objects.requireNonNull(memory.get(project)).apply(delta); return; }
            Meta m = meta(project);
            if(delta.generation()<=m.generation()) throw new IllegalArgumentException("generation must increase");
            // Updates are atomic and bounded; oversized updates fail BEFORE modifying the store.
            var changes = new LinkedHashMap<String,byte[]>();
            long[] size = {0};
            java.util.function.BiConsumer<String,byte[]> put = (k,v)->{
                size[0] += k.length()*2L+(v==null?0:v.length);
                if(size[0]>UPDATE_BYTES)throw new IllegalArgumentException("atomic update exceeds 16 MiB prototype bound"); changes.put(k,v);
            };
            Set<String> removedPaths = new HashSet<>(); delta.removedFiles().forEach(f->removedPaths.add(f.relPath()));
            Set<NodeId> removedIds = new HashSet<>();
            int[] removedSymbols = {0};
            if(!removedPaths.isEmpty())kv.scan(prefix(project)+"n/",(k,v)->{
                Node n=Codec.node(v); if(removedPaths.contains(n.relPath())) { put.accept(k,null);removedIds.add(n.id());if(n.id() instanceof SymbolId)removedSymbols[0]++; }return true; });
            long[] deletedEdges={0};
            record EdgeIdentity(NodeId from,NodeId to,EdgeKind kind){}
            Set<EdgeIdentity> exact = new HashSet<>();delta.removeEdges().forEach(e->exact.add(new EdgeIdentity(e.from(),e.to(),e.kind())));
            if(!removedIds.isEmpty()||!exact.isEmpty())kv.scan(prefix(project)+"o/",(k,v)->{
                Edge e=Codec.edge(v); if(removedIds.contains(e.from())||removedIds.contains(e.to())||exact.contains(new EdgeIdentity(e.from(),e.to(),e.kind()))) {
                    put.accept(k,null); String suffix=k.substring(edgePrefix(project,e.from(),true).length());
                    put.accept(edgePrefix(project,e.to(),false)+suffix,null);deletedEdges[0]++; } return true; });
            Set<String> files=new HashSet<>(); delta.addNodes().forEach(n->{if(n.relPath()!=null)files.add(n.relPath());});
            int[] fileDelta={0};
            for(String path:removedPaths)if(kv.get(prefix(project)+"f/"+enc(path))!=null){put.accept(prefix(project)+"f/"+enc(path),null);fileDelta[0]--;}
            for(String path:files)if(kv.get(prefix(project)+"f/"+enc(path))==null||removedPaths.contains(path)){put.accept(prefix(project)+"f/"+enc(path),new byte[0]);fileDelta[0]++;}
            int added=0,symbols=0;
            Set<NodeId> seen=new HashSet<>();
            for(Node n:delta.addNodes()) {
                if(seen.add(n.id())&&(removedIds.contains(n.id())||kv.get(nodeKey(project,n.id()))==null)){added++;if(n.id() instanceof SymbolId)symbols++;}
                put.accept(nodeKey(project,n.id()),Codec.node(n));
            }
            long sequence=m.sequence();
            for(Edge e:delta.addEdges()) {String suffix=edgeSuffix(e,sequence++);byte[] v=Codec.edge(e);
                put.accept(edgePrefix(project,e.from(),true)+suffix,v);put.accept(edgePrefix(project,e.to(),false)+suffix,v);}
            put.accept(metaKey(project),Codec.encode(new Meta(delta.generation(),sequence,m.nodes()-removedIds.size()+added,
                    m.edges()-deletedEdges[0]+delta.addEdges().size(),m.files()+fileDelta[0],m.symbols()-removedSymbols[0]+symbols)));
            kv.write(changes.entrySet().stream().map(e->new Kv.Put(e.getKey(),e.getValue())).toList(),beforeCommit);
        } finally {lock.writeLock().unlock();}
    }
    void remove(String project) {
        lock.writeLock().lock();try {if(kv==null)memory.remove(project);else kv.removePrefix(prefix(project));}finally{lock.writeLock().unlock();}
    }
    void resize(long bytes) {
        if(bytes<(8L<<20)||bytes%(1<<20)!=0)throw new IllegalArgumentException("budget must be whole MiB, >=8 MiB");
        lock.writeLock().lock();try {if(kv==null)throw new UnsupportedOperationException("in-memory baseline has no eviction");kv.resize(bytes);budget=bytes;}finally{lock.writeLock().unlock();}
    }
    Map<String,Object> stats() {
        lock.readLock().lock();try {var s=new LinkedHashMap<String,Object>();if(kv!=null)s.putAll(kv.stats());
            s.put("requestedBudgetBytes",budget);s.put("maxSubmittedBatchBytes",maxBatchBytes);s.put("submittedBytes",submittedBytes);
            if(kv==null)s.put("budgetEnforced",false);
            else {
                long estimated=((Number)s.get("cacheUsedBytesEstimate")).longValue();
                for(String component:List.of("unsavedBytesEstimate","memtableBytes","tableReaderBytes"))
                    if(s.get(component) instanceof Number n)estimated+=n.longValue();
                s.put("accountedResidencyEstimateBytes",estimated);
                s.put("accountedEstimateOverBudgetBytes",Math.max(0,estimated-budget));
                s.put("accountingComplete",false); // engine metadata, JNI allocations and cursor/version pins are not fully exposed
            }
            return s;}finally{lock.readLock().unlock();}
    }
    public void close() {lock.writeLock().lock();try {memory.clear();if(kv!=null)kv.close();}finally{lock.writeLock().unlock();}}

    private final class Paged implements GraphQuery {
        private final String p; private final Meta m;
        Paged(String p,Meta m){this.p=p;this.m=m;}
        public Optional<Node> node(NodeId id){byte[] bytes=kv.get(nodeKey(p,id));return bytes==null?Optional.empty():Optional.of(Codec.node(bytes));}
        public List<Node> findSymbols(String pattern,Set<NodeKind> kinds,String lang,int limit){
            if(pattern==null||pattern.isBlank()||limit<=0)throw new IllegalArgumentException("nonblank pattern and positive limit required");
            String needle=pattern.toLowerCase();Comparator<Node> order=Comparator.comparing(n->n.id().value());
            PriorityQueue<Node> best=new PriorityQueue<>(order.reversed());
            kv.scan(prefix(p)+"n/",(k,v)->{Node n=Codec.node(v);
                boolean matches=n.name().toLowerCase().contains(needle)||(n.id() instanceof SymbolId s&&s.qualifiedName().toLowerCase().contains(needle));
                if(matches&&(kinds==null||kinds.isEmpty()||kinds.contains(n.kind()))&&(lang==null||lang.equals(n.lang()))){best.add(n);if(best.size()>limit)best.poll();}return true;});
            return best.stream().sorted(order).toList();
        }
        public List<Edge> edges(NodeId id,Direction direction,Set<EdgeKind> kinds){
            List<Edge> result=new ArrayList<>();
            if(direction!=Direction.IN)collect(id,true,kinds,result);
            if(direction!=Direction.OUT)collect(id,false,kinds,result);
            return result;
        }
        private void collect(NodeId id,boolean out,Set<EdgeKind> kinds,List<Edge> result){
            if(kinds==null||kinds.isEmpty())collectPrefix(edgePrefix(p,id,out),result);
            else for(EdgeKind kind:kinds)collectPrefix(edgePrefix(p,id,out)+String.format(Locale.ROOT,"%02d/",kind.ordinal()),result);
        }
        private void collectPrefix(String prefix,List<Edge> result){kv.scan(prefix,(k,v)->{
            if(result.size()>=100_000)throw new IllegalStateException("adjacency exceeds 100,000-edge prototype safety bound");result.add(Codec.edge(v));return true;});}
        public ClosureResult closure(NodeId start,Direction direction,Set<EdgeKind> kinds,int depth,int limit,float confidence){
            if(depth<1||limit<1)throw new IllegalArgumentException("positive depth and limit required");
            record Step(NodeId id,int depth){};
            var queue=new ArrayDeque<Step>();var visited=new HashSet<NodeId>();var hits=new ArrayList<ClosureHit>();
            queue.add(new Step(start,0));visited.add(start);
            while(!queue.isEmpty()) {Step cur=queue.remove();if(cur.depth()==depth)continue;
                for(Edge e:edges(cur.id(),direction,kinds)) {if(e.confidence()<confidence)continue;
                    NodeId next=e.from().equals(cur.id())?e.to():e.from();if(!visited.add(next))continue;
                    Node n=node(next).orElse(null);if(n==null)continue;
                    if(hits.size()>=limit)return new ClosureResult(hits,true);
                    hits.add(new ClosureHit(n,cur.depth()+1,e));queue.add(new Step(next,cur.depth()+1));}
            }return new ClosureResult(hits,false);
        }
        public List<Node> allNodes(Set<NodeKind> kinds){
            var result=new ArrayList<Node>();kv.scan(prefix(p)+"n/",(k,v)->{Node n=Codec.node(v);
                if(kinds==null||kinds.isEmpty()||kinds.contains(n.kind())){if(result.size()>=100_000)throw new IllegalStateException("use streaming scans above 100,000 nodes");result.add(n);}return true;});return result;
        }
        public IndexStatus status(){return new IndexStatus("ready",m.generation(),m.files(),m.symbols(),m.edges(),0,null,Map.of(),"experimental");}
    }
}
