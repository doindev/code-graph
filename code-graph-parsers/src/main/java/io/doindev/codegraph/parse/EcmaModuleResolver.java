package io.doindev.codegraph.parse;

import io.doindev.codegraph.model.*;
import java.nio.file.Path;
import java.util.*;

/** Explicit import bindings take precedence over name heuristics, including failed bindings. */
final class EcmaModuleResolver {
    record Resolution(boolean handled, String status, List<Edge> edges) {}
    private final FileFragment fragment;
    private final SymbolLookup lookup;
    private int probes;
    private String failure = "external_or_unindexed";
    EcmaModuleResolver(FileFragment fragment, SymbolLookup lookup) { this.fragment=fragment;this.lookup=lookup; }
    Resolution resolve(RawRef ref) {
        probes=0;failure="external_or_unindexed";
        String local=ref.receiverHint()==null?ref.name():ref.receiverHint();
        if(local.contains(".")) {
            String root=local.substring(0,local.indexOf('.'));
            if(fragment.modules().bindings().stream().anyMatch(b->b.local().equals(root)))
                return new Resolution(true,"unsupported_nested_module_member",List.of());
        }
        var bindings=fragment.modules().bindings().stream().filter(b->b.local().equals(local)&&contains(b.scope(),ref.site())).toList();
        if(bindings.isEmpty()) {
            if(fragment.modules().bindings().stream().anyMatch(b->b.local().equals(local)))
                return new Resolution(true,"out_of_scope_binding",List.of());
            if(fragment.imports().stream().anyMatch(value->value.equals(local)||value.endsWith("."+local)))
                return new Resolution(true,"legacy_module_context_missing",List.of());
            return new Resolution(false,"not_imported",List.of());
        }
        if(bindings.size()!=1)return new Resolution(true,"ambiguous_binding",List.of());
        var binding=bindings.getFirst();
        if(binding.kind().startsWith("commonjs")&&fragment.modules().shadows().stream()
                .anyMatch(s->s.name().equals("require")&&contains(s.scope(),binding.site())))
            return new Resolution(true,"shadowed_require",List.of());
        if(ref.receiverHint()!=null&&!binding.exported().equals("*"))
            return new Resolution(true,"unsupported_imported_object_member",List.of());
        if(binding.typeOnly()&&ref.kind()==RefKind.CALL)return new Resolution(true,"type_only",List.of());
        for(var shadow:fragment.modules().shadows())
            if(shadow.name().equals(local)&&!shadow.site().equals(binding.site())&&contains(shadow.scope(),ref.site()))
                return new Resolution(true,"shadowed_binding",List.of());
        String exported=binding.exported().equals("*")?(ref.receiverHint()==null?"default":ref.name()):binding.exported();
        try {
            String path=lookup.resolveModule(fragment.file().relPath(),binding.module(),binding.kind(),this::probe);
            if(path==null)return new Resolution(true,binding.module().isEmpty()?"unsupported_dynamic_binding":failure,List.of());
            List<SymbolTable.Entry> entries=exports(path,exported,ref.kind()!=RefKind.CALL,0,new HashSet<>())
                    .stream().filter(e->ref.kind()!=RefKind.CALL||!"false".equals(e.attrs().get("ecmaRuntime"))).toList();
            if(entries.isEmpty())return new Resolution(true,failure,List.of());
            var unique=entries.stream().collect(java.util.stream.Collectors.toMap(e->e.id().value(),e->e,(a,b)->a,TreeMap::new)).values();
            if(unique.size()>NameResolver.MAX_CANDIDATES)return new Resolution(true,"ambiguous_binding",List.of());
            var edges=new ArrayList<Edge>();
            for(var entry:unique) {
                var base=NameResolver.edge(ref,entry,unique.size()==1?1f:0.5f/unique.size(),"module-binding");
                var attrs=new HashMap<>(base.attrs());
                attrs.put("resolutionStatus",unique.size()==1?"resolved":"ambiguous");
                attrs.put("moduleSpecifier",binding.module());attrs.put("modulePath",path);
                attrs.put("exportedName",exported);attrs.put("localAlias",binding.local());
                attrs.put("importKind",binding.kind());attrs.put("candidateCount",""+unique.size());attrs.put("omittedCandidates","0");
                edges.add(new Edge(base.from(),base.to(),base.kind(),base.confidence(),attrs));
            }
            return new Resolution(true,unique.size()==1?"resolved":"ambiguous_binding",edges);
        } catch (BudgetExceeded e) { return new Resolution(true,"work_budget_exhausted",List.of()); }
        catch (IllegalArgumentException e) { return new Resolution(true,"unsupported_module_configuration",List.of()); }
    }
    private ModuleFile probe(String path) {
        if(Thread.currentThread().isInterrupted())throw new IllegalArgumentException("Module resolution cancelled");
        if(++probes>256)throw new BudgetExceeded();
        return lookup.module(path);
    }
    private List<SymbolTable.Entry> exports(String path,String name,boolean types,int hops,Set<String> visited) {
        if(hops>=32)throw new BudgetExceeded();
        if(!visited.add(path+"\n"+name)){failure="cyclic_reexport";return List.of();}
        try {
            ModuleFile module=probe(path);
            if(module==null){failure="external_or_unindexed";return List.of();}
            if(module.evidence().unsafeExports()){failure="unsupported_dynamic_exports";return List.of();}
            if(path.endsWith(".d.ts")||path.endsWith(".d.mts")||path.endsWith(".d.cts")){failure="type_only";return List.of();}
            List<ModuleEvidence.Export> explicit=module.evidence().exports().stream().filter(e->!e.star()&&e.exported().equals(name)).toList();
            List<ModuleEvidence.Export> selected=explicit.isEmpty()?module.evidence().exports().stream().filter(e->e.star()&&!name.equals("default")).toList():explicit;
            var found=new ArrayList<SymbolTable.Entry>();
            for(var export:selected) {
                if(export.typeOnly()&&!types){failure="type_only";continue;}
                if(!export.module().isEmpty()) {
                    String target=lookup.resolveModule(path,export.module(),"named",this::probe);
                    if(target==null){failure="external_or_unindexed";return List.of();}
                    var next=exports(target,export.star()?name:export.local(),types,hops+1,visited);
                    if(next.isEmpty()) {
                        if(export.star()&&Set.of("unresolved_export","cyclic_reexport").contains(failure))continue;
                        return List.of();
                    }
                    found.addAll(next);
                } else {
                    var declarations=module.declarations().stream().filter(e->e.id().qualifiedName().equals(export.local())).toList();
                    if(declarations.isEmpty()) {
                        var imported=module.evidence().bindings().stream().filter(b->b.local().equals(export.local())).toList();
                        if(imported.size()==1&&!imported.getFirst().typeOnly()) {
                            var binding=imported.getFirst();
                            String target=lookup.resolveModule(path,binding.module(),binding.kind(),this::probe);
                            if(target!=null)found.addAll(exports(target,binding.exported(),types,hops+1,visited));
                        }
                    } else found.addAll(declarations);
                }
            }
            if(found.isEmpty())failure="unresolved_export";
            return found;
        } finally { visited.remove(path+"\n"+name); }
    }
    static String relative(String source,String specifier) {
        if(!specifier.startsWith("./")&&!specifier.startsWith("../"))return null;
        String parent=source.contains("/")?source.substring(0,source.lastIndexOf('/')+1):"";
        String target=Path.of(parent).resolve(specifier).normalize().toString().replace('\\','/');
        return target.startsWith("../")||target.equals("..")||target.startsWith("/")||target.contains(":")?null:target;
    }
    private static boolean contains(SourceSpan scope,SourceSpan site) {
        return scope!=null&&site!=null&&(scope.startLine()<site.startLine()||scope.startLine()==site.startLine()&&scope.startCol()<=site.startCol())
                &&(scope.endLine()>site.endLine()||scope.endLine()==site.endLine()&&scope.endCol()>=site.endCol());
    }
    private static final class BudgetExceeded extends RuntimeException {}
}
