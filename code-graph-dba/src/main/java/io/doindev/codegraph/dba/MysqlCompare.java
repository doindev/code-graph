package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Native table transitions; never reconstruct a table from the editor's subset of fields. */
final class MysqlCompare {
    static boolean mode(JsonNode object){return MysqlScript.Mode.parse(str(object,"mysqlSqlMode")).noBackslashEscapes();}
    static String options(String options,boolean mode){StringBuilder out=new StringBuilder();int start=0;var tokens=MysqlTableDefinition.tokens(options,mode);for(int i=0;i<tokens.size();i++)if(tokens.get(i).word().equals("AUTO_INCREMENT")&&i+2<tokens.size()&&tokens.get(i+1).text().equals("=")&&tokens.get(i+2).text().matches("[0-9]+")){out.append(options,start,tokens.get(i).start());start=tokens.get(i+2).end();i+=2;}return out.append(options.substring(start)).toString().strip();}
    static String nextAutoIncrement(String ddl,boolean mode){var table=MysqlTableDefinition.parse(ddl,mode);var tokens=MysqlTableDefinition.tokens(table.options(),mode);for(int i=0;i+2<tokens.size();i++)if(tokens.get(i).word().equals("AUTO_INCREMENT")&&tokens.get(i+1).text().equals("=")&&tokens.get(i+2).text().matches("[0-9]+"))return tokens.get(i+2).text();return "1";}
    static String create(CompareSql.Plan plan,JsonNode source){var table=MysqlTableDefinition.parse(str(source,"nativeDdl"),mode(source));List<String> clauses=table.clauses().stream().filter(c->!foreign(c)).toList();return "CREATE TABLE "+plan.target(source)+" (\n"+plan.mapped(String.join(",\n",clauses))+"\n) "+options(table.options(),mode(source));}
    static boolean foreign(String clause){return clause.stripLeading().matches("(?is)(CONSTRAINT\\s+.+?\\s+)?FOREIGN\\s+KEY\\b.*");}
    static String constraintKey(String clause,boolean mode){var tokens=MysqlTableDefinition.tokens(clause,mode);if(tokens.getFirst().word().equals("PRIMARY"))return "PRIMARY";for(int i=0;i<tokens.size()-1;i++)if(Set.of("CONSTRAINT","KEY","INDEX").contains(tokens.get(i).word())){var n=tokens.get(i+1);if(n.quoted())return n.text().substring(1,n.text().length()-1).replace("``","`").replace("\"\"","\"");}throw new IllegalArgumentException("Unnamed native index/constraint requires explicit SQL review");}
    static String drop(String clause,String engine,boolean mode){String name=constraintKey(clause,mode);if(name.equals("PRIMARY"))return "DROP PRIMARY KEY";boolean check=MysqlTableDefinition.tokens(clause,mode).stream().anyMatch(t->t.depth()==0&&t.word().equals("CHECK"));return "DROP "+(check?engine.equals("mariadb")?"CONSTRAINT ":"CHECK ":"INDEX ")+MysqlDialect.quote(name);}
    static void alter(CompareSql.Plan plan,CompareSql.Choice choice){
        var source=choice.source();var destination=choice.destination();boolean mode=mode(source);if(mode!=mode(destination))throw new IllegalArgumentException("Source and destination NO_BACKSLASH_ESCAPES modes differ; align modes before generating native table changes");
        var left=MysqlTableDefinition.parse(plan.mapped(str(source,"nativeDdl")),mode);var right=MysqlTableDefinition.parse(str(destination,"nativeDdl"),mode);var after=MysqlTableDefinition.columns(left);var before=MysqlTableDefinition.columns(right);List<String> changes=new ArrayList<>();
        var sourceClauses=new LinkedHashMap<String,String>();var destClauses=new LinkedHashMap<String,String>();for(String clause:left.clauses())if(MysqlTableDefinition.columnName(clause)==null&&!foreign(clause))sourceClauses.put(constraintKey(clause,mode),clause);for(String clause:right.clauses())if(MysqlTableDefinition.columnName(clause)==null&&!foreign(clause))destClauses.put(constraintKey(clause,mode),clause);
        for(var entry:destClauses.entrySet())if(!Objects.equals(entry.getValue(),sourceClauses.get(entry.getKey()))){CompareSql.requireDestructive(plan,"Replace or remove index/constraint "+entry.getKey());changes.add(drop(entry.getValue(),plan.engine,mode));}
        for(var entry:before.entrySet())if(!after.containsKey(entry.getKey())){CompareSql.requireDestructive(plan,"Remove column "+entry.getKey());changes.add("DROP COLUMN "+MysqlDialect.quote(entry.getKey()));}
        if(!destination.path("uninspectedPrograms").asText().isEmpty())for(var entry:before.entrySet())if(!after.containsKey(entry.getKey())||!MysqlTableDefinition.attribute(entry.getValue(),"type",mode).equals(MysqlTableDefinition.attribute(after.get(entry.getKey()),"type",mode)))throw new IllegalArgumentException("Column removal/type changes require including stored programs in comparison so their incoming dependencies can be reviewed");
        String previous=null;for(var entry:after.entrySet()){
            String old=before.get(entry.getKey()),definition=entry.getValue(),position=previous==null?" FIRST":" AFTER "+MysqlDialect.quote(previous);boolean orderChanged=old!=null&&new ArrayList<>(before.keySet()).indexOf(entry.getKey())!=new ArrayList<>(after.keySet()).indexOf(entry.getKey());
            if(old==null){String nullable=MysqlTableDefinition.attribute(definition,"NULL",mode),defaultValue=MysqlTableDefinition.attribute(definition,"DEFAULT",mode);if(nullable.equals("NOT NULL")&&defaultValue.isEmpty()&&MysqlTableDefinition.attribute(definition,"GENERATED",mode).isEmpty())throw new IllegalArgumentException("Required new column "+entry.getKey()+" needs an explicit backfill or default before comparison");changes.add("ADD COLUMN "+definition+position);}
            else if(!old.equals(definition)||orderChanged){CompareSql.requireDestructive(plan,"Alter column "+entry.getKey());changes.add("MODIFY COLUMN "+definition+position);}
            previous=entry.getKey();
        }
        for(var entry:sourceClauses.entrySet())if(!Objects.equals(entry.getValue(),destClauses.get(entry.getKey())))changes.add("ADD "+entry.getValue());
        String leftOptions=options(left.options(),mode),rightOptions=options(right.options(),mode);if(!leftOptions.equals(rightOptions)){
            if(leftOptions.toUpperCase(Locale.ROOT).contains("PARTITION")||rightOptions.toUpperCase(Locale.ROOT).contains("PARTITION"))throw new IllegalArgumentException("Changing partition layout requires a partition migration with explicit data placement");
            CompareSql.requireDestructive(plan,"Change native table storage/charset options");changes.add(leftOptions);
        }
        if(!changes.isEmpty()){
            if(!destination.path("triggers").isEmpty())throw new IllegalArgumentException("Review trigger behavior before altering "+str(source,"name"));
            plan.before.add("ALTER TABLE "+plan.target(source)+"\n  "+String.join(",\n  ",changes));plan.warnings.add(str(source,"name")+": ALTER may rebuild and lock the table; the script does not force an online algorithm.");
        }
    }
    static void autoIncrement(CompareSql.Plan plan,CompareSql.Choice choice){
        var source=choice.source().path("state");var destination=choice.destination()==null?source:choice.destination().path("state");var next=CompareSql.integer(str(source,"next"));if(destination.hasNonNull("next"))next=next.max(CompareSql.integer(str(destination,"next")));var increment=CompareSql.integer(str(destination,"increment"));var offset=CompareSql.integer(str(destination,"offset"));if(increment.signum()<=0||offset.signum()<=0)throw new IllegalArgumentException("AUTO_INCREMENT session settings are unavailable");if(offset.compareTo(increment)>0)offset=java.math.BigInteger.ONE;
        if(next.compareTo(offset)>0)next=offset.add(next.subtract(offset).add(increment).subtract(java.math.BigInteger.ONE).divide(increment).multiply(increment));else next=offset;
        plan.state.add("ALTER TABLE "+plan.target(choice.source())+" AUTO_INCREMENT = "+next);plan.warnings.add(str(choice.source(),"name")+": AUTO_INCREMENT advances using captured destination increment/offset. The engine also enforces existing row maxima; other sessions may use different increment settings. No source value was consumed.");
    }
    private MysqlCompare(){}
}
