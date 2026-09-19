package io.doindev.codegraph.lang.javascript;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared JavaScript/TypeScript/TSX extraction; the concrete subclasses differ only in grammar,
 * language id and extensions. Static module evidence and named arrow/function expressions
 * are extracted without executing project code or changing existing declaration identities.
 */
abstract class EcmaAnalyzer extends TreeWalkAnalyzer {
    @Override protected io.doindev.codegraph.parse.SemanticHints semanticHints(TSNode root,Src src) {
        return new io.doindev.codegraph.parse.SemanticHints() {
            @Override public java.util.Map<String,String> declaration(TSNode node) {
                boolean typeOnly=Set.of("interface_declaration","function_signature","method_signature","abstract_method_signature").contains(node.getType());
                for(TSNode parent=node.getParent();EcmaModules.present(parent);parent=parent.getParent())
                    if(parent.getType().equals("ambient_declaration"))typeOnly=true;
                return typeOnly?java.util.Map.of("ecmaRuntime","false"):java.util.Map.of();
            }
        };
    }
    @Override protected io.doindev.codegraph.parse.ModuleEvidence modulesOf(TSNode root, Src src) {
        return EcmaModules.extract(root,src);
    }
    private static boolean functionValue(TSNode n) {
        return EcmaModules.present(n) && Set.of("arrow_function","function_expression","generator_function").contains(n.getType());
    }
    @Override protected boolean isFunctionDeclaration(TSNode n) {
        if(super.isFunctionDeclaration(n))return true;
        if(n.getType().equals("variable_declarator"))return functionValue(n.getChildByFieldName("value"));
        return functionValue(n) && EcmaModules.present(n.getParent()) &&
                Set.of("export_statement","pair","assignment_expression").contains(n.getParent().getType());
    }
    @Override protected String nameOf(TSNode n, Src src) {
        String name=super.nameOf(n,src);
        if(name!=null)return name;
        TSNode parent=n.getParent();
        if(EcmaModules.present(parent))switch(parent.getType()) {
            case "export_statement" -> {return "default";}
            case "pair" -> {return src.text(parent.getChildByFieldName("key"));}
            case "assignment_expression" -> {
                String left=src.text(parent.getChildByFieldName("left"));
                return left.equals("module.exports")?"default":left.substring(left.lastIndexOf('.')+1);
            }
        }
        return null;
    }
    @Override protected int arityOf(TSNode n, Src src) {
        if(n.getType().equals("variable_declarator"))n=n.getChildByFieldName("value");
        if(EcmaModules.present(n.getChildByFieldName("parameter")))return 1;
        return super.arityOf(n,src);
    }
    @Override protected String signatureOf(TSNode n,String name,Src src) {
        if(n.getType().equals("variable_declarator"))n=n.getChildByFieldName("value");
        return super.signatureOf(n,name,src);
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_declaration", "interface_declaration", "enum_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_declaration", "generator_function_declaration", "method_definition",
                "function_signature", "method_signature", "abstract_method_signature");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression", "new_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("public_field_definition", "field_definition", "property_signature");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "for_in_statement", "while_statement",
                "do_statement", "switch_case", "catch_clause", "ternary_expression");
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        if (call.getType().equals("new_expression")) {
            TSNode constructor = call.getChildByFieldName("constructor");
            if (constructor == null || constructor.isNull()) {
                return null;
            }
            TSNode args = call.getChildByFieldName("arguments");
            int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
            String name = src.text(constructor);
            int lastDot = name.lastIndexOf('.');
            return new CallSite(lastDot >= 0 ? name.substring(lastDot + 1) : name, lastDot >= 0 ? name.substring(0,lastDot) : null, arity);
        }
        return super.callOf(call, src);
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (!child.getType().equals("import_statement")) {
                continue;
            }
            collectImportedNames(child, src, imports);
        }
        return imports;
    }

    private static void collectImportedNames(TSNode node, Src src, List<String> into) {
        String type = node.getType();
        if (type.equals("import_specifier")) {
            TSNode name = node.getChildByFieldName("alias");
            if (name == null || name.isNull()) {
                name = node.getChildByFieldName("name");
            }
            if (name != null && !name.isNull()) {
                into.add(src.text(name));
            }
            return;
        }
        if (type.equals("identifier")) { // default import
            into.add(src.text(node));
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectImportedNames(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        List<SuperRef> refs = new ArrayList<>();
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            String type = child.getType();
            if (type.equals("class_heritage") || type.equals("extends_clause")
                    || type.equals("extends_type_clause") || type.equals("implements_clause")) {
                RefKind kind = type.equals("implements_clause") ? RefKind.IMPLEMENTS : RefKind.EXTENDS;
                collectIdentifiers(child, src, refs, kind);
            }
        }
        return refs;
    }

    private static void collectIdentifiers(TSNode node, Src src, List<SuperRef> into, RefKind kind) {
        String type = node.getType();
        if (type.equals("identifier") || type.equals("type_identifier")) {
            into.add(new SuperRef(src.text(node), kind));
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectIdentifiers(node.getNamedChild(i), src, into, kind);
        }
    }
}
