package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bounded retained migration artifacts. Plans contain reviewed SQL, never credentials. */
final class MigrationPlans implements AutoCloseable {
    private static final int MAX_PLANS=16,MAX_SQL=65_536,MAX_STATEMENTS=32;
    private static final long TTL=10*60_000L,MAX_BYTES=2L<<20;
    static final class Plan {
        final String id=UUID.randomUUID().toString(),owner;
        final long created=System.currentTimeMillis(),expires=created+TTL;
        final ObjectNode value,sourceRequest,sourceScope;
        final Runnable release;
        boolean consumed;
        Plan(String owner,ObjectNode value,ObjectNode request,ObjectNode scope,Runnable release){this.owner=owner;this.value=value;this.sourceRequest=request;this.sourceScope=scope;this.release=release;}
    }
    private final Map<String,Plan> plans=new LinkedHashMap<>();

    synchronized ObjectNode prepare(String owner,QueryJobs.Job snapshot,JsonNode input,Runnable release){
        reap();
        if(plans.size()>=MAX_PLANS)throw new IllegalArgumentException("Migration plan allowance is full; wait for retained plans to expire");
        if(!snapshot.state.equals("complete")||snapshot.result==null||!snapshot.result.path("format").asText().equals("codegraph-schema-v1")||snapshot.schemaScope==null)
            throw new IllegalArgumentException("Use a completed retained dba_capture_schema job");
        if(input==null||!input.isObject())throw new IllegalArgumentException("Migration input must be an object");
        input.fieldNames().forEachRemaining(key->{if(!List.of("snapshotId","changes","sql","name","purpose").contains(key))throw new IllegalArgumentException("Unexpected migration field: "+key);});
        boolean structured=input.has("changes"),supplied=input.has("sql");
        if(structured==supplied)throw new IllegalArgumentException("Supply exactly one of changes or sql");
        String engine=snapshot.result.path("engine").asText();
        if(!List.of("postgresql","mysql","mariadb","h2").contains(engine))throw new IllegalArgumentException("Migration planning is not verified for engine "+engine);
        List<String> statements=structured?generate(engine,snapshot.schemaScope,input.path("changes")):supplied(input.path("sql").asText());
        ObjectNode plan=Profiles.JSON.createObjectNode().put("format","codegraph-migration-v1").put("name",input.path("name").asText("Migration"))
                .put("purpose",input.path("purpose").asText("")).put("engine",engine).put("sourceSnapshotId",snapshot.id)
                .put("schemaFingerprint",snapshot.result.path("fingerprint").asText()).put("transactional",engine.equals("postgresql"))
                .put("crossEngine",false).put("rehearsed",false).put("syntheticFixturesOnly",true);
        plan.set("target",ApprovalScope.display(snapshot.schemaScope));
        ArrayNode sql=plan.putArray("statements"),steps=plan.putObject("manifest").putArray("steps"),risks=plan.putArray("risks");
        int index=0;for(String statement:statements){String action=verb(statement);sql.add(statement);steps.addObject().put("index",++index).put("action",action).put("sql",statement).put("expectedPostcondition","Statement succeeds and subsequent schema fingerprint changes as reviewed");if(destructive(statement))risks.add("Step "+index+" is destructive and can permanently remove schema objects or dependent data.");}
        if(!engine.equals("postgresql"))risks.add("This engine can auto-commit DDL; partial completion is possible and successful steps must not be replayed blindly.");
        risks.add("DDL can acquire locks, scan data, rebuild indexes, or perform an internal table rewrite.");
        plan.putObject("manifest").put("stepCount",statements.size()).put("sourceInventoryComplete",snapshot.result.path("inventoryComplete").asBoolean(false))
                .put("precondition","Exact target revision and schema fingerprint still match").put("rollback",engine.equals("postgresql")?"Atomic transaction requested; engine errors can still leave uncertain outcomes after connection loss":"No atomic rollback guarantee");
        plan.putArray("limitations").add("No rename was inferred from drop/add resemblance").add("No table data was copied into this artifact")
                .add("Incomplete source inventories cannot prove dependency absence").add("Applying always requires exact one-time human review");
        String hash=CatalogScanner.hash(CatalogScanner.stable(plan));plan.put("planHash",hash);
        Plan retained=new Plan(owner,plan,snapshot.schemaRequest.deepCopy(),snapshot.schemaScope.deepCopy(),release);
        plan.put("id",retained.id).put("createdAt",retained.created).put("expiresAt",retained.expires).put("state","prepared");
        long projected=retainedBytes()+safeSize(plan);if(projected>MAX_BYTES)throw new IllegalArgumentException("Migration plan allowance exceeds 2 MiB");
        plans.put(retained.id,retained);return publicValue(retained);
    }

    synchronized ObjectNode prepareRehearsal(String owner,Plan source,QueryJobs.Job snapshot,JsonNode input,Runnable release){
        reap();
        if(plans.size()>=MAX_PLANS)throw new IllegalArgumentException("Migration plan allowance is full; wait for retained plans to expire");
        if(source.consumed)throw new IllegalArgumentException("The source migration plan was already consumed");
        if(!snapshot.state.equals("complete")||snapshot.result==null||!snapshot.result.path("format").asText().equals("codegraph-schema-v1")||snapshot.schemaScope==null)
            throw new IllegalArgumentException("Use a completed retained dba_capture_schema job for the disposable rehearsal target");
        if(input==null||!input.isObject())throw new IllegalArgumentException("Rehearsal input must be an object");
        input.fieldNames().forEachRemaining(key->{if(!List.of("planId","rehearsalSnapshotId","setupSql","fixtureSql","checks","name","purpose").contains(key))throw new IllegalArgumentException("Unexpected rehearsal field: "+key);});
        String engine=snapshot.result.path("engine").asText();
        if(!engine.equals(source.value.path("engine").asText()))throw new IllegalArgumentException("Rehearsal target engine must match the migration engine; version differences remain disclosed separately");
        List<String> setup=restricted(input.path("setupSql").asText(""),Set.of("CREATE","COMMENT"),"Rehearsal setup");
        List<String> fixtures=restricted(input.path("fixtureSql").asText(""),Set.of("INSERT"),"Synthetic fixture");
        List<String> migration=Profiles.JSON.convertValue(source.value.path("statements"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
        if(setup.size()+fixtures.size()+migration.size()>MAX_STATEMENTS)throw new IllegalArgumentException("Setup, synthetic fixtures and migration together may contain at most 32 statements");
        ArrayNode checks=Profiles.JSON.createArrayNode();JsonNode suppliedChecks=input.path("checks");
        if(!suppliedChecks.isMissingNode()&&!suppliedChecks.isArray())throw new IllegalArgumentException("checks must be an array");
        if(suppliedChecks.size()>16)throw new IllegalArgumentException("A rehearsal may contain at most 16 validation queries");
        for(JsonNode check:suppliedChecks){if(!check.isTextual()||check.asText().isBlank()||check.asText().length()>16_384)throw new IllegalArgumentException("Each rehearsal check must be bounded SQL text");checks.add(SqlReadGuard.validate(check.asText()));}
        ObjectNode plan=Profiles.JSON.createObjectNode().put("format","codegraph-migration-rehearsal-v1")
                .put("name",input.path("name").asText(source.value.path("name").asText()+" rehearsal"))
                .put("purpose",input.path("purpose").asText("Validate the reviewed migration on a disposable target using synthetic fixtures"))
                .put("engine",engine).put("sourceSnapshotId",snapshot.id).put("sourceMigrationPlanId",source.id)
                .put("sourceMigrationPlanHash",source.value.path("planHash").asText()).put("schemaFingerprint",snapshot.result.path("fingerprint").asText())
                .put("transactional",engine.equals("postgresql")).put("crossEngine",false).put("rehearsed",false)
                .put("rehearsal",true).put("syntheticFixturesOnly",true).put("disposableTargetRequiresHumanConfirmation",true);
        plan.set("target",ApprovalScope.display(snapshot.schemaScope));plan.set("checks",checks);
        ArrayNode statements=plan.putArray("statements"),steps=plan.putObject("manifest").putArray("steps"),risks=plan.putArray("risks");
        int index=0;for(var phase:List.of(Map.entry("setup",setup),Map.entry("synthetic_fixture",fixtures),Map.entry("migration",migration)))for(String statement:phase.getValue()){
            statements.add(statement);steps.addObject().put("index",++index).put("phase",phase.getKey()).put("action",verb(statement)).put("sql",statement)
                    .put("expectedPostcondition",phase.getKey().equals("synthetic_fixture")?"Only bounded synthetic fixture values are inserted":"Statement succeeds on the reviewed disposable target");
        }
        plan.putObject("manifest").put("stepCount",index).put("checkCount",checks.size()).put("sourceInventoryComplete",snapshot.result.path("inventoryComplete").asBoolean(false))
                .put("precondition","Exact disposable target revision and schema fingerprint still match")
                .put("rollback",engine.equals("postgresql")?"Atomic transaction requested":"Vendor DDL can auto-commit; partial rehearsal setup is possible");
        risks.add("The exact setup, fixture, and migration statements mutate the reviewed disposable target.")
                .add("The application does not delete the rehearsal database, container, or schema after execution.")
                .add("DDL can lock, scan, rebuild, or rewrite objects even in a rehearsal.");
        if(!engine.equals("postgresql"))risks.add("This engine can auto-commit DDL; partial completion is possible and successful steps must not be replayed blindly.");
        plan.putArray("limitations").add("The target is designated disposable by the human approval; this is not inferred from its name")
                .add("Only supplied bounded synthetic fixtures are used; application records are never copied")
                .add("Incomplete schema reproduction produces incomplete evidence, not a production-simulation guarantee")
                .add("Engine version differences are reported by capability observations and are not hidden");
        plan.put("planHash",CatalogScanner.hash(CatalogScanner.stable(plan)));
        Plan retained=new Plan(owner,plan,snapshot.schemaRequest.deepCopy(),snapshot.schemaScope.deepCopy(),release);
        plan.put("id",retained.id).put("createdAt",retained.created).put("expiresAt",retained.expires).put("state","prepared");
        if(retainedBytes()+safeSize(plan)>MAX_BYTES)throw new IllegalArgumentException("Migration plan allowance exceeds 2 MiB");
        plans.put(retained.id,retained);return publicValue(retained);
    }

    synchronized Plan require(String owner,String id){
        reap();Plan plan=plans.get(id);if(plan==null||!plan.owner.equals(owner))throw new IllegalArgumentException("Unknown or expired migration plan");
        if(plan.consumed)throw new IllegalArgumentException("Migration plan was already consumed; prepare and review a new plan");
        return plan;
    }
    synchronized Plan verify(String owner,String id,String hash,JsonNode scope){
        reap();Plan plan=plans.get(id);if(plan==null||!plan.owner.equals(owner))throw new IllegalArgumentException("Unknown or expired migration plan");
        if(!plan.value.path("planHash").asText().equals(hash)||!plan.sourceScope.equals(scope))throw new IllegalArgumentException("Migration plan or target changed; prepare and review a new plan");
        return plan;
    }
    synchronized ObjectNode publicValue(Plan plan){ObjectNode out=plan.value.deepCopy();out.put("state",plan.consumed?"consumed":"prepared");return out;}
    synchronized void consume(Plan plan){Plan current=require(plan.owner,plan.id);current.consumed=true;}
    synchronized void release(String owner,String id){Plan plan=plans.get(id);if(plan==null||!plan.owner.equals(owner))return;plans.remove(id);plan.release.run();}
    synchronized void reap(){long now=System.currentTimeMillis();var iterator=plans.values().iterator();while(iterator.hasNext()){Plan plan=iterator.next();if(now>=plan.expires){plan.release.run();iterator.remove();}}}
    synchronized long retainedBytes(){long bytes=0;for(Plan plan:plans.values())bytes+=safeSize(plan.value);return bytes;}
    public synchronized void close(){plans.values().forEach(plan->plan.release.run());plans.clear();}

    private static List<String> supplied(String source){
        if(source==null||source.isBlank()||source.length()>MAX_SQL)throw new IllegalArgumentException("Migration SQL must contain 1..65536 characters");
        List<String> out=new ArrayList<>();for(SqlScript.Unit unit:SqlScript.extract(source)){String statement=unit.sql().trim();if(unit.parameters()!=0)throw new IllegalArgumentException("Migration SQL cannot contain prepared parameter markers");String verb=verb(statement);if(!List.of("CREATE","ALTER","DROP","COMMENT","RENAME").contains(verb))throw new IllegalArgumentException("Migration step "+unit.index()+" is not supported; use explicit CREATE, ALTER, DROP, COMMENT or RENAME DDL");if(statement.matches("(?is)^\\s*DROP\\s+(DATABASE|SCHEMA)\\b.*"))throw new IllegalArgumentException("DROP DATABASE and DROP SCHEMA are not accepted in migration plans");out.add(statement);}
        if(out.isEmpty()||out.size()>MAX_STATEMENTS)throw new IllegalArgumentException("Migration must contain 1..32 statements");return out;
    }
    private static List<String> restricted(String source,Set<String> allowed,String label){
        if(source==null||source.isBlank())return List.of();
        if(source.length()>MAX_SQL)throw new IllegalArgumentException(label+" SQL exceeds 65536 characters");
        List<String> out=new ArrayList<>();for(SqlScript.Unit unit:SqlScript.extract(source)){
            if(unit.parameters()!=0)throw new IllegalArgumentException(label+" SQL cannot contain prepared parameter markers");
            String statement=unit.sql().trim(),action=verb(statement);if(!allowed.contains(action))throw new IllegalArgumentException(label+" statement "+unit.index()+" must be one of "+allowed);
            if(statement.matches("(?is)^\\s*CREATE\\s+(DATABASE|SCHEMA)\\b.*"))throw new IllegalArgumentException(label+" cannot create a database or schema; target an existing disposable schema");
            out.add(statement);
        }return out;
    }
    private static List<String> generate(String engine,JsonNode scope,JsonNode changes){
        if(!changes.isArray()||changes.isEmpty()||changes.size()>MAX_STATEMENTS)throw new IllegalArgumentException("changes must contain 1..32 explicit operations");
        List<String> out=new ArrayList<>();for(JsonNode change:changes){
            if(!change.isObject())throw new IllegalArgumentException("Each migration change must be an object");
            String action=required(change,"action",40),schema=scope.path("schema").asText();
            switch(action){
                case "add_column"->{String table=required(change,"table",128),column=required(change,"column",128),type=dataType(change.path("type").asText());out.add("ALTER TABLE "+qualified(engine,schema,table)+" ADD COLUMN "+quote(engine,column)+" "+type+(change.path("nullable").asBoolean(true)?"":" NOT NULL"));}
                case "create_table"->{String table=required(change,"table",128);JsonNode columns=change.path("columns");if(!columns.isArray()||columns.isEmpty()||columns.size()>256)throw new IllegalArgumentException("create_table columns must contain 1..256 entries");List<String> definitions=new ArrayList<>();for(JsonNode column:columns)definitions.add(quote(engine,required(column,"name",128))+" "+dataType(column.path("type").asText())+(column.path("nullable").asBoolean(true)?"":" NOT NULL"));out.add("CREATE TABLE "+qualified(engine,schema,table)+" ("+String.join(", ",definitions)+")");}
                case "create_index"->{String table=required(change,"table",128),name=required(change,"name",128);JsonNode columns=change.path("columns");if(!columns.isArray()||columns.isEmpty()||columns.size()>32)throw new IllegalArgumentException("create_index columns must contain 1..32 names");List<String> names=new ArrayList<>();for(JsonNode column:columns)names.add(quote(engine,identifier(column.asText())));out.add("CREATE "+(change.path("unique").asBoolean()?"UNIQUE ":"")+"INDEX "+quote(engine,name)+" ON "+qualified(engine,schema,table)+" ("+String.join(", ",names)+")");}
                case "create_view"->{String name=required(change,"name",128),query=change.path("query").asText();SqlReadGuard.validate(query);out.add("CREATE VIEW "+qualified(engine,schema,name)+" AS "+query);}
                default->throw new IllegalArgumentException("Unsupported structured migration action: "+action);
            }
        }return out;
    }
    private static String required(JsonNode node,String key,int max){String value=node.path(key).asText();if(value.isBlank()||value.length()>max)throw new IllegalArgumentException(key+" is required and must be at most "+max+" characters");return key.equals("action")?value:identifier(value);}
    private static String identifier(String value){if(value==null||value.isBlank()||value.length()>128||value.indexOf(0)>=0)throw new IllegalArgumentException("Invalid identifier");return value;}
    private static String dataType(String value){if(value==null||!value.matches("[A-Za-z][A-Za-z0-9_ ]{0,60}(?:\\([0-9]{1,9}(?:,[0-9]{1,9})?\\))?"))throw new IllegalArgumentException("Use a supported literal datatype name with optional numeric length/precision");return value.toUpperCase(Locale.ROOT);}
    private static String quote(String engine,String value){String q=List.of("mysql","mariadb").contains(engine)?String.valueOf((char)96):"\"";return q+value.replace(q,q+q)+q;}
    private static String qualified(String engine,String schema,String name){return schema.isBlank()?quote(engine,name):quote(engine,schema)+"."+quote(engine,name);}
    private static String verb(String sql){String normalized=sql.replaceFirst("(?s)^\\s*(?:--[^\\r\\n]*(?:\\r?\\n|$)|/\\*.*?\\*/\\s*)*","").trim();int end=0;while(end<normalized.length()&&Character.isLetter(normalized.charAt(end)))end++;return normalized.substring(0,end).toUpperCase(Locale.ROOT);}
    private static boolean destructive(String sql){return sql.matches("(?is)^\\s*(DROP\\b|ALTER\\s+TABLE\\b.*\\bDROP\\b).*");}
    private static long safeSize(JsonNode value){try{return Profiles.JSON.writeValueAsBytes(value).length;}catch(Exception e){return MAX_BYTES;}}
}
