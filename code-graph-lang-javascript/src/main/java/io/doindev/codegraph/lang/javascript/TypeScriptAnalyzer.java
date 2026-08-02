package io.doindev.codegraph.lang.javascript;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterTypescript;

import java.util.Set;

/** TypeScript ({@code .ts}; {@code .tsx} is handled by {@link TsxAnalyzer}). */
public final class TypeScriptAnalyzer extends EcmaAnalyzer {

    @Override
    public String languageId() {
        return "ts";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("ts", "mts", "cts");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterTypescript();
    }
}
