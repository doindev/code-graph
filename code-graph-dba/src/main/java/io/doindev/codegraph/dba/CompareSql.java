package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.Writer;
import java.math.BigInteger;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Operation phases separate relation shells, expressions, rows, and validated foreign keys. */
final class CompareSql {
    static final Set<String> DATA_MODES=Set.of("insert","upsert","replace","mirror");
    record Choice(ObjectNode source,ObjectNode destination,JsonNode options){}
    static final class Plan {
        final Inventory source,destination;final Target from,to;final String engine;
        final LinkedHashMap<String,Choice> selected=new LinkedHashMap<>();
        final List<String> before=new ArrayList<>(),after=new ArrayList<>(),state=new ArrayList<>();
        final List<Choice> data=new ArrayList<>();final List<String> warnings=new ArrayList<>();
        final boolean destructive;final String dataMode,sequenceMode;final boolean sync;
        Plan(Inventory source,Inventory destination,Target from,Target to,JsonNode request){
            this.source=source;this.destination=destination;this.from=from;this.to=to;engine=source.engine;
            destructive=request.path("destructiveSchema").asBoolean();dataMode=request.path("dataMode").asText("upsert");sequenceMode=request.path("sequenceMode").asText("advance");sync=request.path("syncSequences").asBoolean();
            if(!dataMode.equals("none")&&!DATA_MODES.contains(dataMode)||!Set.of("advance","exact").contains(sequenceMode))throw new IllegalArgumentException("Invalid comparison mode");
        }
        String schema(String sourceSchema){return !from.allSchemas()&&from.schema().equals(sourceSchema)?to.schema():sourceSchema;}
        String target(JsonNode object){return qualified(engine,schema(str(object,"schema")),str(object,"name"));}
        String mapped(String sql){if(engine.equals("oracle"))return OracleCompareSql.remap(sql,from.allSchemas()?Map.of():Map.of(from.schema(),to.schema()));return remap(sql,from.allSchemas()?Map.of():Map.of(from.schema(),to.schema()));}
        String sourceKeyForDestination(JsonNode object){return key(from.allSchemas()?str(object,"schema"):from.schema(),str(object,"kind"),str(object,"name"));}
        String mode(Choice c){String m=c.options.path("dataMode").asText(dataMode);if(!DATA_MODES.contains(m))throw new IllegalArgumentException("Invalid data mode");return m;}
        String sequenceMode(Choice c){String m=c.options.path("sequenceMode").asText(sequenceMode);if(!Set.of("advance","exact").contains(m))throw new IllegalArgumentException("Invalid sequence mode");return m;}
        boolean sync(Choice c){return c.options.path("syncValues").asBoolean(sync);}
        void emit(Writer writer,List<String> statements)throws Exception{for(String sql:statements){writer.write(sql);writer.write(engine.equals("oracle")&&SqlScript.oracleBlock(sql)?"\n/\n\n":sql.stripTrailing().endsWith(";")?"\n\n":";\n\n");}}
        void header(Writer w)throws Exception{
            w.write("-- Database comparison: "+engine+" "+comment(source.version)+" -> "+comment(destination.version)+"\n-- Generated: "+java.time.Instant.now()+"\n-- Selected objects: "+selected.size()+"\n-- Generated from captured metadata and data. Review and execute with a SQL client.\n-- Destination: "+comment(to.database())+" / "+comment(to.allSchemas()?"all user schemas":to.schema())+"\n-- Destination-only objects are preserved. No statement has been executed by the comparer.\n-- Run against the intended destination with exclusive maintenance access; catalog and data changes after capture can invalidate this script.\n");
            if(dataMode.equals("none"))w.write("-- Structure only: table data is excluded. Sequence values are synchronized only when explicitly selected.\n");
            if(Set.of("mysql","mariadb","h2","oracle").contains(engine))w.write("-- This engine commits DDL independently. A failure may leave partially applied changes.\n");
            if(engine.equals("postgresql"))w.write("BEGIN;\nSET LOCAL check_function_bodies = false;\n");
            if(engine.equals("oracle")&&!data.isEmpty())w.write("-- Oracle data literals use the Gregorian calendar. Staging tables need CREATE TABLE privilege.\nALTER SESSION SET NLS_CALENDAR='GREGORIAN';\nALTER SESSION SET TIME_ZONE='+00:00';\n");
            w.write("\n");for(String warning:warnings)w.write("-- "+comment(warning)+"\n");emit(w,before);
        }
        void footer(Writer w)throws Exception{emit(w,after);emit(w,state);if(engine.equals("postgresql"))w.write("COMMIT;\n");}
    }
    static Plan prepare(Inventory source,Inventory destination,Target from,Target to,JsonNode request)throws Exception{
        if(!source.engine.equals(destination.engine))throw new IllegalArgumentException("Choose source and destination on the same database engine");
        if(!ENGINES.contains(source.engine))throw new IllegalArgumentException("Script generation is unavailable for this engine");
        Plan plan=new Plan(source,destination,from,to,request);
        if(!request.path("objects").isArray()||request.path("objects").isEmpty())throw new IllegalArgumentException("Select at least one object");
        Map<String,ObjectNode> ids=new HashMap<>();for(ObjectNode o:source.objects.values())ids.put(str(o,"id"),o);
        for(JsonNode requested:request.path("objects")){
            ObjectNode option=requested.deepCopy();if(plan.dataMode.equals("none"))option.put("includeData",false);
            ObjectNode object=ids.get(str(option,"id"));if(object==null)throw new IllegalArgumentException("Unknown selected object; refresh the comparison");
            if(!object.path("supported").asBoolean())throw new IllegalArgumentException(str(object,"name")+": "+str(object,"reason"));
            String k=key(str(object,"schema"),str(object,"kind"),str(object,"name"));
            ObjectNode dest=destination.objects.get(key(plan.schema(str(object,"schema")),str(object,"kind"),str(object,"name")));
            if(dest!=null&&!dest.path("supported").asBoolean()&&!dest.path("implicit").asBoolean())throw new IllegalArgumentException("Destination "+str(object,"name")+": "+str(dest,"reason"));
            if(plan.selected.put(k,new Choice(object,dest,option))!=null)throw new IllegalArgumentException("Duplicate selected object");
        }
        if(plan.engine.equals("oracle"))return OracleCompare.prepare(plan);
        requireDependencies(plan);List<Choice> ordered=order(plan);
        for(String schema:source.schemas)if(!destination.schemas.contains(plan.schema(schema))){
            if(!from.allSchemas())throw new IllegalArgumentException("Selected destination schema no longer exists");
            plan.before.add("CREATE SCHEMA "+q(plan.engine,plan.schema(schema)));
        }
        Set<String> touchedTables=new HashSet<>();
        for(var entry:plan.selected.entrySet())if(str(entry.getValue().source,"kind").equals("tables")){
            Choice choice=entry.getValue();if(choice.destination!=null&&!choice.destination.path("externalDependents").isEmpty()&&(!same(plan,choice.source,choice.destination)||choice.options.path("includeData").asBoolean()))throw new IllegalArgumentException(str(choice.source,"name")+": incoming foreign keys outside the comparison scope require comparing all related schemas");if(!same(plan,choice.source,choice.destination)||choice.options.path("includeData").asBoolean())touchedTables.add(entry.getKey());
        }
        for(ObjectNode table:destination.objects.values())if(str(table,"kind").equals("tables")){
            String sk=plan.sourceKeyForDestination(table);
            for(JsonNode fk:table.path("foreignKeys")){
                String ref=key(from.allSchemas()?str(fk,"schema"):from.schema(),"tables",str(fk,"table"));
                if(touchedTables.contains(sk)||touchedTables.contains(ref)){
                    String target=qualified(plan.engine,str(table,"schema"),str(table,"name"));
                    plan.before.add("ALTER TABLE "+target+(Set.of("mysql","mariadb").contains(plan.engine)?" DROP FOREIGN KEY ":" DROP CONSTRAINT ")+q(plan.engine,str(fk,"name")));
                    if(!plan.selected.containsKey(sk)||same(plan,plan.selected.get(sk).source,table))plan.after.add(foreignKey(plan,table,fk,false));
                }
            }
        }
        for(Choice choice:ordered)if(Set.of("types","domains").contains(str(choice.source,"kind")))objectDefinition(plan,choice);
        for(Choice choice:ordered)if(str(choice.source,"kind").equals("sequences")&&!choice.source.path("identity").asBoolean())objectDefinition(plan,choice);
        for(Choice choice:ordered)if(str(choice.source,"kind").equals("tables"))table(plan,choice);
        for(Choice choice:ordered)if(Set.of("functions","procedures").contains(str(choice.source,"kind")))objectDefinition(plan,choice);
        for(Choice choice:ordered)if(str(choice.source,"kind").equals("tables"))finishTable(plan,choice);
        for(Choice choice:ordered)if(Set.of("views","materialized_views","indexes").contains(str(choice.source,"kind")))objectDefinition(plan,choice);
        for(Choice choice:ordered){
            if(str(choice.source,"kind").equals("tables")&&choice.options.path("includeData").asBoolean()){
                if(!choice.source.path("dataSupported").asBoolean()||choice.destination!=null&&!choice.destination.path("dataSupported").asBoolean())throw new IllegalArgumentException(str(choice.source,"name")+": data comparison with triggers is unavailable");
                plan.mode(choice);plan.data.add(choice);
            }
            if(str(choice.source,"kind").equals("sequences")&&plan.sync(choice))sequence(plan,choice);
        }
        return plan;
    }
    static boolean same(Plan plan,ObjectNode a,ObjectNode b){if(plan.engine.equals("oracle"))return b!=null&&a.path("oracleChanges").isEmpty()&&a.path("supported").asBoolean();return b!=null&&semantic(plan,a,true).equals(semantic(plan,b,false));}
    static JsonNode semantic(Plan plan,ObjectNode object,boolean map){
        ObjectNode out=CompareCatalog.definition(object);out.remove(List.of("reason","supported","dataSupported","dataReason","implicit","triggers","rules","policies","externalDependents"));
        if(out.path("fields").isObject())((ObjectNode)out.path("fields")).remove(List.of("owner","comment"));if(map)mapNode(out,plan);canonicalNode(out);if(out.has("nativeDdl"))out.put("nativeDdl",normalizeMysql(str(out,"nativeDdl")));return out;
    }
    static JsonNode mappedNode(Plan plan,JsonNode node){JsonNode copy=node.deepCopy();mapNode(copy,plan);return copy;}
    static final Set<String> SQL_FIELDS=Set.of("ddl","nativeDdl","query","default","definition","type","table","ownedBy","expression","routineIdentity","body");
    static void canonicalNode(JsonNode node){
        if(node.isObject()){ObjectNode o=(ObjectNode)node;List<String> names=new ArrayList<>();o.fieldNames().forEachRemaining(names::add);for(String name:names){JsonNode value=o.get(name);if(value.isTextual()&&SQL_FIELDS.contains(name))o.put(name,canonical(value.asText()));else canonicalNode(value);}}
        else if(node.isArray())for(JsonNode child:node)canonicalNode(child);
    }
    /** Compare equivalent quoted lowercase identifiers without rewriting SQL literals. */
    static String canonical(String sql){
        StringBuilder out=new StringBuilder();for(int i=0;i<sql.length();){char c=sql.charAt(i);
            if(c=='\''||c=='"'||c==96){int end=quotedEnd(sql,i,c);String token=sql.substring(i,end);
                if(c=='"'&&token.substring(1,token.length()-1).matches("[a-z_][a-z0-9_$]*"))out.append(token,1,token.length()-1);
                else if(c=='\''&&sql.substring(end).matches("(?s)^\\s*::\\s*(?:pg_catalog\\.)?regclass\\b.*"))out.append("'").append(canonical(token.substring(1,token.length()-1).replace("''","'")).replace("'","''")).append("'");
                else out.append(token);i=end;
            }else if(c=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){int end=sql.indexOf('\n',i);if(end<0)end=sql.length();out.append(sql,i,end);i=end;}
            else{out.append(c);i++;}
        }return out.toString();
    }
    private static void mapNode(JsonNode node,Plan plan){
        if(node.isObject()){ObjectNode object=(ObjectNode)node;List<String> names=new ArrayList<>();object.fieldNames().forEachRemaining(names::add);
            for(String name:names){JsonNode value=object.get(name);
                if(value.isTextual()&&name.equals("schema"))object.put(name,plan.schema(value.asText()));
                else if(value.isTextual()&&SQL_FIELDS.contains(name))object.put(name,plan.mapped(value.asText()));
                else mapNode(value,plan);}
        }else if(node.isArray())for(JsonNode child:node)mapNode(child,plan);
    }
    static void requireDependencies(Plan plan){
        for(Choice choice:plan.selected.values()){
            for(JsonNode dependency:choice.source.path("dependencies")){
                ObjectNode object=plan.source.objects.get(dependency.asText());if(object==null||object.path("implicit").asBoolean())continue;
                ObjectNode dest=plan.destination.objects.get(key(plan.schema(str(object,"schema")),str(object,"kind"),str(object,"name")));
                if(!plan.selected.containsKey(dependency.asText())&&!same(plan,object,dest))throw new IllegalArgumentException(str(choice.source,"name")+" requires selecting "+str(object,"schema")+"."+str(object,"name"));
            }
            if(str(choice.source,"kind").equals("tables"))for(JsonNode fk:choice.source.path("foreignKeys")){
                String sourceKey=key(str(fk,"schema"),"tables",str(fk,"table")),destKey=key(plan.schema(str(fk,"schema")),"tables",str(fk,"table"));
                if(!plan.selected.containsKey(sourceKey)&&!plan.destination.objects.containsKey(destKey))throw new IllegalArgumentException("Foreign key "+str(fk,"name")+" requires selecting table "+str(fk,"table"));
            }
            if(choice.source.path("identity").asBoolean()){
                JsonNode owner=choice.source.path("ownership");String table=key(str(owner,"schema"),"tables",str(owner,"table"));
                if(!plan.selected.containsKey(table)&&choice.destination==null)throw new IllegalArgumentException("Select the table that owns identity sequence "+str(choice.source,"name"));
            }
        }
    }
    static List<Choice> order(Plan plan){List<Choice> ordered=new ArrayList<>();Set<String> done=new HashSet<>(),visiting=new HashSet<>();for(String k:plan.selected.keySet())visit(plan,k,done,visiting,ordered);return ordered;}
    static void visit(Plan plan,String key,Set<String> done,Set<String> visiting,List<Choice> ordered){
        if(done.contains(key))return;Choice choice=plan.selected.get(key);if(choice==null)return;
        if(!visiting.add(key))throw new IllegalArgumentException("Object dependency cycle requires a dedicated adapter: "+str(choice.source,"name"));
        for(JsonNode dep:choice.source.path("dependencies")){
            Choice other=plan.selected.get(dep.asText());
            if(str(choice.source,"kind").equals("tables")&&other!=null&&!Set.of("types","domains").contains(str(other.source,"kind")))continue;
            if(choice.source.path("identity").asBoolean())continue;
            visit(plan,dep.asText(),done,visiting,ordered);
        }
        visiting.remove(key);done.add(key);ordered.add(choice);
    }
    static String q(String engine,String name){
        if(name==null||name.isEmpty()||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid identifier");
        String quote=Set.of("mysql","mariadb").contains(engine)?String.valueOf((char)96):"\"";return quote+name.replace(quote,quote+quote)+quote;
    }
    static String qualified(String engine,String schema,String name){return schema.isEmpty()?q(engine,name):q(engine,schema)+"."+q(engine,name);}
    static String columns(String engine,JsonNode columns){List<String> names=new ArrayList<>();columns.forEach(n->names.add(q(engine,n.asText())));return String.join(", ",names);}
    static String comment(String text){return text.replace('\r',' ').replace('\n',' ');}
    static String column(Plan plan,JsonNode column,boolean defaultValue){
        String sql=q(plan.engine,str(column,"name"))+" "+plan.mapped(str(column,"type"));
        if(!str(column,"identity").isEmpty()){
            if(Set.of("mysql","mariadb").contains(plan.engine))sql+=" AUTO_INCREMENT";
            else{
                sql+=" GENERATED "+(str(column,"identity").equals("a")?"ALWAYS":"BY DEFAULT")+" AS IDENTITY";JsonNode options=column.path("identityOptions");
                if(options.isObject())sql+=" (START WITH "+integer(str(options,"start"))+" INCREMENT BY "+integer(str(options,"increment"))+" MINVALUE "+integer(str(options,"minimum"))+" MAXVALUE "+integer(str(options,"maximum"))+" CACHE "+integer(str(options,"cache"))+(truth(options.path("cycle"))?" CYCLE":" NO CYCLE")+(plan.engine.equals("postgresql")?" SEQUENCE NAME "+qualified(plan.engine,plan.schema(str(options,"schema")),str(options,"name")):"")+")";
            }
        }else if(defaultValue&&!str(column,"default").isEmpty())sql+=" DEFAULT "+plan.mapped(str(column,"default"));
        if(!column.path("nullable").asBoolean())sql+=" NOT NULL";return sql;
    }


    static void table(Plan plan,Choice choice){
        ObjectNode source=choice.source,dest=choice.destination;String target=plan.target(source);
        long primary=java.util.stream.StreamSupport.stream(source.path("constraints").spliterator(),false).filter(n->str(n,"kind").equals("p")).count();if(primary>1)throw new IllegalArgumentException("Select removal of the old primary key before adding its replacement: "+str(source,"name"));
        Set<String> available=byName(source.path("columns")).keySet();for(JsonNode index:source.path("indexes"))for(JsonNode col:index.path("columns"))if(!col.asText().isEmpty()&&!available.contains(col.asText()))throw new IllegalArgumentException("Select the index change for removed column "+col.asText());
        for(JsonNode fk:source.path("foreignKeys"))for(JsonNode col:fk.path("columns"))if(!available.contains(col.asText()))throw new IllegalArgumentException("Select the foreign-key change for removed column "+col.asText());
        if(dest!=null)for(JsonNode col:dest.path("columns"))if(!available.contains(str(col,"name")))for(JsonNode constraint:source.path("constraints")){
            String name=str(col,"name"),definition=str(constraint,"definition");if(definition.contains(q(plan.engine,name))||java.util.regex.Pattern.compile("(?i)(?<![\\w$])"+java.util.regex.Pattern.quote(name)+"(?![\\w$])").matcher(definition).find())throw new IllegalArgumentException("Select the constraint change for removed column "+name);
        }
        if(dest==null){
            if(source.has("nativeDdl")){plan.before.add(mysqlCreate(plan,source));return;}
            List<String> cols=new ArrayList<>();source.path("columns").forEach(c->cols.add(column(plan,c,false)));
            plan.before.add("CREATE TABLE "+target+" (\n  "+String.join(",\n  ",cols)+"\n)");return;
        }
        if(same(plan,source,dest))return;
        if(source.has("nativeDdl")){
            if(!normalizeMysql(plan.mapped(str(source,"nativeDdl"))).equals(normalizeMysql(str(dest,"nativeDdl"))))throw new IllegalArgumentException(str(source,"name")+": existing MySQL/MariaDB table definition changes are not supported by this adapter");
            return;
        }
        Map<String,JsonNode> before=byName(dest.path("columns")),after=byName(source.path("columns"));
        boolean shapeChanged=!columnShape(plan,source,true).equals(columnShape(plan,dest,false));
        if(shapeChanged){
            for(ObjectNode dependent:plan.destination.objects.values())for(JsonNode dependency:dependent.path("dependencies"))
                if(dependency.asText().equals(key(str(dest,"schema"),"tables",str(dest,"name")))&&!dependent.path("implicit").asBoolean()&&!str(dependent,"kind").equals("indexes"))
                    throw new IllegalArgumentException(str(source,"name")+": dependent "+str(dependent,"name")+" prevents a safe column change; align the dependency first");
            if(!dest.path("triggers").isEmpty())throw new IllegalArgumentException(str(source,"name")+": table triggers prevent safe column changes");
        }
        for(JsonNode constraint:dest.path("constraints"))if(!str(constraint,"kind").equals("f"))plan.before.add("ALTER TABLE "+target+" DROP CONSTRAINT "+q(plan.engine,str(constraint,"name")));
        for(JsonNode index:dest.path("indexes"))if(!index.path("implicit").asBoolean())plan.before.add("DROP INDEX "+qualified(plan.engine,plan.schema(str(source,"schema")),str(index,"name")));
        for(var entry:before.entrySet())if(!after.containsKey(entry.getKey())){
            requireDestructive(plan,"Remove column "+str(source,"name")+"."+entry.getKey());
            plan.before.add("ALTER TABLE "+target+" DROP COLUMN "+q(plan.engine,entry.getKey()));
        }
        for(var entry:after.entrySet()){
            JsonNode old=before.get(entry.getKey()),col=entry.getValue();String name=q(plan.engine,entry.getKey());
            if(old==null){if(!col.path("nullable").asBoolean()&&str(col,"identity").isEmpty())throw new IllegalArgumentException("Required column "+entry.getKey()+" needs a backfill before comparison");
                plan.before.add("ALTER TABLE "+target+" ADD COLUMN "+column(plan,col,false));continue;}
            if(!str(old,"identity").equals(str(col,"identity"))||!mappedNode(plan,col.path("identityOptions")).equals(old.path("identityOptions")))throw new IllegalArgumentException("Changing identity definitions on existing columns is unavailable: "+entry.getKey());
            if(!plan.mapped(str(col,"type")).equalsIgnoreCase(str(old,"type"))){requireDestructive(plan,"Change column type "+entry.getKey());if(!widening(str(old,"type"),str(col,"type")))throw new IllegalArgumentException("Column type conversion needs a dedicated backfill adapter: "+entry.getKey());
                plan.before.add("ALTER TABLE "+target+" ALTER COLUMN "+name+(plan.engine.equals("postgresql")?" TYPE ":" SET DATA TYPE ")+plan.mapped(str(col,"type")));}
            if(old.path("nullable").asBoolean()!=col.path("nullable").asBoolean()){
                if(!col.path("nullable").asBoolean())requireDestructive(plan,"Require non-null values in "+entry.getKey());
                plan.before.add("ALTER TABLE "+target+" ALTER COLUMN "+name+(col.path("nullable").asBoolean()?" DROP NOT NULL":" SET NOT NULL"));
            }
            if(!str(old,"default").isEmpty()&&str(col,"default").isEmpty()&&str(col,"identity").isEmpty())plan.before.add("ALTER TABLE "+target+" ALTER COLUMN "+name+" DROP DEFAULT");
        }
    }
    static boolean widening(String before,String after){
        String a=before.toLowerCase(Locale.ROOT).replace("character varying","varchar").replace(" ",""),b=after.toLowerCase(Locale.ROOT).replace("character varying","varchar").replace(" ","");
        List<String> integers=List.of("smallint","integer","bigint");if(integers.contains(a)&&integers.contains(b))return integers.indexOf(a)<=integers.indexOf(b);
        if(a.startsWith("varchar(")&&b.equals("text"))return true;
        var x=java.util.regex.Pattern.compile("varchar\\((\\d+)\\)").matcher(a);var y=java.util.regex.Pattern.compile("varchar\\((\\d+)\\)").matcher(b);
        if(x.matches()&&y.matches())return Long.parseLong(x.group(1))<=Long.parseLong(y.group(1));
        x=java.util.regex.Pattern.compile("(?:numeric|decimal)\\((\\d+),(\\d+)\\)").matcher(a);y=java.util.regex.Pattern.compile("(?:numeric|decimal)\\((\\d+),(\\d+)\\)").matcher(b);
        return x.matches()&&y.matches()&&Integer.parseInt(y.group(2))>=Integer.parseInt(x.group(2))&&Integer.parseInt(y.group(1))-Integer.parseInt(y.group(2))>=Integer.parseInt(x.group(1))-Integer.parseInt(x.group(2));
    }
    static Map<String,JsonNode> byName(JsonNode array){Map<String,JsonNode> map=new LinkedHashMap<>();array.forEach(n->map.put(str(n,"name"),n));return map;}
    static JsonNode columnShape(Plan plan,ObjectNode object,boolean mapped){ArrayNode out=Profiles.JSON.createArrayNode();for(JsonNode column:object.path("columns")){ObjectNode c=column.deepCopy();c.remove(List.of("id","comment","default","identityBase"));if(mapped)mapNode(c,plan);out.add(c);}return out;}
    static void finishTable(Plan plan,Choice choice){
        ObjectNode source=choice.source,dest=choice.destination;String target=plan.target(source);boolean changed=!same(plan,source,dest);
        if(changed&&!source.has("nativeDdl")){
            Map<String,JsonNode> oldColumns=dest==null?Map.of():byName(dest.path("columns"));
            for(JsonNode column:source.path("columns")){JsonNode old=oldColumns.get(str(column,"name"));if(!str(column,"comment").equals(old==null?"":str(old,"comment")))plan.after.add("COMMENT ON COLUMN "+target+"."+q(plan.engine,str(column,"name"))+" IS "+(str(column,"comment").isEmpty()?"NULL":CompareData.literal(str(column,"comment"),plan.engine)));}
            for(JsonNode column:source.path("columns"))if(str(column,"identity").isEmpty()&&!str(column,"default").isEmpty())plan.before.add("ALTER TABLE "+target+" ALTER COLUMN "+q(plan.engine,str(column,"name"))+" SET DEFAULT "+plan.mapped(str(column,"default")));
            for(JsonNode constraint:source.path("constraints"))if(!str(constraint,"kind").equals("f"))plan.before.add("ALTER TABLE "+target+" ADD CONSTRAINT "+q(plan.engine,str(constraint,"name"))+" "+plan.mapped(str(constraint,"definition")));
            for(JsonNode index:source.path("indexes"))if(!index.path("implicit").asBoolean()){
                if(index.has("ddl"))plan.before.add(plan.mapped(str(index,"ddl")));
                else{if(index.path("expression").asBoolean()||index.has("predicate"))throw new IllegalArgumentException("Expression/filtered index is unavailable: "+str(index,"name"));
                    plan.before.add("CREATE "+(index.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+qualified(plan.engine,plan.schema(str(source,"schema")),str(index,"name"))+" ON "+target+" ("+columns(plan.engine,index.path("columns"))+")");}
            }
            if(dest==null)for(JsonNode trigger:source.path("triggers"))plan.after.add(plan.mapped(str(trigger,"definition")));
            else if(!source.path("triggers").equals(dest.path("triggers")))throw new IllegalArgumentException("Trigger changes require a dedicated comparison adapter: "+str(source,"name"));
        }
        if(changed)for(JsonNode fk:source.path("foreignKeys"))plan.after.add(foreignKey(plan,source,fk,true));
    }
    static String foreignKey(Plan plan,JsonNode table,JsonNode fk,boolean source){
        String target=source?plan.target(table):qualified(plan.engine,str(table,"schema"),str(table,"name"));
        if(fk.has("definition"))return "ALTER TABLE "+target+" ADD CONSTRAINT "+q(plan.engine,str(fk,"name"))+" "+(source?plan.mapped(str(fk,"definition")):str(fk,"definition"));
        String schema=source?plan.schema(str(fk,"schema")):str(fk,"schema");
        String sql="ALTER TABLE "+target+" ADD CONSTRAINT "+q(plan.engine,str(fk,"name"))+" FOREIGN KEY ("+columns(plan.engine,fk.path("columns"))+") REFERENCES "+qualified(plan.engine,schema,str(fk,"table"))+" ("+columns(plan.engine,fk.path("references"))+") ON UPDATE "+rule(fk.path("updateRule").asInt())+" ON DELETE "+rule(fk.path("deleteRule").asInt());
        if(plan.engine.equals("postgresql"))sql+=switch(fk.path("deferrability").asInt()){case 5->" DEFERRABLE INITIALLY IMMEDIATE";case 6->" NOT DEFERRABLE";default->" DEFERRABLE INITIALLY DEFERRED";};
        return sql;
    }
    static String rule(int rule){return switch(rule){case 0->"CASCADE";case 1->"RESTRICT";case 2->"SET NULL";case 3->"NO ACTION";case 4->"SET DEFAULT";default->throw new IllegalArgumentException("Unknown foreign-key action");};}
    static void objectDefinition(Plan plan,Choice choice){
        ObjectNode source=choice.source,dest=choice.destination;String kind=str(source,"kind"),target=plan.target(source);if(same(plan,source,dest))return;
        switch(kind){
            case "views","materialized_views"->{
                if(dest!=null&&!mappedNode(plan,source.path("columnSignature")).equals(dest.path("columnSignature")))throw new IllegalArgumentException("Changing view columns requires a dependency-preserving rebuild adapter: "+str(source,"name"));
                if(kind.equals("materialized_views")&&dest!=null)throw new IllegalArgumentException("Recreating existing materialized views requires a dedicated dependency adapter");
                String mysql="";if(Set.of("mysql","mariadb").contains(plan.engine)){
                    String algorithm=str(source.path("fields"),"algorithm"),security=str(source.path("fields"),"security");if(!Set.of("UNDEFINED","MERGE","TEMPTABLE").contains(algorithm)||!Set.of("INVOKER","DEFINER").contains(security))throw new IllegalArgumentException("Unsupported view security/algorithm");mysql="ALGORITHM="+algorithm+" SQL SECURITY "+security+" ";
                }
                String ddl="CREATE "+(kind.equals("views")&&dest!=null?"OR REPLACE ":"")+mysql+(kind.equals("views")?"VIEW ":"MATERIALIZED VIEW ")+target;
                List<String> names=new ArrayList<>();for(JsonNode col:source.path("columnSignature"))names.add(q(plan.engine,str(col,"name")));if(!names.isEmpty())ddl+=" ("+String.join(", ",names)+")";
                String options=str(source.path("fields"),"options");if(!options.isBlank())ddl+=" WITH ("+options.replace("\n",", ")+")";
                ddl+=" AS\n"+plan.mapped(str(source.path("fields"),"query"));
                if(Set.of("mysql","mariadb").contains(plan.engine)){String check=str(source.path("fields"),"checkOption");if(Set.of("LOCAL","CASCADED").contains(check))ddl+=" WITH "+check+" CHECK OPTION";else if(!check.equals("NONE"))throw new IllegalArgumentException("Unsupported view check option");}
                if(kind.equals("materialized_views"))ddl+=source.path("fields").path("populate").asBoolean()?"\nWITH DATA":"\nWITH NO DATA";plan.after.add(ddl);
            }
            case "sequences"->{
                JsonNode f=source.path("fields");if(dest==null)plan.before.add("CREATE SEQUENCE "+target+sequenceOptions(f,true));
                else plan.before.add("ALTER SEQUENCE "+target+sequenceOptions(f,false));
                if(source.has("ownership")&&!source.path("identity").asBoolean()){
                    JsonNode owner=source.path("ownership");plan.after.add("ALTER SEQUENCE "+target+" OWNED BY "+qualified(plan.engine,plan.schema(str(owner,"schema")),str(owner,"table"))+"."+q(plan.engine,str(owner,"column")));
                }
            }
            case "indexes"->{
                boolean managed=false;for(Choice table:plan.selected.values())if(str(table.source,"kind").equals("tables")&&!same(plan,table.source,table.destination))
                    for(JsonNode index:table.source.path("indexes"))if(str(index,"name").equals(str(source,"name"))&&str(table.source,"schema").equals(str(source,"schema")))managed=true;
                if(managed)return;if(dest!=null)plan.before.add("DROP INDEX "+target);plan.after.add(plan.mapped(str(source,"ddl")));
            }
            case "types","domains"->{
                if(dest!=null)throw new IllegalArgumentException("Changing an existing type/domain is unavailable: "+str(source,"name"));plan.before.add(plan.mapped(str(source,"ddl")));
            }
            case "functions","procedures"->{
                if(!plan.engine.equals("postgresql"))throw new IllegalArgumentException("Routine script formatting is unavailable for "+plan.engine);
                String ddl=plan.mapped(str(source,"ddl"));if(ddl.isBlank())throw new IllegalArgumentException("Routine definition unavailable");
                if(dest!=null&&(!canonical(plan.mapped(source.path("signature").toString())).equals(canonical(dest.path("signature").toString()))||!str(source,"routineIdentity").equals(str(dest,"routineIdentity"))))throw new IllegalArgumentException("Routine signature changes require a dedicated adapter");plan.before.add(ddl);
            }
            default->throw new IllegalArgumentException("Unsupported object kind: "+kind);
        }
    }
    static String sequenceOptions(JsonNode f,boolean create){
        return (create&&!str(f,"type").isBlank()?" AS "+str(f,"type"):"")+(create?" START WITH "+integer(str(f,"start")):"")+" INCREMENT BY "+integer(str(f,"increment"))+" MINVALUE "+integer(str(f,"minimum"))+" MAXVALUE "+integer(str(f,"maximum"))+" CACHE "+integer(str(f,"cache"))+(truth(f.path("cycle"))?" CYCLE":" NO CYCLE");
    }
    static void sequence(Plan plan,Choice choice){
        String mode=plan.sequenceMode(choice);if(!contains(choice.source.path("stateModes"),mode))throw new IllegalArgumentException(str(choice.source,"name")+": requested sequence state mode is unavailable");
        JsonNode f=choice.source.path("fields");BigInteger step=integer(str(f,"increment")),next=next(plan.engine,choice.source);
        if(mode.equals("advance")){
            if(truth(f.path("cycle")))throw new IllegalArgumentException("Safe advancement is unavailable for cycling sequences");
            if(choice.destination!=null){if(!contains(choice.destination.path("stateModes"),"advance"))throw new IllegalArgumentException("Destination sequence state is unreadable");next=advance(next,next(plan.engine,choice.destination),step);}
        }
        for(JsonNode observed:choice.destination==null?List.of(choice.source):List.of(choice.source,choice.destination))if(observed.path("state").hasNonNull("extreme")){
            BigInteger extreme=integer(str(observed.path("state"),"extreme"));
            if(mode.equals("advance"))next=advance(next,extreme.add(BigInteger.valueOf(step.signum())),step);
            else if(step.signum()>0?next.compareTo(extreme)<=0:next.compareTo(extreme)>=0)throw new IllegalArgumentException("Exact sequence state would collide with existing consuming rows");
        }
        if(next.compareTo(integer(str(f,"minimum")))<0||next.compareTo(integer(str(f,"maximum")))>0)throw new IllegalArgumentException("Sequence is exhausted: "+str(choice.source,"name"));
        plan.state.add("ALTER SEQUENCE "+plan.target(choice.source)+" RESTART WITH "+next);
        if(mode.equals("exact"))plan.warnings.add("Exact sequence state may move a generator backwards. Ensure captured values do not conflict with destination rows.");
    }
    static boolean contains(JsonNode array,String value){for(JsonNode n:array)if(n.asText().equals(value))return true;return false;}
    static BigInteger next(String engine,JsonNode object){JsonNode state=object.path("state");BigInteger value=integer(str(state,"value"));return engine.equals("postgresql")&&truth(state.path("called"))?value.add(integer(str(object.path("fields"),"increment"))):value;}
    static BigInteger advance(BigInteger source,BigInteger destination,BigInteger step){if(step.signum()==0)throw new IllegalArgumentException("Sequence increment is zero");BigInteger delta=destination.subtract(source).multiply(BigInteger.valueOf(step.signum()));if(delta.signum()<=0)return source;BigInteger n=delta.add(step.abs()).subtract(BigInteger.ONE).divide(step.abs());return source.add(n.multiply(step));}
    static BigInteger integer(String value){try{return new BigInteger(value);}catch(NumberFormatException failure){throw new IllegalArgumentException("Sequence metadata is incomplete");}}
    static void requireDestructive(Plan plan,String operation){if(!plan.destructive)throw new IllegalArgumentException(operation+" requires enabling destructive schema changes");}
    static String normalizeMysql(String ddl){
        ddl=ddl.replaceAll("(?i) AUTO_INCREMENT=\\d+","").strip();int open=ddl.indexOf('('),close=ddl.lastIndexOf(')');
        if(open<0||close<open)return ddl;
        List<String> entries=split(ddl.substring(open+1,close));entries.removeIf(s->s.stripLeading().matches("(?is)(CONSTRAINT\\s+.+?\\s+)?FOREIGN\\s+KEY\\b.*"));
        return ddl.substring(0,open+1)+String.join(",",entries.stream().map(String::strip).toList())+ddl.substring(close);
    }
    static String mysqlCreate(Plan plan,JsonNode table){
        String ddl=normalizeMysql(str(table,"nativeDdl"));int open=ddl.indexOf('('),close=ddl.lastIndexOf(')');if(open<0||close<open)throw new IllegalArgumentException("Unrecognized native table definition");
        List<String> entries=split(ddl.substring(open+1,close));entries.removeIf(s->s.stripLeading().matches("(?is)(CONSTRAINT\\s+.+?\\s+)?FOREIGN\\s+KEY\\b.*"));
        return "CREATE TABLE "+plan.target(table)+" (\n"+plan.mapped(String.join(",\n",entries))+"\n)"+ddl.substring(close+1);
    }
    static List<String> split(String sql){
        List<String> parts=new ArrayList<>();int start=0,depth=0;char quote=0;
        for(int i=0;i<sql.length();i++){char c=sql.charAt(i);if(quote!=0){if(c=='\\'&&quote=='\''&&i+1<sql.length()){i++;continue;}if(c==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote)i++;else quote=0;}continue;}if(c=='\''||c=='"'||c==96){quote=c;continue;}if(c=='(')depth++;if(c==')')depth--;if(c==','&&depth==0){parts.add(sql.substring(start,i));start=i+1;}}
        if(quote!=0||depth!=0)throw new IllegalArgumentException("Unbalanced native definition");parts.add(sql.substring(start));return parts;
    }
    /** Schema remapping preserves comments and ordinary literals. */
    static String remap(String sql,Map<String,String> schemas){
        if(schemas.isEmpty()||schemas.entrySet().stream().allMatch(e->e.getKey().equals(e.getValue())))return sql;
        StringBuilder out=new StringBuilder();int i=0;
        while(i<sql.length()){
            char ch=sql.charAt(i);
            if(ch=='-'&&i+1<sql.length()&&sql.charAt(i+1)=='-'){int end=sql.indexOf('\n',i);if(end<0)end=sql.length();out.append(sql,i,end);i=end;continue;}
            if(ch=='/'&&i+1<sql.length()&&sql.charAt(i+1)=='*'){int depth=1,end=i+2;while(end<sql.length()&&depth>0){if(end+1<sql.length()&&sql.startsWith("/*",end)){depth++;end+=2;}else if(end+1<sql.length()&&sql.startsWith("*/",end)){depth--;end+=2;}else end++;}if(depth!=0)throw new IllegalArgumentException("Unclosed SQL comment");out.append(sql,i,end);i=end;continue;}
            if(ch=='$'){int tagEnd=sql.indexOf('$',i+1);if(tagEnd>=0&&sql.substring(i+1,tagEnd).matches("[A-Za-z_0-9]*")){
                String tag=sql.substring(i,tagEnd+1);int end=sql.indexOf(tag,tagEnd+1);if(end<0)throw new IllegalArgumentException("Unclosed routine body");String body=sql.substring(tagEnd+1,end);
                if(body.matches("(?is).*\\bEXECUTE\\b.*"))throw new IllegalArgumentException("Dynamic routine SQL cannot be safely remapped across schemas");
                out.append(tag).append(remap(body,schemas)).append(tag);i=end+tag.length();continue;}}
            if(ch=='\''){int end=quotedEnd(sql,i,ch);String literal=sql.substring(i,end),rest=sql.substring(end);
                if(rest.matches("(?s)^\\s*::\\s*(?:pg_catalog\\.)?regclass\\b.*")){String value=literal.substring(1,literal.length()-1).replace("''","'");out.append("'").append(remap(value,schemas).replace("'","''")).append("'");}else out.append(literal);i=end;continue;}
            if(ch=='"'||ch==96||Character.isLetter(ch)||ch=='_'){
                boolean quoted=ch=='"'||ch==96;int end;if(quoted)end=quotedEnd(sql,i,ch);else{end=i+1;while(end<sql.length()&&(Character.isLetterOrDigit(sql.charAt(end))||sql.charAt(end)=='_'||sql.charAt(end)=='$'))end++;}
                String token=sql.substring(i,end),name=quoted?token.substring(1,token.length()-1).replace(""+ch+ch,""+ch):token;int next=end;while(next<sql.length()&&Character.isWhitespace(sql.charAt(next)))next++;String mapped=schemas.get(name);
                if(mapped!=null&&next<sql.length()&&sql.charAt(next)=='.')out.append(quoted?""+ch+mapped.replace(""+ch,""+ch+ch)+ch:q("postgresql",mapped));else out.append(token);i=end;continue;}
            out.append(ch);i++;
        }return out.toString();
    }
    static int quotedEnd(String sql,int begin,char quote){for(int i=begin+1;i<sql.length();i++){if(sql.charAt(i)==quote){if(i+1<sql.length()&&sql.charAt(i+1)==quote)i++;else return i+1;}if(sql.charAt(i)=='\\'&&quote=='\''&&i+1<sql.length())i++;}throw new IllegalArgumentException("Unclosed SQL token");}
    private CompareSql(){}
}
