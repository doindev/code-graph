package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Connection;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Native stored-program formatting, quoting modes and explicit definer policy. */
final class MysqlProgram {
    static String identifier(MysqlTableDefinition.Token token){String text=token.text();if(token.quoted()&&(text.charAt(0)=='`'||text.charAt(0)=='"'||text.charAt(0)=='\'')){char q=text.charAt(0);return text.substring(1,text.length()-1).replace(""+q+q,""+q);}return text;}
    static String remap(String sql,Map<String,String> schemas,MysqlScript.Mode mode){
        if(schemas.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue())))return sql;
        var tokens=MysqlTableDefinition.tokens(sql,mode);StringBuilder out=new StringBuilder();int start=0;
        for(int i=0;i<tokens.size()-1;i++){var token=tokens.get(i);if(token.quoted()&&token.text().charAt(0)!='`'&&!(token.text().charAt(0)=='"'&&mode.ansiQuotes()))continue;String mapped=schemas.get(identifier(token));if(mapped==null||!tokens.get(i+1).text().equals("."))continue;out.append(sql,start,token.start()).append(MysqlDialect.quote(mapped));start=token.end();}
        return out.append(sql.substring(start)).toString();
    }
    static String modeLiteral(String mode){if(!mode.matches("[A-Za-z0-9_,]*"))throw new IllegalArgumentException("Unexpected native SQL mode");return "'"+mode+"'";}
    static void capture(QueryJobs.Job job,Connection c,Inventory inv,ObjectNode object)throws Exception{
        String kind=str(object,"kind");if(!Set.of("functions","procedures","triggers","events","views").contains(kind))return;
        String type=switch(kind){case "functions"->"FUNCTION";case "procedures"->"PROCEDURE";case "triggers"->"TRIGGER";case "events"->"EVENT";default->"VIEW";};var rows=query(job,c,"SHOW CREATE "+type+" "+CompareSql.qualified(inv.engine,str(object,"schema"),str(object,"name")));if(rows.size()!=1)throw new IllegalArgumentException("Native "+type+" definition unavailable");var properties=rows.get(0);String ddl="";for(var fields=properties.fields();fields.hasNext();){var field=fields.next();if(field.getKey().startsWith("create ")||field.getKey().equals("sql original statement"))ddl=field.getValue().asText("");}if(ddl.isBlank())throw new IllegalArgumentException("Native definition is hidden; check SHOW CREATE privileges");
        ObjectNode nativeInfo=Profiles.JSON.createObjectNode().put("sqlMode",properties.path("sql_mode").asText(inv.mysqlSqlMode)).put("charset",properties.path("character_set_client").asText()).put("collation",properties.path("collation_connection").asText()).put("databaseCollation",properties.path("database collation").asText()).put("timeZone",kind.equals("events")?properties.path("time_zone").asText():"");object.set("mysqlProgram",nativeInfo);object.put("ddl",ddl).put("supported",true).put("reason","");
        if(!kind.equals("views")){var tokens=MysqlTableDefinition.tokens(ddl,MysqlScript.Mode.parse(nativeInfo.path("sqlMode").asText()));if(tokens.stream().anyMatch(token->Set.of("PREPARE","EXECUTE").contains(token.word())))object.put("supported",false).put("reason","Dynamic SQL dependencies cannot be resolved for native stored-program comparison");}
    }
    static String definer(CompareSql.Plan plan,JsonNode source,String ddl){
        String policy=plan.definerPolicy;var info=source.path("mysqlProgram");var tokens=MysqlTableDefinition.tokens(ddl,MysqlScript.Mode.parse(info.path("sqlMode").asText(plan.source.mysqlSqlMode)));
        for(int i=0;i+4<tokens.size();i++)if(tokens.get(i).word().equals("DEFINER")&&tokens.get(i+1).text().equals("=")&&tokens.get(i+3).text().equals("@")){
            String identity=identifier(tokens.get(i+2))+"@"+identifier(tokens.get(i+4));if(policy.equals("preserve")&&!identity.equals(plan.destination.mysqlPrincipal))throw new IllegalArgumentException("Source definer "+identity+" differs from the destination login. Select the explicit destination-login definer policy or use that account as the destination connection");
            if(policy.equals("destination")){plan.warnings.add(str(source,"name")+": source definer "+identity+" is explicitly mapped to destination login "+plan.destination.mysqlPrincipal);return ddl.substring(0,tokens.get(i).start())+"DEFINER=CURRENT_USER"+ddl.substring(tokens.get(i+4).end());}return ddl;
        }
        throw new IllegalArgumentException("Native definer is unavailable; generation cannot infer an execution identity");
    }
    static String qualifiedDefinition(CompareSql.Plan plan,JsonNode source,String ddl,String type){var mode=MysqlScript.Mode.parse(source.path("mysqlProgram").path("sqlMode").asText(plan.source.mysqlSqlMode));var tokens=MysqlTableDefinition.tokens(ddl,mode);for(int i=0;i<tokens.size()-1;i++)if(tokens.get(i).word().equals(type)){var name=tokens.get(i+1);int end=name.end();if(i+3<tokens.size()&&tokens.get(i+2).text().equals("."))end=tokens.get(i+3).end();String definition=ddl.substring(0,name.start())+plan.target(source)+ddl.substring(end);return remap(definition,plan.from.allSchemas()?Map.of():Map.of(plan.from.schema(),plan.to.schema()),mode);}throw new IllegalArgumentException("Unrecognized native "+type+" definition");}
    static List<String> definition(CompareSql.Plan plan,CompareSql.Choice choice){var source=choice.source();String kind=str(source,"kind"),type=switch(kind){case "functions"->"FUNCTION";case "procedures"->"PROCEDURE";case "triggers"->"TRIGGER";case "events"->"EVENT";default->"VIEW";};var nativeInfo=source.path("mysqlProgram");String sqlMode=nativeInfo.path("sqlMode").asText(plan.source.mysqlSqlMode),charset=nativeInfo.path("charset").asText(),collation=nativeInfo.path("collation").asText();if(!charset.matches("[A-Za-z0-9_]+")||!collation.matches("[A-Za-z0-9_]+"))throw new IllegalArgumentException("Native stored-program charset/collation is unavailable");String dbCollation=nativeInfo.path("databaseCollation").asText();if(!dbCollation.isEmpty()&&!dbCollation.equals(plan.destination.mysqlDatabaseCollation))throw new IllegalArgumentException("Routine database collation differs; align destination database defaults before generation");
        String ddl=qualifiedDefinition(plan,source,definer(plan,source,str(source,"ddl")),type);MysqlScript.single(ddl,16<<20,MysqlScript.Mode.parse(sqlMode));List<String> commands=new ArrayList<>();if(choice.destination()!=null){if(!choice.destination().path("uninspectedPrograms").asText().isEmpty())throw new IllegalArgumentException("Replacing this native object requires including stored programs to review incoming dependencies");CompareSql.requireDestructive(plan,"Replace "+type+" "+str(source,"name"));commands.add("DROP "+type+" "+plan.target(source));}commands.add("SET SESSION sql_mode="+modeLiteral(sqlMode));commands.add("SET NAMES "+charset+" COLLATE "+collation);String zone=nativeInfo.path("timeZone").asText();if(kind.equals("events")){if(!zone.matches("[A-Za-z0-9_/:+.-]{1,64}"))throw new IllegalArgumentException("Native event time zone is unavailable");commands.add("SET SESSION time_zone='"+zone+"'");}commands.add(ddl);if(kind.equals("events"))commands.add("SET SESSION time_zone='+00:00'");commands.add("SET SESSION sql_mode="+modeLiteral(plan.source.mysqlSqlMode));return commands;
    }
    static void emit(java.io.Writer writer,String sql)throws Exception{String delimiter="__cg_end__";while(sql.contains(delimiter))delimiter+="x";if(delimiter.length()>16)throw new IllegalArgumentException("Cannot choose a bounded stored-program delimiter");writer.write("DELIMITER "+delimiter+"\n"+sql+"\n"+delimiter+"\nDELIMITER ;\n\n");}
    private MysqlProgram(){}
}
