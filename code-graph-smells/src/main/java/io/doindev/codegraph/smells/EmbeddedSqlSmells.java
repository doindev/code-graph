package io.doindev.codegraph.smells;

import io.doindev.codegraph.config.CodeGraphConfig;
import io.doindev.codegraph.lang.sql.SqlQueries;
import io.doindev.codegraph.model.Node;
import io.doindev.codegraph.model.NodeKind;
import io.doindev.codegraph.query.GraphQuery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Embedded-SQL detector: finds SQL statements hiding inside host-language string literals and flags
 * the ones assembled by concatenation/interpolation as likely SQL-injection sites. Needs the
 * working tree (it reads file contents), so like the history detectors it is registered only when
 * the {@link SmellEngine} was constructed with a repo root.
 *
 * <p><strong>How it works.</strong> For every {@code FILE} node the graph knows (skipping
 * {@code .sql} files — their statements are not "embedded"), it reads the source, pulls quoted
 * string literals (single/double/backtick plus Java text blocks and Python triple-quotes) with a
 * pragmatic regex, and keeps those whose content {@link SqlQueries#looksLikeSql}. Each hit records
 * the statement kind, referenced tables and 1-based line.
 *
 * <p><strong>Injection heuristic.</strong> A literal is treated as concatenated (higher severity)
 * when a splice operator sits immediately next to it ({@code +}, {@code .}, {@code ||}, {@code %})
 * or an interpolation marker sits inside it ({@code ${...}}, {@code #{...}}, {@code %s}, or an
 * f-string {@code {expr}}). A plain constant/parameterized statement ({@code ?}, {@code :name},
 * {@code $1}) stays at the informational default. This is a lexical heuristic, not data-flow
 * analysis: it cannot prove a variable is untrusted, only that the query was not a static constant.
 */
final class EmbeddedSqlSmells implements SmellEngine.Detector {

    private static final long MAX_FILE_BYTES = 1024L * 1024;

    /** Multiline literal forms first so their inner quotes are not re-matched as single-line strings. */
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern TRIPLE_SQUOTE = Pattern.compile("'''(.*?)'''", Pattern.DOTALL);
    private static final Pattern DQUOTE = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern SQUOTE = Pattern.compile("'((?:[^'\\\\\\n]|\\\\.)*)'");
    private static final Pattern BACKTICK = Pattern.compile("`([^`]*)`", Pattern.DOTALL);

    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{|#\\{|%[sd]");
    private static final Pattern FSTRING_EXPR = Pattern.compile("\\{[^}]+}");

    private final Path repoRoot;

    EmbeddedSqlSmells(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    @Override
    public String id() {
        return "embedded-sql";
    }

    @Override
    public String defaultSeverity() {
        return "info";
    }

    @Override
    public List<SmellFinding> detect(GraphQuery graph, CodeGraphConfig config, SmellEngine engine) {
        String base = engine.severity(this);
        List<SmellFinding> findings = new ArrayList<>();
        for (Node node : graph.allNodes(Set.of(NodeKind.FILE))) {
            String relPath = node.relPath();
            if (relPath == null || relPath.toLowerCase(java.util.Locale.ROOT).endsWith(".sql")) {
                continue; // .sql statements are not "embedded" in a host language
            }
            Path file = repoRoot.resolve(relPath);
            String text;
            try {
                if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                    continue;
                }
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException ignored) {
                continue; // unreadable / non-UTF-8 file contributes nothing
            }
            scan(text, relPath, base, findings);
        }
        return findings;
    }

    private void scan(String text, String relPath, String base, List<SmellFinding> findings) {
        boolean[] claimed = new boolean[text.length()];
        // Multiline forms first, then single-line strings whose start is not already inside one.
        collect(TEXT_BLOCK, text, claimed, relPath, base, findings, true);
        collect(TRIPLE_SQUOTE, text, claimed, relPath, base, findings, true);
        collect(BACKTICK, text, claimed, relPath, base, findings, false);
        collect(DQUOTE, text, claimed, relPath, base, findings, false);
        collect(SQUOTE, text, claimed, relPath, base, findings, false);
    }

    private void collect(Pattern pattern, String text, boolean[] claimed, String relPath,
                         String base, List<SmellFinding> findings, boolean claim) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int start = m.start();
            if (claimed[start]) {
                continue; // inside a literal already accounted for
            }
            String content = m.group(1);
            if (!SqlQueries.looksLikeSql(content)) {
                if (claim) {
                    mark(claimed, m.start(), m.end());
                }
                continue;
            }
            boolean concatenated = isConcatenated(text, start, m.end(), content);
            String kind = SqlQueries.statementKind(content.strip());
            List<String> tables = SqlQueries.referencedTables(content);
            int line = lineOf(text, start);
            String severity = concatenated ? escalate(base) : base;
            findings.add(new SmellFinding(id(), "file:" + relPath, severity,
                    SmellEngine.ev(
                            "kind", kind,
                            "line", String.valueOf(line),
                            "tables", String.join(",", tables),
                            "built", concatenated ? "concatenation (injection risk)" : "literal/parameterized"),
                    "SQL embedded in a host-language string literal"
                            + (concatenated
                                ? " is assembled by concatenation/interpolation — bind parameters instead of splicing values"
                                : " — prefer a mapper/repository, and keep it parameterized")));
            if (claim) {
                mark(claimed, m.start(), m.end());
            }
        }
    }

    /** A configured info default escalates to warning for concatenated queries; warning/error are kept. */
    private static String escalate(String base) {
        return "info".equals(base) ? "warning" : base;
    }

    private static boolean isConcatenated(String text, int start, int end, String content) {
        if (INTERPOLATION.matcher(content).find()) {
            return true;
        }
        char prefix = start > 0 ? text.charAt(start - 1) : ' ';
        if ((prefix == 'f' || prefix == 'F') && FSTRING_EXPR.matcher(content).find()) {
            return true; // Python f-string with an embedded expression
        }
        return spliceOperator(text, start - 1, -1) || spliceOperator(text, end, 1);
    }

    /** True when the nearest non-space char in {@code dir} from {@code from} is a splice operator. */
    private static boolean spliceOperator(String text, int from, int dir) {
        int i = from;
        while (i >= 0 && i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i += dir;
        }
        if (i < 0 || i >= text.length()) {
            return false;
        }
        char c = text.charAt(i);
        if (c == '+' || c == '.' || c == '%') {
            return true;
        }
        if (c == '|') {
            int j = i + dir;
            return j >= 0 && j < text.length() && text.charAt(j) == '|';
        }
        return false;
    }

    private static void mark(boolean[] claimed, int start, int end) {
        for (int i = start; i < end && i < claimed.length; i++) {
            claimed[i] = true;
        }
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
