package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Trusted local-human SQL, executed as bounded source-preserving units. */
final class HumanSql {
    static final class ScriptCancelled extends Exception {
        private final ObjectNode partial;
        ScriptCancelled(String message,ObjectNode partial){super(message);this.partial=partial;}
        ObjectNode partial(){return partial;}
    }
    private record ErrorKey(String state,int vendor,String type) {}
    private static final class Totals {
        final ObjectNode out=Profiles.JSON.createObjectNode();
        final ArrayNode results=out.putArray("results"),errors=out.putArray("errors");
        int remainingRows,totalRows;long remainingBytes,affected;boolean truncated,hasUpdate;
        ObjectNode first;
        Totals(QueryJobs.Job job){remainingRows=job.rowLimit;remainingBytes=job.byteLimit/2L-65_536;}
    }

    static void checkParameters(JsonNode values){
        if(values==null||!values.isArray()||values.size()>128)throw new IllegalArgumentException("parameters must be an array of at most 128 values");
        for(JsonNode v:values)if(!(v.isNull()||v.isBoolean()||v.isNumber()||(v.isTextual()&&v.asText().length()<=8192)))throw new IllegalArgumentException("Unsupported or oversized parameter");
    }

    static ObjectNode execute(QueryJobs.Job job,Connection connection,String source,JsonNode values,int decisionTimeout,Function<Exception,String> safeError)throws Exception {
        boolean oracle=OracleDialect.isOracle(connection);
        String engine=ExplainPlans.engine(connection.getMetaData());boolean mysql=MysqlDialect.supports(engine);if(!oracle){if(mysql||engine.equals("postgresql"))NativeParameters.check(values,engine);else checkParameters(values);}
        List<SqlScript.Unit> units=mysql?MysqlScript.extract(source,16384,MysqlScript.Mode.parse(MysqlDialect.mode(job,connection))):SqlScript.extract(source,engine);int expected=units.stream().mapToInt(SqlScript.Unit::parameters).sum();
        if(expected!=values.size())throw new IllegalArgumentException("Script contains "+expected+" prepared parameter marker"+(expected==1?"":"s")+" but "+values.size()+" value"+(values.size()==1?" was":"s were")+" supplied; nothing was executed");
        Totals totals=new Totals(job);ArrayNode statements=totals.out.putArray("statements");int bindingOffset=0;for(var unit:units){statements.addObject().put("index",unit.index()).put("sql",unit.sql()).put("parameterOffset",bindingOffset).put("parameterCount",unit.parameters());bindingOffset+=unit.parameters();}
        boolean autoCommit=connection.getAutoCommit(),savepoints=!autoCommit&&connection.getMetaData().supportsSavepoints();int parameterOffset=0;Set<ErrorKey> ignored=new HashSet<>();
        if(mysql)totals.out.put("transactionNotice","MySQL DDL commits implicitly. Stored programs may commit; completed statements can remain applied after later failures.").put("changesMayAlreadyBeCommitted",true);
        if(oracle)totals.out.put("transactionNotice","Oracle DDL commits implicitly. PL/SQL can commit or execute DDL; rollback may not undo those changes.");
        for(SqlScript.Unit unit:units){
            if(job.cancelled)throw cancelled(totals,"Script cancelled before statement "+unit.index());
            Savepoint savepoint=null;if(savepoints)try{savepoint=connection.setSavepoint("code_graph_statement_"+unit.index());}catch(SQLException unsupported){savepoints=false;}
            int outputStart=totals.out.path("outputParameters").size();
            if(oracle&&OracleDialect.mayCommit(unit.sql()))totals.out.put("changesMayAlreadyBeCommitted",true);
            int resultStart=totals.results.size(),rowsBefore=totals.totalRows,remainingRowsBefore=totals.remainingRows;long bytesBefore=totals.remainingBytes,affectedBefore=totals.affected;boolean updateBefore=totals.hasUpdate,truncatedBefore=totals.truncated;ObjectNode firstBefore=totals.first;
            try{
                executeUnit(job,connection,unit,values,parameterOffset,totals);
                parameterOffset+=unit.parameters();if(savepoint!=null)try{connection.releaseSavepoint(savepoint);}catch(SQLException ignoredRelease){}
            }catch(Exception failure){
                if(totals.out.has("outputParameters")){ArrayNode outputs=(ArrayNode)totals.out.get("outputParameters");while(outputs.size()>outputStart)outputs.remove(outputs.size()-1);}
                while(totals.results.size()>resultStart)totals.results.remove(totals.results.size()-1);totals.totalRows=rowsBefore;totals.remainingRows=remainingRowsBefore;totals.remainingBytes=bytesBefore;totals.affected=affectedBefore;totals.hasUpdate=updateBefore;totals.truncated=truncatedBefore;totals.first=firstBefore;
                if(job.cancelled||failure instanceof CancellationException||failure instanceof InterruptedException)throw cancelled(totals,"Script cancelled while executing statement "+unit.index());
                boolean canContinue=autoCommit;
                if(!autoCommit&&savepoint!=null)try{connection.rollback(savepoint);canContinue=true;}catch(SQLException rollback){failure.addSuppressed(rollback);canContinue=false;}
                ErrorKey key=key(failure);ObjectNode error=totals.errors.addObject().put("statementIndex",unit.index()).put("message",bounded(safeError.apply(failure),8192)).put("exceptionType",failure.getClass().getName()).put("canContinue",canContinue);
                if(key.state()!=null)error.put("sqlState",key.state());if(key.vendor()!=0)error.put("vendorCode",key.vendor());
                if(canContinue&&ignored.contains(key)){error.put("resolution","skip_similar");parameterOffset+=unit.parameters();continue;}
                ObjectNode prompt=Profiles.JSON.createObjectNode().put("id",UUID.randomUUID().toString()).put("statementIndex",unit.index()).put("message",error.path("message").asText()).put("exceptionType",failure.getClass().getName()).put("canContinue",canContinue).put("transactional",!autoCommit);
                if(key.state()!=null)prompt.put("sqlState",key.state());if(key.vendor()!=0)prompt.put("vendorCode",key.vendor());ArrayNode actions=prompt.putArray("actions").add("cancel");if(canContinue)actions.add("skip_similar").add("continue");
                String action;try{action=job.awaitDecision(prompt,decisionTimeout);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw cancelled(totals,"Script cancelled while awaiting a decision for statement "+unit.index());}
                if(action.equals("cancel"))throw cancelled(totals,"Script stopped after statement "+unit.index()+" failed");
                error.put("resolution",action);if(action.equals("skip_similar"))ignored.add(key);parameterOffset+=unit.parameters();
            }
        }
        return finish(totals,false);
    }

    private static void executeUnit(QueryJobs.Job job,Connection c,SqlScript.Unit unit,JsonNode values,int offset,Totals totals)throws Exception {
        boolean callable=false;for(int i=0;i<unit.parameters();i++)callable|=values.get(offset+i).isObject()&&!values.get(offset+i).path("mode").asText().equals("in");
        try(Statement statement=callable?c.prepareCall(unit.sql()):unit.parameters()==0?c.createStatement(ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY):c.prepareStatement(unit.sql(),ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
            job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setFetchSize(64);statement.setMaxRows(totals.remainingRows+1);
            if(statement instanceof PreparedStatement prepared)for(int i=0;i<unit.parameters();i++){
                JsonNode value=values.get(offset+i);if(value.isObject()){if(OracleDialect.isOracle(c))OracleSql.bind(prepared,i+1,value);else NativeParameters.bind(prepared,i+1,value);}else bind(prepared,i+1,value);
            }
            boolean resultSet=statement instanceof PreparedStatement prepared?prepared.execute():statement.execute(unit.sql());
            while(true){
                if(job.cancelled)throw new CancellationException();long count=resultSet?-1:statement.getLargeUpdateCount();if(!resultSet&&count==-1)break;
                if(totals.results.size()>=32)throw new IllegalArgumentException("More than 32 JDBC results; run a smaller selection");ObjectNode entry;
                if(resultSet){try(ResultSet rows=statement.getResultSet()){entry=QueryJobs.rows(rows,totals.remainingRows,(int)Math.max(32_768,Math.min(Integer.MAX_VALUE,totals.remainingBytes)));}
                    entry.put("kind","rows").put("statementIndex",unit.index());int size=entry.path("rowCount").asInt();totals.remainingRows-=size;totals.totalRows+=size;totals.truncated|=entry.path("truncated").asBoolean();if(totals.first==null)totals.first=entry;
                }else{entry=Profiles.JSON.createObjectNode().put("kind","update").put("affectedRows",count).put("statementIndex",unit.index());totals.affected=Math.addExact(totals.affected,count);totals.hasUpdate=true;}
                if(entry.path("kind").asText().equals("rows"))GridSql.describeColumns(unit.sql(),entry);
                totals.remainingBytes-=Profiles.JSON.writeValueAsBytes(entry).length;if(totals.remainingBytes<0)throw new IllegalArgumentException("JDBC result metadata exceeds byte allowance");totals.results.add(entry);
                resultSet=statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
            }
            if(statement instanceof CallableStatement call)for(int i=0;i<unit.parameters();i++){
                JsonNode parameter=values.get(offset+i);if(!parameter.isObject()||parameter.path("mode").asText().equals("in"))continue;
                if(job.cancelled)throw new CancellationException();
                if(parameter.path("type").asText().equals("REF_CURSOR")){
                    Object cursor=call.getObject(i+1);if(cursor==null){totals.out.withArray("outputParameters").addObject().put("parameterIndex",offset+i+1).put("statementIndex",unit.index()).putNull("value");continue;}
                    if(!(cursor instanceof ResultSet))throw new SQLException("Oracle REF CURSOR did not return a JDBC result set");
                    ObjectNode entry;try(ResultSet rows=(ResultSet)cursor){entry=QueryJobs.rows(rows,totals.remainingRows,(int)Math.max(32768,totals.remainingBytes));}
                    if(totals.results.size()>=32)throw new IllegalArgumentException("More than 32 JDBC results; run a smaller selection");
                    entry.put("kind","rows").put("statementIndex",unit.index()).put("parameterIndex",offset+i+1).put("refCursor",true);
                    int size=entry.path("rowCount").asInt();totals.remainingRows-=size;totals.totalRows+=size;totals.truncated|=entry.path("truncated").asBoolean();if(totals.first==null)totals.first=entry;
                    totals.remainingBytes-=Profiles.JSON.writeValueAsBytes(entry).length;if(totals.remainingBytes<0)throw new IllegalArgumentException("Oracle cursor exceeds byte allowance");totals.results.add(entry);
                }else{
                    ObjectNode value=(OracleDialect.isOracle(c)?OracleSql.scalar(call,i+1,parameter):NativeParameters.output(call,i+1,parameter)).put("parameterIndex",offset+i+1).put("statementIndex",unit.index());
                    totals.remainingBytes-=Profiles.JSON.writeValueAsBytes(value).length;if(totals.remainingBytes<0)throw new IllegalArgumentException("Output parameters exceed byte allowance");totals.out.withArray("outputParameters").add(value);
                }
            }
        }finally{job.statement=null;}
    }
    private static void bind(PreparedStatement statement,int index,JsonNode value)throws SQLException {if(value.isNull())statement.setNull(index,Types.NULL);else if(value.isBoolean())statement.setBoolean(index,value.asBoolean());else if(value.isNumber())statement.setBigDecimal(index,value.decimalValue());else statement.setString(index,value.asText());}
    private static ObjectNode finish(Totals totals,boolean interrupted){
        if(totals.first!=null){totals.out.set("columns",totals.first.path("columns"));totals.out.set("rows",totals.first.path("rows"));totals.out.put("cellsTruncated",totals.first.path("cellsTruncated").asBoolean());}else{totals.out.putArray("columns");totals.out.putArray("rows");}
        if(totals.hasUpdate)totals.out.put("affectedRows",totals.affected);totals.out.put("rowCount",totals.totalRows).put("truncated",totals.truncated).put("resultCount",totals.results.size()).put("completedWithErrors",!totals.errors.isEmpty()).put("interrupted",interrupted);return totals.out;
    }
    private static ScriptCancelled cancelled(Totals totals,String message){return new ScriptCancelled(message,finish(totals,true));}
    private static ErrorKey key(Exception error){SQLException sql=findSql(error);String state=sql==null?null:sql.getSQLState();int vendor=sql==null?0:sql.getErrorCode();return new ErrorKey(state,vendor,state==null&&vendor==0?error.getClass().getName():"");}
    private static SQLException findSql(Throwable error){for(Throwable current=error;current!=null;current=current.getCause())if(current instanceof SQLException sql)return sql;return null;}
    private static String bounded(String value,int maximum){if(value==null||value.isBlank())return "Database statement failed";return value.length()<=maximum?value:value.substring(0,maximum)+"…";}
    static String failure(Exception e){
        if(e instanceof IllegalArgumentException)return e.getMessage();
        if(e instanceof SQLException sql){String state=sql.getSQLState();String hint=switch(state==null?"":state){case "42704"->"Unknown object or data type; check identifiers and column types";case "42P07"->"Table already exists";case "42601"->"SQL syntax error";case "42501"->"Database account has insufficient privileges";case "25001"->"Command requires auto-commit mode";case "23505"->"Duplicate key violates a unique constraint";case "23503"->"Foreign key constraint violation";case "42P01"->"Table or relation does not exist";default->"Database command failed; inspect SQL syntax, connection and database privileges";};String message=sql.getMessage();return (message==null?hint:message)+"\n"+hint+(state!=null&&state.matches("[A-Za-z0-9]{5}")?" (SQLSTATE "+state+")":"");}
        return "Database command failed";
    }
}
