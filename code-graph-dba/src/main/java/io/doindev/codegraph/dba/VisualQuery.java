package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;

/** The visual query language. Compilation is pure: it never opens a database connection. */
final class VisualQuery {
    static final int VERSION = 2;
    static final Set<String> AGGREGATES = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX");
    static final Set<String> FUNCTIONS = Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "LOWER", "UPPER", "TRIM", "LENGTH", "COALESCE", "NULLIF");
    static ObjectNode empty() {
        ObjectNode m = Profiles.JSON.createObjectNode().put("version", VERSION).put("mode", "detail").put("distinct", false);
        m.putArray("sources"); m.putArray("roots"); m.putArray("parameters"); m.putNull("where");
        m.putObject("detail").putArray("outputs"); m.withObject("detail").putArray("order");
        m.putObject("summary").putArray("outputs"); m.withObject("summary").putArray("groups");
        m.withObject("summary").putArray("order"); m.withObject("summary").putNull("having"); return m;
    }
    static ObjectNode compile(JsonNode request) {
        ObjectNode result = Profiles.JSON.createObjectNode().put("valid", false).put("sql", "");
        ArrayNode diagnostics = result.putArray("diagnostics");
        try {
            Compiler compiler = new Compiler(request.path("model"), request.path("quote").asText("\""), request.path("engine").asText("jdbc"));
            String sql = compiler.query();
            if (sql.length() > 16384) throw invalid("The generated query exceeds 16 KiB. Reduce its outputs or expressions.");
            ObjectNode validation = Profiles.JSON.createObjectNode().put("sql", sql).put("action", "refresh");
            ArrayNode values = validation.putArray("parameters"); compiler.bindings.forEach(b -> values.addNull());
            GridSql.prepare(validation);
            result.put("valid", true).put("sql", sql); result.set("bindings", compiler.bindings); result.set("outputs", compiler.descriptors);
        } catch (IllegalArgumentException e) { diagnostics.addObject().put("message", e.getMessage()); }
        catch (Exception e) { diagnostics.addObject().put("message", "This query cannot be represented by the visual SQL compiler."); }
        return result;
    }
    static ObjectNode validateDraft(JsonNode model) {
        if (!model.isObject() || model.path("version").asInt() != VERSION) throw invalid("Unsupported visual query draft version");
        if (model.toString().length() > 1048576) throw invalid("Visual query draft exceeds its 2 MiB allowance");
        for(JsonNode source:model.path("sources"))if(source.path("columns").size()>256)throw invalid("Builder sources are limited to 256 columns");
        for(String mode:List.of("detail","summary"))if(model.path(mode).path("outputs").size()>256)throw invalid("Select at most 256 output columns per mode");
        if(model.path("parameters").size()>128)throw invalid("A query supports at most 128 parameter definitions");
        ObjectNode copy = model.deepCopy(); copy.retain("version", "mode", "distinct", "sources", "roots", "parameters", "where", "detail", "summary", "layout", "dialect");
        for (JsonNode p : copy.path("parameters")) if (p.isObject()) ((ObjectNode)p).retain("id", "name", "type");
        return copy;
    }
    static ObjectNode expression(String kind) { return Profiles.JSON.createObjectNode().put("kind", kind); }
    static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    private static final class Compiler {
        final JsonNode model, active; final String quote, engine;
        final Map<String, JsonNode> sources = new LinkedHashMap<>(), outputs = new LinkedHashMap<>(), references = new LinkedHashMap<>(), parameters = new LinkedHashMap<>();
        final ArrayNode bindings = Profiles.JSON.createArrayNode(), descriptors = Profiles.JSON.createArrayNode();
        int inspectionDepth; final Set<String> inspectedOutputs = new HashSet<>();
        final Set<String> visited = new HashSet<>(), resolvingOutputs = new HashSet<>(); int nodes;
        Compiler(JsonNode model, String quote, String engine) {
            this.model = validateDraft(model); this.quote = quote; this.engine = engine;
            if (!Set.of("\"", "`", "[").contains(quote)) throw invalid("Unsupported identifier quoting");
            if (!Set.of("detail", "summary").contains(model.path("mode").asText())) throw invalid("Choose Detail or Summary");
            active = model.path(model.path("mode").asText());
            Set<String> aliases = new HashSet<>();
            for (JsonNode s : array(model, "sources")) {
                String id = text(s, "id", 128); if (sources.put(id, s) != null) throw invalid("Duplicate source identity");
                String alias = text(s, "alias", 256); if (!aliases.add(alias)) throw invalid("Each source must have a unique alias");
            }
            for (JsonNode o : array(active, "outputs")) if (outputs.put(text(o, "id", 128), o) != null) throw invalid("Duplicate output identity");
            for (String mode : List.of("detail","summary")) for(JsonNode output : array(model.path(mode),"outputs")) references.put(text(output,"id",128), output);
            for (JsonNode p : array(model, "parameters")) {
                if (parameters.put(text(p, "id", 128), p) != null) throw invalid("Duplicate parameter identity");
                String parameterName=text(p, "name", 128); if(parameters.values().stream().filter(v->v.path("name").asText().equals(parameterName)).count()>1)throw invalid("Parameter names must be unique"); type(p.path("type").asText());
            }
            if (outputs.size() > 256) throw invalid("Select at most 256 output columns");
            if (parameters.size() > 128) throw invalid("A query supports at most 128 parameter definitions");
        }
        String query() throws Exception {
            if (sources.isEmpty()) throw invalid("Add a table or view to begin");
            if (outputs.isEmpty()) throw invalid("Select at least one output column");
            if (array(model, "roots").size() != 1) throw invalid("Connect all tables and views before running or explaining this query");
            boolean summary = model.path("mode").asText().equals("summary");
            Set<String> groups = new HashSet<>(); for (JsonNode g : active.path("groups")) groups.add(g.path("expression").toString());
            List<String> selected = new ArrayList<>();
            for (JsonNode o : outputs.values()) {
                JsonNode e = o.path("expression");
                if (!summary && aggregate(e)) throw invalid("Use Summary mode for aggregate outputs");
                if (summary) validateGrouping(e, groups);
                String value = expr(e, 0, summary);
                String alias = o.path("alias").asText(); selected.add(value + (alias.isBlank() ? "" : " AS " + identifier(alias)));
                ObjectNode descriptor = descriptors.addObject().put("id", o.path("id").asText()).put("label", alias.isBlank() ? value : alias).put("sourceExpression", value).put("aggregate", aggregate(e));
                descriptor.put("jdbcType", jdbcType(e)); descriptor.put("type", e.path("type").asText(""));
            }
            String from = join(model.path("roots").get(0), 0);
            if (!visited.equals(sources.keySet())) throw invalid("Every canvas source must occur exactly once in the join tree");
            StringBuilder sql = new StringBuilder("SELECT ").append(model.path("distinct").asBoolean() ? "DISTINCT " : "").append(String.join(",\n       ", selected)).append("\nFROM ").append(from);
            if (present(model.get("where"))) sql.append("\nWHERE ").append(expr(model.get("where"), 0, false));
            if (summary && !groups.isEmpty()) {
                List<String> items = new ArrayList<>(); for (JsonNode g : active.path("groups")) items.add(expr(g.path("expression"), 0, false));
                sql.append("\nGROUP BY ").append(String.join(", ", items));
            }
            if (summary && present(active.get("having"))) { validateGrouping(active.get("having"), groups); sql.append("\nHAVING ").append(expr(active.get("having"), 0, true)); }
            List<String> ordering = new ArrayList<>();
            for (JsonNode order : array(active, "order")) {
                String direction = order.path("direction").asText("ASC"); if (!Set.of("ASC", "DESC").contains(direction)) throw invalid("Invalid sort direction");
                if (summary) validateGrouping(order.path("expression"), groups);
                ordering.add(expr(order.path("expression"), 0, summary) + " " + direction);
            }
            if (!ordering.isEmpty()) sql.append("\nORDER BY ").append(String.join(", ", ordering));
            return sql.toString();
        }
        String join(JsonNode node, int depth) throws Exception {
            budget(depth);
            if (node.path("kind").asText().equals("source")) {
                String id = node.path("source").asText(); JsonNode source = sources.get(id);
                if (source == null || !visited.add(id)) throw invalid("Missing or repeated source in join tree");
                String ref = text(source, "reference", 8192);
                if (!(CCJSqlParserUtil.parse("SELECT * FROM " + ref, p -> p.withTimeOut(500)) instanceof PlainSelect s)
                        || !(s.getFromItem() instanceof Table t) || t.getAlias() != null || !s.toString().equals("SELECT * FROM " + s.getFromItem())) throw invalid("Invalid table reference");
                return ref + (engine.equals("oracle") ? " " : " AS ") + identifier(source.path("alias").asText());
            }
            if (!node.path("kind").asText().equals("join")) throw invalid("Invalid join node");
            String type = node.path("type").asText(); if (!Set.of("INNER", "LEFT", "RIGHT", "FULL", "CROSS").contains(type)) throw invalid("Invalid join type");
            String left = join(node.path("left"), depth + 1), right = join(node.path("right"), depth + 1);
            List<String> pairs = new ArrayList<>();
            Set<String> leftSources = leafIds(node.path("left")), rightSources = leafIds(node.path("right"));
            for (JsonNode pair : array(node, "pairs")) {
                JsonNode a = pair.path("left"), b = pair.path("right");
                if (!a.path("kind").asText().equals("column") || !b.path("kind").asText().equals("column")
                        || !(leftSources.contains(a.path("source").asText()) && rightSources.contains(b.path("source").asText())
                        || rightSources.contains(a.path("source").asText()) && leftSources.contains(b.path("source").asText()))) throw invalid("Join columns must connect its left and right operands");
                String op = pair.path("op").asText("="); if (!Set.of("=", "<>", "<", "<=", ">", ">=").contains(op)) throw invalid("Invalid join comparison");
                pairs.add(expr(a, 0, false) + " " + op + " " + expr(b, 0, false));
            }
            if (!type.equals("CROSS") && pairs.isEmpty()) throw invalid("Connect columns for every join, or choose Cross join explicitly");
            if (type.equals("CROSS") && !pairs.isEmpty()) throw invalid("Cross joins cannot contain column constraints");
            return "(" + left + "\n" + type + " JOIN " + right + (type.equals("CROSS") ? "" : " ON " + String.join(" AND ", pairs)) + ")";
        }
        String expr(JsonNode e, int depth, boolean allowAggregate) {
            budget(depth); String kind = e.path("kind").asText();
            switch (kind) {
                case "column": {
                    JsonNode source = sources.get(e.path("source").asText()); if (source == null) throw invalid("An expression references a removed table. Repair or remove it.");
                    String name = text(e, "name", 2048);
                    if (source.has("columns") && !hasColumn(source, name)) throw invalid("Column " + name + " is unavailable. Repair or remove the expression.");
                    return identifier(source.path("alias").asText()) + "." + identifier(name);
                }
                case "output": {
                    String id = e.path("output").asText(); JsonNode o = references.get(id);
                    if (o == null) throw invalid("A filter or sort references a removed output. Repair or remove it.");
                    if (!resolvingOutputs.add(id)) throw invalid("Output expressions cannot reference themselves");
                    try { return expr(o.path("expression"), depth + 1, allowAggregate); } finally { resolvingOutputs.remove(id); }
                }
                case "literal": { String value=literal(e); if(Set.of("sqlserver","azure-sql").contains(engine)){ if(e.path("type").asText().equals("boolean"))return value.equals("TRUE")?"1":value.equals("FALSE")?"0":value; if(Set.of("date","time","timestamp").contains(e.path("type").asText())&&!value.equals("NULL")){int start=value.indexOf('\'');return "CAST("+value.substring(start)+" AS "+(e.path("type").asText().equals("timestamp")?"DATETIME2":e.path("type").asText().toUpperCase(Locale.ROOT))+")";} } return value; }
                case "parameter": {
                    JsonNode p = parameters.get(e.path("parameter").asText()); if (p == null) throw invalid("Choose a parameter definition");
                    if (bindings.size() >= 128) throw invalid("The query exceeds 128 parameter occurrences");
                    bindings.addObject().put("id", p.path("id").asText()).put("name", p.path("name").asText()).put("type", p.path("type").asText());
                    return switch (p.path("type").asText()) { case "date" -> "CAST(? AS DATE)"; case "time" -> "CAST(? AS TIME)"; case "timestamp" -> Set.of("sqlserver","azure-sql").contains(engine)?"CAST(? AS DATETIME2)":"CAST(? AS TIMESTAMP)"; default -> "?"; };
                }
                case "binary": {
                    String op = e.path("op").asText(); if (!Set.of("+", "-", "*", "/", "=", "<>", "<", "<=", ">", ">=", "LIKE", "NOT LIKE").contains(op)) throw invalid("Choose an expression operator");
                    return "(" + expr(e.path("left"), depth + 1, allowAggregate) + " " + op + " " + expr(e.path("right"), depth + 1, allowAggregate) + ")";
                }
                case "logical": {
                    String op = e.path("op").asText(); if (!Set.of("AND", "OR").contains(op)) throw invalid("Choose AND or OR");
                    List<String> args = new ArrayList<>(); for (JsonNode a : array(e, "args")) args.add(expr(a, depth + 1, allowAggregate));
                    if (args.isEmpty()) throw invalid("Add a condition or remove the empty group"); return "(" + String.join(" " + op + " ", args) + ")";
                }
                case "not": return "NOT (" + expr(e.path("arg"), depth + 1, allowAggregate) + ")";
                case "null": return "(" + expr(e.path("arg"), depth + 1, allowAggregate) + (e.path("not").asBoolean() ? " IS NOT NULL)" : " IS NULL)");
                case "between": return "(" + expr(e.path("arg"), depth + 1, allowAggregate) + (e.path("not").asBoolean() ? " NOT BETWEEN " : " BETWEEN ") + expr(e.path("lower"), depth + 1, allowAggregate) + " AND " + expr(e.path("upper"), depth + 1, allowAggregate) + ")";
                case "in": {
                    List<String> items = new ArrayList<>(); for (JsonNode a : array(e, "args")) items.add(expr(a, depth + 1, allowAggregate));
                    if (items.isEmpty()) throw invalid("Add at least one value to IN");
                    return "(" + expr(e.path("arg"), depth + 1, allowAggregate) + (e.path("not").asBoolean() ? " NOT IN (" : " IN (") + String.join(", ", items) + "))";
                }
                case "case": {
                    StringBuilder value = new StringBuilder("CASE"); if (array(e, "branches").isEmpty()) throw invalid("Add a CASE condition");
                    for (JsonNode b : e.path("branches")) value.append(" WHEN ").append(expr(b.path("when"), depth + 1, allowAggregate)).append(" THEN ").append(expr(b.path("then"), depth + 1, allowAggregate));
                    return value.append(" ELSE ").append(expr(e.path("else"), depth + 1, allowAggregate)).append(" END").toString();
                }
                case "function": {
                    String name = text(e, "name", 2048), schema = e.path("schema").asText(); boolean agg = aggregateFunction(e);
                    if (agg && !allowAggregate) throw invalid("Aggregate functions belong in Summary outputs or Groups filters");
                    if (schema.isBlank() && !e.path("catalogFunction").asBoolean() && !FUNCTIONS.contains(name.toUpperCase(Locale.ROOT))) throw invalid("Choose a function from the database picker");
                    List<String> args = new ArrayList<>(); for (JsonNode a : array(e, "args")) {
                        if (a.path("kind").asText().equals("star") && name.equalsIgnoreCase("COUNT") && e.path("args").size() == 1 && !e.path("distinct").asBoolean()) args.add("*");
                        else args.add(expr(a, depth + 1, allowAggregate && !agg));
                    }
                    if (e.path("distinct").asBoolean() && !agg) throw invalid("DISTINCT is available for aggregate functions");
                    if (schema.isBlank() && !e.path("catalogFunction").asBoolean()) {
                        name = name.toUpperCase(Locale.ROOT);
                        int n = args.size(); if (Set.of("COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "LOWER", "UPPER", "TRIM", "LENGTH").contains(name) && n != 1 || name.equals("NULLIF") && n != 2 || name.equals("COALESCE") && n < 2 || name.equals("ROUND") && (n < 1 || n > 2)) throw invalid("Incorrect argument count for " + name);
                        if (name.equals("LENGTH") && Set.of("sqlserver", "azure-sql").contains(engine)) name = "LEN";
                    } else {
                        JsonNode signature=e.path("arguments");if(!signature.isArray())throw invalid("Refresh this database function signature in the function picker");int required=0;boolean variadic=false;for(JsonNode argument:signature){if(!argument.path("optional").asBoolean()&&!argument.path("variadic").asBoolean())required++;variadic|=argument.path("variadic").asBoolean();if(argument.path("valueType").asText().equals("unsupported"))throw invalid("Unsupported function argument type");}if(args.size()<required||!variadic&&args.size()>signature.size())throw invalid("Incorrect argument count for the selected overload");
                        for(int i=0;i<args.size();i++){JsonNode argument=signature.get(Math.min(i,signature.size()-1));String castType=VisualFunctions.castType(argument.path("type").asText());if(castType==null)throw invalid("Unsupported function argument type: "+argument.path("type").asText());args.set(i,"CAST("+args.get(i)+" AS "+castType+")");}
                        name = (schema.isBlank()?"":identifier(schema)+".")+identifier(name);
                    }
                    return name + "(" + (e.path("distinct").asBoolean() ? "DISTINCT " : "") + String.join(", ", args) + ")";
                }
                default: throw invalid("Complete the expression using the visual selectors");
            }
        }
        void validateGrouping(JsonNode e, Set<String> groups) {
            if (++inspectionDepth > 64) throw invalid("Expression dependency cycle or excessive depth");
            try {
            if (groups.contains(e.toString()) || aggregateFunction(e)) return;
            if (e.path("kind").asText().equals("output")) { JsonNode o = references.get(e.path("output").asText()); if (o == null) throw invalid("A filter or sort references a removed output"); validateGrouping(o.path("expression"), groups); return; }
            if (e.path("kind").asText().equals("column")) throw invalid("Every non-aggregate Summary column must be in Group by");
            if (e.isContainerNode()) for (JsonNode child : e) if (child.isContainerNode()) validateGrouping(child, groups);
            } finally { inspectionDepth--; }
        }
        boolean aggregate(JsonNode e) { if (++inspectionDepth > 64) throw invalid("Expression dependency cycle or excessive depth"); try { if (aggregateFunction(e)) return true; if (e.path("kind").asText().equals("output")) { JsonNode o = references.get(e.path("output").asText()); return o != null && aggregate(o.path("expression")); } for (JsonNode child : e) if (child.isContainerNode() && aggregate(child)) return true; return false; } finally { inspectionDepth--; } }
        int jdbcType(JsonNode e) { if (e.path("kind").asText().equals("column")) { JsonNode s = sources.get(e.path("source").asText()); if (s != null) for (JsonNode c : s.path("columns")) if (c.path("name").asText().equals(e.path("name").asText())) return c.path("jdbcType").asInt(12); } return e.path("jdbcType").asInt(12); }
        String identifier(String text) { if (text.isBlank() || text.length() > 2048 || text.indexOf('\0') >= 0) throw invalid("Invalid identifier"); String end = quote.equals("[") ? "]" : quote; return quote + text.replace(end, end + end) + end; }
        void budget(int depth) { if (depth > 64 || ++nodes > 8000) throw invalid("Query expression complexity limit exceeded"); }
    }
    static boolean aggregateFunction(JsonNode e) { return e.path("kind").asText().equals("function") && (e.path("aggregate").asBoolean() || e.path("schema").asText().isBlank() && AGGREGATES.contains(e.path("name").asText().toUpperCase(Locale.ROOT))); }
    static boolean hasColumn(JsonNode source, String name) { for (JsonNode c : source.path("columns")) if (c.path("name").asText().equals(name)) return true; return false; }
    static Set<String> leafIds(JsonNode root) { Set<String> ids = new HashSet<>(); leaves(root, ids, 0); return ids; }
    private static void leaves(JsonNode node, Set<String> ids, int depth) { if (depth > 64) throw invalid("Join tree is too deep"); if (node.path("kind").asText().equals("source")) ids.add(node.path("source").asText()); else if (node.path("kind").asText().equals("join")) { leaves(node.path("left"), ids, depth + 1); leaves(node.path("right"), ids, depth + 1); } }
    static String text(JsonNode n, String key, int length) { return Profiles.text(n, key, length); }
    static JsonNode array(JsonNode n, String key) { JsonNode a = n.path(key); if (!a.isArray()) throw invalid("Invalid visual query field: " + key); return a; }
    static boolean present(JsonNode n) { return n != null && !n.isNull() && !n.isMissingNode(); }
    static void type(String t) { if (!Set.of("text", "number", "integer", "boolean", "date", "time", "timestamp", "null").contains(t)) throw invalid("Unsupported value type"); }
    static String literal(JsonNode e) {
        String type = e.path("type").asText("text"); type(type); JsonNode value = e.path("value");
        if (type.equals("null") || value.isNull()) return "NULL";
        String text = value.asText(); if (text.length() > 8192) throw invalid("Value exceeds 8192 characters");
        try {
            return switch (type) {
                case "number", "integer" -> { BigDecimal n = new BigDecimal(text); if(n.precision()>8192||Math.abs((long)n.scale())>8192)throw invalid("Numeric value exceeds the expression allowance"); if (type.equals("integer")) n.toBigIntegerExact(); yield n.toPlainString(); }
                case "boolean" -> { if (!Set.of("true", "false").contains(text.toLowerCase(Locale.ROOT))) throw invalid("Choose true or false"); yield text.toUpperCase(Locale.ROOT); }
                case "date" -> "DATE '" + java.time.LocalDate.parse(text) + "'";
                case "time" -> "TIME '" + java.time.LocalTime.parse(text) + "'";
                case "timestamp" -> "TIMESTAMP '" + java.time.LocalDateTime.parse(text.replace(' ', 'T')).toString().replace('T', ' ') + "'";
                default -> "'" + text.replace("'", "''") + "'";
            };
        } catch (NumberFormatException | ArithmeticException | java.time.DateTimeException ex) { throw invalid("Enter a valid " + type + " value"); }
    }
}
