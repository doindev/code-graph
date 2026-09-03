package io.doindev.codegraph.bench;

import io.doindev.codegraph.model.*;
import io.doindev.codegraph.parse.*;
import io.doindev.codegraph.index.Analyzers;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/** Two-pass experiment: file-sized fragments on disk, bounded extraction queue and resolver lookup. */
final class Ingestion {
    static final Set<String> EXCLUDED = Set.of(".git",".hg",".svn","node_modules","target","build","dist","out",
            ".venv","venv",".idea",".vscode",".code-graph","vendor","bin","obj",".agents",".claude");
    static Node syntheticNode(int i) {
        String path="src/p"+(i%500)+"/F"+(i/500)+".java";
        SymbolId id=new SymbolId("java",path,"C"+(i/20)+".m"+i,1);
        return new Node(id,NodeKind.FUNCTION,"m"+i,"void m"+i+"(int)",new SourceSpan(path,1,1,5,1),Metrics.NONE,Map.of());
    }
    static void synthetic(Engine.Loader loader,int nodes,int edges,long seed) {
        for(int i=0;i<nodes;i++)loader.node(syntheticNode(i));
        Random random=new Random(seed);
        for(int i=0;i<edges;i++)loader.edge(new Edge(syntheticNode(random.nextInt(nodes)).id(),syntheticNode(random.nextInt(nodes)).id(),
                i%7==0?EdgeKind.REFERENCES:EdgeKind.CALLS,i%5==0?0.5f:1f,Map.of()));
    }
    final List<FileFragment> retainedFragments = new ArrayList<>();
    SymbolTable retainedTable;
    long fragmentBytes, declarations, rawRefs, files, maxFragmentBytes, pending;
    long maxResolverCandidates;
    final Analyzers analyzers=Analyzers.discover();

    void repository(Path root,Engine.Loader loader,boolean disk) {
        final int workers=2, queueLimit=8;
        var queue=new ArrayDeque<Future<FileFragment>>();
        try(var executor=Executors.newFixedThreadPool(workers)) {
            Files.walkFileTree(root,new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs){
                    return !dir.equals(root)&&EXCLUDED.contains(dir.getFileName().toString())?FileVisitResult.SKIP_SUBTREE:FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs){
                    String rel=root.relativize(file).toString().replace('\\','/');
                    // Fixed workload: source languages supported by the installed production analyzers.
                    LanguageAnalyzer analyzer=analyzers.forPath(rel);
                    if(analyzer!=null&&attrs.size()<=2L*1024*1024) {
                        queue.add(executor.submit(()->analyzer.extract(new SourceFile(rel,analyzer.languageId(),Files.readString(file)))));
                        if(queue.size()>=queueLimit)consume(queue.remove(),loader,disk);
                    }return FileVisitResult.CONTINUE;
                }
            });
            while(!queue.isEmpty())consume(queue.remove(),loader,disk);
        } catch(IOException e){throw new UncheckedIOException(e);}
        if(!disk) {
            retainedTable=SymbolTable.of(retainedFragments);
            for(FileFragment fragment:retainedFragments)resolve(loader,fragment,retainedTable,false);
        } else {
            for(long i=0;i<files;i++) {
                FileFragment fragment=Codec.decode(loader.auxiliary("fragment/"+Engine.seq(i)),Codec.F.class).fragment();
                // Query only the names referenced by this fragment; preserve declaration insertion order.
                Set<String> names=new HashSet<>();fragment.rawRefs().forEach(r->names.add("simple/"+Engine.enc(r.name())+"/"));
                fragment.imports().forEach(s->names.add("qualified/"+Engine.enc(s.replace("::",".").replace('/','.'))+"/"));
                TreeMap<String,Node> candidates=new TreeMap<>();
                for(String name:names)loader.scanAuxiliary(name,(k,v)->{
                    String ordinal=k.substring(k.lastIndexOf('/')+1);candidates.put(ordinal,Codec.node(v));
                    if(candidates.size()>10_000)throw new IllegalStateException("resolver candidate set exceeds prototype bound of 10,000");return true;});
                maxResolverCandidates=Math.max(maxResolverCandidates,candidates.size());
                var lookupFragment=new FileFragment(fragment.file(),fragment.lang(),"",List.copyOf(candidates.values()),List.of(),List.of(),List.of());
                resolve(loader,fragment,SymbolTable.of(List.of(lookupFragment)),true);
            }
        }
    }
    private void consume(Future<FileFragment> future,Engine.Loader loader,boolean disk) {
        FileFragment f;
        try {f=future.get();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        catch(ExecutionException e){throw new IllegalStateException("file extraction failed",e.getCause());}
        byte[] encoded=Codec.encode(new Codec.F(f));fragmentBytes+=encoded.length;maxFragmentBytes=Math.max(maxFragmentBytes,encoded.length);
        if(disk)loader.auxiliary("fragment/"+Engine.seq(files),encoded);else retainedFragments.add(f);
        files++;rawRefs+=f.rawRefs().size();
        for(Node n:f.declarations()) {
            loader.node(n);
            if(disk&&n.id() instanceof SymbolId id){byte[] v=Codec.node(n);String ordinal=Engine.seq(declarations);
                loader.auxiliary("simple/"+Engine.enc(n.name())+"/"+ordinal,v);
                loader.auxiliary("qualified/"+Engine.enc(id.qualifiedName())+"/"+ordinal,v);}
            declarations++;
        }
        f.localEdges().forEach(loader::edge);
    }
    private void resolve(Engine.Loader loader,FileFragment fragment,SymbolTable table,boolean disk){
        List<RawRef> unresolved=new ArrayList<>();List<Edge> edges=NameResolver.resolve(fragment,table,unresolved);
        edges.forEach(loader::edge);pending+=unresolved.size();
        if(disk){
            loader.auxiliary("resolved/"+Engine.enc(fragment.file().relPath()),Codec.encode(edges.stream().map(Codec.E::new).toList()));
            loader.auxiliary("pending/"+Engine.enc(fragment.file().relPath()),Codec.encode(unresolved.stream().map(Codec.R::new).toList()));
        }
    }
    Map<String,Object> stats(){return Map.of("files",files,"declarations",declarations,"rawReferences",rawRefs,
            "serializedFragmentBytes",fragmentBytes,"maxSerializedFragmentBytes",maxFragmentBytes,"pendingReferences",pending,
            "maxResolverCandidates",maxResolverCandidates,"extractionWorkers",2,"maxInFlightFragments",8,
            "memoryRetainedFragments",retainedFragments.size());}
}
