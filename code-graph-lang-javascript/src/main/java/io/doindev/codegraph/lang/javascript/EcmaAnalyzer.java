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
 * language id and extensions. Known v1 gap: arrow functions bound to consts are not extracted
 * as declarations (their bodies are still scanned for calls).
 */
abstract class EcmaAnalyzer extends TreeWalkAnalyzer {

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
            return new CallSite(lastDot >= 0 ? name.substring(lastDot + 1) : name, null, arity);
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
