package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.ObjectDesigner.*;

/** Bounded native definition and property discovery. Catalog columns remain visible by name. */
final class ObjectCatalog {
    static ArrayNode query(QueryJobs.Job job,Connection c,String sql,Object... args)throws Exception{
        try(var st=c.prepareStatement(sql)){
            job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setMaxRows(1001);
            for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);
            try(var rs=st.executeQuery()){return rows(job,rs);}
        }finally{job.statement=null;}
    }
    static ArrayNode rows(QueryJobs.Job job,ResultSet rs)throws Exception{
        ArrayNode out=Profiles.JSON.createArrayNode();var m=rs.getMetaData();long bytes=0;
        if(m.getColumnCount()>256)throw new IllegalArgumentException("Metadata exceeds 256 properties per row");
        while(rs.next()){
            if(job.cancelled)throw new java.util.concurrent.CancellationException();
            if(out.size()>=1000)throw new IllegalArgumentException("This property category exceeds 1,000 entries");
            ObjectNode row=out.addObject();
            for(int i=1;i<=m.getColumnCount();i++){
                String v;
                if(Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(m.getColumnType(i))){
                    try(var reader=rs.getCharacterStream(i)){
                        if(reader==null)v=null;else{StringBuilder text=new StringBuilder();char[] chunk=new char[4096];int count;
                            while((count=reader.read(chunk))!=-1){if(job.cancelled)throw new java.util.concurrent.CancellationException();if(text.length()+count>65536)throw new IllegalArgumentException("A definition exceeds the 64 KiB editor limit");text.append(chunk,0,count);}v=text.toString();}
                    }
                }else v=rs.getString(i);
                if(v!=null&&v.length()>65536)throw new IllegalArgumentException("A definition exceeds the 64 KiB editor limit");
                bytes+=2L*(m.getColumnLabel(i).length()+(v==null?4:v.length()))+32;
                if(bytes>1<<20)throw new IllegalArgumentException("This metadata category exceeds the 1 MiB editor limit");
                if(v==null)row.putNull(m.getColumnLabel(i).toLowerCase(Locale.ROOT));else row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),v);
            }
        }
        return out;
    }
    interface Read {void run()throws Exception;}
    static void optional(Connection c,ObjectNode out,String label,Read read)throws Exception{
        Savepoint save=null;try{
            if(!c.getAutoCommit()&&c.getMetaData().supportsSavepoints())save=c.setSavepoint();
            read.run();
        }catch(SQLException|UnsupportedOperationException e){
            if(save!=null)c.rollback(save);
            warning(out,label+": "+e.getMessage());
        }catch(IllegalArgumentException e){if(e instanceof CompareCatalog.MetadataLimitException)throw e;throw new IllegalArgumentException(label+": "+e.getMessage(),e);
        }finally{if(save!=null)try{c.releaseSavepoint(save);}catch(SQLException ignored){}}
    }
    static void populate(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node)throws Exception{
        String engine=str(out,"engine"),k=str(out,"kind");
        ObjectNode f=(ObjectNode)out.path("fields");
        ObjectNode choices=out.putObject("choices");
        optional(c,out,"Driver datatype metadata",()->{try(var rs=c.getMetaData().getTypeInfo()){detail(out,"Datatypes",rows(job,rs));}});
        if(engine.equals("postgresql")){
            optional(c,out,"Available schemas",()->choices.set("schema",query(job,c,"SELECT nspname AS name FROM pg_namespace WHERE has_schema_privilege(oid,'USAGE') ORDER BY nspname")));
            optional(c,out,"Available roles",()->choices.set("owner",query(job,c,"SELECT rolname AS name FROM pg_roles ORDER BY rolname")));
            optional(c,out,"Available languages",()->choices.set("language",query(job,c,"SELECT lanname AS name FROM pg_language ORDER BY lanname")));
            optional(c,out,"Available tablespaces",()->choices.set("tablespace",query(job,c,"SELECT spcname AS name FROM pg_tablespace ORDER BY spcname")));
            optional(c,out,"Available index methods",()->choices.set("method",query(job,c,"SELECT amname AS name FROM pg_am WHERE amtype='i' ORDER BY amname")));
            if(node==null){f.put("owner",query(job,c,"SELECT current_user AS name").path(0).path("name").asText());return;}
            postgres(job,c,out,node);return;
        }
        if(node==null)return;
        if(engine.equals("h2")||engine.equals("hsqldb"))embedded(job,c,out,node);
        else if(engine.equals("oracle"))OracleMetadata.populate(job,c,out,node);
        else nativeVendor(job,c,out,node);
        if(Set.of("views","materialized_views","foreign_tables","external_tables","tables").contains(k)){
            optional(c,out,"Columns",()->{var m=c.getMetaData();try(var rs=m.getColumns(c.getCatalog(),pattern(m,str(f,"schema")),pattern(m,str(f,"name")),null)){detail(out,"Columns",rows(job,rs));}});
            optional(c,out,"Permissions",()->{var m=c.getMetaData();try(var rs=m.getTablePrivileges(c.getCatalog(),pattern(m,str(f,"schema")),pattern(m,str(f,"name")))){detail(out,"Permissions",rows(job,rs));}});
        }
        if(!engine.equals("oracle")&&Set.of("functions","procedures").contains(k)){
            optional(c,out,"Parameters",()->{var m=c.getMetaData();try(var rs=k.equals("functions")?m.getFunctionColumns(c.getCatalog(),pattern(m,str(f,"schema")),pattern(m,str(f,"name")),null):m.getProcedureColumns(c.getCatalog(),pattern(m,str(f,"schema")),pattern(m,str(f,"name")),null)){detail(out,"Parameters",rows(job,rs));}});
        }
    }
    @FunctionalInterface interface Query {ArrayNode read(String sql,Object... args)throws Exception;}
    /** Definition evidence only: comparison must never load editor choices or JDBC type catalogs. */
    static ObjectNode comparison(QueryJobs.Job job,Connection c,String engine,JsonNode selection,JsonNode node)throws Exception{
        JsonNode parent=MetadataTree.request(selection.path("parent"));
        if(node==null)for(JsonNode candidate:MetadataTree.browse(job,c,parent,job.remainingSeconds()).path("nodes"))if(str(candidate,"key").equals(str(selection,"key"))){node=candidate;break;}
        if(node==null)throw new IllegalArgumentException("Object changed or is no longer on this page; compare again");
        ObjectNode out=Profiles.JSON.createObjectNode().put("engine",engine).put("kind",kind(parent)).put("ddl","").put("ddlComplete",false);
        out.set("target",parent);out.putObject("fields").put("name",node.path("objectName").asText(str(node,"name"))).put("schema",str(parent,"schema")).put("owner","").put("comment","");
        out.putObject("details");out.putArray("categories");out.putArray("warnings");
        Query query=(sql,args)->CompareCatalog.query(job,c,sql,args);
        if(engine.equals("postgresql"))postgres(job,c,out,node,query,false);
        else if(engine.equals("h2")||engine.equals("hsqldb"))embedded(job,c,out,node,query);
        else nativeVendor(job,c,out,node,query);
        CompareCatalog.limit(Profiles.JSON.writeValueAsBytes(out).length*2L,job.comparisonMetadataBytes,"Comparison object metadata");
        return out;
    }
    static String pattern(DatabaseMetaData m,String s)throws SQLException{String e=m.getSearchStringEscape();if(e==null||e.isEmpty())return s;return s.replace(e,e+e).replace("_",e+"_").replace("%",e+"%");}
    static void values(ObjectNode f,JsonNode row,String... names){for(int i=0;i<names.length;i+=2)if(row.has(names[i+1]))f.set(names[i],row.get(names[i+1]));}
    static String target(ObjectNode out){return ObjectForms.qualified(str(out,"engine"),str(out.path("fields"),"schema"),str(out.path("fields"),"name"));}
    static void postgres(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node)throws Exception{
        postgres(job,c,out,node,(sql,args)->query(job,c,sql,args),true);
    }
    private static void postgres(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node,Query query,boolean editorDetails)throws Exception{
        String k=str(out,"kind"),oid=str(node,"oid");ObjectNode f=(ObjectNode)out.path("fields");JsonNode parent=out.path("target");
        String catalog=switch(k){
            case "tables","foreign_tables","external_tables","views","materialized_views","indexes","sequences","partitions"->"pg_class";
            case "functions","procedures","aggregates"->"pg_proc";case "types","domains"->"pg_type";
            case "schemas"->"pg_namespace";case "roles"->"pg_roles";case "extensions"->"pg_extension";
            case "event_triggers"->"pg_event_trigger";case "tablespaces"->"pg_tablespace";case "foreign_servers"->"pg_foreign_server";
            case "triggers"->"pg_trigger";case "constraints"->"pg_constraint";case "policies"->"pg_policy";case "rules"->"pg_rewrite";
            case "databases"->"pg_database";default->"";
        };
        if(!catalog.isEmpty()&&!oid.isBlank()){
            JsonNode raw=query.read("SELECT to_jsonb(t)::text AS properties FROM pg_catalog."+catalog+" t WHERE oid=?::oid",oid).path(0);
            if(raw.isMissingNode())throw new IllegalArgumentException("Object no longer exists");
            JsonNode props=Profiles.JSON.readTree(str(raw,"properties"));detail(out,"Advanced",props);
            String nameKey=switch(catalog){case "pg_class"->"relname";case "pg_proc"->"proname";case "pg_type"->"typname";case "pg_namespace"->"nspname";case "pg_roles"->"rolname";case "pg_extension"->"extname";case "pg_event_trigger"->"evtname";case "pg_tablespace"->"spcname";case "pg_foreign_server"->"srvname";case "pg_trigger"->"tgname";case "pg_constraint"->"conname";case "pg_policy"->"polname";case "pg_rewrite"->"rulename";case "pg_database"->"datname";default->"";};
            f.put("name",str(props,nameKey));out.put("objectIdentity",oid);
            String ownerKey=switch(catalog){case "pg_class"->"relowner";case "pg_proc"->"proowner";case "pg_type"->"typowner";case "pg_namespace"->"nspowner";case "pg_extension"->"extowner";case "pg_event_trigger"->"evtowner";case "pg_tablespace"->"spcowner";case "pg_foreign_server"->"srvowner";case "pg_database"->"datdba";default->"";};
            if(!ownerKey.isBlank())f.put("owner",query.read("SELECT pg_get_userbyid(?::oid) AS name",props.path(ownerKey).asText()).path(0).path("name").asText());
            if(!Set.of("roles","databases","tablespaces").contains(k))f.put("comment",query.read("SELECT COALESCE(obj_description(?::oid,?),'') AS comment",oid,catalog).path(0).path("comment").asText());
            else f.put("comment",query.read("SELECT COALESCE(shobj_description(?::oid,?),'') AS comment",oid,catalog.equals("pg_roles")?"pg_authid":catalog).path(0).path("comment").asText());
            if(props.has("reloptions"))f.put("options",props.path("reloptions").isArray()?String.join("\n",strings(props.path("reloptions"))):"");
            if(props.has("relacl"))detail(out,"Permissions",props.path("relacl"));
            if(props.has("proacl"))detail(out,"Permissions",props.path("proacl"));
            if(props.has("typacl"))detail(out,"Permissions",props.path("typacl"));
            if(editorDetails)optional(c,out,"Dependencies",()->detail(out,"Dependencies",query.read("SELECT pg_describe_object(classid,objid,objsubid) AS dependent, pg_describe_object(refclassid,refobjid,refobjsubid) AS referenced, deptype FROM pg_depend WHERE (objid=?::oid AND classid=?::regclass) OR (refobjid=?::oid AND refclassid=?::regclass)",oid,"pg_catalog."+catalog,oid,"pg_catalog."+catalog)));
        }
        String t=target(out);
        if(k.equals("columns")){
            JsonNode row=query.read("SELECT a.attname AS name,format_type(a.atttypid,a.atttypmod) AS type,NOT a.attnotnull AS nullable,COALESCE(pg_get_expr(d.adbin,d.adrelid),'') AS default,COALESCE(col_description(a.attrelid,a.attnum),'') AS comment,a.attidentity::text AS identity,a.attgenerated::text AS generated FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid=?::oid AND a.attnum=?::int AND NOT a.attisdropped",str(parent,"oid"),oid).path(0);
            if(row.isMissingNode())throw new IllegalArgumentException("Column no longer exists");
            values(f,row,"name","name","type","type","default","default","comment","comment","identity","identity","generated","generated");f.put("nullable",truth(row.path("nullable")));
            out.put("ddl","ALTER TABLE "+ObjectForms.parentTable(out)+" ADD COLUMN "+ObjectForms.q("postgresql",str(f,"name"))+" "+str(f,"type")+(str(f,"default").isBlank()?"":" DEFAULT "+str(f,"default"))+(f.path("nullable").asBoolean()?"":" NOT NULL")+";");
            detail(out,"Advanced",row);
        }
        switch(k){
            case "sequences"->{
                JsonNode row=query.read("SELECT format_type(seqtypid,NULL) AS type,seqstart::text AS start,seqincrement::text AS increment,seqmin::text AS minimum,seqmax::text AS maximum,seqcache::text AS cache,seqcycle AS cycle FROM pg_sequence WHERE seqrelid=?::oid",oid).path(0);
                for(String key:List.of("type","start","increment","minimum","maximum","cache","cycle"))f.set(key,row.path(key));
                f.put("cycle",truth(row.path("cycle")));f.put("ownedBy",query.read("SELECT quote_ident(n.nspname)||'.'||quote_ident(t.relname)||'.'||quote_ident(a.attname) AS name FROM pg_depend d JOIN pg_class t ON t.oid=d.refobjid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid WHERE d.classid='pg_class'::regclass AND d.objid=?::oid AND d.deptype IN ('a','i')",oid).path(0).path("name").asText(""));
                out.put("ddl","CREATE SEQUENCE "+t+ObjectForms.sequenceOptions(f,true)+";").put("ddlComplete",true);
            }
            case "views","materialized_views"->{
                f.put("query",query.read("SELECT pg_get_viewdef(?::oid,true) AS definition",oid).path(0).path("definition").asText().replaceFirst(";\\s*$",""));
                JsonNode relation=query.read("SELECT COALESCE(s.spcname,'') AS tablespace,c.relispopulated FROM pg_class c LEFT JOIN pg_tablespace s ON s.oid=c.reltablespace WHERE c.oid=?::oid",oid).path(0);
                values(f,relation,"tablespace","tablespace");f.put("populate",truth(relation.path("relispopulated")));
                out.put("ddl","CREATE "+(k.equals("views")?"VIEW ":"MATERIALIZED VIEW ")+t+(str(f,"options").isBlank()?"":" WITH ("+str(f,"options").replace("\n",", ")+")")+" AS\n"+str(f,"query")+(k.equals("materialized_views")?(f.path("populate").asBoolean()?"\nWITH DATA":"\nWITH NO DATA"):"")+";");
                detail(out,"Columns",query.read("SELECT attname AS name,format_type(atttypid,atttypmod) AS datatype,attnotnull AS not_null,COALESCE(col_description(attrelid,attnum),'') AS comment FROM pg_attribute WHERE attrelid=?::oid AND attnum>0 AND NOT attisdropped ORDER BY attnum",oid));
                detail(out,"Indexes",query.read("SELECT c.relname AS name,pg_get_indexdef(i.indexrelid) AS definition FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE i.indrelid=?::oid",oid));
                detail(out,"Rules",query.read("SELECT rulename,pg_get_ruledef(oid) AS definition FROM pg_rewrite WHERE ev_class=?::oid",oid));
                if(k.equals("materialized_views")){MaterializedViewChanges.capture(job,c,out,oid,query);category(out,"Refresh");}
            }
            case "functions","procedures"->{
                JsonNode row=query.read("SELECT pg_get_functiondef(p.oid) AS ddl,pg_get_function_arguments(p.oid) AS parameters,pg_get_function_identity_arguments(p.oid) AS identity,pg_get_function_result(p.oid) AS returns,l.lanname AS language,p.prosrc AS body,p.probin AS library,p.provolatile::text AS volatility,p.proparallel::text AS parallel,p.prosecdef AS security_definer,p.proisstrict AS strict,p.proleakproof AS leakproof,p.procost::text AS cost,p.prorows::text AS rows,p.proretset AS returns_set,p.proconfig::text AS configuration,p.prosqlbody IS NOT NULL AS sql_body FROM pg_proc p JOIN pg_language l ON l.oid=p.prolang WHERE p.oid=?::oid",oid).path(0);
                values(f,row,"parameters","parameters","returns","returns","language","language","body","body","library","library","cost","cost","rows","rows");
                for(String key:List.of("security_definer","strict","leakproof","returns_set"))f.put(key,truth(row.path(key)));
                f.put("volatility",Map.of("i","IMMUTABLE","s","STABLE","v","VOLATILE").get(str(row,"volatility")));
                f.put("parallel",Map.of("s","SAFE","r","RESTRICTED","u","UNSAFE").get(str(row,"parallel")));
                JsonNode props=out.path("details").path("Advanced");f.put("configuration",String.join("\n",strings(props.path("proconfig"))));
                out.put("routineIdentity",str(row,"identity")).put("nativeBody",truth(row.path("sql_body"))||Set.of("c","internal").contains(str(f,"language")));
                out.put("ddl",str(row,"ddl")).put("ddlComplete",true);
            }
            case "indexes"->{
                JsonNode row=query.read("SELECT pg_get_indexdef(i.indexrelid) AS ddl,quote_ident(n.nspname)||'.'||quote_ident(t.relname) AS table_name,a.amname AS method,i.indisunique AS unique,i.indisvalid AS valid,COALESCE(pg_get_expr(i.indpred,i.indrelid),'') AS predicate,COALESCE(s.spcname,'') AS tablespace FROM pg_index i JOIN pg_class x ON x.oid=i.indexrelid JOIN pg_class t ON t.oid=i.indrelid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_am a ON a.oid=x.relam LEFT JOIN pg_tablespace s ON s.oid=x.reltablespace WHERE i.indexrelid=?::oid",oid).path(0);
                values(f,row,"table","table_name","method","method","predicate","predicate","tablespace","tablespace");f.put("unique",truth(row.path("unique")));
                f.put("columns",query.read("SELECT string_agg(pg_get_indexdef(?::oid,i,true),', ' ORDER BY i) AS definition FROM generate_series(1,(SELECT indnkeyatts FROM pg_index WHERE indexrelid=?::oid)) i",oid,oid).path(0).path("definition").asText());
                out.put("ddl",str(row,"ddl")+";").put("ddlComplete",true);
            }
            case "types","domains"->{
                JsonNode props=out.path("details").path("Advanced");String typtype=str(props,"typtype");
                if(typtype.equals("e")){f.put("labels",String.join("\n",query.read("SELECT enumlabel AS name FROM pg_enum WHERE enumtypid=?::oid ORDER BY enumsortorder",oid).findValuesAsText("name")));out.put("ddl","CREATE TYPE "+t+" AS ENUM ("+String.join(", ",stringsLines(str(f,"labels")).stream().map(TableDesigner::literal).toList())+");").put("ddlComplete",true);}
                if(typtype.equals("c"))detail(out,"Attributes",query.read("SELECT attname,format_type(atttypid,atttypmod) AS datatype FROM pg_attribute WHERE attrelid=?::oid AND attnum>0 AND NOT attisdropped ORDER BY attnum",str(props,"typrelid")));
                if(typtype.equals("d")){out.put("domain",true);f.put("type",query.read("SELECT format_type(typbasetype,typtypmod) AS type FROM pg_type WHERE oid=?::oid",oid).path(0).path("type").asText());f.put("default",str(props,"typdefault")).put("notNull",props.path("typnotnull").asBoolean());detail(out,"Constraints",query.read("SELECT conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE contypid=?::oid",oid));out.put("ddl","CREATE DOMAIN "+t+" AS "+str(f,"type")+(str(f,"default").isBlank()?"":" DEFAULT "+str(f,"default"))+(f.path("notNull").asBoolean()?" NOT NULL":"")+";");}
            }
            case "schemas"->out.put("ddl","CREATE SCHEMA "+ObjectForms.q("postgresql",str(f,"name"))+" AUTHORIZATION "+ObjectForms.q("postgresql",str(f,"owner"))+";").put("ddlComplete",true);
            case "extensions"->{JsonNode props=out.path("details").path("Advanced");f.put("version",str(props,"extversion"));out.put("ddl","CREATE EXTENSION "+ObjectForms.q("postgresql",str(f,"name"))+" VERSION "+TableDesigner.literal(str(f,"version"))+";");}
            case "triggers"->{JsonNode row=query.read("SELECT pg_get_triggerdef(oid,true) AS ddl,tgenabled::text AS enabled FROM pg_trigger WHERE oid=?::oid",oid).path(0);out.put("ddl",str(row,"ddl")+";").put("ddlComplete",true);f.put("enabled",str(row,"enabled"));}
            case "constraints"->{String definition=query.read("SELECT pg_get_constraintdef(?::oid,true) AS ddl",oid).path(0).path("ddl").asText();f.put("definition",definition);out.put("ddl","ALTER TABLE "+ObjectForms.qualified("postgresql",str(parent,"schema"),str(parent,"table"))+" ADD CONSTRAINT "+ObjectForms.q("postgresql",str(f,"name"))+" "+definition+";").put("ddlComplete",true);}
            case "rules"->out.put("ddl",query.read("SELECT pg_get_ruledef(?::oid,true) AS ddl",oid).path(0).path("ddl").asText()).put("ddlComplete",true);
            case "policies"->{JsonNode row=query.read("SELECT pg_get_expr(polqual,polrelid) AS using,pg_get_expr(polwithcheck,polrelid) AS check FROM pg_policy WHERE oid=?::oid",oid).path(0);values(f,row,"using","using","check","check");}
            default->{}
        }
        if(str(out,"ddl").isBlank())warning(out,"This object has no complete native DDL exporter. Its catalog properties are available in Advanced; use reviewed SQL in DDL for vendor-specific changes.");
    }
    static boolean truth(JsonNode n){return n.asBoolean()||Set.of("t","true","TRUE","YES").contains(n.asText());}
    static List<String> strings(JsonNode array){List<String> out=new ArrayList<>();if(array.isArray())for(JsonNode n:array)out.add(n.asText());return out;}
    static List<String> stringsLines(String s){return s.lines().filter(x->!x.isBlank()).toList();}
    static void embedded(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node)throws Exception{
        embedded(job,c,out,node,(sql,args)->query(job,c,sql,args));
    }
    private static void embedded(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node,Query query)throws Exception{
        String k=str(out,"kind"),s=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name");
        ObjectNode f=(ObjectNode)out.path("fields");String table=switch(k){case "sequences"->"SEQUENCES";case "views"->"VIEWS";case "domains"->"DOMAINS";case "indexes"->"INDEXES";case "triggers"->"TRIGGERS";case "functions","procedures"->"ROUTINES";case "schemas"->"SCHEMATA";default->"";};
        if(table.isEmpty())return;
        String prefix=switch(k){case "functions","procedures"->"ROUTINE";case "indexes"->"INDEX";case "schemas"->"SCHEMA";case "views"->"TABLE";default->table.substring(0,table.length()-1);};
        optional(c,out,"Native properties",()->{
            ArrayNode rows=k.equals("schemas")?query.read("SELECT * FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME=?",name):query.read("SELECT * FROM INFORMATION_SCHEMA."+table+" WHERE "+prefix+"_SCHEMA=? AND "+prefix+"_NAME=?",s,name);
            detail(out,"Advanced",rows);if(rows.isEmpty())return;JsonNode r=rows.get(0);
            f.put("comment",str(r,"remarks"));
            switch(k){
                case "sequences"->{values(f,r,"type","data_type","start","start_value","increment","increment","minimum","minimum_value","maximum","maximum_value","cache","cache");f.put("cycle",truth(r.path("cycle_option")));out.put("ddl","CREATE SEQUENCE "+target(out)+ObjectForms.sequenceOptions(f,true)+";").put("ddlComplete",true);}
                case "views"->{f.put("query",str(r,"view_definition"));out.put("ddl","CREATE VIEW "+target(out)+" AS\n"+str(f,"query")+";");}
                case "domains"->{values(f,r,"type","data_type","default","domain_default");out.put("ddl","CREATE DOMAIN "+target(out)+" AS "+str(f,"type")+(str(f,"default").isBlank()?"":" DEFAULT "+str(f,"default"))+";");}
                case "functions","procedures"->{values(f,r,"body","routine_definition","language","external_language");out.put("ddl",str(r,"routine_definition"));}
                case "schemas"->out.put("ddl","CREATE SCHEMA "+ObjectForms.q(str(out,"engine"),name)+";");
                default->{}
            }
        });
    }
    static void nativeVendor(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node)throws Exception{
        nativeVendor(job,c,out,node,(sql,args)->query(job,c,sql,args));
    }
    private static void nativeVendor(QueryJobs.Job job,Connection c,ObjectNode out,JsonNode node,Query query)throws Exception{
        String e=str(out,"engine"),k=str(out,"kind"),s=str(out.path("fields"),"schema"),name=str(out.path("fields"),"name"),t=target(out);
        optional(c,out,"Native definition",()->{
            if(e.equals("sqlite")){
                var r=query.read("SELECT * FROM "+ObjectForms.q(e,s)+".sqlite_schema WHERE name=?",name);detail(out,"Advanced",r);out.put("ddl",r.path(0).path("sql").asText("")).put("ddlComplete",true);
            }else if(e.equals("sqlserver")){
                var r=query.read("SELECT o.*,m.definition FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id LEFT JOIN sys.sql_modules m ON m.object_id=o.object_id WHERE s.name=? AND o.name=?",s,name);
                detail(out,"Advanced",r);out.put("ddl",r.path(0).path("definition").asText("")).put("ddlComplete",!r.path(0).path("definition").isNull());
            }else if(e.equals("mysql")||e.equals("mariadb")){
                String type=switch(k){case "views"->"VIEW";case "functions"->"FUNCTION";case "procedures"->"PROCEDURE";case "triggers"->"TRIGGER";case "events"->"EVENT";default->"TABLE";};
                var r=query.read("SHOW CREATE "+type+" "+t);detail(out,"Advanced",r);
                if(!r.isEmpty())for(var it=r.get(0).fields();it.hasNext();){var field=it.next();if(field.getKey().contains("create")&&field.getValue().isTextual())out.put("ddl",field.getValue().asText()).put("ddlComplete",true);}
            }else if(e.equals("snowflake")){
                var r=query.read("SELECT GET_DDL(?,?) AS ddl",label(k).toUpperCase(Locale.ROOT).replace(' ','_'),t);out.put("ddl",r.path(0).path("ddl").asText()).put("ddlComplete",true);
            }else if(e.equals("duckdb")){
                String function=switch(k){case "views"->"duckdb_views";case "indexes"->"duckdb_indexes";case "sequences"->"duckdb_sequences";case "functions"->"duckdb_functions";case "types"->"duckdb_types";default->"duckdb_tables";};
                String col=switch(k){case "views"->"view_name";case "indexes"->"index_name";case "sequences"->"sequence_name";case "functions"->"function_name";case "types"->"type_name";default->"table_name";};
                var r=query.read("SELECT * FROM "+function+"() WHERE schema_name=? AND "+col+"=?",s,name);detail(out,"Advanced",r);out.put("ddl",r.path(0).path("sql").asText(""));
            }else if(e.equals("db2")){
                String table=switch(k){case "views"->"VIEWS";case "functions","procedures"->"ROUTINES";case "triggers"->"TRIGGERS";case "sequences"->"SEQUENCES";default->"";};
                String prefix=switch(k){case "views"->"VIEW";case "functions","procedures"->"ROUTINE";case "triggers"->"TRIG";default->"SEQ";};
                if(!table.isBlank()){var r=query.read("SELECT * FROM SYSCAT."+table+" WHERE "+prefix+"SCHEMA=? AND "+prefix+"NAME=?",s,name);detail(out,"Advanced",r);out.put("ddl",r.path(0).path("text").asText(""));}
            }
        });
    }
    private ObjectCatalog(){}
}
