package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.util.*;

/** Bounded Python C3 and literal Ruby include chains. Unknown ancestors invalidate the chain. */
final class DynamicMethodRelationships {
    private final SymbolLookup lookup;private final String language;private int work;
    DynamicMethodRelationships(SymbolLookup lookup,String language){this.lookup=lookup;this.language=language;}
    List<Edge> resolve(FileFragment fragment){
        var result=new ArrayList<Edge>();
        for(Node node:fragment.declarations()){
            if(node.kind()!=NodeKind.FUNCTION||!"true".equals(node.attrs().get("dynamic.method"))||uncertain(SymbolTable.Entry.of(node)))continue;
            if(language.equals("py")&&node.name().startsWith("__")&&!node.name().endsWith("__"))continue;
            work=0;
            try{
                var child=SymbolTable.Entry.of(node);var owner=type(child.attrs().get("dynamic.owner"),child);
                List<SymbolTable.Entry> order=linearize(owner,new HashSet<>());
                for(int i=1;i<order.size();i++){
                    var parent=order.get(i);var matches=qualified(parent.id().qualifiedName()+"."+node.name()).stream()
                        .filter(e->e.kind()==NodeKind.FUNCTION&&e.id().relPath().equals(parent.id().relPath())).toList();
                    if(matches.isEmpty())continue;
                    if(matches.size()!=1||uncertain(matches.getFirst()))break;
                    var base=matches.getFirst();result.add(new Edge(child.id(),base.id(),EdgeKind.OVERRIDES,.9f,Map.of(
                        "resolution",language.equals("py")?"python-c3-method":"ruby-literal-ancestors","resolutionStatus","resolved",
                        "implementationKind","override","declaringType",owner.id().qualifiedName(),"baseType",parent.id().qualifiedName(),
                        "referencePrecision","declaration","dispatch","Static declaration ancestry only; runtime mutation, imports and metaprogramming remain incomplete")));
                    break;
                }
            }catch(Unknown ignored){/* No partial or invented ancestry. */}
        }
        return result;
    }
    private List<SymbolTable.Entry> linearize(SymbolTable.Entry current,Set<NodeId> active){
        if(uncertain(current)||active.size()>=32||!active.add(current.id()))throw new Unknown();
        var parents=new ArrayList<SymbolTable.Entry>();String raw=current.attrs().getOrDefault("dynamic.parents","");
        if(!raw.isEmpty())for(String name:raw.split("\t"))parents.add(type(name,current));
        var sequences=new ArrayList<List<SymbolTable.Entry>>();
        for(var parent:parents)sequences.add(new ArrayList<>(linearize(parent,active)));
        active.remove(current.id());var result=new ArrayList<SymbolTable.Entry>();result.add(current);
        if(language.equals("rb")){
            for(var sequence:sequences)for(var entry:sequence)if(result.stream().noneMatch(e->e.id().equals(entry.id())))result.add(entry);
        }else{
            sequences.add(new ArrayList<>(parents));
            while(sequences.stream().anyMatch(s->!s.isEmpty())){
                tick();SymbolTable.Entry next=null;
                for(var sequence:sequences){
                    if(sequence.isEmpty())continue;var candidate=sequence.getFirst();boolean inTail=false;
                    for(var other:sequences)for(int i=1;i<other.size();i++)if(other.get(i).id().equals(candidate.id()))inTail=true;
                    if(!inTail){next=candidate;break;}
                }
                if(next==null)throw new Unknown();result.add(next);
                for(var sequence:sequences)if(!sequence.isEmpty()&&sequence.getFirst().id().equals(next.id()))sequence.removeFirst();
                if(result.size()>64)throw new Unknown();
            }
        }
        if(result.size()>64)throw new Unknown();return result;
    }
    private SymbolTable.Entry type(String name,SymbolTable.Entry context){
        if(name==null||!name.matches("[A-Za-z_][A-Za-z0-9_.]*"))throw new Unknown();
        String owner=context.attrs().getOrDefault("dynamic.owner","");
        while(!owner.isEmpty()){
            var nested=qualified(owner+"."+name).stream().filter(e->e.kind()==NodeKind.TYPE&&e.id().relPath().equals(context.id().relPath())).toList();
            if(!nested.isEmpty()){if(nested.size()!=1)throw new Unknown();return nested.getFirst();}
            int dot=owner.lastIndexOf('.');owner=dot<0?"":owner.substring(0,dot);
        }
        var candidates=qualified(name).stream().filter(e->e.kind()==NodeKind.TYPE&&e.id().relPath().equals(context.id().relPath())).toList();
        if(candidates.size()!=1)throw new Unknown();return candidates.getFirst();
    }
    private List<SymbolTable.Entry> qualified(String name){tick();var entries=lookup.byQualifiedName(name,language);work+=entries.size();if(work>512)throw new Unknown();return entries;}
    private void tick(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Method resolution cancelled");if(++work>512)throw new Unknown();}
    private static boolean uncertain(SymbolTable.Entry e){return "true".equals(e.attrs().get("dynamic.uncertain"));}
    private static final class Unknown extends RuntimeException {Unknown(){super(null,null,false,false);}}
}
