package io.doindev.codegraph.lang.c;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterC;

import java.util.Set;

/** C extraction over tree-sitter-c. */
public final class CAnalyzer extends CFamilyAnalyzer {

    @Override
    public String languageId() {
        return "c";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("c", "h");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterC();
    }

    @Override
    protected Set<String> typeDeclarationTypes() {
        return Set.of("struct_specifier", "enum_specifier", "union_specifier");
    }
}
