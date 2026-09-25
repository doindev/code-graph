package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Browser-only lazy catalog navigation. No supplied SQL or identifier interpolation. */
final class MetadataTree {
    static final int LIMIT=200;
    private static final String[][] DATABASE_GROUPS={{"schemas","Schemas"},{"event_triggers","Event Triggers"},{"extensions","Extensions"},{"storage","Storage"},{"system","System Info"},{"roles","Roles"}};
    private static final String[][] SCHEMA_GROUPS={{"tables","Tables"},{"foreign_tables","Foreign Tables"},{"views","Views"},{"materialized_views","Materialized Views"},{"indexes","Indexes"},{"functions","Functions"},{"procedures","Procedures"},{"sequences","Sequences"},{"types","Data types"},{"aggregates","Aggregate functions"}};
    static ObjectNode browse(QueryJobs.Job job,Connection c,JsonNode request,int timeout)throws Exception{
        if(ScheduledJobs.branch(request.path("kind").asText()))return ScheduledJobs.browse(job,c,request);
        return ScheduledJobs.attach(job,c,request,browseCatalog(job,c,request,timeout));
    }
    private static ObjectNode browseCatalog(QueryJobs.Job job,Connection c,JsonNode request,int timeout)throws Exception{
        String kind=request.path("kind").asText("root"),database=request.path("database").asText(c.getCatalog()),schema=request.path("schema").asText("");
        boolean postgres=!request.path("genericOnly").asBoolean()&&c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
        ObjectNode out=Profiles.JSON.createObjectNode().put("engine",postgres?"postgresql":"jdbc").put("offset",request.path("offset").asInt(0));ArrayNode nodes=out.putArray("nodes");
        boolean oracleSchemaTriggers=kind.equals("table_triggers")&&request.path("table").asText().isBlank()&&OracleDialect.isOracle(c);
        if(kind.startsWith("table_")&&!oracleSchemaTriggers||kind.equals("relation")&&request.path("relationType").asText().equals("table"))return TableMetadata.browse(job,c,request,out,timeout);
        if(!postgres)return VendorMetadata.browse(job,c,request,out,timeout);
        switch(kind){
            case "root" -> node(nodes,"databases","Databases",true,"","");
            case "databases" -> query(job,c,out,"SELECT oid::text, datname FROM pg_catalog.pg_database WHERE datallowconn AND pg_catalog.has_database_privilege(oid,'CONNECT') ORDER BY datname",List.of(),"database",true,"","",timeout);
            case "database" -> groups(nodes,DATABASE_GROUPS,database,"");
            case "schemas" -> query(job,c,out,"SELECT oid::text,nspname FROM pg_catalog.pg_namespace WHERE pg_catalog.has_schema_privilege(oid,'USAGE') ORDER BY nspname",List.of(),"schema",true,database,"",timeout);
            case "schema" -> groups(nodes,SCHEMA_GROUPS,database,schema);
            case "tables","foreign_tables","views","materialized_views","indexes","sequences" -> {
                String relkind=switch(kind){case "tables"->"rp";case "foreign_tables"->"f";case "views"->"v";case "materialized_views"->"m";case "indexes"->"iI";default->"S";};
                query(job,c,out,"SELECT c.oid::text,c.relname FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND strpos(?,c.relkind::text)>0 AND pg_catalog.has_schema_privilege(n.oid,'USAGE') ORDER BY c.relname,c.oid",List.of(schema,relkind),Set.of("tables","foreign_tables","views","materialized_views").contains(kind)?"relation":"object",Set.of("tables","foreign_tables","views","materialized_views").contains(kind),database,schema,timeout);
            }
            case "functions","procedures","aggregates" -> query(job,c,out,"SELECT p.oid::text,p.proname || '(' || pg_catalog.pg_get_function_identity_arguments(p.oid) || ')' FROM pg_catalog.pg_proc p JOIN pg_catalog.pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=? AND strpos(?,p.prokind::text)>0 AND pg_catalog.has_schema_privilege(n.oid,'USAGE') ORDER BY p.proname,p.oid",List.of(schema,kind.equals("procedures")?"p":kind.equals("aggregates")?"a":"fw"),"object",false,database,schema,timeout);
            case "types" -> query(job,c,out,"SELECT t.oid::text,t.typname FROM pg_catalog.pg_type t JOIN pg_catalog.pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname=? AND t.typisdefined AND pg_catalog.has_schema_privilege(n.oid,'USAGE') ORDER BY t.typname,t.oid",List.of(schema),"object",false,database,schema,timeout);
            case "event_triggers" -> query(job,c,out,"SELECT oid::text,evtname FROM pg_catalog.pg_event_trigger ORDER BY evtname",List.of(),"object",false,database,"",timeout);
            case "extensions" -> query(job,c,out,"SELECT oid::text,extname || ' (' || extversion || ')' FROM pg_catalog.pg_extension ORDER BY extname",List.of(),"object",false,database,"",timeout);
            case "roles" -> query(job,c,out,"SELECT oid::text,rolname FROM pg_catalog.pg_roles ORDER BY rolname",List.of(),"object",false,database,"",timeout);
            case "storage" -> {node(nodes,"foreign_servers","Foreign servers",true,database,"");node(nodes,"tablespaces","Tablespaces",true,database,"");}
            case "tablespaces" -> query(job,c,out,"SELECT oid::text,spcname FROM pg_catalog.pg_tablespace ORDER BY spcname",List.of(),"object",false,database,"",timeout);
            case "foreign_servers" -> query(job,c,out,"SELECT oid::text,srvname FROM pg_catalog.pg_foreign_server ORDER BY srvname",List.of(),"object",false,database,"",timeout);
            case "system" -> query(job,c,out,"SELECT 'database','Database: ' || current_database() UNION ALL SELECT 'version','Version: ' || current_setting('server_version') UNION ALL SELECT 'user','User: ' || current_user UNION ALL SELECT 'encoding','Encoding: ' || current_setting('server_encoding') UNION ALL SELECT 'timezone','Time zone: ' || current_setting('TimeZone')",List.of(),"object",false,database,"",timeout);
            case "relation" -> query(job,c,out,"SELECT a.attnum::text,a.attname || ' · ' || pg_catalog.format_type(a.atttypid,a.atttypmod) FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE c.oid=?::oid AND n.nspname=? AND pg_catalog.has_schema_privilege(n.oid,'USAGE') AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum",List.of(request.path("oid").asText(),schema),"object",false,database,schema,timeout);
            case "object" -> {} // Leaf Refresh re-fetches its parent, not arbitrary SQL.
            default -> throw new IllegalArgumentException("Unsupported metadata branch");
        }
        return out;
    }
    static void groups(ArrayNode nodes,String[][] groups,String database,String schema){Arrays.stream(groups).sorted(Comparator.comparing(group->group[1],String.CASE_INSENSITIVE_ORDER)).forEach(group->node(nodes,group[0],group[1],true,database,schema));}
    static ObjectNode node(ArrayNode nodes,String kind,String name,boolean branch,String database,String schema){return nodes.addObject().put("kind",kind).put("name",name).put("branch",branch).put("database",database==null?"":database).put("schema",schema).put("key",kind);}
    static void query(QueryJobs.Job job,Connection c,ObjectNode out,String sql,List<String> parameters,String kind,boolean branch,String database,String schema,int timeout)throws Exception{
        int offset=out.path("offset").asInt(0); // Cursor offsets are applied by the query wrapper below.
        boolean pg=c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
        // Sort on the server before pagination. All callers supply fixed two-column catalog SQL.
        int ordering=sql.lastIndexOf(" ORDER BY ");if(ordering>=0)sql=sql.substring(0,ordering);
        sql="WITH catalog_nodes(node_id,node_name) AS ("+sql+") SELECT node_id,node_name FROM catalog_nodes ORDER BY LOWER(node_name),node_name,node_id";
        try(PreparedStatement statement=c.prepareStatement(sql+(pg?" LIMIT ? OFFSET ?":""))){
            job.statement=statement;statement.setQueryTimeout(timeout);statement.setFetchSize(64);int i=1;for(String value:parameters)statement.setString(i++,value);if(pg){statement.setInt(i++,LIMIT+1);statement.setInt(i,offset);}else statement.setMaxRows(offset+LIMIT+1);
            try(ResultSet rs=statement.executeQuery()){ArrayNode nodes=(ArrayNode)out.path("nodes");int count=0,skip=pg?0:offset;while(rs.next()){if(job.cancelled)throw new java.util.concurrent.CancellationException();if(skip-->0)continue;if(count++==LIMIT){out.put("nextOffset",offset+LIMIT);break;}String oid=rs.getString(1),name=rs.getString(2);if(name.length()>2048)name=name.substring(0,2048)+"…";ObjectNode n=node(nodes,kind,name,branch,database,schema).put("key",kind+":"+oid).put("oid",oid);if(kind.equals("database"))n.put("database",name);if(kind.equals("schema"))n.put("schema",name);}}
        }finally{job.statement=null;}
    }
    static ObjectNode request(JsonNode input){ObjectNode result=Profiles.JSON.createObjectNode();for(String key:List.of("kind","database","schema","name","oid","table","relationType","scheduler","key")){if(input.has(key)){if(!input.path(key).isTextual()||input.path(key).asText().length()>(key.equals("key")?4096:Set.of("name","oid","table").contains(key)?2048:256))throw new IllegalArgumentException("Invalid metadata "+key);result.put(key,input.path(key).asText());}}if(input.has("offset")&&!input.path("offset").isIntegralNumber())throw new IllegalArgumentException("Invalid metadata offset");long offset=input.path("offset").asLong(0);if(offset<0||offset>1000000)throw new IllegalArgumentException("Invalid metadata offset");result.put("offset",offset);return result;}
}
