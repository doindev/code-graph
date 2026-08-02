package io.doindev.codegraph.lang.javascript;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterTsx;

import java.util.Set;

/** TSX — TypeScript with JSX. Shares the {@code ts} language id so symbols unify across .ts/.tsx. */
public final class TsxAnalyzer extends EcmaAnalyzer {

    @Override
    public String languageId() {
        return "ts";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of("tsx");
    }

    @Override
    protected TSLanguage newLanguage() {
        return new TreeSitterTsx();
    }
}
