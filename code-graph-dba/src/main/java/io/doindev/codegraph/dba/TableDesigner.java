package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Browser-only schema drafts. No client-supplied statement is accepted by Apply. */
final class TableDesigner {
    static final int MAX_COLUMNS=256,MAX_OPERATIONS=128;
    static String q(String s){if(s==null||s.isBlank()||s.length()>128||s.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid identifier (maximum 128 characters)");return "\""+s.replace("\"","\"\"")+"\"";}
    static String str(JsonNode n,String key){return n.path(key).asText("");}
    static String literal(String s){return "'"+s.replace("'","''")+"'";}
    static String hash(JsonNode n)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Profiles.JSON.writeValueAsBytes(n)));}
    static String target(JsonNode snapshot){if(str(snapshot,"engine").equals("sqlserver"))return SqlServerDesigner.target(snapshot);return q(str(snapshot,"schema"))+"."+q(str(snapshot,"name"));}
    static ArrayNode query(QueryJobs.Job job,Connection c,String sql,Object... params)throws Exception{
        ArrayNode out=Profiles.JSON.createArrayNode();
        try(var st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setMaxRows(1001);for(int i=0;i<params.length;i++)st.setObject(i+1,params[i]);
            try(var rs=st.executeQuery()){var meta=rs.getMetaData();while(rs.next()){if(out.size()>=1000)throw new IllegalArgumentException("Table metadata exceeds the 1,000-object designer limit");ObjectNode row=out.addObject();for(int i=1;i<=meta.getColumnCount();i++){String value=rs.getString(i);if(value!=null&&value.length()>8192)throw new IllegalArgumentException("An object definition exceeds the designer's 8 KiB field limit");row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT),value);}}}
        }finally{job.statement=null;}return out;
    }
    static ObjectNode load(QueryJobs.Job job,Connection c,ObjectNode selection,boolean generic)throws Exception{
        if(!selection.path("parent").path("kind").asText().equals("tables"))throw new IllegalArgumentException("View properties are read-only; use the query builder to edit a query without altering the saved view");
        return loadIdentity(job,c,selection,generic,TableQueries.prepare(job,c,selection,job.remainingSeconds()));
    }
    /** Called only with a node read by the comparer in this same transaction. */
    static ObjectNode loadCatalogObject(QueryJobs.Job job,Connection c,ObjectNode selection,JsonNode node)throws Exception{
        String schema=node.path("schema").asText(),name=node.path("objectName").asText(node.path("name").asText());
        ObjectNode identity=Profiles.JSON.createObjectNode().put("database",Objects.toString(c.getCatalog(),"")).put("schema",schema).put("name",name).put("kind","tables");
        return loadIdentity(job,c,selection,false,identity);
    }
    private static ObjectNode loadIdentity(QueryJobs.Job job,Connection c,ObjectNode selection,boolean generic,ObjectNode identity)throws Exception{
        String schema=str(identity,"schema"),name=str(identity,"name"),product=c.getMetaData().getDatabaseProductName(),engine=generic?"jdbc":product.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(product);
        ObjectNode out=identity.deepCopy();out.put("engine",engine);out.set("selection",selection.deepCopy());out.put("editable",Set.of("postgresql","h2").contains(engine));out.put("reason","Editing is enabled only for validated PostgreSQL/H2 operations. Other drivers and unsupported fields remain read-only.");
        ArrayNode categories=out.putArray("categories");TableMetadata.categories(engine,c.getMetaData().getDatabaseMajorVersion()).forEach(categories::add);categories.add("Statistics").add("Permissions").add("DDL").add("Virtual");
        if(engine.equals("sqlserver")&&c.getMetaData().getDatabaseMajorVersion()>=16)return SqlServerDesigner.load(job,c,out);
        ObjectNode fields=out.putObject("fields");fields.put("name",name).put("schema",schema).put("owner","").put("comment","").put("tablespace","");
        ArrayNode columns=out.putArray("columns");String pkName="";Map<String,Integer> pk=new HashMap<>();
        try(var rs=c.getMetaData().getPrimaryKeys(c.getCatalog(),schema,name)){while(rs.next()){pk.put(rs.getString("COLUMN_NAME"),rs.getInt("KEY_SEQ"));pkName=Objects.toString(rs.getString("PK_NAME"),"");}}
        out.put("primaryKeyName",pkName);
        if(engine.equals("postgresql")){
            var table=query(job,c,"SELECT c.oid::text AS identity,c.relkind::text AS kind,pg_get_userbyid(c.relowner) AS owner,COALESCE(obj_description(c.oid,'pg_class'),'') AS comment,COALESCE(t.spcname,'') AS tablespace FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace LEFT JOIN pg_tablespace t ON t.oid=c.reltablespace WHERE n.nspname=? AND c.relname=?",schema,name);
            if(table.size()!=1)throw new IllegalArgumentException("Table no longer exists");out.put("objectIdentity",table.get(0).path("identity").asText());for(String key:List.of("owner","comment","tablespace"))fields.put(key,str(table.get(0),key));
            if(!str(table.get(0),"kind").equals("r"))out.put("editable",false).put("reason","Partitioned, inherited and foreign tables require a dedicated alteration adapter.");
            if(!query(job,c,"SELECT 1 AS inherited FROM pg_inherits WHERE inhrelid=?::oid OR inhparent=?::oid",str(out,"objectIdentity"),str(out,"objectIdentity")).isEmpty())out.put("editable",false).put("reason","Inherited/partition tables are read-only in the designer.");
            for(JsonNode col:query(job,c,"SELECT a.attnum::text AS id,a.attname AS name,format_type(a.atttypid,a.atttypmod) AS type,NOT a.attnotnull AS nullable,COALESCE(pg_get_expr(d.adbin,d.adrelid),'') AS default,COALESCE(col_description(a.attrelid,a.attnum),'') AS comment,a.attidentity::text AS identity,a.attgenerated::text AS generated FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum WHERE a.attrelid=?::oid AND a.attnum>0 AND NOT a.attisdropped ORDER BY a.attnum",str(out,"objectIdentity"))){ObjectNode row=((ObjectNode)col).deepCopy();row.put("nullable",str(col,"nullable").equals("t")||str(col,"nullable").equals("true"));row.put("pk",pk.getOrDefault(str(col,"name"),0));columns.add(row);}
            out.set("constraints",query(job,c,"SELECT oid::text AS id,conname AS name,contype::text AS kind,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid=?::oid ORDER BY oid",str(out,"objectIdentity")));
            out.set("indexes",query(job,c,"SELECT i.indexrelid::text AS id,ci.relname AS name,pg_get_indexdef(i.indexrelid) AS definition,EXISTS(SELECT 1 FROM pg_constraint k WHERE k.conindid=i.indexrelid) AS constrained FROM pg_index i JOIN pg_class ci ON ci.oid=i.indexrelid WHERE i.indrelid=?::oid ORDER BY i.indexrelid",str(out,"objectIdentity")));
            out.set("triggers",query(job,c,"SELECT oid::text AS id,tgname AS name,pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE tgrelid=?::oid AND NOT tgisinternal ORDER BY oid",str(out,"objectIdentity")));
            out.set("policies",query(job,c,"SELECT oid::text AS id,polname AS name,polcmd::text AS command,COALESCE(pg_get_expr(polqual,polrelid),'') AS using,COALESCE(pg_get_expr(polwithcheck,polrelid),'') AS check FROM pg_policy WHERE polrelid=?::oid ORDER BY oid",str(out,"objectIdentity")));
            out.set("rules",query(job,c,"SELECT oid::text AS id,rulename AS name,pg_get_ruledef(oid) AS definition FROM pg_rewrite WHERE ev_class=?::oid ORDER BY oid",str(out,"objectIdentity")));
            out.set("acl",query(job,c,"SELECT COALESCE(relacl::text,'') AS acl,relrowsecurity::text AS rowsecurity,relforcerowsecurity::text AS forced FROM pg_class WHERE oid=?::oid",str(out,"objectIdentity")));
        }else{
            String escape=c.getMetaData().getSearchStringEscape();String schemaPattern=schema.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%"),namePattern=name.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");
            try(var rs=c.getMetaData().getColumns(c.getCatalog(),schemaPattern,namePattern,null)){while(rs.next()){if(columns.size()>=MAX_COLUMNS)throw new IllegalArgumentException("Table exceeds the 256-column editing limit");String type=rs.getString("TYPE_NAME");int size=rs.getInt("COLUMN_SIZE"),scale=rs.getInt("DECIMAL_DIGITS");if(Set.of("CHARACTER VARYING","VARCHAR","CHARACTER","CHAR","BINARY VARYING","VARBINARY").contains(type)&&size>0)type+="("+size+")";else if(Set.of("NUMERIC","DECIMAL").contains(type)&&size>0)type+="("+size+","+scale+")";String col=rs.getString("COLUMN_NAME");columns.addObject().put("id",Integer.toString(rs.getInt("ORDINAL_POSITION"))).put("name",col).put("type",type).put("nullable",rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls).put("pk",pk.getOrDefault(col,0)).put("default",Objects.toString(rs.getString("COLUMN_DEF"),"")).put("comment",Objects.toString(rs.getString("REMARKS"),"")).put("identity","YES".equals(rs.getString("IS_AUTOINCREMENT"))?"d":"").put("generated","YES".equals(rs.getString("IS_GENERATEDCOLUMN"))?"s":"");}}
            if(engine.equals("h2")){
                var table=query(job,c,"SELECT REMARKS AS comment FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",schema,name);if(!table.isEmpty())fields.put("comment",str(table.get(0),"comment"));
                out.set("constraints",query(job,c,"SELECT CONSTRAINT_NAME AS id,CONSTRAINT_NAME AS name,CONSTRAINT_TYPE AS kind FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY CONSTRAINT_NAME",schema,name));
            }
        }
        if(columns.size()>MAX_COLUMNS)throw new IllegalArgumentException("Table exceeds the 256-column editing limit");
        ObjectNode fingerprint=out.deepCopy();fingerprint.remove(List.of("selection","sql","categories","reason"));out.put("fingerprint",hash(fingerprint));
        if(Profiles.JSON.writeValueAsBytes(out).length>1<<20)throw new IllegalArgumentException("Table properties exceed the 1 MiB designer allowance");return out;
    }
    static String type(String input){String value=input.trim();if(!value.matches("(?i)(smallint|integer|int|bigint|boolean|real|double precision|date|time(?:stamp)?(?: with(?:out)? time zone)?|text|bytea|uuid|jsonb?|character varying|varchar|character|char|numeric|decimal|binary varying|varbinary)(?:\\(\\d{1,9}(?:\\s*,\\s*\\d{1,4})?\\))?"))throw new IllegalArgumentException("Unsupported datatype in designer: use a standard type, including optional length/precision/scale");return value;}
    static String expression(String input)throws Exception{if(input.isBlank())return "";if(input.length()>8192)throw new IllegalArgumentException("Expression exceeds 8 KiB");var exp=net.sf.jsqlparser.parser.CCJSqlParserUtil.parseExpression(input,false);if(exp==null||!SqlScript.extract("SELECT "+input).getFirst().sql().equals("SELECT "+input))throw new IllegalArgumentException("Expected one SQL expression");return exp.toString();}
    static void add(ArrayNode commands,String sql,boolean risky){if(commands.size()>=MAX_OPERATIONS)throw new IllegalArgumentException("At most 128 schema operations can be saved together");commands.addObject().put("sql",sql).put("risky",risky);}
    static ObjectNode prepare(ObjectNode current,JsonNode request)throws Exception{
        if(!current.path("editable").asBoolean())throw new IllegalArgumentException(str(current,"reason"));
        if(!str(current,"fingerprint").equals(str(request,"fingerprint")))throw new IllegalArgumentException("Table changed since it was loaded. Refresh and reapply your draft.");
        JsonNode draft=request.path("draft"),fields=draft.path("fields");if(!fields.isObject()||!draft.path("columns").isArray())throw new IllegalArgumentException("Table fields and columns are required");
        if(str(current,"engine").equals("sqlserver"))return SqlServerDesigner.prepare(current,request);
        ObjectNode plan=Profiles.JSON.createObjectNode();plan.set("snapshot",current);plan.set("draft",draft.deepCopy());ArrayNode commands=plan.putArray("commands");String engine=str(current,"engine"),target=target(current);boolean pg=engine.equals("postgresql");
        if(pg){for(String key:List.of("name","schema","owner","tablespace"))pgIdentifier(str(fields,key));for(JsonNode col:draft.path("columns"))pgIdentifier(str(col,"name"));for(JsonNode object:draft.path("objects")){for(String key:List.of("name","role","schema","table","functionSchema","functionName"))pgIdentifier(str(object,key));for(String key:List.of("columns","references"))for(JsonNode value:object.path(key))pgIdentifier(value.asText());}}
        var original=new LinkedHashMap<String,JsonNode>();current.path("columns").forEach(c->original.put(str(c,"id"),c));var desired=new LinkedHashMap<String,JsonNode>();Set<String> names=new HashSet<>();
        if(draft.path("columns").size()>MAX_COLUMNS)throw new IllegalArgumentException("At most 256 columns are supported");
        for(JsonNode col:draft.path("columns")){String id=str(col,"id");if(desired.put(id,col)!=null)throw new IllegalArgumentException("Duplicate column identity");if(!original.containsKey(id)&&!id.startsWith("new:"))throw new IllegalArgumentException("Unknown column identity");if(!col.path("deleted").asBoolean()){q(str(col,"name"));if(!names.add(str(col,"name")))throw new IllegalArgumentException("Duplicate column name");} }
        if(!desired.keySet().containsAll(original.keySet()))throw new IllegalArgumentException("Existing columns must be explicitly marked for deletion");if(names.isEmpty())throw new IllegalArgumentException("Keep at least one column");
        List<JsonNode> beforePk=new ArrayList<>(),afterPk=new ArrayList<>();current.path("columns").forEach(col->{if(col.path("pk").asInt()>0)beforePk.add(col);});desired.values().forEach(col->{if(!col.path("deleted").asBoolean()&&col.path("pk").asInt()>0)afterPk.add(col);});var rank=Comparator.comparingInt((JsonNode col)->col.path("pk").asInt());beforePk.sort(rank);afterPk.sort(rank);
        boolean pkChanged=!beforePk.stream().map(c->str(c,"id")).toList().equals(afterPk.stream().map(c->str(c,"id")).toList());
        if(pkChanged&&!str(current,"primaryKeyName").isEmpty())add(commands,"ALTER TABLE "+target+" DROP CONSTRAINT "+q(str(current,"primaryKeyName")),true);
        for(JsonNode col:desired.values()){
            JsonNode old=original.get(str(col,"id"));String name=q(str(col,"name"));boolean deleted=col.path("deleted").asBoolean();
            if(old==null){if(deleted)continue;String def=expression(str(col,"default")),identity=str(col,"identity"),generated=str(col,"generated");String extra="";if(!identity.isEmpty()){if(!Set.of("a","d").contains(identity)||!def.isEmpty()||!generated.isEmpty())throw new IllegalArgumentException("Identity, generated expression and default cannot be combined");extra=" GENERATED "+(identity.equals("a")?"ALWAYS":"BY DEFAULT")+" AS IDENTITY";}else if(!generated.isEmpty()){extra=" GENERATED ALWAYS AS ("+expression(generated)+")"+(pg?" STORED":"");}else if(!def.isEmpty())extra=" DEFAULT "+def;
                add(commands,"ALTER TABLE "+target+" ADD COLUMN "+name+" "+type(str(col,"type"))+extra+(col.path("nullable").asBoolean()?"":" NOT NULL"),!extra.isEmpty()||!col.path("nullable").asBoolean());
            }else{
                String oldName=q(str(old,"name"));if(deleted){add(commands,"ALTER TABLE "+target+" DROP COLUMN "+oldName+(pg?" RESTRICT":""),true);continue;}
                if(!str(old,"identity").equals(str(col,"identity"))||!str(old,"generated").equals(str(col,"generated")))throw new IllegalArgumentException("Existing identity/generated column settings cannot be changed by this adapter");
                if(!str(old,"name").equals(str(col,"name")))add(commands,"ALTER TABLE "+target+" RENAME COLUMN "+oldName+" TO "+name,false);
                if(!str(old,"type").equalsIgnoreCase(str(col,"type")))add(commands,"ALTER TABLE "+target+" ALTER COLUMN "+name+(pg?" TYPE ":" SET DATA TYPE ")+type(str(col,"type")),true);
                if(old.path("nullable").asBoolean()!=col.path("nullable").asBoolean())add(commands,"ALTER TABLE "+target+" ALTER COLUMN "+name+(col.path("nullable").asBoolean()?" DROP NOT NULL":" SET NOT NULL"),!col.path("nullable").asBoolean());
                if(!str(old,"default").equals(str(col,"default"))){if(!str(old,"identity").isEmpty()||!str(old,"generated").isEmpty())throw new IllegalArgumentException("Identity/generated defaults are managed by the database");String def=expression(str(col,"default"));add(commands,"ALTER TABLE "+target+" ALTER COLUMN "+name+(def.isEmpty()?" DROP DEFAULT":" SET DEFAULT "+def),false);}
            }
            if(old==null&&!str(col,"comment").isEmpty()||old!=null&&!str(old,"comment").equals(str(col,"comment")))add(commands,"COMMENT ON COLUMN "+target+"."+name+" IS "+literal(str(col,"comment")),false);
        }
        if(pkChanged&&!afterPk.isEmpty()){Set<Integer> ranks=new HashSet<>();for(JsonNode col:afterPk)if(!ranks.add(col.path("pk").asInt()))throw new IllegalArgumentException("Composite primary-key positions must be distinct");add(commands,"ALTER TABLE "+target+" ADD PRIMARY KEY ("+String.join(", ",afterPk.stream().map(c->q(str(c,"name"))).toList())+")",true);}
        List<JsonNode> objects=new ArrayList<>();draft.path("objects").forEach(objects::add);objects.sort(Comparator.comparingInt(x->str(x,"action").equals("delete")?0:1));for(JsonNode change:objects)objectChange(current,change,commands,target,pg);
        if(!str(current.path("fields"),"comment").equals(str(fields,"comment")))add(commands,"COMMENT ON TABLE "+target+" IS "+literal(str(fields,"comment")),false);
        for(String field:List.of("owner","tablespace"))if(!str(current.path("fields"),field).equals(str(fields,field))){if(!pg)throw new IllegalArgumentException(field+" editing is unavailable for this engine");add(commands,"ALTER TABLE "+target+(field.equals("owner")?" OWNER TO ":" SET TABLESPACE ")+q(str(fields,field)),true);}
        if(!str(current,"name").equals(str(fields,"name"))){add(commands,"ALTER TABLE "+target+" RENAME TO "+q(str(fields,"name")),false);target=q(str(current,"schema"))+"."+q(str(fields,"name"));}
        if(!str(current,"schema").equals(str(fields,"schema"))){if(!pg)throw new IllegalArgumentException("Moving tables between schemas is not certified for this engine");add(commands,"ALTER TABLE "+target+" SET SCHEMA "+q(str(fields,"schema")),true);}
        if(commands.isEmpty())throw new IllegalArgumentException("There are no changes to save");
        plan.put("atomic",pg);plan.put("risky",!pg||java.util.stream.StreamSupport.stream(commands.spliterator(),false).anyMatch(x->x.path("risky").asBoolean()));plan.put("expiresAt",System.currentTimeMillis()+300_000);return plan;
    }
    static void objectChange(ObjectNode current,JsonNode change,ArrayNode commands,String target,boolean pg)throws Exception{
        String category=str(change,"category"),action=str(change,"action"),name=str(change,"name");
        if(!pg)throw new IllegalArgumentException("Category editing is not certified for this engine");
        if(category.equals("Statistics")&&action.equals("collect")){add(commands,"ANALYZE "+target,true);return;}
        if(category.equals("Permissions")){String privilege=str(change,"privilege"),role=q(str(change,"role"));if(!Set.of("SELECT","INSERT","UPDATE","DELETE","REFERENCES","TRIGGER").contains(privilege)||!Set.of("grant","revoke").contains(action))throw new IllegalArgumentException("Unsupported grant/revoke");add(commands,(action.equals("grant")?"GRANT ":"REVOKE ")+privilege+" ON TABLE "+target+(action.equals("grant")?" TO ":" FROM ")+role,true);return;}
        if(category.equals("Constraints")||category.equals("Foreign Keys")){
            if(action.equals("delete")){JsonNode existing=find(current.path("constraints"),str(change,"id"));if(Set.of("p","PRIMARY KEY").contains(str(existing,"kind")))throw new IllegalArgumentException("Edit primary keys through Columns");add(commands,"ALTER TABLE "+target+" DROP CONSTRAINT "+q(str(existing,"name"))+" RESTRICT",true);return;}
            if(action.equals("add")){String kind=str(change,"kind"),definition;
                if(kind.equals("CHECK"))definition="CHECK ("+expression(str(change,"expression"))+")";
                else if(kind.equals("UNIQUE"))definition="UNIQUE ("+columnList(change.path("columns"))+")";
                else if(kind.equals("FOREIGN KEY"))definition="FOREIGN KEY ("+columnList(change.path("columns"))+") REFERENCES "+q(str(change,"schema"))+"."+q(str(change,"table"))+" ("+columnList(change.path("references"))+")";
                else throw new IllegalArgumentException("Unsupported constraint type");add(commands,"ALTER TABLE "+target+" ADD CONSTRAINT "+q(name)+" "+definition,true);return;
            }
        }
        if(category.equals("Indexes")){
            if(action.equals("rename")){var existing=find(current.path("indexes"),str(change,"id"));add(commands,"ALTER INDEX "+q(str(current,"schema"))+"."+q(str(existing,"name"))+" RENAME TO "+q(name),false);return;}
            if(action.equals("delete")){JsonNode existing=find(current.path("indexes"),str(change,"id"));if(Set.of("true","t").contains(str(existing,"constrained")))throw new IllegalArgumentException("Constraint-owned indexes must be edited through their constraint");add(commands,"DROP INDEX "+q(str(current,"schema"))+"."+q(str(existing,"name"))+" RESTRICT",true);return;}
            if(action.equals("add")){add(commands,"CREATE "+(change.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+q(name)+" ON "+target+" ("+columnList(change.path("columns"))+")",true);return;}
        }
        if(category.equals("Policies")){
            if(action.equals("delete")){var existing=find(current.path("policies"),str(change,"id"));add(commands,"DROP POLICY "+q(str(existing,"name"))+" ON "+target,true);return;}
            if(action.equals("add")){String using=expression(str(change,"expression"));if(using.isEmpty())throw new IllegalArgumentException("A policy expression is required");add(commands,"CREATE POLICY "+q(name)+" ON "+target+" USING ("+using+")",true);return;}
            if(action.equals("edit")){var existing=find(current.path("policies"),str(change,"id"));String using=expression(str(change,"expression"));if(using.isEmpty())throw new IllegalArgumentException("A policy expression is required");add(commands,"ALTER POLICY "+q(str(existing,"name"))+" ON "+target+" USING ("+using+")",true);return;}
        }
        if(category.equals("Triggers")&&action.equals("add")){
            String timing=str(change,"timing"),event=str(change,"event"),level=str(change,"level");if(!Set.of("BEFORE","AFTER").contains(timing)||!Set.of("INSERT","UPDATE","DELETE").contains(event)||!Set.of("ROW","STATEMENT").contains(level))throw new IllegalArgumentException("Unsupported trigger timing/event/level");
            add(commands,"CREATE TRIGGER "+q(name)+" "+timing+" "+event+" ON "+target+" FOR EACH "+level+" EXECUTE FUNCTION "+q(str(change,"functionSchema"))+"."+q(str(change,"functionName"))+"()",true);return;
        }
        if(category.equals("Rules")){
            if(action.equals("delete")){var existing=find(current.path("rules"),str(change,"id"));add(commands,"DROP RULE "+q(str(existing,"name"))+" ON "+target+" RESTRICT",true);return;}
            if(action.equals("add")){String event=str(change,"event");if(!Set.of("INSERT","UPDATE","DELETE").contains(event))throw new IllegalArgumentException("Unsupported rule event");add(commands,"CREATE RULE "+q(name)+" AS ON "+event+" TO "+target+" DO INSTEAD NOTHING",true);return;}
        }
        if(category.equals("Triggers")&&Set.of("enable","disable","delete").contains(action)){var existing=find(current.path("triggers"),str(change,"id"));add(commands,action.equals("delete")?"DROP TRIGGER "+q(str(existing,"name"))+" ON "+target+" RESTRICT":"ALTER TABLE "+target+" "+action.toUpperCase(Locale.ROOT)+" TRIGGER "+q(str(existing,"name")),true);return;}
        throw new IllegalArgumentException("This category/action has no validated designer adapter");
    }
    static JsonNode find(JsonNode list,String id){for(JsonNode node:list)if(str(node,"id").equals(id))return node;throw new IllegalArgumentException("Object changed; refresh the table");}
    static void pgIdentifier(String value){if(value.getBytes(StandardCharsets.UTF_8).length>63)throw new IllegalArgumentException("PostgreSQL identifiers must not exceed 63 UTF-8 bytes; the designer never relies on silent truncation");}
    static String columnList(JsonNode list){if(!list.isArray()||list.isEmpty()||list.size()>32)throw new IllegalArgumentException("Select between 1 and 32 columns");List<String> result=new ArrayList<>();list.forEach(x->result.add(q(x.asText())));return String.join(", ",result);}
    static ObjectNode details(QueryJobs.Job job,Connection c,ObjectNode snapshot,String category)throws Exception{
        ObjectNode out=Profiles.JSON.createObjectNode();String schema=str(snapshot,"schema"),table=str(snapshot,"name"),engine=str(snapshot,"engine");
        if(category.equals("Permissions")){
            ArrayNode rows=out.putArray("rows");try(var rs=c.getMetaData().getTablePrivileges(c.getCatalog(),schema,table)){while(rs.next()){if(rows.size()>=1000)throw new IllegalArgumentException("Permission list exceeds 1,000 entries");rows.addObject().put("grantor",rs.getString("GRANTOR")).put("grantee",rs.getString("GRANTEE")).put("privilege",rs.getString("PRIVILEGE")).put("grantable",rs.getString("IS_GRANTABLE"));}}out.put("note","Visible explicit grants; inherited roles, ownership and policy effects may grant or restrict additional access.");return out;
        }
        if(category.equals("Statistics")&&engine.equals("postgresql")){out.set("rows",query(job,c,"SELECT n_live_tup::text AS estimated_rows,n_dead_tup::text AS estimated_dead_rows,last_analyze::text,last_autoanalyze::text,pg_total_relation_size(relid)::text AS total_bytes FROM pg_stat_user_tables WHERE schemaname=? AND relname=?",schema,table));return out;}
        if(category.equals("Statistics")){out.put("note","Row and collection-time statistics are not exposed by this adapter. No expensive COUNT(*) is run automatically.");return out;}
        throw new IllegalArgumentException("Unsupported detail category");
    }
    private TableDesigner(){}
}
