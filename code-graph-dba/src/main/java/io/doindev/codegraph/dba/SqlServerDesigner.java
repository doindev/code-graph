package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.TableDesigner.*;

/** Ordinary SQL Server disk-table adapter. No replacement, cascade, privileged administration or special-table editing. */
final class SqlServerDesigner {
    static String quote(String name){q(name);return "["+name.replace("]","]]")+"]";}
    static String collation(String name){if(!name.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))throw new IllegalArgumentException("Unsupported SQL Server collation name");return name;}
    static String target(JsonNode snapshot){return quote(str(snapshot,"schema"))+"."+quote(str(snapshot,"name"));}
    static String type(String input){String value=input.strip().toLowerCase(Locale.ROOT);if(!value.matches("(?:tinyint|smallint|int|integer|bigint|bit|real|float|money|smallmoney|date|datetime|smalldatetime|datetime2|datetimeoffset|time|uniqueidentifier|nvarchar|varchar|nchar|char|varbinary|binary|decimal|numeric)(?:\\((?:max|[0-9]{1,4})(?:\\s*,\\s*[0-9]{1,2})?\\))?"))throw new IllegalArgumentException("Unsupported SQL Server datatype; use a verified built-in scalar type and optional length/precision/scale");return value;}
    static void capabilities(ObjectNode out){out.putArray("editableCategories").add("Constraints").add("Foreign Keys").add("Indexes");out.putArray("unavailableColumnSettings").add("generated");out.put("reason","SQL Server ordinary disk tables: reviewed column/key/constraint/index changes. Temporal, memory-optimized, graph, partitioned, encrypted, sparse, CLR/user-defined and other special tables remain read-only.");}
    static ObjectNode load(QueryJobs.Job job,Connection c,ObjectNode out)throws Exception{
        String schema=str(out,"schema"),name=str(out,"name"),target=target(out);
        var tables=query(job,c,"SELECT t.object_id AS id,t.temporal_type,t.is_memory_optimized,t.is_filetable,t.is_node,t.is_edge,t.ledger_type,t.is_remote_data_archive_enabled,t.is_replicated,t.is_merge_published,t.is_tracked_by_cdc,COALESCE(CONVERT(nvarchar(4000),p.value),'') AS comment FROM sys.tables t LEFT JOIN sys.extended_properties p ON p.major_id=t.object_id AND p.minor_id=0 AND p.name='MS_Description' AND p.class=1 WHERE t.object_id=OBJECT_ID(?)",target);
        if(tables.size()!=1)throw new IllegalArgumentException("SQL Server table is unavailable or metadata permission is missing");JsonNode table=tables.get(0);out.put("objectIdentity",str(table,"id"));
        out.put("editable",List.of("temporal_type","is_memory_optimized","is_filetable","is_node","is_edge","ledger_type","is_remote_data_archive_enabled","is_replicated","is_merge_published","is_tracked_by_cdc").stream().allMatch(key->Set.of("0","false").contains(str(table,key))));capabilities(out);
        out.putObject("fields").put("name",name).put("schema",schema).put("owner","").put("comment",str(table,"comment")).put("tablespace","");
        var columns=query(job,c,"SELECT c.column_id AS id,c.name,ty.name AS type,c.max_length,c.precision,c.scale,c.is_nullable AS nullable,c.is_identity AS [identity],c.is_computed AS computed,c.is_sparse,c.is_filestream,c.encryption_type,ty.is_user_defined,c.collation_name AS collation,COALESCE(dc.definition,'') AS [default],COALESCE(dc.name,'') AS default_name,COALESCE(cc.definition,'') AS generated,COALESCE(CONVERT(nvarchar(4000),ep.value),'') AS comment FROM sys.columns c JOIN sys.types ty ON ty.user_type_id=c.user_type_id LEFT JOIN sys.default_constraints dc ON dc.object_id=c.default_object_id LEFT JOIN sys.computed_columns cc ON cc.object_id=c.object_id AND cc.column_id=c.column_id LEFT JOIN sys.extended_properties ep ON ep.major_id=c.object_id AND ep.minor_id=c.column_id AND ep.class=1 AND ep.name='MS_Description' WHERE c.object_id=OBJECT_ID(?) ORDER BY c.column_id",target);
        if(columns.size()>MAX_COLUMNS)throw new IllegalArgumentException("Table exceeds 256 columns");
        var keys=query(job,c,"SELECT kc.name,kc.type AS kind,ic.column_id,ic.key_ordinal FROM sys.key_constraints kc JOIN sys.index_columns ic ON ic.object_id=kc.parent_object_id AND ic.index_id=kc.unique_index_id WHERE kc.parent_object_id=OBJECT_ID(?) ORDER BY kc.object_id,ic.key_ordinal",target);
        out.put("primaryKeyName","");
        for(JsonNode item:columns){ObjectNode col=(ObjectNode)item;String type=str(col,"type");int length=col.path("max_length").asInt(),precision=col.path("precision").asInt(),scale=col.path("scale").asInt();
            if(Set.of("nvarchar","nchar","varchar","char","varbinary","binary").contains(type))type+="("+(length==-1?"max":String.valueOf(type.startsWith("n")?length/2:length))+")";
            else if(Set.of("decimal","numeric").contains(type))type+="("+precision+","+scale+")";else if(Set.of("datetime2","datetimeoffset","time").contains(type))type+="("+scale+")";
            col.put("type",type).put("nullable",str(col,"nullable").equals("1")).put("identity",str(col,"identity").equals("1")?"d":"").put("pk",0);
            for(JsonNode key:keys)if(str(key,"kind").equals("PK")&&str(key,"column_id").equals(str(col,"id"))){col.put("pk",key.path("key_ordinal").asInt());out.put("primaryKeyName",str(key,"name"));}
            if(!Set.of("0","false").contains(str(col,"is_sparse"))||!Set.of("0","false").contains(str(col,"is_filestream"))||!Set.of("0","false").contains(str(col,"is_user_defined"))||!str(col,"encryption_type").isEmpty())out.put("editable",false);
        }out.set("columns",columns);
        out.set("constraints",query(job,c,"SELECT object_id AS id,name,type AS kind,COALESCE(OBJECT_DEFINITION(object_id),'') AS definition FROM sys.objects WHERE parent_object_id=OBJECT_ID(?) AND type IN ('PK','UQ','F','C') ORDER BY object_id",target));
        out.set("indexes",query(job,c,"SELECT index_id AS id,name,type,is_primary_key,is_unique_constraint,CASE WHEN is_primary_key=1 OR is_unique_constraint=1 THEN 'true' ELSE 'false' END AS constrained,COALESCE(filter_definition,'') AS definition,is_disabled FROM sys.indexes WHERE object_id=OBJECT_ID(?) AND index_id>0 ORDER BY index_id",target));
        if(!query(job,c,"SELECT 1 AS partitioned FROM sys.indexes i JOIN sys.data_spaces ds ON ds.data_space_id=i.data_space_id WHERE i.object_id=OBJECT_ID(?) AND (ds.type='PS' OR i.type NOT IN (0,1,2))",target).isEmpty())out.put("editable",false);
        out.set("dependencies",query(job,c,"SELECT referencing_id,referenced_id,referenced_schema_name,referenced_entity_name FROM sys.sql_expression_dependencies WHERE referencing_id=OBJECT_ID(?) OR referenced_id=OBJECT_ID(?) ORDER BY referencing_id,referenced_id",target,target));
        var fingerprint=out.deepCopy();fingerprint.remove(List.of("selection","sql","categories","reason"));out.put("fingerprint",hash(fingerprint));if(out.toString().length()>512*1024)throw new IllegalArgumentException("SQL Server metadata exceeds designer allowance");return out;
    }
    static ObjectNode prepare(ObjectNode current,JsonNode request)throws Exception{
        JsonNode draft=request.path("draft"),fields=draft.path("fields");String target=target(current);
        for(String field:List.of("schema","owner","tablespace"))if(!str(fields,field).equals(str(current.path("fields"),field)))throw new IllegalArgumentException(field+" changes are not enabled in the SQL Server designer");
        quote(str(fields,"name"));ArrayNode commands=Profiles.JSON.createArrayNode();Map<String,JsonNode> original=new LinkedHashMap<>(),desired=new LinkedHashMap<>();current.path("columns").forEach(col->original.put(str(col,"id"),col));Set<String> names=new HashSet<>();Set<Integer> ranks=new HashSet<>();
        if(draft.path("columns").size()>MAX_COLUMNS)throw new IllegalArgumentException("At most 256 columns");
        for(JsonNode col:draft.path("columns")){String id=str(col,"id"),name=str(col,"name");if(desired.put(id,col)!=null||!original.containsKey(id)&&!id.startsWith("new:"))throw new IllegalArgumentException("Invalid stable column identity");
            if(col.path("deleted").asBoolean())continue;quote(name);if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Duplicate column name");
            int pk=col.path("pk").asInt();if(!col.path("pk").isIntegralNumber()||pk<0||pk>32||pk>0&&!ranks.add(pk))throw new IllegalArgumentException("Primary key positions must be distinct integers 1..32");if(pk>0&&col.path("nullable").asBoolean())throw new IllegalArgumentException("Primary-key columns must be NOT NULL");
        }
        if(names.isEmpty()||!desired.keySet().containsAll(original.keySet()))throw new IllegalArgumentException("Keep at least one column and explicitly mark removed columns");
        List<JsonNode> oldPk=pk(current.path("columns")),newPk=pk(draft.path("columns"));boolean pkChanged=!oldPk.stream().map(c->str(c,"id")).toList().equals(newPk.stream().map(c->str(c,"id")).toList());
        if(pkChanged&&!str(current,"primaryKeyName").isBlank())add(commands,"ALTER TABLE "+target+" DROP CONSTRAINT "+quote(str(current,"primaryKeyName")),true);
        for(JsonNode col:desired.values()){
            JsonNode old=original.get(str(col,"id"));String name=quote(str(col,"name"));boolean deleted=col.path("deleted").asBoolean();if(old==null&&deleted)continue;
            String definition=expression(str(col,"default"));
            if(old==null){
                if(!str(col,"generated").isEmpty())throw new IllegalArgumentException("New computed columns require a dedicated SQL Server adapter");
                String identity=str(col,"identity");if(!identity.isEmpty()&&(!identity.equals("d")||!definition.isEmpty()||col.path("nullable").asBoolean()))throw new IllegalArgumentException("SQL Server identity requires By default, NOT NULL, and no default expression");
                add(commands,"ALTER TABLE "+target+" ADD "+name+" "+type(str(col,"type"))+(identity.isEmpty()?"":" IDENTITY(1,1)")+(col.path("nullable").asBoolean()?" NULL":" NOT NULL")+(definition.isEmpty()?"":" DEFAULT "+definition),!definition.isEmpty()||!col.path("nullable").asBoolean());
            }else{
                String oldName=quote(str(old,"name"));boolean changedType=!str(old,"type").equalsIgnoreCase(str(col,"type")),changedDefault=!str(old,"default").equals(str(col,"default"));
                if(!str(old,"identity").equals(str(col,"identity"))||!str(old,"generated").equals(str(col,"generated")))throw new IllegalArgumentException("Existing identity/computed settings are immutable");
                if((changedType||changedDefault||old.path("nullable").asBoolean()!=col.path("nullable").asBoolean())&&(!str(old,"identity").isEmpty()||!str(old,"generated").isEmpty()))throw new IllegalArgumentException("Identity/computed columns require dedicated alteration review");
                if((deleted||changedType||changedDefault)&&!str(old,"default_name").isBlank())add(commands,"ALTER TABLE "+target+" DROP CONSTRAINT "+quote(str(old,"default_name")),false);
                if(deleted){add(commands,"ALTER TABLE "+target+" DROP COLUMN "+oldName,true);continue;}
                if(!str(old,"name").equals(str(col,"name")))add(commands,rename(target+"."+oldName,str(col,"name"),"COLUMN"),false);
                if(changedType||old.path("nullable").asBoolean()!=col.path("nullable").asBoolean())add(commands,"ALTER TABLE "+target+" ALTER COLUMN "+name+" "+type(str(col,"type"))+(!str(old,"collation").isEmpty()&&type(str(col,"type")).matches("(?i)(n?varchar|n?char).*" )?" COLLATE "+collation(str(old,"collation")):"")+(col.path("nullable").asBoolean()?" NULL":" NOT NULL"),true);
                if((changedDefault||changedType)&&!definition.isEmpty())add(commands,"ALTER TABLE "+target+" ADD DEFAULT "+definition+" FOR "+name,false);
            }
            if(!str(col,"comment").equals(old==null?"":str(old,"comment")))comment(commands,current,str(col,"name"),old==null?"":str(old,"comment"),str(col,"comment"));
        }
        if(pkChanged&&!newPk.isEmpty())add(commands,"ALTER TABLE "+target+" ADD PRIMARY KEY ("+String.join(", ",newPk.stream().map(c->quote(str(c,"name"))).toList())+")",true);
        if(!draft.path("objects").isMissingNode()&&!draft.path("objects").isArray()||draft.path("objects").size()>128)throw new IllegalArgumentException("Invalid category changes");
        for(JsonNode change:draft.path("objects"))objectChange(current,change,commands,names);
        if(!str(fields,"comment").equals(str(current.path("fields"),"comment")))comment(commands,current,"",str(current.path("fields"),"comment"),str(fields,"comment"));
        if(!str(fields,"name").equals(str(current,"name")))add(commands,rename(target,str(fields,"name"),"OBJECT"),false);
        if(commands.isEmpty())throw new IllegalArgumentException("There are no changes to save");
        ObjectNode plan=Profiles.JSON.createObjectNode().put("atomic",true).put("risky",java.util.stream.StreamSupport.stream(commands.spliterator(),false).anyMatch(c->c.path("risky").asBoolean())).put("expiresAt",System.currentTimeMillis()+300000);plan.set("snapshot",current);plan.set("draft",draft.deepCopy());plan.set("commands",commands);return plan;
    }
    private static List<JsonNode> pk(JsonNode columns){var rows=new ArrayList<JsonNode>();columns.forEach(c->{if(!c.path("deleted").asBoolean()&&c.path("pk").asInt()>0)rows.add(c);});rows.sort(Comparator.comparingInt(c->c.path("pk").asInt()));return rows;}
    private static String rename(String name,String next,String kind){return "EXEC sys.sp_rename N"+literal(name)+", N"+literal(next)+", N"+literal(kind);}
    private static void comment(ArrayNode commands,JsonNode current,String column,String before,String after){
        if(after.length()>3500)throw new IllegalArgumentException("SQL Server comments are limited to 3500 characters");
        String proc=after.isEmpty()?"sp_dropextendedproperty":before.isEmpty()?"sp_addextendedproperty":"sp_updateextendedproperty";
        add(commands,"EXEC sys."+proc+" @name=N'MS_Description'"+(after.isEmpty()?"":", @value=N"+literal(after))+", @level0type=N'SCHEMA', @level0name=N"+literal(str(current,"schema"))+", @level1type=N'TABLE', @level1name=N"+literal(str(current,"name"))+(column.isEmpty()?"":", @level2type=N'COLUMN', @level2name=N"+literal(column)),false);
    }
    private static String columns(JsonNode list,Set<String> available){if(!list.isArray()||list.isEmpty()||list.size()>32)throw new IllegalArgumentException("Select 1..32 columns");var names=new ArrayList<String>();for(JsonNode value:list){if(available!=null&&!available.contains(value.asText().toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Unknown draft column");names.add(quote(value.asText()));}return String.join(", ",names);}
    private static void objectChange(ObjectNode current,JsonNode change,ArrayNode commands,Set<String> names)throws Exception{
        String category=str(change,"category"),action=str(change,"action"),target=target(current),name=str(change,"name");
        if(Set.of("Constraints","Foreign Keys").contains(category)){
            if(action.equals("delete")){var old=find(current.path("constraints"),str(change,"id"));if(str(old,"kind").equals("PK"))throw new IllegalArgumentException("Edit primary key through Columns");add(commands,"ALTER TABLE "+target+" DROP CONSTRAINT "+quote(str(old,"name")),true);return;}
            if(action.equals("add")){String definition=switch(str(change,"kind")){case "CHECK"->"CHECK ("+expression(str(change,"expression"))+")";case "UNIQUE"->"UNIQUE ("+columns(change.path("columns"),names)+")";case "FOREIGN KEY"->{if(change.path("columns").size()!=change.path("references").size())throw new IllegalArgumentException("Foreign key column counts differ");yield "FOREIGN KEY ("+columns(change.path("columns"),names)+") REFERENCES "+quote(str(change,"schema"))+"."+quote(str(change,"table"))+" ("+columns(change.path("references"),null)+")";}default->throw new IllegalArgumentException("Unsupported constraint");};add(commands,"ALTER TABLE "+target+" ADD CONSTRAINT "+quote(name)+" "+definition,true);return;}
        }
        if(category.equals("Indexes")){
            if(action.equals("add")){add(commands,"CREATE "+(change.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+quote(name)+" ON "+target+" ("+columns(change.path("columns"),names)+")",true);return;}
            var old=find(current.path("indexes"),str(change,"id"));if(str(old,"constrained").equals("true"))throw new IllegalArgumentException("Constraint-owned indexes are edited through constraints");
            if(action.equals("delete")){add(commands,"DROP INDEX "+quote(str(old,"name"))+" ON "+target,true);return;}
            if(action.equals("rename")){add(commands,rename(target+"."+quote(str(old,"name")),name,"INDEX"),false);return;}
        }
        throw new IllegalArgumentException("SQL Server category/action is not supported by this adapter");
    }
    private SqlServerDesigner(){}
}
