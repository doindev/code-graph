package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.util.*;

/** Structural interface evidence requires every declared method, not a matching name alone. */
final class GoMethodRelationships {
    private static final Set<String> BUILTINS=Set.of("bool","byte","rune","string","int","int8","int16","int32","int64","uint","uint8","uint16","uint32","uint64","uintptr","float32","float64","complex64","complex128","error","any");
    private final SymbolLookup lookup;private int work;
    GoMethodRelationships(SymbolLookup lookup){this.lookup=lookup;}
    List<Edge> resolve(FileFragment fragment){
        var result=new ArrayList<Edge>();
        for(Node node:fragment.declarations()){
            if(node.kind()!=NodeKind.FUNCTION||!node.attrs().containsKey("go.receiver"))continue;
            work=0;var found=new ArrayList<Edge>();
            try{
                var child=SymbolTable.Entry.of(node);String receiver=receiver(child);if(receiver==null)continue;
                if(type(receiver,child)==null)continue;
                for(var candidate:simple(node.name())){
                    if(!"true".equals(candidate.attrs().get("go.contract")))continue;
                    var contract=type(candidate.attrs().getOrDefault("go.owner",""),candidate);if(contract==null)continue;
                    String names=contract.attrs().getOrDefault("go.requirements","?");if(names.equals("?")||names.isEmpty())continue;
                    boolean matches=true,pointer=false;
                    for(String name:names.split("\t")){
                        if(!Character.isUpperCase(name.codePointAt(0))&&!samePackage(child,contract)){matches=false;break;}
                        var required=qualified(contract.id().qualifiedName()+"."+name).stream().filter(e->e.kind()==NodeKind.FUNCTION&&e.id().relPath().equals(contract.id().relPath())).toList();
                        var actual=simple(name).stream().filter(e->e.kind()==NodeKind.FUNCTION&&receiver.equals(receiver(e))&&samePackage(child,e)).toList();
                        if(required.size()!=1||actual.size()!=1||!compatible(actual.getFirst(),required.getFirst())){matches=false;break;}
                        pointer|=actual.getFirst().attrs().getOrDefault("go.receiver","").strip().startsWith("*");
                    }
                    if(matches)found.add(new Edge(child.id(),candidate.id(),EdgeKind.OVERRIDES,1f,Map.of(
                        "resolution","go-complete-declared-method-set","resolutionStatus","resolved","implementationKind","interface_method",
                        "declaringType",(pointer?"*":"")+child.attrs().get("go.package")+"."+receiver,"baseType",contract.id().qualifiedName(),
                        "referencePrecision","declaration","dispatch","Verified explicit method set; embedded methods, type sets, aliases and build constraints are not modeled")));
                }
                result.addAll(found);
            }catch(Unknown ignored){/* Discard partial candidates when bounded work is exhausted. */}
        }
        return result;
    }
    private boolean compatible(SymbolTable.Entry actual,SymbolTable.Entry expected){
        String a=signature(actual),b=signature(expected);return a!=null&&a.equals(b);
    }
    private String signature(SymbolTable.Entry entry){
        String raw=entry.attrs().get("go.signature");if(raw==null)return null;var result=new ArrayList<String>();
        for(String part:raw.split("[\t\n]",-1)){String type=normalized(part,entry);if(type==null)return null;result.add(type);}
        // Preserve parameter/result boundary as well as tuple arity.
        return raw.substring(0,raw.indexOf('\n')).split("\t",-1).length+":"+String.join("\t",result);
    }
    private String normalized(String raw,SymbolTable.Entry context){
        String value=raw.replaceAll("\\s+","");if(value.isEmpty())return "";
        if(value.startsWith("[]")||value.startsWith("*")){int n=value.startsWith("[]")?2:1;String element=normalized(value.substring(n),context);return element==null?null:value.substring(0,n)+element;}
        if(value.startsWith("...")){String element=normalized(value.substring(3),context);return element==null?null:"..."+element;}
        if(BUILTINS.contains(value))return value.equals("byte")?"uint8":value.equals("rune")?"int32":value;
        var type=type(value,context);return type==null?null:type.id().value();
    }
    private SymbolTable.Entry type(String name,SymbolTable.Entry context){
        if(!name.matches("[A-Za-z_][A-Za-z0-9_.]*"))return null;
        String pkg=context.attrs().getOrDefault("go.package","");String qualified=name.startsWith(pkg+".")?name:pkg+"."+name;
        var candidates=qualified(qualified).stream().filter(e->e.kind()==NodeKind.TYPE&&samePackage(context,e)).toList();return candidates.size()==1?candidates.getFirst():null;
    }
    private static String receiver(SymbolTable.Entry entry){String value=entry.attrs().getOrDefault("go.receiver","").replaceAll("\\s+","");if(value.startsWith("*"))value=value.substring(1);return value.matches("[A-Za-z_][A-Za-z0-9_]*")?value:null;}
    private static boolean samePackage(SymbolTable.Entry a,SymbolTable.Entry b){return Objects.equals(a.attrs().get("go.package"),b.attrs().get("go.package"))&&directory(a).equals(directory(b));}
    private static String directory(SymbolTable.Entry entry){String path=entry.id().relPath();int slash=path.lastIndexOf('/');return slash<0?"":path.substring(0,slash);}
    private List<SymbolTable.Entry> simple(String name){return bounded(lookup.bySimpleName(name,"go"));}
    private List<SymbolTable.Entry> qualified(String name){return bounded(lookup.byQualifiedName(name,"go"));}
    private List<SymbolTable.Entry> bounded(List<SymbolTable.Entry> values){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Method resolution cancelled");work+=1+values.size();if(work>512)throw new Unknown();return values;}
    private static final class Unknown extends RuntimeException {Unknown(){super(null,null,false,false);}}
}
