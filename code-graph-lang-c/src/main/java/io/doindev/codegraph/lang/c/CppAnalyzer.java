package io.doindev.codegraph.lang.c;

import io.doindev.codegraph.parse.RefKind;
import io.doindev.codegraph.parse.Src;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCpp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * C++ extraction over tree-sitter-cpp. Namespaces nest into qualified names like types do, so
 * a method defined inline in {@code namespace ns { class Widget { ... } }} gets the qualified
 * name {@code ns.Widget.method}.
 */
public final class CppAnalyzer extends CFamilyAnalyzer {

    @Override
    public String languageId() {
        return "cpp";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("cpp", "cc", "cxx", "hpp", "hh", "hxx");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterCpp();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("struct_specifier", "enum_specifier", "union_specifier",
                "class_specifier", "namespace_definition");
    }

    @Override
    protected List<SuperRef> supertypesOf(TSNode typeDecl, Src src) {
        List<SuperRef> refs = new ArrayList<>();
        int count = typeDecl.getNamedChildCount();
        for (int i = 0; i < count; i++) {
            TSNode child = typeDecl.getNamedChild(i);
            if (!child.getType().equals("base_class_clause")) {
                continue;
            }
            int bases = child.getNamedChildCount();
            for (int j = 0; j < bases; j++) {
                TSNode base = child.getNamedChild(j);
                String type = base.getType();
                if (type.equals("type_identifier") || type.equals("qualified_identifier")) {
                    refs.add(new SuperRef(src.text(base), RefKind.EXTENDS));
                }
            }
        }
        return refs;
    }
}
