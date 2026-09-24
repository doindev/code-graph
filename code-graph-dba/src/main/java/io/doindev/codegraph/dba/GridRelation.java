package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.PlainSelect;
import java.sql.*;
import java.util.*;

/** Conservative, server-observed row identity. Unknown mappings never become writable. */
final class GridRelation {
    static final Set<String> ENGINES=Set.of("postgresql","mysql","mariadb","h2","sqlserver","oracle");
    static String engine(String product){return product.toLowerCase(Locale.ROOT).contains("postgres")?"postgresql":VendorMetadata.engine(product);}
    static final Set<Integer> TYPES=Set.of(Types.CHAR,Types.VARCHAR,Types.NCHAR,Types.NVARCHAR,
            Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL,
            Types.BOOLEAN,Types.BIT,Types.DATE,Types.TIME,Types.TIMESTAMP);
    record Column(String id,String name,int type,int size,int scale,boolean nullable,boolean generated,boolean hasDefault,int source){}
    final String engine,catalog,schema,table,qualified,fingerprint,sql;
    final List<Column> columns;
    final List<String> keys;
    final boolean insert;
    GridRelation(String engine,String catalog,String schema,String table,String qualified,String fingerprint,String sql,List<Column> columns,List<String> keys,boolean insert){
        this.engine=engine;this.catalog=catalog;this.schema=schema;this.table=table;this.qualified=qualified;this.fingerprint=fingerprint;this.sql=sql;this.columns=List.copyOf(columns);this.keys=List.copyOf(keys);this.insert=insert;
    }
    static GridRelation inspect(Connection c,String sql,ObjectNode result)throws Exception {
        if(result.path("cellsTruncated").asBoolean())throw new IllegalArgumentException("Truncated values cannot identify editable rows.");
        SqlReadGuard.validate(sql);
        var parsed=CCJSqlParserUtil.parse(sql,p->p.withTimeOut(500));
        if(!(parsed instanceof PlainSelect select)||!(select.getFromItem() instanceof Table from)
                ||select.getJoins()!=null&&!select.getJoins().isEmpty()||select.getDistinct()!=null
                ||select.getGroupBy()!=null||select.getHaving()!=null||select.getWithItemsList()!=null&&!select.getWithItemsList().isEmpty())
            throw new IllegalArgumentException("Editing requires a verified single-table projection with a unique key.");
        for(var item:select.getSelectItems()){
            String kind=item.getExpression().getClass().getSimpleName();
            if(!Set.of("Column","AllColumns","AllTableColumns").contains(kind))throw new IllegalArgumentException("Computed projections are read-only.");
        }
        DatabaseMetaData m=c.getMetaData();String engine=engine(m.getDatabaseProductName());
        if(!ENGINES.contains(engine)||!m.supportsTransactions())throw new IllegalArgumentException("This vendor has no verified transactional grid adapter.");
        String catalog=engine.equals("oracle")?OracleDialect.target(c,3).database():Objects.toString(c.getCatalog(),""),schema=Objects.toString(c.getSchema(),""),table=identifier(from.getName());
        if(from.getSchemaName()!=null)schema=identifier(from.getSchemaName());
        if(Set.of("mysql","mariadb").contains(engine)){if(from.getSchemaName()!=null&&!schema.equals(catalog))throw new IllegalArgumentException("Select the exact database before editing.");schema="";}
        if(from.getDatabase()!=null&&from.getDatabase().getDatabaseName()!=null&&!identifier(from.getDatabase().getDatabaseName()).equals(catalog))throw new IllegalArgumentException("Cross-database SQL is not an editable target.");
        if(!quoted(from.getName())&&m.storesUpperCaseIdentifiers())table=table.toUpperCase(Locale.ROOT);
        if(!quoted(from.getName())&&m.storesLowerCaseIdentifiers())table=table.toLowerCase(Locale.ROOT);
        if(from.getSchemaName()!=null&&!quoted(from.getSchemaName())&&m.storesUpperCaseIdentifiers())schema=schema.toUpperCase(Locale.ROOT);
        if(from.getSchemaName()!=null&&!quoted(from.getSchemaName())&&m.storesLowerCaseIdentifiers())schema=schema.toLowerCase(Locale.ROOT);
        boolean found=false,view=false;try(ResultSet tables=m.getTables(engine.equals("oracle")?null:catalog,schema.isEmpty()?null:pattern(m,schema),pattern(m,table),null)){
            int count=0;while(tables.next()){if(++count>256)throw new IllegalArgumentException("Ambiguous table metadata.");if(!table.equals(tables.getString("TABLE_NAME")))continue;
                String kind=tables.getString("TABLE_TYPE");view="VIEW".equals(kind);
                if(!view&&!Set.of("TABLE","BASE TABLE").contains(kind))throw new IllegalArgumentException("Materialized views and this object type are read-only.");
                if(found)throw new IllegalArgumentException("Qualify the schema to identify one table.");found=true;schema=Objects.toString(tables.getString("TABLE_SCHEM"),"");}
        }
        if(!found)throw new IllegalArgumentException("Table metadata is unavailable; qualify the table and check privileges.");
        if(view)return GridViews.inspect(c,engine,catalog,schema,table,sql,result);
        if(Set.of("mysql","mariadb").contains(engine))try(var statement=c.prepareStatement("SELECT ENGINE FROM information_schema.tables WHERE TABLE_SCHEMA=? AND TABLE_NAME=?")){
            statement.setQueryTimeout(3);statement.setString(1,catalog);statement.setString(2,table);try(var rs=statement.executeQuery()){if(!rs.next()||!"InnoDB".equalsIgnoreCase(rs.getString(1)))throw new IllegalArgumentException("Atomic row saves require InnoDB.");}
        }
        var all=new LinkedHashMap<String,Column>();var unsupported=new HashSet<String>();try(ResultSet rs=m.getColumns(engine.equals("oracle")?null:catalog,schema.isEmpty()?null:pattern(m,schema),pattern(m,table),"%")){
            while(rs.next()){if(all.size()>=256)throw new IllegalArgumentException("Table exceeds the supported column allowance.");String name=rs.getString("COLUMN_NAME");
                if(!scalarTypeSupported(engine,rs.getInt("DATA_TYPE"),rs.getString("TYPE_NAME"),rs.getInt("COLUMN_SIZE")))unsupported.add(name);
                int type=rs.getInt("DATA_TYPE"),size=rs.getInt("COLUMN_SIZE"),scale=rs.getInt("DECIMAL_DIGITS");
                if(engine.equals("oracle")){
                    if(Set.of(Types.NUMERIC,Types.DECIMAL).contains(type)&&(size==0||scale<-84)){size=38;scale=Integer.MIN_VALUE;}
                    if(rs.getString("TYPE_NAME").equalsIgnoreCase("DATE")){type=Types.TIMESTAMP;scale=0;}
                }
                all.put(name,new Column("",name,type,size,scale,
                        rs.getInt("NULLABLE")==DatabaseMetaData.columnNullable,"YES".equals(rs.getString("IS_GENERATEDCOLUMN"))||"YES".equals(rs.getString("IS_AUTOINCREMENT")),rs.getString("COLUMN_DEF")!=null,-1));}
        }
        var keys=new TreeMap<Integer,String>();try(ResultSet rs=m.getPrimaryKeys(engine.equals("oracle")?null:catalog,schema.isEmpty()?null:schema,table)){while(rs.next()){if(keys.size()>=256)throw new IllegalArgumentException("Key metadata exceeds limits.");keys.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));}}
        if(keys.isEmpty()){var unique=new TreeMap<String,TreeMap<Integer,String>>();try(ResultSet rs=m.getIndexInfo(engine.equals("oracle")?null:catalog,schema.isEmpty()?null:schema,table,true,false)){int n=0;while(rs.next()){if(++n>4096)throw new IllegalArgumentException("Index metadata exceeds limits.");String name=rs.getString("INDEX_NAME"),column=rs.getString("COLUMN_NAME");if(name==null||column==null||rs.getBoolean("NON_UNIQUE")||rs.getString("FILTER_CONDITION")!=null)continue;unique.computeIfAbsent(name,k->new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"),column);}}
            for(var key:unique.values())if(!key.isEmpty()&&key.values().stream().allMatch(n->all.containsKey(n)&&!all.get(n).nullable())){keys.putAll(key);break;}}
        if(keys.isEmpty())throw new IllegalArgumentException("A primary key or non-null unique key is required.");
        if(engine.equals("sqlserver"))sqlServerProvenance(c,select,result,catalog,schema,table);
        if(engine.equals("oracle"))oracleProvenance(m,select,result,schema,table);
        var columns=new ArrayList<Column>();var seen=new HashSet<String>();int index=0;
        for(JsonNode output:result.path("columns")){String name=output.path("name").asText();Column column=all.get(name);
            String sourceTable=output.path("table").asText(),sourceSchema=output.path("schema").asText();
            if(sourceTable.isEmpty()||!sourceTable.equals(table)||!sourceSchema.isEmpty()&&!sourceSchema.equals(schema)&&!Set.of("mysql","mariadb").contains(engine))
                throw new IllegalArgumentException("JDBC source provenance does not match the verified table; qualify its schema and rerun.");
            if(column==null||!seen.add(name)||unsupported.contains(name))throw new IllegalArgumentException("Result columns must map uniquely to supported, complete scalar table values; unverified vendor precision/types are read-only.");
            columns.add(new Column(output.path("id").asText(),name,column.type(),column.size(),column.scale(),column.nullable(),column.generated(),column.hasDefault(),index++));}
        for(JsonNode row:result.path("rows"))for(Column column:columns)validate(column,row.get(column.source()));
        if(!seen.containsAll(keys.values()))throw new IllegalArgumentException("Include every unique-key column in the SELECT to enable editing and paging.");
        String prefix=Set.of("mysql","mariadb").contains(engine)?catalog:schema;
        String target=(prefix.isEmpty()?"":quote(m,prefix)+".")+quote(m,table);
        String fingerprint=CatalogScanner.hash(engine+"|"+catalog+"|"+schema+"|"+table+"|"+all+"|"+keys);
        boolean insert=all.values().stream().allMatch(col->seen.contains(col.name())||col.nullable()||col.generated()||col.hasDefault());
        return new GridRelation(engine,catalog,schema,table,target,fingerprint,sql,columns,new ArrayList<>(keys.values()),insert);
    }
    private static void oracleProvenance(DatabaseMetaData metadata,PlainSelect select,ObjectNode result,String schema,String table)throws SQLException{
        // Oracle omits base-table metadata and reports aliases as column names. The already
        // executed, direct single-table projection was checked above; resolve only its columns.
        int explicit=0,stars=0;for(var item:select.getSelectItems())if(item.getExpression() instanceof net.sf.jsqlparser.schema.Column)explicit++;else stars++;
        if(stars>1)throw new IllegalArgumentException("Repeated wildcard projections are not an editable target.");
        int width=result.path("columns").size()-explicit,index=0;
        for(var item:select.getSelectItems()){
            if(item.getExpression() instanceof net.sf.jsqlparser.schema.Column column){
                String raw=column.getColumnName(),name=identifier(raw);if(!quoted(raw)&&metadata.storesUpperCaseIdentifiers())name=name.toUpperCase(Locale.ROOT);
                if(index>=result.path("columns").size())throw new IllegalArgumentException("Oracle projection metadata is incomplete.");
                ((ObjectNode)result.path("columns").get(index++)).put("name",name).put("schema",schema).put("table",table);
            }else for(int i=0;i<width;i++)((ObjectNode)result.path("columns").get(index++)).put("schema",schema).put("table",table);
        }
        if(index!=result.path("columns").size())throw new IllegalArgumentException("Oracle projection metadata is incomplete.");
    }
    private static void sqlServerProvenance(Connection c,PlainSelect select,ObjectNode result,String catalog,String schema,String table)throws Exception{
        // The Microsoft JDBC driver may omit base-table metadata for forward-only results.
        // Ask SQL Server to describe this already-validated projection, without executing it.
        String projection="SELECT "+String.join(", ",select.getSelectItems().stream().map(Object::toString).toList())+" FROM "+select.getFromItem()+" WHERE 1=0";
        try(var s=c.prepareStatement("SELECT column_ordinal, source_database, source_schema, source_table, source_column FROM sys.dm_exec_describe_first_result_set(?, NULL, 1) WHERE is_hidden=0 ORDER BY column_ordinal")){
            s.setString(1,projection);s.setQueryTimeout(3);s.setMaxRows(257);
            try(var rs=s.executeQuery()){int index=0;while(rs.next()){
                if(index>=result.path("columns").size()||rs.getInt(1)!=index+1||!catalog.equals(rs.getString(2))||!schema.equals(rs.getString(3))||!table.equals(rs.getString(4))||rs.getString(5)==null)
                    throw new IllegalArgumentException("SQL Server could not verify this projection's source.");
                ((ObjectNode)result.path("columns").get(index++)).put("name",rs.getString(5)).put("schema",schema).put("table",table);
            }if(index!=result.path("columns").size())throw new IllegalArgumentException("SQL Server projection metadata is incomplete.");}
        }
    }
    static boolean scalarTypeSupported(String engine,int type,String nativeType,int size){
        if(!TYPES.contains(type))return false;
        String name=Objects.toString(nativeType,"").toLowerCase(Locale.ROOT);
        if(engine.equals("oracle")&&!Set.of("number","char","nchar","varchar","varchar2","nvarchar2","date").contains(name)&&!(name.startsWith("timestamp")&&!name.contains("time zone")))return false;
        // JDBC's generic TIMESTAMP/TIME/BIT labels hide these incompatible semantics.
        if(engine.equals("sqlserver")&&Set.of("datetime","smalldatetime").contains(name))return false;
        if(Set.of("mysql","mariadb").contains(engine)&&(name.contains("unsigned")||type==Types.TIME||type==Types.BIT&&size>1))return false;
        return true;
    }
    ObjectNode descriptor(){ObjectNode out=Profiles.JSON.createObjectNode().put("table",table).put("schema",schema).put("database",catalog).put("engine",engine);ArrayNode cols=out.putArray("columns");
        for(Column column:columns)cols.addObject().put("id",column.id()).put("name",column.name()).put("key",keys.contains(column.name())).put("jdbcType",column.type()).put("unsigned",engine.equals("sqlserver")&&column.type()==Types.TINYINT).put("nullable",column.nullable()).put("editable",!column.generated()).put("generated",column.generated()).put("hasDefault",column.hasDefault()).put("size",column.size()).put("scale",column.scale());return out;}
    static String pattern(DatabaseMetaData m,String value)throws SQLException{String esc=m.getSearchStringEscape();return value.replace(esc,esc+esc).replace("%",esc+"%").replace("_",esc+"_");}
    static boolean quoted(String s){return s.startsWith("\"")||s.startsWith("\u0060")||s.startsWith("[");}
    static String identifier(String s){if(s==null||s.isEmpty()||s.length()>256||s.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid identifier");if(quoted(s)){String end=s.startsWith("[")?"]":s.substring(0,1);return s.substring(1,s.length()-1).replace(end+end,end);}return s;}
    static String quote(DatabaseMetaData m,String name)throws SQLException{String q=m.getIdentifierQuoteString();if(q==null||q.isBlank())throw new IllegalArgumentException("Safe identifier quoting is unavailable");q=q.trim();String end=q.equals("[")?"]":q;return q+name.replace(end,end+end)+end;}
    static void validate(Column column,JsonNode value){
        if(value.isNull())return;String text=value.asText();
        try{
            switch(column.type()){
                case Types.CHAR,Types.VARCHAR,Types.NCHAR,Types.NVARCHAR->{if(column.size()>0&&text.codePointCount(0,text.length())>column.size())throw new IllegalArgumentException("Value exceeds column length.");}
                case Types.NUMERIC,Types.DECIMAL->{var n=new java.math.BigDecimal(text).stripTrailingZeros();
                    if(column.scale()==Integer.MIN_VALUE){long exponent=(long)n.precision()-n.scale()-1;if(n.precision()>38||n.signum()!=0&&(exponent< -130||exponent>125))throw new IllegalArgumentException("Value exceeds Oracle NUMBER precision/range.");}
                    else if(n.scale()>column.scale()||Math.max(0L,(long)n.precision()-n.scale())>(long)column.size()-column.scale())throw new IllegalArgumentException("Value exceeds column precision/scale; no rounding is performed.");}
                case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT->new java.math.BigDecimal(text).toBigIntegerExact();
                case Types.DATE->java.time.LocalDate.parse(text);
                case Types.TIME->temporalPrecision(java.time.LocalTime.parse(text).getNano(),column.scale());
                case Types.TIMESTAMP->temporalPrecision(java.time.LocalDateTime.parse(text.replace(' ','T')).getNano(),column.scale());
                case Types.BOOLEAN,Types.BIT->{if(!Set.of("true","false","1","0").contains(text.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Expected a Boolean value.");}
                default->throw new IllegalArgumentException("Unsupported column datatype.");
            }
        }catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid value for "+column.name()+": expected "+JDBCType.valueOf(column.type())+" within the column bounds.",invalid);}
    }
    private static void temporalPrecision(int nanos,int scale){if(scale>=0&&scale<9&&nanos%(int)Math.pow(10,9-scale)!=0)throw new IllegalArgumentException("Value exceeds temporal precision; no rounding is performed.");}
    static void bind(PreparedStatement s,int at,JsonNode value,int type)throws SQLException{
        if(value==null||value.isNull()){s.setNull(at,type);return;}String text=value.asText();
        if(Set.of(Types.NCHAR,Types.NVARCHAR).contains(type)&&OracleDialect.isOracle(s.getConnection())){s.setNString(at,text);return;}
        try{switch(type){
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT->{var number=new java.math.BigDecimal(text);number.toBigIntegerExact();s.setObject(at,number,type);}
            case Types.NUMERIC,Types.DECIMAL->s.setBigDecimal(at,new java.math.BigDecimal(text));
            case Types.BOOLEAN,Types.BIT->{if(!Set.of("true","false","1","0").contains(text.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Expected Boolean");s.setBoolean(at,text.equalsIgnoreCase("true")||text.equals("1"));}
            case Types.DATE->s.setObject(at,java.time.LocalDate.parse(text));
            case Types.TIME->s.setObject(at,java.time.LocalTime.parse(text));
            case Types.TIMESTAMP->s.setObject(at,java.time.LocalDateTime.parse(text.replace(' ','T')));
            default->s.setString(at,text);
        }}catch(RuntimeException bad){throw new IllegalArgumentException("Cell value is incompatible with its database type.");}
    }
}
