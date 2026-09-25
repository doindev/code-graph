package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import static io.doindev.codegraph.dba.TableDesigner.*;

/** Incremental Oracle heap-table DDL. Unedited native attributes are never reconstructed. */
final class OracleDesigner {
    private OracleDesigner(){}
    static String type(String input){
        String value=input.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+"," ");
        if(Set.of("NUMBER","INTEGER","INT","SMALLINT","DATE","BINARY_FLOAT","BINARY_DOUBLE","FLOAT","BLOB","CLOB","NCLOB").contains(value))return value;
        Matcher m=Pattern.compile("NUMBER\\((\\*|[0-9]{1,2})(?:\\s*,\\s*(-?[0-9]{1,3}))?\\)").matcher(value);
        if(m.matches()){int precision=m.group(1).equals("*")?38:Integer.parseInt(m.group(1)),scale=m.group(2)==null?0:Integer.parseInt(m.group(2));if(precision>=1&&precision<=38&&scale>=-84&&scale<=127)return value;}
        m=Pattern.compile("(VARCHAR2|NVARCHAR2|CHAR|NCHAR|RAW)\\(([0-9]{1,4})(?: (BYTE|CHAR))?\\)").matcher(value);
        if(m.matches()){String kind=m.group(1);int length=Integer.parseInt(m.group(2)),max=switch(kind){case "VARCHAR2"->4000;case "NCHAR"->1000;default->2000;};if(length>0&&length<=max&&(m.group(3)==null||Set.of("VARCHAR2","CHAR").contains(kind)))return value;}
        if(value.matches("TIMESTAMP(?:\\([0-9]\\))?(?: WITH (?:LOCAL )?TIME ZONE)?"))return value;
        m=Pattern.compile("FLOAT\\(([0-9]{1,3})\\)").matcher(value);if(m.matches()&&Integer.parseInt(m.group(1))>=1&&Integer.parseInt(m.group(1))<=126)return value;
        throw new IllegalArgumentException("Use an Oracle built-in datatype with a supported length, precision and scale");
    }
    static void capabilities(ObjectNode out){
        out.putArray("editableCategories").add("Constraints").add("Foreign Keys").add("Indexes");
        out.put("reason","Oracle heap tables use reviewed incremental DDL. DDL commits implicitly; completed steps remain committed if a later step fails. Unedited native attributes are retained.");
    }
    static ObjectNode load(QueryJobs.Job job,Connection c,ObjectNode out)throws Exception{
        String owner=str(out,"schema"),name=str(out,"name");OracleDialect.identifier(owner);OracleDialect.identifier(name);
        var target=OracleDialect.target(job,c,job.remainingSeconds());out.put("database",target.database()).set("oracleTarget",target.json());
        var tables=query(job,c,"SELECT t.TABLESPACE_NAME,t.TEMPORARY,t.PARTITIONED,t.IOT_TYPE,t.NESTED,t.SECONDARY,o.OBJECT_ID,cm.COMMENTS FROM SYS.ALL_TABLES t JOIN SYS.ALL_OBJECTS o ON o.OWNER=t.OWNER AND o.OBJECT_NAME=t.TABLE_NAME AND o.OBJECT_TYPE='TABLE' AND o.SUBOBJECT_NAME IS NULL LEFT JOIN SYS.ALL_TAB_COMMENTS cm ON cm.OWNER=t.OWNER AND cm.TABLE_NAME=t.TABLE_NAME AND cm.TABLE_TYPE='TABLE' WHERE t.OWNER=? AND t.TABLE_NAME=?",owner,name);
        if(tables.size()!=1)throw new IllegalArgumentException("Oracle table is unavailable or metadata privileges are missing");JsonNode table=tables.get(0);
        out.put("objectIdentity",str(table,"object_id")).put("editable",c.getMetaData().getDatabaseMajorVersion()>=19&&str(table,"temporary").equals("N")&&str(table,"partitioned").equals("NO")&&str(table,"iot_type").isEmpty()&&str(table,"nested").equals("NO")&&str(table,"secondary").equals("N"));capabilities(out);
        out.putObject("fields").put("name",name).put("schema",owner).put("owner",owner).put("comment",str(table,"comments")).put("tablespace",str(table,"tablespace_name"));
        if(!query(job,c,"SELECT 1 AS special FROM SYS.ALL_EXTERNAL_TABLES WHERE OWNER=? AND TABLE_NAME=? UNION ALL SELECT 1 FROM SYS.ALL_MVIEWS WHERE OWNER=? AND MVIEW_NAME=? UNION ALL SELECT 1 FROM SYS.ALL_INDEXES WHERE TABLE_OWNER=? AND TABLE_NAME=? AND INDEX_TYPE LIKE 'DOMAIN%' UNION ALL SELECT 1 FROM SYS.ALL_POLICIES WHERE OBJECT_OWNER=? AND OBJECT_NAME=? AND ENABLE='YES'",owner,name,owner,name,owner,name,owner,name).isEmpty())out.put("editable",false);
        var keys=query(job,c,"SELECT k.CONSTRAINT_NAME,k.CONSTRAINT_TYPE,c.COLUMN_NAME,c.POSITION FROM SYS.ALL_CONSTRAINTS k JOIN SYS.ALL_CONS_COLUMNS c ON c.OWNER=k.OWNER AND c.CONSTRAINT_NAME=k.CONSTRAINT_NAME AND c.TABLE_NAME=k.TABLE_NAME WHERE k.OWNER=? AND k.TABLE_NAME=? AND k.CONSTRAINT_TYPE='P' ORDER BY c.POSITION",owner,name);
        out.put("primaryKeyName",keys.isEmpty()?"":str(keys.get(0),"constraint_name"));
        var columns=query(job,c,"SELECT c.INTERNAL_COLUMN_ID AS id,c.COLUMN_NAME AS name,c.DATA_TYPE,c.DATA_TYPE_OWNER,c.DATA_LENGTH,c.CHAR_LENGTH,c.CHAR_USED,c.DATA_PRECISION,c.DATA_SCALE,c.NULLABLE,c.DATA_DEFAULT,c.IDENTITY_COLUMN,ic.GENERATION_TYPE,c.VIRTUAL_COLUMN,c.DEFAULT_ON_NULL,c.HIDDEN_COLUMN AS invisible_column,cm.COMMENTS FROM SYS.ALL_TAB_COLS c LEFT JOIN SYS.ALL_TAB_IDENTITY_COLS ic ON ic.OWNER=c.OWNER AND ic.TABLE_NAME=c.TABLE_NAME AND ic.COLUMN_NAME=c.COLUMN_NAME LEFT JOIN SYS.ALL_COL_COMMENTS cm ON cm.OWNER=c.OWNER AND cm.TABLE_NAME=c.TABLE_NAME AND cm.COLUMN_NAME=c.COLUMN_NAME WHERE c.OWNER=? AND c.TABLE_NAME=? AND (c.HIDDEN_COLUMN='NO' OR c.USER_GENERATED='YES') ORDER BY c.INTERNAL_COLUMN_ID",owner,name);
        if(columns.size()>MAX_COLUMNS)throw new IllegalArgumentException("Table exceeds the 256-column designer limit");
        for(JsonNode value:columns){ObjectNode col=(ObjectNode)value;String datatype=str(col,"data_type"),length=str(col,"char_length"),precision=str(col,"data_precision"),scale=str(col,"data_scale");
            if(Set.of("VARCHAR2","CHAR").contains(datatype))datatype+="("+(str(col,"char_used").equals("C")?length+" CHAR":str(col,"data_length")+" BYTE")+")";
            else if(Set.of("NVARCHAR2","NCHAR").contains(datatype))datatype+="("+length+")";
            else if(datatype.equals("RAW"))datatype+="("+str(col,"data_length")+")";
            else if(datatype.equals("NUMBER")&&(!precision.isEmpty()||!scale.isEmpty()))datatype+="("+(precision.isEmpty()?"*":precision)+(scale.isEmpty()?"":","+scale)+")";
            else if(datatype.equals("FLOAT")&&!precision.isEmpty())datatype+="("+precision+")";
            String definition=str(col,"data_default").strip();boolean virtual=str(col,"virtual_column").equals("YES"),identity=str(col,"identity_column").equals("YES");
            col.put("type",datatype).put("nullable",str(col,"nullable").equals("Y")).put("default",virtual||identity?"":definition).put("generated",virtual?definition:"").put("identity",identity?(str(col,"generation_type").equals("ALWAYS")?"a":"d"):"").put("comment",str(col,"comments")).put("pk",0);
            for(JsonNode key:keys)if(str(key,"column_name").equals(str(col,"name")))col.put("pk",key.path("position").asInt());
            if(!str(col,"data_type_owner").isEmpty())out.put("editable",false);
        }
        out.set("columns",columns);
        out.set("constraints",query(job,c,"SELECT CONSTRAINT_NAME AS id,CONSTRAINT_NAME AS name,CASE CONSTRAINT_TYPE WHEN 'P' THEN 'p' WHEN 'R' THEN 'f' WHEN 'U' THEN 'u' ELSE 'c' END AS kind,SEARCH_CONDITION_VC AS definition,STATUS,DEFERRABLE,DEFERRED,VALIDATED,RELY,R_OWNER,R_CONSTRAINT_NAME,DELETE_RULE,INDEX_OWNER,INDEX_NAME FROM SYS.ALL_CONSTRAINTS WHERE OWNER=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME",owner,name));
        out.set("indexes",query(job,c,"SELECT i.INDEX_NAME AS id,i.INDEX_NAME AS name,i.INDEX_TYPE AS definition,i.TABLESPACE_NAME,i.STATUS,CASE WHEN EXISTS(SELECT 1 FROM SYS.ALL_CONSTRAINTS k WHERE k.INDEX_OWNER=i.OWNER AND k.INDEX_NAME=i.INDEX_NAME) THEN 'true' ELSE 'false' END AS constrained FROM SYS.ALL_INDEXES i WHERE i.TABLE_OWNER=? AND i.TABLE_NAME=? ORDER BY i.INDEX_NAME",owner,name));
        try{out.put("ddl",OracleMetadata.definition(job,c,"TABLE",owner,name));if(str(out,"ddl").isBlank())throw new SQLException("Empty native definition");}
        catch(SQLException e){out.put("editable",false).put("reason","Native table definition is unavailable; DBMS_METADATA privileges are required for safe conflict detection.");}
        if(!out.path("editable").asBoolean()&&!out.path("reason").asText().contains("unavailable"))out.put("reason","This Oracle table requires native DDL review: partitioned, temporary, index-organized, nested, external, materialized, custom-type, domain-index or row-policy metadata is not editable here.");
        var fingerprint=out.deepCopy();fingerprint.remove(List.of("selection","sql","categories","reason"));out.put("fingerprint",hash(fingerprint));if(out.toString().length()>512*1024)throw new IllegalArgumentException("Oracle metadata exceeds designer allowance");return out;
    }
    static ObjectNode prepare(ObjectNode current,JsonNode request)throws Exception{
        JsonNode draft=request.path("draft"),fields=draft.path("fields");String owner=str(current,"schema"),target=OracleDialect.qualified(owner,str(current,"name"));
        for(String field:List.of("schema","owner","tablespace"))if(!str(fields,field).equals(str(current.path("fields"),field)))throw new IllegalArgumentException(field+" changes require native Oracle DDL review");
        OracleDialect.identifier(str(fields,"name"));ArrayNode commands=Profiles.JSON.createArrayNode();Map<String,JsonNode> original=new LinkedHashMap<>(),desired=new LinkedHashMap<>();current.path("columns").forEach(col->original.put(str(col,"id"),col));Set<String> names=new HashSet<>();Set<Integer> ranks=new HashSet<>();
        if(draft.path("columns").size()>MAX_COLUMNS)throw new IllegalArgumentException("At most 256 columns");
        for(JsonNode col:draft.path("columns")){String id=str(col,"id"),name=str(col,"name");if(desired.put(id,col)!=null||!original.containsKey(id)&&!id.startsWith("new:"))throw new IllegalArgumentException("Invalid stable column identity");
            if(col.path("deleted").asBoolean())continue;OracleDialect.identifier(name);if(!names.add(name))throw new IllegalArgumentException("Duplicate column name");int rank=col.path("pk").asInt();
            if(!col.path("pk").isIntegralNumber()||rank<0||rank>32||rank>0&&!ranks.add(rank))throw new IllegalArgumentException("Primary key positions must be distinct integers 1..32");if(rank>0&&col.path("nullable").asBoolean())throw new IllegalArgumentException("Primary-key columns must be NOT NULL");
        }
        if(names.isEmpty()||!desired.keySet().containsAll(original.keySet()))throw new IllegalArgumentException("Keep at least one column and explicitly mark removed columns");
        var before=primaryKeys(current.path("columns"));var after=primaryKeys(draft.path("columns"));boolean keyChanged=!before.stream().map(col->str(col,"id")).toList().equals(after.stream().map(col->str(col,"id")).toList());
        if(keyChanged&&!str(current,"primaryKeyName").isEmpty())throw new IllegalArgumentException("Existing Oracle primary-key changes require native DDL review to preserve constraint and index attributes");
        if(desired.values().stream().filter(col->!col.path("deleted").asBoolean()&&!str(col,"identity").isEmpty()).count()>1)throw new IllegalArgumentException("Oracle permits one identity column per table");
        for(JsonNode col:desired.values()){
            JsonNode old=original.get(str(col,"id"));if(old==null&&col.path("deleted").asBoolean())continue;String name=OracleDialect.identifier(str(col,"name"));
            if(old==null){
                String definition=expression(str(col,"default")),identity=str(col,"identity"),generated=str(col,"generated"),extra="";
                if(!identity.isEmpty()){if(!Set.of("a","d").contains(identity)||!definition.isEmpty()||!generated.isEmpty()||col.path("nullable").asBoolean())throw new IllegalArgumentException("Oracle identity requires NOT NULL and cannot combine a default or virtual expression");if(!type(str(col,"type")).matches("(?:NUMBER|INTEGER|INT|SMALLINT|FLOAT|BINARY_FLOAT|BINARY_DOUBLE)(?:\\(.*\\))?"))throw new IllegalArgumentException("Oracle identity requires a numeric datatype");extra=" GENERATED "+(identity.equals("a")?"ALWAYS":"BY DEFAULT")+" AS IDENTITY";}
                else if(!generated.isEmpty()){if(!definition.isEmpty())throw new IllegalArgumentException("Virtual expression and default cannot be combined");extra=" GENERATED ALWAYS AS ("+expression(generated)+") VIRTUAL";}
                else if(!definition.isEmpty())extra=" DEFAULT "+definition;
                add(commands,"ALTER TABLE "+target+" ADD "+name+" "+type(str(col,"type"))+extra+(col.path("nullable").asBoolean()?"":" NOT NULL"),!extra.isEmpty()||!col.path("nullable").asBoolean());
            }else{
                String oldName=OracleDialect.identifier(str(old,"name"));if(col.path("deleted").asBoolean()){add(commands,"ALTER TABLE "+target+" DROP COLUMN "+oldName,true);continue;}
                if(!str(old,"identity").equals(str(col,"identity"))||!str(old,"generated").equals(str(col,"generated")))throw new IllegalArgumentException("Existing identity and virtual settings must be preserved");
                boolean datatype=!str(old,"type").equalsIgnoreCase(str(col,"type")),nullable=old.path("nullable").asBoolean()!=col.path("nullable").asBoolean(),defaults=!str(old,"default").equals(str(col,"default"));
                if((datatype||nullable||defaults)&&(!str(old,"identity").isEmpty()||!str(old,"generated").isEmpty()||str(old,"default_on_null").equals("YES")))throw new IllegalArgumentException("Identity, virtual and DEFAULT ON NULL columns require native Oracle alteration review");
                if(!str(old,"name").equals(str(col,"name")))add(commands,"ALTER TABLE "+target+" RENAME COLUMN "+oldName+" TO "+name,false);
                if(datatype)add(commands,"ALTER TABLE "+target+" MODIFY ("+name+" "+type(str(col,"type"))+")",true);
                if(defaults){String definition=expression(str(col,"default"));add(commands,"ALTER TABLE "+target+" MODIFY ("+name+" DEFAULT "+(definition.isBlank()?"NULL":definition)+")",false);}
                if(nullable)add(commands,"ALTER TABLE "+target+" MODIFY ("+name+(col.path("nullable").asBoolean()?" NULL)":" NOT NULL)"),!col.path("nullable").asBoolean());
            }
            if(old==null?!str(col,"comment").isEmpty():!str(old,"comment").equals(str(col,"comment")))add(commands,"COMMENT ON COLUMN "+target+"."+name+" IS "+literal(str(col,"comment")),false);
        }
        if(keyChanged&&!after.isEmpty())add(commands,"ALTER TABLE "+target+" ADD PRIMARY KEY ("+String.join(",",after.stream().map(col->OracleDialect.identifier(str(col,"name"))).toList())+")",true);
        List<JsonNode> changes=new ArrayList<>();draft.path("objects").forEach(changes::add);changes.sort(Comparator.comparingInt(change->str(change,"action").equals("delete")?0:1));for(JsonNode change:changes)objectChange(current,change,commands,target);
        if(!str(current.path("fields"),"comment").equals(str(fields,"comment")))add(commands,"COMMENT ON TABLE "+target+" IS "+literal(str(fields,"comment")),false);
        if(!str(current,"name").equals(str(fields,"name")))add(commands,"ALTER TABLE "+target+" RENAME TO "+OracleDialect.identifier(str(fields,"name")),false);
        if(commands.isEmpty())throw new IllegalArgumentException("There are no changes to save");
        var plan=Profiles.JSON.createObjectNode().put("atomic",false).put("risky",true).put("expiresAt",System.currentTimeMillis()+300000);plan.set("snapshot",current);plan.set("draft",draft.deepCopy());plan.set("commands",commands);return plan;
    }
    private static List<JsonNode> primaryKeys(JsonNode columns){var result=new ArrayList<JsonNode>();for(JsonNode col:columns)if(!col.path("deleted").asBoolean()&&col.path("pk").asInt()>0)result.add(col);result.sort(Comparator.comparingInt(col->col.path("pk").asInt()));return result;}
    private static void objectChange(ObjectNode current,JsonNode change,ArrayNode commands,String target)throws Exception{
        String category=str(change,"category"),action=str(change,"action"),name=str(change,"name"),owner=str(current,"schema");
        if(Set.of("Constraints","Foreign Keys").contains(category)){
            if(action.equals("delete")){JsonNode old=find(current.path("constraints"),str(change,"id"));if(str(old,"kind").equals("p"))throw new IllegalArgumentException("Edit primary keys through Columns");add(commands,"ALTER TABLE "+target+" DROP CONSTRAINT "+OracleDialect.identifier(str(old,"name")),true);return;}
            if(action.equals("add")){String definition=switch(str(change,"kind")){case "CHECK"->"CHECK ("+expression(str(change,"expression"))+")";case "UNIQUE"->"UNIQUE ("+columnList(change.path("columns"))+")";case "FOREIGN KEY"->"FOREIGN KEY ("+columnList(change.path("columns"))+") REFERENCES "+OracleDialect.qualified(str(change,"schema"),str(change,"table"))+" ("+columnList(change.path("references"))+")";default->throw new IllegalArgumentException("Unsupported Oracle constraint kind");};add(commands,"ALTER TABLE "+target+" ADD CONSTRAINT "+OracleDialect.identifier(name)+" "+definition,true);return;}
        }
        if(category.equals("Indexes")){
            if(action.equals("add")){add(commands,"CREATE "+(change.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+OracleDialect.qualified(owner,name)+" ON "+target+" ("+columnList(change.path("columns"))+")",true);return;}
            var old=find(current.path("indexes"),str(change,"id"));if(str(old,"constrained").equals("true"))throw new IllegalArgumentException("Constraint-owned indexes must be edited through their constraint");
            if(action.equals("delete")){add(commands,"DROP INDEX "+OracleDialect.qualified(owner,str(old,"name")),true);return;}
            if(action.equals("rename")){add(commands,"ALTER INDEX "+OracleDialect.qualified(owner,str(old,"name"))+" RENAME TO "+OracleDialect.identifier(name),false);return;}
        }
        throw new IllegalArgumentException("This Oracle category requires native DDL review");
    }
}
