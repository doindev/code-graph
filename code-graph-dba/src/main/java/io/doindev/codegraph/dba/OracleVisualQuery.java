package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Oracle scalar function signatures, including independent package overloads. */
final class OracleVisualQuery {
    private OracleVisualQuery(){}
    static ObjectNode functions(QueryJobs.Job job,Connection c,String schema,String search,String key,int offset)throws Exception{
        if(c.getMetaData().getDatabaseMajorVersion()<19)throw VisualQuery.invalid("Oracle visual function discovery requires 19c or newer");
        if(schema.isBlank())schema=OracleDialect.target(job,c,job.remainingSeconds()).schema();
        if(!key.isEmpty()&&!key.matches("[0-9]+:[0-9]+"))throw VisualQuery.invalid("Invalid Oracle function identity");
        ObjectNode out=Profiles.JSON.createObjectNode();ArrayNode functions=out.putArray("functions");
        String sql="""
            SELECT p.OBJECT_ID,p.SUBPROGRAM_ID,p.OWNER,p.OBJECT_NAME,p.PROCEDURE_NAME,p.OVERLOAD,
                   p.AGGREGATE,p.PIPELINED,p.INTERFACE,a.DATA_TYPE,a.TYPE_OWNER
            FROM ALL_PROCEDURES p JOIN ALL_ARGUMENTS a
              ON a.OWNER=p.OWNER AND a.OBJECT_ID=p.OBJECT_ID AND a.SUBPROGRAM_ID=p.SUBPROGRAM_ID AND a.POSITION=0
            WHERE p.OWNER=? AND (p.OBJECT_TYPE='FUNCTION' OR p.OBJECT_TYPE='PACKAGE' AND p.PROCEDURE_NAME IS NOT NULL)
              AND (? IS NULL OR INSTR(UPPER(NVL(p.PROCEDURE_NAME,p.OBJECT_NAME)),UPPER(?))>0)
              AND (? IS NULL OR TO_CHAR(p.OBJECT_ID)||':'||TO_CHAR(p.SUBPROGRAM_ID)=?)
            ORDER BY p.OBJECT_NAME,p.PROCEDURE_NAME,p.SUBPROGRAM_ID OFFSET ? ROWS FETCH NEXT 201 ROWS ONLY
            """;
        try(PreparedStatement st=c.prepareStatement(sql)){
            job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setString(1,schema);st.setString(2,search);st.setString(3,search);st.setString(4,key);st.setString(5,key);st.setInt(6,offset);
            try(ResultSet rs=st.executeQuery()){while(rs.next()){
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                if(functions.size()==200){out.put("truncated",true).put("nextOffset",offset+200);break;}
                String member=rs.getString(5),type=Objects.toString(rs.getString(10),"unknown"),identity=rs.getString(1)+":"+rs.getString(2);
                boolean available=!"YES".equals(rs.getString(7))&&!"YES".equals(rs.getString(8))&&!"YES".equals(rs.getString(9))&&rs.getString(11)==null&&castType(type)!=null;
                ObjectNode f=functions.addObject().put("key",identity).put("schema",rs.getString(3)).put("name",member==null?rs.getString(4):member)
                    .put("package",member==null?"":rs.getString(4)).put("signature",member==null?"standalone":"overload "+Objects.toString(rs.getString(6),"1"))
                    .put("returnType",type).put("aggregate",false).put("available",available).put("signatureRequired",true);
                if(!available)f.put("reason","Only scalar SQL functions with supported return types are available. Table, aggregate, object and PL/SQL-only return types require Script.");
            }}
        }finally{job.statement=null;}
        if(!key.isEmpty()&&functions.size()==1&&functions.get(0).path("available").asBoolean())signature(job,c,schema,key,(ObjectNode)functions.get(0));
        out.put("notice","Functions are scoped to the selected owner. Package members retain their package and overload identity.");return out;
    }
    private static void signature(QueryJobs.Job job,Connection c,String schema,String key,ObjectNode function)throws SQLException{
        String[] ids=key.split(":");ArrayNode args=function.putArray("arguments");boolean valid=true;
        try(PreparedStatement st=c.prepareStatement("SELECT ARGUMENT_NAME,DATA_TYPE,IN_OUT,DEFAULTED,TYPE_OWNER,POSITION FROM ALL_ARGUMENTS WHERE OWNER=? AND OBJECT_ID=? AND SUBPROGRAM_ID=? AND POSITION>0 ORDER BY SEQUENCE FETCH FIRST 129 ROWS ONLY")){
            job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setString(1,schema);st.setLong(2,Long.parseLong(ids[0]));st.setLong(3,Long.parseLong(ids[1]));
            try(ResultSet rs=st.executeQuery()){while(rs.next()){
                if(job.cancelled)throw new java.util.concurrent.CancellationException();
                if(args.size()==128)throw VisualQuery.invalid("Function exceeds 128 arguments");
                String type=Objects.toString(rs.getString(2),"unknown");
                valid&="IN".equals(rs.getString(3))&&rs.getString(5)==null&&castType(type)!=null&&rs.getInt(6)==args.size()+1;
                args.addObject().put("name",Objects.toString(rs.getString(1),"argument")).put("type",type).put("valueType",valueType(type)).put("optional","Y".equals(rs.getString(4)));
            }}
        }finally{job.statement=null;}
        // Positional calls cannot omit a defaulted argument before a required argument.
        boolean required=false;for(int i=args.size()-1;i>=0;i--){ObjectNode arg=(ObjectNode)args.get(i);required|=!arg.path("optional").asBoolean();if(required)arg.put("optional",false);}
        function.put("signatureRequired",false).put("available",valid);
        if(!valid)function.put("reason","Output parameters, composite types and incomplete SQL signatures require Script.");
    }
    static String literal(JsonNode expression){
        String value=VisualQuery.literal(expression),type=expression.path("type").asText();
        if(value.equals("NULL"))return value;
        if(type.equals("timestamp"))return "TIMESTAMP '"+java.time.LocalDateTime.parse(expression.path("value").asText().replace(' ','T')).format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSSSSS",Locale.ROOT))+"'";
        if(Set.of("integer","number").contains(type)){
            ObjectNode parameter=Profiles.JSON.createObjectNode().put("mode","in").put("type","NUMBER").put("value",expression.path("value").asText());
            return OracleSql.displayInput(parameter);
        }
        return value;
    }
    static String castType(String type){
        return switch(type.toUpperCase(Locale.ROOT)){
            case "NUMBER","INTEGER","PLS_INTEGER","BINARY_INTEGER","SIMPLE_INTEGER"->"NUMBER";
            case "FLOAT"->"FLOAT";case "BINARY_FLOAT"->"BINARY_FLOAT";case "BINARY_DOUBLE"->"BINARY_DOUBLE";
            case "VARCHAR2","VARCHAR","CHAR"->"VARCHAR2(4000)";case "NVARCHAR2","NCHAR"->"NVARCHAR2(2000)";
            case "DATE"->"DATE";case "TIMESTAMP"->"TIMESTAMP(9)";default->null;
        };
    }
    private static String valueType(String type){String cast=castType(type);if(cast==null)return "unsupported";return cast.startsWith("NVARCHAR")||cast.startsWith("VARCHAR")?"text":cast.equals("DATE")||cast.startsWith("TIMESTAMP")?"timestamp":"number";}
}
