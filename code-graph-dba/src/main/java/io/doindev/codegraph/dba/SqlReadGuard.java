package io.doindev.codegraph.dba;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.*;

/** Deliberately narrow read grammar until the separately gated write-policy engine is validated. */
final class SqlReadGuard {
    static String validate(String sql) {
        return validate(sql,(schema,name)->{});
    }
    static String validate(String sql,java.util.function.BiConsumer<String,String> authorize) {
        return validate(sql,authorize,false);
    }
    /** Local human sessions may invoke functions; agent policies remain deliberately narrower. */
    static String validateBrowser(String sql) {
        return validate(sql,(schema,name)->{},true);
    }
    private static String validate(String sql,java.util.function.BiConsumer<String,String> authorize,boolean browser) {
        if(sql==null||sql.isBlank()||sql.length()>16_384)throw new IllegalArgumentException("SQL must contain 1..16384 characters");
        try {
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(500));
            if(statements.size()!=1)throw new IllegalArgumentException("Exactly one statement is required");
            Statement statement=statements.get(0);
            if(!(statement instanceof PlainSelect)&&!(statement instanceof Values))throw new IllegalArgumentException("Only a single structurally restricted SELECT or VALUES statement is enabled");
            inspect(statement,Collections.newSetFromMap(new IdentityHashMap<>()),0,browser);
            new TablesNamesFinder<Void>() {
                @Override public <S> Void visit(PlainSelect select,S context) {
                    if(select.getIntoTables()!=null || select.getIntoTempTable()!=null || select.getWithItemsList()!=null
                            || select.getForMode()!=null || select.getForUpdateTable()!=null)
                        throw new IllegalArgumentException("SELECT INTO, CTEs and row locking are not enabled");
                    return super.visit(select,context);
                }
                @Override public <S> Void visit(Function function,S context) {
                    // Calling user-defined functions may write even inside a SELECT.
                    if(!browser)throw new IllegalArgumentException("Function calls require the future object/function policy engine");
                    return super.visit(function,context);
                }
                @Override public <S> Void visit(CastExpression expression,S context) {
                    throw new IllegalArgumentException("Explicit casts are not enabled in restricted read mode");
                }
                @Override public <S> Void visit(Table table,S context) {
                    if(table.getDatabase()!=null && table.getDatabase().getDatabaseName()!=null)
                        throw new IllegalArgumentException("Cross-database names are not enabled");
                    authorize.accept(identifier(table.getSchemaName()),identifier(table.getName()));
                    return super.visit(table,context);
                }
            }.getTables(statement);
            return sql;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Unsupported or invalid SQL");}
    }
    private static String identifier(String value){if(value==null)return null;if(value.startsWith("\"")&&value.endsWith("\""))return value.substring(1,value.length()-1).replace("\"\"","\"");return value.toLowerCase(Locale.ROOT);}

    // TablesNamesFinder deliberately skips some clauses. Inspect every domain AST field as
    // a positive grammar gate, excluding only the parser's token/back-reference base class.
    // New parser constructs must be reviewed and tested before being added here.
    private static final Set<String> READ_NODES=Set.of("PlainSelect","Values","SelectItem","ParenthesedSelect",
            "Table","Column","Database","Alias","LongValue","DoubleValue","StringValue","NullValue","BooleanValue",
            "JdbcParameter","AllColumns","AllTableColumns","Join","OrderByElement","Limit","Offset","Distinct",
            "EqualsTo","NotEqualsTo","GreaterThan","GreaterThanEquals","MinorThan","MinorThanEquals",
            "AndExpression","OrExpression","NotExpression","Between","InExpression","IsNullExpression",
            "LikeExpression","Addition","Subtraction","Multiplication","Division","Modulo","SignedExpression",
            "ExistsExpression","ParenthesedExpressionList","ExpressionList","DateValue","TimeValue","TimestampValue");
    private static void inspect(Object value,Set<Object> seen,int depth,boolean browser)throws ReflectiveOperationException {
        if(value==null||value instanceof String||value instanceof Number||value instanceof Boolean||value instanceof Enum<?>)return;
        if(depth>128||seen.size()>10000)throw new IllegalArgumentException("SELECT expression complexity limit exceeded");
        if(!seen.add(value))return;
        if(value instanceof Map<?,?> map){for(Object item:map.values())inspect(item,seen,depth+1,browser);return;}
        if(value.getClass().isArray()){for(int i=0;i<java.lang.reflect.Array.getLength(value);i++)inspect(java.lang.reflect.Array.get(value,i),seen,depth+1,browser);return;}
        if(value instanceof Iterable<?> list){for(Object item:list)inspect(item,seen,depth+1,browser);return;}
        Class<?> type=value.getClass();
        if(!type.getPackageName().startsWith("net.sf.jsqlparser"))return;
        if(!READ_NODES.contains(type.getSimpleName())&&!(browser&&type==Function.class))throw new IllegalArgumentException("Unsupported restricted SELECT construct: "+type.getSimpleName());
        for(Class<?> c=type;c!=null&&c.getPackageName().startsWith("net.sf.jsqlparser");c=c.getSuperclass()){
            if(c.getSimpleName().equals("ASTNodeAccessImpl"))break;
            for(var field:c.getDeclaredFields()){
                if(java.lang.reflect.Modifier.isStatic(field.getModifiers())||field.isSynthetic())continue;
                field.setAccessible(true);inspect(field.get(value),seen,depth+1,browser);
            }
        }
    }
}
