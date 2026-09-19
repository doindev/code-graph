package io.doindev.codegraph.lang.javascript;

import io.doindev.codegraph.parse.*;
import org.treesitter.TSNode;
import java.util.*;

/** Static syntax only. No package loading, evaluation, or execution of project code. */
final class EcmaModules {
    private final Src src;
    private final TSNode root;
    private final List<ModuleEvidence.Binding> imports = new ArrayList<>();
    private final List<ModuleEvidence.Export> exports = new ArrayList<>();
    private final List<ModuleEvidence.Shadow> shadows = new ArrayList<>();
    private boolean unsafeExports;
    private int commonReplacements;
    private EcmaModules(TSNode root, Src src) { this.root = root; this.src = src; }
    static ModuleEvidence extract(TSNode root, Src src) {
        var collector = new EcmaModules(root, src); collector.walk(root);
        if(collector.shadows.stream().anyMatch(s->Set.of("exports","module").contains(s.name())&&s.scope().equals(src.span(root))))
            collector.unsafeExports=true;
        return new ModuleEvidence(collector.imports, collector.exports, collector.shadows, collector.unsafeExports);
    }
    static boolean present(TSNode n) { return n != null && !n.isNull(); }
    private String text(TSNode n) { return present(n) ? src.text(n) : ""; }
    private String field(TSNode n, String name) { return text(n.getChildByFieldName(name)); }
    private static List<TSNode> children(TSNode n) {
        var children = new ArrayList<TSNode>();
        for (int i=0;i<n.getNamedChildCount();i++) children.add(n.getNamedChild(i));
        return children;
    }
    private static boolean keyword(TSNode node, String keyword) {
        for (int i=0;i<node.getChildCount();i++) if (node.getChild(i).getType().equals(keyword)) return true;
        return false;
    }
    private String literal(TSNode node) {
        String raw = text(node);
        if (!present(node) || !node.getType().equals("string") || raw.length()<2 || raw.contains("\\")) return "";
        return raw.substring(1,raw.length()-1);
    }
    private TSNode scope(TSNode node) {
        TSNode current=node.getParent();
        boolean functionScoped=present(current)&&current.getType().equals("variable_declaration");
        while(present(current)) {
            if(Set.of("program","function_declaration","function_expression","arrow_function","method_definition").contains(current.getType())
                    ||!functionScoped&&current.getType().equals("statement_block")) return current;
            current=current.getParent();
        }
        return root;
    }
    private void binding(String module,String exported,String local,String kind,boolean typeOnly,TSNode site) {
        if(!local.isBlank()) imports.add(new ModuleEvidence.Binding(module,exported,local,kind,typeOnly,src.span(site),src.span(scope(site))));
    }
    private void exported(String name,String local,String module,boolean star,boolean typeOnly,TSNode site) {
        exports.add(new ModuleEvidence.Export(name,local,module,star,typeOnly,src.span(site)));
    }
    private void walk(TSNode node) {
        switch(node.getType()) {
            case "import_statement" -> { importStatement(node); return; }
            case "export_statement" -> exportStatement(node);
            case "variable_declarator" -> {
                TSNode name=node.getChildByFieldName("name");
                for(String local:patternNames(name)) shadows.add(new ModuleEvidence.Shadow(local,src.span(node),src.span(scope(node))));
                require(node);
            }
            case "formal_parameters" -> {
                for(String name:patternNames(node)) shadows.add(new ModuleEvidence.Shadow(name,src.span(node),src.span(scope(node))));
            }
            case "arrow_function" -> {
                TSNode parameter=node.getChildByFieldName("parameter");
                for(String name:patternNames(parameter))shadows.add(new ModuleEvidence.Shadow(name,src.span(parameter),src.span(node)));
            }
            case "function_declaration","class_declaration" -> {
                String name=field(node,"name");
                if(!name.isBlank()) shadows.add(new ModuleEvidence.Shadow(name,src.span(node),src.span(scope(node))));
            }
            case "assignment_expression" -> {
                commonExport(node);
                String left=field(node,"left"),local=left.split("[.\\[]",2)[0];
                if(local.matches("[A-Za-z_$][A-Za-z0-9_$]*")&&!Set.of("exports","module").contains(local))
                    shadows.add(new ModuleEvidence.Shadow(local,src.span(node),src.span(scope(node))));
            }
            case "call_expression" -> {
                TSNode arguments=node.getChildByFieldName("arguments");
                if(present(arguments)&&children(arguments).stream().anyMatch(n->Set.of("exports","module.exports").contains(text(n))))
                    unsafeExports=true;
            }
        }
        for(TSNode child:children(node)) walk(child);
    }
    private List<String> patternNames(TSNode node) {
        if(!present(node))return List.of();
        if(Set.of("identifier","shorthand_property_identifier_pattern").contains(node.getType()))return List.of(text(node));
        if(Set.of("type_annotation","predefined_type","type_identifier").contains(node.getType()))return List.of();
        if(Set.of("pair_pattern","assignment_pattern","required_parameter","optional_parameter").contains(node.getType())) {
            for(String name:List.of("value","left","pattern")) { TSNode value=node.getChildByFieldName(name); if(present(value))return patternNames(value); }
        }
        var names=new ArrayList<String>();for(TSNode child:children(node))names.addAll(patternNames(child));return names;
    }
    private void importStatement(TSNode node) {
        String module=literal(node.getChildByFieldName("source"));
        boolean typeOnly=keyword(node,"type");
        for(TSNode clause:children(node)) if(clause.getType().equals("import_clause")) importClause(clause,module,typeOnly);
    }
    private void importClause(TSNode clause,String module,boolean typeOnly) {
        for(TSNode child:children(clause))switch(child.getType()) {
            case "identifier" -> binding(module,"default",text(child),"default",typeOnly,clause);
            case "namespace_import" -> {
                for(TSNode id:children(child))if(id.getType().equals("identifier"))binding(module,"*",text(id),"namespace",typeOnly,clause);
            }
            case "named_imports" -> {
                for(TSNode item:children(child))if(item.getType().equals("import_specifier")) {
                    String name=field(item,"name"),alias=field(item,"alias");
                    binding(module,name,alias.isEmpty()?name:alias,"named",typeOnly||keyword(item,"type"),clause);
                }
            }
        }
    }
    private void exportStatement(TSNode node) {
        String module=literal(node.getChildByFieldName("source")); boolean typeOnly=keyword(node,"type");
        TSNode declaration=node.getChildByFieldName("declaration"), value=node.getChildByFieldName("value");
        if(keyword(node,"default")) {
            TSNode target=present(declaration)?declaration:value;
            String local=present(target)?field(target,"name"):"";
            if(present(target)&&target.getType().equals("identifier"))local=text(target);
            exported("default",local.isEmpty()?"default":local,"",false,typeOnly,node);return;
        }
        for(TSNode child:children(node)) {
            if(child.getType().equals("export_clause")) {
                for(TSNode item:children(child)) if(item.getType().equals("export_specifier")) {
                    String local=field(item,"name"),alias=field(item,"alias");
                    exported(alias.isEmpty()?local:alias,local,module,false,typeOnly||keyword(item,"type"),item);
                }
            } else if(child.getType().equals("namespace_export")) {
                exported(text(child.getNamedChild(child.getNamedChildCount()-1)),"*",module,false,typeOnly,child);
            }
        }
        if(keyword(node,"*"))exported("*","*",module,true,typeOnly,node);
        if(present(declaration)) {
            if(Set.of("lexical_declaration","variable_declaration").contains(declaration.getType())) {
                for(TSNode item:children(declaration))if(item.getType().equals("variable_declarator"))
                    for(String name:patternNames(item.getChildByFieldName("name")))exported(name,name,"",false,typeOnly,item);
            } else {
                String name=field(declaration,"name");if(!name.isBlank())exported(name,name,"",false,typeOnly,declaration);
            }
        }
    }
    private void require(TSNode declaration) {
        TSNode call=declaration.getChildByFieldName("value"),name=declaration.getChildByFieldName("name");
        if(!present(call))return;
        if(!call.getType().equals("call_expression")||!field(call,"function").equals("require")) {
            if(hasModuleLoad(call,0))for(String local:patternNames(name))binding("","default",local,"unsupported_dynamic",false,declaration);
            return;
        }
        TSNode args=call.getChildByFieldName("arguments");
        String module=present(args)&&args.getNamedChildCount()==1?literal(args.getNamedChild(0)):"";
        if(name.getType().equals("identifier"))binding(module,"*",text(name),"commonjs",false,declaration);
        else if(name.getType().equals("object_pattern"))for(TSNode item:children(name)) {
            if(item.getType().equals("pair_pattern")&&item.getChildByFieldName("value").getType().equals("identifier"))
                binding(module,field(item,"key"),field(item,"value"),"commonjs_named",false,declaration);
            else if(item.getType().equals("shorthand_property_identifier_pattern"))binding(module,text(item),text(item),"commonjs_named",false,declaration);
            else for(String local:patternNames(item))binding("","default",local,"unsupported_dynamic",false,declaration);
        }
    }
    private boolean hasModuleLoad(TSNode node,int depth) {
        if(depth>8)return false;
        if(node.getType().equals("call_expression")&&Set.of("require","import").contains(field(node,"function")))return true;
        for(TSNode child:children(node))if(hasModuleLoad(child,depth+1))return true;
        return false;
    }
    private void commonExport(TSNode assignment) {
        String left=field(assignment,"left");
        TSNode value=assignment.getChildByFieldName("right");
        boolean export=left.startsWith("exports.")||left.startsWith("exports[")||left.startsWith("module.exports");
        if(export && (!present(assignment.getParent())||!present(assignment.getParent().getParent())
                ||!assignment.getParent().getParent().getType().equals("program") || left.contains("["))) {unsafeExports=true;return;}
        if(left.equals("module.exports")) {
            if(++commonReplacements>1||!exports.isEmpty()){unsafeExports=true;return;}
            if(value.getType().equals("object")) {
                for(TSNode pair:children(value)) {
                    if(pair.getType().equals("shorthand_property_identifier"))exported(text(pair),text(pair),"",false,false,pair);
                    else if(pair.getType().equals("method_definition"))exported(field(pair,"name"),field(pair,"name"),"",false,false,pair);
                    else if(pair.getType().equals("pair")) {
                        String name=field(pair,"key"),local=field(pair,"value");
                        TSNode v=pair.getChildByFieldName("value");
                        if(!Set.of("identifier","function_expression","arrow_function").contains(v.getType())){unsafeExports=true;continue;}
                        if(Set.of("function_expression","arrow_function").contains(v.getType()))local=name;
                        exported(name,local,"",false,false,pair);
                    } else unsafeExports=true;
                }
            } else if(Set.of("identifier","function_expression","arrow_function").contains(value.getType()))
                exported("default",value.getType().equals("identifier")?text(value):"default","",false,false,assignment);
            else unsafeExports=true;
        } else if(left.startsWith("exports.")||left.startsWith("module.exports.")) {
            String member=left.startsWith("exports.")?left.substring(8):left.substring(15);
            if(member.contains(".")||!Set.of("identifier","function_expression","arrow_function").contains(value.getType())){unsafeExports=true;return;}
            String name=left.substring(left.lastIndexOf('.')+1);
            if(exports.stream().anyMatch(e->e.exported().equals(name)))unsafeExports=true;
            exported(name,value.getType().equals("identifier")?text(value):name,"",false,false,assignment);
        }
    }
}
