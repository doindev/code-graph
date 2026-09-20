package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.*;
import net.sf.jsqlparser.statement.select.*;
import java.sql.*;
import java.util.*;

/** Only automatically updatable, direct base-table views. DML always targets the view itself. */
final class GridViews {
    static GridRelation inspect(Connection c,String engine,String catalog,String schema,String name,String sql,ObjectNode result)throws Exception{
        if(!Set.of("postgresql","mysql","mariadb").contains(engine))throw new IllegalArgumentException("This vendor's view row-write adapter is not verified.");
        String definition,check;
        try(var s=c.prepareStatement("SELECT VIEW_DEFINITION, CHECK_OPTION, IS_UPDATABLE FROM information_schema.views WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){
            s.setQueryTimeout(3);s.setString(1,engine.equals("postgresql")?schema:catalog);s.setString(2,name);
            try(var rs=s.executeQuery()){if(!rs.next()||!"YES".equalsIgnoreCase(rs.getString(3)))throw new IllegalArgumentException("This view is not automatically updatable.");definition=GridExports.cell(rs,1);check=rs.getString(2);if(rs.next()||definition==null||definition.length()>16384)throw new IllegalArgumentException("View definition is unavailable or exceeds validation limits.");}
        }
        SqlReadGuard.validate(definition);var parsed=CCJSqlParserUtil.parse(definition,p->p.withTimeOut(500));
        if(!(parsed instanceof PlainSelect select)||!(select.getFromItem() instanceof Table table)||select.getJoins()!=null&&!select.getJoins().isEmpty()
                ||select.getDistinct()!=null||select.getGroupBy()!=null||select.getHaving()!=null||select.getLimit()!=null||select.getOffset()!=null
                ||select.getFetch()!=null||select.getTop()!=null||select.getWithItemsList()!=null&&!select.getWithItemsList().isEmpty())
            throw new IllegalArgumentException("View editing requires one direct base table without aggregation or row limits.");
        DatabaseMetaData m=c.getMetaData();String baseName=GridRelation.identifier(table.getName()),baseSchema=table.getSchemaName()==null?schema:GridRelation.identifier(table.getSchemaName());
        if(!GridRelation.quoted(table.getName())){if(m.storesLowerCaseIdentifiers())baseName=baseName.toLowerCase(Locale.ROOT);if(m.storesUpperCaseIdentifiers())baseName=baseName.toUpperCase(Locale.ROOT);}
        if(table.getDatabase()!=null&&table.getDatabase().getDatabaseName()!=null&&!catalog.equals(GridRelation.identifier(table.getDatabase().getDatabaseName())))throw new IllegalArgumentException("Cross-catalog view mapping is unavailable.");
        String prefix=engine.equals("postgresql")?baseSchema:catalog;
        if(!engine.equals("postgresql")&&table.getSchemaName()!=null&&!catalog.equals(baseSchema))throw new IllegalArgumentException("Cross-catalog view mapping is unavailable.");
        try(var rs=m.getTables(catalog,engine.equals("postgresql")?GridRelation.pattern(m,baseSchema):null,GridRelation.pattern(m,baseName),null)){
            if(!rs.next()||!Set.of("TABLE","BASE TABLE").contains(rs.getString("TABLE_TYPE"))||rs.next())throw new IllegalArgumentException("Nested or ambiguous views remain read-only.");
        }
        String baseSql="SELECT * FROM "+GridRelation.quote(m,prefix)+"."+GridRelation.quote(m,baseName);
        ObjectNode metadata;try(var s=c.prepareStatement(baseSql+" WHERE 1=0")){s.setQueryTimeout(3);s.setMaxRows(1);try(var rs=s.executeQuery()){metadata=QueryJobs.rows(rs,1);}}
        GridRelation base=GridRelation.inspect(c,baseSql,metadata);
        var sourceNames=new ArrayList<String>();for(var item:select.getSelectItems()){
            if(item.getExpression() instanceof Column col)sourceNames.add(GridRelation.identifier(col.getColumnName()));
            else if(item.getExpression() instanceof AllColumns||item.getExpression() instanceof AllTableColumns)sourceNames.addAll(base.columns.stream().map(GridRelation.Column::name).toList());
            else throw new IllegalArgumentException("Computed view projections remain read-only.");
        }
        var viewNames=new ArrayList<String>();try(var rs=m.getColumns(catalog,engine.equals("postgresql")?GridRelation.pattern(m,schema):null,GridRelation.pattern(m,name),"%")){while(rs.next()){if(viewNames.size()>=256)throw new IllegalArgumentException("View exceeds column limits.");viewNames.add(rs.getString("COLUMN_NAME"));}}
        if(sourceNames.size()!=viewNames.size()||new HashSet<>(sourceNames).size()!=sourceNames.size())throw new IllegalArgumentException("View column mapping is ambiguous.");
        var mapping=new LinkedHashMap<String,GridRelation.Column>();for(int i=0;i<sourceNames.size();i++){String source=sourceNames.get(i);var matches=base.columns.stream().filter(col->col.name().equals(source)).toList();if(matches.size()!=1)throw new IllegalArgumentException("View column mapping could not be verified.");mapping.put(viewNames.get(i),matches.getFirst());}
        var keys=new ArrayList<String>();for(String key:base.keys){var match=mapping.entrySet().stream().filter(e->e.getValue().name().equals(key)).toList();if(match.size()!=1)throw new IllegalArgumentException("View does not preserve a verified unique key.");keys.add(match.getFirst().getKey());}
        var columns=new ArrayList<GridRelation.Column>();Set<String> seen=new HashSet<>();int index=0;
        for(JsonNode output:result.path("columns")){
            String viewName=output.path("name").asText();var col=mapping.get(viewName);String origin=output.path("table").asText();
            if(col==null||!seen.add(viewName)||!Set.of(name,base.table).contains(origin))throw new IllegalArgumentException("Result-to-view column provenance is ambiguous.");
            columns.add(new GridRelation.Column(output.path("id").asText(),viewName,col.type(),col.size(),col.scale(),col.nullable(),col.generated(),col.hasDefault(),index++));
        }
        for(JsonNode row:result.path("rows"))for(var column:columns)GridRelation.validate(column,row.get(column.source()));
        if(!seen.containsAll(keys))throw new IllegalArgumentException("Include all view unique-key columns to edit.");
        boolean insert=base.columns.stream().allMatch(col->col.nullable()||col.generated()||col.hasDefault()||mapping.entrySet().stream().anyMatch(e->e.getValue().name().equals(col.name())&&seen.contains(e.getKey())));
        String qualified=GridRelation.quote(m,engine.equals("postgresql")?schema:catalog)+"."+GridRelation.quote(m,name);
        return new GridRelation(engine,catalog,schema,name,qualified,CatalogScanner.hash(base.fingerprint+"|"+definition+"|"+check+"|"+viewNames),sql,columns,keys,insert);
    }
}
