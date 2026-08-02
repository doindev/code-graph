package io.doindev.codegraph.lang.objc;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterObjc;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Objective-C extraction over tree-sitter-objc. Handles the ObjC-specific declaration forms
 * ({@code @interface}/{@code @implementation}/category/{@code @protocol}) and message sends
 * ({@code [recv sel:arg]}) as well as the embedded C {@code call_expression}s.
 *
 * <p>Selector-naming scheme: a method/message name is the full Objective-C selector. A unary
 * selector is the bare identifier ({@code speak}); a keyword selector joins each keyword part
 * with its trailing colon ({@code addValue:withValue:}). Method declarations and message sends
 * use the same scheme so a send resolves to its declaration in pass 2; arity is the number of
 * keyword parts (0 for a unary selector).
 *
 * <p>Extensions: claims only {@code .m}/{@code .mm}. ObjC headers ({@code .h}) are intentionally
 * left to the C analyzer.
 */
public final class ObjcAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "objc";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("m", "mm");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterObjc();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_interface", "class_implementation", "category_interface",
                "category_implementation", "protocol_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("method_declaration", "method_definition");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("message_expression", "call_expression");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "while_statement", "do_statement",
                "case_statement", "conditional_expression");
    }

    @Override
    protected String nameOf(TSNode decl, Src src) {
        if (functionDeclarationTypes().contains(decl.getType())) {
            return selectorName(decl.getChildByFieldName("selector"), src);
        }
        return super.nameOf(decl, src);
    }

    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode selector = decl.getChildByFieldName("selector");
        if (selector != null && !selector.isNull() && selector.getType().equals("keyword_selector")) {
            return countChildrenOfType(selector, "keyword_declarator");
        }
        return 0;
    }

    /** Full selector from a method's {@code selector} field (identifier or keyword_selector). */
    private static String selectorName(TSNode selector, Src src) {
        if (selector == null || selector.isNull()) {
            return null;
        }
        if (selector.getType().equals("keyword_selector")) {
            StringBuilder sb = new StringBuilder();
            int count = selector.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode part = selector.getNamedChild(i);
                if (part.getType().equals("keyword_declarator")) {
                    TSNode keyword = part.getChildByFieldName("keyword");
                    if (keyword != null && !keyword.isNull()) {
                        sb.append(src.text(keyword)).append(':');
                    }
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return src.text(selector);
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        if (call.getType().equals("message_expression")) {
            TSNode receiver = call.getChildByFieldName("receiver");
            String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
            TSNode selector = call.getChildByFieldName("selector");
            if (selector == null || selector.isNull()) {
                return null;
            }
            if (selector.getType().equals("keyword_argument_list")) {
                StringBuilder sb = new StringBuilder();
                int arity = 0;
                int count = selector.getNamedChildCount();
                for (int i = 0; i < count; i++) {
                    TSNode part = selector.getNamedChild(i);
                    if (part.getType().equals("keyword_argument")) {
                        TSNode keyword = part.getChildByFieldName("keyword");
                        if (keyword != null && !keyword.isNull()) {
                            sb.append(src.text(keyword)).append(':');
                        }
                        arity++;
                    }
                }
                return sb.length() == 0 ? null : new CallSite(sb.toString(), receiverHint, arity);
            }
            return new CallSite(src.text(selector), receiverHint, 0);
        }
        // embedded C call: foo(...)
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        return new CallSite(src.text(function), null, arity);
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        collectImports(root, src, imports);
        return imports;
    }

    private static void collectImports(TSNode node, Src src, List<String> into) {
        String type = node.getType();
        if (type.equals("preproc_import") || type.equals("preproc_include")) {
            TSNode path = node.getChildByFieldName("path");
            if (path != null && !path.isNull()) {
                into.add(stripDelimiters(src.text(path)));
            }
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
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            String type = child.getType();
            if (type.equals("superclass_reference")) {
                TSNode name = child.getChildByFieldName("name");
                if (name != null && !name.isNull()) {
                    refs.add(new SuperRef(src.text(name), RefKind.EXTENDS));
                }
            } else if (type.equals("protocol_qualifiers") || type.equals("protocol_reference_list")) {
                int adopted = child.getNamedChildCount();
                for (int j = 0; j < adopted; j++) {
                    TSNode protocol = child.getNamedChild(j);
                    if (protocol.getType().equals("identifier")) {
                        refs.add(new SuperRef(src.text(protocol), RefKind.IMPLEMENTS));
                    }
                }
            }
        }
        return refs;
    }

    private static int countChildrenOfType(TSNode node, String type) {
        int found = 0;
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            if (node.getNamedChild(i).getType().equals(type)) {
                found++;
            }
        }
        return found;
    }

    private static String stripDelimiters(String text) {
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '"' && last == '"') || (first == '<' && last == '>')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }
}
