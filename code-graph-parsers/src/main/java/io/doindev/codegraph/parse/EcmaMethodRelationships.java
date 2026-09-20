package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.util.*;

/** Explicit ES class/interface ancestry only; imports never fall back to unrelated global names. */
final class EcmaMethodRelationships {
    private static final Set<String> SIMPLE=Set.of("string","number","boolean","bigint","symbol","void","never","null","undefined");
    private final SymbolLookup lookup;
    private int work;
    EcmaMethodRelationships(SymbolLookup lookup){this.lookup=lookup;}
    List<Edge> resolve(FileFragment fragment){
        var edges=new ArrayList<Edge>();
        for(Node node:fragment.declarations()){
            if(node.kind()!=NodeKind.FUNCTION||!"true".equals(node.attrs().get("ecma.method"))||excluded(node.attrs()))continue;
            SymbolTable.Entry child=SymbolTable.Entry.of(node);work=0;var found=new LinkedHashMap<NodeId,Edge>();
            try{
                String owner=child.attrs().getOrDefault("ecma.owner","");
                var owners=local(owner,child);if(owners.size()!=1)continue;
                boolean javascript=fragment.lang().equals("js");
                List<String> signature=javascript?List.of():signature(child);
                if(signature==null)continue;
                var queue=new ArrayDeque<SymbolTable.Entry>(parents(owners.getFirst()));var seen=new HashSet<NodeId>();seen.add(owners.getFirst().id());
                while(!queue.isEmpty()){
                    check();var parent=queue.removeFirst();if(!seen.add(parent.id()))continue;
                    if(seen.size()>32)throw new Limit();
                    for(var base:qualified(parent.id().qualifiedName()+"."+node.name(),parent.id().lang())){
                        if(!base.id().relPath().equals(parent.id().relPath())||base.kind()!=NodeKind.FUNCTION||excluded(base.attrs()))continue;
                        if(!"true".equals(base.attrs().get("ecma.method")))continue;
                        if(!javascript){
                            if(!signature.equals(signature(base)))continue;
                            String actual=type(child.attrs().getOrDefault("ecma.return",""),child),
                                   expected=type(base.attrs().getOrDefault("ecma.return",""),base);
                            if(actual==null||!actual.equals(expected))continue;
                        }
                        var attrs=Map.of("resolution","ecma-explicit-ancestry","resolutionStatus","resolved",
                            "implementationKind","true".equals(parent.attrs().get("ecma.interface"))?"interface_method":"override",
                            "declaringType",owner,"baseType",parent.id().qualifiedName(),"referencePrecision","declaration",
                            "dispatch","static class declarations; prototype mutation and runtime replacement are not modeled");
                        found.put(base.id(),new Edge(child.id(),base.id(),EdgeKind.OVERRIDES,javascript?.9f:1f,attrs));
                    }
                    queue.addAll(parents(parent));
                }
                edges.addAll(found.values());
            }catch(Limit ignored){/* Do not publish an incomplete traversal as resolved evidence. */}
        }
        return edges;
    }
    private List<String> signature(SymbolTable.Entry entry){
        if("true".equals(entry.attrs().get("ecma.generic")))return null;
        String raw=entry.attrs().get("ecma.params");if(raw==null)return null;
        List<String> params=raw.isEmpty()?List.of():Arrays.asList(raw.split("\t",-1));
        if(params.size()!=entry.arity())return null;
        List<String> result=new ArrayList<>();
        for(String p:params){String t=type(p,entry);if(t==null)return null;result.add(t);}return result;
    }
    private String type(String raw,SymbolTable.Entry context){
        String name=raw.replaceAll("\\s+","");if(SIMPLE.contains(name))return name;
        if(name.endsWith("[]")){String element=type(name.substring(0,name.length()-2),context);return element==null?null:element+"[]";}
        SymbolTable.Entry target=resolveType(name,context);return target==null?null:target.id().value();
    }
    private List<SymbolTable.Entry> parents(SymbolTable.Entry entry){
        if("true".equals(entry.attrs().get("ecma.generic")))return List.of();
        String raw=entry.attrs().getOrDefault("ecma.parents","");if(raw.isBlank())return List.of();
        var result=new ArrayList<SymbolTable.Entry>();
        for(String parent:raw.split("\t")){var value=resolveType(parent,entry);if(value!=null)result.add(value);}
        return result;
    }
    private SymbolTable.Entry resolveType(String name,SymbolTable.Entry context){
        check();if(!name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*"))return null;
        String path=context.id().relPath();ModuleFile module=lookup.module(path);
        if(module==null)return null;
        int line=Integer.parseInt(context.attrs().getOrDefault("ecma.line","1")),column=Integer.parseInt(context.attrs().getOrDefault("ecma.column","1"));
        var location=new SourceSpan(path,line,column,line,column);
        String shortName=name,receiver=null;if(name.contains(".")){int dot=name.lastIndexOf('.');shortName=name.substring(dot+1);receiver=name.substring(0,dot);}
        var ref=new RawRef(context.id(),RefKind.TYPE_REF,shortName,receiver,-1,location);
        var fragment=new FileFragment(new FileId(path),context.id().lang(),"",List.of(),List.of(),List.of(),List.of(),module.evidence());
        var imported=new EcmaModuleResolver(fragment,lookup).resolve(ref);
        if(imported.handled()){
            if(imported.edges().size()!=1||!"resolved".equals(imported.edges().getFirst().attrs().get("resolutionStatus")))return null;
            SymbolId id=(SymbolId)imported.edges().getFirst().to();
            var exact=qualified(id.qualifiedName(),id.lang()).stream().filter(e->e.id().equals(id)&&e.kind()==NodeKind.TYPE).toList();
            return exact.size()==1?exact.getFirst():null;
        }
        String owner=context.attrs().getOrDefault("ecma.owner","");
        while(!owner.isBlank()){
            var nested=local(owner+"."+name,context);if(!nested.isEmpty())return nested.size()==1?nested.getFirst():null;
            int dot=owner.lastIndexOf('.');owner=dot<0?"":owner.substring(0,dot);
        }
        var sameFile=local(name,context);return sameFile.size()==1?sameFile.getFirst():null;
    }
    private List<SymbolTable.Entry> local(String name,SymbolTable.Entry context){
        return qualified(name,context.id().lang()).stream().filter(e->e.kind()==NodeKind.TYPE&&e.id().relPath().equals(context.id().relPath())).toList();
    }
    private List<SymbolTable.Entry> qualified(String name,String language){check();return lookup.byQualifiedName(name,language);}
    private void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Method resolution cancelled");if(++work>256)throw new Limit();}
    private static boolean excluded(Map<String,String> attrs){return "true".equals(attrs.get("ecma.excluded"));}
    private static final class Limit extends RuntimeException {Limit(){super(null,null,false,false);}}
}
