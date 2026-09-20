package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.util.*;

/** Verified nominal ancestry plus adapter-specific dispatch rules; no global-name fallback. */
final class NominalMethodRelationships {
    private static final Map<String,Set<String>> BUILTINS=Map.of(
        "kt",Set.of("Unit","Boolean","Byte","Short","Int","Long","Float","Double","Char","String","Nothing"),
        "scala",Set.of("Unit","Boolean","Byte","Short","Int","Long","Float","Double","Char","String","Nothing"),
        "cs",Set.of("void","bool","byte","sbyte","short","ushort","int","uint","long","ulong","float","double","decimal","char","string","object"),
        "php",Set.of("void","bool","int","float","string","array","object","callable","iterable","mixed","never","null","false","true"),
        "swift",Set.of("Void","Bool","Int","Int8","Int16","Int32","Int64","UInt","UInt8","UInt16","UInt32","UInt64","Float","Double","String","Character"),
        "dart",Set.of("void","bool","int","double","num","String","Object","dynamic","Never"),
        "objc",Set.of("void","BOOL","int","char","short","long","float","double","id","NSInteger","NSUInteger"),
        "rs",Set.of("()","bool","char","str","u8","u16","u32","u64","u128","usize","i8","i16","i32","i64","i128","isize","f32","f64"),
        "cpp",Set.of("void","bool","char","signedchar","unsignedchar","short","unsignedshort","int","unsignedint","long","unsignedlong","longlong","unsignedlonglong","float","double","longdouble"));
    private final SymbolLookup lookup;private final String language;private int work;
    NominalMethodRelationships(SymbolLookup lookup,String language){this.lookup=lookup;this.language=language;}
    List<Edge> resolve(FileFragment fragment){
        var result=new ArrayList<Edge>();
        for(Node node:fragment.declarations()){
            if(node.kind()!=NodeKind.FUNCTION||!"true".equals(node.attrs().get("nominal.method")))continue;
            var child=SymbolTable.Entry.of(node);if(excluded(child))continue;work=0;
            var found=new LinkedHashMap<NodeId,Edge>();
            try{
                var owner=type(child.attrs().getOrDefault("nominal.owner",""),child);
                if(owner==null||generic(owner))continue;
                List<String> signature=signature(child);if(signature==null)continue;
                var pending=new ArrayDeque<SymbolTable.Entry>(language.equals("rs")?parents(child):parents(owner));var seen=new HashSet<NodeId>();seen.add(owner.id());
                while(!pending.isEmpty()){
                    var parent=pending.removeFirst();if(!seen.add(parent.id()))continue;
                    if(seen.size()>32)throw new Limit();
                    if(generic(parent)||flags(parent).contains("final"))continue;
                    boolean contract="true".equals(parent.attrs().get("nominal.interface"));
                    for(var base:qualified(parent.id().qualifiedName()+"."+node.name())){
                        if(base.kind()!=NodeKind.FUNCTION||!base.id().relPath().equals(parent.id().relPath())||base.arity()!=child.arity()||excluded(base))continue;
                        if(!eligible(child,base,contract)||!signature.equals(signature(base)))continue;
                        String actual=normalized(child.attrs().getOrDefault("nominal.return","?"),child),
                                expected=normalized(base.attrs().getOrDefault("nominal.return","?"),base);
                        if(actual==null||!actual.equals(expected))continue;
                        if(!Objects.equals(child.attrs().get("nominal.qualifiers"),base.attrs().get("nominal.qualifiers")))continue;
                        found.put(base.id(),new Edge(child.id(),base.id(),EdgeKind.OVERRIDES,1f,Map.of(
                            "resolution",language+"-nominal-method","resolutionStatus","resolved","implementationKind",contract?"interface_method":"override",
                            "declaringType",owner.id().qualifiedName(),"baseType",parent.id().qualifiedName(),
                            "referencePrecision","declaration","dispatch","verified declaration subset; external ancestors, generics and runtime dispatch are incomplete")));
                    }
                    pending.addAll(parents(parent));
                }
                result.addAll(found.values());
            }catch(Limit ignored){/* Never publish partially inspected method evidence. */}
        }
        return result;
    }
    private boolean eligible(SymbolTable.Entry child,SymbolTable.Entry base,boolean contract){
        Set<String> c=flags(child),b=flags(base);if(b.contains("final")||b.contains("sealed")||c.contains("new"))return false;
        return switch(language){
            case "cs" -> contract?c.contains("public"):c.contains("override")&&(b.contains("virtual")||b.contains("abstract")||b.contains("override"));
            case "kt" -> c.contains("override")&&(contract||b.contains("open")||b.contains("abstract")||b.contains("override"));
            case "scala" -> contract||c.contains("override")||b.contains("abstract")||base.attrs().getOrDefault("nominal.syntax","").equals("function_declaration");
            case "cpp" -> b.contains("virtual")||b.contains("override");
            case "swift" -> contract||c.contains("override");
            case "dart","objc" -> true;
            case "php" -> !contract||!c.contains("protected");
            case "rs" -> contract&&!child.attrs().getOrDefault("nominal.explicitParents","").isBlank();
            default -> false;
        };
    }
    private boolean excluded(SymbolTable.Entry entry){
        Set<String> flags=flags(entry);return language.equals("php")&&Set.of("__construct","__destruct").contains(entry.id().qualifiedName().substring(entry.id().qualifiedName().lastIndexOf('.')+1).toLowerCase(Locale.ROOT))||flags.contains("static")||!language.equals("cpp")&&flags.contains("private")
            ||entry.attrs().getOrDefault("nominal.syntax","").contains("constructor")||generic(entry)
            ||language.equals("swift")&&(flags.contains("class")||flags.contains("mutating")||flags.contains("nonmutating"))
            ||language.equals("objc")&&!entry.attrs().getOrDefault("nominal.syntax","").startsWith("method_");
    }
    private List<String> signature(SymbolTable.Entry entry){
        String text=entry.attrs().get("nominal.params");if(text==null)return null;
        List<String> raw=text.isEmpty()?List.of():Arrays.asList(text.split("\t",-1));
        if(raw.size()!=entry.arity())return null;var result=new ArrayList<String>();
        for(String p:raw){String value=normalized(p,entry);if(value==null)return null;result.add(value);}return result;
    }
    private String normalized(String raw,SymbolTable.Entry entry){
        String text=raw.replaceAll("\\s+","");if(BUILTINS.get(language).contains(text))return text;
        if(text.endsWith("[]")){String item=normalized(text.substring(0,text.length()-2),entry);return item==null?null:item+"[]";}
        var type=type(text,entry);return type==null?null:type.id().value();
    }
    private List<SymbolTable.Entry> parents(SymbolTable.Entry entry){
        String text=entry.attrs().getOrDefault(language.equals("rs")&&entry.kind()==NodeKind.FUNCTION?"nominal.explicitParents":"nominal.parents","");if(text.isEmpty())return List.of();
        List<SymbolTable.Entry> result=new ArrayList<>();for(String name:text.split("\t")){var parent=type(name,entry);if(parent!=null)result.add(parent);}return result;
    }
    private SymbolTable.Entry type(String raw,SymbolTable.Entry context){
        if(!raw.matches("[A-Za-z_][A-Za-z0-9_]*(?:(?:\\.|::)[A-Za-z_][A-Za-z0-9_]*)*"))return null;
        String name=raw.replace("::",".");List<SymbolTable.Entry> candidates=preferred(qualified(name).stream().filter(e->e.kind()==NodeKind.TYPE).toList());
        if(name.contains(".")&&candidates.size()==1)return candidates.getFirst();
        String owner=context.attrs().getOrDefault("nominal.owner","");
        while(!owner.isEmpty()){
            var nested=qualified(owner+"."+name).stream().filter(e->e.kind()==NodeKind.TYPE).toList();
            if(!nested.isEmpty())return nested.size()==1?nested.getFirst():null;
            int dot=owner.lastIndexOf('.');owner=dot<0?"":owner.substring(0,dot);
        }
        String pkg=context.attrs().getOrDefault("nominal.package","");
        if(!pkg.isEmpty()){
            var packaged=qualified(pkg+"."+name).stream().filter(e->e.kind()==NodeKind.TYPE).toList();
            if(!packaged.isEmpty())return packaged.size()==1?packaged.getFirst():null;
        }
        var local=candidates.stream().filter(e->e.id().relPath().equals(context.id().relPath())).toList();
        if(!local.isEmpty())return local.size()==1?local.getFirst():null;
        var imported=new LinkedHashMap<NodeId,SymbolTable.Entry>();
        for(String path:context.attrs().getOrDefault("nominal.imports","").split("\n")){
            String query=path.endsWith("."+name)?path:language.equals("cs")?path+"."+name:null;
            if(query!=null)for(var entry:qualified(query))if(entry.kind()==NodeKind.TYPE)imported.put(entry.id(),entry);
        }
        return imported.size()==1?imported.values().iterator().next():null;
    }
    private List<SymbolTable.Entry> preferred(List<SymbolTable.Entry> values){
        if(language.equals("objc")){
            var interfaces=values.stream().filter(e->e.attrs().getOrDefault("nominal.syntax","").equals("class_interface")).toList();
            if(!interfaces.isEmpty())return interfaces;
        }
        if(language.equals("rs")){
            var definitions=values.stream().filter(e->!e.attrs().getOrDefault("nominal.syntax","").equals("impl_item")).toList();
            if(!definitions.isEmpty())return definitions;
        }
        return values;
    }
    private List<SymbolTable.Entry> qualified(String name){
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Method resolution cancelled");
        if(++work>512)throw new Limit();var entries=lookup.byQualifiedName(name,language);work+=entries.size();if(work>512)throw new Limit();return entries;
    }
    private static Set<String> flags(SymbolTable.Entry entry){return new HashSet<>(Arrays.asList(entry.attrs().getOrDefault("nominal.modifiers","").split(" ")));}
    private static boolean generic(SymbolTable.Entry entry){return "true".equals(entry.attrs().get("nominal.generic"));}
    private static final class Limit extends RuntimeException {Limit(){super(null,null,false,false);}}
}
