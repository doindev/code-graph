package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.util.*;

/**
 * Bounded declaration binding, not a Java compiler. Explicit receivers never fall back to global
 * simple-name matching. Unknown argument types preserve overload candidates; virtual calls identify
 * their declared target, not every runtime implementation.
 */
final class JavaCallResolver {
    private static final Set<String> PRIMITIVES=Set.of("boolean","byte","short","char","int","long","float","double","void");
    private static final Set<String> JAVA_LANG=Set.of("String","Object","Class","Boolean","Byte","Short","Character","Integer","Long","Float","Double","Number","Throwable","Exception","RuntimeException","Iterable","Enum","Record","AutoCloseable","StringBuilder");
    private static final Map<String,String> BOX=Map.of("boolean","java.lang.Boolean","byte","java.lang.Byte","short","java.lang.Short","char","java.lang.Character","int","java.lang.Integer","long","java.lang.Long","float","java.lang.Float","double","java.lang.Double");
    private final SymbolLookup symbols;
    private final FileFragment fragment;
    private final String packageName;
    private int work;

    JavaCallResolver(FileFragment fragment,SymbolLookup symbols) {
        this.fragment=fragment;this.symbols=symbols;
        packageName=fragment.declarations().stream().map(n->n.attrs().get("java.package")).filter(Objects::nonNull).findFirst().orElse("");
    }
    List<Edge> resolve(RawRef ref) {
        work=0;
        try {
            if(ref.kind()!=RefKind.CALL) {
                String owner=ref.from() instanceof SymbolId id?id.qualifiedName():"";
                String type=type(ref.name(),owner,packageName,fragment.imports());
                return edges(ref,qualified(type).stream().filter(e->e.kind()==NodeKind.TYPE).toList(),"java-type",true);
            }
            CallContext context=ref.context();
            if(context==null)return List.of(); // legacy fragments must be re-extracted, not guessed
            String owner=context.ownerType();
            List<SymbolTable.Entry> targets;
            if(context.constructor()) {
                String type=value(context.receiver(),owner,0);
                String simple=type.substring(type.lastIndexOf('.')+1);
                var constructors=members(type,simple,true);
                targets=select(constructors,context.arguments(),owner,0);
                if(constructors.isEmpty()&&context.arguments().isEmpty())targets=qualified(type).stream().filter(e->e.kind()==NodeKind.TYPE).toList();
            } else {
                ValueHint receiver=context.receiver();
                if(receiver==null)receiver=ValueHint.of("implicit",owner);
                targets=methods(receiver,ref.name(),context.arguments(),owner,0);
            }
            boolean exact=context.constructor()||targets.stream().allMatch(e->flag(e,"static")||flag(e,"private")||flag(e,"final")||finalOwner(e));
            return edges(ref,targets,"java-"+(exact?"bound":"declared-receiver"),exact);
        } catch (ResolutionLimit ignored) {
            return List.of(); // caller retains pending evidence; never publish a guessed high-confidence edge
        }
    }
    private List<Edge> edges(RawRef ref,List<SymbolTable.Entry> entries,String reason,boolean exact) {
        var unique=new LinkedHashMap<String,SymbolTable.Entry>();
        entries.forEach(e->unique.put(e.id().value(),e));
        List<SymbolTable.Entry> all=List.copyOf(unique.values());
        if(all.isEmpty())return List.of();
        boolean ambiguous=all.size()>1;
        List<Edge> result=new ArrayList<>();
        for(var entry:all.stream().limit(NameResolver.MAX_CANDIDATES).toList()) {
            Edge edge=NameResolver.edge(ref,entry,ambiguous?0.5f/all.size():exact?1f:0.9f,reason+(ambiguous?"-ambiguous":""));
            var attrs=new HashMap<>(edge.attrs());
            attrs.put("resolutionStatus",ambiguous?"candidate":"resolved");
            attrs.put("dispatch",exact?"static-binding":"declared-target; runtime overrides may differ");
            attrs.put("candidateCount",String.valueOf(all.size()));
            attrs.put("omittedCandidates",String.valueOf(Math.max(0,all.size()-NameResolver.MAX_CANDIDATES)));
            result.add(new Edge(edge.from(),edge.to(),edge.kind(),edge.confidence(),attrs));
        }
        return result;
    }
    private boolean finalOwner(SymbolTable.Entry entry) {
        return qualified(entry.attrs().getOrDefault("java.owner","")).stream().anyMatch(e->flag(e,"final"));
    }
    private List<SymbolTable.Entry> methods(ValueHint receiver,String name,List<ValueHint> arguments,String owner,int depth) {
        if(depth>8)return List.of();
        String receiverType=value(receiver,owner,depth+1);
        List<SymbolTable.Entry> candidates=members(receiverType,name,false);
        if(receiver.kind().equals("name") && !receiverType.startsWith("?") && unknown(fieldType(owner,receiver.name())))
            candidates=candidates.stream().filter(e->flag(e,"static")).toList();
        // A class's own and inherited methods precede enclosing types and static imports.
        if(receiver.kind().equals("implicit")&&candidates.isEmpty()) {
            String enclosing=owner;
            while(enclosing.contains(".")&&candidates.isEmpty()) {
                enclosing=enclosing.substring(0,enclosing.lastIndexOf('.'));
                if(qualified(enclosing).stream().anyMatch(e->e.kind()==NodeKind.TYPE)) candidates=members(enclosing,name,false);
            }
            if(candidates.isEmpty()) {
                candidates=new ArrayList<>();
                for(String imported:fragment.imports()) if(imported.startsWith("static ")) {
                    String path=imported.substring(7);
                    if(path.endsWith("."+name)||path.endsWith(".*")) {
                        String type=path.substring(0,path.lastIndexOf('.'));
                        members(type,name,false).stream().filter(e->flag(e,"static")).forEach(candidates::add);
                    }
                }
            }
        }
        return select(candidates,arguments,owner,depth+1);
    }
    private List<SymbolTable.Entry> members(String owner,String name,boolean constructors) {
        if(unknown(owner))return List.of();
        var result=new ArrayList<SymbolTable.Entry>();
        var seen=new HashSet<String>();
        var signatures=new HashMap<String,List<String>>();
        var queue=new ArrayDeque<String>();queue.add(owner);
        while(!queue.isEmpty()) {
            if(seen.size()>64)throw new ResolutionLimit();
            String current=queue.remove();
            if(!seen.add(current))continue;
            for(var entry:qualified(current+"."+name)) {
                if(entry.kind()!=NodeKind.FUNCTION)continue;
                if(flag(entry,"constructor")!=constructors)continue;
                if(!current.equals(owner)&&flag(entry,"private"))continue;
                String signature=String.join("\t",split(entry.attrs().get("java.params")).stream().map(p->declaredType(p,entry)).toList());
                var priorOwners=signatures.computeIfAbsent(signature,k->new ArrayList<>());
                boolean shadowed=priorOwners.stream().anyMatch(prior->!prior.equals(current)&&inherits(prior,current));
                if(!shadowed) {result.add(entry);priorOwners.add(current);}
            }
            if(!constructors)queue.addAll(parents(current));
        }
        return result;
    }
    boolean inherits(String child,String parent) {
        var seen=new HashSet<String>();var queue=new ArrayDeque<String>();queue.add(child);
        while(!queue.isEmpty()&&seen.size()<64) {
            String next=queue.remove();if(!seen.add(next))continue;
            if(next.equals(parent))return true;queue.addAll(parents(next));
        }
        return false;
    }
    List<String> parents(String owner) {
        var result=new ArrayList<String>();
        for(var entry:qualified(owner)) if(entry.kind()==NodeKind.TYPE)
            for(String parent:split(entry.attrs().get("java.parents")))
                result.add(declaredType(parent,entry));
        return result;
    }
    private List<SymbolTable.Entry> select(List<SymbolTable.Entry> candidates,List<ValueHint> arguments,String owner,int depth) {
        if(depth>8)return List.of();
        List<String> types=arguments.stream().map(v->value(v,owner,depth+1)).toList();
        var possible=new ArrayList<SymbolTable.Entry>();var scores=new ArrayList<Integer>();
        boolean uncertain=false;
        for(var entry:candidates) {
            List<String> parameters=split(entry.attrs().get("java.params"));
            boolean varargs=flag(entry,"varargs");
            if((!varargs&&entry.arity()!=types.size())||(varargs&&types.size()<entry.arity()-1))continue;
            if(parameters.size()!=entry.arity()) {possible.add(entry);scores.add(100);uncertain=true;continue;}
            boolean expanded=varargs && !(types.size()==parameters.size()&&!types.isEmpty()&&types.getLast().endsWith("[]"));
            int score=expanded?40:0; boolean fits=true;
            for(int i=0;i<types.size();i++) {
                String param=declaredType(parameters.get(Math.min(i,parameters.size()-1)),entry);
                if(varargs&&i>=parameters.size()-1&&param.endsWith("[]") && !(types.size()==parameters.size()&&types.get(i).endsWith("[]")))
                    param=param.substring(0,param.length()-2);
                int cost=conversion(types.get(i),param);
                if(cost<0){fits=false;break;}
                if(cost>=100)uncertain=true;
                score+=cost;
            }
            if(fits){possible.add(entry);scores.add(score);}
        }
        if(possible.size()<2||uncertain)return possible;
        int best=Collections.min(scores);
        var result=new ArrayList<SymbolTable.Entry>();
        for(int i=0;i<possible.size();i++)if(scores.get(i)==best)result.add(possible.get(i));
        return result;
    }
    private int conversion(String actual,String expected) {
        if(unknown(actual)||unknown(expected))return 100;
        if(actual.equals(expected))return 0;
        if(actual.equals("null"))return PRIMITIVES.contains(expected)?-1:5;
        if(PRIMITIVES.contains(actual)&&PRIMITIVES.contains(expected)) {
            String widening=switch(actual) {
                case "byte" -> "short,int,long,float,double"; case "short","char" -> "int,long,float,double";
                case "int" -> "long,float,double";case "long" -> "float,double";case "float" -> "double";default -> "";
            };
            int at=Arrays.asList(widening.split(",")).indexOf(expected);return at<0?-1:at+1;
        }
        if(expected.equals(BOX.get(actual))||actual.equals(BOX.get(expected)))return 10;
        if(PRIMITIVES.contains(expected)) {
            String unboxed=BOX.entrySet().stream().filter(e->e.getValue().equals(actual)).map(Map.Entry::getKey).findFirst().orElse(null);
            if(unboxed!=null) {int cost=conversion(unboxed,expected);return cost<0?-1:10+cost;}
        }
        if(PRIMITIVES.contains(actual)&&BOX.containsKey(actual)) {
            int cost=conversion(BOX.get(actual),expected);return cost<0?-1:10+cost;
        }
        if(expected.equals("java.lang.Object")&&!PRIMITIVES.contains(actual))return 20;
        if(actual.startsWith("java.lang.")&&expected.startsWith("java.lang.")
                &&JAVA_LANG.contains(actual.substring(10))&&JAVA_LANG.contains(expected.substring(10))) {
            if(expected.equals("java.lang.Number")&&Set.of("java.lang.Byte","java.lang.Short","java.lang.Integer","java.lang.Long","java.lang.Float","java.lang.Double").contains(actual))return 5;
            if(actual.equals("java.lang.RuntimeException")&&Set.of("java.lang.Exception","java.lang.Throwable").contains(expected))return 5;
            if(actual.equals("java.lang.Exception")&&expected.equals("java.lang.Throwable"))return 5;
            return -1;
        }
        if(actual.endsWith("[]")||expected.endsWith("[]"))return -1;
        if(PRIMITIVES.contains(actual)||PRIMITIVES.contains(expected))return -1;
        var seen=new HashSet<String>();var queue=new ArrayDeque<String>();queue.add(actual);
        while(!queue.isEmpty()&&seen.size()<64) {
            String current=queue.remove();if(!seen.add(current))continue;
            if(current.equals(expected))return 5;
            queue.addAll(parents(current));
        }
        // External hierarchy is unavailable; do not incorrectly eliminate a possible overload.
        if(qualified(actual).isEmpty()||qualified(expected).isEmpty())return 100;
        return -1;
    }
    private String value(ValueHint hint,String owner,int depth) {
        if(hint==null||depth>8)return "?";
        return switch(hint.kind()) {
            case "type","new","implicit" -> type(hint.name(),owner,packageName,fragment.imports());
            case "null" -> "null";
            case "super" -> parents(owner).stream().findFirst().orElse("?");
            case "name" -> {
                String field=fieldType(owner,hint.name());
                yield !unknown(field)?field:type(hint.name(),owner,packageName,fragment.imports());
            }
            case "field" -> {
                String receiver=value(hint.receiver(),owner,depth+1);
                String field=fieldType(receiver,hint.name());
                if(unknown(field)) {
                    String path=path(hint);
                    yield path==null?"?":type(path,owner,packageName,fragment.imports());
                }
                yield field;
            }
            case "call" -> {
                var targets=methods(hint.receiver(),hint.name(),hint.arguments(),owner,depth+1);
                Set<String> returns=new HashSet<>();
                for(var target:targets)returns.add(declaredType(target.attrs().getOrDefault("java.return",""),target));
                yield returns.size()==1?returns.iterator().next():"?";
            }
            case "element" -> {String array=value(hint.receiver(),owner,depth+1);yield array.endsWith("[]")?array.substring(0,array.length()-2):"?";}
            default -> "?";
        };
    }
    private String path(ValueHint hint) {
        if(hint==null)return null;
        if(hint.kind().equals("name"))return hint.name();
        if(hint.kind().equals("field")) {String parent=path(hint.receiver());return parent==null?null:parent+"."+hint.name();}
        return null;
    }
    private String fieldType(String owner,String name) {
        if(unknown(owner))return "?";
        var queue=new ArrayDeque<String>();var seen=new HashSet<String>();queue.add(owner);
        while(!queue.isEmpty()&&seen.size()<64) {
            String current=queue.remove();if(!seen.add(current))continue;
            var fields=qualified(current+"."+name).stream().filter(e->e.kind()==NodeKind.VARIABLE).toList();
            if(fields.size()==1)return declaredType(fields.getFirst().attrs().getOrDefault("java.return",""),fields.getFirst());
            queue.addAll(parents(current));
        }
        return "?";
    }
    String declaredType(String name,SymbolTable.Entry entry) {
        String raw=erase(name);
        for(String variable:split(entry.attrs().get("java.typevars"))) {
            int equals=variable.indexOf('=');
            if(equals>0&&raw.replace("[]","").equals(variable.substring(0,equals))) {
                name=variable.substring(equals+1)+(raw.endsWith("[]")?"[]":"");break;
            }
        }
        return type(name,entry.attrs().getOrDefault("java.owner",""),entry.attrs().getOrDefault("java.package",""),
                Arrays.asList(entry.attrs().getOrDefault("java.imports","").split("\n")));
    }
    private String type(String text,String owner,String pkg,List<String> imports) {
        String name=erase(text);
        if(name.isEmpty()||name.equals("var")||name.contains("?")||name.contains("|"))return "?";
        if(name.endsWith("[]"))return type(name.substring(0,name.length()-2),owner,pkg,imports)+"[]";
        if(PRIMITIVES.contains(name))return name;
        if(name.startsWith("java.lang."))return name;
        if(name.contains(".")&&qualified(name).stream().anyMatch(e->e.kind()==NodeKind.TYPE
                &&(!e.attrs().getOrDefault("java.package","").isEmpty()||pkg.isEmpty())))return name;
        String first=name.contains(".")?name.substring(0,name.indexOf('.')):name;
        String tail=name.substring(first.length());
        for(String scope=owner;!scope.isEmpty();) {
            if(qualified(scope).stream().noneMatch(e->e.kind()==NodeKind.TYPE))break;
            String nested=scope+"."+name;
            if(qualified(nested).stream().anyMatch(e->e.kind()==NodeKind.TYPE))return nested;
            int dot=scope.lastIndexOf('.');scope=dot<0?"":scope.substring(0,dot);
        }
        for(String imported:imports) if(!imported.startsWith("static ")&&imported.endsWith("."+first))return imported+tail;
        String local=pkg.isEmpty()?name:pkg+"."+name;
        if(qualified(local).stream().anyMatch(e->e.kind()==NodeKind.TYPE))return local;
        var wildcard=new LinkedHashSet<String>();
        for(String imported:imports)if(!imported.startsWith("static ")&&imported.endsWith(".*")) {
            String candidate=imported.substring(0,imported.length()-1)+name;
            if(qualified(candidate).stream().anyMatch(e->e.kind()==NodeKind.TYPE))wildcard.add(candidate);
        }
        if(JAVA_LANG.contains(name))wildcard.add("java.lang."+name);
        if(wildcard.size()==1)return wildcard.iterator().next();
        if(wildcard.size()>1)return "?";
        return name.contains(".")?name:"?"+name;
    }
    static String erase(String input) {
        if(input==null)return "";var out=new StringBuilder();int depth=0;
        for(char c:input.toCharArray()) {if(c=='<'){depth++;continue;}if(c=='>'){depth--;continue;}if(depth==0&&!Character.isWhitespace(c))out.append(c);}
        return out.toString().replace("...","[]");
    }
    void resetBudget(){work=0;}
    List<SymbolTable.Entry> qualified(String name) {
        if(++work>2048)throw new ResolutionLimit();
        return symbols.byQualifiedName(name,"java").stream().filter(e->e.id().lang().equals("java")).toList();
    }
    private static boolean unknown(String value) {return value==null||value.isEmpty()||value.startsWith("?");}
    private static boolean flag(SymbolTable.Entry e,String key) {return "true".equals(e.attrs().get("java."+key));}
    private static List<String> split(String text) {return text==null||text.isEmpty()?List.of():Arrays.asList(text.split("\t",-1));}
    static final class ResolutionLimit extends RuntimeException {}
}
