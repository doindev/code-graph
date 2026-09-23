package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.Types;
import java.util.*;
import java.util.regex.*;

/** Pure, conservative join type analysis. Unknown types never imply a safe conversion. */
final class VisualJoinTypes {
    private static final Set<String> PG = Set.of("postgresql", "greenplum", "yugabytedb", "cockroachdb");
    private static final Set<String> STANDARD = Set.of("h2", "hsqldb", "duckdb", "trino", "presto", "redshift", "snowflake");
    private static final Set<String> MSSQL = Set.of("sqlserver", "azure-sql");
    private static final Set<String> MYSQL = Set.of("mysql", "mariadb");
    private static final Pattern TYPE = Pattern.compile("([A-Z][A-Z0-9 ]*?)(?:\\(([0-9]{1,6}|MAX)(?:,([0-9]{1,4}))?\\))?");
    static ArrayNode analyze(JsonNode model, String engine) {
        Map<String,JsonNode> sources=new LinkedHashMap<>();
        for(JsonNode source:model.path("sources"))sources.put(source.path("id").asText(),source);
        ArrayNode result=Profiles.JSON.createArrayNode();int[] budget={0};
        for(JsonNode root:model.path("roots"))walk(root,sources,engine,result,0,budget);
        return result;
    }
    private static void walk(JsonNode node,Map<String,JsonNode> sources,String engine,ArrayNode result,int depth,int[] budget) {
        if(depth>64||++budget[0]>8000)throw VisualQuery.invalid("Join type analysis exceeds the query complexity limit");
        if(!node.path("kind").asText().equals("join"))return;
        int index=0;
        for(JsonNode pair:node.path("pairs")){
            if(++budget[0]>8000)throw VisualQuery.invalid("Too many join predicates");
            ObjectNode analysis=assess(pair,sources,engine);
            analysis.put("joinId",node.path("id").asText()).put("pairId",pair.path("id").asText()).put("index",index++);result.add(analysis);
        }
        walk(node.path("left"),sources,engine,result,depth+1,budget);walk(node.path("right"),sources,engine,result,depth+1,budget);
    }
    static ObjectNode assess(JsonNode pair,Map<String,JsonNode> sources,String engine) {
        JsonNode left=metadata(pair.path("left"),sources),right=metadata(pair.path("right"),sources);
        String op=pair.path("op").asText("="),leftCast="",rightCast="",castError="";
        String lf=family(left,engine),rf=family(right,engine);
        try {
            leftCast=cast(pair,"leftCast",engine);rightCast=cast(pair,"rightCast",engine);
            if(!leftCast.isEmpty()&&!canConvert(lf,family(castMetadata(leftCast),engine)))throw VisualQuery.invalid("Unsupported conversion of the left join column to "+leftCast);
            if(!rightCast.isEmpty()&&!canConvert(rf,family(castMetadata(rightCast),engine)))throw VisualQuery.invalid("Unsupported conversion of the right join column to "+rightCast);
        } catch(IllegalArgumentException invalidCast) { castError=invalidCast.getMessage()+" "; }
        String effectiveLeft=leftCast.isEmpty()?lf:family(castMetadata(leftCast),engine),effectiveRight=rightCast.isEmpty()?rf:family(castMetadata(rightCast),engine);
        boolean compatible=castError.isEmpty()&&compatible(effectiveLeft,effectiveRight,op),converted=!pair.path("leftCast").asText().isBlank()||!pair.path("rightCast").asText().isBlank();
        String signature=signature(pair,left,right,engine);
        boolean acknowledged=!converted&&signature.equals(pair.path("typeAcknowledgment").asText());
        ObjectNode out=Profiles.JSON.createObjectNode().put("leftType",display(left)).put("rightType",display(right)).put("signature",signature)
                .put("resolved",compatible||acknowledged).put("status",compatible?(converted?"converted":"compatible"):acknowledged?"acknowledged":"unresolved");
        ArrayNode choices=out.putArray("conversions");
        choice(choices,"left",left,right,engine,op);choice(choices,"right",right,left,engine,op);
        String message=compatible?(converted?"Explicit conversion selected. "+conversionNotice(engine):"Column types are compatible."):
                acknowledged?"Type compatibility is not verified. The unchanged comparison was explicitly accepted; the database may reject or coerce it.":
                "Join types "+display(left)+" and "+display(right)+" are incompatible or unverified for "+op+". Choose a supported conversion or explicitly keep the comparison unchanged.";
        out.put("message",castError+message);return out;
    }
    static String conversionNotice(String engine) {
        return "CAST uses database conversion rules; invalid values can fail and precision or length limits can change values."
                +(MYSQL.contains(engine)?" This engine may coerce invalid values with a warning instead of an error.":"");
    }
    private static void choice(ArrayNode choices,String side,JsonNode from,JsonNode to,String engine,String op) {
        String target=target(to,engine),reason="",fromFamily=family(from,engine);
        if(target==null)reason="No verified CAST target is available for "+display(to)+" on "+engine+" (its type or precision metadata may be unavailable).";
        else if(!canConvert(fromFamily,family(castMetadata(target),engine))||!compatible(family(castMetadata(target),engine),family(to,engine),op))reason="This conversion/comparison is not supported by the builder's verified type rules.";
        ObjectNode choice=choices.addObject().put("side",side).put("available",reason.isEmpty()).put("reason",reason);
        if(reason.isEmpty())choice.put("castType",target).put("label","Convert "+side+" column to "+target).put("notice",conversionNotice(engine));
    }
    private static JsonNode metadata(JsonNode expression,Map<String,JsonNode> sources) {
        JsonNode source=sources.get(expression.path("source").asText());
        if(source!=null)for(JsonNode column:source.path("columns"))if(column.path("name").asText().equals(expression.path("name").asText()))return column;
        return Profiles.JSON.createObjectNode();
    }
    private static String display(JsonNode column) {
        String name=column.path("type").asText("Unknown type");if(name.isBlank())name="Unknown type";
        int precision=column.path("precision").asInt(0),scale=column.path("scale").asInt(-1),jdbc=column.path("jdbcType").asInt(Types.OTHER);
        if(!name.contains("(")&&precision>0){if(Set.of(Types.DECIMAL,Types.NUMERIC).contains(jdbc)&&scale>=0)name+="("+precision+","+scale+")";else if(Set.of(Types.VARCHAR,Types.CHAR,Types.NVARCHAR,Types.NCHAR).contains(jdbc))name+="("+precision+")";}
        return name;
    }
    private static String family(JsonNode column,String engine) {
        String nativeType=column.path("type").asText().toUpperCase(Locale.ROOT);
        if(nativeType.equals("UUID")&&(PG.contains(engine)||Set.of("h2","duckdb").contains(engine)))return "uuid";
        if(nativeType.equals("UNIQUEIDENTIFIER")&&MSSQL.contains(engine))return "uuid";
        return switch(column.path("jdbcType").asInt(Types.OTHER)) {
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.NUMERIC,Types.DECIMAL,Types.REAL,Types.FLOAT,Types.DOUBLE -> "number";
            case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR -> "text";
            case Types.BOOLEAN -> "boolean";
            case Types.BIT -> MSSQL.contains(engine)||nativeType.equals("BOOL")||nativeType.equals("BOOLEAN")?"boolean":"unknown";
            case Types.DATE -> "date";case Types.TIME -> "time";case Types.TIMESTAMP -> "timestamp";
            case Types.TIME_WITH_TIMEZONE -> "time-zone";case Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp-zone";
            case Types.BINARY,Types.VARBINARY,Types.LONGVARBINARY -> "binary";
            default -> "unknown";
        };
    }
    private static boolean compatible(String left,String right,String op) {
        return !left.equals("unknown")&&left.equals(right)&&(!Set.of("boolean","binary","uuid").contains(left)||Set.of("=","<>").contains(op));
    }
    private static boolean canConvert(String from,String to) {
        if(from.equals("unknown")||to.equals("unknown")||from.equals("binary")||to.equals("binary"))return false;
        return from.equals(to)||from.equals("text")||to.equals("text")||Set.of("date","timestamp").contains(from)&&Set.of("date","timestamp").contains(to);
    }
    private static String signature(JsonNode pair,JsonNode left,JsonNode right,String engine) {
        ObjectNode a=operand(pair.path("left"),left),b=operand(pair.path("right"),right);String op=pair.path("op").asText("=");
        if(a.toString().compareTo(b.toString())>0){ObjectNode temp=a;a=b;b=temp;op=reverse(op);}
        ObjectNode value=Profiles.JSON.createObjectNode().put("engine",engine).put("op",op);value.set("left",a);value.set("right",b);try{return TableDesigner.hash(value);}catch(Exception e){throw new IllegalStateException("Cannot fingerprint join type metadata",e);}
    }
    private static ObjectNode operand(JsonNode e,JsonNode metadata) {
        ObjectNode value=Profiles.JSON.createObjectNode().put("source",e.path("source").asText()).put("column",e.path("name").asText());
        for(String name:List.of("type","jdbcType","precision","scale","length","signed"))value.set(name,metadata.path(name));return value;
    }
    static String reverse(String op){return switch(op){case "<"->">";case ">"->"<";case "<="->">=";case ">="->"<=";default->op;};}
    private static String target(JsonNode column,String engine) {
        if(!PG.contains(engine)&&!STANDARD.contains(engine)&&!MSSQL.contains(engine)&&!MYSQL.contains(engine)&&!engine.equals("oracle"))return null;
        String family=family(column,engine);boolean pg=PG.contains(engine),my=MYSQL.contains(engine),ms=MSSQL.contains(engine),oracle=engine.equals("oracle");
        int jdbc=column.path("jdbcType").asInt(Types.OTHER),p=column.path("precision").asInt(0),s=column.path("scale").asInt(-1);
        String result=switch(family){
            case "text" -> textTarget(column,engine);
            case "uuid" -> ms?"UNIQUEIDENTIFIER":"UUID";
            case "boolean" -> my||oracle?null:ms?"BIT":"BOOLEAN";
            case "date" -> "DATE";
            case "time" -> oracle?null:"TIME";
            case "timestamp" -> my?"DATETIME":ms?"DATETIME2":"TIMESTAMP";
            case "time-zone" -> pg||engine.equals("h2")?"TIME WITH TIME ZONE":null;
            case "timestamp-zone" -> ms?"DATETIMEOFFSET":pg||oracle||engine.equals("h2")?"TIMESTAMP WITH TIME ZONE":null;
            case "number" -> {
                if(Set.of(Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT).contains(jdbc)){
                    if(my)yield column.path("signed").asBoolean(true)?"SIGNED":"UNSIGNED";
                    if(oracle)yield "NUMBER("+(jdbc==Types.BIGINT?19:jdbc==Types.INTEGER?10:jdbc==Types.SMALLINT?5:3)+",0)";
                    yield jdbc==Types.BIGINT?"BIGINT":jdbc==Types.INTEGER?"INTEGER":"SMALLINT";
                }
                if(Set.of(Types.REAL,Types.FLOAT,Types.DOUBLE).contains(jdbc))yield my?null:oracle?"BINARY_DOUBLE":ms?"FLOAT":pg||engine.equals("redshift")?"DOUBLE PRECISION":"DOUBLE";
                if(p>0&&p<=(pg||engine.equals("h2")?1000:my?65:38)&&s>=0&&s<=p&&(!my||s<=30))yield (oracle?"NUMBER":"DECIMAL")+"("+p+","+s+")";
                yield pg?"NUMERIC":null;
            }
            default -> null;
        };
        return result==null?null:validateCast(result,engine);
    }
    private static String textTarget(JsonNode column,String engine) {
        String nativeType=column.path("type").asText().toUpperCase(Locale.ROOT);
        int size=column.path("precision").asInt(0),jdbc=column.path("jdbcType").asInt(Types.OTHER);
        if(PG.contains(engine)&&nativeType.equals("TEXT"))return "TEXT";
        boolean fixed=jdbc==Types.CHAR||jdbc==Types.NCHAR,national=jdbc==Types.NCHAR||jdbc==Types.NVARCHAR||jdbc==Types.LONGNVARCHAR;
        String base=MYSQL.contains(engine)?"CHAR":engine.equals("oracle")?(national?"NVARCHAR2":"VARCHAR2"):MSSQL.contains(engine)&&national?(fixed?"NCHAR":"NVARCHAR"):(fixed?"CHAR":"VARCHAR");
        if(MSSQL.contains(engine)&&!fixed&&(size>textLimit(base,engine)||nativeType.contains("MAX")))return base+"(MAX)";
        // Unbounded text has known syntax on these engines. Never guess a driver default length.
        if(!fixed&&(PG.contains(engine)||Set.of("h2","duckdb","trino","presto","snowflake").contains(engine))&&(size==0||size>1000000))return "VARCHAR";
        if(size<1||size>textLimit(base,engine))return null;
        return base+"("+size+")";
    }
    private static int textLimit(String base,String engine) {
        if(MSSQL.contains(engine))return base.startsWith("N")?4000:8000;
        if(engine.equals("oracle"))return base.equals("VARCHAR2")?4000:2000;
        if(engine.equals("redshift"))return base.equals("CHAR")?4096:65535;
        return 1000000;
    }
    static String cast(JsonNode pair,String side,String engine){String value=pair.path(side).asText();return value.isBlank()?"":validateCast(value,engine);}
    static String validateCast(String value,String engine) {
        if(value.length()>128)throw VisualQuery.invalid("Join CAST type exceeds 128 characters");
        String type=value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+"," ").replaceAll("\\s*([(),])\\s*","$1");
        Matcher m=TYPE.matcher(type);if(!m.matches())throw VisualQuery.invalid("Unsupported join CAST type: "+value);
        String base=m.group(1),arg=m.group(2),scale=m.group(3);
        base=switch(base){case "INT","INT4"->"INTEGER";case "INT2"->"SMALLINT";case "INT8"->"BIGINT";case "BOOL"->"BOOLEAN";case "CHARACTER VARYING"->"VARCHAR";case "CHARACTER"->"CHAR";case "TIMESTAMP WITHOUT TIME ZONE"->"TIMESTAMP";case "TIME WITHOUT TIME ZONE"->"TIME";default->base;};
        Set<String> types=PG.contains(engine)?Set.of("SMALLINT","INTEGER","BIGINT","NUMERIC","DECIMAL","REAL","DOUBLE PRECISION","TEXT","VARCHAR","CHAR","BOOLEAN","DATE","TIME","TIMESTAMP","TIME WITH TIME ZONE","TIMESTAMP WITH TIME ZONE","UUID"):
                MYSQL.contains(engine)?Set.of("SIGNED","UNSIGNED","DECIMAL","CHAR","DATE","TIME","DATETIME"):
                MSSQL.contains(engine)?Set.of("SMALLINT","INTEGER","BIGINT","DECIMAL","NUMERIC","REAL","FLOAT","VARCHAR","NVARCHAR","CHAR","NCHAR","BIT","DATE","TIME","DATETIME","DATETIME2","DATETIMEOFFSET","UNIQUEIDENTIFIER"):
                engine.equals("oracle")?Set.of("NUMBER","BINARY_DOUBLE","VARCHAR2","NVARCHAR2","CHAR","NCHAR","DATE","TIMESTAMP","TIMESTAMP WITH TIME ZONE"):
                STANDARD.contains(engine)?Set.of("SMALLINT","INTEGER","BIGINT","NUMERIC","DECIMAL","REAL","DOUBLE","DOUBLE PRECISION","VARCHAR","CHAR","BOOLEAN","DATE","TIME","TIMESTAMP"):Set.of();
        if((engine.equals("h2")||engine.equals("duckdb"))&&base.equals("UUID"))types=Set.of("UUID");
        if(engine.equals("h2")&&Set.of("TIME WITH TIME ZONE","TIMESTAMP WITH TIME ZONE").contains(base))types=Set.of(base);
        if(!types.contains(base))throw VisualQuery.invalid("Join CAST to "+value+" is not verified for "+engine);
        boolean decimal=Set.of("DECIMAL","NUMERIC","NUMBER").contains(base),text=Set.of("VARCHAR","NVARCHAR","VARCHAR2","NVARCHAR2","CHAR","NCHAR").contains(base);
        if(arg!=null){
            if(!decimal&&!text)throw VisualQuery.invalid("Unsupported join CAST type modifiers: "+value);
            if(arg.equals("MAX")){if(!MSSQL.contains(engine)||!Set.of("VARCHAR","NVARCHAR").contains(base)||scale!=null)throw VisualQuery.invalid("Unsupported MAX CAST type");}
            else {int n=Integer.parseInt(arg),max=decimal?(PG.contains(engine)||engine.equals("h2")?1000:MYSQL.contains(engine)?65:38):textLimit(base,engine);
                if(n<1||n>max||scale!=null&&(!decimal||(Integer.parseInt(scale)>n||MYSQL.contains(engine)&&Integer.parseInt(scale)>30)))throw VisualQuery.invalid("Unsupported join CAST precision/length: "+value);}
        }else if(decimal&&!PG.contains(engine))throw VisualQuery.invalid("Choose an explicit precision and scale for this join CAST");
        return base+(arg==null?"":"("+arg+(scale==null?"":","+scale)+")");
    }
    private static ObjectNode castMetadata(String type) {
        String base=type.replaceFirst("\\(.*","");int jdbc=switch(base){
            case "SMALLINT"->Types.SMALLINT;case "INTEGER"->Types.INTEGER;case "BIGINT","SIGNED","UNSIGNED"->Types.BIGINT;
            case "DECIMAL","NUMERIC","NUMBER"->Types.DECIMAL;case "REAL","FLOAT","DOUBLE","DOUBLE PRECISION","BINARY_DOUBLE"->Types.DOUBLE;
            case "TEXT","VARCHAR","CHAR","NVARCHAR","NCHAR","VARCHAR2","NVARCHAR2"->Types.VARCHAR;
            case "BOOLEAN","BIT"->Types.BOOLEAN;case "DATE"->Types.DATE;case "TIME"->Types.TIME;case "TIMESTAMP","DATETIME","DATETIME2"->Types.TIMESTAMP;
            case "TIME WITH TIME ZONE"->Types.TIME_WITH_TIMEZONE;case "TIMESTAMP WITH TIME ZONE","DATETIMEOFFSET"->Types.TIMESTAMP_WITH_TIMEZONE;default->Types.OTHER;};
        return Profiles.JSON.createObjectNode().put("type",base).put("jdbcType",jdbc);
    }
}
