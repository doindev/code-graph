package io.doindev.codegraph.lang.javascript;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterJavascript;

import java.util.Set;

/** JavaScript (and JSX — the javascript grammar parses JSX). */
public final class JavaScriptAnalyzer extends EcmaAnalyzer {

    @Override
    public String languageId() {
        return "js";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("js", "jsx", "mjs", "cjs");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterJavascript();
    }
}
