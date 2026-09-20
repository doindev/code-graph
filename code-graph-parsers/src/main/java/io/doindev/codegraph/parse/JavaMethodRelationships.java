package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.util.*;

/** Declaration relationships with bounded, instantiated generic ancestry; never erased-name guessing. */
final class JavaMethodRelationships {
    private record Instance(SymbolTable.Entry type,Map<String,String> variables) {}
    private final JavaCallResolver resolver;
    JavaMethodRelationships(FileFragment fragment,SymbolLookup lookup){resolver=new JavaCallResolver(fragment,lookup);}
    List<Edge> resolve(FileFragment fragment){
        var edges=new ArrayList<Edge>();
        for(Node node:fragment.declarations()){
            if(node.kind()!=NodeKind.FUNCTION)continue;var child=SymbolTable.Entry.of(node);
            if(excluded(child))continue;resolver.resetBudget();var found=new LinkedHashMap<NodeId,Edge>();
            try{
                String owner=child.attrs().getOrDefault("java.owner","");var own=type(owner);if(own==null)continue;
                var variables=new LinkedHashMap<String,String>();for(String p:split(own.attrs().get("java.parameters")))variables.put(p,"$"+own.id().value()+":"+p);
                var start=new Instance(own,Map.copyOf(variables));var signature=signature(child,start.variables);if(signature==null)continue;
                var queue=new ArrayDeque<Instance>(parents(start));var visited=new HashMap<NodeId,Map<String,String>>();visited.put(own.id(),start.variables);
                int states=0;
                while(!queue.isEmpty()){
                    if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Method resolution cancelled");
                    if(++states>64)throw new JavaCallResolver.ResolutionLimit();
                    var current=queue.removeFirst();var prior=visited.putIfAbsent(current.type.id(),current.variables);
                    if(prior!=null){if(!prior.equals(current.variables))throw new JavaCallResolver.ResolutionLimit();continue;}
                    var parent=current.type;if(flag(parent,"final"))continue;boolean contract=flag(parent,"interface");
                    for(var base:resolver.qualified(parent.id().qualifiedName()+"."+node.name())){
                        if(base.kind()!=NodeKind.FUNCTION||!base.id().relPath().equals(parent.id().relPath())||base.arity()!=child.arity()||excluded(base)||flag(base,"final"))continue;
                        String visibility=contract?"public":base.attrs().getOrDefault("java.visibility","package");
                        if(visibility.equals("package")&&!Objects.equals(child.attrs().get("java.package"),base.attrs().get("java.package")))continue;
                        String actualVisibility=flag(own,"interface")?"public":child.attrs().getOrDefault("java.visibility","package");
                        if(rank(actualVisibility)<rank(visibility))continue;
                        if(!signature.equals(signature(base,current.variables)))continue;
                        String actual=normalize(child.attrs().get("java.return"),child,start.variables,0),expected=normalize(base.attrs().get("java.return"),base,current.variables,0);
                        if(actual==null||expected==null||!actual.equals(expected)&&!covariant(actual,expected))continue;
                        found.put(base.id(),new Edge(child.id(),base.id(),EdgeKind.OVERRIDES,1f,Map.of(
                            "resolution","java-instantiated-method-signature","resolutionStatus","resolved","implementationKind",contract?"interface_method":"override",
                            "declaringType",owner,"baseType",parent.id().qualifiedName(),"referencePrecision","declaration",
                            "dispatch","Verified declaration relationship; wildcard/method-generic substitutions and runtime dispatch remain incomplete")));
                    }
                    queue.addAll(parents(current));
                }
                edges.addAll(found.values());
            }catch(JavaCallResolver.ResolutionLimit ignored){/* Never publish partial evidence. */}
        }
        return edges;
    }
    private boolean covariant(String actual,String expected){
        if(actual.contains("<")||expected.contains("<")||actual.startsWith("$")||expected.startsWith("$"))return false;
        if(expected.equals("java.lang.Object")&&!Set.of("void","boolean","byte","short","char","int","long","float","double").contains(actual))return true;
        return resolver.inherits(actual,expected);
    }
    private List<Instance> parents(Instance instance){
        var result=new ArrayList<Instance>();
        for(String raw:split(instance.type.attrs().get("java.parents"))){
            String erased=JavaCallResolver.erase(raw);var parent=type(resolver.declaredType(erased,instance.type));if(parent==null)continue;
            List<String> names=split(parent.attrs().get("java.parameters"));List<String> arguments=arguments(raw);
            if(arguments==null||arguments.size()!=names.size())continue; // Raw generic inheritance is not an instantiated path.
            var variables=new LinkedHashMap<String,String>();boolean complete=true;
            for(int i=0;i<names.size();i++){
                String value=normalize(arguments.get(i),instance.type,instance.variables,0);if(value==null){complete=false;break;}variables.put(names.get(i),value);
            }
            if(complete)result.add(new Instance(parent,Map.copyOf(variables)));
        }
        return result;
    }
    private List<String> signature(SymbolTable.Entry entry,Map<String,String> variables){
        if(flag(entry,"methodTypeVariables"))return null;
        List<String> raw=split(entry.attrs().get("java.params"));if(raw.size()!=entry.arity()||raw.size()>64)return null;
        var normalized=new ArrayList<String>();for(String p:raw){String value=normalize(p,entry,variables,0);if(value==null)return null;normalized.add(value);}return normalized;
    }
    private String normalize(String text,SymbolTable.Entry entry,Map<String,String> variables,int depth){
        if(text==null||text.length()>2048||depth>8)return null;String raw=text.replaceAll("\\s+","");
        if(raw.isEmpty()||raw.contains("?")||raw.contains("@")||raw.contains("&"))return null;
        if(raw.endsWith("[]")){String value=normalize(raw.substring(0,raw.length()-2),entry,variables,depth+1);return value==null?null:value+"[]";}
        if(variables.containsKey(raw))return variables.get(raw);
        for(String p:split(entry.attrs().get("java.typevars")))if(p.startsWith(raw+"="))return null;
        List<String> arguments=arguments(raw);if(arguments==null)return null;
        String base=resolver.declaredType(JavaCallResolver.erase(raw),entry);if(base==null||base.startsWith("?")||base.isEmpty())return null;
        if(arguments.isEmpty())return base;
        var values=new ArrayList<String>();for(String p:arguments){String value=normalize(p,entry,variables,depth+1);if(value==null)return null;values.add(value);}
        return base+"<"+String.join(",",values)+">";
    }
    private static List<String> arguments(String raw){
        int open=raw.indexOf('<');if(open<0)return List.of();if(!raw.endsWith(">"))return null;
        var values=new ArrayList<String>();int depth=0,start=open+1;
        for(int i=start;i<raw.length()-1;i++){
            char c=raw.charAt(i);if(c=='<')depth++;else if(c=='>')depth--;else if(c==','&&depth==0){values.add(raw.substring(start,i));start=i+1;}
            if(depth<0||depth>8||values.size()>32)return null;
        }
        if(depth!=0)return null;values.add(raw.substring(start,raw.length()-1));return values;
    }
    private SymbolTable.Entry type(String name){var types=resolver.qualified(name).stream().filter(e->e.kind()==NodeKind.TYPE).toList();return types.size()==1?types.getFirst():null;}
    private static int rank(String visibility){return switch(visibility){case "public"->3;case "protected"->2;case "package"->1;default->0;};}
    private static boolean excluded(SymbolTable.Entry entry){return flag(entry,"constructor")||flag(entry,"private")||flag(entry,"static")||flag(entry,"methodTypeVariables");}
    private static boolean flag(SymbolTable.Entry entry,String key){return "true".equals(entry.attrs().get("java."+key));}
    private static List<String> split(String value){return value==null||value.isEmpty()?List.of():Arrays.asList(value.split("\t",-1));}
}
