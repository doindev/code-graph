package io.doindev.codegraph.dba;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Browser estimated-plan adapters. Never substitutes data execution for unsupported planning. */
final class ExplainPlans {
    record Adapter(String id, String protocol, String command, String reason) {}
    static final Map<String,Adapter> REGISTRY = new LinkedHashMap<>();
    static {
        nativePlan("postgresql", "EXPLAIN (ANALYZE FALSE, FORMAT JSON) "); nativePlan("greenplum", "EXPLAIN (ANALYZE FALSE, FORMAT JSON) "); nativePlan("yugabytedb", "EXPLAIN (ANALYZE FALSE, FORMAT JSON) ");
        nativePlan("redshift", "EXPLAIN "); nativePlan("cockroachdb", "EXPLAIN "); nativePlan("mysql", "EXPLAIN FORMAT=JSON "); nativePlan("mariadb", "EXPLAIN FORMAT=JSON ");
        nativePlan("h2", "EXPLAIN "); nativePlan("hsqldb", "EXPLAIN PLAN FOR "); nativePlan("sqlite", "EXPLAIN QUERY PLAN "); nativePlan("duckdb", "EXPLAIN ");
        nativePlan("snowflake", "EXPLAIN USING JSON "); nativePlan("clickhouse", "EXPLAIN PLAN "); nativePlan("starrocks", "EXPLAIN ");
        nativePlan("trino", "EXPLAIN "); nativePlan("presto", "EXPLAIN "); nativePlan("hive", "EXPLAIN FORMATTED "); nativePlan("databricks", "EXPLAIN FORMATTED "); nativePlan("calcite", "EXPLAIN PLAN FOR "); nativePlan("teradata", "EXPLAIN ");
        register("sqlserver","showplan", "", "Requires SHOWPLAN permission on every referenced database"); register("azure-sql","showplan", "", "Requires SHOWPLAN permission on every referenced database");
        register("oracle","oracle", "", "Requires PLAN_TABLE and access to DBMS_XPLAN.DISPLAY"); register("hana","hana", "", "Requires EXPLAIN_PLAN_TABLE access");
        register("db2","db2", "", "Requires the current schema's Db2 LUW explain tables"); register("db2-zos","db2-zos", "", "Requires a current-format PLAN_TABLE");
        register("firebird","firebird", "", "Requires Jaybird statement plan facilities"); register("altibase","altibase", "", "Requires Altibase JDBC plan-only facilities");
        unavailable("db2-i","missing_driver_facility","Db2 for i Visual Explain requires IBM i diagnostic facilities; this JDBC connection does not expose a statement estimated-plan result. LUW and z/OS protocols do not apply.");
        unavailable("informix","missing_driver_facility","Informix AVOID_EXECUTE writes plans to a server-side file. This connection does not expose authenticated retrieval of that file. Runtime getExplain routines are not estimated-plan substitutes.");
        register("exasol","native","EXPLAIN VIRTUAL ","Estimated virtual-schema pushdowns only. Exasol local-table profiling requires execution and is never used.");
        unavailable("bigquery","unsupported_estimated_plan","BigQuery query-plan diagnostics require query execution. A dry run does not return an estimated operator plan.");
        unavailable("cassandra","unsupported_estimated_plan","Cassandra CQL does not expose an estimated query-plan operation."); unavailable("cosmos-cassandra","unsupported_estimated_plan","The Cosmos DB Cassandra API does not expose an estimated CQL query plan.");
        nativePlan("csv","EXPLAIN PLAN FOR "); nativePlan("json","EXPLAIN PLAN FOR "); nativePlan("redis","EXPLAIN PLAN FOR ");
        unavailable("mongodb","missing_driver_facility","MongoDB queryPlanner is native to MongoDB commands; this SQL JDBC interface does not expose the translated pipeline and an authenticated native plan facility.");
        unavailable("neo4j","missing_driver_facility","Neo4j estimated plans are result-summary metadata. This JDBC interface does not expose that native result summary.");
        nativePlan("elasticsearch","EXPLAIN "); register("opensearch","opensearch-http","","Requires the SQL plugin Explain endpoint and JDBC native HTTP transport facilities");
        unavailable("custom","unsupported_estimated_plan","No estimated-plan adapter matches the actual database metadata.");
    }
    static void nativePlan(String id,String command){register(id,"native",command,"");}
    static void register(String id,String protocol,String command,String reason){REGISTRY.put(id,new Adapter(id,protocol,command,reason));}
    static void unavailable(String id,String status,String reason){register(id,status,"",reason);}
    static String engine(DatabaseMetaData m)throws SQLException {
        String p=m.getDatabaseProductName().toLowerCase(Locale.ROOT),d=m.getDriverName().toLowerCase(Locale.ROOT),version=m.getDatabaseProductVersion().toLowerCase(Locale.ROOT);
        if(p.contains("db2")||p.contains("as/400")) { if(p.contains("as/400")||p.contains("ibm i")||d.contains("toolbox"))return "db2-i";if(p.contains("dsn")||p.contains("z/os")||version.startsWith("dsn"))return "db2-zos";return "db2"; }
        if(p.startsWith("dsn"))return "db2-zos";
        for(String id:List.of("yugabytedb","cockroachdb","redshift","greenplum","mariadb","starrocks","clickhouse","databricks","snowflake","duckdb","hsqldb","sqlite","firebird","altibase","informix","exasol","bigquery","cassandra","elasticsearch","opensearch","mongodb","neo4j","redis","teradata","trino","presto","calcite","hive","oracle","mysql","postgresql"))if(p.replace(" ","").contains(id))return id;
        if(p.contains("microsoft")||p.contains("sql server"))return "sqlserver";if(p.contains("hana"))return "hana";if(p.equals("h2"))return "h2";if(p.contains("hsql"))return "hsqldb";if(p.contains("csv"))return "csv";if(p.contains("json"))return "json";return "custom";
    }
    static ObjectNode capability(Connection c)throws SQLException {
        DatabaseMetaData m=c.getMetaData();String id=engine(m);
        if(id.equals("postgresql")){try(Statement st=c.createStatement()){st.setQueryTimeout(5);try(ResultSet rs=st.executeQuery("SELECT version()")){if(rs.next()){String brand=rs.getString(1).toLowerCase(Locale.ROOT);if(brand.contains("cockroach"))id="cockroachdb";else if(brand.contains("redshift"))id="redshift";else if(brand.contains("greenplum"))id="greenplum";else if(brand.contains("yugabyte"))id="yugabytedb";}}}}
        Adapter a=REGISTRY.get(id);String status=a.protocol.contains("unsupported")||a.protocol.equals("missing_driver_facility")?a.protocol:"available";
        int major=m.getDatabaseMajorVersion(),minor=m.getDatabaseMinorVersion();
        if(id.equals("mysql")&&(major<5||major==5&&minor<6)||id.equals("mariadb")&&major<10||id.equals("postgresql")&&major<9)status="unsupported_server_version";
        return Profiles.JSON.createObjectNode().put("engine",id).put("product",m.getDatabaseProductName()).put("version",m.getDatabaseProductVersion()).put("driver",m.getDriverName()).put("status",status).put("supported",status.equals("available")).put("estimated",true).put("protocol",a.protocol).put("reason",status.equals("unsupported_server_version")?"This server version predates the adapter's estimated-plan format":a.reason);
    }
    static ObjectNode collect(QueryJobs.Job job,Connection c,String sql,JsonNode values)throws Exception {return collect(job,c,sql,values,c.getClass().getClassLoader());}
    static ObjectNode collect(QueryJobs.Job job,Connection c,String sql,JsonNode values,ClassLoader loader)throws Exception {
        ObjectNode capability=capability(c);if(!capability.path("supported").asBoolean())throw VisualQuery.invalid(capability.path("status").asText()+": "+capability.path("reason").asText());
        String id=capability.path("engine").asText();Adapter adapter=REGISTRY.get(id);ObjectNode raw;
        job.progress="Collecting estimated plan (query rows are not executed)";
        try {
            raw=switch(adapter.protocol) {
                case "native" -> id.equals("hsqldb")&&!values.isEmpty()?query(job,c,adapter.command+literalParameters(sql,values),none()):query(job,c,adapter.command+sql,values);
                case "showplan" -> showplan(job,c,sql,values);
                case "oracle","hana","db2","db2-zos" -> planTable(job,c,adapter.protocol,sql,values);
                case "firebird" -> firebird(job,c,sql,values,loader);
                case "altibase" -> altibase(job,c,sql,values,loader);
                case "opensearch-http" -> OpenSearchExplain.collect(job,c,sql,values,loader);
                default -> throw VisualQuery.invalid("missing_driver_facility: "+adapter.reason);
            };
        }catch(SQLException e){String state=Objects.toString(e.getSQLState(),"");String category=state.equals("42501")||state.equals("42000")&&Set.of(229,262,297).contains(e.getErrorCode())||e.getErrorCode()==1031?"missing_privileges":Set.of("42P01","42704","42S02").contains(state)||Set.of(942,208).contains(e.getErrorCode())?"missing_server_prerequisites":e instanceof SQLFeatureNotSupportedException?"missing_driver_facility":"plan_collection_failed";throw new IllegalArgumentException(category+": "+(category.equals("missing_privileges")?"Grant estimated-plan and referenced-object access, then retry.":category.equals("missing_server_prerequisites")?adapter.reason:"The database rejected estimated planning. Check query validity, server version, driver facilities and privileges.")+" (SQLSTATE "+state+", code "+e.getErrorCode()+")",e);}
        ObjectNode out=normalize(id,sql,raw);out.set("capability",capability);if(id.equals("hsqldb")&&!values.isEmpty())out.withArray("observations").add("The engine plans supplied literal values because its EXPLAIN statement does not accept JDBC parameter bindings.");
        // Retain the established Script Explain shape alongside the normalized browser result.
        ArrayNode columns=out.putArray("columns");for(JsonNode name:raw.path("columns"))columns.addObject().put("id","c"+columns.size()).put("label",name.asText()).put("type","PLAN").put("jdbcType",Types.VARCHAR);
        out.set("rows",raw.path("rows"));out.put("rowCount",raw.path("rows").size()).put("analysis","Estimated plan only. No query rows were executed.");return out;
    }
    static String literalParameters(String sql,JsonNode values){ObjectNode input=Profiles.JSON.createObjectNode().put("sql",sql).put("action","refresh");input.set("parameters",values);return GridSql.prepare(input).path("displaySql").asText();}
    static void bind(PreparedStatement st,JsonNode values)throws SQLException{GridPaging.bind(st,values);}
    static ObjectNode query(QueryJobs.Job job,Connection c,String sql,JsonNode values)throws Exception {
        if(job.cancelled)throw new java.util.concurrent.CancellationException();
        try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(job.remainingSeconds());bind(st,values);try(ResultSet rs=st.executeQuery()){return raw(rs,job.byteLimit/3,Math.min(4096,job.rowLimit));}}finally{job.statement=null;}
    }
    static void command(QueryJobs.Job job,Connection c,String sql,JsonNode values)throws Exception{try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(job.remainingSeconds());bind(st,values);st.execute();}finally{job.statement=null;}}
    static ArrayNode none(){return Profiles.JSON.createArrayNode();}
    static ObjectNode showplan(QueryJobs.Job job,Connection c,String sql,JsonNode values)throws Exception{
        // Always turn OFF on this borrowed session; failed cleanup poisons the connection.
        command(job,c,"SET SHOWPLAN_XML ON",none());
        try{return query(job,c,sql,values);}finally{try{command(job,c,"SET SHOWPLAN_XML OFF",none());}catch(Exception e){c.abort(Runnable::run);throw e;}}
    }
    static ObjectNode planTable(QueryJobs.Job job,Connection c,String kind,String sql,JsonNode values)throws Exception {
        String tag="cg"+job.id.replace("-","").substring(0,26); Savepoint point=c.setSavepoint();
        try {
            if(kind.equals("oracle")){command(job,c,"EXPLAIN PLAN SET STATEMENT_ID = '"+tag+"' FOR "+sql,values);return query(job,c,"SELECT PLAN_TABLE_OUTPUT FROM TABLE(DBMS_XPLAN.DISPLAY('PLAN_TABLE', ?, 'TYPICAL'))",none().add(tag));}
            if(kind.equals("hana")){command(job,c,"EXPLAIN PLAN SET STATEMENT_NAME = '"+tag+"' FOR "+sql,values);return query(job,c,"SELECT * FROM EXPLAIN_PLAN_TABLE WHERE STATEMENT_NAME = ? ORDER BY OPERATOR_ID",none().add(tag));}
            if(kind.equals("db2")){command(job,c,"EXPLAIN PLAN SELECTION SET QUERYTAG = '"+tag+"' FOR "+sql,values);return query(job,c,"SELECT o.* FROM EXPLAIN_OPERATOR o JOIN EXPLAIN_STATEMENT s ON o.EXPLAIN_REQUESTER=s.EXPLAIN_REQUESTER AND o.EXPLAIN_TIME=s.EXPLAIN_TIME AND o.SOURCE_NAME=s.SOURCE_NAME AND o.SOURCE_SCHEMA=s.SOURCE_SCHEMA AND o.SOURCE_VERSION=s.SOURCE_VERSION AND o.EXPLAIN_LEVEL=s.EXPLAIN_LEVEL AND o.STMTNO=s.STMTNO AND o.SECTNO=s.SECTNO WHERE s.QUERYTAG = ? ORDER BY o.OPERATOR_ID",none().add(tag));}
            int number=Math.floorMod(UUID.fromString(job.id).hashCode(),Integer.MAX_VALUE-1)+1;
            // Collision check avoids selecting somebody else's existing plan. Rollback removes only this transaction's rows.
            if(!query(job,c,"SELECT QUERYNO FROM PLAN_TABLE WHERE QUERYNO = ?",none().add(number)).path("rows").isEmpty())throw VisualQuery.invalid("Plan identifier collision; retry Explain");
            command(job,c,"EXPLAIN PLAN SET QUERYNO = "+number+" FOR "+sql,values);return query(job,c,"SELECT * FROM PLAN_TABLE WHERE QUERYNO = ? ORDER BY QBLOCKNO, PLANNO",none().add(number));
        }finally{try{c.rollback(point);}catch(Exception e){c.abort(Runnable::run);throw e;}}
    }
    @SuppressWarnings({"unchecked","rawtypes"}) static Object unwrap(Object value,String name,ClassLoader loader)throws Exception{Class type=Class.forName(name,true,loader);return ((Wrapper)value).unwrap(type);}
    static ObjectNode firebird(QueryJobs.Job job,Connection c,String sql,JsonNode values,ClassLoader loader)throws Exception {
        try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(job.remainingSeconds());bind(st,values);
            try {Object nativeStatement=unwrap(st,"org.firebirdsql.jdbc.FirebirdStatement",loader);String method=c.getMetaData().getDatabaseMajorVersion()>=3?"getExplainedExecutionPlan":"getExecutionPlan";return textRaw(Objects.toString(nativeStatement.getClass().getMethod(method).invoke(nativeStatement),""),job.byteLimit/3);}
            catch(ReflectiveOperationException e){throw VisualQuery.invalid("missing_driver_facility: Install a Jaybird driver exposing statement execution plans");}
        }finally{job.statement=null;}
    }
    static ObjectNode altibase(QueryJobs.Job job,Connection c,String sql,JsonNode values,ClassLoader loader)throws Exception{
        Object nativeConnection;try{nativeConnection=unwrap(c,"Altibase.jdbc.driver.AltibaseConnection",loader);}catch(ReflectiveOperationException e){throw VisualQuery.invalid("missing_driver_facility: Altibase native plan facilities are unavailable");}
        java.lang.reflect.Method mode=nativeConnection.getClass().getMethod("setExplainPlan",byte.class);byte only=nativeConnection.getClass().getField("EXPLAIN_PLAN_ONLY").getByte(null);byte off=nativeConnection.getClass().getField("EXPLAIN_PLAN_OFF").getByte(null);
        mode.invoke(nativeConnection,only);
        try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;bind(st,values);st.setQueryTimeout(job.remainingSeconds());Object nativeStatement=unwrap(st,"Altibase.jdbc.driver.AltibaseStatement",loader);return textRaw(Objects.toString(nativeStatement.getClass().getMethod("getExplainPlan").invoke(nativeStatement),""),job.byteLimit/3);}
        finally{job.statement=null;try{mode.invoke(nativeConnection,off);}catch(Exception e){c.abort(Runnable::run);throw e;}}
    }
    static Reader textReader(ResultSet rs,int column,int limit)throws SQLException{try{return rs.getCharacterStream(column);}catch(SQLFeatureNotSupportedException unsupported){String value=rs.getString(column);return value==null?null:new StringReader(value.length()>limit+1?value.substring(0,limit+1):value);}}
    static ObjectNode raw(ResultSet rs,int maxBytes,int maxRows)throws Exception{
        ObjectNode out=Profiles.JSON.createObjectNode();ArrayNode cols=out.putArray("columns"),rows=out.putArray("rows");ResultSetMetaData meta=rs.getMetaData();int count=meta.getColumnCount();if(count>256)throw VisualQuery.invalid("Plan exceeds 256 columns");for(int i=1;i<=count;i++)cols.add(meta.getColumnLabel(i));int remaining=maxBytes/4;boolean cut=false;
        while(rs.next()){if(rows.size()>=maxRows||remaining<=0){cut=true;break;}ArrayNode row=rows.addArray();for(int i=1;i<=count;i++){if(remaining<=0){row.add("[truncated]");cut=true;continue;}try(Reader r=Set.of(Types.INTEGER,Types.BIGINT,Types.SMALLINT,Types.TINYINT,Types.NUMERIC,Types.DECIMAL,Types.DOUBLE,Types.FLOAT,Types.REAL,Types.BOOLEAN,Types.BIT).contains(meta.getColumnType(i))?new StringReader(Objects.toString(rs.getString(i),"NULL")):textReader(rs,i,remaining)){if(r==null){row.addNull();continue;}char[] b=new char[remaining+1];int used=0,n;while(used<b.length&&(n=r.read(b,used,b.length-used))>0)used+=n;if(used>remaining){used=remaining;cut=true;}row.add(new String(b,0,used));remaining-=used;}}}
        return out.put("truncated",cut);
    }
    static ObjectNode textRaw(String text,int max){boolean cut=text.length()>max/4;ObjectNode out=Profiles.JSON.createObjectNode().put("truncated",cut);out.putArray("columns").add("Plan");out.putArray("rows").addArray().add(cut?text.substring(0,max/4):text);return out;}
    static ObjectNode normalize(String engine,String sql,ObjectNode raw){
        ObjectNode out=Profiles.JSON.createObjectNode().put("engine",engine).put("estimated",true).put("executed",false).put("sourceSql",sql).put("format","tabular").put("truncated",raw.path("truncated").asBoolean()).put("costLabel",engine.equals("db2")?"Estimated cost (timerons)":"Estimated optimizer cost (engine-specific units)");out.set("raw",raw);ArrayNode nodes=out.putArray("nodes"),observations=out.putArray("observations");observations.add("Estimated plan only; no measured execution timings or runtime statistics were collected.");
        if(raw.path("columns").size()==1){StringBuilder text=new StringBuilder();for(JsonNode row:raw.path("rows")){if(!text.isEmpty())text.append('\n');text.append(row.path(0).asText());}String value=text.toString().strip();out.put("rawText",value).put("format","text");
            if(!out.path("truncated").asBoolean())try{if(value.startsWith("{")||value.startsWith("[")){ObjectMapper json=new ObjectMapper(com.fasterxml.jackson.core.JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(1048576).build()).build());JsonNode plan=json.readTree(value);out.put("format","json");walkJson(plan,nodes,0,new int[]{0},"");}else if(value.startsWith("<")){out.put("format","xml");DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");f.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth",64);f.setXIncludeAware(false);f.setExpandEntityReferences(false);walkXml(f.newDocumentBuilder().parse(new InputSource(new StringReader(value))).getDocumentElement(),nodes,0,new int[]{0});}}catch(Exception malformed){nodes.removeAll();observations.add("Structural interpretation is unavailable; original plan output is retained.");}
        }
        if(out.path("truncated").asBoolean())observations.add("Plan output was truncated at the result allowance. The raw output is incomplete.");return out;
    }
    static void walkJson(JsonNode value,ArrayNode into,int depth,int[] count,String name){if(depth>64||++count[0]>4096)throw VisualQuery.invalid("Plan structure exceeds interpretation limits");if(value.isArray()){for(JsonNode n:value)walkJson(n,into,depth+1,count,name);return;}if(!value.isObject())return;ObjectNode props=Profiles.JSON.createObjectNode();value.fields().forEachRemaining(e->{if(e.getValue().isValueNode())props.set(e.getKey(),e.getValue());});String title=value.path("Node Type").asText(value.path("operatorType").asText(value.path("name").asText(value.path("operation").asText(name))));ArrayNode children=into;if(!props.isEmpty()){ObjectNode node=into.addObject().put("operator",title.isBlank()?"Plan properties":title);node.set("properties",props);children=node.putArray("children");}final ArrayNode target=children;value.fields().forEachRemaining(e->{if(e.getValue().isContainerNode())walkJson(e.getValue(),target,depth+1,count,e.getKey());});}
    static void walkXml(Element value,ArrayNode into,int depth,int[] count){if(depth>64||++count[0]>4096)throw VisualQuery.invalid("Plan structure exceeds interpretation limits");ObjectNode node=into.addObject().put("operator",value.hasAttribute("PhysicalOp")?value.getAttribute("PhysicalOp"):value.getTagName());ObjectNode props=node.putObject("properties");NamedNodeMap attributes=value.getAttributes();for(int i=0;i<attributes.getLength();i++)props.put(attributes.item(i).getNodeName(),attributes.item(i).getNodeValue());ArrayNode children=node.putArray("children");NodeList list=value.getChildNodes();for(int i=0;i<list.getLength();i++){Node child=list.item(i);if(child instanceof Element e)walkXml(e,children,depth+1,count);else if(child.getNodeType()==Node.TEXT_NODE&&!child.getTextContent().isBlank())props.put("text",child.getTextContent());}}
}
