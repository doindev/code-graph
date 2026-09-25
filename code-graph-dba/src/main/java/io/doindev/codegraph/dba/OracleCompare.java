package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Oracle catalog scope and native definition evidence, captured without reading table rows. */
final class OracleCompare {
    private OracleCompare(){}
    static String kind(String type){return switch(type){
        case "TABLE"->"tables";case "VIEW"->"views";case "MATERIALIZED VIEW"->"materialized_views";
        case "INDEX"->"indexes";case "SEQUENCE"->"sequences";case "TYPE","TYPE BODY"->"types";
        case "PACKAGE","PACKAGE BODY"->"packages";case "PROCEDURE"->"procedures";case "FUNCTION"->"functions";
        case "TRIGGER"->"table_triggers";case "SYNONYM"->"synonyms";case "JAVA SOURCE","JAVA CLASS","JAVA RESOURCE"->"java";
        default->"";
    };}
    static List<String> schemas(QueryJobs.Job job,Connection c,Target target)throws Exception{
        if(!target.allSchemas())return List.of(target.schema());var names=new ArrayList<String>();
        for(JsonNode row:query(job,c,"SELECT username FROM SYS.ALL_USERS WHERE oracle_maintained='N' ORDER BY username"))names.add(str(row,"username"));return names;
    }
    static Inventory capture(QueryJobs.Job job,Connection c,Target target,Set<String> selectedKinds,boolean sequenceValues)throws Exception{
        var resolved=OracleDialect.target(job,c,job.remainingSeconds());if(!resolved.matches(target.database()))throw new SQLException("Oracle comparison targets a different service/PDB");
        Inventory inventory=new Inventory("oracle",resolved.database(),c.getMetaData().getDatabaseProductVersion());inventory.sequenceValues=sequenceValues;
        inventory.schemas.addAll(schemas(job,c,target));var roster=new TreeMap<String,ObjectNode>();var edges=new ArrayList<JsonNode>();var identityAliases=new HashMap<String,String>();
        boolean catalogReader=resolved.user().equals("SYS")||!query(job,c,"SELECT role FROM SYS.SESSION_ROLES WHERE role='SELECT_CATALOG_ROLE'").isEmpty();
        for(String owner:inventory.schemas)if(!owner.equals(resolved.user())&&!catalogReader)throw new IllegalArgumentException("Complete Oracle metadata for another owner requires SELECT_CATALOG_ROLE; select that owner's connection or grant catalog access: "+owner);
        String side=job.comparisonProgress==null?"":job.comparisonProgress.path("side").asText();
        for(String owner:inventory.schemas){
            job.comparisonProgress("Listing Oracle objects",side,owner,roster.size(),0);
            for(JsonNode row:query(job,c,"SELECT object_name,object_type,status,edition_name,TO_CHAR(object_id) AS oid FROM SYS.ALL_OBJECTS o WHERE owner=? AND subobject_name IS NULL AND object_type IN ('TABLE','VIEW','MATERIALIZED VIEW','INDEX','SEQUENCE','TYPE','TYPE BODY','PACKAGE','PACKAGE BODY','PROCEDURE','FUNCTION','TRIGGER','SYNONYM','JAVA SOURCE','JAVA CLASS','JAVA RESOURCE') AND (object_type<>'TABLE' OR NOT EXISTS (SELECT 1 FROM SYS.ALL_MVIEWS m WHERE m.owner=o.owner AND m.mview_name=o.object_name)) ORDER BY object_name,object_type",owner)){
                String name=str(row,"object_name"),type=str(row,"object_type"),kind=kind(type),identity=key(owner,kind,name);
                if(roster.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Comparison exceeds 10,000 Oracle objects; narrow the scope");
                ObjectNode object=roster.computeIfAbsent(identity,k->item(owner,kind,name));object.set("oracleTarget",resolved.json());object.put("incomingCoverage",catalogReader?"catalog":"accessible_objects");object.put("oracleType",type.replace(' ','_')).put("oid",str(row,"oid"));
                if(type.endsWith(" BODY")){object.put("oracleBody",true);object.put("oracleType",type.startsWith("PACKAGE")?"PACKAGE_SPEC":"TYPE_SPEC");}
                else if(type.equals("PACKAGE")||type.equals("TYPE"))object.put("oracleType",type+"_SPEC");
                if(!str(row,"status").equals("VALID"))object.put("invalid",true);
                if(!str(row,"edition_name").isBlank())object.put("edition",str(row,"edition_name"));
            }
            for(String[] category:List.of(new String[]{"queues","SYS.ALL_QUEUES","name","owner"},new String[]{"database_links","SYS.ALL_DB_LINKS","db_link","owner"},new String[]{"jobs",catalogReader?"SYS.DBA_JOBS":"SYS.USER_JOBS","TO_CHAR(job)","schema_user"})){
                if(!selectedKinds.isEmpty()&&!selectedKinds.contains(category[0]))continue;
                for(JsonNode row:query(job,c,"SELECT "+category[2]+" AS name FROM "+category[1]+" WHERE "+category[3]+"=?",owner)){
                    String name=str(row,"name");
                    ObjectNode object=item(owner,category[0],name).put("oracleType","").put("captureBlocker",category[0].equals("database_links")?"Database-link credentials and network assets require independent destination configuration":"Native generation for this Oracle object category is not yet validated");
                    object.set("oracleTarget",resolved.json());roster.put(key(owner,category[0],name),object);
                    if(roster.size()>MAX_OBJECTS)throw new IllegalArgumentException("Comparison exceeds 10,000 Oracle objects; narrow the scope");
                }
            }
            for(JsonNode row:query(job,c,"SELECT trigger_name FROM SYS.ALL_TRIGGERS WHERE owner=? AND base_object_type IN ('SCHEMA','DATABASE')",owner)){
                var object=roster.remove(key(owner,"table_triggers",str(row,"trigger_name")));if(object!=null){object.put("kind","schema_triggers");roster.put(key(owner,"schema_triggers",str(row,"trigger_name")),object);}
            }
            for(JsonNode row:query(job,c,"SELECT owner,name,type,referenced_owner,referenced_name,referenced_type,referenced_link_name FROM "+(catalogReader?"SYS.DBA_DEPENDENCIES":"SYS.ALL_DEPENDENCIES")+" WHERE owner=? OR referenced_owner=? ORDER BY owner,name,type,referenced_owner,referenced_name",owner,owner)){if(edges.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Oracle dependency scope exceeds 10,000 edges; narrow the comparison");edges.add(row);}
            for(JsonNode row:query(job,c,"SELECT c.owner,c.table_name AS name,'TABLE' AS type,r.owner AS referenced_owner,r.table_name AS referenced_name,'TABLE' AS referenced_type FROM "+(catalogReader?"SYS.DBA_CONSTRAINTS":"SYS.ALL_CONSTRAINTS")+" c JOIN "+(catalogReader?"SYS.DBA_CONSTRAINTS":"SYS.ALL_CONSTRAINTS")+" r ON r.owner=c.r_owner AND r.constraint_name=c.r_constraint_name WHERE c.constraint_type='R' AND (c.owner=? OR r.owner=?)",owner,owner)){if(edges.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Oracle dependency scope exceeds 10,000 edges; narrow the comparison");edges.add(row);}
            for(JsonNode row:query(job,c,"SELECT index_name,table_owner,table_name FROM SYS.ALL_INDEXES WHERE owner=?",owner)){
                var index=roster.get(key(owner,"indexes",str(row,"index_name")));if(index!=null)((ArrayNode)index.path("dependencies")).add(key(str(row,"table_owner"),"tables",str(row,"table_name")));
            }
            for(JsonNode row:query(job,c,"SELECT index_owner,index_name FROM SYS.ALL_CONSTRAINTS WHERE owner=? AND constraint_type IN ('P','U') AND index_name IS NOT NULL",owner)){
                var index=roster.get(key(str(row,"index_owner"),"indexes",str(row,"index_name")));if(index!=null)index.put("implicit",true);
            }
        }
        for(String owner:inventory.schemas)OracleIdentitySequences.roster(job,c,owner,roster,identityAliases);
        OracleCompareScheduler.roster(job,c,inventory,roster,catalogReader);
        for(ObjectNode object:roster.values())object.set("oracleTarget",resolved.json());
        for(String owner:inventory.schemas)OracleCompareGrants.capture(job,c,owner,roster);
        Set<String> maintained=new HashSet<>();for(JsonNode row:query(job,c,"SELECT username FROM SYS.ALL_USERS WHERE oracle_maintained='Y'"))maintained.add(str(row,"username"));
        for(JsonNode edge:edges){
            String from=identity(roster,str(edge,"owner"),str(edge,"type"),str(edge,"name")),to=identity(roster,str(edge,"referenced_owner"),str(edge,"referenced_type"),str(edge,"referenced_name"));
            from=identityAliases.getOrDefault(from,from);
            if(identityAliases.containsKey(to)){
                ObjectNode generator=roster.get(identityAliases.get(to));JsonNode ownership=generator.path("ownership");
                if(from.equals(key(str(ownership,"schema"),"tables",str(ownership,"table"))))continue;
                if(roster.containsKey(from))roster.get(from).withArray("blockers").add("Explicit reference to a system-generated identity sequence requires manual remapping: "+str(edge,"referenced_name"));
                to=identityAliases.get(to);
            }
            ObjectNode object=roster.get(from);if(object==null){if(roster.containsKey(to)&&!maintained.contains(str(edge,"owner")))roster.get(to).withArray("outsideDependents").add(str(edge,"owner")+"."+str(edge,"name"));continue;}
            if(!str(edge,"referenced_link_name").isBlank()){object.withArray("blockers").add("Remote dependency requires database link "+str(edge,"referenced_link_name"));continue;}
            if(maintained.contains(str(edge,"referenced_owner"))||from.equals(to))continue;
            if(to==null||!roster.containsKey(to)){object.withArray("blockers").add("Dependency outside captured scope: "+str(edge,"referenced_owner")+"."+str(edge,"referenced_name"));continue;}
            ArrayNode dependencies=(ArrayNode)object.path("dependencies");if(!CompareSql.contains(dependencies,to))dependencies.add(to);
        }
        Set<String> included=new TreeSet<>();for(var entry:roster.entrySet())if(selectedKinds.isEmpty()||selectedKinds.contains(str(entry.getValue(),"kind")))included.add(entry.getKey());
        boolean changed;do{check(job);changed=false;for(var entry:roster.entrySet())for(JsonNode dependency:entry.getValue().path("dependencies")){
            if(included.contains(entry.getKey())&&roster.containsKey(dependency.asText()))changed|=included.add(dependency.asText());
            if(included.contains(dependency.asText()))changed|=included.add(entry.getKey());
        }}while(changed);
        int processed=0;
        for(String identity:included){
            check(job);ObjectNode object=roster.get(identity);job.comparisonProgress("Capturing Oracle definitions",side,str(object,"schema")+"."+str(object,"name"),processed++,included.size());
            try{definition(job,c,inventory,object);}catch(SQLException|IllegalArgumentException failure){check(job);if(failure instanceof SQLException sql&&fatal(sql))throw failure;object.put("supported",false).put("reason",Objects.toString(failure.getMessage(),"Native Oracle metadata unavailable"));}
            inventory.add(object);
        }
        return inventory;
    }
    private static String identity(Map<String,ObjectNode> roster,String owner,String type,String name){String kind=kind(type),key=key(owner,kind,name);if(type.equals("TRIGGER")&&!roster.containsKey(key))key=key(owner,"schema_triggers",name);return key;}
    private static void definition(QueryJobs.Job job,Connection c,Inventory inventory,ObjectNode object)throws Exception{
        String owner=str(object,"schema"),name=str(object,"name"),type=str(object,"oracleType"),kind=str(object,"kind");
        if(!str(object,"captureBlocker").isBlank())throw new IllegalArgumentException(str(object,"captureBlocker"));
        if(object.path("invalid").asBoolean())throw new IllegalArgumentException("Source/destination object is invalid; resolve its compilation diagnostics first");
        if(!object.path("blockers").isEmpty())throw new IllegalArgumentException(object.path("blockers").toString());
        if(kind.equals("scheduler")){OracleCompareScheduler.definition(job,c,object);return;}
        if(kind.equals("java"))throw new IllegalArgumentException("Java definitions require independent external asset validation");
        if(kind.equals("tables")&&!query(job,c,"SELECT table_name FROM SYS.ALL_EXTERNAL_TABLES WHERE owner=? AND table_name=?",owner,name).isEmpty())throw new IllegalArgumentException("External table assets must be supplied and validated independently");
        if(object.path("identity").asBoolean()){OracleIdentitySequences.capture(job,c,inventory,object);return;}
        if(object.path("implicit").asBoolean()){object.put("supported",true).put("reason","Index is managed by its table constraint");return;}
        String ddl=OracleDocuments.capture(job,c,type,owner,name,"DDL");
        if(OracleCompareSql.dynamic(ddl))throw new IllegalArgumentException("Dynamic SQL requires manual dependency and identifier review");
        if(kind.equals("sequences")){
            ObjectNode fields=query(job,c,"SELECT min_value, max_value, increment_by, cycle_flag, order_flag, cache_size, last_number, scale_flag, extend_flag, sharded_flag, session_flag, keep_value FROM SYS.ALL_SEQUENCES WHERE sequence_owner=? AND sequence_name=?",owner,name).path(0).deepCopy();
            JsonNode boundary=fields.remove("last_number");object.set("fields",fields);String initial=CompareSql.integer(str(fields,"increment_by")).signum()>0?str(fields,"min_value"):str(fields,"max_value");object.put("ddl",ddl.replaceFirst("(?i)START WITH\\s+[-+]?\\d+","START WITH "+initial));
            // LAST_NUMBER is already returned by this catalog read; retain it so review can enable synchronization.
            object.putObject("state").set("value",boundary);object.withObject("state").put("observation",fields.path("cache_size").asInt()==0?"uncached_catalog_boundary":"cache_boundary");
            boolean ordinary=str(fields,"cycle_flag").equals("N")&&str(fields,"scale_flag").equals("N")&&str(fields,"sharded_flag").equals("N")&&str(fields,"session_flag").equals("N");
            ArrayNode modes=object.putArray("stateModes");if(ordinary)modes.add("advance");object.put("stateReason",ordinary?"Sync advances to a catalog boundary; cached source values may remain unused":"Cyclic, scalable, sharded or session sequence values need manual synchronization");

        }else{
            object.put("ddl",ddl);object.put("oracleXml",OracleDocuments.capture(job,c,type,owner,name,"XML"));
            if(Set.of("tables","indexes","views","materialized_views","types").contains(kind))object.put("oracleSxml",OracleDocuments.capture(job,c,type,owner,name,"SXML"));
            if(object.path("oracleBody").asBoolean()){
                String bodyType=kind.equals("packages")?"PACKAGE_BODY":"TYPE_BODY",body=OracleDocuments.capture(job,c,bodyType,owner,name,"DDL");
                if(OracleCompareSql.dynamic(body))throw new IllegalArgumentException("Dynamic SQL in object body requires manual dependency and identifier review");
                object.put("oracleBodyDdl",body).put("oracleBodyXml",OracleDocuments.capture(job,c,bodyType,owner,name,"XML"));
            }
        }
        if(kind.equals("tables"))OracleCompareData.metadata(job,c,object);
        object.put("supported",true).put("reason","");
    }
    static void review(QueryJobs.Job job,Connection destination,Inventory source,Inventory target,Target from,Target to)throws Exception{
        Map<String,String> mapping=from.allSchemas()?Map.of():Map.of(from.schema(),to.schema());int processed=0;long reviewBytes=0;
        for(ObjectNode object:source.objects.values()){
            check(job);String owner=mapping.getOrDefault(str(object,"schema"),str(object,"schema")),name=str(object,"name"),kind=str(object,"kind"),type=str(object,"oracleType");
            job.comparisonProgress("Preparing Oracle differences","destination",owner+"."+name,processed++,source.objects.size());
            ArrayNode changes=object.putArray("oracleChanges");if(!object.path("supported").asBoolean()||object.path("implicit").asBoolean()){
                reviewBytes=reviewBudget(reviewBytes,object);continue;
            }
            ObjectNode old=target.objects.get(key(owner,kind,name));
            try{
                if(old!=null&&!old.path("supported").asBoolean())throw new IllegalArgumentException("Destination metadata is blocked: "+str(old,"reason"));
                if(kind.equals("scheduler"))OracleCompareScheduler.review(object,old,owner,mapping,changes);
                else if(old==null){
                    var statements=new ArrayList<String>();
                    if(kind.equals("sequences"))statements.add(OracleCompareSql.remap(str(object,"ddl"),mapping));
                    else statements.add(OracleCompareSql.remap(OracleDocuments.remap(job,destination,type,str(object,"oracleXml"),str(object,"schema"),owner,false,true),mapping));
                    if(object.path("oracleBody").asBoolean())statements.add(OracleCompareSql.remap(OracleDocuments.remap(job,destination,kind.equals("packages")?"PACKAGE_BODY":"TYPE_BODY",str(object,"oracleBodyXml"),str(object,"schema"),owner,false,true),mapping));
                    change(changes,"CREATE",name,"",false,statements);
                }else if(kind.equals("sequences")){
                    if(!object.path("fields").equals(old.path("fields")))change(changes,"ALTER_SEQUENCE",name,"",true,List.of(OracleCompareSql.remap(str(object,"ddl"),mapping).replaceFirst("(?i)CREATE\\s+SEQUENCE","ALTER SEQUENCE").replaceFirst("(?i)START WITH\\s+[-+]?\\d+","")));
                }else if(!str(object,"oracleSxml").isBlank()){
                    String desired=OracleDocuments.simplified(job,destination,type,str(object,"oracleXml"),str(object,"schema"),owner);
                    if(kind.equals("tables"))desired=OracleDocuments.preserveIdentityStarts(str(old,"oracleSxml"),desired);
                    for(var alteration:OracleDocuments.changes(job,destination,type,str(old,"oracleSxml"),desired)){
                        if(!alteration.blocker().isBlank())throw new IllegalArgumentException(alteration.blocker());
                        change(changes,alteration.clause(),alteration.name(),alteration.attribute(),alteration.destructive(),alteration.statements().stream().map(sql->OracleCompareSql.remap(sql,mapping)).toList());
                    }
                }else if(!OracleCompareSql.canonical(OracleCompareSql.remap(str(object,"ddl"),mapping)).equals(OracleCompareSql.canonical(str(old,"ddl")))){
                    change(changes,"REPLACE",name,"",false,List.of(OracleCompareSql.remap(OracleDocuments.remap(job,destination,type,str(object,"oracleXml"),str(object,"schema"),owner,false,true),mapping)));
                }
                if(old!=null&&object.path("oracleBody").asBoolean()&&!OracleCompareSql.canonical(OracleCompareSql.remap(str(object,"oracleBodyDdl"),mapping)).equals(OracleCompareSql.canonical(str(old,"oracleBodyDdl"))))
                    change(changes,"REPLACE_BODY",name,"",false,List.of(OracleCompareSql.remap(OracleDocuments.remap(job,destination,kind.equals("packages")?"PACKAGE_BODY":"TYPE_BODY",str(object,"oracleBodyXml"),str(object,"schema"),owner,false,true),mapping)));
                OracleCompareGrants.review(job,destination,object,old,owner,mapping,changes,kind.equals("scheduler")&&OracleCompareScheduler.hasDefinitionChange(changes));
                if(!changes.isEmpty()&&!object.path("outsideDependents").isEmpty())throw new IllegalArgumentException("Incoming dependents outside the captured scope: "+object.path("outsideDependents"));
            }catch(SQLException|IllegalArgumentException failure){check(job);if(failure instanceof SQLException sql&&fatal(sql))throw failure;changes.removeAll();object.put("supported",false).put("reason",Objects.toString(failure.getMessage(),"Oracle cannot generate this change"));}
            reviewBytes=reviewBudget(reviewBytes,object);
        }
        OracleCompareScheduler.incoming(source,target,mapping);
        reviewBytes=0;for(ObjectNode object:source.objects.values())reviewBytes=reviewBudget(reviewBytes,object);
    }
    static void retainReviewScope(CompareDiff diff,Set<String> kinds,Set<String> ids){
        // Dependency and incoming-dependent objects must remain selectable in review.
        var retained=new HashSet<String>();var sourceIds=new HashMap<String,String>();var destinationIds=new HashMap<String,String>();
        for(var object:diff.objects.values()){
            JsonNode display=object.source==null?object.destination:object.source;
            if(kinds.contains(str(display,"kind"))&&(ids.isEmpty()||object.source==null||ids.contains(object.id)))retained.add(object.id);
            if(object.source!=null)sourceIds.put(key(str(object.source,"schema"),str(object.source,"kind"),str(object.source,"name")),object.id);
            if(object.destination!=null)destinationIds.put(key(str(object.destination,"schema"),str(object.destination,"kind"),str(object.destination,"name")),object.id);
        }
        boolean changed;do{changed=false;for(var object:diff.objects.values()){
            for(boolean sourceSide:List.of(true,false)){JsonNode value=sourceSide?object.source:object.destination;if(value==null)continue;var index=sourceSide?sourceIds:destinationIds;
                for(JsonNode dependency:value.path("dependencies")){String other=index.get(dependency.asText());if(other==null)continue;if(retained.contains(object.id))changed|=retained.add(other);if(retained.contains(other))changed|=retained.add(object.id);}
            }
        }}while(changed);
        diff.objects.values().removeIf(object->{JsonNode value=object.source==null?object.destination:object.source;return !retained.contains(object.id)||value.path("implicit").asBoolean()&&!kinds.contains(str(value,"kind"));});
    }
    static long reviewBudget(long used,ObjectNode object)throws Exception{
        long next=used+Profiles.JSON.writeValueAsBytes(object).length*2L+512;
        if(next>MAX_BYTES)throw new IllegalArgumentException("Oracle review exceeds its metadata allowance; narrow the scope");return next;
    }
    private static void change(ArrayNode changes,String clause,String name,String attribute,boolean destructive,List<String> statements){
        ObjectNode change=changes.addObject().put("clause",clause).put("name",name).put("attribute",attribute).put("destructive",destructive);ArrayNode sql=change.putArray("sql");statements.forEach(sql::add);
    }

    static CompareSql.Plan prepare(CompareSql.Plan plan){
        CompareSql.requireDependencies(plan);
        for(String schema:plan.source.schemas)if(!plan.destination.schemas.contains(plan.schema(schema)))throw new IllegalArgumentException("Create and authorize the destination Oracle owner before comparison: "+plan.schema(schema));
        var ordered=new ArrayList<CompareSql.Choice>();var visited=new HashSet<String>();var active=new HashSet<String>();
        for(String key:plan.selected.keySet())order(plan,key,visited,active,ordered);
        OracleCompareScheduler.prepare(plan,ordered);
        var recompile=new LinkedHashMap<String,ObjectNode>();
        for(var choice:ordered){
            JsonNode source=choice.source();
            if(!source.path("oracleSelectedChanges").isEmpty())for(var dependent:plan.source.objects.entrySet())if(CompareSql.contains(dependent.getValue().path("dependencies"),key(str(source,"schema"),str(source,"kind"),str(source,"name")))){
                ObjectNode object=dependent.getValue(),old=plan.destination.objects.get(key(plan.schema(str(object,"schema")),str(object,"kind"),str(object,"name")));
                if(old==null)continue;
                if(!plan.selected.containsKey(dependent.getKey())&&!CompareSql.same(plan,object,old))throw new IllegalArgumentException("Select the changed incoming dependent "+str(object,"schema")+"."+str(object,"name"));
                recompile.put(dependent.getKey(),object);
            }
            if(choice.options().path("includeData").asBoolean()){
                if(!source.path("dataSupported").asBoolean())throw new IllegalArgumentException(str(source,"name")+": "+str(source,"dataReason"));
                if(choice.destination()!=null&&!choice.destination().path("dataSupported").asBoolean())throw new IllegalArgumentException("Destination "+str(source,"name")+": "+str(choice.destination(),"dataReason"));
                if(!source.path("outsideDependents").isEmpty()||choice.destination()!=null&&!choice.destination().path("outsideDependents").isEmpty())throw new IllegalArgumentException("Data changes require including incoming dependents outside the captured owners");
                if(source.path("oracleSelectedChanges").size()!=source.path("oracleChanges").size())throw new IllegalArgumentException("Include all reviewed Oracle table changes before copying its data");
                String mode=plan.mode(choice);
                if(choice.destination()!=null&&Set.of("replace","mirror").contains(mode)&&!str(choice.destination(),"incomingCoverage").equals("catalog"))throw new IllegalArgumentException("Oracle data deletion requires destination SELECT_CATALOG_ROLE to verify incoming foreign keys across owners");
                plan.data.add(choice);
            }
            for(JsonNode change:source.path("oracleSelectedChanges")){
                if(change.path("destructive").asBoolean()&&str(source,"incomingCoverage").equals("accessible_objects"))throw new IllegalArgumentException("Destructive Oracle changes require SELECT_CATALOG_ROLE to validate incoming dependents across owners");
                if(change.path("destructive").asBoolean()&&!plan.destructive)throw new IllegalArgumentException("Enable destructive schema changes to include "+str(change,"clause")+" for "+str(source,"name"));
                for(JsonNode sql:change.path("sql"))plan.before.add(sql.asText());
                if(str(source,"kind").equals("sequences")&&choice.destination()==null&&!plan.sync(choice))plan.warnings.add(str(source,"name")+": new sequence starts at its initial bound; enable value synchronization to use the observed source boundary.");
            }
            if(str(source,"kind").equals("sequences")&&plan.sync(choice))sequence(plan,choice);
        }
        for(ObjectNode object:recompile.values()){
            String kind=str(object,"kind"),type=switch(kind){case "views"->"VIEW";case "functions"->"FUNCTION";case "procedures"->"PROCEDURE";case "packages"->"PACKAGE";case "types"->"TYPE";case "table_triggers","schema_triggers"->"TRIGGER";default->"";};
            if(!type.isEmpty())plan.after.add("ALTER "+type+" "+plan.target(object)+" COMPILE");
        }
        return plan;
    }
    private static void order(CompareSql.Plan plan,String key,Set<String> visited,Set<String> active,List<CompareSql.Choice> out){
        if(visited.contains(key))return;var choice=plan.selected.get(key);if(choice==null)return;
        if(!active.add(key))throw new IllegalArgumentException("Oracle dependency cycle requires staged definitions: "+str(choice.source(),"name"));
        for(JsonNode dependency:choice.source().path("dependencies"))order(plan,dependency.asText(),visited,active,out);
        active.remove(key);visited.add(key);out.add(choice);
    }
    private static void sequence(CompareSql.Plan plan,CompareSql.Choice choice){
        JsonNode source=choice.source(),destination=choice.destination();
        if(!CompareSql.contains(source.path("stateModes"),plan.sequenceMode(choice)))throw new IllegalArgumentException(str(source,"name")+": "+str(source,"stateReason"));
        java.math.BigInteger increment=CompareSql.integer(str(source.path("fields"),"increment_by")),next=CompareSql.integer(str(source.path("state"),"value"));
        if(destination!=null){if(!CompareSql.contains(destination.path("stateModes"),"advance"))throw new IllegalArgumentException("Destination sequence cannot be safely advanced");next=CompareSql.advance(next,CompareSql.integer(str(destination.path("state"),"value")),increment);}
        if(next.compareTo(CompareSql.integer(str(source.path("fields"),"min_value")))<0||next.compareTo(CompareSql.integer(str(source.path("fields"),"max_value")))>0)throw new IllegalArgumentException("Sequence synchronization would exceed its bounds");
        plan.warnings.add(str(source,"name")+": value synchronization advances to an observed catalog/cache boundary; source NEXTVAL was never evaluated. Cached unused values may be skipped.");
        plan.state.add(source.path("identity").asBoolean()?OracleIdentitySequences.advance(plan,choice,next):"ALTER SEQUENCE "+plan.target(source)+" RESTART START WITH "+next);
    }

}
