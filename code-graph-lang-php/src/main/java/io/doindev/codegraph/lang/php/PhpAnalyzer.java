package io.doindev.codegraph.lang.php;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPhp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PHP extraction over tree-sitter-php. The declared {@code namespace} (backslash-separated,
 * as written) becomes the qualified-name prefix; {@code use} imports are collected verbatim.
 */
public final class PhpAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "php";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("php");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterPhp();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_declaration", "interface_declaration", "trait_declaration",
                "enum_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_definition", "method_declaration");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("function_call_expression", "member_call_expression",
                "scoped_call_expression", "object_creation_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("property_declaration");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "else_if_clause", "for_statement", "foreach_statement",
                "while_statement", "do_statement", "case_statement", "catch_clause",
                "conditional_expression");
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        int arity = callArity(call);
        switch (call.getType()) {
            case "function_call_expression" -> {
                TSNode function = call.getChildByFieldName("function");
                if (function == null || function.isNull()) {
                    return null;
                }
                return new CallSite(stripLeadingBackslash(src.text(function)), null, arity);
            }
            case "member_call_expression", "scoped_call_expression" -> {
                TSNode name = call.getChildByFieldName("name");
                if (name == null || name.isNull()) {
                    return null;
                }
                TSNode receiver = call.getChildByFieldName(
                        call.getType().equals("scoped_call_expression") ? "scope" : "object");
                String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
                return new CallSite(src.text(name), receiverHint, arity);
            }
            case "object_creation_expression" -> {
                String className = creationClassName(call, src);
                return className == null ? null : new CallSite(className, null, arity);
            }
            default -> {
                return null;
            }
        }
    }

    /** Class name of {@code new Foo(...)}; the first named child that is not the arguments. */
    private static String creationClassName(TSNode call, Src src) {
        int count = call.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = call.getNamedChild(i);
            if (!child.getType().equals("arguments")) {
                // \App\Foo → Foo: resolution matches on the simple name
                String text = stripLeadingBackslash(src.text(child));
                int lastSep = text.lastIndexOf('\\');
                return lastSep >= 0 ? text.substring(lastSep + 1) : text;
            }
        }
        return null;
    }

    private int callArity(TSNode call) {
        TSNode args = call.getChildByFieldName("arguments");
        if (args == null || args.isNull()) {
            // object_creation_expression carries arguments as a plain child
            int count = call.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = call.getNamedChild(i);
                if (child.getType().equals("arguments")) {
                    return child.getNamedChildCount();
                }
            }
            return 0;
        }
        return args.getNamedChildCount();
    }

    private static String stripLeadingBackslash(String name) {
        return name.startsWith("\\") ? name.substring(1) : name;
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        collectUses(root, src, imports);
        return imports;
    }

    private static void collectUses(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("namespace_use_declaration")) {
            int count = node.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode clause = node.getNamedChild(i);
                if (!clause.getType().equals("namespace_use_clause")
                        || clause.getNamedChildCount() == 0) {
                    continue;
                }
                // first child is the imported name (backslash-separated, as written);
                // a trailing namespace_aliasing_clause is ignored
                into.add(src.text(clause.getNamedChild(0)));
            }
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectUses(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected String packagePrefix(TSNode root, Src src) {
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (child.getType().equals("namespace_definition")) {
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
        List<SuperRef> refs = new ArrayList<>();
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            if (child.getType().equals("base_clause")) {
                collectSuperNames(child, src, refs, RefKind.EXTENDS);
            } else if (child.getType().equals("class_interface_clause")) {
                collectSuperNames(child, src, refs, RefKind.IMPLEMENTS);
            }
        }
        return refs;
    }

    private static void collectSuperNames(TSNode clause, Src src, List<SuperRef> into, RefKind kind) {
        int count = clause.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            String text = stripLeadingBackslash(src.text(clause.getNamedChild(i)));
            into.add(new SuperRef(text, kind));
        }
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        List<String> names = new ArrayList<>();
        int count = fieldDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = fieldDecl.getNamedChild(i);
            if (!child.getType().equals("property_element")) {
                continue;
            }
            TSNode variable = child.getNamedChildCount() > 0 ? child.getNamedChild(0) : null;
            if (variable != null && variable.getType().equals("variable_name")) {
                String text = src.text(variable);
                names.add(text.startsWith("$") ? text.substring(1) : text);
            }
        }
        return names;
    }
}
