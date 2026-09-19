import java.io.IOException;
import java.util.*;

/** Bounded, lossless JSON/JSONC editing and conservative TOML inspection.
 * Never reserializes unrelated settings or includes their values in errors. */
final class McpConfigDocument {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    record ObjectValue(Map<String, Object> fields, int close, int lastEnd, boolean trailingComma) {}
    final String text;
    final boolean comments;
    int at;
    McpConfigDocument(String text) { this(text, true); }
    McpConfigDocument(String text, boolean comments) { this.text = text; this.comments = comments; }
    IOException invalid() { return new IOException("Malformed or unsupported configuration near character " + at + "; original file preserved. Use the client's MCP settings editor."); }
    void space() throws IOException {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (Character.isWhitespace(c) || (at == 0 && c == '\ufeff')) { at++; continue; }
            if (comments && text.startsWith("//", at)) { while (at < text.length() && text.charAt(at) != '\n') at++; continue; }
            if (comments && text.startsWith("/*", at)) { int end = text.indexOf("*/", at + 2); if (end < 0) throw invalid(); at = end + 2; continue; }
            break;
        }
    }
    ObjectValue json() throws IOException {
        Object result = value(0); space();
        if (at != text.length() || !(result instanceof ObjectValue)) throw invalid();
        return (ObjectValue) result;
    }
    Object value(int depth) throws IOException {
        if (depth > 64) throw invalid();
        space(); if (at >= text.length()) throw invalid();
        char c = text.charAt(at);
        if (c == '"') return string();
        if (c == '{') {
            at++; Map<String,Object> fields = new LinkedHashMap<>(); int last = at; boolean comma = false;
            space();
            while (at < text.length() && text.charAt(at) != '}') {
                if (text.charAt(at) != '"') throw invalid(); String key = string(); space();
                if (at >= text.length() || text.charAt(at++) != ':') throw invalid();
                Object item = value(depth + 1);
                if (fields.containsKey(key)) throw invalid(); fields.put(key, item);
                last = at; space(); comma = at < text.length() && text.charAt(at) == ',';
                if (comma) { at++; space(); } else break;
            }
            if (at >= text.length() || text.charAt(at) != '}') throw invalid();
            if(comma && !comments)throw invalid();
            return new ObjectValue(fields, at++, last, comma);
        }
        if (c == '[') {
            at++; List<Object> items = new ArrayList<>(); space();
            while (at < text.length() && text.charAt(at) != ']') {
                items.add(value(depth + 1)); space();
                if (at < text.length() && text.charAt(at) == ',') { at++; space(); if(!comments && at<text.length() && text.charAt(at)==']')throw invalid(); } else break;
            }
            if (at >= text.length() || text.charAt(at++) != ']') throw invalid(); return items;
        }
        int start = at;
        while (at < text.length() && !Character.isWhitespace(text.charAt(at)) && ",}]/".indexOf(text.charAt(at)) < 0) at++;
        String token = text.substring(start, at);
        if (!token.matches("true|false|null|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
        return new Scalar(token);
    }
    record Scalar(String raw) {}
    String string() throws IOException {
        if (at >= text.length() || text.charAt(at++) != '"') throw invalid();
        StringBuilder out = new StringBuilder();
        while (at < text.length()) {
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c < 32) throw invalid();
            if (c != '\\') { out.append(c); continue; }
            if (at >= text.length()) throw invalid();
            char escape = text.charAt(at++);
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                case 'u' -> {
                    if (at + 4 > text.length()) throw invalid();
                    try { out.append((char) Integer.parseInt(text.substring(at, at + 4), 16)); }
                    catch (NumberFormatException e) { throw invalid(); } at += 4;
                }
                default -> throw invalid();
            }
        }
        throw invalid();
    }
    String insert(ObjectValue parent, String key, String value) {
        String eol = text.contains("\r\n") ? "\r\n" : "\n";
        String member = eol + "  " + quote(key) + ": " + value + eol;
        // Insert a missing comma before trailing comments, never after a // comment.
        String prefix = text.substring(0, parent.close);
        if (!parent.fields.isEmpty() && !parent.trailingComma)
            prefix = text.substring(0, parent.lastEnd) + "," + text.substring(parent.lastEnd, parent.close);
        return prefix + member + text.substring(parent.close);
    }
    static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) switch(c) {
            case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
            default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
        }
        return out.append('"').toString();
    }

    /** Accept conventional [mcp_servers.name] tables. Unusual MCP layouts fail closed;
     * unrelated TOML is lexed but never rewritten. No regex searches through strings/comments. */
    static Map<String, Map<String,Object>> tomlServers(String text) throws IOException {
        Map<String, Map<String,Object>> servers = new LinkedHashMap<>();
        List<String> section = List.of(); Set<List<String>> headers = new HashSet<>();
        for (String raw : tomlStatements(text)) {
            String s = raw.strip(); if (s.isEmpty()) continue;
            if (s.startsWith("[")) {
                boolean array = s.startsWith("[["); String suffix = array ? "]]" : "]";
                if (!s.endsWith(suffix)) throw tomlError();
                section = tomlKeys(s.substring(array ? 2 : 1, s.length() - suffix.length()));
                if (section.getFirst().equals("mcp_servers")) {
                    if (array || !headers.add(section)) throw tomlError();
                    if (section.size() >= 2) servers.computeIfAbsent(section.get(1), ignored -> new LinkedHashMap<>());
                }
                continue;
            }
            int equals = tomlEquals(s); if (equals < 0) throw tomlError();
            List<String> keys = tomlKeys(s.substring(0, equals));
            String value = s.substring(equals + 1).strip(); if (value.isEmpty()) throw tomlError();
            if (section.isEmpty() && keys.getFirst().equals("mcp_servers")) throw tomlError();
            if (!section.isEmpty() && section.getFirst().equals("mcp_servers")) {
                if (section.size() < 2 || keys.size() != 1) throw tomlError();
                if (section.size() == 2) {
                    Map<String,Object> server = servers.get(section.get(1));
                    String key = keys.getFirst(); if (server.containsKey(key)) throw tomlError();
                    Object parsed = new Scalar(value);
                    if (value.startsWith("\"") || value.startsWith("'")) parsed = tomlString(value);
                    if(key.equals("args") && value.startsWith("[")) {
                        // Conventional JSON-compatible TOML arrays; other representations remain opaque.
                        try { var array=new McpConfigDocument(value); parsed=array.value(0); array.space(); if(array.at!=value.length())throw tomlError(); }
                        catch(IOException ignored){parsed=new Scalar(value);}
                    }
                    if (Set.of("url", "command").contains(key) && !(parsed instanceof String)) throw tomlError();
                    server.put(key, parsed);
                }
            }
        }
        return servers;
    }
    static IOException tomlError() { return new IOException("Malformed or unsupported TOML layout. Use conventional [mcp_servers.name] tables or the Codex MCP editor; original file preserved."); }
    static int tomlEquals(String s) throws IOException {
        char quote = 0; boolean escaped = false;
        for (int i=0; i<s.length(); i++) {
            char c=s.charAt(i);
            if (quote!=0) { if (escaped) escaped=false; else if (c=='\\' && quote=='"') escaped=true; else if(c==quote)quote=0; }
            else if(c=='"'||c=='\'')quote=c; else if(c=='=')return i;
        }
        return -1;
    }
    static List<String> tomlKeys(String s) throws IOException {
        List<String> parts = new ArrayList<>(); int start=0; char quote=0; boolean escaped=false;
        for (int i=0;i<=s.length();i++) {
            char c=i<s.length()?s.charAt(i):'.';
            if(quote!=0){if(escaped)escaped=false;else if(c=='\\'&&quote=='"')escaped=true;else if(c==quote)quote=0;}
            else if(c=='"'||c=='\'')quote=c;
            else if(c=='.') {
                String part=s.substring(start,i).strip();
                if(part.startsWith("\"")||part.startsWith("'"))parts.add(tomlString(part));
                else if(part.matches("[A-Za-z0-9_-]+"))parts.add(part); else throw tomlError(); start=i+1;
            }
        }
        if(quote!=0||parts.isEmpty())throw tomlError(); return List.copyOf(parts);
    }
    static String tomlString(String s) throws IOException {
        // Multiline MCP values require manual review, rather than guessing transport identities.
        if(s.startsWith("\"\"\"")||s.startsWith("'''"))throw tomlError();
        if(s.startsWith("'")) {
            if(s.length()<2||!s.endsWith("'")||s.substring(1,s.length()-1).contains("'"))throw tomlError();
            return s.substring(1,s.length()-1);
        }
        McpConfigDocument parser=new McpConfigDocument(s); String value=parser.string();
        if(parser.at!=s.length())throw tomlError(); return value;
    }
    static List<String> tomlStatements(String text) throws IOException {
        List<String> out=new ArrayList<>(); StringBuilder current=new StringBuilder();
        char quote=0; boolean triple=false,escaped=false; Deque<Character> brackets=new ArrayDeque<>();
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i); if(i==0&&c=='\ufeff')continue;
            if(quote!=0) {
                current.append(c);
                if(escaped){escaped=false;continue;}
                if(c=='\\'&&quote=='"'){escaped=true;continue;}
                if(c==quote) {
                    if(!triple)quote=0;
                    else if(i+2<text.length()&&text.charAt(i+1)==quote&&text.charAt(i+2)==quote) {
                        current.append(quote).append(quote); i+=2;quote=0;triple=false;
                    }
                } else if(c=='\n'&&!triple)throw tomlError();
                continue;
            }
            if(c=='#'){while(i+1<text.length()&&text.charAt(i+1)!='\n')i++;continue;}
            if(c=='"'||c=='\'') {
                quote=c; current.append(c);
                if(i+2<text.length()&&text.charAt(i+1)==c&&text.charAt(i+2)==c){triple=true;current.append(c).append(c);i+=2;}
                continue;
            }
            if(c=='['||c=='{'){if(brackets.size()>=64)throw tomlError();brackets.push(c);}
            else if(c==']'||c=='}'){if(brackets.isEmpty()||brackets.pop()!=(c==']'?'[':'{'))throw tomlError();}
            if(c=='\n'&&brackets.isEmpty()){out.add(current.toString());current.setLength(0);} else current.append(c);
        }
        if(quote!=0||!brackets.isEmpty())throw tomlError();out.add(current.toString());return out;
    }
}
