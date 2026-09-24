package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.MetadataTree.*;

/** Fixed, read-only catalog adapters. Identifiers from the browser are always bound values. */
final class VendorMetadata {
    static String engine(String product){String p=product.toLowerCase(Locale.ROOT);if(p.contains("oracle"))return "oracle";if(p.contains("microsoft"))return "sqlserver";if(p.contains("db2"))return "db2";if(p.contains("hsql"))return "hsqldb";for(String e:List.of("mariadb","mysql","snowflake","sqlite","duckdb","h2"))if(p.contains(e))return e;return "jdbc";}
    static String[] categories(String engine){String[] result=switch(engine){
        case "oracle" -> new String[]{"Tables","Views","Materialized Views","Indexes","Sequences","Queues","Types","Packages","Procedures","Functions","Synonyms","Schema Triggers","Table Triggers","Database Links","Java","Jobs","Scheduler"};
        case "db2" -> new String[]{"Tables","Views","Materialized Views","Indexes","Sequences","Types","Packages","Procedures","Functions","Aliases","Triggers"};
        case "sqlserver" -> new String[]{"Tables","Views","Indexes","Sequences","Types","Procedures","Functions","Synonyms","Triggers"};
        case "mysql","mariadb" -> new String[]{"Tables","Views","Indexes","Procedures","Functions","Triggers","Events"};
        case "snowflake" -> new String[]{"Tables","Views","Materialized Views","External Tables","Sequences","Functions","Procedures","Stages","File Formats","Pipes","Tasks","Streams"};
        case "sqlite" -> new String[]{"Tables","Views","Indexes","Triggers"};
        case "duckdb" -> new String[]{"Tables","Views","Indexes","Sequences","Functions","Types"};
        case "h2","hsqldb" -> new String[]{"Tables","Views","Indexes","Sequences","Functions","Procedures","Triggers","Domains"};
        default -> new String[]{"Tables","Views","Functions","Procedures","Types"};
    };Arrays.sort(result,String.CASE_INSENSITIVE_ORDER);return result;}
    static String kind(String label){return label.toLowerCase(Locale.ROOT).replace(' ','_');}
    static boolean catalogs(String engine){return Set.of("mysql","mariadb","sqlserver","snowflake").contains(engine);}
    static ObjectNode browse(QueryJobs.Job job,Connection c,JsonNode r,ObjectNode out,int timeout)throws Exception{
        String e=r.path("genericOnly").asBoolean()?"jdbc":engine(c.getMetaData().getDatabaseProductName()),k=r.path("kind").asText("root"),db=r.path("database").asText(c.getCatalog()),s=r.path("schema").asText("");out.put("engine",e);ArrayNode nodes=(ArrayNode)out.path("nodes");
        if(k.equals("root")){node(nodes,catalogs(e)?"databases":"schemas",catalogs(e)?"Databases":"Schemas",true,"","");return out;}
        if(k.equals("database")){if(e.equals("mysql")||e.equals("mariadb"))addGroups(nodes,e,db,db);else node(nodes,"schemas","Schemas",true,db,"");return out;}
        if(k.equals("schema")){addGroups(nodes,e,db,s);return out;}
        if(k.equals("object"))return out;
        if(k.equals("scheduler")){for(String label:List.of("Scheduler Chains","Scheduler Jobs","Scheduler Programs","Scheduler Schedules"))node(nodes,kind(label),label.substring(10),true,db,s);return out;}
        if(e.equals("oracle")&&!k.equals("relation")){oracle(job,c,r,out,timeout);return out;}
        if(e.equals("sqlite")){sqlite(job,c,r,out,timeout);return out;}
        if(Set.of("schemas","databases","relation").contains(k))return jdbc(job,c,r,out,e);
        String sql=catalogSql(e,k);
        if(sql!=null){query(job,c,out,sql,List.of(s),relation(k)?"relation":"object",relation(k),db,s,timeout);return out;}
        if(e.equals("snowflake")&&Set.of("tasks","streams").contains(k)){
            // SHOW requires an identifier, not a bind marker. Quote every code point, including embedded quotes.
            String qualified="\""+db.replace("\"","\"\"")+"\".\""+s.replace("\"","\"\"")+"\"";
            try(Statement statement=c.createStatement()){job.statement=statement;statement.setQueryTimeout(timeout);try(ResultSet rs=statement.executeQuery("SHOW "+k.toUpperCase(Locale.ROOT)+" IN SCHEMA "+qualified)){read(job,rs,out,"name","object",false,db,s);}}finally{job.statement=null;}return out;
        }
        return jdbc(job,c,r,out,e);
    }
    private static boolean relation(String kind){return Set.of("tables","views","materialized_views","external_tables").contains(kind);}
    private static void addGroups(ArrayNode nodes,String e,String db,String s){for(String label:categories(e))node(nodes,kind(label),label,true,db,s);}
    private static void oracle(QueryJobs.Job job,Connection c,JsonNode r,ObjectNode out,int timeout)throws Exception{
        String k=r.path("kind").asText(),s=r.path("schema").asText(""),db=r.path("database").asText("");String sql;
        if(k.equals("databases")){var target=OracleDialect.target(job,c,timeout);node((ArrayNode)out.path("nodes"),"database",target.database(),true,target.database(),"");return;}
        if(k.equals("schemas")){query(job,c,out,"SELECT username AS node_id,username AS node_name FROM all_users ORDER BY username",List.of(),"schema",true,db,"",timeout);return;}
        sql=switch(k){
            case "queues" -> "SELECT name AS node_id,name AS node_name FROM all_queues WHERE owner=? ORDER BY name";
            case "synonyms" -> "SELECT synonym_name AS node_id,synonym_name AS node_name FROM all_synonyms WHERE owner=? ORDER BY synonym_name";
            case "schema_triggers" -> "SELECT trigger_name AS node_id,trigger_name AS node_name FROM all_triggers WHERE owner=? AND base_object_type IN ('SCHEMA','DATABASE') ORDER BY trigger_name";
            case "table_triggers" -> "SELECT trigger_name AS node_id,trigger_name AS node_name FROM all_triggers WHERE owner=? AND base_object_type IN ('TABLE','VIEW') ORDER BY trigger_name";
            case "database_links" -> "SELECT db_link AS node_id,db_link AS node_name FROM all_db_links WHERE owner=? ORDER BY db_link";
            case "jobs" -> "SELECT TO_CHAR(job),TO_CHAR(job) FROM all_jobs WHERE schema_user=? ORDER BY job";
            case "scheduler_jobs" -> "SELECT job_name AS node_id,job_name AS node_name FROM all_scheduler_jobs WHERE owner=? ORDER BY job_name";
            case "scheduler_programs" -> "SELECT program_name AS node_id,program_name AS node_name FROM all_scheduler_programs WHERE owner=? ORDER BY program_name";
            case "scheduler_schedules" -> "SELECT schedule_name AS node_id,schedule_name AS node_name FROM all_scheduler_schedules WHERE owner=? ORDER BY schedule_name";
            case "scheduler_chains" -> "SELECT chain_name AS node_id,chain_name AS node_name FROM all_scheduler_chains WHERE owner=? ORDER BY chain_name";
            default -> null;
        };
        if(sql!=null){query(job,c,out,sql,List.of(s),"object",false,db,s,timeout);return;}
        String types=switch(k){case "tables"->"'TABLE'";case "views"->"'VIEW'";case "materialized_views"->"'MATERIALIZED VIEW'";case "indexes"->"'INDEX'";case "sequences"->"'SEQUENCE'";case "types"->"'TYPE'";case "packages"->"'PACKAGE'";case "procedures"->"'PROCEDURE'";case "functions"->"'FUNCTION'";case "java"->"'JAVA SOURCE','JAVA CLASS','JAVA RESOURCE'";default->throw new IllegalArgumentException("Unsupported Oracle catalog branch");};
        query(job,c,out,"SELECT TO_CHAR(object_id),object_name FROM all_objects WHERE owner=? AND object_type IN ("+types+") AND subobject_name IS NULL ORDER BY object_name,object_id",List.of(s),relation(k)?"relation":"object",relation(k),db,s,timeout);
    }
    /** Only application constants are used to compose catalog SQL. */
    static String catalogSql(String e,String k){
        if(e.equals("db2")){String[] spec=switch(k){case "tables"->new String[]{"TABLES","TABNAME","TABSCHEMA"," AND TYPE='T'"};case "views"->new String[]{"VIEWS","VIEWNAME","VIEWSCHEMA",""};case "materialized_views"->new String[]{"TABLES","TABNAME","TABSCHEMA"," AND TYPE='S'"};case "indexes"->new String[]{"INDEXES","INDNAME","INDSCHEMA",""};case "sequences"->new String[]{"SEQUENCES","SEQNAME","SEQSCHEMA",""};case "packages"->new String[]{"PACKAGES","PKGNAME","PKGSCHEMA",""};case "types"->new String[]{"DATATYPES","TYPENAME","TYPESCHEMA",""};case "aliases"->new String[]{"TABLES","TABNAME","TABSCHEMA"," AND TYPE='A'"};case "triggers"->new String[]{"TRIGGERS","TRIGNAME","TRIGSCHEMA",""};case "functions","procedures"->new String[]{"ROUTINES","SPECIFICNAME","ROUTINESCHEMA",k.equals("functions")?" AND ROUTINETYPE='F'":" AND ROUTINETYPE='P'"};default->null;};return spec==null?null:select("SYSCAT."+spec[0],spec[1],spec[2],spec[3]);}
        if(e.equals("sqlserver")){
            if(k.equals("indexes"))return "SELECT CONCAT(i.object_id,':',i.index_id),CONCAT(o.name,'.',i.name) FROM sys.indexes i JOIN sys.objects o ON o.object_id=i.object_id JOIN sys.schemas s ON s.schema_id=o.schema_id WHERE s.name=? AND i.name IS NOT NULL ORDER BY o.name,i.name";
            if(k.equals("types"))return "SELECT CAST(t.user_type_id AS varchar(32)),t.name FROM sys.types t JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? ORDER BY t.name";
            String types=switch(k){case "tables"->"'U'";case "views"->"'V'";case "sequences"->"'SO'";case "procedures"->"'P','PC'";case "functions"->"'FN','IF','TF','FS','FT'";case "synonyms"->"'SN'";case "triggers"->"'TR','TA'";default->null;};return types==null?null:"SELECT CAST(o.object_id AS varchar(32)),o.name FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id WHERE s.name=? AND o.type IN ("+types+") ORDER BY o.name,o.object_id";
        }
        if(Set.of("mysql","mariadb").contains(e)&&k.equals("indexes"))return "SELECT DISTINCT CONCAT(TABLE_NAME,'.',INDEX_NAME),CONCAT(TABLE_NAME,'.',INDEX_NAME) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA=? ORDER BY 1";
        if(e.equals("duckdb"))return switch(k){case "indexes"->"SELECT index_name,index_name FROM duckdb_indexes() WHERE schema_name=? AND database_name=current_database() ORDER BY index_name";case "sequences"->"SELECT sequence_name,sequence_name FROM duckdb_sequences() WHERE schema_name=? AND database_name=current_database() ORDER BY sequence_name";case "types"->"SELECT COALESCE(CAST(type_oid AS VARCHAR),'builtin') || ':' || type_name,type_name FROM duckdb_types() WHERE schema_name=? AND database_name=current_database() ORDER BY type_name,type_oid";case "functions"->"SELECT CAST(function_oid AS VARCHAR) || ':' || COALESCE(array_to_string(parameter_types,','),''),function_name || '(' || COALESCE(array_to_string(parameter_types,', '),'') || ')' FROM duckdb_functions() WHERE schema_name=? ORDER BY function_name,function_oid,parameter_types";default->null;};
        if(e.equals("snowflake")){String[] spec=switch(k){case "stages"->new String[]{"STAGES","STAGE_NAME","STAGE_SCHEMA",""};case "file_formats"->new String[]{"FILE_FORMATS","FILE_FORMAT_NAME","FILE_FORMAT_SCHEMA",""};case "pipes"->new String[]{"PIPES","PIPE_NAME","PIPE_SCHEMA",""};case "materialized_views"->new String[]{"TABLES","TABLE_NAME","TABLE_SCHEMA"," AND TABLE_TYPE='MATERIALIZED VIEW'"};case "external_tables"->new String[]{"EXTERNAL_TABLES","TABLE_NAME","TABLE_SCHEMA",""};case "functions","procedures"->new String[]{k.toUpperCase(Locale.ROOT),k.equals("functions")?"FUNCTION_NAME":"PROCEDURE_NAME",k.equals("functions")?"FUNCTION_SCHEMA":"PROCEDURE_SCHEMA",""};default->null;};if(spec!=null){String id=spec[1];if(Set.of("functions","procedures").contains(k))id+=" || ARGUMENT_SIGNATURE";return select("INFORMATION_SCHEMA."+spec[0],id,spec[2],spec[3]);}}
        if(Set.of("h2","hsqldb","mysql","mariadb","snowflake").contains(e))return switch(k){
            case "sequences" -> select("INFORMATION_SCHEMA.SEQUENCES","SEQUENCE_NAME","SEQUENCE_SCHEMA","");
            case "triggers" -> "SELECT DISTINCT TRIGGER_NAME,TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA=? ORDER BY TRIGGER_NAME";
            case "domains" -> select("INFORMATION_SCHEMA.DOMAINS","DOMAIN_NAME","DOMAIN_SCHEMA","");
            case "events" -> select("INFORMATION_SCHEMA.EVENTS","EVENT_NAME","EVENT_SCHEMA","");
            case "indexes" -> e.equals("h2")?select("INFORMATION_SCHEMA.INDEXES","INDEX_NAME","INDEX_SCHEMA",""):e.equals("hsqldb")?"SELECT DISTINCT INDEX_NAME,INDEX_NAME FROM INFORMATION_SCHEMA.SYSTEM_INDEXINFO WHERE TABLE_SCHEM=? AND INDEX_NAME IS NOT NULL ORDER BY INDEX_NAME":null;
            default -> null;
        };
        return null;
    }
    private static String select(String table,String name,String schema,String condition){return "SELECT "+name+","+name+" FROM "+table+" WHERE "+schema+"=?"+condition+" ORDER BY 1";}
    private static ObjectNode jdbc(QueryJobs.Job job,Connection c,JsonNode r,ObjectNode out,String e)throws Exception{
        DatabaseMetaData m=c.getMetaData();String k=r.path("kind").asText(),s=r.path("schema").asText(""),db=r.path("database").asText(c.getCatalog());String schema=Set.of("mysql","mariadb").contains(e)?null:literal(m,s);
        ResultSet rs=switch(k){case "databases"->m.getCatalogs();case "schemas"->m.getSchemas(db,null);case "tables"->m.getTables(db,schema,"%",new String[]{"TABLE","BASE TABLE","SYSTEM TABLE"});case "views"->m.getTables(db,schema,"%",new String[]{"VIEW","SYSTEM VIEW"});case "relation"->m.getColumns(db,schema,literal(m,r.path("name").asText()),"%");case "functions"->m.getFunctions(db,schema,"%");case "procedures"->m.getProcedures(db,schema,"%");case "types"->m.getUDTs(db,schema,"%",null);default->throw new SQLFeatureNotSupportedException("This JDBC driver has no catalog adapter for "+k);};
        try(rs){String label=switch(k){case "databases"->"TABLE_CAT";case "schemas"->"TABLE_SCHEM";case "relation"->"COLUMN_NAME";case "functions"->"FUNCTION_NAME";case "procedures"->"PROCEDURE_NAME";case "types"->"TYPE_NAME";default->"TABLE_NAME";};read(job,rs,out,label,k.equals("databases")?"database":k.equals("schemas")?"schema":relation(k)?"relation":"object",Set.of("schemas","databases","tables","views").contains(k),db,s);}
        return out;
    }
    private static String literal(DatabaseMetaData m,String value)throws SQLException{String esc=m.getSearchStringEscape();if(esc==null||esc.isEmpty())return value;return value.replace(esc,esc+esc).replace("%",esc+"%").replace("_",esc+"_");}
    private static void read(QueryJobs.Job job,ResultSet rs,ObjectNode out,String label,String kind,boolean branch,String db,String schema)throws Exception{
        ArrayNode nodes=(ArrayNode)out.path("nodes");int offset=out.path("offset").asInt();if(offset>2000)throw new IllegalArgumentException("JDBC metadata paging is limited to 2,000 preceding entries");
        record Entry(String name,String id){}Comparator<Entry> order=Comparator.comparing(Entry::name,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::name).thenComparing(Entry::id);int capacity=offset+LIMIT+1;var retained=new PriorityQueue<Entry>(capacity,order.reversed());
        // JDBC does not promise a common catalog sort order. Keep only the smallest bounded
        // prefix rather than materializing every object just to sort the requested page.
        while(rs.next()){if(job.cancelled)throw new java.util.concurrent.CancellationException();String name=rs.getString(label);if(name==null)continue;if(name.length()>2048)throw new IllegalArgumentException("Metadata name exceeds 2048 characters");String id=name;if(label.equals("FUNCTION_NAME")||label.equals("PROCEDURE_NAME")){String specific=rs.getString("SPECIFIC_NAME");if(specific!=null)id=specific;}if(id.length()>2048)throw new IllegalArgumentException("Metadata identity exceeds 2048 characters");retained.add(new Entry(name,id));if(retained.size()>capacity)retained.poll();}
        var ordered=new ArrayList<>(retained);ordered.sort(order);if(ordered.size()>offset+LIMIT)out.put("nextOffset",offset+LIMIT);
        for(int i=offset;i<Math.min(ordered.size(),offset+LIMIT);i++){Entry entry=ordered.get(i);node(nodes,kind,entry.name,branch,kind.equals("database")?entry.name:db,kind.equals("schema")?entry.name:schema).put("key",kind+":"+entry.id);}
    }
    private static void sqlite(QueryJobs.Job job,Connection c,JsonNode r,ObjectNode out,int timeout)throws Exception{
        String k=r.path("kind").asText(),s=r.path("schema").asText("main");
        if(k.equals("schemas")){query(job,c,out,"SELECT name,name FROM pragma_database_list ORDER BY seq",List.of(),"schema",true,"","",timeout);return;}
        // Verify the selected attached database before using a correctly escaped SQL identifier.
        boolean exists=false;try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("PRAGMA database_list")){while(rs.next())if(s.equals(rs.getString("name")))exists=true;}if(!exists)throw new IllegalArgumentException("Attached database is unavailable");
        if(k.equals("relation")){query(job,c,out,"SELECT name,name || ' · ' || type FROM pragma_table_info(?,?) ORDER BY cid",List.of(r.path("name").asText(),s),"object",false,"",s,timeout);return;}
        String type=switch(k){case "tables"->"table";case "views"->"view";case "indexes"->"index";case "triggers"->"trigger";default->throw new IllegalArgumentException("Unsupported SQLite branch");};
        query(job,c,out,"SELECT name,name FROM \""+s.replace("\"","\"\"")+"\".sqlite_schema WHERE type=? ORDER BY name",List.of(type),relation(k)?"relation":"object",relation(k),"",s,timeout);
    }
}
