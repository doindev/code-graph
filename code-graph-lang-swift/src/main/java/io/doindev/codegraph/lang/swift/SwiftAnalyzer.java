package io.doindev.codegraph.lang.swift;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterSwift;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Swift extraction over tree-sitter-swift. Swift has no packages, so qualified names are rooted at
 * the top-level declaration.
 *
 * <p>Grammar notes: {@code class}, {@code struct}, {@code enum}, {@code actor} and
 * {@code extension} all parse as {@code class_declaration} (distinguished only by a
 * {@code declaration_kind} child); {@code protocol} is a {@code protocol_declaration}. Parameters
 * are direct {@code parameter} children of the declaration rather than a {@code parameters} field,
 * so arity is counted directly. A navigation call ({@code obj.m(x)}) has a
 * {@code navigation_expression} callee; arguments live under {@code call_suffix &gt; value_arguments}.
 * Swift cannot statically distinguish a superclass from an adopted protocol, so every
 * {@code inheritance_specifier} is recorded as EXTENDS.
 */
public final class SwiftAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "swift";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("swift");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterSwift();
    }

    /** {@code class_declaration} covers class/struct/enum/actor/extension; protocols are separate. */
    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_declaration", "protocol_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_declaration", "init_declaration", "deinit_declaration",
                "protocol_function_declaration");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("property_declaration");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "guard_statement", "switch_statement", "for_statement",
                "while_statement", "catch_block");
    }

    /** Parameters are direct {@code parameter} children (there is no {@code parameters} field). */
    @Override
    protected int arityOf(TSNode decl, Src src) {
        int arity = 0;
        int count = decl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            if (decl.getNamedChild(i).getType().equals("parameter")) {
                arity++;
            }
        }
        return arity;
    }

    @Override
    protected String signatureOf(TSNode decl, String name, Src src) {
        List<String> params = new ArrayList<>();
        int count = decl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = decl.getNamedChild(i);
            if (child.getType().equals("parameter")) {
                params.add(src.text(child).replaceAll("\\s+", " "));
            }
        }
        String signature = name + "(" + String.join(", ", params) + ")";
        return signature.length() > 100 ? signature.substring(0, 97) + "..." : signature;
    }

    /**
     * {@code m(x)} has a {@code simple_identifier} callee; {@code obj.m(x)} a
     * {@code navigation_expression} whose {@code navigation_suffix} holds the method name and whose
     * {@code target} is the receiver.
     */
    @Override
    protected CallSite callOf(TSNode call, Src src) {
        if (call.getNamedChildCount() == 0) {
            return null;
        }
        TSNode callee = call.getNamedChild(0);
        int arity = callArity(call);
        if (callee.getType().equals("simple_identifier")) {
            return new CallSite(src.text(callee), null, arity);
        }
        if (callee.getType().equals("navigation_expression")) {
            TSNode suffix = callee.getChildByFieldName("suffix");
            TSNode name = suffix == null || suffix.isNull()
                    ? null : suffix.getChildByFieldName("suffix");
            if (name == null || name.isNull()) {
                return null;
            }
            TSNode receiver = callee.getChildByFieldName("target");
            String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
            return new CallSite(src.text(name), receiverHint, arity);
        }
        return null;
    }

    private static int callArity(TSNode call) {
        TSNode suffix = namedChildOfType(call, "call_suffix");
        TSNode args = suffix == null ? null : namedChildOfType(suffix, "value_arguments");
        if (args == null) {
            return 0;
        }
        int arity = 0;
        int count = args.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            if (args.getNamedChild(i).getType().equals("value_argument")) {
                arity++;
            }
        }
        return arity;
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (!child.getType().equals("import_declaration")) {
                continue;
            }
            TSNode identifier = namedChildOfType(child, "identifier");
            if (identifier != null) {
                imports.add(src.text(identifier));
            }
        }
        return imports;
    }

    /** Every {@code inheritance_specifier} (superclass or adopted protocol) becomes an EXTENDS ref. */
    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        List<SuperRef> refs = new ArrayList<>();
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            if (!child.getType().equals("inheritance_specifier")) {
                continue;
            }
            TSNode from = child.getChildByFieldName("inherits_from");
            TSNode name = from == null || from.isNull() ? null : from;
            if (name != null) {
                refs.add(new SuperRef(src.text(name), RefKind.EXTENDS));
            }
        }
        return refs;
    }

    private static TSNode lastTypeIdentifier(TSNode node) {
        if (node.getType().equals("type_identifier")) {
            return node;
        }
        TSNode found = null;
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode nested = lastTypeIdentifier(node.getNamedChild(i));
            if (nested != null) {
                found = nested;
            }
        }
        return found;
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        TSNode pattern = fieldDecl.getChildByFieldName("name");
        if (pattern == null || pattern.isNull()) {
            return List.of();
        }
        TSNode bound = pattern.getChildByFieldName("bound_identifier");
        if (bound == null || bound.isNull()) {
            return List.of();
        }
        return List.of(src.text(bound));
    }

    private static TSNode namedChildOfType(TSNode node, String type) {
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }
}
