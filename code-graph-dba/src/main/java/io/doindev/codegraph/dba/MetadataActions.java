package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

/** Browser-only object actions. Never execute a client-supplied identifier or SQL fragment. */
final class MetadataActions {
    static final Set<String> OBJECT_PARENTS=Set.of("schemas","databases","tables","foreign_tables","views","materialized_views","external_tables","indexes","functions","procedures","sequences","types","aggregates","event_triggers","extensions","roles","tablespaces","foreign_servers","relation","domains","triggers","events","queues","packages","synonyms","schema_triggers","table_triggers","database_links","java","scheduled_jobs","jobs","scheduler_jobs","scheduler_programs","scheduler_schedules","scheduler_chains","aliases","stages","file_formats","pipes","tasks","streams");
    record Plan(String name,String type,String target,String drop,String rename,String reason,String fingerprint,String truncate,String refresh,List<MaterializedViewSchedules.Command> deleteCleanup,List<String> deleteWarnings) {
        Plan(String name,String type,String target,String drop,String rename,String reason,String fingerprint){this(name,type,target,drop,rename,reason,fingerprint,"","",List.of(),List.of());}
        Plan(String name,String type,String target,String drop,String rename,String reason,String fingerprint,String truncate,String refresh){this(name,type,target,drop,rename,reason,fingerprint,truncate,refresh,List.of(),List.of());}
        ObjectNode json(String database){
            ObjectNode out=Profiles.JSON.createObjectNode().put("name",name).put("type",type).put("target",target).put("canDelete",!drop.isEmpty()).put("canRename",!rename.isEmpty()).put("canTruncate",!truncate.isEmpty()).put("canRefresh",!refresh.isEmpty()).put("truncateSql",truncate).put("refreshSql",refresh).put("reason",reason).put("fingerprint",fingerprint);
            ArrayNode commands=out.putArray("deleteCommands"),details=out.putArray("deleteCommandDetails");List<String> preview=new ArrayList<>();
            for(MaterializedViewSchedules.Command command:deleteCleanup){commands.add(command.sql());details.addObject().put("sql",command.sql()).put("database",command.database()).put("phase",command.phase()).put("purpose",command.purpose());preview.add("-- Database: "+command.database()+" | "+command.purpose()+"\n"+command.sql());}
            if(!drop.isBlank()){commands.add(drop);details.addObject().put("sql",drop).put("database",database).put("phase","object").put("purpose","Delete the selected object");preview.add("-- Database: "+database+" | Delete the selected object\n"+drop);}
            out.put("deleteSql",preview.isEmpty()?"":String.join(";\n\n",preview)+";");ArrayNode warnings=out.putArray("deleteWarnings");deleteWarnings.forEach(warnings::add);return out;
        }
    }
    static Plan withDeleteCleanup(Plan plan,List<MaterializedViewSchedules.Command> cleanup,List<String> extraWarnings){
        List<String> warnings=new ArrayList<>(plan.deleteWarnings());warnings.addAll(extraWarnings);
        String fingerprint=HexFormat.of().formatHex(digest((plan.fingerprint()+"\n"+cleanup+"\n"+warnings).getBytes(StandardCharsets.UTF_8)));
        return new Plan(plan.name(),plan.type(),plan.target(),plan.drop(),plan.rename(),plan.reason(),fingerprint,plan.truncate(),plan.refresh(),List.copyOf(cleanup),List.copyOf(warnings));
    }
    static ObjectNode request(JsonNode input){
        if(!input.path("parent").isObject())throw new IllegalArgumentException("Object parent is required");
        ObjectNode out=Profiles.JSON.createObjectNode();out.set("parent",MetadataTree.request(input.path("parent")));
        out.put("key",Profiles.text(input,"key",4096));return out;
    }
    static Plan resolve(QueryJobs.Job job,Connection c,JsonNode selection,int timeout)throws Exception{
        if(selection.path("parent").path("kind").asText().equals("scheduled_jobs"))return ScheduledJobEditor.actionPlan(job,c,selection);
        JsonNode parent=selection.path("parent");String group=parent.path("kind").asText();if(group.equals("table_columns"))group="relation";
        if(group.startsWith("table_"))group=switch(group){case "table_indexes"->"indexes";case "table_constraints","table_foreign_keys"->"constraints";case "table_triggers"->"triggers";case "table_policies"->"policies";case "table_rules"->"rules";case "table_partitions"->"tables";default->group;};
        if(!OBJECT_PARENTS.contains(group)&&!Set.of("constraints","policies","rules").contains(group))throw new IllegalArgumentException("This tree item is a grouping, not a database object");
        JsonNode found=null;for(JsonNode node:MetadataTree.browse(job,c,parent,timeout).path("nodes"))if(node.path("key").asText().equals(selection.path("key").asText())){found=node;break;}
        if(found==null)throw new IllegalArgumentException("Object changed or is no longer on this page; refresh its parent and try again");
        String product=c.getMetaData().getDatabaseProductName(),engine=product.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(product);
        String name=found.path("objectName").asText(found.path("name").asText()),schema=found.path("schema").asText(),type=type(group),target="",drop="",rename="";
        boolean pg=engine.equals("postgresql");
        String columnTable="";
        if(pg){
            String oid=found.path("oid").asText();String[] raw=switch(group){
                case "schemas" -> lookup(job,c,"SELECT nspname FROM pg_catalog.pg_namespace WHERE oid=?::oid",timeout,oid);
                case "databases" -> lookup(job,c,"SELECT datname FROM pg_catalog.pg_database WHERE oid=?::oid",timeout,oid);
                case "tables","foreign_tables","views","materialized_views","indexes","sequences" -> lookup(job,c,"SELECT relname FROM pg_catalog.pg_class WHERE oid=?::oid",timeout,oid);
                case "functions","procedures","aggregates" -> lookup(job,c,"SELECT proname,pg_catalog.pg_get_function_identity_arguments(oid) FROM pg_catalog.pg_proc WHERE oid=?::oid",timeout,oid);
                case "types" -> lookup(job,c,"SELECT typname,typtype::text FROM pg_catalog.pg_type WHERE oid=?::oid",timeout,oid);
                case "extensions" -> lookup(job,c,"SELECT extname FROM pg_catalog.pg_extension WHERE oid=?::oid",timeout,oid);
                case "event_triggers" -> lookup(job,c,"SELECT evtname FROM pg_catalog.pg_event_trigger WHERE oid=?::oid",timeout,oid);
                case "roles" -> lookup(job,c,"SELECT rolname FROM pg_catalog.pg_roles WHERE oid=?::oid",timeout,oid);
                case "tablespaces" -> lookup(job,c,"SELECT spcname FROM pg_catalog.pg_tablespace WHERE oid=?::oid",timeout,oid);
                case "foreign_servers" -> lookup(job,c,"SELECT srvname FROM pg_catalog.pg_foreign_server WHERE oid=?::oid",timeout,oid);
                case "constraints" -> lookup(job,c,"SELECT conname FROM pg_catalog.pg_constraint WHERE oid=?::oid",timeout,oid);
                case "triggers" -> lookup(job,c,"SELECT tgname FROM pg_catalog.pg_trigger WHERE oid=?::oid",timeout,oid);
                case "policies" -> lookup(job,c,"SELECT polname FROM pg_catalog.pg_policy WHERE oid=?::oid",timeout,oid);
                case "rules" -> lookup(job,c,"SELECT rulename FROM pg_catalog.pg_rewrite WHERE oid=?::oid",timeout,oid);
                case "relation" -> lookup(job,c,"SELECT a.attname,c.relname,c.relkind::text FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid WHERE a.attrelid=?::oid AND a.attnum=?::int AND NOT a.attisdropped",timeout,parent.path("oid").asText(),oid);
                default -> new String[]{name};
            };
            name=raw[0];target=qualified(engine,schema,name);
            if(group.equals("types")&&raw[1].equals("d"))type="DOMAIN";
            if(Set.of("schemas","databases","extensions","event_triggers","roles","tablespaces","foreign_servers").contains(group))target=quote(engine,name);
            if(Set.of("functions","procedures","aggregates").contains(group))target+="("+raw[1]+")";
            if(group.equals("relation")){
                columnTable=qualified(engine,schema,raw[1]);
                if(!Set.of("r","p","f").contains(raw[2]))type=""; // Do not guess view-column DDL.
            }
        }else{
            if(Set.of("mysql","mariadb").contains(engine)&&group.equals("indexes"))name=lookup(job,c,"SELECT INDEX_NAME FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=? AND CONCAT(TABLE_NAME,'.',INDEX_NAME)=?",timeout,schema,found.path("oid").asText())[0];
            if(engine.equals("sqlserver")&&group.equals("indexes"))name=lookup(job,c,"SELECT name FROM sys.indexes WHERE CONCAT(object_id,':',index_id)=?",timeout,found.path("oid").asText())[0];
            if(engine.equals("duckdb")&&group.equals("functions"))name=lookup(job,c,"SELECT function_name FROM duckdb_functions() WHERE CAST(function_oid AS VARCHAR) || ':' || COALESCE(array_to_string(parameter_types,','),'')=? AND schema_name=?",timeout,found.path("oid").asText(),schema)[0];
            if(engine.equals("snowflake")&&Set.of("functions","procedures").contains(group)){
                String stem=group.equals("functions")?"FUNCTION":"PROCEDURE";
                name=lookup(job,c,"SELECT "+stem+"_NAME FROM INFORMATION_SCHEMA."+group.toUpperCase(Locale.ROOT)+" WHERE "+stem+"_SCHEMA=? AND "+stem+"_NAME || ARGUMENT_SIGNATURE=?",timeout,schema,found.path("oid").asText())[0];
            }
            if(engine.equals("sqlite")&&group.equals("relation"))name=found.path("oid").asText();
            // Catalog display labels containing signatures/table prefixes must never become identifiers.
            boolean simple=Set.of("schemas","tables","views","materialized_views","external_tables","sequences","domains","triggers","events","types","packages","synonyms","schema_triggers","table_triggers","stages","file_formats","pipes","tasks","streams").contains(group)
                ||group.equals("indexes")&&!Set.of("mysql","mariadb","sqlserver").contains(engine);
            if(!simple)type="";
            target=group.equals("schemas")?quote(engine,name):qualified(engine,schema,name);
            if(group.equals("relation")&&!engine.equals("sqlite")){
                // JDBC column names are raw; SQLite's existing labels include type decorations.
                columnTable=qualified(engine,schema,parent.path("table").asText(parent.path("name").asText()));type="COLUMN";
            }
        }
        String reason="This driver/object type has no verified tree-action adapter. Use the SQL editor if appropriate.";
        if(!type.isEmpty()){
            if(pg){drop=type.equals("COLUMN")?"ALTER TABLE "+columnTable+" DROP COLUMN "+quote(engine,name)+" RESTRICT":"DROP "+type+" "+target+(Set.of("DATABASE","ROLE","TABLESPACE","EVENT TRIGGER").contains(type)?"":" RESTRICT");
                if(!Set.of("DATABASE","EXTENSION").contains(type))rename=type.equals("COLUMN")?"ALTER TABLE "+columnTable+" RENAME COLUMN "+quote(engine,name)+" TO ":"ALTER "+type+" "+target+" RENAME TO ";
            }else{
                Set<String> supported=switch(engine){
                    case "h2","hsqldb" -> Set.of("SCHEMA","TABLE","VIEW","INDEX","SEQUENCE","DOMAIN","TRIGGER","COLUMN");
                    case "sqlite" -> Set.of("TABLE","VIEW","INDEX","TRIGGER");
                    case "duckdb" -> Set.of("SCHEMA","TABLE","VIEW","INDEX","SEQUENCE","TYPE","COLUMN");
                    case "mysql","mariadb" -> Set.of("TABLE","VIEW","TRIGGER","EVENT","COLUMN");
                    case "oracle" -> Set.of("TABLE","VIEW","MATERIALIZED VIEW","INDEX","SEQUENCE","TYPE","PACKAGE","SYNONYM","TRIGGER","COLUMN");
                    case "sqlserver" -> Set.of("SCHEMA","TABLE","VIEW","SEQUENCE","TYPE","SYNONYM","TRIGGER","COLUMN");
                    case "db2" -> Set.of("SCHEMA","TABLE","VIEW","MATERIALIZED VIEW","INDEX","SEQUENCE","TYPE","TRIGGER","COLUMN");
                    case "snowflake" -> Set.of("SCHEMA","TABLE","VIEW","MATERIALIZED VIEW","EXTERNAL TABLE","SEQUENCE","STAGE","FILE FORMAT","PIPE","TASK","STREAM","COLUMN");
                    default -> Set.of();
                };
                if(supported.contains(type)){
                    drop=type.equals("COLUMN")?"ALTER TABLE "+columnTable+" DROP COLUMN "+quote(engine,name):engine.equals("db2")&&type.equals("MATERIALIZED VIEW")?"DROP TABLE "+target:"DROP "+type+" "+target;
                    if(type.equals("SCHEMA")||Set.of("h2","hsqldb","duckdb","snowflake").contains(engine)&&Set.of("TABLE","VIEW","DOMAIN").contains(type))drop+=" RESTRICT";
                    if(type.equals("TABLE"))rename=engine.equals("db2")?"RENAME TABLE "+target+" TO ":"ALTER TABLE "+target+" RENAME TO ";
                    if(engine.equals("h2")&&Set.of("SCHEMA","VIEW","INDEX","DOMAIN").contains(type))rename="ALTER "+type+" "+target+" RENAME TO ";
                    if(type.equals("COLUMN"))rename="ALTER TABLE "+columnTable+" RENAME COLUMN "+quote(engine,name)+" TO ";
                    // Rename has no portable JDBC capability flag. Enable only validated dialect/type
                    // pairs; never infer it from the mere existence of an ALTER or DROP command.
                    if(!engine.equals("h2"))rename="";
                }
            }
        }
        if(pg&&Set.of("constraints","triggers","policies","rules").contains(group)){
            type=switch(group){case "constraints"->"CONSTRAINT";case "triggers"->"TRIGGER";case "policies"->"POLICY";default->"RULE";};
            String ownerTable=qualified(engine,schema,parent.path("table").asText());
            if(parent.path("table").asText().isBlank())throw new IllegalArgumentException("Open this object beneath its owning table");
            target=quote(engine,name)+" ON "+ownerTable;
            drop=group.equals("constraints")?"ALTER TABLE "+ownerTable+" DROP CONSTRAINT "+quote(engine,name)+" RESTRICT":"DROP "+type+" "+target+" RESTRICT";
            rename=group.equals("constraints")?"ALTER TABLE "+ownerTable+" RENAME CONSTRAINT "+quote(engine,name)+" TO ":"ALTER "+type+" "+target+" RENAME TO ";
        }
        if(!drop.isEmpty()||!rename.isEmpty())reason="The database may reject this action due to permissions or dependencies. No CASCADE or FORCE is added. DDL may commit immediately and affect dependent objects.";
        if(group.equals("databases"))reason="Catalog database Rename is unavailable. Rename the root connection to change its display name without renaming the database.";
        List<MaterializedViewSchedules.Command> cleanup=group.equals("materialized_views")?MaterializedViewSchedules.deleteCleanup(job,c,engine,Objects.toString(c.getCatalog(),""),schema,name):List.of();
        if(pg&&group.equals("materialized_views")&&!cleanup.isEmpty()){rename="";reason="Rename this materialized view in its Properties tab so the managed pg_cron target is updated in the same reviewed plan.";}
        List<String> deleteWarnings=group.equals("materialized_views")?List.of("Recognized code-graph-managed schedule jobs and helper procedures are removed before the view. Other scheduler jobs are read-only here and remain unchanged."):List.of();
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((selection+"\n"+c.getCatalog()+"\n"+engine+"\n"+name+"\n"+drop+"\n"+rename+"\n"+cleanup).getBytes(StandardCharsets.UTF_8)));
        String truncate=group.equals("tables")&&Set.of("postgresql","h2","hsqldb","duckdb","mysql","mariadb","oracle","sqlserver","db2","snowflake").contains(engine)?"TRUNCATE TABLE "+target+(pg?" CONTINUE IDENTITY RESTRICT":engine.equals("h2")?" CONTINUE IDENTITY":""):"";
        String refresh=group.equals("materialized_views")?switch(engine){case "postgresql"->"REFRESH MATERIALIZED VIEW "+target;case "oracle"->"BEGIN DBMS_MVIEW.REFRESH("+TableDesigner.literal(target)+"); END;";case "db2"->"REFRESH TABLE "+target;default->"";}:"";
        return new Plan(name,type,target,drop,rename,reason,fingerprint,truncate,refresh,cleanup,deleteWarnings);
    }
    static String command(Plan plan,String engine,String action,JsonNode input){
        if(!input.path("fingerprint").asText().equals(plan.fingerprint()))throw new IllegalArgumentException("Object changed since the dialog opened; refresh and review the action again");
        if(Set.of("delete","truncate","refresh").contains(action)){
            if(!input.path("confirmed").isBoolean()||!input.path("confirmed").asBoolean())throw new IllegalArgumentException("Explicit destructive-action confirmation is required");
            String sql=switch(action){case "truncate"->plan.truncate();case "refresh"->plan.refresh();default->plan.drop();};
            if(sql.isEmpty())throw new IllegalArgumentException("This database does not provide a supported "+action+" operation for this object");return sql;
        }
        if(!action.equals("rename")||plan.rename().isEmpty())throw new IllegalArgumentException("Rename is unsupported for this object");
        String name=Profiles.text(input,"newName",256);if(name.indexOf('\0')>=0||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Names cannot contain control characters");
        int limit=engine.equals("postgresql")?63:Set.of("mysql","mariadb").contains(engine)?64:engine.equals("oracle")?128:256;
        if(name.getBytes(StandardCharsets.UTF_8).length>limit)throw new IllegalArgumentException("New name exceeds this database's identifier limit ("+limit+" bytes)");
        return plan.rename()+quote(engine,name);
    }
    private static String type(String group){return switch(group){case "schemas"->"SCHEMA";case "databases"->"DATABASE";case "tables"->"TABLE";case "foreign_tables"->"FOREIGN TABLE";case "views"->"VIEW";case "materialized_views"->"MATERIALIZED VIEW";case "external_tables"->"EXTERNAL TABLE";case "indexes"->"INDEX";case "sequences"->"SEQUENCE";case "functions"->"FUNCTION";case "procedures"->"PROCEDURE";case "aggregates"->"AGGREGATE";case "types"->"TYPE";case "domains"->"DOMAIN";case "extensions"->"EXTENSION";case "event_triggers"->"EVENT TRIGGER";case "roles"->"ROLE";case "tablespaces"->"TABLESPACE";case "foreign_servers"->"SERVER";case "relation"->"COLUMN";case "triggers","schema_triggers","table_triggers"->"TRIGGER";case "events"->"EVENT";case "packages"->"PACKAGE";case "synonyms"->"SYNONYM";case "stages"->"STAGE";case "file_formats"->"FILE FORMAT";case "pipes"->"PIPE";case "tasks"->"TASK";case "streams"->"STREAM";default->"";};}
    static String quote(String engine,String name){if(name==null||name.isEmpty()||name.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid object identifier");String q=Set.of("mysql","mariadb").contains(engine)?"`":"\"";return q+name.replace(q,q+q)+q;}
    private static byte[] digest(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static String qualified(String engine,String schema,String name){return schema.isEmpty()?quote(engine,name):quote(engine,schema)+"."+quote(engine,name);}
    private static String[] lookup(QueryJobs.Job job,Connection c,String sql,int timeout,String... values)throws Exception{
        try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(timeout);for(int i=0;i<values.length;i++)st.setString(i+1,values[i]);try(ResultSet rs=st.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Object no longer exists; refresh its parent");String[] row=new String[rs.getMetaData().getColumnCount()];for(int i=0;i<row.length;i++)row[i]=rs.getString(i+1);return row;}}finally{job.statement=null;}
    }
}
