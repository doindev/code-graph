package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.*;
import java.sql.*;
import java.util.*;

/** Bounded page queries; never retains an open cursor between browser actions. */
final class GridPaging {
    static long windowOffset(GridResults service,GridResults.Context context,JsonNode request){
        if(context.orderedSql==null)throw new IllegalArgumentException("Automatic scrolling requires verified server paging.");
        int limit=requestedLimit(service,context,request);JsonNode offset=request.path("offset");
        if(limit!=context.limit||!offset.isIntegralNumber()||!offset.canConvertToLong())throw new IllegalArgumentException("Scroll window must use the current page size and an integer offset.");
        long value=offset.longValue(),end=context.offset+context.result.path("rows").size();
        if(value<Math.max(0,context.offset-limit)||value>end||value<0||value>9_007_199_254_740_991L)
            throw new IllegalArgumentException("Scroll window must be adjacent to the current page.");
        return value;
    }
    static ObjectNode window(GridResults service,GridResults.Context context,QueryJobs.Job job,Connection c,JsonNode request)throws Exception{
        long offset=windowOffset(service,context,request);ObjectNode metadata=Profiles.JSON.createObjectNode();metadata.set("columns",context.result.path("columns"));
        if(!GridRelation.inspect(c,context.sql,metadata).fingerprint.equals(context.relation.fingerprint)){service.pages.removeContext(context.id);throw new IllegalArgumentException("Table definition changed. Rerun the query.");}
        ObjectNode result=read(context,job,c,offset,context.limit);result.set("columns",context.result.path("columns").deepCopy());service.check(context,job);c.rollback();
        return publishWindow(service,context,job,new GridPageCache.Page(result,offset,context.limit,result.path("truncated").asBoolean(),System.currentTimeMillis()),false);
    }
    static ObjectNode publishWindow(GridResults service,GridResults.Context context,QueryJobs.Job job,GridPageCache.Page page,boolean cached)throws Exception{
        service.check(context,job);
        long bytes=Math.max(4096,Profiles.JSON.writeValueAsBytes(page.result()).length*3L+context.baseBytes);
        try{context.reservation.resize(bytes);}catch(IllegalArgumentException pressure){service.pages.close();context.reservation.resize(bytes);}
        synchronized(service){
            service.check(context,job);context.result=page.result();context.offset=page.offset();context.limit=page.limit();context.hasMore=page.more();context.capturedAt=page.capturedAt();context.cacheHit=cached;
            context.rowIds=new ArrayList<>();for(JsonNode ignored:context.result.path("rows"))context.rowIds.add(UUID.randomUUID().toString());context.revision++;context.plan=null;
            if(!cached)service.pages.put(context);
            ObjectNode result=context.result.deepCopy();result.set("grid",service.descriptor(context));return result;
        }
    }
    static String ordered(GridRelation relation,DatabaseMetaData metadata)throws Exception {
        PlainSelect select=(PlainSelect)CCJSqlParserUtil.parse(relation.sql,p->p.withTimeOut(500));
        List<OrderByElement> order=select.getOrderByElements();var parts=new ArrayList<String>();var covered=new HashSet<String>();
        // Outer wrapper names are the SELECT labels, not source table qualifiers.
        var labels=new LinkedHashMap<String,String>();int at=0;
        for(var item:select.getSelectItems()){
            if(item.getExpression() instanceof Column column){String name=GridRelation.identifier(column.getColumnName());var match=relation.columns.stream().filter(c->c.name().equalsIgnoreCase(name)).toList();if(match.size()!=1)throw new IllegalArgumentException("Column ordering is ambiguous.");String label=item.getAlias()==null?match.getFirst().name():GridRelation.identifier(item.getAlias().getName());labels.put(match.getFirst().name(),label);at++;}
            else for(var column:relation.columns)labels.put(column.name(),column.name());
        }
        if(new HashSet<>(labels.values()).size()!=labels.size())throw new IllegalArgumentException("Duplicate output labels prevent server paging; use unique aliases.");
        if(order!=null)for(OrderByElement item:order){if(!(item.getExpression() instanceof Column column))throw new IllegalArgumentException("Paging needs simple column ordering; expression ordering is not verified.");String name=GridRelation.identifier(column.getColumnName());String base=labels.keySet().stream().filter(n->n.equalsIgnoreCase(name)||labels.get(n).equalsIgnoreCase(name)).findFirst().orElseThrow(()->new IllegalArgumentException("Include ordered columns in the result."));covered.add(base);parts.add("cg."+GridRelation.quote(metadata,labels.get(base))+(item.isAsc()?" ASC":" DESC")+(item.getNullOrdering()==null?"":" "+item.getNullOrdering().toString().replace('_',' ')));}
        boolean limited=select.getLimit()!=null||select.getOffset()!=null||select.getFetch()!=null||select.getTop()!=null;
        if(limited&&!covered.containsAll(relation.keys))throw new IllegalArgumentException("A SQL-limited result needs its own unique ORDER BY before paging.");
        for(String key:relation.keys)if(!covered.contains(key))parts.add("cg."+GridRelation.quote(metadata,labels.get(key))+" ASC");
        return "SELECT * FROM ("+scope(relation)+") cg ORDER BY "+String.join(", ",parts);
    }
    static String scope(GridRelation relation)throws Exception{
        PlainSelect select=(PlainSelect)CCJSqlParserUtil.parse(relation.sql,p->p.withTimeOut(500));String sql=select.toString();
        if(relation.engine.equals("sqlserver")&&select.getOrderByElements()!=null&&select.getOffset()==null&&select.getTop()==null&&select.getFetch()==null)sql+=" OFFSET 0 ROWS";
        return sql;
    }
    static void bind(PreparedStatement statement,JsonNode parameters)throws SQLException{for(int i=0;i<parameters.size();i++){JsonNode value=parameters.get(i);if(value.isNull())statement.setNull(i+1,Types.NULL);else if(value.isNumber())statement.setBigDecimal(i+1,value.decimalValue());else if(value.isBoolean())statement.setBoolean(i+1,value.asBoolean());else statement.setString(i+1,value.asText());}}
    static ObjectNode read(GridResults.Context context,QueryJobs.Job job,Connection c,long offset,int limit)throws Exception{
        if(job.cancelled)throw new java.util.concurrent.CancellationException();
        String sql=context.orderedSql==null?context.sql:context.orderedSql+(context.relation.engine.equals("sqlserver")?" OFFSET "+offset+" ROWS FETCH NEXT "+(limit+1)+" ROWS ONLY":" LIMIT "+(limit+1)+" OFFSET "+offset);
        try(var statement=c.prepareStatement(sql,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setFetchSize(64);statement.setMaxRows(limit+1);bind(statement,context.parameters);
            try(var rs=statement.executeQuery()){return QueryJobs.rows(rs,limit,job.byteLimit/2).put("kind","rows").put("sourceSql",context.orderedSql==null?context.sql:context.orderedSql);}
        }finally{job.statement=null;}
    }
    static int requestedLimit(GridResults service,GridResults.Context context,JsonNode request){
        int ceiling=service.config.get().uiRows();JsonNode value=request.get("limit");
        if(value==null)return Math.min(context.limit,ceiling);
        if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1||value.intValue()>ceiling)
            throw new IllegalArgumentException("Rows per page must be an integer between 1 and "+ceiling+".");
        return value.intValue();
    }
    static ObjectNode reload(GridResults service,GridResults.Context context,QueryJobs.Job job,Connection c,JsonNode request)throws Exception{
        GridResults.validateReload(context);int limit=requestedLimit(service,context,request);
        String direction=request.path("direction").asText("refresh");if(!Set.of("first","refresh").contains(direction))throw new IllegalArgumentException("This query supports a bounded first-page reload, not server paging.");
        ObjectNode next=read(context,job,c,0,limit);next.set("columns",context.result.path("columns").deepCopy());service.check(context,job);c.rollback();
        context.reservation.resize(Math.max(4096,Profiles.JSON.writeValueAsBytes(next).length*3L+context.baseBytes));
        synchronized(service){service.check(context,job);context.result=next;context.capturedAt=System.currentTimeMillis();context.cacheHit=false;context.rowIds=new ArrayList<>();for(JsonNode ignored:next.path("rows"))context.rowIds.add(UUID.randomUUID().toString());context.offset=0;context.limit=limit;context.uncertain=false;context.hasMore=next.path("truncated").asBoolean();context.revision++;context.plan=null;
            ObjectNode out=next.deepCopy();out.set("grid",service.descriptor(context));return out;}
    }
    static ObjectNode page(GridResults service,GridResults.Context context,QueryJobs.Job job,Connection c,JsonNode request,boolean reconcile)throws Exception{
        if(context.orderedSql==null)throw new IllegalArgumentException(context.reason);
        int limit=requestedLimit(service,context,request);
        String direction=request.path("direction").asText("refresh");if(!Set.of("first","last","next","previous","refresh").contains(direction))throw new IllegalArgumentException("Invalid page direction.");
        ObjectNode metadata=Profiles.JSON.createObjectNode();metadata.set("columns",context.result.path("columns"));
        if(!GridRelation.inspect(c,context.sql,metadata).fingerprint.equals(context.relation.fingerprint))throw new IllegalArgumentException("Table definition changed. Rerun the query.");
        // One short serializable read transaction gives count and page a consistent boundary.
        long total;try(var count=c.prepareStatement("SELECT COUNT(*) FROM ("+scope(context.relation)+") cg")){
            job.statement=count;count.setQueryTimeout(job.remainingSeconds());bind(count,context.parameters);try(var rs=count.executeQuery()){if(!rs.next())throw new SQLException("Count returned no row");total=rs.getLong(1);}
        }
        long offset=switch(direction){case "first"->0;case "last"->Math.max(0,total-limit);case "next"->Math.min(total,context.offset+context.result.path("rows").size());case "previous"->Math.max(0,context.offset-limit);default->Math.min(context.offset,Math.max(0,total-limit));};
        service.check(context,job);ObjectNode result=read(context,job,c,offset,limit);
        result.set("columns",context.result.path("columns").deepCopy());
        long boundary=direction.equals("last")?total:direction.equals("previous")?context.offset:-1;
        for(int attempts=0;boundary>=0&&offset+result.path("rows").size()<boundary;attempts++){
            int actual=result.path("rows").size();if(actual==0||attempts>=31)throw new IllegalArgumentException("The final row cannot fit this page allowance; select fewer/smaller columns.");
            offset=boundary-actual;result=read(context,job,c,offset,actual);result.set("columns",context.result.path("columns").deepCopy());
        }
        service.check(context,job);c.rollback();context.reservation.resize(Math.max(4096,Profiles.JSON.writeValueAsBytes(result).length*3L+context.baseBytes));
        synchronized(service){service.check(context,job);context.result=result;context.capturedAt=System.currentTimeMillis();context.cacheHit=false;context.rowIds=new ArrayList<>();for(JsonNode ignored:result.path("rows"))context.rowIds.add(UUID.randomUUID().toString());context.offset=offset;context.limit=limit;context.hasMore=offset+result.path("rows").size()<total;context.revision++;context.plan=null;if(reconcile)context.uncertain=false;service.pages.put(context);
            ObjectNode descriptor=service.descriptor(context);((ObjectNode)descriptor.path("page")).put("total",total);result=result.deepCopy();result.set("grid",descriptor);result.put("sourceSql",context.orderedSql);return result;}
    }
}
