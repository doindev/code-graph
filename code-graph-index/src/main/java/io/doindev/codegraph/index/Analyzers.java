package io.doindev.codegraph.index;

import io.doindev.codegraph.parse.LanguageAnalyzer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/** Extension-dispatch registry over the {@link LanguageAnalyzer} SPI. */
public final class Analyzers {

    private final Map<String, LanguageAnalyzer> byExtension;

    private Analyzers(Map<String, LanguageAnalyzer> byExtension) {
        this.byExtension = Map.copyOf(byExtension);
    }

    /** Discover analyzers on the classpath via {@link ServiceLoader}. */
    public static Analyzers discover() {
        return of(ServiceLoader.load(LanguageAnalyzer.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList());
    }

    public static Analyzers of(List<LanguageAnalyzer> analyzers) {
        Map<String, LanguageAnalyzer> byExtension = new HashMap<>();
        for (LanguageAnalyzer analyzer : analyzers) {
            analyzer = io.doindev.codegraph.index.mapping.CodeDatabaseMappings.wrap(analyzer);
            for (String extension : analyzer.fileExtensions()) {
                LanguageAnalyzer previous = byExtension.putIfAbsent(extension, analyzer);
                if (previous != null) {
                    throw new IllegalStateException("extension ." + extension + " claimed by both "
                            + previous.getClass().getName() + " and " + analyzer.getClass().getName());
                }
            }
        }
        // Mapping-only formats participate in the same file lifecycle; never launch a second scanner.
        for (String extension : List.of("xml", "prisma"))
            byExtension.putIfAbsent(extension, io.doindev.codegraph.index.mapping.CodeDatabaseMappings.standalone(extension));
        return new Analyzers(byExtension);
    }

    /** Analyzer for a repo-relative path, or {@code null} if the extension is not handled. */
    public LanguageAnalyzer forPath(String relPath) {
        int dot = relPath.lastIndexOf('.');
        if (dot < 0 || dot == relPath.length() - 1) {
            return null;
        }
        return byExtension.get(relPath.substring(dot + 1).toLowerCase());
    }

    public boolean isEmpty() {
        return byExtension.isEmpty();
    }
}
