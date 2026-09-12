package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.MetadataTree.*;

/** Lazy, table-scoped catalog adapters. All catalog predicates use bound identifiers. */
final class TableMetadata {
    static List<String> categories(String engine,int major) {
        var result=new ArrayList<>(List.of("Columns","Foreign Keys","References","Indexes"));
        if(Set.of("postgresql","oracle","sqlserver","mysql","mariadb","h2","hsqldb","db2","duckdb","snowflake","sqlite").contains(engine))result.add("Constraints");
        if(Set.of("postgresql","oracle","sqlserver","mysql","mariadb","h2","hsqldb","db2","sqlite").contains(engine))result.add("Triggers");
        if(Set.of("postgresql","oracle","sqlserver","mysql","mariadb","db2").contains(engine))result.add("Partitions");
        if(Set.of("postgresql","oracle","sqlserver","db2","hsqldb").contains(engine))result.add("Dependencies");
        if(engine.equals("postgresql"))result.add("Rules");
        if(engine.equals("postgresql")||engine.equals("oracle")||engine.equals("snowflake")||engine.equals("sqlserver")&&major>=13)result.add("Policies");
        if(engine.equals("snowflake"))result.remove("Indexes"); // Ordinary Snowflake tables have no user-managed indexes.
        result.sort(String.CASE_INSENSITIVE_ORDER);return result;
    }
    static ObjectNode browse(QueryJobs.Job job,Connection c,JsonNode request,ObjectNode out,int timeout)throws Exception {
        DatabaseMetaData m=c.getMetaData();String product=m.getDatabaseProductName(),engine=request.path("genericOnly").asBoolean()?"jdbc":product.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(product);
        String kind=request.path("kind").asText(),db=request.path("database").asText(c.getCatalog()),schema=request.path("schema").asText(""),table=request.path("table").asText(request.path("name").asText());
        out.put("engine",engine);ArrayNode nodes=(ArrayNode)out.path("nodes");
        if(kind.equals("relation")){
            for(String label:categories(engine,m.getDatabaseMajorVersion()))node(nodes,"table_"+VendorMetadata.kind(label),label,true,db,schema).put("table",table).put("oid",request.path("oid").asText());
            return out;
        }
        String category=kind.substring("table_".length());
        if(!categories(engine,m.getDatabaseMajorVersion()).stream().anyMatch(label->VendorMetadata.kind(label).equals(category)))throw new SQLFeatureNotSupportedException("This database does not support this table category");
        if(engine.equals("postgresql")){
            String target="(SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND pg_catalog.has_schema_privilege(n.oid,'USAGE'))";
            String sql=switch(category){
                case "columns" -> "SELECT a.attnum::text,a.attname || ' · ' || pg_catalog.format_type(a.atttypid,a.atttypmod) FROM pg_catalog.pg_attribute a WHERE a.attrelid="+target+" AND a.attnum>0 AND NOT a.attisdropped";
                case "constraints" -> "SELECT oid::text,conname || ' · ' || pg_catalog.pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint WHERE conrelid="+target;
                case "foreign_keys" -> "SELECT oid::text,conname || ' → ' || confrelid::regclass::text FROM pg_catalog.pg_constraint WHERE contype='f' AND conrelid="+target;
                case "references" -> "SELECT oid::text,conrelid::regclass::text || ' · ' || conname FROM pg_catalog.pg_constraint WHERE contype='f' AND confrelid="+target;
                case "indexes" -> "SELECT x.indexrelid::text,c.relname FROM pg_catalog.pg_index x JOIN pg_catalog.pg_class c ON c.oid=x.indexrelid WHERE x.indrelid="+target;
                case "triggers" -> "SELECT oid::text,tgname FROM pg_catalog.pg_trigger WHERE NOT tgisinternal AND tgrelid="+target;
                case "partitions" -> "SELECT c.oid::text,c.oid::regclass::text FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class c ON c.oid=i.inhrelid WHERE c.relispartition AND i.inhparent="+target;
                case "dependencies" -> "SELECT DISTINCT d.classid::text || ':' || d.objid::text || ':' || d.objsubid::text,pg_catalog.pg_describe_object(d.classid,d.objid,d.objsubid) FROM pg_catalog.pg_depend d WHERE d.refclassid='pg_catalog.pg_class'::regclass AND d.refobjid="+target;
                case "rules" -> "SELECT oid::text,rulename FROM pg_catalog.pg_rewrite WHERE ev_class="+target;
                case "policies" -> "SELECT oid::text,polname FROM pg_catalog.pg_policy WHERE polrelid="+target;
                default -> throw new SQLFeatureNotSupportedException(category);
            };
            query(job,c,out,sql,List.of(schema,table),"object",false,db,schema,timeout);return out;
        }
        String sql=vendorSql(engine,category);
        if(sql!=null){query(job,c,out,sql,List.of(schema,table),"object",false,db,schema,timeout);return out;}
        if(engine.equals("sqlite")&&category.equals("constraints")){sqliteConstraints(job,c,out,schema,table,timeout);return out;}
        if(engine.equals("snowflake")&&category.equals("policies")){
            String full=TableQueries.sql(m,db,schema,table).substring("SELECT * FROM ".length());
            query(job,c,out,"SELECT POLICY_DB || '.' || POLICY_SCHEMA || '.' || POLICY_NAME,POLICY_NAME || ' · ' || POLICY_KIND FROM TABLE(INFORMATION_SCHEMA.POLICY_REFERENCES(REF_ENTITY_NAME => ?, REF_ENTITY_DOMAIN => ?))",List.of(full,"TABLE"),"object",false,db,schema,timeout);return out;
        }
        if(engine.equals("sqlite")&&category.equals("triggers")){
            String prefix=TableQueries.sql(m,"",schema,"sqlite_schema");
            query(job,c,out,prefix.replace("SELECT *","SELECT name,name")+" WHERE type='trigger' AND tbl_name=?",List.of(table),"object",false,db,schema,timeout);return out;
        }
        jdbc(job,c,request,out,engine,category,db,schema,table);return out;
    }
    static String vendorSql(String engine,String category){
        if(category.equals("constraints"))return switch(engine){
            case "h2","hsqldb","mysql","mariadb","snowflake" -> "SELECT CONSTRAINT_NAME,CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
            case "oracle" -> "SELECT CONSTRAINT_NAME,CONSTRAINT_NAME FROM ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=?";
            case "sqlserver" -> "SELECT o.name,o.name FROM sys.objects o JOIN sys.tables t ON t.object_id=o.parent_object_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=? AND o.type IN ('C','D','F','PK','UQ')";
            case "db2" -> "SELECT CONSTNAME,CONSTNAME FROM SYSCAT.TABCONST WHERE TABSCHEMA=? AND TABNAME=?";
            case "duckdb" -> "SELECT CAST(constraint_index AS VARCHAR),constraint_text FROM duckdb_constraints() WHERE schema_name=? AND table_name=? AND database_name=current_database()";
            default -> null;
        };
        if(category.equals("triggers"))return switch(engine){
            case "h2","hsqldb","mysql","mariadb" -> "SELECT DISTINCT TRIGGER_NAME,TRIGGER_NAME FROM INFORMATION_SCHEMA.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?";
            case "oracle" -> "SELECT TRIGGER_NAME,TRIGGER_NAME FROM ALL_TRIGGERS WHERE TABLE_OWNER=? AND TABLE_NAME=?";
            case "sqlserver" -> "SELECT tr.name,tr.name FROM sys.triggers tr JOIN sys.tables t ON t.object_id=tr.parent_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?";
            case "db2" -> "SELECT TRIGNAME,TRIGNAME FROM SYSCAT.TRIGGERS WHERE TABSCHEMA=? AND TABNAME=?";
            default -> null;
        };
        if(category.equals("partitions"))return switch(engine){
            case "oracle" -> "SELECT PARTITION_NAME,PARTITION_NAME FROM ALL_TAB_PARTITIONS WHERE TABLE_OWNER=? AND TABLE_NAME=?";
            case "mysql","mariadb" -> "SELECT DISTINCT PARTITION_NAME,PARTITION_NAME FROM INFORMATION_SCHEMA.PARTITIONS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND PARTITION_NAME IS NOT NULL";
            case "sqlserver" -> "SELECT CAST(p.partition_id AS varchar(32)),CONCAT('Partition ',p.partition_number) FROM sys.partitions p JOIN sys.tables t ON t.object_id=p.object_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=? AND p.index_id IN (0,1)";
            case "db2" -> "SELECT DATAPARTITIONNAME,DATAPARTITIONNAME FROM SYSCAT.DATAPARTITIONS WHERE TABSCHEMA=? AND TABNAME=?";
            default -> null;
        };
        if(category.equals("dependencies"))return switch(engine){
            case "oracle" -> "SELECT DISTINCT OWNER || '.' || NAME || ':' || TYPE,OWNER || '.' || NAME || ' · ' || TYPE FROM ALL_DEPENDENCIES WHERE REFERENCED_OWNER=? AND REFERENCED_NAME=? AND REFERENCED_TYPE='TABLE'";
            case "sqlserver" -> "SELECT DISTINCT CAST(d.referencing_id AS varchar(32)),CONCAT(OBJECT_SCHEMA_NAME(d.referencing_id),'.',OBJECT_NAME(d.referencing_id)) FROM sys.sql_expression_dependencies d JOIN sys.tables t ON t.object_id=d.referenced_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?";
            case "db2" -> "SELECT DISTINCT TABSCHEMA || '.' || TABNAME,TABSCHEMA || '.' || TABNAME FROM SYSCAT.TABDEP WHERE BSCHEMA=? AND BNAME=?";
            case "hsqldb" -> "SELECT DISTINCT VIEW_SCHEMA || '.' || VIEW_NAME,VIEW_SCHEMA || '.' || VIEW_NAME FROM INFORMATION_SCHEMA.VIEW_TABLE_USAGE WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
            default -> null;
        };
        if(category.equals("policies"))return switch(engine){
            case "oracle" -> "SELECT POLICY_NAME,POLICY_NAME FROM ALL_POLICIES WHERE OBJECT_OWNER=? AND OBJECT_NAME=?";
            case "sqlserver" -> "SELECT DISTINCT policy.name,policy.name FROM sys.security_predicates p JOIN sys.security_policies policy ON policy.object_id=p.object_id JOIN sys.tables t ON t.object_id=p.target_object_id JOIN sys.schemas s ON s.schema_id=t.schema_id WHERE s.name=? AND t.name=?";
            default -> null;
        };
        return null;
    }
    private static void sqliteConstraints(QueryJobs.Job job,Connection c,ObjectNode out,String schema,String table,int timeout)throws Exception{
        String source=null;String query=TableQueries.sql(c.getMetaData(),"",schema,"sqlite_schema").replace("SELECT *","SELECT sql")+" WHERE type='table' AND name=?";
        try(PreparedStatement st=c.prepareStatement(query)){job.statement=st;st.setQueryTimeout(timeout);st.setString(1,table);try(ResultSet rs=st.executeQuery()){if(rs.next()){try(java.io.Reader reader=rs.getCharacterStream(1)){if(reader!=null){StringBuilder bounded=new StringBuilder();char[] chunk=new char[2048];int n;while((n=reader.read(chunk))!=-1){if(bounded.length()+n>65536)throw new IllegalArgumentException("Table definition is too large for bounded constraint discovery");bounded.append(chunk,0,n);}source=bounded.toString();}}}}}finally{job.statement=null;}
        if(source==null)throw new IllegalArgumentException("Table is no longer available");
        var labels=new ArrayList<String>();
        try{
            var parsed=net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(source,p->p.withTimeOut(500));
            if(!(parsed instanceof net.sf.jsqlparser.statement.create.table.CreateTable create))throw new IllegalArgumentException("Unsupported table definition");
            if(create.getIndexes()!=null)for(var constraint:create.getIndexes())labels.add(constraint.toString());
            if(create.getColumnDefinitions()!=null)for(var column:create.getColumnDefinitions())if(column.getColumnSpecs()!=null){
                // Presentation only: never executes reconstructed DDL. Each entry includes all constraints on that column.
                var specs=column.getColumnSpecs();if(specs.stream().anyMatch(token->Set.of("PRIMARY","UNIQUE","CHECK","REFERENCES","CONSTRAINT","NOT").contains(token.toUpperCase(Locale.ROOT))))labels.add(column.getColumnName()+" · "+String.join(" ",specs));
            }
        }catch(Exception unsupported){throw new IllegalArgumentException("This SQLite table definition cannot be structurally described; inspect its CREATE TABLE SQL",unsupported);}
        labels.sort(String.CASE_INSENSITIVE_ORDER);int offset=out.path("offset").asInt();for(int i=offset;i<Math.min(offset+LIMIT,labels.size());i++){String label=labels.get(i);node((ArrayNode)out.path("nodes"),"object",label.length()>2048?label.substring(0,2048)+"…":label,false,"",schema).put("key","constraint:"+i);}if(labels.size()>offset+LIMIT)out.put("nextOffset",offset+LIMIT);
    }
    private static String pattern(DatabaseMetaData m,String s)throws SQLException{String e=m.getSearchStringEscape();return e==null||e.isEmpty()?s:s.replace(e,e+e).replace("_",e+"_").replace("%",e+"%");}
    private static void jdbc(QueryJobs.Job job,Connection c,JsonNode request,ObjectNode out,String engine,String category,String db,String schema,String table)throws Exception{
        DatabaseMetaData m=c.getMetaData();String catalog=db==null||db.isEmpty()?c.getCatalog():db,s=Set.of("mysql","mariadb").contains(engine)?null:schema;
        ResultSet rs=switch(category){case "columns"->m.getColumns(catalog,s==null?null:pattern(m,s),pattern(m,table),"%");case "foreign_keys"->m.getImportedKeys(catalog,s,table);case "references"->m.getExportedKeys(catalog,s,table);case "indexes"->m.getIndexInfo(catalog,s,table,false,true);default->throw new SQLFeatureNotSupportedException("No JDBC adapter for "+category);};
        // JDBC metadata ordering varies: retain only the smallest bounded prefix, not all records.
        int offset=out.path("offset").asInt();if(offset>2000)throw new IllegalArgumentException("JDBC metadata paging is limited to 2,000 preceding entries");int capacity=offset+LIMIT+1;
        record Entry(String key,String label,String raw){}Comparator<Entry> order=Comparator.comparing(Entry::label,String.CASE_INSENSITIVE_ORDER).thenComparing(Entry::label).thenComparing(Entry::key);var entries=new TreeSet<Entry>(order);
        try(rs){while(rs.next()){
            if(job.cancelled)throw new java.util.concurrent.CancellationException();String raw,label,key;
            if(category.equals("columns")){raw=rs.getString("COLUMN_NAME");key=raw;String type=rs.getString("TYPE_NAME");label=raw+" · "+type;}
            else if(category.equals("indexes")){raw=rs.getString("INDEX_NAME");if(raw==null)continue;key=raw;label=raw;}
            else {raw=rs.getString("FK_NAME");String other=rs.getString(category.equals("references")?"FKTABLE_NAME":"PKTABLE_NAME"),otherSchema=rs.getString(category.equals("references")?"FKTABLE_SCHEM":"PKTABLE_SCHEM");if(raw==null||raw.isBlank())raw="Foreign key "+rs.getString("FKCOLUMN_NAME")+" → "+rs.getString("PKCOLUMN_NAME");String identity=Objects.toString(otherSchema,"")+"."+other;key=raw+":"+identity;label=raw+" → "+identity;}
            if(raw==null)continue;if(key.length()>2048||label.length()>2048)throw new IllegalArgumentException("Oversized metadata identifier");entries.add(new Entry(key,label,raw));if(entries.size()>capacity)entries.pollLast();
        }}
        int i=0;for(Entry e:entries){if(i++<offset)continue;if(i>offset+LIMIT){out.put("nextOffset",offset+LIMIT);break;}node((ArrayNode)out.path("nodes"),"object",e.label,false,db,schema).put("key","object:"+e.key).put("objectName",e.raw);}
    }
}
