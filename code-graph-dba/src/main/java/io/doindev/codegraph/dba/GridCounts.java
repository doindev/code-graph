package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.*;

/** Exact query totals, independent of editable row identity and bounded page contents. */
final class GridCounts {
    static String sql(GridResults.Context context,Connection connection)throws Exception {
        if(!context.canLimitRows)throw new IllegalArgumentException("This result cannot safely replay a single SELECT.");
        SqlReadGuard.validate(context.sql);
        String engine=GridRelation.engine(connection.getMetaData().getDatabaseProductName());
        if(!GridRelation.ENGINES.contains(engine))throw new IllegalArgumentException("Exact counts are unavailable for this JDBC vendor.");
        var statement=CCJSqlParserUtil.parse(context.sql,p->p.withTimeOut(500));
        if(!(statement instanceof PlainSelect select))throw new IllegalArgumentException("Exact counts require a supported single SELECT.");
        if(engine.equals("sqlserver")&&select.getWithItemsList()!=null&&!select.getWithItemsList().isEmpty())throw new IllegalArgumentException("Exact counts of SQL Server CTE results are unavailable.");
        boolean limited=select.getLimit()!=null||select.getOffset()!=null||select.getFetch()!=null||select.getTop()!=null;
        // Removing parameterized ordering would change the positional bind list.
        boolean boundOrder=select.getOrderByElements()!=null&&select.getOrderByElements().stream().anyMatch(order->order.toString().contains("?"));
        if(!limited&&boundOrder&&engine.equals("sqlserver"))throw new IllegalArgumentException("Exact counts of parameterized SQL Server ordering are unavailable.");
        if(!limited&&!boundOrder)select.setOrderByElements(null);
        return "SELECT COUNT(*) FROM ("+select+") cg_count";
    }
    static long read(GridResults.Context context,QueryJobs.Job job,Connection connection)throws Exception {
        if(context.countSql==null)throw new IllegalArgumentException(context.countReason);
        try(var statement=connection.prepareStatement(context.countSql)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());GridPaging.bind(statement,context.parameters);
            try(var rows=statement.executeQuery()){if(!rows.next())throw new SQLException("Count returned no row");return rows.getLong(1);}
        }finally{job.statement=null;}
    }
    static ObjectNode count(GridResults service,GridResults.Context context,QueryJobs.Job job,Connection connection)throws Exception {
        long total=read(context,job,connection);service.check(context,job);connection.rollback();
        synchronized(service){service.check(context,job);context.total=total;context.totalAt=System.currentTimeMillis();
            return Profiles.JSON.createObjectNode().put("total",total).put("capturedAt",context.totalAt).put("revision",context.revision);}
    }
}
