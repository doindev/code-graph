package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.Reader;
import java.sql.*;
import java.util.*;
import java.util.function.Function;

/** Bounded Oracle callable parameters and opt-in DBMS_OUTPUT, without driver-specific classes. */
final class OracleSql {
    static final Map<String,Integer> TYPES=Map.of("VARCHAR",Types.VARCHAR,"NVARCHAR",Types.NVARCHAR,"NUMBER",Types.NUMERIC,
        "DATE",Types.DATE,"TIMESTAMP",Types.TIMESTAMP,"TIMESTAMP_WITH_TIMEZONE",Types.TIMESTAMP_WITH_TIMEZONE,
        "CLOB",Types.CLOB,"NCLOB",Types.NCLOB,"REF_CURSOR",Types.REF_CURSOR);
    private OracleSql(){}
    static void checkParameters(JsonNode values){
        if(values==null||!values.isArray()||values.size()>128)throw new IllegalArgumentException("parameters must be an array of at most 128 values");
        for(JsonNode value:values){
            if(!value.isObject()){HumanSql.checkParameters(Profiles.JSON.createArrayNode().add(value));continue;}
            value.fieldNames().forEachRemaining(key->{if(!Set.of("mode","type","value").contains(key))throw new IllegalArgumentException("Unknown routine parameter field: "+key);});
            String mode=value.path("mode").asText(),type=value.path("type").asText();
            if(!Set.of("in","out","inout").contains(mode)||!TYPES.containsKey(type))throw new IllegalArgumentException("Oracle parameters require mode in/out/inout and a supported JDBC type");
            if(mode.equals("out")&&value.has("value"))throw new IllegalArgumentException("OUT parameters cannot contain an input value");
            if(!mode.equals("out")){
                if(!value.has("value")||type.equals("REF_CURSOR"))throw new IllegalArgumentException("IN/INOUT parameters require a scalar value and cannot bind REF_CURSOR");
                HumanSql.checkParameters(Profiles.JSON.createArrayNode().add(value.get("value")));
                if(!value.path("value").isNull())typedValue(type,value.path("value").asText());
            }
        }
    }
    /** Only scalar IN values are safe for retained SELECTs, paging and estimated plans. */
    static void checkInputParameters(JsonNode values){
        checkParameters(values);
        for(JsonNode value:values)if(value.isObject()&&(!value.path("mode").asText().equals("in")||!Set.of("NUMBER","DATE","TIMESTAMP","VARCHAR","NVARCHAR").contains(value.path("type").asText())))
            throw new IllegalArgumentException("Grid and plan parameters require scalar IN values");
    }
    static String displayInput(JsonNode parameter){
        checkInputParameters(Profiles.JSON.createArrayNode().add(parameter));
        JsonNode value=parameter.path("value");if(value.isNull())return "NULL";
        String type=parameter.path("type").asText();
        if(type.equals("NUMBER"))return number(value.asText()).toPlainString();
        if(type.equals("DATE")||type.equals("TIMESTAMP")){
            String timestamp="TIMESTAMP '"+((java.time.LocalDateTime)typedValue(type,value.asText())).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS",Locale.ROOT))+"'";
            return type.equals("DATE")?"CAST("+timestamp+" AS DATE)":timestamp;
        }
        return "'"+value.asText().replace("'","''")+"'";
    }
    private static java.math.BigDecimal number(String value){
        try{var number=new java.math.BigDecimal(value);var exact=number.stripTrailingZeros();long exponent=(long)exact.precision()-exact.scale()-1;if(exact.precision()>38||exact.signum()!=0&&(exponent< -130||exponent>125))throw new NumberFormatException();return exact;}
        catch(NumberFormatException failure){throw new IllegalArgumentException("NUMBER parameter must contain at most 38 significant digits within the Oracle exponent range (-130..125)");}
    }
    private static Object typedValue(String type,String value){
        try{return switch(type){
            case "NUMBER"->number(value);
            case "DATE"->value.length()==10?java.time.LocalDate.parse(value).atStartOfDay():java.time.LocalDateTime.parse(value.replace(' ','T'));
            case "TIMESTAMP"->java.time.LocalDateTime.parse(value.replace(' ','T'));
            case "TIMESTAMP_WITH_TIMEZONE"->java.time.OffsetDateTime.parse(value);
            default->value;
        };}catch(java.time.DateTimeException failure){throw new IllegalArgumentException("Use an ISO date/time value for "+type);}
    }
    static void bind(PreparedStatement statement,int index,JsonNode parameter)throws SQLException{
        int type=TYPES.get(parameter.path("type").asText());String mode=parameter.path("mode").asText();
        if(!mode.equals("in"))((CallableStatement)statement).registerOutParameter(index,type);
        if(!mode.equals("out")){
            JsonNode value=parameter.path("value");if(value.isNull()){statement.setNull(index,type);return;}
            Object typed=typedValue(parameter.path("type").asText(),value.asText());
            switch(type){
                case Types.NUMERIC->statement.setBigDecimal(index,(java.math.BigDecimal)typed);
                case Types.NVARCHAR->statement.setNString(index,value.asText());
                case Types.CLOB->statement.setClob(index,new java.io.StringReader(value.asText()));
                case Types.NCLOB->statement.setNClob(index,new java.io.StringReader(value.asText()));
                case Types.DATE,Types.TIMESTAMP->statement.setTimestamp(index,Timestamp.valueOf((java.time.LocalDateTime)typed));
                case Types.TIMESTAMP_WITH_TIMEZONE->statement.setObject(index,typed,type);
                default->statement.setString(index,value.asText());
            }
        }
    }
    static ObjectNode scalar(CallableStatement statement,int index,JsonNode parameter)throws Exception{
        ObjectNode out=Profiles.JSON.createObjectNode().put("type",parameter.path("type").asText());int type=TYPES.get(parameter.path("type").asText());String value;
        if(type==Types.CLOB||type==Types.NCLOB){
            Clob lob=type==Types.NCLOB?statement.getNClob(index):statement.getClob(index);
            if(lob==null){out.putNull("value");return out;}
            try(Reader reader=lob.getCharacterStream()){char[] buffer=new char[8193];int length=0,n;while(length<buffer.length&&(n=reader.read(buffer,length,buffer.length-length))!=-1)length+=n;value=new String(buffer,0,Math.min(8192,length));out.put("truncated",length>8192);}finally{lob.free();}
        }else if(type==Types.VARCHAR||type==Types.NVARCHAR){
            value=type==Types.NVARCHAR?statement.getNString(index):statement.getString(index);
        }else if(type==Types.NUMERIC){var number=statement.getBigDecimal(index);value=number==null?null:number.toPlainString();}
        else if(type==Types.DATE||type==Types.TIMESTAMP){var date=statement.getTimestamp(index);value=date==null?null:date.toLocalDateTime().toString();}
        else {var date=statement.getObject(index,java.time.OffsetDateTime.class);value=date==null?null:date.toString();}
        if(value==null)out.putNull("value");else{if(value.length()>8192){value=value.substring(0,8192);out.put("truncated",true);}out.put("value",value);}return out;
    }
    static ObjectNode execute(QueryJobs.Job job,Connection c,String sql,JsonNode values,int decisionTimeout,Function<Exception,String> safeError,boolean output)throws Exception{
        if(output)try(CallableStatement enable=c.prepareCall("BEGIN DBMS_OUTPUT.ENABLE(100000); END;")){job.statement=enable;enable.setQueryTimeout(job.remainingSeconds());enable.execute();}finally{job.statement=null;}
        ObjectNode result;
        try{result=HumanSql.execute(job,c,sql,values,decisionTimeout,safeError);}
        catch(HumanSql.ScriptCancelled cancelled){if(output)drain(job,c,cancelled.partial(),safeError);throw cancelled;}
        if(output)drain(job,c,result,safeError);return result;
    }
    private static void drain(QueryJobs.Job job,Connection c,ObjectNode result,Function<Exception,String> safeError){
        ObjectNode output=result.putObject("serverOutput").put("enabled",true);ArrayNode lines=output.putArray("lines");
        if(job.cancelled){output.put("incomplete",true).put("message","Server output could not be collected after cancellation");return;}
        try(CallableStatement read=c.prepareCall("BEGIN DBMS_OUTPUT.GET_LINE(?,?); END;")){
            job.statement=read;read.registerOutParameter(1,Types.VARCHAR);read.registerOutParameter(2,Types.INTEGER);int bytes=0;
            for(int i=0;i<=256;i++){
                read.setQueryTimeout(job.remainingSeconds());read.execute();if(read.getInt(2)!=0)return;
                String line=Objects.toString(read.getString(1),"");int size=line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(i==256||bytes+size>100000){output.put("truncated",true);return;}
                lines.add(line);bytes+=size;
            }
        }catch(Exception failure){output.put("incomplete",true).put("message",safeError.apply(failure));}finally{job.statement=null;}
    }
}
