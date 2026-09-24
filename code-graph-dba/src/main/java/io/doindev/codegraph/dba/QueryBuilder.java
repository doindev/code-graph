package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;

/** Browser-only catalog descriptions and loss-checked SELECT import. Never executes generated DDL. */
final class QueryBuilder {
    static ObjectNode source(QueryJobs.Job job,Connection c,ObjectNode selection,int timeout)throws Exception {
        ObjectNode out=TableQueries.prepare(job,c,selection,timeout);
        String table=out.path("sql").asText().substring(14),schema=out.path("schema").asText(),name=out.path("name").asText();
        out.put("database",OracleDialect.database(job,c));out.put("reference",table).put("quote",quote(c)).put("engine",ExplainPlans.engine(c.getMetaData()));out.set("explainCapability",ExplainPlans.capability(c));out.put("engine",out.path("explainCapability").path("engine").asText());
        out.set("columns",columns(job,c,table,timeout));
        ArrayNode relations=out.putArray("relationships");String catalog=c.getCatalog();
        if(out.path("kind").asText().equals("tables")){
            try{keys(job,c.getMetaData().getImportedKeys(catalog,schema,name),relations,"outgoing");keys(job,c.getMetaData().getExportedKeys(catalog,schema,name),relations,"incoming");}
            catch(SQLFeatureNotSupportedException unavailable){out.put("relationshipNotice","The driver does not expose foreign-key relationships");}
        }else{
            String product=c.getMetaData().getDatabaseProductName(),engine=product.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(product);
            String sql=switch(engine){case "postgresql"->"SELECT pg_catalog.pg_get_viewdef(c.oid,true) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('v','m')";case "oracle"->out.path("kind").asText().equals("materialized_views")?"SELECT QUERY FROM ALL_MVIEWS WHERE OWNER=? AND MVIEW_NAME=?":"SELECT TEXT FROM ALL_VIEWS WHERE OWNER=? AND VIEW_NAME=?";case "h2","hsqldb","mysql","mariadb"->"SELECT VIEW_DEFINITION FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";default->null;};
            if(sql!=null)try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(timeout);st.setString(1,schema);st.setString(2,name);try(ResultSet rs=st.executeQuery()){if(rs.next())try(var reader=rs.getCharacterStream(1)){if(reader!=null){char[] buffer=new char[16385];int count=0,n;while(count<buffer.length&&(n=reader.read(buffer,count,buffer.length-count))>0)count+=n;if(count>16384)out.put("definitionNotice","View SQL exceeds the 16 KiB editor limit");else out.put("definition",new String(buffer,0,count));}}}}finally{job.statement=null;}
            if(!out.has("definition"))out.put("definitionNotice",out.path("definitionNotice").asText("The driver does not expose this view definition, or access is denied. Paste its SELECT to import it."));
        }
        return out;
    }
    static String quote(Connection c)throws SQLException {String q=c.getMetaData().getIdentifierQuoteString();if(q==null||q.isBlank())throw new SQLFeatureNotSupportedException("The driver does not advertise identifier quoting");return q.trim();}
    static ArrayNode columns(QueryJobs.Job job,Connection c,String reference,int timeout)throws Exception {
        ArrayNode out=Profiles.JSON.createArrayNode();
        // Empty SELECT obtains exact projected columns, including views. Never requests table data.
        try(PreparedStatement st=c.prepareStatement("SELECT * FROM "+reference+" WHERE 1=0")){job.statement=st;st.setQueryTimeout(timeout);st.setMaxRows(1);
            try(ResultSet rs=st.executeQuery()){ResultSetMetaData m=rs.getMetaData();if(m.getColumnCount()>256)throw new IllegalArgumentException("Builder sources are limited to 256 columns");for(int i=1;i<=m.getColumnCount();i++){
                ObjectNode column=out.addObject().put("name",bounded(m.getColumnLabel(i))).put("type",bounded(m.getColumnTypeName(i))).put("jdbcType",m.getColumnType(i));
                try{column.put("precision",m.getPrecision(i));column.put("scale",m.getScale(i));column.put("length",m.getColumnDisplaySize(i));column.put("signed",m.isSigned(i));}catch(SQLException|AbstractMethodError unavailable){/* Missing modifiers disable conversions that depend on them. */}
            }}
        }finally{job.statement=null;}return out;
    }
    private static String bounded(String value){if(value==null)return "";if(value.length()>2048)throw new IllegalArgumentException("Oversized catalog identifier");return value;}
    private static void keys(QueryJobs.Job job,ResultSet rs,ArrayNode out,String direction)throws Exception{
        try(rs){while(rs.next()){if(job.cancelled)throw new java.util.concurrent.CancellationException();if(out.size()>=256)throw new IllegalArgumentException("Relationship diagram exceeds 256 foreign-key columns");ObjectNode key=out.addObject().put("direction",direction);for(String field:List.of("FK_NAME","FKTABLE_CAT","FKTABLE_SCHEM","FKTABLE_NAME","FKCOLUMN_NAME","PKTABLE_CAT","PKTABLE_SCHEM","PKTABLE_NAME","PKCOLUMN_NAME"))key.put(field,bounded(rs.getString(field)));key.put("sequence",rs.getInt("KEY_SEQ"));}}
    }
    static ObjectNode analyze(QueryJobs.Job job,Connection c,String sql,int timeout)throws Exception{
        ObjectNode out=importSql(sql);out.put("database",OracleDialect.database(job,c)).put("quote",quote(c)).put("engine",ExplainPlans.engine(c.getMetaData()));out.set("explainCapability",ExplainPlans.capability(c));out.put("engine",out.path("explainCapability").path("engine").asText());
        int retained=0;
        if(out.path("editable").asBoolean())for(JsonNode item:out.path("model").path("sources")){
            ObjectNode source=(ObjectNode)item;
            try{source.set("columns",columns(job,c,source.path("reference").asText(),timeout));}
            catch(SQLException e){source.putArray("columns");source.put("notice","Column discovery unavailable. Check object access and qualification; the original SQL is preserved.");}
            retained+=Profiles.JSON.writeValueAsBytes(source).length;
            if(retained>1048576){out.put("editable",false).put("notice","Source metadata exceeds the bounded visual model allowance. SQL is preserved in text mode.");out.remove("model");break;}
        }
        if(out.path("editable").asBoolean())try{ObjectNode visual=VisualQueryImport.convert(sql,out.path("model"));ObjectNode request=Profiles.JSON.createObjectNode().put("quote",quote(c)).put("engine",ExplainPlans.engine(c.getMetaData()));request.set("model",visual);ObjectNode compiled=VisualQuery.compile(request);if(!compiled.path("structurallyValid").asBoolean())throw VisualQuery.invalid(compiled.path("diagnostics").toString());out.set("visualModel",visual);}catch(Exception unsupported){out.put("editable",false).put("notice","The complete query requires constructs outside the visual editor. Its exact SQL is preserved for inspection and Open in Script.");}
        return out;
    }
    static ObjectNode importSql(String sql){
        if(sql==null||sql.isBlank()||sql.length()>16384)throw new IllegalArgumentException("Import one SELECT of at most 16 KiB");
        ObjectNode out=Profiles.JSON.createObjectNode().put("sql",sql).put("editable",false);
        try{
            var statements=CCJSqlParserUtil.parseStatements(sql,p->p.withTimeOut(500));
            if(statements.size()!=1||!(statements.get(0) instanceof PlainSelect select))throw new IllegalArgumentException();
            ObjectNode model=Profiles.JSON.createObjectNode().put("distinct",select.getDistinct()!=null).put("where",Objects.toString(select.getWhere(),"")).put("having",Objects.toString(select.getHaving(),""));
            model.put("group",select.getGroupBy()==null?"":select.getGroupBy().getGroupByExpressionList().toString());
            model.put("order",select.getOrderByElements()==null?"":String.join(", ",select.getOrderByElements().stream().map(Object::toString).toList()));
            ArrayNode sources=model.putArray("sources"),projections=model.putArray("projections");
            model.set("tree",joinTree(sourceTree(select.getFromItem(),sources,0),select.getJoins(),sources,0));
            for(var item:select.getSelectItems())projections.add(item.toString());
            if(projections.size()>256)throw new IllegalArgumentException();
            // Compare full AST serialization after reconstruction. Unknown clauses are never dropped.
            String rebuilt=build(model);if(!CCJSqlParserUtil.parse(rebuilt,p->p.withTimeOut(500)).toString().equals(select.toString()))throw new IllegalArgumentException();
            ObjectNode validation=Profiles.JSON.createObjectNode().put("sql",rebuilt).put("action","refresh");validation.putArray("parameters");
            // Do not require parameters on import. Execution performs its existing parameter checks.
            if(!sql.contains("?"))GridSql.prepare(validation);
            out.put("editable",true);out.set("model",model);
        }catch(Exception unsupported){out.put("notice","This SQL cannot be represented losslessly by the visual editor. Its exact text is preserved in SQL mode. Edit there, or start a separate query; no clauses have been discarded.");}
        return out;
    }
    private static ObjectNode sourceTree(FromItem item,ArrayNode sources,int depth){
        if(depth>64)throw VisualQuery.invalid("Join nesting exceeds the visual limit");
        if(item instanceof Table table){if(table.getAlias()!=null)table.getAlias().setUseAs(true);addSource(sources,table,"","");return VisualQuery.expression("source").put("source",sources.get(sources.size()-1).path("id").asText());}
        if(item instanceof ParenthesedFromItem group&&group.getAlias()==null){ObjectNode node=VisualQuery.expression("group");node.set("child",joinTree(sourceTree(group.getFromItem(),sources,depth+1),group.getJoins(),sources,depth+1));return node;}
        throw VisualQuery.invalid("Unsupported FROM operand");
    }
    private static ObjectNode joinTree(ObjectNode root,List<Join> joins,ArrayNode sources,int depth){
        if(joins==null)return root;
        for(Join join:joins){
            if(join.isNatural()||join.isSimple()||join.isSemi()||join.isStraight()||join.isApply()||join.isGlobal()||join.isWindowJoin()||join.getJoinHint()!=null||join.getUsingColumns()!=null&&!join.getUsingColumns().isEmpty())throw VisualQuery.invalid("Unsupported join constraints");
            String type=join.isLeft()?"LEFT":join.isRight()?"RIGHT":join.isFull()?"FULL":join.isCross()?"CROSS":"INNER";
            if(type.equals("INNER"))join.setInner(true);join.setOuter(false);
            ObjectNode node=VisualQuery.expression("join").put("id","j"+UUID.randomUUID()).put("type",type).put("on",join.getOnExpressions()==null?"":String.join(" AND ",join.getOnExpressions().stream().map(Object::toString).toList()));
            node.set("left",root);node.set("right",sourceTree(join.getRightItem(),sources,depth+1));root=node;
        }return root;
    }
    private static String treeSql(JsonNode tree,JsonNode model){
        if(tree.path("kind").asText().equals("group"))return "("+treeSql(tree.path("child"),model)+")";
        if(tree.path("kind").asText().equals("source")){for(JsonNode s:model.path("sources"))if(s.path("id").equals(tree.path("source")))return s.path("reference").asText()+(s.path("alias").asText().isBlank()?"":" AS "+s.path("alias").asText());throw VisualQuery.invalid("Missing source");}
        return treeSql(tree.path("left"),model)+" "+tree.path("type").asText()+" JOIN "+treeSql(tree.path("right"),model)+(tree.path("type").asText().equals("CROSS")?"":" ON "+tree.path("on").asText());
    }
    private static void addSource(ArrayNode sources,Table table,String join,String on){sources.addObject().put("id","s"+(sources.size()+1)).put("name",table.getUnquotedName()).put("reference",table.getFullyQualifiedName()).put("alias",table.getAlias()==null?"":table.getAlias().getName()).put("join",join).put("on",on).putArray("columns");}
    static String build(JsonNode model){
        List<String> projection=new ArrayList<>();model.path("projections").forEach(p->projection.add(p.asText()));
        StringBuilder sql=new StringBuilder("SELECT ").append(model.path("distinct").asBoolean()?"DISTINCT ":"").append(projection.isEmpty()?"*":String.join(", ",projection));
        if(model.has("tree"))sql.append(" FROM ").append(treeSql(model.path("tree"),model));
        else {int i=0;for(JsonNode source:model.path("sources")){sql.append(i++==0?" FROM ":" "+source.path("join").asText()+" JOIN ").append(source.path("reference").asText());if(!source.path("alias").asText().isBlank())sql.append(" AS ").append(source.path("alias").asText());if(i>1&&!source.path("join").asText().equals("CROSS"))sql.append(" ON ").append(source.path("on").asText());}}
        for(String[] clause:new String[][]{{"where"," WHERE "},{"group"," GROUP BY "},{"having"," HAVING "},{"order"," ORDER BY "}})if(!model.path(clause[0]).asText().isBlank())sql.append(clause[1]).append(model.path(clause[0]).asText());return sql.toString();
    }
}
