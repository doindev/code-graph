package io.doindev.codegraph.index.mapping;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.util.TablesNamesFinder;
import java.util.*;

/** Static SQL AST evidence. It is never used to authorize or execute SQL. */
final class SqlMappings {
    static void extract(MappingEvidence evidence,String sql,int offset,String framework){
        if(sql.length()>32768){evidence.uncertain(offset,framework,"SQL mapping input exceeds 32 KiB");return;}
        if(sql.contains("${")||sql.contains("#{")){evidence.uncertain(offset,framework,"Dynamic SQL substitutions require runtime resolution");return;}
        try{
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(100));
            if(statements.size()>32){evidence.uncertain(offset,framework,"SQL mapping statement limit exceeded");return;}
            for(var statement:statements){
                if(statement instanceof CreateTable create){
                    add(evidence,offset,framework,create.getTable(),"","declares",Map.of());
                    if(create.getColumnDefinitions()!=null)for(var column:create.getColumnDefinitions())
                        add(evidence,offset,framework,create.getTable(),unquote(column.getColumnName()),"declares_column",Map.of("databaseType",column.getColDataType().toString()));
                }
                new TablesNamesFinder<Void>(){
                    final Deque<Map<String,Table>> scopes=new ArrayDeque<>();
                    final Deque<Boolean> ambiguous=new ArrayDeque<>();
                    int depth;
                    @Override public <S> Void visit(PlainSelect select,S context){
                        if(++depth>32)throw new IllegalArgumentException("SQL nesting limit");
                        var aliases=new HashMap<String,Table>();boolean uncertain=select.getWithItemsList()!=null;
                        uncertain|=!register(select.getFromItem(),aliases);
                        if(select.getJoins()!=null)for(var join:select.getJoins())uncertain|=!register(join.getRightItem(),aliases);
                        scopes.push(aliases);ambiguous.push(uncertain);
                        if(uncertain)evidence.uncertain(offset,framework,"Derived/CTE relations require additional lineage resolution");
                        try{return super.visit(select,context);}finally{scopes.pop();ambiguous.pop();depth--;}
                    }
                    @Override public <S> Void visit(Table table,S context){
                        if(ambiguous.isEmpty()||!ambiguous.peek())add(evidence,offset,framework,table,"","references",Map.of("alias",table.getAlias()==null?"":unquote(table.getAlias().getName())));
                        return super.visit(table,context);
                    }
                    @Override public <S> Void visit(Column column,S context){
                        if(scopes.isEmpty()||ambiguous.peek())return null;
                        Map<String,Table> scope=scopes.peek();String qualifier=column.getTable()==null?"":unquote(column.getTable().getName());
                        Table table=scope.get(qualifier);
                        if(qualifier.isBlank()){
                            var unique=new HashSet<>(scope.values());if(unique.size()==1)table=unique.iterator().next();
                        }
                        if(table==null)evidence.add(offset,framework,"","",unquote(column.getColumnName()),"unresolved_column",0.3,Map.of("uncertainty","Ambiguous column or outer/derived scope; catalog validation required"));
                        else add(evidence,offset,framework,table,unquote(column.getColumnName()),"references_column",Map.of("alias",qualifier));
                        return null;
                    }
                }.getTables(statement);
            }
        }catch(Exception|StackOverflowError unsupported){evidence.uncertain(offset,framework,"SQL could not be structurally mapped within parser limits; inspect source");}
    }
    private static boolean register(FromItem from,Map<String,Table> aliases){
        if(from==null)return true;if(!(from instanceof Table table))return false;
        aliases.put(table.getAlias()==null?unquote(table.getName()):unquote(table.getAlias().getName()),table);return true;
    }
    private static void add(MappingEvidence evidence,int offset,String framework,Table table,String column,String relation,Map<String,String> extra){
        var attrs=new HashMap<>(extra);attrs.put("database",Objects.toString(table.getDatabaseName(),""));attrs.put("locationPrecision","sql_expression");
        attrs.put("nameResolution",table.getSchemaName()==null?"schema_unspecified":"explicit_schema");
        evidence.add(offset,framework,unquote(table.getSchemaName()),unquote(table.getName()),column,relation,.9,attrs);
    }
    static String unquote(String name){
        if(name==null)return "";
        if(name.length()>1){char quote=name.charAt(0),end=name.charAt(name.length()-1);if((quote=='"'||quote=='`')&&end==quote)return name.substring(1,name.length()-1).replace(""+quote+quote,""+quote);if(quote=='['&&end==']')return name.substring(1,name.length()-1).replace("]]","]");}
        return name;
    }
}
