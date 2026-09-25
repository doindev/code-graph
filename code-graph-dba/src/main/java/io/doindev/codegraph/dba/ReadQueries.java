package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.expression.Function;
import java.lang.reflect.Modifier;
import java.util.*;

/** Version two read grammar. Legacy approval grammar deliberately does not call this class. */
final class ReadQueries {
    static final Set<String> VENDORS=Set.of("postgresql","mysql","mariadb","oracle");
    private static final Set<String> FUNCTIONS=Set.of("COUNT","SUM","AVG","MIN","MAX","COALESCE","NULLIF","ABS","LOWER","UPPER","LENGTH","CHAR_LENGTH");
    private static final Set<String> NODES=Set.of("PlainSelect","Values","SelectItem","ParenthesedSelect","SetOperationList","UnionOp",
        "Table","Column","Database","Alias","LongValue","DoubleValue","StringValue","NullValue","BooleanValue","JdbcParameter",
        "AllColumns","AllTableColumns","Join","OrderByElement","Limit","Offset","Fetch","Distinct","GroupByElement","Function",
        "CaseExpression","WhenClause","EqualsTo","NotEqualsTo","GreaterThan","GreaterThanEquals","MinorThan","MinorThanEquals",
        "AndExpression","OrExpression","NotExpression","Between","InExpression","IsNullExpression","LikeExpression","Addition",
        "Subtraction","Multiplication","Division","Modulo","SignedExpression","ExistsExpression","ParenthesedExpressionList",
        "ExpressionList","DateValue","TimeValue","TimestampValue");
    record Relation(String database,String schema,String object) {
        ObjectNode json(){return Profiles.JSON.createObjectNode().put("database",database).put("schema",schema).put("object",object);}
    }
    record Analysis(String sql,List<Relation> relations,boolean functions) {
        ArrayNode json(){ArrayNode a=Profiles.JSON.createArrayNode();relations.forEach(r->a.add(r.json()));return a;}
    }
    static Analysis analyze(String sql,JsonNode scope){
        String vendor=scope.path("vendor").asText();
        if(!VENDORS.contains(vendor))throw new IllegalArgumentException("SELECT permissions require a verified PostgreSQL, MySQL, MariaDB or Oracle read-only transaction adapter");
        if(scope.path("database").asText().isBlank()||scope.path("schema").asText().isBlank())throw new IllegalArgumentException("Select an explicit database and schema");
        if(sql==null||sql.isBlank()||sql.length()>16384)throw new IllegalArgumentException("SQL must contain 1..16384 characters");
        // Dialect escapes and executable comments must never bypass the positive AST grammar.
        if(sql.contains("\\")||sql.contains("@")||sql.contains("/*!" )||sql.toUpperCase(Locale.ROOT).contains("/*M!"))throw new IllegalArgumentException("Dialect escapes, variables and executable comments require one-time review");
        try{
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(500));
            if(statements.size()!=1||!(statements.get(0) instanceof Select select))throw new IllegalArgumentException("Exactly one read-only SELECT is required");
            Walker w=new Walker(scope);w.walk(select,Set.of(),0);
            return new Analysis(select.toString(),List.copyOf(w.relations),w.functions);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Unsupported read-only SELECT syntax",e);}
    }
    static Analysis inspection(String sql,JsonNode scope){
        if(!Set.of("mysql","mariadb").contains(scope.path("vendor").asText()))throw new IllegalArgumentException("This inspection needs a supported metadata tool");
        var m=java.util.regex.Pattern.compile("(?is)^SHOW\\s+CREATE\\s+(DATABASE|TABLE|VIEW|FUNCTION|PROCEDURE)\\s+(.+?)\\s*;?$").matcher(sql.trim());
        if(!m.matches())throw new IllegalArgumentException("Only supported SHOW CREATE statements qualify for metadata permission");
        String kind=m.group(1).toUpperCase(Locale.ROOT),name=m.group(2);
        var parsed=analyze("SELECT * FROM "+name,scope);
        if(parsed.relations().size()!=1)throw new IllegalArgumentException("An exact object is required");
        Relation r=parsed.relations().getFirst();
        if(kind.equals("DATABASE"))r=new Relation(r.object(),"","");
        else if(Set.of("FUNCTION","PROCEDURE").contains(kind))r=new Relation(r.database(),r.schema(),"");
        return new Analysis(sql,List.of(r),false);
    }
    static String identifier(String raw,String vendor){
        if(raw==null)return "";
        if(raw.length()>1&&((raw.startsWith("\"")&&raw.endsWith("\""))||(raw.startsWith("`")&&raw.endsWith("`")))){
            String q=raw.substring(0,1);return raw.substring(1,raw.length()-1).replace(q+q,q);
        }
        return vendor.equals("postgresql")?raw.toLowerCase(Locale.ROOT):vendor.equals("oracle")?raw.toUpperCase(Locale.ROOT):raw;
    }
    private static final class Walker {
        final JsonNode scope;final String vendor;final Set<Relation> relations=new LinkedHashSet<>();
        final Set<Object> seen=Collections.newSetFromMap(new IdentityHashMap<>());boolean functions;
        Walker(JsonNode scope){this.scope=scope;vendor=scope.path("vendor").asText();}
        void walk(Object value,Set<String> ctes,int depth)throws Exception{
            if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof Enum<?>)return;
            if(depth>128||seen.size()>10000)throw new IllegalArgumentException("SELECT complexity limit exceeded");
            if(!seen.add(value))return;
            if(value instanceof Iterable<?> values){for(Object v:values)walk(v,ctes,depth+1);return;}
            if(value instanceof Map<?,?> values){for(Object v:values.values())walk(v,ctes,depth+1);return;}
            if(value.getClass().isArray()){for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)walk(java.lang.reflect.Array.get(value,i),ctes,depth+1);return;}
            Class<?> type=value.getClass();if(!type.getPackageName().startsWith("net.sf.jsqlparser"))return;
            if(!NODES.contains(type.getSimpleName()))throw new IllegalArgumentException("Unsupported read-only SELECT construct: "+type.getSimpleName());
            if(value instanceof Select s){
                if(s.getForMode()!=null||s.getForUpdateTable()!=null||s.getForClause()!=null)throw new IllegalArgumentException("Locking SELECTs require one-time review");
                if(s instanceof PlainSelect p&&(p.getIntoTables()!=null||p.getIntoTempTable()!=null))throw new IllegalArgumentException("SELECT INTO is not a read permission");
                if(s.getWithItemsList()!=null){
                    Set<String> local=new HashSet<>(ctes);
                    for(var item:s.getWithItemsList()){
                        if(item.isRecursive()||!(item.getParenthesedStatement() instanceof ParenthesedSelect))throw new IllegalArgumentException("Only nonrecursive read-only CTEs are supported");
                        walk(item.getParenthesedStatement(),local,depth+1);
                        local.add(identifier(item.getAliasName(),vendor));
                    }
                    ctes=local;
                }
            }
            if(value instanceof Column column){
                if(vendor.equals("oracle")&&Set.of("NEXTVAL","CURRVAL").contains(identifier(column.getColumnName(),vendor).toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("Oracle sequence pseudocolumns require one-time review");
                return; // Column qualifiers are aliases, not relation reads.
            }
            if(value instanceof Table t){
                String object=identifier(t.getName(),vendor),schema=identifier(t.getSchemaName(),vendor);
                if(schema.isEmpty()&&ctes.contains(object))return;
                if(t.getDatabase()!=null&&t.getDatabase().getDatabaseName()!=null)throw new IllegalArgumentException("Three-part and remote relation names require one-time review");
                if(schema.isEmpty())schema=scope.path("schema").asText();
                String database=Set.of("postgresql","oracle").contains(vendor)?scope.path("database").asText():schema;
                if(relations.size()>=256)throw new IllegalArgumentException("At most 256 referenced relations are supported");
                relations.add(new Relation(database,schema,object));
                String quote=Character.toString(Set.of("postgresql","oracle").contains(vendor)?34:96);t.setSchemaName(quote+schema.replace(quote,quote+quote)+quote);
            }
            if(value instanceof Function f){
                String name=f.getName();String canonical=name.toUpperCase(Locale.ROOT);
                if(canonical.startsWith("PG_CATALOG.")&&vendor.equals("postgresql"))canonical=canonical.substring(11);
                if(!FUNCTIONS.contains(canonical)||vendor.equals("oracle")&&canonical.equals("CHAR_LENGTH"))throw new IllegalArgumentException("Unverified function requires one-time review: "+name);
                int arguments=f.getParameters()==null?0:f.getParameters().size();
                if(canonical.equals("COALESCE")?arguments<1:canonical.equals("NULLIF")?arguments!=2:arguments!=1)
                    throw new IllegalArgumentException("Unverified function signature requires one-time review: "+name);
                functions=true;
                if(vendor.equals("postgresql")&&!Set.of("COALESCE","NULLIF").contains(canonical))f.setName(List.of("pg_catalog",canonical.toLowerCase(Locale.ROOT)));
            }
            for(Class<?> c=type;c!=null&&c.getPackageName().startsWith("net.sf.jsqlparser");c=c.getSuperclass()){
                if(c.getSimpleName().equals("ASTNodeAccessImpl"))break;
                for(var field:c.getDeclaredFields()){
                    if(Modifier.isStatic(field.getModifiers())||field.isSynthetic()||field.getName().equals("withItemsList"))continue;
                    field.setAccessible(true);walk(field.get(value),ctes,depth+1);
                }
            }
        }
    }
}
