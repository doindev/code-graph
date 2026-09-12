package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.*;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.statement.select.*;

/** Converts only expressions represented by the visual controls. Unsupported SQL stays exact. */
final class VisualQueryImport {
    final ObjectNode model = VisualQuery.empty(); final Map<String,String> aliases = new HashMap<>();
    final Map<String,JsonNode> outputAliases = new HashMap<>(); int parameter, output; boolean resolveAlias;
    static ObjectNode convert(String sql, JsonNode legacy) throws Exception { return new VisualQueryImport().read(sql, legacy); }
    ObjectNode read(String sql, JsonNode legacy) throws Exception {
        PlainSelect select = (PlainSelect) CCJSqlParserUtil.parse(sql, p -> p.withTimeOut(500));
        ArrayNode sources = model.withArray("sources");
        for (JsonNode old : legacy.path("sources")) {
            ObjectNode s = old.deepCopy(); String alias = s.path("alias").asText(); if (alias.isBlank()) alias = "t" + (sources.size() + 1);
            aliases.put(unquote(old.path("alias").asText()), old.path("id").asText()); aliases.put(unquote(old.path("name").asText()), old.path("id").asText());
            aliases.put(old.path("reference").asText(), old.path("id").asText()); s.put("alias", unquote(alias)); s.remove(List.of("join", "on", "leftId")); sources.add(s);
        }
        aliases.remove("");
        JsonNode root = readTree(legacy.path("tree"));
        model.withArray("roots").add(root); model.put("distinct", legacy.path("distinct").asBoolean());
        List<ObjectNode> selected = new ArrayList<>();
        for (SelectItem<?> item : select.getSelectItems()) {
            Expression e = item.getExpression();
            if (e instanceof AllColumns) {
                String qualifier = e instanceof AllTableColumns a ? a.getTable().getFullyQualifiedName() : null;
                for (JsonNode s : sources) if (qualifier == null || s.path("id").asText().equals(aliases.get(unquote(qualifier)))) {
                    if (s.path("columns").isEmpty()) throw VisualQuery.invalid("Column metadata is required to expand a wildcard");
                    for (JsonNode c : s.path("columns")) selected.add(out(column(s.path("id").asText(), c.path("name").asText()), ""));
                }
            } else {
                ObjectNode o = out(expr(e), item.getAliasName() == null ? "" : unquote(item.getAliasName())); selected.add(o);
                if (!o.path("alias").asText().isBlank()) outputAliases.put(o.path("alias").asText(), VisualQuery.expression("output").put("output", o.path("id").asText()));
            }
        }
        boolean summary = select.getGroupBy() != null || select.getHaving() != null || selected.stream().anyMatch(o -> aggregate(o.path("expression")));
        model.put("mode", summary ? "summary" : "detail"); ObjectNode active = model.withObject(summary ? "summary" : "detail");
        for (ObjectNode o : selected) active.withArray("outputs").add(o);
        if (summary) for (JsonNode s : sources) for (JsonNode c : s.path("columns")) model.withObject("detail").withArray("outputs").add(out(column(s.path("id").asText(), c.path("name").asText()), ""));
        if (!summary) { ObjectNode count=VisualQuery.expression("function").put("name","COUNT");count.putArray("args").add(VisualQuery.expression("star"));model.withObject("summary").withArray("outputs").add(out(count,"")); }
        if (select.getWhere() != null) model.set("where", expr(select.getWhere()));
        if (select.getGroupBy() != null) for (Object g : select.getGroupBy().getGroupByExpressionList()) active.withArray("groups").addObject().put("id", "g" + active.withArray("groups").size()).set("expression", expr((Expression)g));
        if (select.getHaving() != null) active.set("having", expr(select.getHaving()));
        resolveAlias = true;
        if (select.getOrderByElements() != null) for (OrderByElement order : select.getOrderByElements()) {
            if(order.getNullOrdering()!=null)throw VisualQuery.invalid("Explicit NULL ordering is preserved in Script");
            JsonNode e;
            if (order.getExpression() instanceof LongValue ordinal) {
                int n = (int) ordinal.getValue(); if (n < 1 || n > selected.size()) throw VisualQuery.invalid("Invalid output ordinal");
                e = VisualQuery.expression("output").put("output", selected.get(n - 1).path("id").asText());
            } else e = expr(order.getExpression());
            active.withArray("order").addObject().put("direction", order.isAsc() ? "ASC" : "DESC").set("expression", e);
        }
        return model;
    }
    JsonNode readTree(JsonNode node)throws Exception{
        if(node.path("kind").asText().equals("source"))return source(node.path("source").asText());
        if(node.path("kind").asText().equals("group"))return readTree(node.path("child"));
        if(!node.path("kind").asText().equals("join"))throw VisualQuery.invalid("Missing join structure");
        ObjectNode join=VisualQuery.expression("join").put("id",node.path("id").asText()).put("type",node.path("type").asText());join.set("left",readTree(node.path("left")));join.set("right",readTree(node.path("right")));ArrayNode predicates=join.putArray("pairs");if(!node.path("type").asText().equals("CROSS"))pairs(CCJSqlParserUtil.parseCondExpression(node.path("on").asText()),predicates);return join;
    }
    ObjectNode out(JsonNode e, String alias) { ObjectNode o = Profiles.JSON.createObjectNode().put("id", "o" + ++output).put("alias", alias); o.set("expression", e); return o; }
    static ObjectNode source(String id) { return VisualQuery.expression("source").put("source", id); }
    static ObjectNode column(String id, String name) { return VisualQuery.expression("column").put("source", id).put("name", unquote(name)); }
    void pairs(Expression e, ArrayNode pairs) {
        if (e instanceof ParenthesedExpressionList<?> p && p.size() == 1) { pairs(p.get(0), pairs); return; }
        if (e instanceof AndExpression a) { pairs(a.getLeftExpression(), pairs); pairs(a.getRightExpression(), pairs); return; }
        if (!(e instanceof BinaryExpression b) || !(b.getLeftExpression() instanceof Column) || !(b.getRightExpression() instanceof Column)
                || !Set.of("=", "<>", "<", "<=", ">", ">=").contains(b.getStringExpression())) throw VisualQuery.invalid("Join predicate requires an expression unsupported by column connections");
        ObjectNode pair = pairs.addObject().put("id", "p" + pairs.size()).put("op", b.getStringExpression()); pair.set("left", expr(b.getLeftExpression())); pair.set("right", expr(b.getRightExpression()));
    }
    JsonNode expr(Expression e) {
        if (e instanceof ParenthesedExpressionList<?> p && p.size() == 1) return expr(p.get(0));
        if (e instanceof Column c) {
            String name = unquote(c.getColumnName()), qualifier = c.getTable() == null ? "" : c.getTable().getFullyQualifiedName();
            if (resolveAlias && qualifier.isBlank() && outputAliases.containsKey(name)) return outputAliases.get(name).deepCopy();
            String id = aliases.get(unquote(qualifier));
            if (qualifier.isBlank()) { List<JsonNode> matching = new ArrayList<>(); for (JsonNode s : model.path("sources")) if (VisualQuery.hasColumn(s, name)) matching.add(s); if (matching.size() != 1) throw VisualQuery.invalid("Column origin is ambiguous"); id = matching.get(0).path("id").asText(); }
            if (id == null) throw VisualQuery.invalid("Unknown source alias"); return column(id, name);
        }
        if (e instanceof JdbcParameter) { String id = "param" + ++parameter; model.withArray("parameters").addObject().put("id", id).put("name", id).put("type", "text"); return VisualQuery.expression("parameter").put("parameter", id); }
        if (e instanceof NullValue) return VisualQuery.expression("literal").put("type", "null").putNull("value");
        if (e instanceof StringValue s) return literal("text", s.getValue());
        if (e instanceof LongValue || e instanceof DoubleValue) return literal("number", e.toString());
        if (e instanceof BooleanValue) return literal("boolean", e.toString().toLowerCase(Locale.ROOT));
        if (e instanceof DateValue d) return literal("date", d.getValue().toString());
        if (e instanceof TimeValue t) return literal("time", t.getValue().toString());
        if (e instanceof TimestampValue t) return literal("timestamp", t.getValue().toString());
        if (e instanceof SignedExpression s) { if (s.getSign() == '+') return expr(s.getExpression()); ObjectNode n = VisualQuery.expression("binary").put("op", "-"); n.set("left", literal("number", "0")); n.set("right", expr(s.getExpression())); return n; }
        if (e instanceof BinaryExpression b) {
            String op = b.getStringExpression(); ObjectNode n;
            if (Set.of("AND", "OR").contains(op)) { n = VisualQuery.expression("logical").put("op", op); n.putArray("args").add(expr(b.getLeftExpression())).add(expr(b.getRightExpression())); }
            else { if (b instanceof LikeExpression l && l.getEscape() != null) throw VisualQuery.invalid("LIKE ESCAPE is preserved in Script"); n = VisualQuery.expression("binary").put("op", b instanceof LikeExpression l && l.isNot() ? "NOT LIKE" : op); n.set("left", expr(b.getLeftExpression())); n.set("right", expr(b.getRightExpression())); }
            return n;
        }
        if (e instanceof IsNullExpression z) { ObjectNode n = VisualQuery.expression("null").put("not", z.isNot()); n.set("arg", expr(z.getLeftExpression())); return n; }
        if (e instanceof NotExpression z) { ObjectNode n = VisualQuery.expression("not"); n.set("arg", expr(z.getExpression())); return n; }
        if (e instanceof Between b) { ObjectNode n = VisualQuery.expression("between").put("not", b.isNot()); n.set("arg", expr(b.getLeftExpression())); n.set("lower", expr(b.getBetweenExpressionStart())); n.set("upper", expr(b.getBetweenExpressionEnd())); return n; }
        if (e instanceof InExpression in && in.getRightExpression() instanceof ExpressionList<?> list) { ObjectNode n = VisualQuery.expression("in").put("not", in.isNot()); n.set("arg", expr(in.getLeftExpression())); ArrayNode args = n.putArray("args"); list.forEach(a -> args.add(expr(a))); return n; }
        if (e instanceof Function f) {
            Function expected=new Function();expected.setName(f.getName());expected.setParameters(f.getParameters());expected.setDistinct(f.isDistinct());if(!f.toString().equals(expected.toString()))throw VisualQuery.invalid("Function modifiers are preserved in Script");
            String name = f.getName(); if (name.contains(".") || !VisualQuery.FUNCTIONS.contains(name.toUpperCase(Locale.ROOT))) throw VisualQuery.invalid("Import of this function requires verified visual signature metadata");
            ObjectNode n = VisualQuery.expression("function").put("name", name.toUpperCase(Locale.ROOT)).put("aggregate", VisualQuery.AGGREGATES.contains(name.toUpperCase(Locale.ROOT))).put("distinct", f.isDistinct());
            ArrayNode args = n.putArray("args"); if (f.getParameters() != null) for (Expression a : f.getParameters()) args.add(a instanceof AllColumns ? VisualQuery.expression("star") : expr(a)); return n;
        }
        if (e instanceof CaseExpression c && c.getSwitchExpression() == null) {
            ObjectNode n = VisualQuery.expression("case"); ArrayNode branches = n.putArray("branches"); for (WhenClause w : c.getWhenClauses()) { ObjectNode b = branches.addObject(); b.set("when", expr(w.getWhenExpression())); b.set("then", expr(w.getThenExpression())); } n.set("else", c.getElseExpression() == null ? literal("null", "") : expr(c.getElseExpression())); return n;
        }
        throw VisualQuery.invalid("Unsupported visual expression: " + e.getClass().getSimpleName());
    }
    static ObjectNode literal(String type, String value) { return VisualQuery.expression("literal").put("type", type).put("value", value); }
    static String unquote(String s) { if (s.length() > 1 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("`") && s.endsWith("`") || s.startsWith("[") && s.endsWith("]"))) { String end = s.substring(s.length()-1); return s.substring(1, s.length()-1).replace(end+end, end); } return s; }
    static boolean aggregate(JsonNode e) { if (VisualQuery.aggregateFunction(e)) return true; for (JsonNode child : e) if (child.isContainerNode() && aggregate(child)) return true; return false; }
}
