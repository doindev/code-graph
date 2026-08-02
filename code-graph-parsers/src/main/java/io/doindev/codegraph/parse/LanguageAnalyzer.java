package io.doindev.codegraph.parse;

import java.util.Set;

/**
 * Per-language extraction SPI. Implementations are discovered via {@link java.util.ServiceLoader}
 * ({@code META-INF/services/io.doindev.codegraph.parse.LanguageAnalyzer}) and must be
 * thread-safe: {@link #extract} is called from many virtual threads concurrently, one file per
 * call, with parser instances created per call.
 */
public interface LanguageAnalyzer {

    /** Language id, e.g. {@code java}, {@code ts}, {@code py}. Used in symbol IDs. */
    String languageId();

    /** File extensions (without dot) this analyzer handles, e.g. {@code ["java"]}. */
    Set<String> fileExtensions();

    /** Pass 1 — pure function of one file: declarations, local edges, unresolved refs. */
    FileFragment extract(SourceFile file);
}
