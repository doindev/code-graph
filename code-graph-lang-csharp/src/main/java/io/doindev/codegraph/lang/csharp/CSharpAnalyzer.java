package io.doindev.codegraph.lang.csharp;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * C# extraction over tree-sitter-c-sharp. Block-bodied {@code namespace} declarations are
 * treated as type-declaration scopes so members get {@code Ns.Class.Method} qualified names;
 * a file-scoped namespace becomes the package prefix instead.
 */
public final class CSharpAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "cs";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("cs");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterCSharp();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        // namespace_declaration included so block namespaces act as scope containers
        return Set.of("class_declaration", "interface_declaration", "struct_declaration",
                "enum_declaration", "record_declaration", "namespace_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("method_declaration", "constructor_declaration", "local_function_statement");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("invocation_expression", "object_creation_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("field_declaration", "property_declaration");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "foreach_statement", "while_statement",
                "do_statement", "catch_clause", "conditional_expression", "switch_section");
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        if (call.getType().equals("object_creation_expression")) {
            TSNode type = call.getChildByFieldName("type");
            if (type == null || type.isNull()) {
                return null;
            }
            return new CallSite(simpleTypeName(src.text(type)), null, arity);
        }
        // invocation_expression: function is identifier/generic_name or member_access_expression
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        if (function.getType().equals("member_access_expression")) {
            TSNode name = function.getChildByFieldName("name");
            if (name == null || name.isNull()) {
                return null;
            }
            TSNode receiver = function.getChildByFieldName("expression");
            String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
            return new CallSite(simpleTypeName(src.text(name)), receiverHint, arity);
        }
        return new CallSite(simpleTypeName(src.text(function)), null, arity);
    }

    /** {@code Ns.Foo<T>} → {@code Foo}: strip generic suffix and qualifier. */
    private static String simpleTypeName(String name) {
        int generic = name.indexOf('<');
        if (generic > 0) {
            name = name.substring(0, generic);
        }
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        collectUsings(root, src, imports);
        return imports;
    }

    private static void collectUsings(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("using_directive")) {
            int named = node.getNamedChildCount();
            for (int j = 0; j < named; j++) {
                TSNode part = node.getNamedChild(j);
                if (part.getType().equals("qualified_name") || part.getType().equals("identifier")) {
                    into.add(src.text(part));
                }
            }
            return;
        }
        // usings may sit inside a block namespace
        if (node.getType().equals("compilation_unit") || node.getType().equals("namespace_declaration")
                || node.getType().equals("declaration_list")) {
            int count = node.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                collectUsings(node.getNamedChild(i), src, into);
            }
        }
    }

    @Override
    protected String packagePrefix(TSNode root, Src src) {
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (child.getType().equals("file_scoped_namespace_declaration")) {
                TSNode name = child.getChildByFieldName("name");
                if (name != null && !name.isNull()) {
                    return src.text(name);
                }
            }
        }
        return "";
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        // C# base_list syntax cannot distinguish a base class from implemented interfaces
        // statically (both are just names after ':'), so every base is reported as EXTENDS.
        List<SuperRef> refs = new ArrayList<>();
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            if (child.getType().equals("base_list")) {
                collectBaseNames(child, src, refs);
            }
        }
        return refs;
    }

    private static void collectBaseNames(TSNode node, Src src, List<SuperRef> into) {
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            String type = child.getType();
            if (type.equals("identifier") || type.equals("qualified_name")
                    || type.equals("generic_name")) {
                into.add(new SuperRef(simpleTypeName(src.text(child)), RefKind.EXTENDS));
            } else {
                // e.g. primary_constructor_base_type in records: Base(args)
                collectBaseNames(child, src, into);
            }
        }
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        if (fieldDecl.getType().equals("property_declaration")) {
            TSNode name = fieldDecl.getChildByFieldName("name");
            return name == null || name.isNull() ? List.of() : List.of(src.text(name));
        }
        // field_declaration → variable_declaration → variable_declarator
        List<String> names = new ArrayList<>();
        collectDeclaratorNames(fieldDecl, src, names);
        return names;
    }

    private static void collectDeclaratorNames(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("variable_declarator")) {
            TSNode name = node.getChildByFieldName("name");
            if (name != null && !name.isNull()) {
                into.add(src.text(name));
            } else if (node.getNamedChildCount() > 0
                    && node.getNamedChild(0).getType().equals("identifier")) {
                into.add(src.text(node.getNamedChild(0)));
            }
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectDeclaratorNames(node.getNamedChild(i), src, into);
        }
    }
}
