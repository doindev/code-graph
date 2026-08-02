package io.doindev.codegraph.lang.dart;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterDart;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Dart extraction over tree-sitter-dart. The Dart grammar splits declarations in awkward ways:
 * a method is a {@code method_signature}/{@code function_signature} whose body is a *sibling*
 * {@code function_body} (not a child), and a call has no single call node — it is an
 * {@code identifier}/selector followed by a sibling {@code selector} wrapping the
 * {@code arguments}. Function/type declarations (name + arity), imports and supertypes are
 * extracted exactly; calls are best-effort by anchoring on the {@code arguments} node and
 * reading the callee name from the preceding sibling.
 *
 * <p>Because bodies are siblings of their signatures rather than children, calls inside a method
 * body are attributed to the enclosing type/file rather than the method symbol.
 */
public final class DartAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "dart";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("dart");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterDart();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_definition", "mixin_declaration", "enum_declaration",
                "extension_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        // function_signature is used both top-level and nested inside method_signature, so it
        // uniformly captures free functions and methods; constructor_signature covers ctors.
        return Set.of("function_signature", "constructor_signature");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("arguments");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "while_statement", "do_statement",
                "switch_statement_case", "conditional_expression", "if_null_expression");
    }

    @Override
    protected String nameOf(TSNode decl, Src src) {
        TSNode name = decl.getChildByFieldName("name");
        if (name != null && !name.isNull()) {
            return src.text(name);
        }
        // mixin_declaration exposes no `name` field; the declared name is its first identifier.
        int count = decl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = decl.getNamedChild(i);
            if (child.getType().equals("identifier")) {
                return src.text(child);
            }
        }
        return null;
    }

    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode params = decl.getChildByFieldName("parameters");
        if (params == null || params.isNull()) {
            params = firstChildOfType(decl, "formal_parameter_list");
        }
        return params == null || params.isNull() ? 0 : params.getNamedChildCount();
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        // `call` is the `arguments` node. Climb argument_part -> selector to reach the anchor
        // whose preceding sibling names the callee: `foo(...)`, `recv.foo(...)`, `super.foo(...)`.
        int arity = call.getNamedChildCount();
        TSNode anchor = call;
        TSNode parent = call.getParent();
        if (parent != null && !parent.isNull() && parent.getType().equals("argument_part")) {
            TSNode selector = parent.getParent();
            anchor = selector != null && !selector.isNull() && selector.getType().equals("selector")
                    ? selector : parent;
        }
        TSNode prev = anchor.getPrevNamedSibling();
        if (prev == null || prev.isNull()) {
            return null;
        }
        String prevType = prev.getType();
        if (prevType.equals("identifier")) {
            return new CallSite(src.text(prev), null, arity);
        }
        // selector chains: unconditional_assignable_selector `. name`, conditional_..._selector `?. name`
        TSNode nameNode = lastChildOfType(prev, "identifier");
        if (nameNode != null) {
            TSNode receiver = prev.getPrevNamedSibling();
            String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
            return new CallSite(src.text(nameNode), receiverHint, arity);
        }
        return new CallSite(src.text(prev), null, arity);
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        collectImports(root, src, imports);
        return imports;
    }

    private static void collectImports(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("uri")) {
            into.add(unquote(src.text(node)));
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectImports(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        List<SuperRef> refs = new ArrayList<>();
        TSNode superclass = typeDecl.getChildByFieldName("superclass");
        if (superclass != null && !superclass.isNull()) {
            collectDirectTypeIdentifiers(superclass, src, refs, RefKind.EXTENDS);
        }
        TSNode interfaces = typeDecl.getChildByFieldName("interfaces");
        if (interfaces != null && !interfaces.isNull()) {
            collectDirectTypeIdentifiers(interfaces, src, refs, RefKind.IMPLEMENTS);
        }
        return refs;
    }

    private static void collectDirectTypeIdentifiers(TSNode clause, Src src, List<SuperRef> into,
                                                     RefKind kind) {
        int count = clause.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = clause.getNamedChild(i);
            if (child.getType().equals("type_identifier")) {
                into.add(new SuperRef(src.text(child), kind));
            }
        }
    }

    private static TSNode firstChildOfType(TSNode node, String type) {
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    private static TSNode lastChildOfType(TSNode node, String type) {
        TSNode found = null;
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                found = child;
            }
        }
        return found;
    }

    private static String unquote(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }
}
