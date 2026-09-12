package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectDesigner.*;

/** Property descriptors and dialect-aware changes. SQL mode handles native features without lossy reconstruction. */
final class ObjectForms {
    static String q(String engine,String value){if(value.isBlank()||value.length()>256||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Enter a valid object identifier");return MetadataActions.quote(engine,value);}
    static String qualified(String e,String s,String n){return s.isBlank()?q(e,n):q(e,s)+"."+q(e,n);}
    static void field(ObjectNode out,String category,String id,String label,String type,String fallback,boolean editable,String... options){
        category(out,category);ObjectNode f=(ObjectNode)out.path("fields");
        if(!f.hasNonNull(id)){if(type.equals("boolean"))f.put(id,Boolean.parseBoolean(fallback));else f.put(id,fallback);}
        ObjectNode control=((ArrayNode)out.path("controls")).addObject().put("category",category).put("id",id).put("label",label).put("type",type).put("editable",editable);
        if(options.length>0){ArrayNode a=control.putArray("options");for(String option:options)a.add(option);}
        if(out.path("choices").has(id))control.set("choices",out.path("choices").path(id));
    }
    static boolean formSupported(String engine,String k){
        return engine.equals("postgresql")&&Set.of("columns","schemas","sequences","views","materialized_views","functions","procedures","indexes","types","domains","constraints","policies","triggers","rules","extensions","roles","foreign_servers","event_triggers").contains(k)
            ||engine.equals("h2")&&Set.of("schemas","sequences","views","indexes","domains").contains(k);
    }
    static void configure(Connection c,ObjectNode out)throws SQLException{
        String e=str(out,"engine"),k=str(out,"kind");boolean pg=e.equals("postgresql"),create=out.path("creation").asBoolean(),forms=formSupported(e,k);
        out.put("formSupported",forms);ObjectNode f=(ObjectNode)out.path("fields");
        field(out,"General","name","Name","text","",forms&&(create||!Set.of("extensions","constraints","triggers","policies","rules","event_triggers","foreign_servers").contains(k)));
        field(out,"General","schema","Schema","text","",pg&&!create&&Set.of("sequences","views","materialized_views","functions","procedures","types","domains").contains(k));
        field(out,"General","owner","Owner","text","",pg&&forms&&!Set.of("extensions","roles","columns","indexes","triggers","constraints","policies","rules").contains(k));
        field(out,"General","comment","Comment","textarea","",forms&&!Set.of("roles","foreign_servers","event_triggers").contains(k));
        switch(k){
            case "columns"->{
                field(out,"Definition","type","Datatype","text","integer",pg);
                field(out,"Definition","nullable","Nullable","boolean","true",pg);
                field(out,"Definition","default","Default expression","sql","",pg&&str(f,"identity").isBlank()&&str(f,"generated").isBlank());
                field(out,"Definition","identity","Identity mode","text","",false);field(out,"Definition","generated","Generated mode","text","",false);
            }
            case "sequences"->{
                field(out,"Definition","type","Datatype","text","bigint",forms);
                for(String[] spec:new String[][]{{"start","Start value","1"},{"increment","Increment","1"},{"minimum","Minimum value","1"},{"maximum","Maximum value","9223372036854775807"},{"cache","Cache size","1"}})
                    field(out,"Definition",spec[0],spec[1],"integer",spec[2],forms);
                field(out,"Definition","cycle","Cycle","boolean","false",forms);
                field(out,"Ownership","ownedBy","Owned by column (qualified SQL identifier, or NONE)","text","",pg);
                field(out,"State","restart","Restart at (blank keeps current state)","integer","",forms&&!create);
            }
            case "views","materialized_views"->{
                field(out,"Definition","query","SELECT query","sql","",forms);
                field(out,"Definition","columnNames","Output column names (one per line; optional)","textarea","",forms&&create);
                if(pg)field(out,"Options","options","View/storage options (one name=value per line)","textarea","",forms);
                if(k.equals("materialized_views")){
                    field(out,"Storage","tablespace","Tablespace","text","",pg);
                    field(out,"Storage","accessMethod","Access method","text","heap",pg&&create);
                    field(out,"Refresh","populate","Populate during creation","boolean","true",forms&&create);
                    category(out,"Indexes");category(out,"Refresh");
                    if(!create)warning(out,"Changing this query uses a reviewed, transactional replacement. Indexes, grants and supported attributes are restored. Dependent objects block replacement; CASCADE is never added.");
                }
                category(out,"Columns");category(out,"Permissions");category(out,"Dependencies");
            }
            case "functions","procedures"->{
                boolean bodyEditable=forms&&!out.path("nativeBody").asBoolean();
                field(out,"Parameters","parameters","Parameters (native declaration)","sql","",forms&&create);
                if(k.equals("functions")){field(out,"Return Type","returns","Return type / TABLE declaration","text","void",forms&&create);field(out,"Return Type","returns_set","Returns a set","boolean","false",forms&&create);}
                field(out,"Definition","language","Language","text","sql",bodyEditable);
                field(out,"Definition","body","Body","sql","",bodyEditable);
                field(out,"Security","security_definer","Execute as owner (SECURITY DEFINER)","boolean","false",bodyEditable);
                if(k.equals("functions")){
                    field(out,"Execution","volatility","Volatility","select","VOLATILE",bodyEditable,"VOLATILE","STABLE","IMMUTABLE");
                    field(out,"Execution","parallel","Parallel safety","select","UNSAFE",bodyEditable,"UNSAFE","RESTRICTED","SAFE");
                    field(out,"Execution","strict","Strict (returns NULL on NULL input)","boolean","false",bodyEditable);
                    field(out,"Security","leakproof","Leakproof (requires database privilege)","boolean","false",bodyEditable);
                    field(out,"Execution","cost","Estimated cost","decimal","100",bodyEditable);
                    field(out,"Execution","rows","Estimated returned rows","decimal","1000",bodyEditable&&f.path("returns_set").asBoolean());
                }
                field(out,"Configuration","configuration","Settings (one name=value per line)","textarea","",bodyEditable);
                category(out,"Permissions");category(out,"Dependencies");
                if(out.path("nativeBody").asBoolean())warning(out,"This routine uses a native/parsed body. Its exact definition is retained in DDL; edit that definition to preserve language-specific attributes.");
            }
            case "indexes"->{
                field(out,"Definition","table","Table (qualified SQL identifier)","text","",forms&&create);
                field(out,"Definition","columns","Key columns / expressions","sql","",forms&&create);
                field(out,"Definition","unique","Unique","boolean","false",forms&&create);
                if(pg){field(out,"Definition","method","Access method","text","btree",create);field(out,"Definition","include","Included columns","sql","",create);field(out,"Definition","predicate","Partial-index predicate","sql","",create);field(out,"Storage","tablespace","Tablespace","text","",true);field(out,"Storage","options","Storage parameters (one name=value per line)","textarea","",true);}
                category(out,"Dependencies");
            }
            case "types","domains"->{
                if(k.equals("types")&&!out.path("domain").asBoolean())field(out,"Labels","labels","Enum labels (one per line)","textarea","",forms&&(create||str(out.path("details").path("Advanced"),"typtype").equals("e")));
                else {field(out,"Definition","type","Base datatype","text","integer",forms&&create);field(out,"Definition","default","Default expression","sql","",forms);field(out,"Definition","notNull","Not null","boolean","false",pg);}
                category(out,"Constraints");category(out,"Attributes");category(out,"Permissions");
            }
            case "schemas"->category(out,"Permissions");
            case "constraints"->field(out,"Definition","definition","Constraint definition","sql","CHECK (true)",pg&&create);
            case "policies"->{field(out,"Definition","using","USING expression","sql","",pg);field(out,"Definition","check","WITH CHECK expression","sql","",pg);field(out,"Security","roles","Roles (one per line, or PUBLIC)","textarea","PUBLIC",pg&&create);field(out,"Definition","command","Command","select","ALL",pg&&create,"ALL","SELECT","INSERT","UPDATE","DELETE");field(out,"Definition","permissive","Permissive","boolean","true",pg&&create);}
            case "triggers"->{field(out,"Definition","definition","Trigger clause (timing, events, table, function)","sql","",pg&&create);field(out,"Execution","enabled","Enabled mode","select","O",pg&&!create,"O","D","R","A");category(out,"Events");}
            case "rules"->field(out,"Definition","definition","Rule definition (after AS)","sql","",pg&&create);
            case "extensions"->field(out,"Definition","version","Version (blank uses database default)","text","",pg);
            case "roles"->{for(String[] flag:new String[][]{{"login","Can log in"},{"superuser","Superuser"},{"createdb","Create databases"},{"createrole","Create roles"},{"inherit","Inherit privileges"},{"replication","Replication"},{"bypassrls","Bypass row-level security"}}){if(!create)f.put(flag[0],out.path("details").path("Advanced").path("rol"+flag[0]).asBoolean());field(out,"Privileges",flag[0],flag[1],"boolean",flag[0].equals("inherit")?"true":"false",pg);}category(out,"Membership");}
            case "foreign_servers"->{field(out,"Definition","wrapper","Foreign data wrapper","text","",pg&&create);field(out,"Options","serverOptions","Options (native SQL list)","sql","",pg&&create);}
            case "event_triggers"->field(out,"Definition","definition","Event trigger clause (ON event … EXECUTE FUNCTION …)","sql","",pg&&create);
            default->{category(out,"Definition");category(out,"Dependencies");}
        }
        if(!forms)warning(out,"This database/object uses the native DDL editor for changes. Available driver and catalog properties are shown below.");
        if(create&&!forms)out.put("template","-- Enter the database's complete CREATE "+label(k).toUpperCase(Locale.ROOT)+" statement here.\n");
    }
    static List<String> compile(ObjectNode base,JsonNode draft){
        String e=str(base,"engine"),k=str(base,"kind");boolean pg=e.equals("postgresql"),create=base.path("creation").asBoolean();
        if(!base.path("formSupported").asBoolean())throw new IllegalArgumentException("Use the native DDL editor for this object type");
        JsonNode f=draft.path("fields"),old=base.path("fields");
        for(JsonNode ctrl:base.path("controls")){
            String key=str(ctrl,"id");if(!ctrl.path("editable").asBoolean()&&!f.path(key).equals(old.path(key)))throw new IllegalArgumentException(str(ctrl,"label")+" is read-only in the form. Use DDL for a native alteration.");
        }
        String name=str(f,"name"),schema=str(f,"schema");q(e,name);
        String t=qualified(e,create?schema:str(old,"schema"),create?name:str(old,"name")),type=base.path("domain").asBoolean()?"DOMAIN":label(k).toUpperCase(Locale.ROOT);
        if(Set.of("schemas","roles","extensions","foreign_servers","event_triggers").contains(k))t=q(e,create?name:str(old,"name"));
        if(k.equals("foreign_servers"))type="SERVER";
        List<String> sql=new ArrayList<>();
        switch(k){
            case "columns"->{
                String table=parentTable(base),column=q(e,create?name:str(old,"name"));
                if(create)sql.add("ALTER TABLE "+table+" ADD COLUMN "+column+" "+fragment(str(f,"type"))+(str(f,"default").isBlank()?"":" DEFAULT "+fragment(str(f,"default")))+(f.path("nullable").asBoolean()?"":" NOT NULL"));
                else {if(changed(old,f,"type"))sql.add("ALTER TABLE "+table+" ALTER COLUMN "+column+" TYPE "+fragment(str(f,"type")));if(changed(old,f,"nullable"))sql.add("ALTER TABLE "+table+" ALTER COLUMN "+column+(f.path("nullable").asBoolean()?" DROP NOT NULL":" SET NOT NULL"));if(changed(old,f,"default"))sql.add("ALTER TABLE "+table+" ALTER COLUMN "+column+(str(f,"default").isBlank()?" DROP DEFAULT":" SET DEFAULT "+fragment(str(f,"default"))));}
                if(create&&!str(f,"comment").isBlank()||!create&&changed(old,f,"comment"))sql.add("COMMENT ON COLUMN "+table+"."+column+" IS "+(str(f,"comment").isBlank()?"NULL":TableDesigner.literal(str(f,"comment"))));
                if(!create&&changed(old,f,"name"))sql.add("ALTER TABLE "+table+" RENAME COLUMN "+column+" TO "+q(e,name));
                return sql;
            }
            case "schemas"->{if(create)sql.add("CREATE SCHEMA "+t+(pg&&!str(f,"owner").isBlank()?" AUTHORIZATION "+q(e,str(f,"owner")):""));}
            case "sequences"->{
                if(create)sql.add("CREATE SEQUENCE "+t+sequenceOptions(f,true));
                else{List<String> changes=new ArrayList<>();for(String[] spec:new String[][]{{"type","AS"},{"start","START WITH"},{"increment","INCREMENT BY"},{"minimum","MINVALUE"},{"maximum","MAXVALUE"},{"cache","CACHE"}})if(changed(old,f,spec[0]))changes.add(spec[1]+" "+(spec[0].equals("type")?sequenceType(str(f,"type")):integer(str(f,spec[0]))));
                    if(changed(old,f,"cycle"))changes.add(f.path("cycle").asBoolean()?"CYCLE":"NO CYCLE");
                    if(!str(f,"restart").isBlank())changes.add("RESTART WITH "+integer(str(f,"restart")));
                    if(!changes.isEmpty())sql.add("ALTER SEQUENCE "+t+" "+String.join(" ",changes));}
                if(pg&&(create&&!str(f,"ownedBy").isBlank()||!create&&changed(old,f,"ownedBy")))sql.add("ALTER SEQUENCE "+t+" OWNED BY "+(str(f,"ownedBy").isBlank()||str(f,"ownedBy").equalsIgnoreCase("NONE")?"NONE":identifierPath(str(f,"ownedBy"))));
            }
            case "views","materialized_views"->{
                if(create||changed(old,f,"query")){
                    if(!create&&k.equals("materialized_views")){sql.addAll(MaterializedViewChanges.replace(base,f));break;}
                    String query=fragment(str(f,"query"));if(!query.stripLeading().matches("(?is)(SELECT|WITH|VALUES|TABLE)\\b.*"))throw new IllegalArgumentException("Enter a SELECT query");
                    String cols=str(f,"columnNames").isBlank()?"":" ("+String.join(", ",str(f,"columnNames").lines().map(x->q(e,x.strip())).toList())+")";
                    sql.add("CREATE "+(!create?"OR REPLACE ":"")+(k.equals("views")?"VIEW ":"MATERIALIZED VIEW ")+t+cols+(k.equals("materialized_views")&&pg?" USING "+q(e,str(f,"accessMethod")):"")+(pg&&!str(f,"options").isBlank()?" WITH ("+options(str(f,"options"))+")":"")+(k.equals("materialized_views")&&!str(f,"tablespace").isBlank()?" TABLESPACE "+q(e,str(f,"tablespace")):"")+" AS\n"+query+(k.equals("materialized_views")?(f.path("populate").asBoolean()?"\nWITH DATA":"\nWITH NO DATA"):""));
                }
                if(pg&&!create){changeOptions(sql,type,t,old,f);if(k.equals("materialized_views")&&changed(old,f,"tablespace"))sql.add("ALTER MATERIALIZED VIEW "+t+" SET TABLESPACE "+q(e,str(f,"tablespace").isBlank()?"pg_default":str(f,"tablespace")));}
            }
            case "functions","procedures"->{
                boolean definition=create;for(String key:List.of("body","language","security_definer","strict","leakproof","volatility","parallel","cost","rows","configuration"))definition|=changed(old,f,key);
                if(definition){
                    if(!create&&base.path("nativeBody").asBoolean())throw new IllegalArgumentException("Edit this routine's exact native definition in DDL");
                    String parameters=fragmentOptional(str(f,"parameters")),body=str(f,"body");if(body.isBlank())throw new IllegalArgumentException("Routine body is required");
                    String returns=k.equals("functions")?" RETURNS "+(f.path("returns_set").asBoolean()&&!str(f,"returns").stripLeading().toUpperCase(Locale.ROOT).startsWith("TABLE")?"SETOF ":"")+fragment(str(f,"returns")):"";
                    String attrs=" LANGUAGE "+q(e,str(f,"language"))+(f.path("security_definer").asBoolean()?" SECURITY DEFINER":" SECURITY INVOKER");
                    if(k.equals("functions")){attrs+=" "+choice(f,"volatility","VOLATILE","STABLE","IMMUTABLE")+" PARALLEL "+choice(f,"parallel","UNSAFE","RESTRICTED","SAFE")+(f.path("strict").asBoolean()?" STRICT":" CALLED ON NULL INPUT")+(f.path("leakproof").asBoolean()?" LEAKPROOF":" NOT LEAKPROOF")+" COST "+decimal(str(f,"cost"));if(f.path("returns_set").asBoolean())attrs+=" ROWS "+decimal(str(f,"rows"));}
                    String tag="$codegraph$";while(body.contains(tag))tag=tag.substring(0,tag.length()-1)+"x$";
                    if(create)sql.add("CREATE "+type+" "+t+"("+parameters+")"+returns+attrs+" AS "+tag+body+tag);
                    else {
                        // Start with the database's own complete declaration, retaining SUPPORT,
                        // WINDOW, TRANSFORM and settings that a newer server may expose.
                        if(changed(old,f,"body")||changed(old,f,"language")){
                            String ddl=str(base,"ddl");var match=java.util.regex.Pattern.compile("(?s)\\bAS\\s+(\\$[A-Za-z_0-9]*\\$).*?\\1").matcher(ddl);
                            if(!match.find())throw new IllegalArgumentException("Use DDL to edit this native routine body");
                            ddl=ddl.substring(0,match.start())+"AS "+tag+body+tag+ddl.substring(match.end());
                            if(changed(old,f,"language"))ddl=ddl.replaceFirst("(?im)(\\bLANGUAGE\\s+)[^\\r\\n]+",java.util.regex.Matcher.quoteReplacement("LANGUAGE "+q(e,str(f,"language"))));
                            sql.add(ddl.replaceFirst(";\\s*$",""));
                        }
                        String routine=type+" "+t+"("+str(base,"routineIdentity")+")";
                        if(changed(old,f,"security_definer"))sql.add("ALTER "+routine+(f.path("security_definer").asBoolean()?" SECURITY DEFINER":" SECURITY INVOKER"));
                        if(k.equals("functions")){
                            if(changed(old,f,"volatility"))sql.add("ALTER "+routine+" "+choice(f,"volatility","VOLATILE","STABLE","IMMUTABLE"));
                            if(changed(old,f,"parallel"))sql.add("ALTER "+routine+" PARALLEL "+choice(f,"parallel","UNSAFE","RESTRICTED","SAFE"));
                            if(changed(old,f,"strict"))sql.add("ALTER "+routine+(f.path("strict").asBoolean()?" STRICT":" CALLED ON NULL INPUT"));
                            if(changed(old,f,"leakproof"))sql.add("ALTER "+routine+(f.path("leakproof").asBoolean()?" LEAKPROOF":" NOT LEAKPROOF"));
                            if(changed(old,f,"cost"))sql.add("ALTER "+routine+" COST "+decimal(str(f,"cost")));
                            if(changed(old,f,"rows")&&f.path("returns_set").asBoolean())sql.add("ALTER "+routine+" ROWS "+decimal(str(f,"rows")));
                        }
                    }
                    String identity=create?identityArguments(parameters):str(base,"routineIdentity");
                    // Configuration is applied separately so removing a setting is represented too.
                    if(create&&!str(f,"configuration").isBlank()||!create&&changed(old,f,"configuration")){sql.add("ALTER "+type+" "+t+"("+identity+") RESET ALL");for(String line:ObjectCatalog.stringsLines(str(f,"configuration"))){int at=line.indexOf('=');if(at<1)throw new IllegalArgumentException("Settings must be name=value");String setting=settingName(line.substring(0,at).strip()),value=line.substring(at+1).strip();sql.add("ALTER "+type+" "+t+"("+identity+") SET "+setting+" TO "+(setting.equalsIgnoreCase("search_path")?fragment(value):TableDesigner.literal(value)));}}
                }
                t+="("+(create?identityArguments(fragmentOptional(str(f,"parameters"))):str(base,"routineIdentity"))+")";
            }
            case "indexes"->{
                if(create)sql.add("CREATE "+(f.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+(pg?q(e,name):t)+" ON "+identifierPath(str(f,"table"))+(pg?" USING "+q(e,str(f,"method")):"")+" ("+fragment(str(f,"columns"))+")"+(pg&&!str(f,"include").isBlank()?" INCLUDE ("+fragment(str(f,"include"))+")":"")+(pg&&!str(f,"options").isBlank()?" WITH ("+options(str(f,"options"))+")":"")+(pg&&!str(f,"tablespace").isBlank()?" TABLESPACE "+q(e,str(f,"tablespace")):"")+(pg&&!str(f,"predicate").isBlank()?" WHERE "+fragment(str(f,"predicate")):""));
                else if(pg){changeOptions(sql,"INDEX",t,old,f);if(changed(old,f,"tablespace"))sql.add("ALTER INDEX "+t+" SET TABLESPACE "+q(e,str(f,"tablespace").isBlank()?"pg_default":str(f,"tablespace")));}
            }
            case "types","domains"->{
                if(type.equals("DOMAIN")){
                    if(create)sql.add("CREATE DOMAIN "+t+" AS "+fragment(str(f,"type"))+(str(f,"default").isBlank()?"":" DEFAULT "+fragment(str(f,"default")))+(pg&&f.path("notNull").asBoolean()?" NOT NULL":""));
                    else{if(changed(old,f,"default"))sql.add("ALTER DOMAIN "+t+(str(f,"default").isBlank()?" DROP DEFAULT":" SET DEFAULT "+fragment(str(f,"default"))));if(changed(old,f,"notNull"))sql.add("ALTER DOMAIN "+t+(f.path("notNull").asBoolean()?" SET NOT NULL":" DROP NOT NULL"));}
                }else if(create||changed(old,f,"labels")){List<String> labels=ObjectCatalog.stringsLines(str(f,"labels"));if(labels.isEmpty())throw new IllegalArgumentException("Enter enum labels, or use native DDL to define another kind of type");
                    if(create)sql.add("CREATE TYPE "+t+" AS ENUM ("+String.join(", ",labels.stream().map(TableDesigner::literal).toList())+")");
                    else{List<String> prior=ObjectCatalog.stringsLines(str(old,"labels"));if(labels.size()<prior.size()||!labels.subList(0,prior.size()).equals(prior))throw new IllegalArgumentException("The enum form appends labels. Use native ALTER TYPE in DDL for renaming or ordering labels.");for(String value:labels.subList(prior.size(),labels.size()))sql.add("ALTER TYPE "+t+" ADD VALUE "+TableDesigner.literal(value));}}
            }
            case "constraints"->{if(create)sql.add("ALTER TABLE "+parentTable(base)+" ADD CONSTRAINT "+q(e,name)+" "+fragment(str(f,"definition")));}
            case "policies"->{
                String clause=(str(f,"using").isBlank()?"":" USING ("+fragment(str(f,"using"))+")")+(str(f,"check").isBlank()?"":" WITH CHECK ("+fragment(str(f,"check"))+")");
                if(create)sql.add("CREATE POLICY "+q(e,name)+" ON "+parentTable(base)+" AS "+(f.path("permissive").asBoolean()?"PERMISSIVE":"RESTRICTIVE")+" FOR "+choice(f,"command","ALL","SELECT","INSERT","UPDATE","DELETE")+" TO "+String.join(", ",str(f,"roles").lines().map(x->x.equalsIgnoreCase("PUBLIC")?"PUBLIC":q(e,x)).toList())+clause);
                else if(changed(old,f,"using")||changed(old,f,"check")){if((changed(old,f,"using")&&str(f,"using").isBlank())||(changed(old,f,"check")&&str(f,"check").isBlank()))throw new IllegalArgumentException("Removing a policy expression requires replacement; use DDL");sql.add("ALTER POLICY "+q(e,str(old,"name"))+" ON "+parentTable(base)+clause);}
            }
            case "triggers"->{if(create)sql.add("CREATE TRIGGER "+q(e,name)+" "+fragment(str(f,"definition")));else if(changed(old,f,"enabled"))sql.add("ALTER TABLE "+parentTable(base)+" "+switch(choice(f,"enabled","O","D","R","A")){case "D"->"DISABLE";case "R"->"ENABLE REPLICA";case "A"->"ENABLE ALWAYS";default->"ENABLE";}+" TRIGGER "+q(e,str(old,"name")));}
            case "rules"->{if(create)sql.add("CREATE RULE "+q(e,name)+" AS "+fragment(str(f,"definition")));}
            case "extensions"->{if(create)sql.add("CREATE EXTENSION "+t+(str(f,"version").isBlank()?"":" VERSION "+TableDesigner.literal(str(f,"version"))));else if(changed(old,f,"version"))sql.add("ALTER EXTENSION "+t+" UPDATE"+(str(f,"version").isBlank()?"":" TO "+TableDesigner.literal(str(f,"version"))));}
            case "roles"->{List<String> flags=new ArrayList<>();for(String key:List.of("login","superuser","createdb","createrole","inherit","replication","bypassrls"))if(create||changed(old,f,key))flags.add((f.path(key).asBoolean()?"":"NO")+key.toUpperCase(Locale.ROOT));if(create||!flags.isEmpty())sql.add((create?"CREATE":"ALTER")+" ROLE "+t+" "+String.join(" ",flags));}
            case "foreign_servers"->{if(create)sql.add("CREATE SERVER "+t+" FOREIGN DATA WRAPPER "+q(e,str(f,"wrapper"))+(str(f,"serverOptions").isBlank()?"":" OPTIONS ("+fragment(str(f,"serverOptions"))+")"));}
            case "event_triggers"->{if(create)sql.add("CREATE EVENT TRIGGER "+t+" "+fragment(str(f,"definition")));}
            default->throw new IllegalArgumentException("Use the native DDL editor for this definition");
        }
        if((create&&!str(f,"comment").isBlank()||!create&&changed(old,f,"comment"))&&base.path("controls").findValues("id").stream().anyMatch(x->x.asText().equals("comment"))){
            String commentTarget=Set.of("constraints","triggers","policies","rules").contains(k)?q(e,name)+" ON "+parentTable(base):t;
            sql.add("COMMENT ON "+type+" "+commentTarget+" IS "+(str(f,"comment").isBlank()?"NULL":TableDesigner.literal(str(f,"comment"))));
        }
        if(!create&&changed(old,f,"name")){
            if(Set.of("extensions","constraints","triggers","policies","rules","event_triggers","foreign_servers").contains(k))throw new IllegalArgumentException("Use DDL for this object's native rename command");
            sql.add("ALTER "+type+" "+t+" RENAME TO "+q(e,name));
            t=Set.of("schemas","roles").contains(k)?q(e,name):qualified(e,str(old,"schema"),name);
            if(Set.of("functions","procedures").contains(k))t+="("+str(base,"routineIdentity")+")";
        }
        if(!create&&changed(old,f,"schema")){
            sql.add("ALTER "+type+" "+t+" SET SCHEMA "+q(e,schema));t=qualified(e,schema,name);
            if(Set.of("functions","procedures").contains(k))t+="("+str(base,"routineIdentity")+")";
        }
        if(pg&&!str(f,"owner").isBlank()&&(create||changed(old,f,"owner"))&&!Set.of("schemas","extensions","roles","indexes","constraints","policies","triggers","rules").contains(k))sql.add("ALTER "+type+" "+t+" OWNER TO "+q(e,str(f,"owner")));
        if(pg&&!create&&k.equals("schemas")&&changed(old,f,"owner"))sql.add("ALTER SCHEMA "+t+" OWNER TO "+q(e,str(f,"owner")));
        return sql;
    }
    static boolean changed(JsonNode old,JsonNode f,String key){return !old.path(key).equals(f.path(key));}
    static String parentTable(ObjectNode out){return qualified(str(out,"engine"),str(out.path("target"),"schema"),str(out.path("target"),"table"));}
    static String identityArguments(String source){
        List<String> args=new ArrayList<>();StringBuilder current=new StringBuilder();int depth=0;char quote=0;boolean defaultValue=false;
        for(int i=0;i<=source.length();i++){
            char ch=i==source.length()?',':source.charAt(i);
            if(quote!=0){if(!defaultValue)current.append(ch);if(ch==quote){if(i+1<source.length()&&source.charAt(i+1)==quote){if(!defaultValue)current.append(source.charAt(++i));else i++;}else quote=0;}continue;}
            if(ch=='\''||ch=='"'){quote=ch;if(!defaultValue)current.append(ch);continue;}
            if(ch=='('||ch=='[')depth++;if(ch==')'||ch==']')depth--;
            if(depth==0&&ch==','){if(!current.toString().isBlank())args.add(current.toString().strip());current.setLength(0);defaultValue=false;continue;}
            if(depth==0&&(ch=='='||i+7<=source.length()&&source.regionMatches(true,i,"DEFAULT",0,7)&&(i==0||Character.isWhitespace(source.charAt(i-1)))&&(i+7==source.length()||Character.isWhitespace(source.charAt(i+7))))){defaultValue=true;}
            if(!defaultValue)current.append(ch);
        }
        return String.join(", ",args);
    }
    static String sequenceOptions(JsonNode f,boolean create){
        return " AS "+sequenceType(str(f,"type"))+" START WITH "+integer(str(f,"start"))+" INCREMENT BY "+integer(str(f,"increment"))+" MINVALUE "+integer(str(f,"minimum"))+" MAXVALUE "+integer(str(f,"maximum"))+" CACHE "+integer(str(f,"cache"))+(ObjectCatalog.truth(f.path("cycle"))?" CYCLE":" NO CYCLE");
    }
    static String sequenceType(String s){String t=s.toUpperCase(Locale.ROOT);if(!Set.of("SMALLINT","INTEGER","BIGINT").contains(t))throw new IllegalArgumentException("Sequence datatype must be smallint, integer or bigint");return t;}
    static String integer(String s){if(!s.matches("[+-]?[0-9]+"))throw new IllegalArgumentException("Enter an integer");try{return Long.toString(Long.parseLong(s));}catch(NumberFormatException e){throw new IllegalArgumentException("Integer exceeds the signed 64-bit range");}}
    static String decimal(String s){try{var d=new java.math.BigDecimal(s);if(d.signum()<=0)throw new NumberFormatException();return d.toPlainString();}catch(NumberFormatException e){throw new IllegalArgumentException("Enter a positive numeric estimate");}}
    static String choice(JsonNode f,String key,String... options){String value=str(f,key);if(!List.of(options).contains(value))throw new IllegalArgumentException("Invalid "+key);return value;}
    static String fragmentOptional(String s){return s.isBlank()?"":fragment(s);}
    static String fragment(String s){if(s.isBlank()||s.length()>65536||s.indexOf('\0')>=0||s.contains(";")||s.contains("--")||s.contains("/*"))throw new IllegalArgumentException("Enter one SQL expression/clause without statement separators or comments; use DDL for a full native definition");return s.strip();}
    static String identifierPath(String s){String value=fragment(s);if(!value.matches("(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")+\"|\u0060[^\u0060]+\u0060)(?:\\.(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")+\"|\u0060[^\u0060]+\u0060)){0,2}"))throw new IllegalArgumentException("Enter a qualified SQL identifier, quoting special names");return value;}
    static String settingName(String s){if(!s.matches("[A-Za-z_][A-Za-z0-9_.]*"))throw new IllegalArgumentException("Invalid setting name");return s;}
    static String options(String source){List<String> values=new ArrayList<>();for(String line:ObjectCatalog.stringsLines(source)){int at=line.indexOf('=');if(at<1)throw new IllegalArgumentException("Options must use name=value");String key=settingName(line.substring(0,at).strip()),value=fragment(line.substring(at+1));values.add(key+"="+value);}return String.join(", ",values);}
    static void changeOptions(List<String> sql,String type,String t,JsonNode old,JsonNode f){if(!changed(old,f,"options"))return;Map<String,String> before=new LinkedHashMap<>(),after=new LinkedHashMap<>();for(String line:ObjectCatalog.stringsLines(str(old,"options"))){int at=line.indexOf('=');if(at>0)before.put(line.substring(0,at).strip(),line.substring(at+1));}for(String line:ObjectCatalog.stringsLines(str(f,"options"))){int at=line.indexOf('=');if(at<1)throw new IllegalArgumentException("Options must be name=value");after.put(settingName(line.substring(0,at).strip()),line.substring(at+1));}List<String> removed=before.keySet().stream().filter(x->!after.containsKey(x)).map(ObjectForms::settingName).toList();if(!removed.isEmpty())sql.add("ALTER "+type+" "+t+" RESET ("+String.join(", ",removed)+")");if(!after.isEmpty())sql.add("ALTER "+type+" "+t+" SET ("+options(str(f,"options"))+")");}
    private ObjectForms(){}
}
