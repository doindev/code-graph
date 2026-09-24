package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.statement.select.*;
import java.sql.*;
import java.util.*;

/** Bounded, read-only distinct values from a server-verified result column's source. */
final class GridValues {
    record Source(String engine,String target,String column,int jdbcType) {}
    static Source source(Connection connection,GridResults.Context context,String columnId)throws Exception {
        JsonNode columns=context.result.path("columns");int index=-1;
        for(int i=0;i<columns.size();i++)if(columns.get(i).path("id").asText().equals(columnId)){index=i;break;}
        if(index<0)throw new IllegalArgumentException("Unknown result column");
        GridResults.validateReload(context);
        PlainSelect select=(PlainSelect)CCJSqlParserUtil.parse(context.sql,p->p.withTimeOut(500));
        if(select.getWithItemsList()!=null&&!select.getWithItemsList().isEmpty())
            throw new IllegalArgumentException("Server values require a direct table or view column, not a CTE.");
        Column output=GridSql.resolve(select,index,columns.get(index).path("label").asText(),columns);
        List<FromItem> sources=new ArrayList<>();sources.add(select.getFromItem());
        if(select.getJoins()!=null)for(var join:select.getJoins())sources.add(join.getRightItem());
        String qualifier=output.getTable()==null?"":output.getTable().getFullyQualifiedName();
        List<FromItem> matches=sources.stream().filter(item->{
            if(qualifier.isBlank())return sources.size()==1;
            if(item.getAlias()!=null)return equal(qualifier,item.getAlias().getName());
            return item instanceof Table table&&(equal(qualifier,table.getFullyQualifiedName())||equal(qualifier,table.getName()));
        }).toList();
        if(matches.size()!=1||!(matches.get(0) instanceof Table table))
            throw new IllegalArgumentException("Server values require an unambiguous direct table or view column.");
        DatabaseMetaData metadata=connection.getMetaData();
        String engine=GridRelation.engine(metadata.getDatabaseProductName());
        if(!GridRelation.ENGINES.contains(engine))throw new IllegalArgumentException("Server value paging is unavailable for this database vendor. Use retained grid rows.");
        String catalog=Objects.toString(connection.getCatalog(),""),schema=context.schema;
        boolean mysql=Set.of("mysql","mariadb").contains(engine);
        if(table.getDatabase()!=null&&table.getDatabase().getDatabaseName()!=null&&!catalog.equals(GridRelation.identifier(table.getDatabase().getDatabaseName())))
            throw new IllegalArgumentException("Cross-database source values require a query in that database.");
        if(table.getSchemaName()!=null)schema=normalize(metadata,table.getSchemaName());
        if(mysql){if(table.getSchemaName()!=null&&!catalog.equals(schema))throw new IllegalArgumentException("Source database does not match the result.");schema="";}
        String name=normalize(metadata,table.getName()),column=normalize(metadata,output.getColumnName());
        int tables=0;
        try(ResultSet rs=metadata.getTables(engine.equals("oracle")?null:catalog,schema.isEmpty()?null:GridRelation.pattern(metadata,schema),GridRelation.pattern(metadata,name),null)){
            while(rs.next()){
                if(!name.equals(rs.getString("TABLE_NAME")))continue;
                if(++tables>1)throw new IllegalArgumentException("Qualify the source schema before loading values.");
                String kind=rs.getString("TABLE_TYPE");
                if(!Set.of("TABLE","BASE TABLE","VIEW","MATERIALIZED VIEW").contains(kind))throw new IllegalArgumentException("This source is not a supported table or view.");
                schema=Objects.toString(rs.getString("TABLE_SCHEM"),"");
            }
        }
        if(tables!=1)throw new IllegalArgumentException("Source metadata is unavailable. Check database permissions.");
        int type=0,found=0;
        try(ResultSet rs=metadata.getColumns(engine.equals("oracle")?null:catalog,schema.isEmpty()?null:GridRelation.pattern(metadata,schema),GridRelation.pattern(metadata,name),GridRelation.pattern(metadata,column))){
            while(rs.next())if(column.equals(rs.getString("COLUMN_NAME"))){
                if(++found>1)throw new IllegalArgumentException("Ambiguous source column");
                type=rs.getInt("DATA_TYPE");
                if(!GridRelation.scalarTypeSupported(engine,type,rs.getString("TYPE_NAME"),rs.getInt("COLUMN_SIZE")))
                    throw new IllegalArgumentException("This column type does not support complete scalar value filtering.");
            }
        }
        if(found!=1)throw new IllegalArgumentException("Source column metadata changed or is unavailable. Rerun the query.");
        String prefix=mysql?catalog:schema;
        return new Source(engine,(prefix.isEmpty()?"":GridRelation.quote(metadata,prefix)+".")+GridRelation.quote(metadata,name),GridRelation.quote(metadata,column),type);
    }
    static ObjectNode read(GridResults service,GridResults.Context context,QueryJobs.Job job,Connection connection,JsonNode request)throws Exception {
        String columnId=Profiles.text(request,"columnId",256),search=request.path("search").asText("");
        if(request.has("search")&&!request.path("search").isTextual()||search.length()>512)throw new IllegalArgumentException("Search must be text of at most 512 characters.");
        JsonNode position=request.path("offset");
        if(!position.isMissingNode()&&(!position.isIntegralNumber()||!position.canConvertToInt()||position.asInt()<0))throw new IllegalArgumentException("Invalid values offset.");
        for(String option:List.of("showRowCount","showDistinctValuesCount"))if(request.has(option)&&!request.path(option).isBoolean())throw new IllegalArgumentException("Count options must be Boolean.");
        int offset=position.asInt(0),limit=Math.min(200,service.config.get().uiRows());
        Source source=source(connection,context,columnId);service.check(context,job);
        boolean counts=request.path("showDistinctValuesCount").asBoolean(),totals=request.path("showRowCount").asBoolean();
        String count=source.engine.equals("sqlserver")?"COUNT_BIG(*)":"COUNT(*)";
        String cast=switch(source.engine){case "postgresql"->"TEXT";case "mysql","mariadb"->"CHAR";case "sqlserver"->"NVARCHAR(MAX)";case "oracle"->"VARCHAR2(4000)";default->"VARCHAR";};
        String searchValue="%"+search.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_").replace("[","![")+"%";
        String where=search.isEmpty()?"":" WHERE (LOWER(CAST("+source.column+" AS "+cast+")) LIKE ? ESCAPE '!'"+("null".contains(search.toLowerCase(Locale.ROOT))?" OR "+source.column+" IS NULL":"")+")";
        String from=" FROM "+source.target,group=" GROUP BY "+source.column;
        String sql=(counts?"SELECT "+source.column+", "+count:"SELECT DISTINCT "+source.column)+from+where+(counts?group:"")+" ORDER BY "+source.column+" ASC";
        sql+=Set.of("sqlserver","oracle").contains(source.engine)?" OFFSET "+offset+" ROWS FETCH NEXT "+(limit+1)+" ROWS ONLY":" LIMIT "+(limit+1)+" OFFSET "+offset;
        ObjectNode out=Profiles.JSON.createObjectNode().put("columnId",columnId).put("jdbcType",source.jdbcType).put("offset",offset).put("limit",limit).put("coverage","source").put("source",source.target);
        ArrayNode entries=out.putArray("values");
        try(var statement=connection.prepareStatement(sql)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setFetchSize(64);statement.setMaxRows(limit+1);
            if(!search.isEmpty())statement.setString(1,searchValue);
            try(ResultSet rs=statement.executeQuery()){
                ObjectNode result=QueryJobs.rows(rs,limit,job.byteLimit/2);
                if(result.path("cellsTruncated").asBoolean())throw new IllegalArgumentException("Some values exceed the complete-value limit. Refine the search; truncated previews cannot be selected.");
                for(JsonNode row:result.path("rows")){
                    ObjectNode entry=entries.addObject();entry.set("value",row.get(0));
                    if(counts)entry.put("count",row.get(1).asText());
                }
                boolean more=result.path("truncated").asBoolean();
                if(more&&entries.isEmpty())throw new IllegalArgumentException("Values exceed the result allowance. Refine the search.");
                out.put("hasMore",more).put("nextOffset",(long)offset+entries.size());
            }
        }finally{job.statement=null;}
        if(totals){
            out.put("totalDistinct",count(job,connection,"SELECT "+count+" FROM (SELECT "+source.column+from+group+") cg",null));
            out.put("matchingDistinct",search.isEmpty()?out.path("totalDistinct").asText():count(job,connection,"SELECT "+count+" FROM (SELECT "+source.column+from+where+group+") cg",searchValue));
        }
        service.check(context,job);connection.rollback();return out;
    }
    private static String count(QueryJobs.Job job,Connection connection,String sql,String search)throws Exception{
        try(var statement=connection.prepareStatement(sql)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());if(search!=null)statement.setString(1,search);
            try(var rs=statement.executeQuery()){if(!rs.next())throw new SQLException("Count returned no row");return rs.getString(1);}
        }finally{job.statement=null;}
    }
    private static String normalize(DatabaseMetaData metadata,String value)throws SQLException {
        if(GridRelation.quoted(value))return GridRelation.identifier(value);
        if(metadata.storesUpperCaseIdentifiers())return value.toUpperCase(Locale.ROOT);
        if(metadata.storesLowerCaseIdentifiers())return value.toLowerCase(Locale.ROOT);
        return value;
    }
    private static boolean equal(String a,String b){return a.equals(b)||!GridRelation.quoted(a)&&!GridRelation.quoted(b)&&a.equalsIgnoreCase(b);}
    private GridValues(){}
}
