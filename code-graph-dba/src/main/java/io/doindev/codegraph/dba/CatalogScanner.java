package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import io.doindev.codegraph.store.DocumentStore;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Streams bounded, vendor-aware catalog documents. Extracted text is data, never executed. */
final class CatalogScanner {
    static final long MAX_BYTES=64L<<20;
    static final int MAX_OBJECTS=50_000, MAX_DDL=4<<20;
    private final Connection connection;
    private final JsonNode profile,binding;
    private final DocumentStore.Writer writer;
    private final BooleanSupplier cancelled;
    private final long deadline;
    private final java.util.function.IntSupplier statementTimeout;
    private final Set<String> seen=new HashSet<>();
    private final ArrayNode warnings=Profiles.JSON.createArrayNode();
    private String oracleUser="";
    private String engine;private final Set<String> schemas=new TreeSet<>();
    private long bytes;private int objects,edges;
    private boolean inventoryComplete=true;
    private volatile Statement statement;
    interface Progress {void accept(String phase,long objects,long dependencies,long bytes);}
    private Progress progress=(phase,objects,dependencies,bytes)->{};
    private String phase="server_version";
    void onProgress(Progress listener){progress=listener;}
    private void phase(String next){phase=next;report();}
    private void report(){progress.accept(phase,objects,edges,bytes);}
    record Limits(int objects,long bytes,int rows,int ddlCharacters){
        static final Limits DEFAULT=new Limits(MAX_OBJECTS,MAX_BYTES,10_000,MAX_DDL);
    }
    static final class CaptureLimit extends IllegalArgumentException {CaptureLimit(String reason){super(reason);}}
    private final Limits limits;
    private int capturedRows;
    private final java.util.function.Consumer<Statement> execution;
    CatalogScanner(Connection c,JsonNode p,JsonNode b,DocumentStore.Writer w,BooleanSupplier cancel){
        this(c,p,b,w,cancel,Limits.DEFAULT,_ ->{});
    }
    CatalogScanner(Connection c,JsonNode p,JsonNode b,DocumentStore.Writer w,BooleanSupplier cancel,Limits limits,java.util.function.Consumer<Statement> execution){
        this(c,p,b,w,cancel,limits,execution,()->30);
    }
    CatalogScanner(Connection c,JsonNode p,JsonNode b,DocumentStore.Writer w,BooleanSupplier cancel,Limits limits,java.util.function.Consumer<Statement> execution,java.util.function.IntSupplier statementTimeout){
        connection=c;profile=p;binding=b;writer=w;cancelled=cancel;this.limits=limits;this.execution=execution;this.statementTimeout=statementTimeout;deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5);engine=engine(p);
    }
    static String engine(JsonNode p){return switch(p.path("templateId").asText("custom")){
        case "azure-sql"->"sqlserver";case "cosmos-cassandra"->"cassandra";default->p.path("templateId").asText("custom");};}
    void cancel(){Statement s=statement;if(s!=null)try{s.cancel();}catch(SQLException ignored){}}
    ObjectNode scan()throws Exception{
        phase("server_version");check();DatabaseMetaData metadata=connection.getMetaData();if(engine.equals("custom")){String product=metadata.getDatabaseProductName();engine=product.toLowerCase(Locale.ROOT).contains("postgres")?"postgresql":VendorMetadata.engine(product);}
        ObjectNode result=Profiles.JSON.createObjectNode().put("engine",engine).put("startedAt",System.currentTimeMillis());
        ObjectNode version=result.putObject("version");
        version.put("product",metadata.getDatabaseProductName()).put("server",metadata.getDatabaseProductVersion())
            .put("major",metadata.getDatabaseMajorVersion()).put("minor",metadata.getDatabaseMinorVersion())
            .put("driver",metadata.getDriverName()).put("driverVersion",metadata.getDriverVersion()).put("detectedAt",System.currentTimeMillis());
        var patch=java.util.regex.Pattern.compile("(?<![0-9])([0-9]+)\\.([0-9]+)\\.([0-9]+)").matcher(version.path("server").asText());if(patch.find())version.put("patch",patch.group(3));
        String catalog=binding.path("database").asText(),schema=binding.path("schema").asText();
        if(engine.equals("sqlserver"))optional("Compatibility level",()->query("SELECT compatibility_level FROM sys.databases WHERE name=DB_NAME()",rs->version.put("compatibility",rs.getString(1))));
        if(engine.equals("mysql")||engine.equals("mariadb"))optional("SQL mode",()->query("SELECT @@sql_mode",rs->version.put("compatibility",rs.getString(1))));
        String jdbcCatalog=catalog;
        if(engine.equals("oracle")){
            OracleDialect.Target actual=OracleDialect.target(connection,remainingSeconds());if(!actual.matches(catalog))throw new SQLException("Oracle scan connection targets a different service/PDB");
            result.set("resolvedTarget",actual.json());jdbcCatalog="";oracleUser=actual.user();
            if(!actual.user().equals("SYS")&&(schema.isBlank()||!schema.equals(actual.user()))&&nativeRows("SELECT role FROM SYS.SESSION_ROLES WHERE role='SELECT_CATALOG_ROLE'").isEmpty())throw new SQLException("Complete Oracle scanning of other owners requires SELECT_CATALOG_ROLE; choose the authenticated owner's schema or grant catalog access");
        }
        boolean roster=false;phase("relations");
        try(ResultSet tables=metadata.getTables(jdbcCatalog.isBlank()?null:jdbcCatalog,pattern(metadata,schema),"%",null)){
            roster=true;while(tables.next()){
                check();String s=text(tables,"TABLE_SCHEM"),db=text(tables,"TABLE_CAT"),name=text(tables,"TABLE_NAME"),kind=text(tables,"TABLE_TYPE");
                if(!inScope(db,s)||systemSchema(s)&&!(engine.equals("oracle")&&s.equals(schema))||kind.toUpperCase(Locale.ROOT).startsWith("SYSTEM"))continue;
                if(s.isEmpty()&&!schema.isEmpty()&&(db.equals(schema)||engine.equals("sqlite")))s=schema;schemas.add(s);
                ObjectNode object=object(s,name,kind.equalsIgnoreCase("BASE TABLE")?"table":kind.toLowerCase(Locale.ROOT).replace(' ','_'),"");
                object.put("catalog",db);object.put("remarks",text(tables,"REMARKS"));
                table(metadata,object,db,s,name);nativeDefinition(object);emit(object);
            }
        }catch(SQLFeatureNotSupportedException e){inventoryComplete=false;warn("Table inventory is unavailable through this driver");}
        catch(SQLException e){throw new SQLException("Catalog inventory failed; the previous snapshot remains available",e);}
        phase("native_inventory");nativeInventory();phase("supplemental_inventory");supplementalInventory(metadata);
        if(!Set.of("postgresql","oracle","sqlserver","mysql","mariadb","db2","snowflake").contains(engine)){
            phase("routines");optional("Routine inventory",()->{try(ResultSet routines=metadata.getProcedures(catalog.isBlank()?null:catalog,pattern(metadata,schema),"%")){
                while(routines.next()){check();String s=text(routines,"PROCEDURE_SCHEM"),db=text(routines,"PROCEDURE_CAT"),name=text(routines,"PROCEDURE_NAME");if(inScope(db,s)&&!systemSchema(s)){ObjectNode o=object(s,name,"procedure",text(routines,"SPECIFIC_NAME"));o.put("remarks",text(routines,"REMARKS"));nativeDefinition(o);emit(o);}}
            }});
            optional("Function inventory",()->{try(ResultSet routines=metadata.getFunctions(catalog.isBlank()?null:catalog,pattern(metadata,schema),"%")){
                while(routines.next()){check();String s=text(routines,"FUNCTION_SCHEM"),db=text(routines,"FUNCTION_CAT"),name=text(routines,"FUNCTION_NAME");if(inScope(db,s)&&!systemSchema(s)){ObjectNode o=object(s,name,"function",text(routines,"SPECIFIC_NAME"));o.put("remarks",text(routines,"REMARKS"));nativeDefinition(o);emit(o);}}
            }});
        }
        if(objects>0&&seen.size()>0)warn("Coverage is limited to catalog metadata exposed by this driver/account. Partial definitions are labeled per object.");
        result.put("finishedAt",System.currentTimeMillis()).put("objects",objects).put("dependencies",edges).put("bytes",bytes)
            .put("inventoryComplete",inventoryComplete&&roster).put("coverage",warnings.isEmpty()?"native_and_jdbc":"partial")
            .put("scopeNote","Accessible non-system objects in the selected scope. JDBC reconstruction is not a restore script; inaccessible, server-global and unsupported object classes may be absent.");
        result.set("warnings",warnings);store("metadata",result);return result;
    }
    static String id(String schema,String name,String kind,String signature){return hash(schema+"\u0000"+name+"\u0000"+kind+"\u0000"+signature);}
    static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private ObjectNode object(String schema,String name,String kind,String signature){return Profiles.JSON.createObjectNode().put("id",id(schema,name,kind,signature)).put("schema",schema).put("name",name).put("kind",kind).put("signature",signature).put("ddl","").put("definitionCoverage","unavailable").put("definitionSource","jdbc");}
    private void table(DatabaseMetaData m,ObjectNode o,String catalog,String schema,String name)throws Exception{
        ArrayNode primary=o.putArray("primaryKeys"),columns=o.putArray("columns"),indexes=o.putArray("indexes"),keys=o.putArray("foreignKeys"),grants=o.putArray("privileges");
        optional("Primary key for "+name,()->{try(ResultSet r=m.getPrimaryKeys(empty(catalog),empty(schema),name)){while(r.next()){check();bounded(primary);primary.addObject().put("name",text(r,"PK_NAME")).put("column",text(r,"COLUMN_NAME")).put("ordinal",r.getInt("KEY_SEQ"));}}});
        optional("Columns for "+schema+"."+name,()->{try(ResultSet r=m.getColumns(empty(catalog),pattern(m,schema),pattern(m,name),null)){while(r.next()){check();bounded(columns);columns.addObject().put("name",text(r,"COLUMN_NAME")).put("type",text(r,"TYPE_NAME")).put("size",r.getInt("COLUMN_SIZE")).put("scale",r.getInt("DECIMAL_DIGITS")).put("nullable",r.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls).put("default",text(r,"COLUMN_DEF")).put("ordinal",r.getInt("ORDINAL_POSITION")).put("remarks",text(r,"REMARKS"));}}});
        optional("Indexes for "+name,()->{try(ResultSet r=m.getIndexInfo(empty(catalog),empty(schema),name,false,true)){while(r.next()){check();if(r.getShort("TYPE")==DatabaseMetaData.tableIndexStatistic)continue;bounded(indexes);indexes.addObject().put("name",text(r,"INDEX_NAME")).put("column",text(r,"COLUMN_NAME")).put("unique",!r.getBoolean("NON_UNIQUE")).put("ordinal",r.getInt("ORDINAL_POSITION"));}}});
        optional("Foreign keys for "+name,()->{try(ResultSet r=m.getImportedKeys(empty(catalog),empty(schema),name)){while(r.next()){check();bounded(keys);ObjectNode edge=keys.addObject().put("name",text(r,"FK_NAME")).put("column",text(r,"FKCOLUMN_NAME")).put("targetCatalog",text(r,"PKTABLE_CAT")).put("targetSchema",text(r,"PKTABLE_SCHEM")).put("target",text(r,"PKTABLE_NAME")).put("targetColumn",text(r,"PKCOLUMN_NAME"));dependency(schema,name,edge.path("targetSchema").asText(),edge.path("target").asText(),"foreign_key");}}});
        optional("Privileges for "+name,()->{try(ResultSet r=m.getTablePrivileges(empty(catalog),pattern(m,schema),pattern(m,name))){while(r.next()){check();bounded(grants);grants.addObject().put("grantee",text(r,"GRANTEE")).put("privilege",text(r,"PRIVILEGE")).put("grantable",text(r,"IS_GRANTABLE"));}}});
        if(!columns.isEmpty()){
            List<String> definitions=new ArrayList<>();for(JsonNode column:columns)definitions.add(q(column.path("name").asText())+" "+column.path("type").asText()+(column.path("nullable").asBoolean()?"":" NOT NULL")+(column.path("default").asText().isEmpty()?"":" DEFAULT "+column.path("default").asText()));
            o.put("ddl","-- Structural JDBC reconstruction; inspect columns, indexes and foreignKeys for details. Not executable migration SQL.\nCREATE TABLE "+qualified(schema,name)+" (\n  "+String.join(",\n  ",definitions)+"\n);").put("definitionCoverage","partial");
        }
    }
    private void nativeDefinition(ObjectNode o)throws Exception{
        if(engine.equals("sqlserver")&&Set.of("table","base_table").contains(o.path("kind").asText())){optional("SQL Server table definition",()->SqlServerDefinitions.table(o,this::nativeRows));return;}
        String s=o.path("schema").asText(),name=o.path("name").asText(),kind=o.path("kind").asText(),target=qualified(s,name);
        optional("Definition for "+s+"."+name,()->{
            String sql=null;Object[] args={};int column=1;
            switch(engine){
                case "postgresql"->{if(kind.contains("view")){sql="SELECT pg_get_viewdef(c.oid,true) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?";args=new Object[]{s,name};}}
                case "oracle"->{String type=OracleMetadata.ddlType(kind);if(!type.isBlank()){sql="SELECT SYS.DBMS_METADATA.GET_DDL(?,?,?) FROM SYS.DUAL";args=new Object[]{type,name,s};}}
                case "mysql","mariadb","starrocks","clickhouse"->{
                    // Indexes, constraints and other supplemental objects are not tables.
                    if(Set.of("table","base_table","view","materialized_view","procedure","function").contains(kind)){
                        sql="SHOW CREATE "+(kind.contains("view")?"VIEW":kind.equals("procedure")?"PROCEDURE":kind.equals("function")?"FUNCTION":"TABLE")+" "+target;
                        column=kind.equals("procedure")||kind.equals("function")?3:2;
                    }
                }
                case "snowflake"->{sql="SELECT GET_DDL(?,?)";args=new Object[]{kind.contains("view")?"VIEW":kind.toUpperCase(Locale.ROOT),target};}
                case "sqlite"->{sql="SELECT sql FROM "+q(s.isBlank()?"main":s)+".sqlite_schema WHERE name=?";args=new Object[]{name};}
                case "duckdb"->{sql="SELECT sql FROM "+(kind.contains("view")?"duckdb_views()":"duckdb_tables()")+" WHERE schema_name=? AND "+(kind.contains("view")?"view_name":"table_name")+"=?";args=new Object[]{s,name};}
                case "sqlserver"->{sql="SELECT OBJECT_DEFINITION(OBJECT_ID(?))";args=new Object[]{target};}
                case "trino","presto","hive","databricks","cockroachdb"->sql="SHOW CREATE "+(kind.contains("view")?"VIEW":"TABLE")+" "+target;
                case "h2","hsqldb"->{if(kind.contains("view")){sql="SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";args=new Object[]{s,name};}}
                default->{}
            }
            if(sql!=null){final int col=column;query(sql,r->{String ddl=definition(r,col);if(!ddl.isBlank()){o.put("ddl",ddl).put("definitionSource","native").put("definitionCoverage",engine.equals("postgresql")||engine.equals("h2")||engine.equals("hsqldb")?"body":"native");}},args);}
        });
    }
    private void nativeInventory()throws Exception{
        String scope=binding.path("schema").asText();
        switch(engine){
            case "postgresql"->{
                inventory("Routines","SELECT n.nspname,p.proname,CASE p.prokind WHEN 'p' THEN 'procedure' ELSE 'function' END,pg_get_function_identity_arguments(p.oid),pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE p.prokind IN ('p','f','w')",scope);
                inventory("Indexes","SELECT n.nspname,c.relname,'index','',pg_get_indexdef(c.oid) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE c.relkind='i'",scope);
                inventory("Triggers","SELECT n.nspname,t.tgname,'trigger',c.relname,pg_get_triggerdef(t.oid,true) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE NOT t.tgisinternal",scope);
                inventory("Constraints","SELECT n.nspname,c.conname,'constraint',c.conrelid::regclass::text,pg_get_constraintdef(c.oid,true) FROM pg_constraint c JOIN pg_namespace n ON n.oid=c.connamespace",scope);
                inventory("Sequences","SELECT n.nspname,c.relname,'sequence','',format('CREATE SEQUENCE %I.%I AS %s INCREMENT BY %s MINVALUE %s MAXVALUE %s START WITH %s CACHE %s %s;',n.nspname,c.relname,format_type(s.seqtypid,NULL),s.seqincrement,s.seqmin,s.seqmax,s.seqstart,s.seqcache,CASE WHEN s.seqcycle THEN 'CYCLE' ELSE 'NO CYCLE' END) FROM pg_sequence s JOIN pg_class c ON c.oid=s.seqrelid JOIN pg_namespace n ON n.oid=c.relnamespace",scope);
                inventory("Types","SELECT n.nspname,t.typname,'type','',CASE WHEN t.typtype='e' THEN 'ENUM ('||(SELECT string_agg(quote_literal(e.enumlabel),', ' ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid=t.oid)||')' ELSE format_type(t.oid,NULL) END FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE t.typtype IN ('e','d','r','m')",scope);
                inventory("Policies","SELECT schemaname,policyname,'policy',tablename,COALESCE(qual,'')||CASE WHEN with_check IS NULL THEN '' ELSE ' WITH CHECK ('||with_check||')' END FROM pg_policies",scope);
                optional("Native dependencies",()->query("SELECT DISTINCT sn.nspname,sv.relname,tn.nspname,tv.relname FROM pg_depend d JOIN pg_rewrite r ON r.oid=d.objid JOIN pg_class sv ON sv.oid=r.ev_class JOIN pg_namespace sn ON sn.oid=sv.relnamespace JOIN pg_class tv ON tv.oid=d.refobjid JOIN pg_namespace tn ON tn.oid=tv.relnamespace WHERE d.classid='pg_rewrite'::regclass AND d.refclassid='pg_class'::regclass AND sv.oid<>tv.oid",r->{if((scope.isBlank()||scope.equals(r.getString(1)))&&!systemSchema(r.getString(1)))dependency(r.getString(1),r.getString(2),r.getString(3),r.getString(4),"depends_on");}));
            }
            case "oracle"->oracleInventory(scope);
            case "sqlserver"->inventory("Modules","SELECT s.name,o.name,LOWER(o.type_desc),CONVERT(varchar,o.object_id),m.definition FROM sys.objects o JOIN sys.schemas s ON s.schema_id=o.schema_id JOIN sys.sql_modules m ON m.object_id=o.object_id",scope);
            case "mysql","mariadb"->{inventory("Routines","SELECT ROUTINE_SCHEMA,ROUTINE_NAME,LOWER(ROUTINE_TYPE),SPECIFIC_NAME,ROUTINE_DEFINITION FROM INFORMATION_SCHEMA.ROUTINES",scope);inventory("Triggers","SELECT TRIGGER_SCHEMA,TRIGGER_NAME,'trigger',EVENT_OBJECT_TABLE,ACTION_STATEMENT FROM INFORMATION_SCHEMA.TRIGGERS",scope);inventory("Events","SELECT EVENT_SCHEMA,EVENT_NAME,'event','',EVENT_DEFINITION FROM INFORMATION_SCHEMA.EVENTS",scope);}
            case "db2"->{inventory("Routines","SELECT ROUTINESCHEMA,ROUTINENAME,'routine',SPECIFICNAME,TEXT FROM SYSCAT.ROUTINES",scope);inventory("Triggers","SELECT TRIGSCHEMA,TRIGNAME,'trigger',TABNAME,TEXT FROM SYSCAT.TRIGGERS",scope);}
            case "snowflake"->{inventory("Procedures","SELECT PROCEDURE_SCHEMA,PROCEDURE_NAME,'procedure',ARGUMENT_SIGNATURE,PROCEDURE_DEFINITION FROM INFORMATION_SCHEMA.PROCEDURES",scope);inventory("Functions","SELECT FUNCTION_SCHEMA,FUNCTION_NAME,'function',ARGUMENT_SIGNATURE,FUNCTION_DEFINITION FROM INFORMATION_SCHEMA.FUNCTIONS",scope);}
            default->warn("Provider exposes JDBC metadata plus available native relation definitions. Other object types may not be exposed by this driver.");
        }
    }
    private void oracleInventory(String scope)throws Exception{
        String filter=scope.isBlank()?" AND oracle_maintained='N'":" AND owner=?";Object[] args=scope.isBlank()?new Object[]{}:new Object[]{scope};
        optional("Oracle native objects",()->query("SELECT owner,object_name,LOWER(REPLACE(object_type,' ','_')),TO_CHAR(object_id),status,edition_name FROM SYS.all_objects WHERE subobject_name IS NULL AND object_type IN ('PROCEDURE','FUNCTION','PACKAGE','PACKAGE BODY','TRIGGER','TYPE','TYPE BODY','SEQUENCE','INDEX','MATERIALIZED VIEW','SYNONYM','JAVA SOURCE','JAVA CLASS','JAVA RESOURCE')"+filter+" ORDER BY owner,object_type,object_name",r->{
            String owner=r.getString(1),name=r.getString(2),kind=r.getString(3);if(systemSchema(owner)&&scope.isBlank())return;
            ObjectNode object=object(owner,name,kind,"").put("catalogIdentity",r.getString(4)).put("status",r.getString(5)).put("edition",Objects.toString(r.getString(6),""));
            nativeDefinition(object);
            if(Set.of("package","package_body","type","type_body","function","procedure","trigger","java_source").contains(kind)){
                optional("Compilation errors for "+owner+"."+name,()->object.set("compilationErrors",nativeRows("SELECT type,line,position,attribute,message_number,text FROM SYS.all_errors WHERE owner=? AND name=? ORDER BY type,sequence",owner,name)));
            }
            if(kind.startsWith("java_")&&!kind.equals("java_source"))object.put("externalAssetRequired",true);
            emit(object);
        },args));
        String ownerFilter=scope.isBlank()?" WHERE owner IN (SELECT username FROM SYS.all_users WHERE oracle_maintained='N')":" WHERE owner=?";
        optional("Oracle dependencies",()->query("SELECT owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link_name,dependency_type FROM SYS.all_dependencies"+(scope.isBlank()?ownerFilter:" WHERE owner=? OR referenced_owner=?")+" ORDER BY owner,name,type,referenced_owner,referenced_name",r->{
            ObjectNode edge=Profiles.JSON.createObjectNode().put("schema",r.getString(1)).put("name",r.getString(2)).put("objectType",r.getString(3)).put("targetSchema",r.getString(4)).put("target",r.getString(5)).put("targetType",r.getString(6)).put("databaseLink",Objects.toString(r.getString(7),"")).put("kind","depends_on").put("dependencyType",r.getString(8)).put("confidence",1.0).put("source","oracle_catalog");
            store("e/"+String.format(Locale.ROOT,"%08d",edges++),edge);
        },scope.isBlank()?new Object[]{}:new Object[]{scope,scope}));
        for(String[] category:List.of(new String[]{"queue","SYS.all_queues","name"},new String[]{"database_link","SYS.all_db_links","db_link"},new String[]{"scheduler_job","SYS.all_scheduler_jobs","job_name"},new String[]{"scheduler_program","SYS.all_scheduler_programs","program_name"},new String[]{"scheduler_schedule","SYS.all_scheduler_schedules","schedule_name"},new String[]{"scheduler_chain","SYS.all_scheduler_chains","chain_name"})){
            optional("Oracle "+category[0],()->query("SELECT * FROM "+category[1]+ownerFilter+" ORDER BY owner,"+category[2],r->{
                String owner=r.getString("OWNER"),name=r.getString(category[2]);ObjectNode object=object(owner,name,category[0],"");
                ObjectNode properties=object.putObject("nativeProperties");var metadata=r.getMetaData();
                for(int i=1;i<=metadata.getColumnCount();i++){String key=metadata.getColumnLabel(i).toLowerCase(Locale.ROOT);String value=Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(metadata.getColumnType(i))?definition(r,i):Objects.toString(r.getString(i),"");properties.put(key,value);}
                if(category[0].equals("database_link"))object.put("externalCredentialsRequired",true);else nativeDefinition(object);
                for(var detail:OracleMetadata.schedulerDetails(category[0]))optional("Oracle "+detail.label()+" for "+owner+"."+name,()->object.set(detail.key(),nativeRows(detail.sql(),owner,name)));
                if(category[0].equals("scheduler_chain"))object.put("definitionCoverage","native_fragment").put("definitionNote",OracleMetadata.CHAIN_DEFINITION_NOTE);
                for(JsonNode argument:object.path("schedulerArguments"))if(argument.path("anydata_value_present").asText().equals("YES"))object.put("definitionCoverage","native_fragment").put("externalArgumentValueRequired",true);
                if(category[0].equals("scheduler_job")){oracleReference(owner,name,properties,"program_owner","program_name","scheduler_program");oracleReference(owner,name,properties,"schedule_owner","schedule_name","scheduler_schedule");}
                if(category[0].equals("scheduler_chain")){
                    oracleReference(owner,name,properties,"rule_set_owner","rule_set_name","scheduler_rule_set");
                    for(JsonNode step:object.path("chainSteps")){oracleReference(owner,name,step,"program_owner","program_name","scheduler_step");oracleReference(owner,name,step,"event_schedule_owner","event_schedule_name","scheduler_event_schedule");oracleReference(owner,name,step,"event_queue_owner","event_queue_name","scheduler_event_queue");}
                }
                emit(object);
            },args));
        }
        optional("Oracle legacy jobs",()->query("SELECT job,log_user,priv_user,schema_user,broken,failures,last_date,next_date,interval,what,instance FROM "+(scope.equals(oracleUser)?"SYS.USER_JOBS":"SYS.DBA_JOBS")+(scope.isBlank()?" WHERE schema_user IN (SELECT username FROM SYS.all_users WHERE oracle_maintained='N')":" WHERE schema_user=?")+" ORDER BY schema_user,job",r->{ObjectNode object=object(r.getString("SCHEMA_USER"),r.getString("JOB"),"job","");object.put("definitionSource","oracle_catalog").put("ddl",definition(r,10)).put("definitionCoverage","job_action_only").put("executionUser",r.getString("PRIV_USER")).put("interval",r.getString("INTERVAL"));emit(object);},args));
        for(boolean columns:List.of(false,true))optional(columns?"Oracle column grants":"Oracle object grants",()->query("SELECT table_schema,table_name,grantor,grantee,privilege,grantable,common,inherited,"+(columns?"column_name,'NO' AS hierarchy":"'' AS column_name,hierarchy")+" FROM SYS."+(columns?"ALL_COL_PRIVS":"ALL_TAB_PRIVS")+(scope.isBlank()?" WHERE table_schema IN (SELECT username FROM SYS.ALL_USERS WHERE oracle_maintained='N')":" WHERE table_schema=?")+" ORDER BY table_schema,table_name,grantor,grantee,privilege,column_name",r->{
            String column=Objects.toString(r.getString("COLUMN_NAME"),"");ObjectNode object=object(r.getString("TABLE_SCHEMA"),r.getString("TABLE_NAME"),columns?"column_grant":"grant",r.getString("GRANTOR")+":"+r.getString("GRANTEE")+":"+r.getString("PRIVILEGE")+":"+column);
            object.put("grantor",r.getString("GRANTOR")).put("grantee",r.getString("GRANTEE")).put("privilege",r.getString("PRIVILEGE")).put("grantable",r.getString("GRANTABLE")).put("column",column).put("hierarchy",r.getString("HIERARCHY")).put("common",r.getString("COMMON")).put("inherited",r.getString("INHERITED")).put("definitionSource","oracle_catalog").put("definitionCoverage","grant_attributes");emit(object);
        },args));
    }
    private void oracleReference(String owner,String name,JsonNode row,String ownerKey,String nameKey,String kind){
        String targetOwner=row.path(ownerKey).asText(),target=row.path(nameKey).asText();if(!targetOwner.isBlank()&&!target.isBlank())dependency(owner,name,targetOwner,target,kind);
    }
    private void inventory(String label,String sql,String scope)throws Exception{
        String column=sql.substring(7,sql.indexOf(','));String scopedSql=sql+(sql.contains(" WHERE ")?" AND ":" WHERE ")+"LOWER("+column+") NOT IN ('information_schema','sys','system','mysql','performance_schema','syscat','sysibm','sysstat')"+(engine.equals("postgresql")?" AND "+column+" !~ '^pg_'":"")+(scope.isBlank()?"":" AND "+column+"=?");
        optional(label,()->query(scopedSql,r->{String schema=Objects.toString(r.getString(1),"");if(systemSchema(schema)||!scope.isBlank()&&!scope.equals(schema))return;ObjectNode o=object(schema,Objects.toString(r.getString(2),""),Objects.toString(r.getString(3),"object"),Objects.toString(r.getString(4),""));String ddl=definition(r,5);o.put("ddl",ddl).put("definitionSource","native").put("definitionCoverage",ddl.isBlank()?"unavailable":"native_fragment");emit(o);},scope.isBlank()?new Object[]{}:new Object[]{scope}));
    }
    private ArrayNode nativeRows(String sql,Object... args)throws Exception{
        ArrayNode out=Profiles.JSON.createArrayNode();query(sql,row->{bounded(out);ObjectNode value=out.addObject();var metadata=row.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++)value.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT),Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(metadata.getColumnType(i))?definition(row,i):Objects.toString(row.getString(i),""));},args);return out;
    }
    private String definition(ResultSet rows,int column)throws Exception{
        try(var reader=rows.getCharacterStream(column)){
            if(reader==null)return "";var text=new StringBuilder();char[] chunk=new char[4096];int count;
            while((count=reader.read(chunk))!=-1){check();if(text.length()+count>limits.ddlCharacters())throw new CaptureLimit("Definition exceeds capture limit; narrow the scope");text.append(chunk,0,count);}
            return text.toString();
        }
    }
    static String stable(JsonNode node){if(node.isArray()){List<String> values=new ArrayList<>();node.forEach(v->values.add(stable(v)));Collections.sort(values);return values.toString();}if(node.isObject()){TreeMap<String,String> values=new TreeMap<>();node.fields().forEachRemaining(e->values.put(e.getKey(),stable(e.getValue())));return values.toString();}return node.toString();}
    private void supplementalInventory(DatabaseMetaData metadata)throws Exception{
        String requested=binding.path("schema").asText();if(!requested.isBlank())schemas.add(requested);
        if(requested.isBlank())optional("Schema inventory",()->{try(ResultSet r=metadata.getSchemas()){while(r.next()){check();String schema=text(r,"TABLE_SCHEM");if(!systemSchema(schema)&&inScope(text(r,"TABLE_CATALOG"),schema)){if(schemas.size()>1000)throw new IllegalArgumentException("Too many schemas; narrow the binding");schemas.add(schema);}}}});
        for(String schema:schemas){
            for(String category:VendorMetadata.categories(engine)){
                String kind=VendorMetadata.kind(category);if(Set.of("tables","views","functions","procedures","triggers","table_triggers","schema_triggers").contains(kind))continue;
                if(engine.equals("postgresql")||engine.equals("oracle"))continue;
                String sql=VendorMetadata.catalogSql(engine,kind);if(sql==null)continue;
                String singular=Set.of("indices","indexes").contains(kind)?"index":kind.endsWith("s")?kind.substring(0,kind.length()-1):kind;
                optional(category+" in "+schema,()->query(sql,r->{ObjectNode object=object(schema,r.getString(2),singular,"");object.put("catalogIdentity",r.getString(1));nativeDefinition(object);emit(object);},schema));
            }
        }
        if(!engine.equals("oracle"))optional("User-defined types",()->{try(ResultSet r=metadata.getUDTs(engine.equals("oracle")?null:empty(binding.path("database").asText()),pattern(metadata,requested),"%",null)){while(r.next()){check();String schema=text(r,"TYPE_SCHEM");if(systemSchema(schema)||!inScope(text(r,"TYPE_CAT"),schema))continue;ObjectNode object=object(schema,text(r,"TYPE_NAME"),"type","");object.put("remarks",text(r,"REMARKS")).put("jdbcType",r.getInt("DATA_TYPE"));emit(object);}}});
    }
    private void dependency(String schema,String name,String targetSchema,String target,String kind){ObjectNode e=Profiles.JSON.createObjectNode().put("schema",schema).put("name",name).put("targetSchema",targetSchema).put("target",target).put("kind",kind).put("confidence",1.0).put("source","catalog");store("e/"+String.format(Locale.ROOT,"%08d",edges++),e);}
    private void emit(ObjectNode object){
        if(!seen.add(object.path("id").asText()))return;if(++objects>limits.objects())throw new CaptureLimit("Catalog object limit reached; narrow the schema scope");
        String ddl=object.path("ddl").asText();if(ddl.length()>limits.ddlCharacters())throw new CaptureLimit("Definition exceeds capture limit; narrow the scope");
        object.put("objectHash",hash(stable(object))).put("definitionHash",hash(ddl)).put("observedAt",System.currentTimeMillis());String id=object.path("id").asText();
        store("o/"+id,object);ObjectNode summary=object.deepCopy();summary.remove(List.of("ddl","columns","indexes","foreignKeys","privileges","primaryKeys","remarks","nativeColumns","nativeKeys","nativeIndexes","constraints","fieldObservations","schedulerArguments","chainSteps","chainRules"));summary.put("ddlCharacters",ddl.length());store("i/"+id,summary);report();
    }
    private void store(String key,JsonNode value){check();byte[] data=value.toString().getBytes(StandardCharsets.UTF_8);bytes+=data.length+key.length()*2L+128;if(bytes>limits.bytes())throw new CaptureLimit("Catalog byte limit reached; narrow its scope");writer.put(key,data);}
    private boolean inScope(String catalog,String schema){String db=binding.path("database").asText(),s=binding.path("schema").asText();return (db.isBlank()||catalog.isBlank()||db.equals(catalog))&&(s.isBlank()||s.equals(schema)||schema.isBlank()&&(s.equals(catalog)||engine.equals("sqlite")&&s.equals("main")));}
    static boolean systemSchema(String schema){String s=Objects.toString(schema,"").toLowerCase(Locale.ROOT);return s.startsWith("pg_")||Set.of("information_schema","sys","system","mysql","performance_schema","syscat","sysibm","sysstat").contains(s);}
    static String pattern(DatabaseMetaData m,String s)throws SQLException{if(s==null||s.isEmpty())return null;String escape=m.getSearchStringEscape();if(escape==null||escape.isEmpty())return s;return s.replace(escape,escape+escape).replace("%",escape+"%").replace("_",escape+"_");}
    private String q(String s){String mark=Set.of("mysql","mariadb","starrocks","clickhouse","hive","databricks").contains(engine)?"`":"\"";return mark+s.replace(mark,mark+mark)+mark;}
    private String qualified(String schema,String name){return schema.isBlank()?q(name):q(schema)+"."+q(name);}
    private static String empty(String s){return s==null||s.isBlank()?null:s;}
    private static String text(ResultSet r,String column){try{return Objects.toString(r.getString(column),"");}catch(SQLException e){return "";}}
    private void check(){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException("Catalog scan cancelled during "+phase);if(System.nanoTime()>=deadline)throw new CancellationException("Catalog scan exceeded its five-minute deadline during "+phase);}
    private int remainingSeconds(){check();return Math.max(1,Math.min(statementTimeout.getAsInt(),(int)Math.ceil((deadline-System.nanoTime())/1_000_000_000.0)));}
    private void bounded(ArrayNode rows){if(rows.size()>=limits.rows()||limits!=Limits.DEFAULT&&capturedRows++>=limits.rows())throw new CaptureLimit("Metadata row limit reached; narrow its scope");}
    interface Rows{void accept(ResultSet r)throws Exception;}
    interface Checked{void run()throws Exception;}
    private void query(String sql,Rows consume,Object...args)throws Exception{check();Statement prior=statement;try(PreparedStatement s=connection.prepareStatement(sql)){statement=s;execution.accept(s);try{s.setQueryTimeout(remainingSeconds());}catch(SQLFeatureNotSupportedException ignored){}try{s.setFetchSize(128);}catch(SQLFeatureNotSupportedException ignored){}for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);try(ResultSet r=s.executeQuery()){while(r.next()){check();consume.accept(r);}}}finally{statement=prior;execution.accept(prior);}}
    // MySQL-family drivers may reject SAVEPOINT on read-only connections; their catalog read errors do not abort the transaction.
    private void optional(String label,Checked work)throws Exception{Savepoint point=null;try{if(!Set.of("mysql","mariadb").contains(engine)&&!connection.getAutoCommit()&&connection.getMetaData().supportsSavepoints())point=connection.setSavepoint();work.run();}catch(SQLException|UnsupportedOperationException failure){if(point!=null)connection.rollback(point);inventoryComplete=false;warn(label+" is unavailable with this provider or database account"+(failure instanceof SQLException sql?" (SQLSTATE "+Objects.toString(sql.getSQLState(),"unknown")+", vendor code "+sql.getErrorCode()+")":""));}finally{if(point!=null)try{connection.releaseSavepoint(point);}catch(SQLException ignored){}}}
    private void warn(String warning){if(warnings.size()<100)warnings.add(warning);}
}
