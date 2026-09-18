package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.*;

/** Positive, vendor-aware grammar. Unknown constructs are reviewable once, never reusable.
 * This is intentionally not a keyword blacklist or a claim that database-side triggers are harmless.
 */
final class ReusableOperation {
    record Reference(String name, boolean qualified) {}
    record Result(String category, boolean eligible, boolean readOnly, String reason, List<Reference> references) {
        Result(String category,boolean eligible,boolean readOnly,String reason){this(category,eligible,readOnly,reason,List.of());}
        ObjectNode json() { return Profiles.JSON.createObjectNode().put("category", category)
                .put("eligible", eligible).put("readOnly", readOnly).put("reason", reason)
                .put("limitations", "Database event triggers, existing object behavior, locks and resource costs remain subject to database privileges; routine creation does not authorize invocation."); }
    }
    private static final Set<String> VENDORS = Set.of("postgresql", "mysql", "mariadb", "h2");
    private static final Set<String> TYPES = Set.of("INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "BOOLEAN", "BOOL",
            "VARCHAR", "CHAR", "TEXT", "NUMERIC", "DECIMAL", "REAL", "FLOAT", "DOUBLE", "DATE", "TIME", "TIMESTAMP", "BYTEA", "BINARY", "VARBINARY");
    private ReusableOperation() {}

    static Result classify(String sql, JsonNode scope) {
        String vendor = scope.path("vendor").asText();
        if (!VENDORS.contains(vendor)) return denied("Vendor syntax has not been verified for reusable approval");
        if (scope.path("database").asText().isBlank() || scope.path("schema").asText().isBlank())
            return denied("An explicit, unambiguous database and schema are required");
        try {
            Parser p = new Parser(sql, scope);
            if(scope.has("bindingId")&&!Set.of("local","dev","test","stage","prod").contains(scope.path("environment").asText()))
                throw fail("Correct the legacy environment before creating a reusable permission");
            for(String dangerous:List.of("DROP","DELETE","TRUNCATE","UPDATE","INSERT","MERGE","ALTER","GRANT","REVOKE","CALL","DO","EXECUTE","PURGE"))
                if(p.peek(dangerous))throw fail("This mutation, destructive action or executable routine requires one-time approval");
            if (p.peek("SELECT") || p.peek("VALUES")) {
                p.read(sql); return readResult("read", vendor, p.references);
            }
            if (p.take("EXPLAIN")) {
                // No options, ANALYZE, ANALYSE, FORMAT or vendor extensions. Explain never executes here.
                String remainder = sql.substring(p.offset());
                p.read(remainder); return readResult("explain", vendor, p.references);
            }
            if (p.take("SHOW")) {
                if (!Set.of("mysql", "mariadb").contains(vendor)) throw fail("Unverified catalog command");
                p.expect("CREATE"); p.oneOf("TABLE", "VIEW", "PROCEDURE", "FUNCTION"); p.object(); p.end();
                return readResult("ddl_inspection", vendor, List.of());
            }
            p.expect("CREATE");
            if (p.peek("OR") || p.peek("REPLACE") || p.peek("TEMP") || p.peek("TEMPORARY"))
                throw fail("Replacement and temporary creation require one-time approval");
            String kind = p.oneOf("TABLE", "VIEW", "PROCEDURE", "FUNCTION");
            p.object();
            switch (kind) {
                case "TABLE" -> p.table();
                case "VIEW" -> { p.expect("AS"); p.read(sql.substring(p.offset())); p.at = p.tokens.size(); }
                case "PROCEDURE", "FUNCTION" -> p.routine(kind);
                default -> throw fail("Unknown creation form");
            }
            p.end(); return new Result("create_" + kind.toLowerCase(Locale.ROOT), true, false, "New objects only; no replacement or execution permission",List.copyOf(p.references));
        } catch (Exception e) {
            return denied(e instanceof IllegalArgumentException && e.getMessage() != null ? e.getMessage() : "Syntax is not in the verified reusable grammar");
        }
    }
    private static Result readResult(String category, String vendor, List<Reference> references) {
        // H2 documents that Connection.setReadOnly is ignored. Never claim it is a read-only session.
        if (vendor.equals("h2")) return new Result(category, false, true, "H2 has no verified per-session read-only enforcement; use Allow once");
        return new Result(category, true, true, "Structurally verified with a database-enforced read-only transaction",List.copyOf(references));
    }
    private static Result denied(String reason) { return new Result("one_time_review", false, false, reason); }
    private static IllegalArgumentException fail(String message) { return new IllegalArgumentException(message); }

    private record Token(String text, char kind, int start) {
        boolean keyword(String s) { return kind == 'w' && text.equalsIgnoreCase(s); }
    }
    private static final class Parser {
        final String sql, vendor, database, schema;
        final List<Token> tokens;
        final List<Reference> references = new ArrayList<>();
        int at;
        Parser(String sql, JsonNode scope) {
            if (sql == null || sql.isBlank() || sql.length() > 65536) throw fail("SQL exceeds the reusable grammar limit");
            this.sql = sql; vendor = scope.path("vendor").asText(); database = scope.path("database").asText(); schema = scope.path("schema").asText();
            tokens = lex(sql);
        }
        boolean peek(String word) { return at < tokens.size() && tokens.get(at).keyword(word); }
        boolean take(String word) { if (!peek(word)) return false; at++; return true; }
        void expect(String word) { if (!take(word)) throw fail("Unsupported syntax; " + word + " expected in verified grammar"); }
        boolean punct(String text) { if (at < tokens.size() && tokens.get(at).kind == 'p' && tokens.get(at).text.equals(text)) { at++; return true; } return false; }
        void symbol(String text) { if (!punct(text)) throw fail("Unsupported syntax in verified grammar"); }
        String oneOf(String... words) { for (String w : words) if (take(w)) return w; throw fail("Operation or clause requires one-time review"); }
        int offset() { return at < tokens.size() ? tokens.get(at).start : sql.length(); }
        Token next() { if (at >= tokens.size()) throw fail("Incomplete SQL"); return tokens.get(at++); }
        String identifier() {
            Token t = next(); if (t.kind != 'w' && t.kind != 'q') throw fail("Expected an identifier");
            return t.kind == 'q' ? t.text : vendor.equals("h2") ? t.text.toUpperCase(Locale.ROOT) : vendor.equals("postgresql") ? t.text.toLowerCase(Locale.ROOT) : t.text;
        }
        Reference object() {
            List<String> parts = new ArrayList<>(); parts.add(identifier()); while (punct(".")) parts.add(identifier());
            if (parts.size() > 3) throw fail("Unsupported object qualification");
            if (parts.size() == 3 && (!parts.get(0).equals(database) || !parts.get(1).equals(schema))) throw fail("Object is outside the displayed database/schema");
            if (parts.size() == 2 && !parts.get(0).equals(schema)) throw fail("Object is outside the displayed schema");
            if (Set.of("mysql", "mariadb").contains(vendor) && parts.size() == 3) throw fail("Unsupported MySQL object qualification");
            return new Reference(parts.getLast(),parts.size()>1);
        }
        void end() { punct(";"); if (at != tokens.size()) throw fail("Multiple statements or unsupported trailing clauses require one-time review"); }
        void read(String text) throws Exception {
            // Lex first: JSQLParser does not model MySQL executable comments or all dialect escapes.
            List<Token> readTokens = lex(text);
            int semicolons = 0;
            for (int i = 0; i < readTokens.size(); i++) if (readTokens.get(i).text.equals(";") && readTokens.get(i).kind == 'p') {
                if (++semicolons > 1 || i != readTokens.size() - 1) throw fail("Multiple top-level statements require one-time review");
            }
            SqlReadGuard.validate(text);
            var statement = CCJSqlParserUtil.parse(text, p -> p.withTimeOut(500));
            new TablesNamesFinder<Void>() {
                @Override public <S> Void visit(Table table, S context) {
                    String raw = table.getFullyQualifiedName();
                    Parser reference = new Parser(raw, Profiles.JSON.createObjectNode().put("vendor", vendor).put("database", database).put("schema", schema));
                    Reference resolved=reference.object(); reference.end();if(!references.contains(resolved)){if(references.size()>=256)throw fail("At most 256 referenced relations can receive reusable approval");references.add(resolved);}return super.visit(table, context);
                }
            }.getTables(statement);
        }
        void type() {
            String name = oneOf(TYPES.toArray(String[]::new));
            if (name.equals("DOUBLE")) take("PRECISION");
            if (punct("(")) { number(); if (punct(",")) number(); symbol(")"); }
        }
        void number() { Token t = next(); if (t.kind != 'n' || !t.text.matches("[0-9]{1,5}")) throw fail("Bounded numeric type size required"); }
        void literal() {
            if (take("NULL") || take("TRUE") || take("FALSE") || take("CURRENT_TIMESTAMP") || take("CURRENT_DATE")) return;
            punct("-"); Token t = next(); if (t.kind != 's' && t.kind != 'n') throw fail("Only literal or built-in time defaults are reusable");
        }
        void names() { symbol("("); identifier(); while (punct(",")) identifier(); symbol(")"); }
        void table() {
            symbol("("); int count = 0;
            do {
                if (++count > 256) throw fail("Maximum 256 definitions");
                if (take("CONSTRAINT")) identifier();
                if (take("PRIMARY")) { expect("KEY"); names(); }
                else if (take("UNIQUE")) names();
                else {
                    identifier(); type();
                    // Positive column options only: no expressions, external sources, CTAS, LIKE, engines or triggers.
                    for (int i = 0; i < 8; i++) {
                        if (take("NOT")) expect("NULL");
                        else if (take("NULL")) { }
                        else if (take("PRIMARY")) expect("KEY");
                        else if (take("UNIQUE")) { }
                        else if (take("DEFAULT")) literal();
                        else break;
                    }
                }
            } while (punct(","));
            symbol(")");
        }
        void routine(String kind) throws Exception {
            if (vendor.equals("h2")) throw fail("H2 Java aliases/external routines are not eligible for reusable approval");
            symbol("(");
            if (!punct(")")) { do { take("IN"); identifier(); type(); } while (punct(",")); symbol(")"); }
            if (kind.equals("FUNCTION")) { expect("RETURNS"); type(); }
            if (vendor.equals("postgresql")) {
                expect("LANGUAGE"); expect("SQL");
                if (take("SECURITY")) expect("INVOKER");
                expect("AS"); Token body = next();
                if (body.kind != 'd' && body.kind != 's') throw fail("A literal SQL-language routine body is required");
                read(body.text); // No PL/pgSQL, EXECUTE, configuration clauses, writes or function calls.
            } else {
                if (take("DETERMINISTIC")) { }
                if (take("NO")) expect("SQL"); else { expect("READS"); expect("SQL"); expect("DATA"); }
                expect("SQL"); expect("SECURITY"); expect("INVOKER");
                if (kind.equals("FUNCTION")) {
                    expect("RETURN"); read("SELECT " + sql.substring(offset())); at = tokens.size();
                } else {
                    // A single SELECT body. Compound blocks and dynamic SQL remain one-time only.
                    read(sql.substring(offset())); at = tokens.size();
                }
            }
        }
    }

    /** Dialect-aware lexical boundaries, with bounded token count and fail-closed quoting. */
    private static List<Token> lex(String sql) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < sql.length();) {
            int start = i; char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '\\' || c == '#' || c == '@') throw fail("Dialect escapes, variables or directives require one-time review");
            if (sql.startsWith("--", i)) {
                if (i + 2 < sql.length() && !Character.isWhitespace(sql.charAt(i + 2))) throw fail("Ambiguous dialect comment");
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (sql.startsWith("/*", i)) {
                if (sql.startsWith("/*!", i) || sql.regionMatches(true, i, "/*M!", 0, 4)) throw fail("Executable comments require one-time review");
                int end = sql.indexOf("*/", i + 2);
                if (end < 0 || sql.substring(i + 2, end).contains("/*")) throw fail("Unterminated or nested dialect comment");
                i = end + 2; continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                StringBuilder value = new StringBuilder(); i++; boolean closed = false;
                while (i < sql.length()) {
                    char ch = sql.charAt(i++);
                    if (ch == '\\') throw fail("Backslash-escaped strings require one-time review");
                    if (ch == c) { if (i < sql.length() && sql.charAt(i) == c) { value.append(c); i++; } else { closed = true; break; } }
                    else value.append(ch);
                }
                if (!closed) throw fail("Unterminated quoted value");
                out.add(new Token(value.toString(), c == '\'' ? 's' : 'q', start));
            } else if (c == '$') {
                int delimiterEnd = sql.indexOf('$', i + 1);
                if (delimiterEnd < 0 || !sql.substring(i + 1, delimiterEnd).matches("[A-Za-z_0-9]*")) throw fail("Unsupported dollar quoting");
                String delimiter = sql.substring(i, delimiterEnd + 1); int end = sql.indexOf(delimiter, delimiterEnd + 1);
                if (end < 0) throw fail("Unterminated dollar quoting");
                out.add(new Token(sql.substring(delimiterEnd + 1, end), 'd', start)); i = end + delimiter.length();
            } else if (Character.isLetter(c) || c == '_') {
                i++; while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
                out.add(new Token(sql.substring(start, i), 'w', start));
            } else if (Character.isDigit(c)) {
                i++; while (i < sql.length() && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) i++;
                out.add(new Token(sql.substring(start, i), 'n', start));
            } else { out.add(new Token(String.valueOf(c), 'p', start)); i++; }
            if (out.size() > 10000) throw fail("SQL token limit exceeded");
        }
        return out;
    }
}
