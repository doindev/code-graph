package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Complete, bounded inventories; discovery never evaluates defaults or consumes a sequence. */
final class CompareCatalog {
    static final int MAX_OBJECTS=10_000,MAX_BYTES=16<<20;
    static final Set<String> ENGINES=Set.of("postgresql","h2","mysql","mariadb","oracle");
    static String engine(Connection c)throws SQLException{return ExplainPlans.capability(c).path("engine").asText();}
    static boolean supported(Connection c,String engine)throws SQLException{
        int major=c.getMetaData().getDatabaseMajorVersion();return switch(engine){case "postgresql"->major>=12;case "h2"->major>=2;case "mysql"->major>=8;case "mariadb"->major>=10;case "oracle"->major>=19;default->false;};
    }
    record Target(String connectionId,String database,String schema,boolean allSchemas,String revision){
        ObjectNode json(){return Profiles.JSON.createObjectNode().put("connectionId",connectionId).put("database",database).put("schema",schema).put("allSchemas",allSchemas).put("revision",revision);}
    }
    static final class Inventory {
        final String engine,database,version;final SortedMap<String,ObjectNode> objects=new TreeMap<>();final SortedSet<String> schemas=new TreeSet<>();
        long bytes;final long maxBytes;boolean sequenceValues=true;
        Inventory(String engine,String database,String version){this(engine,database,version,MAX_BYTES);}
        Inventory(String engine,String database,String version,long maxBytes){this.engine=engine;this.database=database;this.version=version;this.maxBytes=maxBytes;}
        void add(ObjectNode object)throws Exception{
            if(objects.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Comparison exceeds 10,000 objects; select a smaller scope");
            bytes+=Profiles.JSON.writeValueAsBytes(object).length*2L+512;
            limit(bytes,maxBytes,"Comparison metadata");
            String key=key(str(object,"schema"),str(object,"kind"),str(object,"name"));
            object.put("id",TableDesigner.hash(Profiles.JSON.getNodeFactory().textNode(key)).substring(0,32));
            if(objects.put(key,object)!=null)throw new IllegalArgumentException("Ambiguous object identity: "+str(object,"name"));
        }
        String fingerprint()throws Exception{ObjectNode node=Profiles.JSON.createObjectNode();for(var entry:objects.entrySet())node.set(entry.getKey(),definition(entry.getValue()));return TableDesigner.hash(node);}
    }
    static String str(JsonNode node,String name){return node.path(name).asText("");}
    static String key(String schema,String kind,String name){return schema+"\u0000"+kind+"\u0000"+name;}
    static ObjectNode definition(ObjectNode object){
        ObjectNode n=object.deepCopy();if(n.has("oracleType"))n.remove(List.of("oracleXml","oracleBodyXml","oracleChanges","oracleSelectedChanges","supported","reason"));n.remove(List.of("id","oid","selection","state","stateModes","stateReason","base","identityBase","dependencies"));
        if(n.path("columns").isArray())for(JsonNode column:n.path("columns"))((ObjectNode)column).remove(List.of("id","identityBase"));
        return n;
    }
    static ObjectNode item(String schema,String kind,String name){
        ObjectNode n=Profiles.JSON.createObjectNode().put("schema",schema).put("kind",kind).put("name",name).put("supported",false).put("reason","No validated comparison adapter for this object type");
        n.putArray("dependencies");return n;
    }
    static boolean system(String schema){String s=schema.toLowerCase(Locale.ROOT);return s.startsWith("pg_")||Set.of("information_schema","sys","system","mysql","performance_schema","sysibm","syscat","sysstat").contains(s);}
    static boolean fatal(SQLException failure){
        for(SQLException current=failure;current!=null;current=current.getNextException()){
            if(current.getErrorCode()==1013||current instanceof SQLTimeoutException||current instanceof SQLRecoverableException||current instanceof SQLNonTransientConnectionException)return true;
            String state=current.getSQLState();if(state!=null&&state.length()>=2&&Set.of("08","28","40","53","57","58").contains(state.substring(0,2)))return true;
        }
        return false;
    }
    static void check(QueryJobs.Job job){if(job.cancelled||Thread.currentThread().isInterrupted())throw new CancellationException();job.remainingSeconds();}
    static ArrayNode pages(QueryJobs.Job job,Connection c,String kind,String database,String schema)throws Exception{
        ArrayNode nodes=Profiles.JSON.createArrayNode();int offset=0;
        do{check(job);ObjectNode request=Profiles.JSON.createObjectNode().put("kind",kind).put("database",database).put("schema",schema).put("offset",offset);
            ObjectNode page=MetadataTree.browse(job,c,request,job.remainingSeconds());
            for(JsonNode n:page.path("nodes")){if(nodes.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Catalog exceeds 10,000 objects; select a smaller scope");ObjectNode copy=n.deepCopy();copy.put("_offset",offset);nodes.add(copy);}
            int next=page.path("nextOffset").asInt(-1);if(next<0)break;if(next<=offset)throw new IllegalArgumentException("Invalid metadata pagination");offset=next;
        }while(true);return nodes;
    }
    record CatalogObject(ObjectNode object,ObjectNode selection,JsonNode node) {}
    static Inventory capture(QueryJobs.Job job,Connection c,Target target)throws Exception{return capture(job,c,target,Set.of(),true);}
    static Inventory capture(QueryJobs.Job job,Connection c,Target target,Set<String> selectedKinds,boolean sequenceValues)throws Exception{
        return capture(job,c,target,selectedKinds,sequenceValues,Set.of(),target.allSchemas()?null:target.schema());
    }
    static Inventory capture(QueryJobs.Job job,Connection c,Target target,Set<String> selectedKinds,boolean sequenceValues,Set<String> selectedIds,String identitySchema)throws Exception{
        return capture(job,c,target,selectedKinds,sequenceValues,selectedIds,identitySchema,true);
    }
    static Inventory capture(QueryJobs.Job job,Connection c,Target target,Set<String> selectedKinds,boolean sequenceValues,Set<String> selectedIds,String identitySchema,boolean definitions)throws Exception{
        java.util.function.Predicate<CatalogObject> selected=entry->{
            if(!selectedKinds.isEmpty()&&!selectedKinds.contains(str(entry.object(),"kind")))return false;
            if(selectedIds.isEmpty())return true;
            try{return selectedIds.contains(TableDesigner.hash(Profiles.JSON.getNodeFactory().textNode(key(identitySchema==null?str(entry.object(),"schema"):identitySchema,str(entry.object(),"kind"),str(entry.object(),"name")))).substring(0,32));}
            catch(Exception failure){throw new IllegalStateException(failure);}
        };
        String engine=engine(c);if(engine.equals("oracle"))return OracleCompare.capture(job,c,target,selectedKinds,sequenceValues);Inventory inv=new Inventory(engine,Objects.toString(c.getCatalog(),target.database()),c.getMetaData().getDatabaseProductVersion(),job.comparisonMetadataBytes);inv.sequenceValues=sequenceValues;
        if(target.allSchemas()){
            if(Set.of("mysql","mariadb").contains(engine))inv.schemas.add(inv.database);
            else for(JsonNode n:pages(job,c,"schemas",inv.database,"")){String schema=n.path("schema").asText(str(n,"name"));if(!system(schema))inv.schemas.add(schema);}
        }else inv.schemas.add(target.schema());
        Map<String,CatalogObject> catalog=new TreeMap<>();String side=job.comparisonProgress==null?"":job.comparisonProgress.path("side").asText();
        if(engine.equals("postgresql"))ComparePostgresScope.roster(job,c,inv,catalog);
        else for(String schema:inv.schemas)for(JsonNode category:pages(job,c,"schema",inv.database,schema)){
            String kind=str(category,"kind");if(kind.equals("scheduled_jobs"))continue;
            job.comparisonProgress("Listing objects",side,schema+" / "+kind,catalog.size(),0);
            ArrayNode nodes;
            try{nodes=pages(job,c,kind,inv.database,schema);}catch(SQLException|IllegalArgumentException failure){check(job);throw new IllegalArgumentException("Cannot completely inspect "+schema+" / "+kind+": "+failure.getMessage(),failure);}
            for(JsonNode node:nodes){
                check(job);if(catalog.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Comparison exceeds 10,000 objects; select a smaller scope");
                ObjectNode out=item(schema,kind,node.path("objectName").asText(str(node,"name")));out.put("oid",str(node,"oid"));
                ObjectNode selection=Profiles.JSON.createObjectNode().put("key",str(node,"key"));
                selection.putObject("parent").put("kind",kind).put("database",inv.database).put("schema",schema).put("offset",node.path("_offset").asInt());
                out.set("selection",selection);catalog.put(key(schema,kind,str(out,"name")),new CatalogObject(out,selection,node));
            }
        }
        ComparePostgresScope dependencies=engine.equals("postgresql")?ComparePostgresScope.read(job,c,catalog):CompareRelationalScope.read(job,c,inv,catalog);
        Set<String> included=dependencies.closure(catalog,selected);
        if(!definitions){for(String identity:included)inv.add(catalog.get(identity).object());dependencies.apply(inv);return inv;}
        int processed=0;boolean enabled=supported(c,engine);
        for(String identity:included){
            CatalogObject entry=catalog.get(identity);ObjectNode out=entry.object();String kind=str(out,"kind");check(job);
            job.comparisonProgress("Inspecting definitions",side,str(out,"schema")+"."+str(out,"name"),processed,included.size());
            Savepoint savepoint=engine.equals("postgresql")?c.setSavepoint():null;
            try{
                if(out.path("implicit").asBoolean()){} // Its complete definition is retained with the owning table/type.
                else if(kind.equals("indexes")&&!engine.equals("postgresql"))out.put("implicit",true).put("reason","Indexes are managed with their table");
                else if(!enabled)out.put("reason","Comparison generation is unavailable for "+engine+"; this connection remains available for browsing");
                else if(kind.equals("tables"))table(job,c,inv,out,entry.selection(),entry.node());
                else object(job,c,inv,out,entry.selection(),entry.node());
            }catch(SQLException|IllegalArgumentException failure){check(job);if(failure instanceof MetadataLimitException||failure instanceof SQLException sql&&fatal(sql))throw failure;if(savepoint!=null)c.rollback(savepoint);out.put("supported",false).put("reason",Objects.toString(failure.getMessage(),"Definition could not be inspected"));}
            if(savepoint!=null)c.releaseSavepoint(savepoint);inv.add(out);processed++;
        }
        dependencies.apply(inv);if(!engine.equals("postgresql"))viewDependencies(inv);
        job.comparisonProgress("Definitions captured",side,"",processed,included.size());return inv;
    }
    static void table(QueryJobs.Job job,Connection c,Inventory inv,ObjectNode out,ObjectNode selection)throws Exception{table(job,c,inv,out,selection,null);}
    static void table(QueryJobs.Job job,Connection c,Inventory inv,ObjectNode out,ObjectNode selection,JsonNode node)throws Exception{
        ObjectNode snap=CompareTableMetadata.capture(job,c,selection,node);out.set("columns",snap.path("columns"));out.put("oid",snap.path("objectIdentity").asText(str(out,"oid")));
        String schema=str(out,"schema"),name=str(out,"name"),engine=inv.engine;DatabaseMetaData m=c.getMetaData();
        out.put("supported",true).put("reason","").put("dataSupported",true);
        ArrayNode external=out.putArray("externalDependents");
        try(ResultSet rs=m.getExportedKeys(c.getCatalog(),schema,name)){while(rs.next()){
            String dependentSchema=Objects.toString(rs.getString("FKTABLE_SCHEM"),Objects.toString(rs.getString("FKTABLE_CAT"),schema));
            if(!inv.schemas.contains(dependentSchema))external.addObject().put("schema",dependentSchema).put("table",rs.getString("FKTABLE_NAME")).put("constraint",rs.getString("FK_NAME"));
        }}
        ArrayNode keys=out.putArray("keys"),constraints=out.putArray("constraints"),indexes=out.putArray("indexes"),fks=out.putArray("foreignKeys");
        TreeMap<Integer,String> pk=new TreeMap<>();String pkName="";
        try(ResultSet rs=m.getPrimaryKeys(c.getCatalog(),schema,name)){while(rs.next()){pk.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));pkName=Objects.toString(rs.getString("PK_NAME"),"");}}
        if(!pk.isEmpty()){ObjectNode k=keys.addObject().put("name",pkName).put("primary",true);ArrayNode cols=k.putArray("columns");pk.values().forEach(cols::add);}
        Map<String,ObjectNode> byFk=new TreeMap<>();
        try(ResultSet rs=m.getImportedKeys(c.getCatalog(),schema,name)){while(rs.next()){
            String fn=rs.getString("FK_NAME");if(fn==null)throw new IllegalArgumentException("Unnamed foreign keys cannot be safely staged");
            ObjectNode fk=byFk.computeIfAbsent(fn,n->{ObjectNode v=Profiles.JSON.createObjectNode().put("name",n);v.putObject("positions");return v;});
            fk.put("schema",Objects.toString(rs.getString("PKTABLE_SCHEM"),Objects.toString(rs.getString("PKTABLE_CAT"),schema))).put("table",rs.getString("PKTABLE_NAME")).put("deleteRule",rs.getInt("DELETE_RULE")).put("updateRule",rs.getInt("UPDATE_RULE")).put("deferrability",rs.getInt("DEFERRABILITY"));
            ((ObjectNode)fk.path("positions")).putObject(Integer.toString(rs.getInt("KEY_SEQ"))).put("column",rs.getString("FKCOLUMN_NAME")).put("reference",rs.getString("PKCOLUMN_NAME"));
        }}
        for(ObjectNode fk:byFk.values()){ArrayNode columns=fk.putArray("columns"),refs=fk.putArray("references");JsonNode positions=fk.remove("positions");for(int i=1;i<=positions.size();i++){columns.add(str(positions.path(""+i),"column"));refs.add(str(positions.path(""+i),"reference"));}fks.add(fk);}
        Map<String,ObjectNode> byIndex=new TreeMap<>();
        try(ResultSet rs=m.getIndexInfo(c.getCatalog(),schema,name,false,false)){while(rs.next()){
            if(rs.getShort("TYPE")==DatabaseMetaData.tableIndexStatistic)continue;String n=rs.getString("INDEX_NAME");if(n==null)continue;
            ObjectNode index=byIndex.computeIfAbsent(n,k->{ObjectNode v=Profiles.JSON.createObjectNode().put("name",k);v.putObject("positions");return v;});
            index.put("unique",!rs.getBoolean("NON_UNIQUE"));String col=rs.getString("COLUMN_NAME");
            if(col==null)index.put("expression",true);((ObjectNode)index.path("positions")).put(Integer.toString(rs.getInt("ORDINAL_POSITION")),col);
            String filter=rs.getString("FILTER_CONDITION");if(filter!=null&&!filter.isBlank())index.put("predicate",filter);
        }}
        for(ObjectNode index:byIndex.values()){
            JsonNode positions=index.remove("positions");ArrayNode cols=index.putArray("columns");for(int i=1;i<=positions.size();i++)cols.add(positions.path(""+i).asText(""));
            if(index.path("unique").asBoolean()&&!index.path("expression").asBoolean()&&!index.has("predicate")&&!cols.equals(keys.path(0).path("columns"))){
                boolean nonnull=true;for(JsonNode col:cols)for(JsonNode sc:out.path("columns"))if(str(sc,"name").equals(col.asText())&&sc.path("nullable").asBoolean())nonnull=false;
                if(nonnull){ObjectNode k=keys.addObject().put("name",str(index,"name")).put("primary",false);k.set("columns",cols);}
            }
            indexes.add(index);
        }
        if(engine.equals("postgresql")){
            if(!snap.path("editable").asBoolean())throw new IllegalArgumentException(str(snap,"reason"));
            if(!query(job,c,"SELECT 1 FROM pg_class WHERE oid=?::oid AND (relpersistence<>'p' OR reloptions IS NOT NULL OR reltablespace<>0)",str(out,"oid")).isEmpty())throw new IllegalArgumentException("Custom table storage requires a dedicated comparison adapter");
            if(!query(job,c,"SELECT 1 FROM pg_attribute a JOIN pg_type t ON t.oid=a.atttypid WHERE a.attrelid=?::oid AND a.attnum>0 AND NOT a.attisdropped AND a.attcollation<>t.typcollation",str(out,"oid")).isEmpty())throw new IllegalArgumentException("Explicit column collations require a dedicated comparison adapter");
            for(JsonNode constraint:snap.path("constraints")){ObjectNode cp=constraint.deepCopy();cp.remove("id");if(str(cp,"kind").equals("f")){for(JsonNode fk:fks)if(str(fk,"name").equals(str(cp,"name")))((ObjectNode)fk).put("definition",str(cp,"definition"));}else constraints.add(cp);}
            for(JsonNode index:indexes)for(JsonNode nativeIndex:snap.path("indexes"))if(str(index,"name").equals(str(nativeIndex,"name"))){((ObjectNode)index).put("ddl",str(nativeIndex,"definition")).put("implicit",truth(nativeIndex.path("constrained")));}
            out.set("triggers",snap.path("triggers"));out.set("rules",snap.path("rules"));out.set("policies",snap.path("policies"));
            if(truth(snap.path("acl").path(0).path("rowsecurity"))||truth(snap.path("acl").path(0).path("forced")))throw new IllegalArgumentException("Tables using row security require a dedicated comparison adapter");
            if(!snap.path("rules").isEmpty()||!snap.path("policies").isEmpty())throw new IllegalArgumentException("Table rules and policies require a dedicated comparison adapter");
            for(JsonNode column:out.path("columns"))if(!str(column,"identity").isEmpty()){
                JsonNode seq=query(job,c,"SELECT n.nspname AS schema,s.relname AS name,q.seqstart::text AS start,q.seqincrement::text AS increment,q.seqmin::text AS minimum,q.seqmax::text AS maximum,q.seqcache::text AS cache,q.seqcycle AS cycle FROM pg_depend d JOIN pg_class s ON s.oid=d.objid JOIN pg_namespace n ON n.oid=s.relnamespace JOIN pg_sequence q ON q.seqrelid=s.oid JOIN pg_attribute a ON a.attrelid=d.refobjid AND a.attnum=d.refobjsubid WHERE d.refobjid=?::oid AND a.attname=? AND d.deptype='i' AND d.classid='pg_class'::regclass",str(out,"oid"),str(column,"name")).path(0);
                if(seq.isMissingNode())throw new IllegalArgumentException("Identity sequence definition is unavailable");((ObjectNode)column).set("identityOptions",seq);
            }
        }else if(engine.equals("h2")){
            if(!query(job,c,"SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND DOMAIN_NAME IS NOT NULL",schema,name).isEmpty())throw new IllegalArgumentException("Domain columns require a dedicated H2 comparison adapter");
            for(JsonNode key:keys)if(key.path("primary").asBoolean())constraints.addObject().put("name",str(key,"name")).put("kind","p").put("definition","PRIMARY KEY ("+CompareSql.columns(engine,key.path("columns"))+")");
            for(JsonNode row:query(job,c,"SELECT tc.CONSTRAINT_NAME AS name,cc.CHECK_CLAUSE AS expression FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_CATALOG=tc.CONSTRAINT_CATALOG AND cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA=? AND tc.TABLE_NAME=? AND tc.CONSTRAINT_TYPE='CHECK'",schema,name))
                constraints.addObject().put("name",str(row,"name")).put("kind","c").put("definition","CHECK ("+str(row,"expression")+")");
            for(JsonNode row:query(job,c,"SELECT COLUMN_NAME AS name,IDENTITY_GENERATION AS generation,IDENTITY_START AS start,IDENTITY_INCREMENT AS increment,IDENTITY_MINIMUM AS minimum,IDENTITY_MAXIMUM AS maximum,IDENTITY_CYCLE AS cycle,IDENTITY_BASE AS base,IDENTITY_CACHE AS cache FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND IS_IDENTITY='YES'",schema,name))
                for(JsonNode column:out.path("columns"))if(str(column,"name").equals(str(row,"name"))){ObjectNode options=row.deepCopy();options.remove(List.of("base","name","generation"));((ObjectNode)column).set("identityOptions",options);((ObjectNode)column).put("identity",str(row,"generation").equals("ALWAYS")?"a":"d").put("identityBase",str(row,"base"));}
            out.set("triggers",query(job,c,"SELECT TRIGGER_NAME AS name FROM INFORMATION_SCHEMA.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?",schema,name));
            Set<String> constraintIndexes=new HashSet<>();
            for(JsonNode row:query(job,c,"SELECT CONSTRAINT_NAME AS name,CONSTRAINT_TYPE AS kind,INDEX_NAME AS index_name FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",schema,name)){
                constraintIndexes.add(str(row,"index_name"));
                if(str(row,"kind").equals("UNIQUE")){
                    ArrayNode columns=Profiles.JSON.createArrayNode();for(JsonNode col:query(job,c,"SELECT COLUMN_NAME AS name FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=? AND CONSTRAINT_NAME=? ORDER BY ORDINAL_POSITION",schema,str(row,"name")))columns.add(str(col,"name"));
                    constraints.addObject().put("name",str(row,"name")).put("kind","u").put("definition","UNIQUE ("+CompareSql.columns(engine,columns)+")");
                }
            }
            for(JsonNode index:indexes)((ObjectNode)index).put("implicit",constraintIndexes.contains(str(index,"name")));
        }else{
            ArrayNode rows=query(job,c,"SHOW CREATE TABLE "+CompareSql.qualified(engine,schema,name));
            if(rows.isEmpty())throw new IllegalArgumentException("Native table definition unavailable");
            String ddl=str(rows.get(0),"create table");if(ddl.isEmpty())throw new IllegalArgumentException("Native table definition unavailable");
            out.put("nativeDdl",ddl);
            out.set("triggers",query(job,c,"SELECT TRIGGER_NAME AS name FROM INFORMATION_SCHEMA.TRIGGERS WHERE EVENT_OBJECT_SCHEMA=? AND EVENT_OBJECT_TABLE=?",schema,name));
            for(JsonNode row:query(job,c,"SELECT COLUMN_NAME AS name,COLUMN_TYPE AS type,EXTRA AS extra,GENERATION_EXPRESSION AS expression,COLLATION_NAME AS collation FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",schema,name))
                for(JsonNode column:out.path("columns"))if(str(column,"name").equals(str(row,"name"))){((ObjectNode)column).put("type",str(row,"type")).put("extra",str(row,"extra")).put("collation",str(row,"collation"));}
        }
        for(JsonNode column:out.path("columns"))if(!str(column,"generated").isEmpty())throw new IllegalArgumentException("Generated columns require a dedicated comparison adapter");
        if(out.path("triggers").size()>0)out.put("dataSupported",false).put("dataReason","Data comparison with table triggers is unavailable");
    }
    static void object(QueryJobs.Job job,Connection c,Inventory inv,ObjectNode out,ObjectNode selection)throws Exception{object(job,c,inv,out,selection,null);}
    static void object(QueryJobs.Job job,Connection c,Inventory inv,ObjectNode out,ObjectNode selection,JsonNode node)throws Exception{
        String kind=str(out,"kind");if(!Set.of("views","materialized_views","sequences","indexes","functions","procedures","types","domains").contains(kind))return;
        if(kind.equals("indexes")&&!inv.engine.equals("postgresql")){out.put("implicit",true).put("reason","Indexes are managed with their table");return;}
        ObjectNode snap=ObjectCatalog.comparison(job,c,inv.engine,selection,node);out.set("fields",snap.path("fields"));out.put("ddl",str(snap,"ddl")).put("routineIdentity",str(snap,"routineIdentity"));
        if(kind.equals("views")||kind.equals("materialized_views")){
            ArrayNode signature=out.putArray("columnSignature");try(ResultSet cols=c.getMetaData().getColumns(c.getCatalog(),str(out,"schema"),str(out,"name"),"%")){while(cols.next())signature.addObject().put("name",cols.getString("COLUMN_NAME")).put("type",cols.getString("TYPE_NAME")).put("length",cols.getInt("COLUMN_SIZE")).put("scale",cols.getInt("DECIMAL_DIGITS"));}
            String query=str(snap.path("fields"),"query");if(query.isBlank())query=str(snap.path("fields"),"definition");
            if(query.isBlank()&&!kind.equals("materialized_views"))query=str(query(job,c,"SELECT VIEW_DEFINITION AS definition FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",str(out,"schema"),str(out,"name")).path(0),"definition");
            if(query.isBlank())throw new IllegalArgumentException("View definition unavailable");
            if(Set.of("mysql","mariadb").contains(inv.engine)){
                JsonNode attributes=query(job,c,"SELECT CHECK_OPTION AS check_option,SECURITY_TYPE AS security FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA=? AND TABLE_NAME=?",str(out,"schema"),str(out,"name")).path(0);
                ObjectNode fields=(ObjectNode)out.path("fields");fields.put("checkOption",str(attributes,"check_option")).put("security",str(attributes,"security"));
                String nativeView=str(query(job,c,"SHOW CREATE VIEW "+CompareSql.qualified(inv.engine,str(out,"schema"),str(out,"name"))).path(0),"create view");
                var algorithm=java.util.regex.Pattern.compile("(?is)^CREATE\\s+ALGORITHM\\s*=\\s*(UNDEFINED|MERGE|TEMPTABLE)\\b").matcher(nativeView);
                if(!algorithm.find())throw new IllegalArgumentException("Native view algorithm is unavailable");fields.put("algorithm",algorithm.group(1).toUpperCase(Locale.ROOT));
            }
            ((ObjectNode)out.path("fields")).put("query",query.replaceFirst(";\\s*$",""));out.put("supported",true).put("reason","");
        }else if(snap.path("ddlComplete").asBoolean()){out.put("supported",true).put("reason","");}
        if(inv.engine.equals("postgresql")&&Set.of("functions","procedures").contains(kind)){
            out.set("signature",query(job,c,"SELECT pg_get_function_identity_arguments(oid) AS arguments,pg_get_function_result(oid) AS result,proargnames::text AS names FROM pg_proc WHERE oid=?::oid",str(out,"oid")).path(0));
            if(!Set.of("sql","plpgsql").contains(str(out.path("fields"),"language")))out.put("supported",false).put("reason","This routine language requires an environment-specific comparison adapter");
        }
        if(kind.equals("materialized_views")&&!str(out.path("fields"),"tablespace").isBlank())out.put("supported",false).put("reason","Materialized-view storage requires a dedicated comparison adapter");
        if(inv.engine.equals("postgresql")&&kind.equals("types")){
            JsonNode props=snap.path("details").path("Advanced");if(Set.of("c","b").contains(str(props,"typtype"))||!str(props,"typelem").equals("0")){out.put("implicit",true).put("supported",false).put("reason","Implicit relation or array type is managed with its owning object");}
        }
        if(inv.engine.equals("postgresql")&&kind.equals("indexes")){
            boolean implicit=!query(job,c,"SELECT 1 FROM pg_constraint WHERE conindid=?::oid",str(out,"oid")).isEmpty();out.put("implicit",implicit);
            if(implicit)out.put("supported",false).put("reason","Constraint index is managed with its table");
        }
        if(kind.equals("indexes")&&!inv.engine.equals("postgresql")){out.put("implicit",true).put("supported",false).put("reason","Indexes are managed with their table");}
        if(kind.equals("sequences"))sequence(job,c,inv,out);
    }
    static void sequence(QueryJobs.Job job,Connection c,Inventory inv,ObjectNode out)throws Exception{
        Savepoint statePoint=c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")?c.setSavepoint():null;ArrayNode modes=out.putArray("stateModes");String schema=str(out,"schema"),name=str(out,"name");
        try{
            if(inv.engine.equals("postgresql")){
                JsonNode state=query(job,c,"SELECT last_value::text AS value,is_called AS called FROM "+CompareSql.qualified(inv.engine,schema,name)).path(0);out.set("state",state);modes.add("advance");
                if(str(out.path("fields"),"cache").equals("1"))modes.add("exact");
                JsonNode ownership=query(job,c,"SELECT d.deptype::text AS kind,n.nspname AS schema,t.relname AS table,a.attname AS column FROM pg_depend d JOIN pg_class t ON t.oid=d.refobjid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=d.refobjsubid WHERE d.classid='pg_class'::regclass AND d.objid=?::oid AND d.deptype IN ('a','i')",str(out,"oid")).path(0);
                if(!ownership.isMissingNode()){out.set("ownership",ownership);if(str(ownership,"kind").equals("i"))out.put("identity",true);}
                ArrayNode consumers=query(job,c,"SELECT n.nspname AS schema,t.relname AS table,a.attname AS column FROM pg_depend d JOIN pg_attrdef f ON d.classid='pg_attrdef'::regclass AND f.oid=d.objid JOIN pg_class t ON t.oid=f.adrelid JOIN pg_namespace n ON n.oid=t.relnamespace JOIN pg_attribute a ON a.attrelid=t.oid AND a.attnum=f.adnum WHERE d.refclassid='pg_class'::regclass AND d.refobjid=?::oid",str(out,"oid"));
                if(!ownership.isMissingNode())consumers.add(ownership);out.set("consumers",consumers);
                java.math.BigInteger extreme=null,step=CompareSql.integer(str(out.path("fields"),"increment"));
                if(inv.sequenceValues)for(JsonNode consumer:consumers){JsonNode row=query(job,c,"SELECT "+(step.signum()>0?"MAX":"MIN")+"("+CompareSql.q(inv.engine,str(consumer,"column"))+")::text AS extreme FROM "+CompareSql.qualified(inv.engine,str(consumer,"schema"),str(consumer,"table"))).path(0);if(row.path("extreme").isNull())continue;var value=CompareSql.integer(str(row,"extreme"));if(extreme==null||step.signum()>0&&value.compareTo(extreme)>0||step.signum()<0&&value.compareTo(extreme)<0)extreme=value;}
                if(extreme!=null)((ObjectNode)state).put("extreme",extreme.toString());
            }else if(inv.engine.equals("h2")){
                JsonNode state=query(job,c,"SELECT BASE_VALUE AS \"value\" FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA=? AND SEQUENCE_NAME=?",schema,name).path(0);out.set("state",state);if(!state.path("value").isNull())modes.add("advance").add("exact");
            }
            if(modes.isEmpty())out.put("stateReason","Read-only sequence state synchronization is unavailable for this engine");
        }catch(SQLException failure){check(job);if(fatal(failure))throw failure;if(statePoint!=null)c.rollback(statePoint);modes.removeAll();out.put("stateReason","Sequence state is not readable with this connection");}finally{if(statePoint!=null)c.releaseSavepoint(statePoint);}
    }
    static boolean truth(JsonNode n){return Set.of("true","t","YES","yes","1").contains(n.asText());}
    static ArrayNode query(QueryJobs.Job job,Connection c,String sql,Object... args)throws Exception{
        ArrayNode out=Profiles.JSON.createArrayNode();long bytes=0;
        try(PreparedStatement st=c.prepareStatement(sql)){job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.setMaxRows(MAX_OBJECTS+1);
            for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);
            try(ResultSet rs=st.executeQuery()){ResultSetMetaData m=rs.getMetaData();while(rs.next()){
                check(job);if(out.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Catalog category exceeds 10,000 entries");
                bytes+=128;ObjectNode row=out.addObject();for(int i=1;i<=m.getColumnCount();i++){String label=m.getColumnLabel(i).toLowerCase(Locale.ROOT);bytes+=64L+2L*label.length();
                    String value=read(job,rs,i,m.getColumnType(i),job.comparisonMetadataBytes-bytes);bytes+=value==null?4:2L*value.length();limit(bytes,job.comparisonMetadataBytes,"Comparison catalog category");row.put(m.getColumnLabel(i).toLowerCase(Locale.ROOT),value);}
            }}
        }finally{job.statement=null;}return out;
    }
    static final class MetadataLimitException extends IllegalArgumentException {
        MetadataLimitException(String message){super(message);}
    }
    static void limit(long bytes,long maximum,String category){
        if(bytes>maximum)throw new MetadataLimitException(category+" exceeds "+(maximum>>20)+" MiB; select fewer objects or increase the saved Comparison metadata limit in DBA settings");
    }
    static String read(QueryJobs.Job job,ResultSet rs,int column,int jdbcType,long available)throws Exception{
        // JDBC only guarantees character-stream conversion for character/LOB columns.
        // Native numeric, timestamp and boolean catalog values retain their driver conversion.
        if(!Set.of(Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB).contains(jdbcType)){
            String value=rs.getString(column);limit((value==null?4:2L*value.length())+job.comparisonMetadataBytes-available,job.comparisonMetadataBytes,"Comparison catalog category");return value;
        }
        try(java.io.Reader reader=rs.getCharacterStream(column)){
            if(reader==null)return null;
            StringBuilder value=new StringBuilder();char[] buffer=new char[4096];int count;
            while((count=reader.read(buffer,0,(int)Math.min(buffer.length,Math.max(1,available/2-value.length()+1))))!=-1){
                check(job);limit((value.length()+(long)count)*2L+job.comparisonMetadataBytes-available,job.comparisonMetadataBytes,"Comparison catalog category");value.append(buffer,0,count);
            }
            return value.toString();
        }
    }
    static String pgClass(JsonNode o){return switch(str(o,"kind")){case "functions","procedures","aggregates"->"pg_proc";case "types","domains"->"pg_type";default->"pg_class";};}
    static void pgDependencies(QueryJobs.Job job,Connection c,Inventory inv)throws Exception{
        Map<String,String> ids=new HashMap<>();for(var e:inv.objects.entrySet())ids.put(pgClass(e.getValue())+":"+str(e.getValue(),"oid"),e.getKey());
        for(ObjectNode o:inv.objects.values()){
            if(o.path("implicit").asBoolean())continue;String oid=str(o,"oid");if(oid.isBlank())continue;
            ArrayNode rows=query(job,c,"SELECT d.refclassid::regclass::text AS catalog,d.refobjid::text AS oid, CASE d.refclassid WHEN 'pg_class'::regclass THEN (SELECT n.nspname FROM pg_class v JOIN pg_namespace n ON n.oid=v.relnamespace WHERE v.oid=d.refobjid) WHEN 'pg_proc'::regclass THEN (SELECT n.nspname FROM pg_proc v JOIN pg_namespace n ON n.oid=v.pronamespace WHERE v.oid=d.refobjid) WHEN 'pg_type'::regclass THEN (SELECT n.nspname FROM pg_type v JOIN pg_namespace n ON n.oid=v.typnamespace WHERE v.oid=d.refobjid) END AS schema FROM pg_depend d WHERE (d.classid=?::regclass AND d.objid=?::oid OR d.classid='pg_rewrite'::regclass AND d.objid IN (SELECT oid FROM pg_rewrite WHERE ev_class=?::oid) OR d.classid='pg_attrdef'::regclass AND d.objid IN (SELECT oid FROM pg_attrdef WHERE adrelid=?::oid)) AND d.deptype='n' AND d.refclassid IN ('pg_class'::regclass,'pg_proc'::regclass,'pg_type'::regclass)",pgClass(o),oid,oid,oid);
            SortedSet<String> dependencies=new TreeSet<>();for(JsonNode row:rows){String ref=ids.get(str(row,"catalog")+":"+str(row,"oid"));if(ref==null&&!system(str(row,"schema")))o.put("supported",false).put("reason","Dependency outside the comparison scope: "+str(row,"schema")+" (include its schema)");if(ref!=null&&!ref.equals(key(str(o,"schema"),str(o,"kind"),str(o,"name"))))dependencies.add(ref);}
            ArrayNode deps=o.putArray("dependencies");dependencies.forEach(deps::add);
        }
    }
    static void viewDependencies(Inventory inv){
        for(ObjectNode o:inv.objects.values())if(str(o,"kind").equals("views")&&o.path("supported").asBoolean()){
            try{
                var statement=net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(str(o.path("fields"),"query"));
                var names=new net.sf.jsqlparser.util.TablesNamesFinder<Void>().getTableList(statement);
                ArrayNode deps=(ArrayNode)o.path("dependencies");for(String name:names){
                    String clean=name.replace("\"","").replace(String.valueOf((char)96),"");String[] parts=clean.split("\\.");String schema=parts.length>1?parts[parts.length-2]:str(o,"schema"),object=parts[parts.length-1];boolean found=false;
                    for(String kind:List.of("tables","views","materialized_views")){String k=key(schema,kind,object);if(inv.objects.containsKey(k)){deps.add(k);found=true;break;}}
                    if(!found)throw new IllegalArgumentException("View references an object outside the comparison scope: "+name);
                }
            }catch(Exception failure){o.put("supported",false).put("reason","Cannot prove view dependencies: "+failure.getMessage());}
        }
    }
    private CompareCatalog(){}
}
