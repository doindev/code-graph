package io.doindev.codegraph.lang.java;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Java extraction over tree-sitter-java. Qualified names include the declared package. */
public final class JavaAnalyzer extends TreeWalkAnalyzer {

    @Override
    protected io.doindev.codegraph.parse.SemanticHints semanticHints(TSNode root, Src src) {
        return new JavaSemanticHints(root, src, packagePrefix(root, src), importsOf(root, src));
    }

    @Override
    public String languageId() {
        return "java";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("java");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterJava();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_declaration", "interface_declaration", "enum_declaration",
                "record_declaration", "annotation_type_declaration");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("method_declaration", "constructor_declaration");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("method_invocation", "object_creation_expression");
    }

    @Override
    protected Set<String> fieldDeclarationTypes() {
        return Set.of("field_declaration");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "for_statement", "enhanced_for_statement", "while_statement",
                "do_statement", "catch_clause", "conditional_expression", "switch_label");
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
            String name = src.text(type);
            int generic = name.indexOf('<');
            if (generic > 0) {
                name = name.substring(0, generic);
            }
            int lastDot = name.lastIndexOf('.');
            return new CallSite(lastDot >= 0 ? name.substring(lastDot + 1) : name, null, arity);
        }
        TSNode name = call.getChildByFieldName("name");
        if (name == null || name.isNull()) {
            return null;
        }
        TSNode receiver = call.getChildByFieldName("object");
        String receiverHint = receiver == null || receiver.isNull() ? null : src.text(receiver);
        return new CallSite(src.text(name), receiverHint, arity);
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
            String declaration = src.text(child).replaceFirst("^import\\s+", "").replaceFirst(";\\s*$", "").trim();
            imports.add(declaration);
        }
        return imports;
    }

    @Override
    protected String packagePrefix(TSNode root, Src src) {
        int count = root.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = root.getNamedChild(i);
            if (child.getType().equals("package_declaration")) {
                int named = child.getNamedChildCount();
                for (int j = 0; j < named; j++) {
                    TSNode part = child.getNamedChild(j);
                    if (part.getType().equals("scoped_identifier") || part.getType().equals("identifier")) {
                        return src.text(part);
                    }
                }
            }
        }
        return "";
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        List<SuperRef> refs = new ArrayList<>();
        TSNode superclass = typeDecl.getChildByFieldName("superclass");
        if (superclass != null && !superclass.isNull()) {
            collectTypeIdentifiers(superclass, src, refs, RefKind.EXTENDS);
        }
        TSNode interfaces = typeDecl.getChildByFieldName("interfaces");
        if (interfaces != null && !interfaces.isNull()) {
            collectTypeIdentifiers(interfaces, src, refs, RefKind.IMPLEMENTS);
        }
        // interface Foo extends Bar — tree-sitter-java exposes extends_interfaces as a plain child
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            if (child.getType().equals("extends_interfaces")) {
                collectTypeIdentifiers(child, src, refs, RefKind.EXTENDS);
            }
        }
        return refs;
    }

    private static void collectTypeIdentifiers(TSNode node, Src src, List<SuperRef> into, RefKind kind) {
        if (node.getType().equals("type_identifier")) {
            into.add(new SuperRef(src.text(node), kind));
            return;
        }
        if (node.getType().equals("generic_type")) {
            // Foo<Bar> — the raw type is the reference that matters
            int count = node.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = node.getNamedChild(i);
                if (child.getType().equals("type_identifier")) {
                    into.add(new SuperRef(src.text(child), kind));
                    return;
                }
            }
            return;
        }
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectTypeIdentifiers(node.getNamedChild(i), src, into, kind);
        }
    }

    @Override
    protected List<String> fieldNamesOf(TSNode fieldDecl, Src src) {
        List<String> names = new ArrayList<>();
        int count = fieldDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = fieldDecl.getNamedChild(i);
            if (child.getType().equals("variable_declarator")) {
                TSNode name = child.getChildByFieldName("name");
                if (name != null && !name.isNull()) {
                    names.add(src.text(name));
                }
            }
        }
        return names;
    }
}
