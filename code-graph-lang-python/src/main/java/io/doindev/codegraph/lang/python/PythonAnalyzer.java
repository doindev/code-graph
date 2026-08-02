package io.doindev.codegraph.lang.python;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import io.doindev.codegraph.parse.TreeWalkAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Python extraction. Method arity excludes a leading {@code self}/{@code cls} parameter so call-site arity matches. */
public final class PythonAnalyzer extends TreeWalkAnalyzer {

    @Override
    public String languageId() {
        return "py";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("py");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterPython();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("class_definition");
    }

    @Override
    protected Set<String> functionDeclarationTypes() {
        return Set.of("function_definition");
    }

    @Override
    protected Set<String> callTypes() {
        return Set.of("call");
    }

    @Override
    protected Set<String> branchTypes() {
        return Set.of("if_statement", "elif_clause", "for_statement", "while_statement",
                "except_clause", "conditional_expression", "case_clause", "boolean_operator");
    }

    @Override
    protected int arityOf(TSNode decl, Src src) {
        TSNode params = decl.getChildByFieldName("parameters");
        if (params == null || params.isNull()) {
            return 0;
        }
        int count = params.getNamedChildCount();
        if (count > 0) {
            String first = src.text(params.getNamedChild(0));
            if (first.equals("self") || first.equals("cls")) {
                return count - 1;
            }
        }
        return count;
    }

    @Override
    protected List<String> importsOf(TSNode root, Src src) {
        List<String> imports = new ArrayList<>();
        collectImports(root, src, imports);
        return imports;
    }

    private static void collectImports(TSNode node, Src src, List<String> into) {
        String type = node.getType();
        if (type.equals("import_statement")) {
            int count = node.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = node.getNamedChild(i);
                if (child.getType().equals("dotted_name") || child.getType().equals("aliased_import")) {
                    into.add(src.text(child.getType().equals("aliased_import")
                            ? child.getChildByFieldName("name") : child));
                }
            }
            return;
        }
        if (type.equals("import_from_statement")) {
            TSNode module = node.getChildByFieldName("module_name");
            String prefix = module == null || module.isNull() ? "" : src.text(module) + ".";
            int count = node.getNamedChildCount();
            for (int i = 0; i < count; i++) {
                TSNode child = node.getNamedChild(i);
                if (child.getType().equals("dotted_name") && !src.text(child).equals(
                        prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1))) {
                    into.add(prefix + src.text(child));
                } else if (child.getType().equals("aliased_import")) {
                    TSNode name = child.getChildByFieldName("name");
                    if (name != null && !name.isNull()) {
                        into.add(prefix + src.text(name));
                    }
                }
            }
            return;
        }
        // imports can appear nested (inside functions); only recurse into containers
        int count = node.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            collectImports(node.getNamedChild(i), src, into);
        }
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        TSNode superclasses = typeDecl.getChildByFieldName("superclasses");
        if (superclasses == null || superclasses.isNull()) {
            return List.of();
        }
        List<SuperRef> refs = new ArrayList<>();
        int count = superclasses.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = superclasses.getNamedChild(i);
            if (child.getType().equals("identifier")) {
                refs.add(new SuperRef(src.text(child), RefKind.EXTENDS));
            } else if (child.getType().equals("attribute")) {
                TSNode attr = child.getChildByFieldName("attribute");
                if (attr != null && !attr.isNull()) {
                    refs.add(new SuperRef(src.text(attr), RefKind.EXTENDS));
                }
            }
        }
        return refs;
    }
}
