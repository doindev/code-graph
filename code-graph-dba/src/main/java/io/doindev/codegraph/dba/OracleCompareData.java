package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Oracle row metadata and lossless, bounded script values. Never executes generated DML. */
final class OracleCompareData {
    private OracleCompareData(){}
    private static void check(QueryJobs.Job job){if(job!=null)CompareCatalog.check(job);}
    static void metadata(QueryJobs.Job job,Connection connection,ObjectNode object)throws Exception{
        String owner=str(object,"schema"),table=str(object,"name");
        object.put("dataSupported",true).put("dataReason","");
        ArrayNode columns=object.putArray("columns"),keys=object.putArray("keys"),indexes=object.putArray("indexes"),foreign=object.putArray("foreignKeys");
        for(JsonNode row:query(job,connection,"SELECT column_name,data_type,data_type_owner,data_length,data_precision,data_scale,char_length,char_used,nullable,virtual_column,identity_column,collation FROM SYS.all_tab_cols WHERE owner=? AND table_name=? AND (hidden_column='NO' OR user_generated='YES') ORDER BY internal_column_id",owner,table)){
            ObjectNode col=columns.addObject().put("name",str(row,"column_name")).put("type",str(row,"data_type")).put("nullable",str(row,"nullable").equals("Y"))
                .put("generated",str(row,"virtual_column").equals("YES")?"virtual":"").put("identity",str(row,"identity_column").equals("YES")?"identity":"");
            for(String field:List.of("data_length","data_precision","data_scale","char_length","char_used","collation"))col.set(field,row.path(field));
            String type=str(col,"type");
            if(!str(row,"data_type_owner").isEmpty()||!supported(type))block(object,"Row capture is unavailable for Oracle type "+type+" in "+str(col,"name"));
            if(!str(col,"identity").isEmpty())block(object,"Identity columns require a separately reviewed generator transition");
        }
        if(columns.isEmpty())block(object,"Column metadata is unavailable");
        var constraints=new LinkedHashMap<String,ObjectNode>();
        for(JsonNode row:query(job,connection,"SELECT c.constraint_name,c.constraint_type,c.status,c.validated,c.deferrable,k.column_name,k.position,r.owner AS reference_owner,r.table_name AS reference_table,rk.column_name AS reference_column FROM SYS.all_constraints c JOIN SYS.all_cons_columns k ON k.owner=c.owner AND k.constraint_name=c.constraint_name LEFT JOIN SYS.all_constraints r ON r.owner=c.r_owner AND r.constraint_name=c.r_constraint_name LEFT JOIN SYS.all_cons_columns rk ON rk.owner=r.owner AND rk.constraint_name=r.constraint_name AND rk.position=k.position WHERE c.owner=? AND c.table_name=? AND c.constraint_type IN ('P','U','R') ORDER BY c.constraint_name,k.position",owner,table)){
            String name=str(row,"constraint_name");ObjectNode constraint=constraints.computeIfAbsent(name,n->{ObjectNode out=Profiles.JSON.createObjectNode().put("name",n).put("kind",str(row,"constraint_type")).put("primary",str(row,"constraint_type").equals("P"));out.putArray("columns");out.putArray("references");return out;});
            constraint.withArray("columns").add(str(row,"column_name"));constraint.withArray("references").add(str(row,"reference_column"));constraint.put("schema",str(row,"reference_owner")).put("table",str(row,"reference_table"));
            if(!str(row,"status").equals("ENABLED")||!str(row,"validated").equals("VALIDATED")||!str(row,"deferrable").equals("NOT DEFERRABLE"))block(object,"Disabled, unvalidated or deferrable constraints require a dedicated data transition");
        }
        var byName=CompareSql.byName(columns);
        for(ObjectNode constraint:constraints.values()){
            if(str(constraint,"kind").equals("R")){foreign.add(constraint);continue;}
            boolean usable=true;for(JsonNode name:constraint.path("columns")){JsonNode col=byName.get(name.asText());if(col==null||col.path("nullable").asBoolean()||!keySupported(col))usable=false;}
            if(usable)keys.add(constraint);
        }
        // Unique indexes, including those without a constraint, participate in conflict checks.
        var byIndex=new LinkedHashMap<String,ObjectNode>();
        for(JsonNode row:query(job,connection,"SELECT i.index_name,i.uniqueness,i.index_type,k.column_name,k.column_position FROM SYS.all_indexes i JOIN SYS.all_ind_columns k ON k.index_owner=i.owner AND k.index_name=i.index_name WHERE i.table_owner=? AND i.table_name=? ORDER BY i.index_name,k.column_position",owner,table)){
            ObjectNode index=byIndex.computeIfAbsent(str(row,"index_name"),n->{ObjectNode out=Profiles.JSON.createObjectNode().put("name",n).put("unique",str(row,"uniqueness").equals("UNIQUE"));out.putArray("columns");return out;});index.withArray("columns").add(str(row,"column_name"));
            if(index.path("unique").asBoolean()&&!str(byName.getOrDefault(str(row,"column_name"),Profiles.JSON.createObjectNode()),"generated").isEmpty())block(object,"Unique virtual columns require a dedicated data transition");
            if(!Set.of("NORMAL","NORMAL/REV","BITMAP").contains(str(row,"index_type")))block(object,"Function, domain and specialized indexes require dedicated data validation");
        }
        byIndex.values().forEach(indexes::add);
        if(!query(job,connection,"SELECT trigger_name FROM SYS.all_triggers WHERE table_owner=? AND table_name=? AND status='ENABLED'",owner,table).isEmpty())block(object,"Enabled triggers can change copied rows or external state");
        if(!query(job,connection,"SELECT policy_name FROM SYS.all_policies WHERE object_owner=? AND object_name=? AND enable='YES'",owner,table).isEmpty())block(object,"Row security policies prevent complete row comparison");
        if(!query(job,connection,"SELECT table_name FROM SYS.all_tables WHERE owner=? AND table_name=? AND (temporary='Y' OR nested='YES' OR secondary='Y')",owner,table).isEmpty())block(object,"Temporary, nested and secondary tables need a dedicated data adapter");
    }
    static void block(ObjectNode object,String reason){object.put("dataSupported",false);String prior=str(object,"dataReason");object.put("dataReason",prior.isBlank()?reason:prior+"; "+reason);}
    static boolean supported(String type){return Set.of("NUMBER","FLOAT","BINARY_FLOAT","BINARY_DOUBLE","VARCHAR2","NVARCHAR2","CHAR","NCHAR","DATE","RAW","BLOB","CLOB","NCLOB").contains(type)||type.startsWith("TIMESTAMP")||type.startsWith("INTERVAL");}
    static boolean keySupported(JsonNode column){String type=str(column,"type"),collation=str(column,"collation");return str(column,"generated").isEmpty()&&(Set.of("NUMBER","FLOAT","RAW").contains(type)||Set.of("VARCHAR2","NVARCHAR2").contains(type)&&collation.equals("BINARY"));}
    static final String DATE="YYYY-MM-DD HH24:MI:SS AD",STAMP="YYYY-MM-DD HH24:MI:SS.FF9 AD",ZONE=STAMP+" TZR TZD";
    static String projection(JsonNode column){String name=OracleDialect.identifier(str(column,"name")),type=str(column,"type");
        if(type.equals("DATE"))return "TO_CHAR("+name+",'"+DATE+"','NLS_DATE_LANGUAGE=English')";
        if(type.startsWith("TIMESTAMP")){
            if(type.contains("LOCAL TIME ZONE"))return "TO_CHAR(SYS_EXTRACT_UTC("+name+"),'"+STAMP+"','NLS_DATE_LANGUAGE=English')";
            return "TO_CHAR("+name+",'"+(type.contains("TIME ZONE")?ZONE:STAMP)+"','NLS_DATE_LANGUAGE=English')";
        }
        if(type.startsWith("INTERVAL"))return "TO_CHAR("+name+")";
        return name;
    }
    static ObjectNode cell(QueryJobs.Job job,ResultSet rs,int index,JsonNode column)throws Exception{
        check(job);
        String type=str(column,"type"),value,sql,kind="text";ObjectNode out=Profiles.JSON.createObjectNode();
        if(Set.of("BLOB","RAW").contains(type)){
            try(InputStream stream=rs.getBinaryStream(index)){if(stream==null)return nil();ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1){check(job);if(bytes.size()+n>1<<20)throw new IllegalArgumentException("Binary value exceeds 1 MiB");bytes.write(buffer,0,n);}value=HexFormat.of().formatHex(bytes.toByteArray());}
            return out.put("type",type.equals("BLOB")?"oracle_blob":value.length()>2000?"oracle_raw":"binary").put("value",value).put("sql",type.equals("BLOB")?"EMPTY_BLOB()":"HEXTORAW('"+value+"')");
        }
        if(Set.of("NUMBER","FLOAT","BINARY_FLOAT","BINARY_DOUBLE").contains(type)){
            value=rs.getString(index);if(value==null)return nil();try{value=new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();}catch(NumberFormatException failure){throw new IllegalArgumentException("Non-finite Oracle numeric data requires a dedicated literal");}
            return out.put("type","number").put("value",value).put("sql",value+(type.equals("BINARY_FLOAT")?"f":type.equals("BINARY_DOUBLE")?"d":""));
        }
        try(Reader reader=rs.getCharacterStream(index)){if(reader==null)return nil();StringBuilder text=new StringBuilder();char[] buffer=new char[4096];int n;while((n=reader.read(buffer))!=-1){check(job);if(text.length()+n>1<<19)throw new IllegalArgumentException("Text value exceeds 512 KiB");text.append(buffer,0,n);}value=text.toString();}
        if(type.equals("DATE")){kind="date";sql="TO_DATE("+quote(value)+",'"+DATE+"','NLS_DATE_LANGUAGE=English')";}
        else if(type.startsWith("TIMESTAMP")){kind="temporal";boolean local=type.contains("LOCAL TIME ZONE"),zone=type.contains("TIME ZONE");sql=(zone?"TO_TIMESTAMP_TZ(":"TO_TIMESTAMP(")+quote(local?value+" +00:00":value)+",'"+(local?STAMP+" TZH:TZM":zone?ZONE:STAMP)+"','NLS_DATE_LANGUAGE=English')";}
        else if(type.startsWith("INTERVAL")){kind="interval";sql=(type.contains("YEAR")?"TO_YMINTERVAL(":"TO_DSINTERVAL(")+quote(value)+")";}
        else if(Set.of("CLOB","NCLOB").contains(type)){kind=type.equals("NCLOB")?"oracle_nclob":"oracle_clob";sql="EMPTY_CLOB()";}
        else {sql=unicode(value);if(value.length()>500)kind=Set.of("NVARCHAR2","NCHAR").contains(type)?"oracle_nstring":"oracle_string";}
        return out.put("type",kind).put("value",value).put("sql",sql);
    }
    static ObjectNode nil(){return Profiles.JSON.createObjectNode().put("type","null").putNull("value").put("sql","NULL");}
    static String quote(String value){return "'"+value.replace("'","''")+"'";}
    // ASCII-only expressions also preserve national characters when the SQL client uses a legacy encoding.
    static String unicode(String value){StringBuilder out=new StringBuilder("UNISTR('");for(int i=0;i<value.length();i++){char c=value.charAt(i);if(c>=32&&c<127&&c!='\\')out.append(c=='\''?"''":Character.toString(c));else out.append(String.format(Locale.ROOT,"\\%04x",(int)c));}return out.append("')").toString();}
    static List<String> chunks(String value){var chunks=new ArrayList<String>();for(int start=0;start<value.length();){int end=Math.min(start+500,value.length());if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;chunks.add(value.substring(start,end));start=end;}return chunks;}
    static void insert(QueryJobs.Job job,Writer out,String stage,List<String> columns,JsonNode values)throws Exception{
        boolean block=false;for(JsonNode value:values)if(str(value,"type").startsWith("oracle_"))block=true;
        List<String> sql=new ArrayList<>();StringBuilder declarations=new StringBuilder(),setup=new StringBuilder(),cleanup=new StringBuilder();int index=0;
        for(JsonNode cell:values){check(job);String kind=str(cell,"type"),value=str(cell,"value"),variable="v"+index++;
            if(!kind.startsWith("oracle_")){sql.add(str(cell,"sql"));continue;}
            String type=switch(kind){case "oracle_blob"->"BLOB";case "oracle_clob"->"CLOB";case "oracle_nclob"->"NCLOB";case "oracle_raw"->"RAW(32767)";case "oracle_nstring"->"NVARCHAR2(32767)";default->"VARCHAR2(32767)";};
            declarations.append("  ").append(variable).append(' ').append(type).append(";\n");sql.add(variable);
            if(kind.equals("oracle_raw")){for(int start=0;start<value.length();start+=2000)setup.append("  ").append(variable).append(" := UTL_RAW.CONCAT(").append(variable).append(",HEXTORAW('").append(value,start,Math.min(start+2000,value.length())).append("'));\n");continue;}
            if(kind.equals("oracle_string")||kind.equals("oracle_nstring")){for(String chunk:chunks(value)){check(job);setup.append("  ").append(variable).append(" := ").append(variable).append(" || ").append(kind.equals("oracle_string")?"TO_CHAR(":"").append(unicode(chunk)).append(kind.equals("oracle_string")?")":"").append(";\n");}continue;}
            setup.append("  DBMS_LOB.CREATETEMPORARY(").append(variable).append(",TRUE,DBMS_LOB.CALL);\n");
            if(kind.equals("oracle_blob")){for(int i=0;i<value.length();i+=2000){check(job);String chunk=value.substring(i,Math.min(i+2000,value.length()));setup.append("  DBMS_LOB.WRITEAPPEND(").append(variable).append(',').append(chunk.length()/2).append(",HEXTORAW('").append(chunk).append("'));\n");}}
            else for(String chunk:chunks(value)){check(job);String expression=unicode(chunk);setup.append("  DBMS_LOB.WRITEAPPEND(").append(variable).append(",LENGTH(").append(expression).append("),").append(expression).append(");\n");}
            cleanup.append("  IF DBMS_LOB.ISTEMPORARY(").append(variable).append(")=1 THEN DBMS_LOB.FREETEMPORARY(").append(variable).append("); END IF;\n");
        }
        String insert="INSERT INTO "+stage+" ("+CompareDataSql.names("oracle",columns)+") VALUES ("+String.join(", ",sql)+");\n";
        if(block)out.write("DECLARE\n"+declarations+"BEGIN\n"+setup+"  "+insert+cleanup+"EXCEPTION WHEN OTHERS THEN\n"+cleanup+"  RAISE;\nEND;\n/\n");else out.write(insert);
    }
}
