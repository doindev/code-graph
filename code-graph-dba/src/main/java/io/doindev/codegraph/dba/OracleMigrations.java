package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Oracle migration boundaries supplement exact review; they never authorize execution. */
final class OracleMigrations {
    private OracleMigrations(){}
    static void snapshot(JsonNode value){
        if(value.path("version").path("major").asInt()<19||!value.has("resolvedTarget"))throw new IllegalArgumentException("Oracle migrations require an observed Oracle 19c+ PDB and owner");
        if(value.path("truncated").asBoolean()||!value.path("coverage").path("inventoryComplete").asBoolean())throw new IllegalArgumentException("Oracle migration capture is incomplete; resolve reported metadata limits or privileges before preparing a plan");
        for(JsonNode object:value.path("objects"))if(!OracleMetadata.ddlType(object.path("kind").asText()).isEmpty()&&!object.path("definitionSource").asText().equals("native"))throw new IllegalArgumentException("Native Oracle definition is unavailable for "+object.path("name").asText());
    }
    static void distinct(JsonNode source,JsonNode destination){
        if(source.path("databaseUniqueName").equals(destination.path("databaseUniqueName"))&&source.path("containerId").equals(destination.path("containerId"))&&source.path("owner").equals(destination.path("owner")))throw new IllegalArgumentException("Oracle rehearsal must use a different resolved database/container or owner; another saved connection to the same schema is not a disposable target");
    }
    static void execution(List<String> statements,String owner,boolean rehearsal){
        for(String sql:statements){var first=OracleCompareSql.tokens(sql).stream().filter(t->!t.text().isBlank()&&!t.text().startsWith("--")&&!t.text().startsWith("/*")).findFirst();if(rehearsal&&first.isPresent()&&first.get().text().equalsIgnoreCase("INSERT"))fixture(sql,owner);else statement(sql,owner);}
    }
    static void fixture(String sql,String owner){
        var tokens=OracleCompareSql.tokens(sql).stream().filter(t->!t.text().isBlank()&&!t.text().startsWith("--")&&!t.text().startsWith("/*")).toList();
        if(tokens.size()<4||!word(tokens,0,"INSERT")||!word(tokens,1,"INTO")||!tokens.get(2).identifier())throw new IllegalArgumentException("Use a single-target INSERT INTO for synthetic Oracle fixtures");
        if(tokens.get(3).text().equals(".")&&!tokens.get(2).name().equals(owner))throw new IllegalArgumentException("Synthetic Oracle fixture owner differs from the reviewed disposable schema");
        if(tokens.stream().anyMatch(t->t.text().equals("@"))||OracleCompareSql.dynamic(sql))throw new IllegalArgumentException("Synthetic Oracle fixtures cannot use dynamic SQL or database links");
        int values=-1;for(int i=3;i<tokens.size();i++)if(word(tokens,i,"VALUES")){values=i;break;}
        if(values<0)throw new IllegalArgumentException("Synthetic Oracle fixtures must use explicit VALUES, without reading application records");
        var allowed=Set.of("NULL","DATE","TIMESTAMP","INTERVAL","DAY","TO","SECOND","YEAR","MONTH","TO_DATE","TO_TIMESTAMP","TO_TIMESTAMP_TZ","HEXTORAW","EMPTY_CLOB","EMPTY_BLOB","UNISTR","N");
        for(int i=values+1;i<tokens.size();i++)if(tokens.get(i).identifier()&&!allowed.contains(tokens.get(i).name()))throw new IllegalArgumentException("Synthetic Oracle fixture values require literals or supported Oracle literal conversions");
    }
    static void compilation(JsonNode before,JsonNode after){
        var prior=new HashMap<String,String>();for(JsonNode object:before.path("objects"))prior.put(object.path("id").asText(),object.path("objectHash").asText());
        for(JsonNode object:after.path("objects"))if(object.path("status").asText().equals("INVALID")&&!object.path("objectHash").asText().equals(prior.get(object.path("id").asText())))throw new IllegalArgumentException("Oracle object "+object.path("name").asText()+" is invalid after migration. DDL may already be committed; inspect compilation errors before continuing.");
    }
    static void statements(List<String> statements,String owner){
        OracleDialect.identifier(owner);for(String sql:statements)statement(sql,owner);
    }
    private static void statement(String sql,String owner){
        if(OracleCompareSql.dynamic(sql))throw new IllegalArgumentException("Dynamic SQL requires separate native review; its target cannot be verified for migration/rehearsal");
        var tokens=OracleCompareSql.tokens(sql).stream().filter(t->!t.text().isBlank()&&!t.text().startsWith("--")&&!t.text().startsWith("/*")).toList();
        if(tokens.stream().anyMatch(t->t.text().equals("@")))throw new IllegalArgumentException("Database links are external migration dependencies and require separate review");
        if(tokens.size()<3)throw new IllegalArgumentException("Incomplete Oracle migration statement");
        int at=1;String verb=tokens.get(0).name();
        if(verb.equals("CREATE")){
            if(word(tokens,at,"OR")){if(!word(tokens,at+1,"REPLACE"))throw new IllegalArgumentException("Expected CREATE OR REPLACE");at+=2;}
            if(word(tokens,at,"EDITIONABLE")||word(tokens,at,"NONEDITIONABLE")||word(tokens,at,"UNIQUE")||word(tokens,at,"BITMAP"))at++;
        }else if(verb.equals("COMMENT")){
            if(!word(tokens,at,"ON"))throw new IllegalArgumentException("Expected COMMENT ON");at++;
        }else if(!Set.of("ALTER","DROP").contains(verb))throw new IllegalArgumentException("Use native Oracle CREATE, ALTER, DROP or COMMENT DDL");
        String kind=at<tokens.size()?tokens.get(at++).name():"";
        if(kind.equals("MATERIALIZED")){if(!word(tokens,at,"VIEW"))throw new IllegalArgumentException("Expected MATERIALIZED VIEW");kind="MATERIALIZED VIEW";at++;}
        if(!Set.of("TABLE","VIEW","MATERIALIZED VIEW","INDEX","SEQUENCE","FUNCTION","PROCEDURE","PACKAGE","TYPE","TRIGGER","SYNONYM","COLUMN").contains(kind))throw new IllegalArgumentException("This Oracle migration object type requires a separate administration review");
        if((kind.equals("PACKAGE")||kind.equals("TYPE"))&&word(tokens,at,"BODY"))at++;
        if(at>=tokens.size()||!tokens.get(at).identifier())throw new IllegalArgumentException("Expected a literal Oracle object identifier");
        String first=tokens.get(at).name();OracleDialect.identifier(first);
        if(at+1<tokens.size()&&tokens.get(at+1).text().equals(".")&&!kind.equals("COLUMN")&&!first.equals(owner))throw new IllegalArgumentException("Oracle migration object owner differs from the reviewed schema");
        if(kind.equals("COLUMN")&&at+3<tokens.size()&&tokens.get(at+3).text().equals(".")&&!first.equals(owner))throw new IllegalArgumentException("Oracle column comment owner differs from the reviewed schema");
    }
    private static boolean word(List<OracleCompareSql.Token> tokens,int at,String value){return at<tokens.size()&&tokens.get(at).text().equalsIgnoreCase(value);}
    static List<String> remap(List<String> statements,String from,String to){
        statements(statements,from);var mapped=statements.stream().map(sql->OracleCompareSql.remap(sql,Map.of(from,to))).toList();statements(mapped,to);return mapped;
    }
}
