package io.doindev.codegraph.lang.sql;

import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterSql;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless helpers for reasoning about arbitrary SQL text — the reusable core the embedded-SQL
 * smell detector builds on. Everything here is best-effort and defensive: the methods never throw
 * on malformed input, and {@link #referencedTables} degrades from a real tree-sitter-sql parse to
 * a regex heuristic when the grammar cannot cope (parameter placeholders, exotic dialects).
 *
 * <p><strong>Limitations.</strong> {@code looksLikeSql} recognizes statement <em>shapes</em>, not
 * valid SQL; it is deliberately conservative to avoid flagging prose. {@code referencedTables}
 * returns base relations named after {@code FROM}/{@code JOIN}/{@code INTO}/{@code UPDATE} only —
 * it does not resolve CTE aliases, subqueries, or synonyms, and keeps {@code schema.table}
 * qualifiers verbatim.
 */
public final class SqlQueries {

    private SqlQueries() {
    }

    /** Statements need at least this many characters before we treat them as SQL rather than prose. */
    private static final int MIN_LENGTH = 12;

    // Statement-shape probes. Each requires enough co-occurring keywords to distinguish SQL from prose.
    private static final Pattern SELECT_FROM = Pattern.compile("(?is)\\bselect\\b.*\\bfrom\\b");
    private static final Pattern INSERT_INTO = Pattern.compile("(?is)\\binsert\\s+into\\b");
    // A real UPDATE assigns: SET <col> = ... — the "= " requirement rejects prose like "set aside".
    private static final Pattern UPDATE_SET = Pattern.compile("(?is)\\bupdate\\b.+\\bset\\s+\\S+\\s*=");
    private static final Pattern DELETE_FROM = Pattern.compile("(?is)\\bdelete\\s+from\\b");
    private static final Pattern CREATE_OBJ =
            Pattern.compile("(?is)\\bcreate\\s+(?:or\\s+replace\\s+)?(?:temp(?:orary)?\\s+)?"
                    + "(?:unique\\s+)?(table|view|index)\\b");
    private static final Pattern ALTER_TABLE = Pattern.compile("(?is)\\balter\\s+table\\b");
    private static final Pattern DROP_OBJ = Pattern.compile("(?is)\\bdrop\\s+(table|view)\\b");
    private static final Pattern MERGE_INTO = Pattern.compile("(?is)\\bmerge\\s+into\\b");
    private static final Pattern WITH_AS = Pattern.compile("(?is)\\bwith\\b.+\\bas\\s*\\(");

    /** Leading keyword (ignoring wrapping parens/whitespace), used by {@link #statementKind}. */
    private static final Pattern LEADING =
            Pattern.compile("(?is)^[\\s(]*(with|select|insert|update|delete|create|alter|drop|merge)\\b");

    /** Fallback table extraction: the identifier following FROM / JOIN / INTO / UPDATE. */
    private static final Pattern TABLE_AFTER_KEYWORD =
            Pattern.compile("(?is)\\b(?:from|join|into|update)\\s+([`\"\\[]?[A-Za-z_][\\w$]*"
                    + "(?:\\.[`\"\\[]?[A-Za-z_][\\w$]*[`\"\\]]?)*[`\"\\]]?)");

    /**
     * True when {@code text} matches a recognizable SQL statement shape (case-insensitive). Requires
     * co-occurring keywords (e.g. SELECT together with FROM) so ordinary prose that merely contains
     * the word "select" or "update" is not misread as SQL. Short strings are rejected outright.
     */
    public static boolean looksLikeSql(String text) {
        if (text == null) {
            return false;
        }
        String s = text.strip();
        if (s.length() < MIN_LENGTH) {
            return false;
        }
        // Anchor on the leading verb: prose that merely *mentions* SELECT/UPDATE mid-sentence
        // ("select an option from the menu", "update the records set aside") is rejected, while a
        // statement that begins with the verb must still carry its distinguishing second keyword.
        return switch (statementKind(s)) {
            case "select" -> SELECT_FROM.matcher(s).find();
            case "insert" -> INSERT_INTO.matcher(s).find();
            case "update" -> UPDATE_SET.matcher(s).find();
            case "delete" -> DELETE_FROM.matcher(s).find();
            case "create" -> CREATE_OBJ.matcher(s).find();
            case "alter" -> ALTER_TABLE.matcher(s).find();
            case "drop" -> DROP_OBJ.matcher(s).find();
            case "merge" -> MERGE_INTO.matcher(s).find();
            case "with" -> WITH_AS.matcher(s).find();
            default -> false;
        };
    }

    /**
     * Classifies the statement by its leading keyword:
     * {@code select|insert|update|delete|create|alter|drop|merge|with}, or {@code other} when no
     * leading SQL keyword is present.
     */
    public static String statementKind(String text) {
        if (text == null) {
            return "other";
        }
        Matcher m = LEADING.matcher(text);
        if (m.find()) {
            return m.group(1).toLowerCase(Locale.ROOT);
        }
        return "other";
    }

    /**
     * Extracts the base tables referenced by {@code query}, in first-seen order, de-duplicated.
     * Placeholders ({@code ?}, {@code :name}, {@code $1}, {@code ${...}}, {@code %s}, {@code #{...}})
     * are neutralized first so the parser does not choke, then tree-sitter-sql is walked for
     * {@code object_reference} relations under FROM/JOIN/UPDATE/INSERT. If the parse errors or finds
     * nothing, a regex over the same keywords is used instead. Never throws — returns an empty list
     * on any failure. Schema qualifiers are preserved (e.g. {@code sales.orders}).
     */
    public static List<String> referencedTables(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String sanitized = sanitizePlaceholders(query);
        try {
            List<String> viaGrammar = tablesViaGrammar(sanitized);
            if (!viaGrammar.isEmpty()) {
                return viaGrammar;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // grammar unavailable/ABI-broken — fall through to the regex heuristic
        }
        return tablesViaRegex(sanitized);
    }

    /** Replaces the common placeholder syntaxes with a harmless literal so a query still parses. */
    static String sanitizePlaceholders(String query) {
        String s = query;
        s = s.replaceAll("\\$\\{[^}]*\\}", "1");   // ${var}
        s = s.replaceAll("#\\{[^}]*\\}", "1");      // #{var} (MyBatis)
        s = s.replaceAll("(?<!:):[A-Za-z_]\\w*", "1"); // :name  (but not :: casts)
        s = s.replaceAll("\\$\\d+", "1");            // $1 (positional)
        s = s.replaceAll("%[A-Za-z]", "1");          // %s / %d (printf-style)
        s = s.replace("?", "1");                      // JDBC positional
        return s;
    }

    private static List<String> tablesViaGrammar(String sanitized) {
        TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterSql());
        TSTree tree = parser.parseString(null, sanitized);
        TSNode root = tree.getRootNode();
        if (root.hasError()) {
            return List.of();
        }
        byte[] bytes = sanitized.getBytes(StandardCharsets.UTF_8);
        Set<String> tables = new LinkedHashSet<>();
        collectTables(root, null, bytes, tables);
        return new ArrayList<>(tables);
    }

    /**
     * An {@code object_reference} names a table when it sits directly under a {@code relation}
     * (FROM / JOIN / UPDATE target) or directly under an {@code insert} / {@code merge} statement.
     * Elsewhere ({@code field}, {@code binary_expression}) it is a qualified column and is skipped.
     */
    private static void collectTables(TSNode node, String parentType, byte[] bytes, Set<String> into) {
        String type = node.getType();
        if (type.equals("object_reference")
                && parentType != null
                && (parentType.equals("relation") || parentType.equals("insert")
                    || parentType.equals("merge"))) {
            String name = text(node, bytes).strip();
            if (!name.isEmpty()) {
                into.add(name);
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collectTables(node.getChild(i), type, bytes, into);
        }
    }

    private static List<String> tablesViaRegex(String query) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher m = TABLE_AFTER_KEYWORD.matcher(query);
        while (m.find()) {
            String raw = m.group(1).strip();
            String cleaned = raw.replace("`", "").replace("\"", "").replace("[", "").replace("]", "");
            if (!cleaned.isEmpty() && !isKeyword(cleaned)) {
                tables.add(cleaned);
            }
        }
        return new ArrayList<>(tables);
    }

    /** Guards the regex path from swallowing a following keyword as if it were a table name. */
    private static boolean isKeyword(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "select", "where", "set", "values", "on", "using", "join", "inner", "left",
                 "right", "outer", "full", "cross", "as" -> true;
            default -> false;
        };
    }

    private static String text(TSNode node, byte[] bytes) {
        int start = node.getStartByte();
        int end = node.getEndByte();
        return new String(bytes, start, end - start, StandardCharsets.UTF_8);
    }
}
