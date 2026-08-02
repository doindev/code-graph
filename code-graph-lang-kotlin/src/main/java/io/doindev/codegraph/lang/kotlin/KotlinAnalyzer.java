package io.doindev.codegraph.lang.kotlin;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterKotlin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Kotlin extraction over tree-sitter-kotlin (fwcd grammar). Qualified names include the declared
 * package, matching the Java analyzer.
 *
 * <p>Grammar quirks this analyzer works around: the grammar exposes almost no fields, so names
 * are located positionally ({@link #nameOf}); expression-body functions ({@code fun f() = x})
 * do not parse at all (grammar 0.3.8.1 limitation — such declarations are simply lost); a
 * trailing lambda argument ({@code list.map { ... }}) is an {@code annotated_lambda}, not a
 * {@code value_argument}, so it is not counted in call arity.
 */
public final class KotlinAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "kt";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("kt", "kts");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterKotlin();
    }

    /** {@code class_declaration} covers classes, interfaces and enums; {@code object_declaration} singletons. */
    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_declaration", "object_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_declaration");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_expression", "when_entry", "for_statement", "while_statement",
                "do_while_statement", "catch_block");
    }

    /** The grammar has no {@code name} fields; the declared name is the first identifier child. */
    @Override
    protected String nameOf(TSNode decl, Src src) {
        int count = decl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = decl.getNamedChild(i);
            String type = child.getType();
            if (type.equals("type_identifier") || type.equals("simple_identifier")) {
                return src.text(child);
            }
        }
        return null;
    }

    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode params = namedChildOfType(decl, "function_value_parameters");
        if (params == null) {
            return 0;
        }
        int arity = 0;
        int count = params.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            String type = params.getNamedChild(i).getType();
            if (type.equals("parameter") || type.equals("class_parameter")) {
                arity++;
            }
        }
        return arity;
    }

    @Override
    protected String signatureOf(TSNode decl, String name, Src src) {
        TSNode params = namedChildOfType(decl, "function_value_parameters");
        String paramText = params == null ? "()" : src.text(params);
        if (paramText.length() > 80) {
            paramText = paramText.substring(0, 77) + "...";
        }
        return name + paramText.replaceAll("\\s+", " ");
    }

    /**
     * {@code call_expression} = callee expression + {@code call_suffix}. For navigation calls
     * ({@code obj.save(x)}) the call name is the identifier inside the {@code navigation_suffix}
     * and the receiver is the expression before it.
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
            TSNode suffix = namedChildOfType(callee, "navigation_suffix");
            TSNode name = suffix == null ? null : namedChildOfType(suffix, "simple_identifier");
            if (name == null) {
                return null;
            }
            TSNode receiver = callee.getNamedChild(0);
            String receiverHint = receiver.isNull() ? null : src.text(receiver);
            return new CallSite(src.text(name), receiverHint, arity);
        }
        return null; // lambda/expression callees carry no navigable name
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
            if (!child.getType().equals("import_list")) {
                continue;
            }
            int headers = child.getNamedChildCount();
            for (int j = 0; j < headers; j++) {
                TSNode header = child.getNamedChild(j);
                if (header.getType().equals("import_header")) {
                    TSNode identifier = namedChildOfType(header, "identifier");
                    if (identifier != null) {
                        imports.add(src.text(identifier));
                    }
                }
            }
        }
        return imports;
    }

    @Override
    protected String packagePrefix(TSNode root, Src src) {
        TSNode header = namedChildOfType(root, "package_header");
        TSNode identifier = header == null ? null : namedChildOfType(header, "identifier");
        return identifier == null ? "" : src.text(identifier);
    }

    /**
     * Delegation specifiers: {@code : Entity(), Payable} — a {@code constructor_invocation}
     * (superclass) or bare {@code user_type} (interface). The grammar does not distinguish the
     * two beyond the constructor call, so both map to EXTENDS.
     */
    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        List<SuperRef> refs = new ArrayList<>();
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            if (!child.getType().equals("delegation_specifier")) {
                continue;
            }
            TSNode name = lastTypeIdentifier(child);
            if (name != null) {
                refs.add(new SuperRef(src.text(name), RefKind.EXTENDS));
            }
        }
        return refs;
    }

    /** Deepest/last {@code type_identifier} — the simple name of a possibly qualified user type. */
    private static TSNode lastTypeIdentifier(TSNode node) {
        TSNode found = null;
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = node.getNamedChild(i);
            if (child.getType().equals("type_identifier")) {
                found = child;
            } else {
                TSNode nested = lastTypeIdentifier(child);
                if (nested != null) {
                    found = nested;
                }
            }
        }
        return found;
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
