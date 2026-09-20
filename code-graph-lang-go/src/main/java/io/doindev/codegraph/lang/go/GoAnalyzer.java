package io.doindev.codegraph.lang.go;

import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Go extraction over tree-sitter-go. Qualified names are prefixed with the declared package name. */
public final class GoAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "go";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("go");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterGo();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("type_spec");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_declaration", "method_declaration", "method_elem");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "expression_case", "type_case",
                "communication_case");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("field_declaration");
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        if (function.getType().equals("selector_expression")) {
            TSNode field = function.getChildByFieldName("field");
            if (field == null || field.isNull()) {
                return null;
            }
            TSNode operand = function.getChildByFieldName("operand");
            String receiverHint = operand == null || operand.isNull() ? null : src.text(operand);
            return new CallSite(src.text(field), receiverHint, arity);
        }
        if (function.getType().equals("identifier")) {
            return new CallSite(src.text(function), null, arity);
        }
        return null;
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (child.getType().equals("import_declaration")) {
                collectImportSpecs(child, src, imports);
            }
        }
        return imports;
    }

    private static void collectImportSpecs(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("import_spec")) {
            TSNode path = node.getChildByFieldName("path");
            if (path != null && !path.isNull()) {
                String text = src.text(path);
                if (text.length() >= 2 && (text.startsWith("\"") || text.startsWith("`"))) {
                    text = text.substring(1, text.length() - 1);
                }
                into.add(text);
            }
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectImportSpecs(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected String packagePrefix(TSNode root, Src src) {
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (child.getType().equals("package_clause")) {
                int named = child.getNamedChildCount();
                for (int j = 0; j < named; j++) {
                    TSNode part = child.getNamedChild(j);
                    if (part.getType().equals("package_identifier")) {
                        return src.text(part);
                    }
                }
            }
        }
        return "";
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        List<String> names = new ArrayList<>();
        int count = fieldDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = fieldDecl.getNamedChild(i);
            if (child.getType().equals("field_identifier")) {
                names.add(src.text(child));
            }
        }
        return names;
    }
}
