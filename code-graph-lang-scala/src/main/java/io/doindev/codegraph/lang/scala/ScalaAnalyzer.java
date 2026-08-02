package io.doindev.codegraph.lang.scala;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterScala;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Scala extraction over tree-sitter-scala. Qualified names include the declared package, matching
 * the Java analyzer.
 *
 * <p>Grammar notes: case classes are ordinary {@code class_definition}s carrying a {@code case}
 * modifier, so they need no special handling; a navigation call ({@code obj.m(x)}) has a
 * {@code field_expression} in the {@code function} field whose {@code field} child is the method
 * name and whose {@code value} child is the receiver; {@code extends A with B} exposes each mixed
 * type as a {@code type_identifier} under the {@code extends_clause} (the {@code with} keyword is an
 * anonymous child), all recorded as EXTENDS.
 */
public final class ScalaAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "scala";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("scala", "sc");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterScala();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_definition", "object_definition", "trait_definition", "enum_definition");
    }

    /** {@code function_definition} is a {@code def} with a body; {@code function_declaration} an abstract def. */
    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_definition", "function_declaration");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("val_definition", "var_definition");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_expression", "match_expression", "case_clause", "for_expression",
                "while_expression", "try_expression", "catch_clause");
    }

    /**
     * {@code obj.m(x)} parses as a {@code call_expression} whose {@code function} is a
     * {@code field_expression} ({@code value}=receiver, {@code field}=method); a bare {@code m(x)}
     * has an {@code identifier} function.
     */
    @Override
    protected CallSite callOf(TSNode call, Src src) {
        TSNode function = call.getChildByFieldName("function");
        if (function == null || function.isNull()) {
            return null;
        }
        TSNode args = call.getChildByFieldName("arguments");
        int arity = args == null || args.isNull() ? 0 : args.getNamedChildCount();
        if (function.getType().equals("field_expression")) {
            TSNode field = function.getChildByFieldName("field");
            if (field == null || field.isNull()) {
                return null;
            }
            TSNode receiver = function.getChildByFieldName("value");
            String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
            return new CallSite(src.text(field), receiverHint, arity);
        }
        if (function.getType().equals("identifier")) {
            return new CallSite(src.text(function), null, arity);
        }
        return null; // generic-applied or otherwise unnavigable callee
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
            StringBuilder path = new StringBuilder();
            int parts = child.getNamedChildCount();
            for (int j = 0; j < parts; j++) {
                TSNode part = child.getNamedChild(j);
                if (part.getType().equals("identifier")) {
                    if (path.length() > 0) {
                        path.append('.');
                    }
                    path.append(src.text(part));
                }
            }
            if (path.length() > 0) {
                imports.add(path.toString());
            }
        }
        return imports;
    }

    @Override
    protected String packagePrefix(TSNode root, Src src) {
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (child.getType().equals("package_clause")) {
                TSNode name = child.getChildByFieldName("name");
                if (name != null && !name.isNull()) {
                    return src.text(name);
                }
            }
        }
        return "";
    }

    /** {@code extends A with B} — every mixed-in {@code type_identifier} becomes an EXTENDS ref. */
    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        TSNode extend = typeDecl.getChildByFieldName("extend");
        if (extend == null || extend.isNull()) {
            return List.of();
        }
        List<SuperRef> refs = new ArrayList<>();
        collectTypeIdentifiers(extend, src, refs);
        return refs;
    }

    private static void collectTypeIdentifiers(TSNode node, Src src, List<SuperRef> into) {
        if (node.getType().equals("type_identifier")) {
            into.add(new SuperRef(src.text(node), RefKind.EXTENDS));
            return;
        }
        if (node.getType().equals("generic_type")) {
            TSNode raw = node.getChildByFieldName("type");
            if (raw != null && !raw.isNull()) {
                collectTypeIdentifiers(raw, src, into);
            }
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectTypeIdentifiers(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        TSNode pattern = fieldDecl.getChildByFieldName("pattern");
        if (pattern == null || pattern.isNull()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        collectPatternNames(pattern, src, names);
        return names;
    }

    private static void collectPatternNames(TSNode node, Src src, List<String> into) {
        if (node.getType().equals("identifier")) {
            into.add(src.text(node));
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectPatternNames(node.getNamedChild(i), src, into);
        }
    }
}
