package io.doindev.codegraph.lang.c;

import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared C/C++ extraction; the concrete subclasses differ in grammar, language id, extensions
 * and the extra C++ type forms (classes, namespaces, base clauses). The C-family grammars nest
 * a function's name inside a chain of {@code declarator} fields (pointer/function declarators),
 * so name and arity extraction drill that chain instead of reading a {@code name} field.
 *
 * <p>Known v1 gaps: {@code typedef struct { ... } Name;} loses the typedef name (the struct is
 * anonymous), enumerators are not extracted as variables, and method prototypes without bodies
 * are skipped entirely.
 */
abstract class CFamilyAnalyzer extends TreeWalkAnalyzer {

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_definition");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("field_declaration");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "while_statement", "do_statement",
                "case_statement", "conditional_expression");
    }

    @Override
    protected String nameOf(TSNode decl, Src src) {
        String type = decl.getType();
        if (isFunctionDeclaration(decl)) {
            TSNode name = drillDeclarator(decl.getChildByFieldName("declarator"));
            return name == null ? null : src.text(name);
        }
        // struct/enum/union/class heads double as type references (e.g. `struct order *o`);
        // only bodied specifiers declare a type.
        if (type.endsWith("_specifier")) {
            TSNode body = decl.getChildByFieldName("body");
            if (body == null || body.isNull()) {
                return null;
            }
        }
        return super.nameOf(decl, src);
    }

    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode current = decl.getChildByFieldName("declarator");
        while (current != null && !current.isNull()) {
            if (current.getType().equals("function_declarator")) {
                return countNamedNonComment(current.getChildByFieldName("parameters"));
            }
            current = current.getChildByFieldName("declarator");
        }
        return 0;
    }

    @Override
    protected CallSite callOf(TSNode call, Src src) {
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        int arity = countNamedNonComment(call.getChildByFieldName("arguments"));
        // receiver.field(...) / receiver->field(...)
        if (function.getType().equals("field_expression")) {
            TSNode field = function.getChildByFieldName("field");
            if (field == null || field.isNull()) {
                return null;
            }
            TSNode receiver = function.getChildByFieldName("argument");
            String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
            return new CallSite(src.text(field), receiverHint, arity);
        }
        // ns::f(...) — last segment is the call name, the scope is the receiver hint (C++ only)
        if (function.getType().equals("qualified_identifier")) {
            TSNode name = function;
            while (name.getType().equals("qualified_identifier")) {
                TSNode next = name.getChildByFieldName("name");
                if (next == null || next.isNull()) {
                    break;
                }
                name = next;
            }
            TSNode scope = function.getChildByFieldName("scope");
            String receiverHint = scope == null || scope.isNull() ? null : src.text(scope);
            return new CallSite(src.text(name), receiverHint, arity);
        }
        return new CallSite(src.text(function), null, arity);
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> includes = new ArrayList<>();
        collectIncludes(root, src, includes);
        return includes;
    }

    private static void collectIncludes(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("preproc_include")) {
            TSNode path = node.getChildByFieldName("path");
            if (path != null && !path.isNull()) {
                String text = src.text(path);
                if (text.length() >= 2 && (text.startsWith("\"") || text.startsWith("<"))) {
                    text = text.substring(1, text.length() - 1);
                }
                into.add(text);
            }
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectIncludes(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        // `int x, y;` puts each declarator as a named child; drill each chain to its
        // field_identifier. Chains through function_declarator are method prototypes, not fields.
        List<String> names = new ArrayList<>();
        int count = fieldDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode current = fieldDecl.getNamedChild(i);
            boolean function = false;
            while (true) {
                if (current.getType().equals("function_declarator")) {
                    function = true;
                }
                TSNode next = current.getChildByFieldName("declarator");
                if (next == null || next.isNull()) {
                    break;
                }
                current = next;
            }
            if (!function && current.getType().equals("field_identifier")) {
                names.add(src.text(current));
            }
        }
        return names;
    }

    @Override
    protected String signatureOf(TSNode decl, String name, Src src) {
        TSNode current = decl.getChildByFieldName("declarator");
        while (current != null && !current.isNull()) {
            if (current.getType().equals("function_declarator")) {
                return super.signatureOf(current, name, src);
            }
            current = current.getChildByFieldName("declarator");
        }
        return super.signatureOf(decl, name, src);
    }

    /**
     * Follows the {@code declarator} field chain (pointer/function declarators) to the terminal
     * name node: identifier, field_identifier, qualified_identifier, destructor_name, ...
     */
    static TSNode drillDeclarator(TSNode declarator) {
        if (declarator == null || declarator.isNull()) {
            return null;
        }
        TSNode current = declarator;
        while (true) {
            TSNode next = current.getChildByFieldName("declarator");
            if (next == null || next.isNull()) {
                return current;
            }
            current = next;
        }
    }

    private static int countNamedNonComment(TSNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        int arity = 0;
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            if (!node.getNamedChild(i).getType().equals("comment")) {
                arity++;
            }
        }
        return arity;
    }
}
