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

/** Browser-only structured SELECT edits. No database access; unsupported/ambiguous edits fail closed. */
final class GridSql {
    record Edit(String sql,ArrayNode parameters) {}
    /** Separates original predicates from grid-added predicates; only the latter populate the editor. */
    static ObjectNode prepare(JsonNode input){
        if(input.path("action").asText().equals("filter_values")||input.has("valueFilters"))return prepareValues(input);
        return prepareCore(input);
    }
    private static ObjectNode prepareValues(JsonNode input){
        String action=input.path("action").asText();
        ObjectNode request=input.deepCopy();request.remove(List.of("pickerBase","valueFilters"));
        if(input.has("valueFilters")&&!input.path("valueFilters").isArray())throw invalid("Invalid column value filters");
        ArrayNode filters=input.has("valueFilters")?(ArrayNode)input.path("valueFilters").deepCopy():Profiles.JSON.createArrayNode();
        if(filters.size()>256)throw invalid("Too many column filters");
        if(input.path("pickerBase").isObject()&&!action.equals("expression")){
            for(String field:List.of("sql","parameters","baseSql","baseParameters","filterExpression"))
                if(input.path("pickerBase").has(field))request.set(field,input.path("pickerBase").get(field));
        }
        if(Set.of("clear_all","clear_filters","expression").contains(action))filters.removeAll();
        if(action.equals("filter_values")){
            String id=Profiles.text(input,"columnId",256);HumanSql.checkParameters(input.path("values"));
            boolean removed=false;
            for(int i=filters.size()-1;i>=0;i--)if(filters.get(i).path("columnId").asText().equals(id)){filters.remove(i);removed=true;}
            if(input.path("values").isEmpty()){
                if(!removed)throw invalid("Select at least one value or clear an existing value filter");
            }else{
                ObjectNode filter=Profiles.JSON.createObjectNode();
                for(String field:List.of("columnId","columnIndex","columnLabel","jdbcType","values"))if(input.has(field))filter.set(field,input.get(field));
                filters.add(filter);
            }
            request.put("action","refresh");
        }
        ObjectNode base=prepareCore(request),result=base.deepCopy();
        for(JsonNode filter:filters){
            if(!filter.isObject())throw invalid("Invalid column value filter");
            ObjectNode next=result.deepCopy();next.setAll((ObjectNode)filter);next.put("action","filter_values");next.set("columns",input.path("columns"));
            result=prepareCore(next);
        }
        result.set("pickerBase",base);result.set("valueFilters",filters);return result;
    }
    private static ObjectNode prepareCore(JsonNode input){
        try{
            String baseSql=input.path("baseSql").asText(input.path("sql").asText());JsonNode baseParameters=input.has("baseParameters")?input.get("baseParameters"):input.path("parameters");
            ObjectNode request=input.deepCopy();boolean expression=input.path("action").asText().equals("expression");
            if(expression){request.put("sql",baseSql);request.set("parameters",baseParameters);}
            Edit edited=edit(request);Edit base;String filter;
            if(expression||input.path("action").asText().equals("refresh")){base=new Edit(baseSql,((ArrayNode)baseParameters).deepCopy());filter=input.path(expression?"expression":"filterExpression").asText();}
            else{
                PlainSelect original=(PlainSelect)CCJSqlParserUtil.parse(baseSql,p->p.withTimeOut(500));
                PlainSelect current=(PlainSelect)CCJSqlParserUtil.parse(edited.sql(),p->p.withTimeOut(500));
                Set<Object> nodes=Collections.newSetFromMap(new IdentityHashMap<>());inspect(current,nodes,0);
                boolean single=current.getJoins()==null||current.getJoins().isEmpty();Set<String> originalKeys=new HashSet<>();for(Expression term:terms(original.getWhere()))originalKeys.add(signature(term,single));
                var retained=new ArrayList<Expression>();var added=new ArrayList<Expression>();for(Expression term:terms(current.getWhere()))(originalKeys.contains(signature(term,single))?retained:added).add(term);
                for(Object node:nodes)if(node instanceof JdbcParameter p)p.setUseFixedIndex(true);
                Expression extra=and(added);filter=extra==null?"":SqlScript.displayIndexed(extra.toString(),edited.parameters());
                current.setWhere(and(retained));base=SqlScript.bindIndexed(current.toString(),edited.parameters());
            }
            ObjectNode out=Profiles.JSON.createObjectNode().put("sql",edited.sql()).put("displaySql",display(edited)).put("baseSql",base.sql()).put("filterExpression",filter);out.set("parameters",edited.parameters());out.set("baseParameters",base.parameters());return out;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw invalid("The filter expression cannot be applied safely. Edit the SELECT in the SQL editor.");}
    }
    private static String display(Edit edit)throws Exception{
        var statement=CCJSqlParserUtil.parse(edit.sql(),p->p.withTimeOut(500));Set<Object> nodes=Collections.newSetFromMap(new IdentityHashMap<>());inspect(statement,nodes,0);
        for(Object node:nodes)if(node instanceof JdbcParameter parameter)parameter.setUseFixedIndex(true);
        return SqlScript.displayIndexed(statement.toString(),edit.parameters());
    }
    private record TypedValue(JsonNode value,String cast){}
    private static java.math.BigDecimal boundedNumber(String text){
        java.math.BigDecimal number=new java.math.BigDecimal(text);
        if(number.precision()>8192||Math.abs((long)number.scale())>8192)throw invalid("Numeric value exceeds the value allowance");
        return number;
    }
    private static TypedValue typedValue(JsonNode value,int type){
        String text=value.asText(),trimmed=text.trim();
        try{
            return switch(type){
                case java.sql.Types.TINYINT,java.sql.Types.SMALLINT,java.sql.Types.INTEGER,java.sql.Types.BIGINT -> {
                    java.math.BigDecimal number=boundedNumber(trimmed);number.toBigIntegerExact();yield new TypedValue(DecimalNode.valueOf(number),null);
                }
                case java.sql.Types.NUMERIC,java.sql.Types.DECIMAL,java.sql.Types.FLOAT,java.sql.Types.REAL,java.sql.Types.DOUBLE -> new TypedValue(DecimalNode.valueOf(boundedNumber(trimmed)),null);
                case java.sql.Types.BOOLEAN,java.sql.Types.BIT -> {
                    if(!Set.of("true","false","1","0").contains(trimmed.toLowerCase(Locale.ROOT)))throw invalid("Enter true, false, 1 or 0 for a Boolean column");
                    boolean bool=trimmed.equalsIgnoreCase("true")||trimmed.equals("1");
                    yield type==java.sql.Types.BIT?new TypedValue(IntNode.valueOf(bool?1:0),"BIT"):new TypedValue(BooleanNode.valueOf(bool),null);
                }
                case java.sql.Types.DATE -> new TypedValue(TextNode.valueOf(java.time.LocalDate.parse(trimmed).toString()),"DATE");
                case java.sql.Types.TIME -> new TypedValue(TextNode.valueOf(java.time.LocalTime.parse(trimmed).toString()),"TIME");
                case java.sql.Types.TIMESTAMP -> new TypedValue(TextNode.valueOf(java.time.LocalDateTime.parse(trimmed.replace(' ','T')).toString().replace('T',' ')),"TIMESTAMP");
                case java.sql.Types.TIME_WITH_TIMEZONE -> new TypedValue(TextNode.valueOf(java.time.OffsetTime.parse(trimmed).toString()),"TIME WITH TIME ZONE");
                case java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> new TypedValue(TextNode.valueOf(java.time.OffsetDateTime.parse(trimmed.replace(' ','T')).toString().replace('T',' ')),"TIMESTAMP WITH TIME ZONE");
                case java.sql.Types.VARCHAR,java.sql.Types.CHAR,java.sql.Types.LONGVARCHAR,java.sql.Types.NVARCHAR,java.sql.Types.NCHAR,java.sql.Types.LONGNVARCHAR -> new TypedValue(TextNode.valueOf(text),null);
                default -> throw invalid("This column type requires an explicit typed expression in the SQL editor");
            };
        }catch(NumberFormatException e){throw invalid("Enter a numeric value for this column");}
        catch(ArithmeticException e){throw invalid("Enter a whole number for an integer column");}
        catch(java.time.DateTimeException e){throw invalid("Enter a valid ISO date/time (YYYY-MM-DD, HH:mm:ss, or YYYY-MM-DD HH:mm:ss; include an offset for timezone columns)");}
    }
    private static List<Expression> terms(Expression expression){
        if(expression==null)return List.of();if(expression instanceof ParenthesedExpressionList<?> group&&group.size()==1)return terms(group.get(0));
        if(expression instanceof AndExpression a){var list=new ArrayList<>(terms(a.getLeftExpression()));list.addAll(terms(a.getRightExpression()));return list;}return List.of(expression);
    }
    private static Expression and(List<Expression> terms){Expression result=null;for(Expression term:terms){Expression next=term instanceof OrExpression?new ParenthesedExpressionList<>(term):term;result=result==null?next:new AndExpression(result,next);}return result;}
    private static String signature(Expression term,boolean single){
        if(term instanceof BinaryExpression b&&b.getLeftExpression() instanceof Column c&&Set.of("=","<>",">","<").contains(b.getStringExpression()))return key(single?c.getColumnName():c.toString())+" "+b.getStringExpression();
        if(term instanceof IsNullExpression n&&n.getLeftExpression() instanceof Column c)return key(single?c.getColumnName():c.toString())+(n.isNot()?" IS NOT NULL":" IS NULL");
        return term.toString();
    }
    static void describeColumns(String sql,ObjectNode result){
        JsonNode columns=result.path("columns");if(!columns.isArray()||columns.size()>256)return;
        try{
            if(!(CCJSqlParserUtil.parse(sql,p->p.withTimeOut(500)) instanceof PlainSelect select))return;
            Map<String,Integer> counts=new HashMap<>();for(var column:columns)counts.merge(column.path("label").asText(),1,Integer::sum);
            for(int i=0;i<columns.size();i++){
                var column=(ObjectNode)columns.get(i);String label=column.path("label").asText();
                try{String origin=resolve(select,i,label,columns).toString();column.put("sourceExpression",origin);if(counts.get(label)>1)column.put("displayLabel",origin);}
                catch(IllegalArgumentException ambiguous){if(counts.get(label)>1)column.put("displayLabel",label+" ["+column.path("id").asText()+"]");}
            }
        }catch(Exception unsupported){/* Provenance is optional; never prevent ordinary human SQL. */}
    }
    static Edit edit(JsonNode input) {
        String sql=Profiles.text(input,"sql",16384),action=Profiles.text(input,"action",24);
        JsonNode supplied=input.path("parameters");HumanSql.checkParameters(supplied);
        try {
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(500));
            if(statements.size()!=1||!(statements.get(0) instanceof PlainSelect select))throw invalid("Only one SELECT can be edited; scripts, batches and set queries must be edited in the SQL editor");
            Set<Object> nodes=Collections.newSetFromMap(new IdentityHashMap<>());inspect(select,nodes,0);
            var params=nodes.stream().filter(JdbcParameter.class::isInstance).map(JdbcParameter.class::cast).toList();
            if(params.size()!=supplied.size())throw invalid("Prepared values do not match the originating statement");
            for(var parameter:params){if(parameter.isUseFixedIndex()||!"?".equals(parameter.getParameterCharacter())||parameter.getIndex()==null||parameter.getIndex()<1||parameter.getIndex()>supplied.size())throw invalid("Only positional JDBC parameters can be edited");parameter.setUseFixedIndex(true);}
            ArrayNode values=((ArrayNode)supplied).deepCopy();
            if(action.equals("expression")){
                String text=input.path("expression").asText();if(text.length()>8192)throw invalid("Filter expression exceeds 8192 characters");
                if(!text.isBlank()){
                    Expression extra=CCJSqlParserUtil.parseCondExpression(text,false);Set<Object> extraNodes=Collections.newSetFromMap(new IdentityHashMap<>());inspect(extra,extraNodes,0);
                    if(extraNodes.stream().anyMatch(JdbcParameter.class::isInstance))throw invalid("Enter literal filter values, not unbound question-mark parameters");
                    select.setWhere(select.getWhere()==null?extra:new AndExpression(new ParenthesedExpressionList<>(select.getWhere()),new ParenthesedExpressionList<>(extra)));
                }
            }
            else if(action.equals("refresh")){return new Edit(sql,values);/* Validated above; preserve the exact current query and bindings. */}
            else if(action.equals("clear_all")){select.setWhere(null);select.setOrderByElements(null);}
            else if(action.equals("clear_filters"))select.setWhere(null);
            else if(action.equals("clear_order"))select.setOrderByElements(null);
            else {
                int index=input.path("columnIndex").asInt(-1);String label=Profiles.text(input,"columnLabel",1024);
                Column column=resolve(select,index,label,input.path("columns"));String alias=select.getSelectItems().size()>index&&index>=0?select.getSelectItems().get(index).getAliasName():null;
                boolean single=select.getJoins()==null||select.getJoins().isEmpty();
                if(action.equals("order")){
                    String direction=Profiles.text(input,"direction",4);if(!Set.of("ASC","DESC").contains(direction))throw invalid("Unknown order direction");
                    List<OrderByElement> order=select.getOrderByElements();if(order==null){order=new ArrayList<>();select.setOrderByElements(order);}boolean found=false;
                    for(var item:order){Expression e=item.getExpression();if(same(e,column,single)||e instanceof LongValue n&&n.getValue()==index+1||alias!=null&&e instanceof Column c&&key(c.getColumnName()).equals(key(alias))){item.setAsc(direction.equals("ASC"));item.setAscDescPresent(true);found=true;}}
                    if(!found){var item=new OrderByElement();item.setExpression(column);item.setAsc(direction.equals("ASC"));item.setAscDescPresent(true);order.add(item);}
                }else if(action.equals("filter_values")){
                    Expression predicate=valuePredicate(column,input,values);
                    select.setWhere(select.getWhere()==null?predicate:new AndExpression(new ParenthesedExpressionList<>(select.getWhere()),new ParenthesedExpressionList<>(predicate)));
                }else if(action.equals("filter")){
                    String operator=Profiles.text(input,"operator",12);Expression replacement;
                    if(operator.equals("IS NULL")||operator.equals("IS NOT NULL")){var condition=new IsNullExpression();condition.setLeftExpression(column);condition.setNot(operator.equals("IS NOT NULL"));replacement=condition;}
                    else {
                        if(!Set.of("=","<>",">","<").contains(operator))throw invalid("Unknown comparison operator");
                        if(!input.has("value")||input.path("value").isNull())throw invalid("Use IS NULL or IS NOT NULL for NULL values");
                        var check=Profiles.JSON.createArrayNode().add(input.get("value"));HumanSql.checkParameters(check);values.add(input.get("value"));
                        BinaryExpression comparison=switch(operator){case "="->new EqualsTo();case "<>"->new NotEqualsTo();case ">"->new GreaterThan();default->new MinorThan();};
                        Expression parameter=new JdbcParameter().withIndex(values.size()).withUseFixedIndex(true);
                        TypedValue typed=typedValue(input.get("value"),input.path("jdbcType").asInt(java.sql.Types.VARCHAR));values.set(values.size()-1,typed.value());String cast=typed.cast();
                        if(cast!=null)parameter=new CastExpression().withLeftExpression(parameter).withType(new net.sf.jsqlparser.statement.create.table.ColDataType().withDataType(cast)).withUseCastKeyword(true);
                        comparison.setLeftExpression(column);comparison.setRightExpression(parameter);replacement=comparison;
                    }
                    int[] replaced={0};Expression where=replace(select.getWhere(),column,operator,replacement,single,replaced,false);
                    if(replaced[0]>1)throw invalid("Several matching comparisons exist; edit this condition in the SQL editor");
                    if(replaced[0]==0)where=where==null?replacement:new AndExpression(new ParenthesedExpressionList<>(where),replacement);
                    select.setWhere(where);
                }else throw invalid("Unknown grid SQL action");
            }
            String rewritten=select.toString();var normalized=SqlScript.bindIndexed(rewritten,values);HumanSql.checkParameters(normalized.parameters());
            if(normalized.sql().length()>16384)throw invalid("Updated SQL exceeds the statement-size limit");return normalized;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw invalid("This SQL syntax cannot be safely edited automatically; use the SQL editor");}
    }
    private static Expression valuePredicate(Column column,JsonNode input,ArrayNode parameters){
        JsonNode chosen=input.path("values");HumanSql.checkParameters(chosen);if(chosen.isEmpty())throw invalid("Select at least one value");
        List<Expression> entries=new ArrayList<>();boolean hasNull=false;Set<JsonNode> seen=new HashSet<>();
        for(JsonNode value:chosen){
            if(value.isNull()){hasNull=true;continue;}
            TypedValue typed=typedValue(value,input.path("jdbcType").asInt(java.sql.Types.VARCHAR));if(!seen.add(typed.value()))continue;
            // JSON numbers would round in the browser before query execution. Bind exact
            // numeric text with an explicit lossless decimal shape instead.
            String cast=typed.cast();JsonNode bound=typed.value();
            if(bound.isNumber()&&cast==null){
                java.math.BigDecimal number=bound.decimalValue().stripTrailingZeros();
                int scale=Math.max(0,number.scale()),precision=Math.max(1,Math.max(scale,number.precision()-number.scale()+scale));
                if(precision>8192)throw invalid("Numeric value exceeds the value allowance");
                bound=TextNode.valueOf(number.toPlainString());cast="DECIMAL("+precision+","+scale+")";
            }
            parameters.add(bound);Expression parameter=new JdbcParameter().withIndex(parameters.size()).withUseFixedIndex(true);
            if(cast!=null)parameter=new CastExpression().withLeftExpression(parameter).withType(new net.sf.jsqlparser.statement.create.table.ColDataType().withDataType(cast)).withUseCastKeyword(true);
            entries.add(parameter);
        }
        Expression predicate=entries.isEmpty()?null:new InExpression(column,new ParenthesedExpressionList<>(entries));
        if(hasNull){IsNullExpression nil=new IsNullExpression();nil.setLeftExpression(column);predicate=predicate==null?nil:new OrExpression(predicate,nil);}
        return predicate;
    }
    static Column resolve(PlainSelect select,int index,String label,JsonNode columns){
        if(index<0||index>=256)throw invalid("Invalid result column");var items=select.getSelectItems();
        var sources=new ArrayList<FromItem>();if(select.getFromItem()!=null)sources.add(select.getFromItem());if(select.getJoins()!=null)for(var join:select.getJoins())sources.add(join.getRightItem());
        long stars=items.stream().filter(i->i.getExpression() instanceof AllColumns).count();
        if(stars>0&&columns.isArray()&&columns.size()<=256&&index<columns.size()){
            if(stars!=1)throw invalid("Multiple wildcards cannot be mapped reliably. Select qualified columns explicitly.");
            int start=0;for(var item:items){if(item.getExpression() instanceof AllColumns)break;start++;}
            int width=columns.size()-items.size()+1;
            if(index>=start&&index<start+width){
                Expression star=items.get(start).getExpression();FromItem source=null;
                if(star instanceof AllTableColumns qualified){String qualifier=qualified.getTable().getFullyQualifiedName();source=sources.stream().filter(s->key(sourceName(s)).equals(key(qualifier))).findFirst().orElseThrow(()->invalid("Unknown wildcard qualifier"));}
                else if(sources.size()==1)source=sources.get(0);
                else source=sourceFromMetadata(sources,columns.get(index));
                String name=columns.get(index).path("name").asText(label);
                if(source instanceof ParenthesedSelect)name=label;
                if(name.isBlank())name=label;
                return new Column(sources.size()==1&&source.getAlias()==null?null:sourceQualifier(source,sources),identifier(name));
            }
            int itemIndex=index<start?index:index-width+1;
            if(itemIndex<0||itemIndex>=items.size()||!(items.get(itemIndex).getExpression() instanceof Column column))throw invalid("Expression columns require explicit SQL editing");
            return qualify(column,sources,columns.get(index));
        }
        if(items.size()==1&&items.get(0).getExpression() instanceof AllColumns&&select.getFromItem() instanceof Table table&&(select.getJoins()==null||select.getJoins().isEmpty())){
            // A JDBC result label is data, not SQL. The simple-star path only accepts ordinary identifiers.
            if(!label.matches("[A-Za-z_][A-Za-z0-9_$]*"))throw invalid("Quote this column explicitly in the SELECT before filtering");
            return new Column(table.getAlias()==null?null:sourceQualifier(table,sources),label);
        }
        if(items.stream().anyMatch(i->i.getExpression() instanceof AllColumns)||index>=items.size()||!(items.get(index).getExpression() instanceof Column column))throw invalid("Select this column explicitly; expression, aggregate and expanded wildcard columns cannot be resolved safely");
        return qualify(column,sources,columns.isArray()&&index<columns.size()?columns.get(index):null);
    }
    private static Column qualify(Column column,List<FromItem> sources,JsonNode metadata){
        if(column.getTable()!=null&&!column.getTable().getFullyQualifiedName().isEmpty())return column;
        if(sources.size()<=1)return column;
        FromItem source=sourceFromMetadata(sources,metadata);
        return new Column(sourceQualifier(source,sources),column.getColumnName());
    }
    private static FromItem sourceFromMetadata(List<FromItem> sources,JsonNode metadata){
        String name=metadata==null?"":metadata.path("table").asText(),schema=metadata==null?"":metadata.path("schema").asText();
        var matches=sources.stream().filter(s->s instanceof Table t&&!name.isBlank()&&(t.getUnquotedName().equals(name)||key(t.getName()).equals(key(name)))&&(schema.isBlank()||t.getSchemaName()==null||t.getUnquotedSchemaName().equals(schema)||key(t.getSchemaName()).equals(key(schema)))).toList();
        if(matches.size()!=1)throw invalid("Column origin is ambiguous (duplicate names, self-join or subquery). Select the column with its table/subquery alias explicitly.");
        return matches.get(0);
    }
    private static String sourceName(FromItem source){if(source.getAlias()!=null)return source.getAlias().getName();if(source instanceof Table t)return t.getFullyQualifiedName();throw invalid("Give this subquery an alias before filtering");}
    private static Table sourceQualifier(FromItem source,List<FromItem> sources){
        // Preserve parsed identifier parts: Table(String) splits dotted names and can corrupt quotes.
        // Aliases are single identifiers; dotted aliases also hit a parser round-trip limitation.
        if(source.getAlias()!=null){
            String alias=source.getAlias().getName();
            if(alias.contains("."))throw invalid("Automatic grid filters cannot safely rewrite an alias containing a dot. Use an alias without dots in the SQL editor.");
            return singleIdentifier(alias);
        }
        if(source instanceof Table table){
            // A unique table name is sufficient; same-named tables in different schemas need all parts.
            long matches=sources.stream().filter(s->(s instanceof Table t&&t.getUnquotedName().equals(table.getUnquotedName()))||(s.getAlias()!=null&&key(s.getAlias().getName()).equals(key(table.getName())))).count();
            return matches==1&&!table.getUnquotedName().contains(".")?singleIdentifier(table.getName()):table;
        }
        throw invalid("Give this subquery an alias before filtering");
    }
    private static Table singleIdentifier(String name){var table=new Table();table.getNameParts().add(name);return table;}
    private static String identifier(String name){if(name.matches("[a-z_][a-z0-9_$]*"))return name;return "\""+name.replace("\"","\"\"")+"\"";}
    private static Expression replace(Expression where,Column column,String operator,Expression replacement,boolean single,int[] replaced,boolean underOr){
        if(where==null)return null;
        if(where instanceof ParenthesedExpressionList<?> group&&group.size()==1)return new ParenthesedExpressionList<>(replace(group.get(0),column,operator,replacement,single,replaced,underOr));
        if(where instanceof AndExpression and){and.setLeftExpression(replace(and.getLeftExpression(),column,operator,replacement,single,replaced,underOr));and.setRightExpression(replace(and.getRightExpression(),column,operator,replacement,single,replaced,underOr));return and;}
        if(where instanceof OrExpression or){replace(or.getLeftExpression(),column,operator,replacement,single,replaced,true);replace(or.getRightExpression(),column,operator,replacement,single,replaced,true);return or;}
        String current=where instanceof EqualsTo?"=":where instanceof NotEqualsTo?"<>":where instanceof GreaterThan?">":where instanceof MinorThan?"<":where instanceof IsNullExpression n?(n.isNot()?"IS NOT NULL":"IS NULL"):"";
        Expression left=where instanceof BinaryExpression b?b.getLeftExpression():where instanceof IsNullExpression n?n.getLeftExpression():null;
        if(current.equals(operator)&&same(left,column,single)){
            if(underOr)throw invalid("The matching comparison is inside OR; edit it explicitly to preserve its meaning");
            if(where instanceof BinaryExpression b&&!(b.getRightExpression() instanceof JdbcParameter||b.getRightExpression() instanceof CastExpression cast&&cast.getLeftExpression() instanceof JdbcParameter||b.getRightExpression() instanceof StringValue||b.getRightExpression() instanceof LongValue||b.getRightExpression() instanceof DoubleValue||b.getRightExpression() instanceof SignedExpression||b.getRightExpression() instanceof NullValue||b.getRightExpression() instanceof DateValue||b.getRightExpression() instanceof TimestampValue||b.getRightExpression() instanceof TimeValue||b.getRightExpression() instanceof BooleanValue))throw invalid("This comparison is an expression, not a replaceable value");
            replaced[0]++;return replacement;
        }
        return where;
    }
    private static boolean same(Expression e,Column column,boolean single){if(!(e instanceof Column c)||!key(c.getColumnName()).equals(key(column.getColumnName())))return false;String a=c.getTable()==null?"":c.getTable().getFullyQualifiedName(),b=column.getTable()==null?"":column.getTable().getFullyQualifiedName();return key(a).equals(key(b))||single&&(a.isEmpty()||b.isEmpty());}
    private static String key(String value){if(value==null)return "";return value.startsWith("\"")||value.startsWith("`")||value.startsWith("[")?value:value.toLowerCase(Locale.ROOT);}
    private static void inspect(Object value,Set<Object> seen,int depth)throws ReflectiveOperationException{
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof Enum<?>)return;
        if(depth>100||seen.size()>8000)throw invalid("SQL is too complex for automatic editing");if(!seen.add(value))return;
        if(value instanceof Iterable<?> list){for(Object item:list)inspect(item,seen,depth+1);return;}
        if(value instanceof net.sf.jsqlparser.statement.Statement&&!(value instanceof Select))throw invalid("Data-modifying statements and CTEs cannot be rerun from a grid");
        if(value instanceof PlainSelect s&&(s.getIntoTables()!=null||s.getIntoTempTable()!=null||s.getForMode()!=null||s.getForUpdateTable()!=null))throw invalid("SELECT INTO and locking queries cannot be rerun from a grid");
        if(value instanceof JdbcNamedParameter||value instanceof SetOperationList)throw invalid("Named parameters and set queries require manual SQL editing");
        for(Class<?> c=value.getClass();c!=null&&c.getPackageName().startsWith("net.sf.jsqlparser");c=c.getSuperclass()){
            if(c.getSimpleName().equals("ASTNodeAccessImpl"))break;
            for(var field:c.getDeclaredFields()){if(java.lang.reflect.Modifier.isStatic(field.getModifiers())||field.isSynthetic())continue;field.setAccessible(true);inspect(field.get(value),seen,depth+1);}
        }
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException(message);}
    private GridSql(){}
}
