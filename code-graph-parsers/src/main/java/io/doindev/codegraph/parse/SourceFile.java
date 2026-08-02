package io.doindev.codegraph.parse;

/**
 * One source file handed to a {@link LanguageAnalyzer}.
 *
 * @param relPath repo-relative path with '/' separators
 * @param lang    language id (e.g. {@code java}), decided by extension dispatch
 * @param content full text content
 */
public record SourceFile(String relPath, String lang, String content) {
}
