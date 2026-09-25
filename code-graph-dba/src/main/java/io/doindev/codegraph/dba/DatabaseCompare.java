package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.function.Predicate;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Browser-owned comparison jobs. Only private artifacts are written; database connections are read-only. */
final class DatabaseCompare implements AutoCloseable {
    static final long TTL=600_000;
    private final Profiles profiles;private final Connections connections;private final QueryJobs jobs;private final Predicate<String> alive;
    private final CompareData.Budget dataBudget=new CompareData.Budget(256L<<20);
    final CompareArtifacts artifacts;final Path directory;
    private final Map<String,Receipt> receipts=new HashMap<>();private final Map<String,Comparison> comparisons=new HashMap<>();private boolean closed;
    record Receipt(String owner,Target target,long expires){}
    final class Comparison {
        final String id=UUID.randomUUID().toString(),owner;final Target from,to;final JsonNode options;final QueryJobs.RetainedReservation lease;
        final long metadataBytes;
        final Set<String> jobIds=new HashSet<>();CompareDiff diff;CompareData data;long expires=System.currentTimeMillis()+TTL;boolean disposed,busy,ready;volatile QueryJobs.Job activeJob;
        Comparison(String owner,Target from,Target to,JsonNode options,QueryJobs.RetainedReservation lease,long metadataBytes){this.owner=owner;this.from=from;this.to=to;this.options=options.deepCopy();this.lease=lease;this.metadataBytes=metadataBytes;}
    }
    DatabaseCompare(Profiles profiles,Connections connections,QueryJobs jobs,Path root,Predicate<String> alive)throws IOException{
        this.profiles=profiles;this.connections=connections;this.jobs=jobs;this.alive=alive;artifacts=new CompareArtifacts(root,alive);
        directory=root.resolve("compare-data");Files.createDirectories(directory);if(Files.isSymbolicLink(directory))throw new IOException("Compare data directory cannot be a link");Profiles.protect(directory);
        try(var dirs=Files.newDirectoryStream(directory,"cgraph-compare-data-*")){for(Path dir:dirs)if(Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)&&Files.getLastModifiedTime(dir).toMillis()<System.currentTimeMillis()-TTL){
            try(var files=Files.newDirectoryStream(dir)){for(Path file:files)if(file.getFileName().toString().matches("\\d+\\.jsonl")&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(file);}try{Files.delete(dir);}catch(DirectoryNotEmptyException ignored){}
        }}
    }
    void human(String owner){if(closed||owner.startsWith("agent:")||!alive.test(owner))throw new SecurityException("Database comparison requires a live browser session");}
    Target target(JsonNode request){String id=Profiles.text(request,"connectionId",36);ObjectNode profile=profiles.get(id);
        if(DatabaseTransport.of(profile)!=DatabaseTransport.JDBC)throw new IllegalArgumentException("Select a SQL connection");
        return new Target(id,request.path("database").asText(""),request.path("schema").asText(""),request.path("allSchemas").asBoolean(),ProjectContexts.profileRevision(profile));}
    void current(Target target){if(!target.revision().equals(ProjectContexts.profileRevision(profiles.get(target.connectionId()))))throw new IllegalArgumentException("Connection changed; test the target again");}
    interface Read<T>{T run(Connection c)throws Exception;}
    <T>T read(QueryJobs.Job job,Target target,Read<T> action)throws Exception{
        check(job);current(target);
        try(Connections.Target opened=connections.target(target.connectionId(),target.database())){
            Connection c=opened.connection();if(!c.getAutoCommit())c.rollback();try{c.setReadOnly(true);}catch(SQLFeatureNotSupportedException ignored){}
            boolean transactional=c.getMetaData().supportsTransactions();
            if(transactional){if(c.getMetaData().supportsTransactionIsolationLevel(Connection.TRANSACTION_REPEATABLE_READ))c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);c.setAutoCommit(false);}
            try{
                if(ExplainPlans.engine(c.getMetaData()).equals("postgresql"))try(Statement setup=c.createStatement()){job.statement=setup;setup.setQueryTimeout(job.remainingSeconds());setup.execute("SET LOCAL search_path = pg_catalog");setup.execute("SET LOCAL TIME ZONE 'UTC'");setup.execute("SET LOCAL lock_timeout = '3s'");setup.execute("SET LOCAL statement_timeout = "+job.remainingSeconds()*1000);}
                if(OracleDialect.isOracle(c))try(Statement setup=c.createStatement()){job.statement=setup;setup.setQueryTimeout(job.remainingSeconds());setup.execute("ALTER SESSION SET NLS_CALENDAR='GREGORIAN'");setup.execute("ALTER SESSION SET TIME_ZONE='+00:00'");}
                return action.run(c);
            }finally{job.statement=null;if(transactional)c.rollback();}
        }
    }
    ObjectNode test(String owner,JsonNode request){
        human(owner);Target target=target(request);
        return jobs.comparison(owner,Set.of(target.connectionId()),"connection_test",job->read(job,target,c->{
            String engine=engine(c),database=engine.equals("oracle")?OracleDialect.target(job,c,job.remainingSeconds()).database():Objects.toString(c.getCatalog(),target.database());
            ObjectNode result=Profiles.JSON.createObjectNode().put("engine",engine).put("version",c.getMetaData().getDatabaseProductVersion()).put("supported",supported(c,engine)).put("database",database);
            ArrayNode databases=result.putArray("databases");for(JsonNode n:pages(job,c,"databases",database,""))databases.add(n.path("database").asText(str(n,"name")));if(databases.isEmpty())databases.add(database);
            ArrayNode schemas=result.putArray("schemas");
            if(Set.of("mysql","mariadb").contains(engine))schemas.add(database);
            else for(JsonNode n:pages(job,c,"schemas",database,"")){String s=n.path("schema").asText(str(n,"name"));if(!system(s))schemas.add(s);}
            if(!target.allSchemas()&&!target.schema().isEmpty()&&!CompareSql.contains(schemas,target.schema()))throw new IllegalArgumentException("Selected schema is unavailable");
            String schema=target.schema();ArrayNode kinds=result.putArray("objectTypes");
            for(JsonNode n:pages(job,c,"schema",database,schema)){if(str(n,"kind").equals("scheduled_jobs"))continue;kinds.addObject().put("id",str(n,"kind")).put("label",str(n,"name"));}
            if(!schema.isEmpty()||target.allSchemas()){
                String receipt=UUID.randomUUID().toString();synchronized(this){human(owner);if(receipts.size()>=128)throw new IllegalArgumentException("Too many target tests; wait for old receipts to expire");receipts.put(receipt,new Receipt(owner,new Target(target.connectionId(),database,schema,target.allSchemas(),target.revision()),System.currentTimeMillis()+TTL));}
                result.put("receipt",receipt);
            }return result;
        }),()->{});
    }
    synchronized Target receipt(String owner,String id){human(owner);Receipt receipt=receipts.get(id);if(receipt==null||!receipt.owner.equals(owner)||receipt.expires<System.currentTimeMillis())throw new IllegalArgumentException("Target test expired; test the selection again");current(receipt.target);return receipt.target;}
    ObjectNode catalog(String owner,JsonNode request){
        Target target=receipt(owner,str(request,"sourceReceipt"));
        return jobs.comparison(owner,Set.of(target.connectionId()),"catalog",job->read(job,target,c->{
            ArrayNode objects=Profiles.JSON.createArrayNode();List<String> schemas=new ArrayList<>();
            if(target.allSchemas()){if(Set.of("mysql","mariadb").contains(ExplainPlans.engine(c.getMetaData())))schemas.add(target.database());else for(JsonNode n:pages(job,c,"schemas",target.database(),""))if(!system(str(n,"schema")))schemas.add(str(n,"schema"));}
            else schemas.add(target.schema());
            for(String schema:schemas)for(JsonNode kind:request.path("objectTypes"))for(JsonNode n:kind.asText().equals("scheduler")&&OracleDialect.isOracle(c)?OracleCompareScheduler.catalog(job,c,schema):pages(job,c,kind.asText(),target.database(),schema)){
                if(objects.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Catalog exceeds 10,000 objects");String name=n.path("objectName").asText(str(n,"name"));
                job.comparisonProgress("Loading object data options","source",schema+"."+name,objects.size(),0);
                ObjectNode listed=objects.addObject().put("id",TableDesigner.hash(Profiles.JSON.getNodeFactory().textNode(key(schema,kind.asText(),name))).substring(0,32)).put("name",name).put("schema",schema).put("kind",kind.asText());
                if(kind.asText().equals("tables")){
                    ObjectNode object=item(schema,"tables",name),selection=Profiles.JSON.createObjectNode().put("key",str(n,"key"));selection.putObject("parent").put("kind","tables").put("database",target.database()).put("schema",schema).put("offset",n.path("_offset").asInt());
                    Savepoint point=c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")?c.setSavepoint():null;
                    try{if(OracleDialect.isOracle(c))OracleCompareData.metadata(job,c,object);else table(job,c,new Inventory(ExplainPlans.engine(c.getMetaData()),target.database(),""),object,selection,n);listed.set("keys",object.path("keys"));listed.put("dataSupported",object.path("dataSupported").asBoolean()).put("dataReason",str(object,"dataReason"));}
                    catch(SQLException|IllegalArgumentException error){check(job);if(error instanceof SQLException sql&&fatal(sql))throw error;if(point!=null)c.rollback(point);listed.put("dataSupported",false).put("dataReason","Data inspection is unavailable for this table");}
                    finally{if(point!=null)c.releaseSavepoint(point);}
                }
            }
            ObjectNode result=Profiles.JSON.createObjectNode();result.set("objects",objects);return result;
        }),()->{});
    }
    synchronized ObjectNode start(String owner,JsonNode request)throws Exception{
        validateDataMode(request);
        Target from=receipt(owner,str(request,"sourceReceipt")),to=receipt(owner,str(request,"destinationReceipt"));
        if(from.allSchemas()!=to.allSchemas())throw new IllegalArgumentException("Use the same scope mode for source and destination");
        if(!request.path("objectTypes").isArray()||request.path("objectTypes").isEmpty())throw new IllegalArgumentException("Select object types");
        if(comparisons.size()>=2)throw new IllegalArgumentException("Close another comparison before starting a new one (maximum two)");
        long metadataBytes=jobs.comparisonMetadataBytes();
        QueryJobs.RetainedReservation lease=jobs.retainAllowance(Math.multiplyExact(5L,metadataBytes));Comparison comparison=new Comparison(owner,from,to,request,lease,metadataBytes);comparisons.put(comparison.id,comparison);comparison.busy=true;
        try{
            ObjectNode submitted=jobs.comparison(owner,new HashSet<>(List.of(from.connectionId(),to.connectionId())),job->{
                comparison.activeJob=job;job.comparisonMetadataBytes=comparison.metadataBytes;var captured=capturePair(job,from,to,request,sequenceValues(request));
                Inventory source=captured.source(),destination=captured.destination();
                if(!source.engine.equals(destination.engine))throw new IllegalArgumentException("Source and destination must use the same engine");
                if(source.engine.equals("oracle"))read(job,to,c->{OracleCompare.review(job,c,source,destination,from,to);return null;});
                CompareDiff diff=new CompareDiff(source,destination,from,to);CompareData data=new CompareData(directory,dataBudget);
                synchronized(this){if(comparison.disposed){data.close();throw new IllegalArgumentException("Comparison closed");}comparison.diff=diff;comparison.data=data;}
                Set<String> types=new HashSet<>();request.path("objectTypes").forEach(n->types.add(n.asText()));
                Set<String> included=new HashSet<>();request.path("objectIds").forEach(n->included.add(n.asText()));
                OracleCompare.retainReviewScope(diff,types,included);
                if(!source.engine.equals("oracle"))for(var object:diff.objects.values())object.dependencyOnly=!types.contains(str(object.source==null?object.destination:object.source,"kind"))||!included.isEmpty()&&!included.contains(object.id)&&object.source!=null;
                for(JsonNode option:request.path("tableData"))if(!structureOnly(request)&&option.path("includeData").asBoolean()){
                    CompareDiff.ObjectDiff object=diff.objects.get(str(option,"id"));if(object==null||object.source==null||!str(object.source,"kind").equals("tables")||!object.source.path("dataSupported").asBoolean())throw new IllegalArgumentException("Table data is unavailable for selected object");
                    data.table(object,option);
                }
                if(!data.tables.isEmpty())jobs.comparisonReads(job,from.connectionId(),to.connectionId(),
                    side->read(side,from,c->{for(CompareData.Table table:data.tables.values()){side.comparisonProgress("Reading rows","source",str(table.source,"name"),0,data.tables.size());table.left=data.capture(side,c,source.engine,table,true);}return null;}),
                    side->read(side,to,c->{for(CompareData.Table table:data.tables.values()){side.comparisonProgress("Reading rows","destination",str(table.source,"name"),0,data.tables.size());table.right=data.capture(side,c,destination.engine,table,false);}return null;}));
                for(CompareData.Table table:data.tables.values()){job.progress="Comparing rows: "+str(table.source,"name");data.compare(job,table);}
                synchronized(this){if(comparison.disposed)throw new IllegalArgumentException("Comparison closed");comparison.ready=true;comparison.expires=System.currentTimeMillis()+TTL;}
                return Profiles.JSON.createObjectNode().put("comparisonId",comparison.id).put("revision",diff.revision);
            },()->finished(comparison));
            comparison.jobIds.add(str(submitted,"id"));return submitted.put("comparisonId",comparison.id);
        }catch(Exception error){comparison.disposed=true;comparison.busy=false;cleanup(comparison);throw error;}
    }
    private synchronized void finished(Comparison comparison){comparison.busy=false;if(comparison.activeJob!=null&&!comparison.activeJob.state.equals("complete"))artifacts.discard(comparison.owner,comparison.id);comparison.activeJob=null;if(comparison.disposed||!comparison.ready||!alive.test(comparison.owner))cleanup(comparison);}
    private synchronized Comparison require(String owner,String id){human(owner);Comparison c=comparisons.get(id);if(c==null||!c.owner.equals(owner)||c.disposed)throw new SecurityException("Comparison belongs to another session or was closed");if(c.busy||!c.ready||c.diff==null)throw new IllegalArgumentException("Comparison is still running");c.expires=System.currentTimeMillis()+TTL;return c;}
    synchronized ObjectNode results(String owner,String id,int offset,int limit,String query,String status){
        Comparison c=require(owner,id);ObjectNode result=c.diff.results(offset,limit,query,status);
        for(JsonNode o:result.path("objects")){CompareData.Table data=c.data.tables.get(str(o,"id"));if(data!=null)((ObjectNode)o).set("data",data.summary());}
        result.set("source",c.from.json());result.set("destination",c.to.json());return result;
    }
    synchronized ObjectNode object(String owner,String id,String objectId){Comparison c=require(owner,id);CompareDiff.ObjectDiff object=c.diff.objects.get(objectId);if(object==null)throw new IllegalArgumentException("Unknown comparison object");ObjectNode result=object.json(true);CompareData.Table data=c.data.tables.get(objectId);if(data!=null)result.set("data",data.summary());return result;}
    synchronized ObjectNode rows(String owner,String id,String objectId,int offset,int limit,String status)throws Exception{return require(owner,id).data.page(objectId,offset,limit,status);}
    synchronized ObjectNode plan(String owner,String id,JsonNode request)throws Exception{
        Comparison c=require(owner,id);CompareSql.Plan plan=c.diff.plan(effectiveRequest(c,request));CompareDataSql.validate(plan,c.data);ObjectNode result=Profiles.JSON.createObjectNode().put("revision",c.diff.revision);
        ArrayNode statements=result.putArray("statements");for(String sql:plan.prelude)statements.add(sql);for(String sql:plan.before)statements.add(sql);for(var d:plan.data)statements.add("-- Data: "+str(d.source(),"name")+" · "+plan.mode(d));for(String sql:plan.after)statements.add(sql);for(String sql:plan.state)statements.add(sql);for(String sql:plan.finish)statements.add(sql);return result;
    }
    synchronized ObjectNode generate(String owner,String id,JsonNode request)throws Exception{
        Comparison c=require(owner,id);request=effectiveRequest(c,request);CompareSql.Plan validated=c.diff.plan(request);final JsonNode generationRequest=request;CompareDataSql.validate(validated,c.data);c.busy=true;artifacts.discard(owner,id);
        try{
            ObjectNode job=jobs.comparison(owner,new HashSet<>(List.of(c.from.connectionId(),c.to.connectionId())),"generate",running->{
                c.activeJob=running;running.comparisonMetadataBytes=c.metadataBytes;
                var captured=capturePair(running,c.from,c.to,c.options,sequenceValues(generationRequest));
                read(running,c.to,connection->{preflightColumns(running,connection,validated);return null;});
                Inventory source=captured.source(),dest=captured.destination();
                if(!source.fingerprint().equals(c.diff.source.fingerprint())||!dest.fingerprint().equals(c.diff.destination.fingerprint()))throw new IllegalArgumentException("Database definitions changed; compare again");
                if(!validated.data.isEmpty())try(CompareData fresh=new CompareData(directory,dataBudget)){
                    jobs.comparisonReads(running,c.from.connectionId(),c.to.connectionId(),
                        side->read(side,c.from,connection->{for(var choice:validated.data){CompareData.Table table=c.data.tables.get(str(choice.source(),"id"));if(table==null)throw new IllegalArgumentException("Compare table data first");CompareData.Snapshot snapshot=fresh.capture(side,connection,source.engine,table,true);if(!snapshot.fingerprint().equals(table.left.fingerprint()))throw new IllegalArgumentException("Source data changed; compare again");fresh.delete(snapshot.file());}return null;}),
                        side->read(side,c.to,connection->{for(var choice:validated.data){CompareData.Table table=c.data.tables.get(str(choice.source(),"id"));CompareData.Snapshot snapshot=fresh.capture(side,connection,source.engine,table,false);if(!snapshot.fingerprint().equals(table.right.fingerprint()))throw new IllegalArgumentException("Destination data changed; compare again");fresh.delete(snapshot.file());}return null;}));
                }
                // Refresh sequence observations without replacing the immutable reviewed definitions.
                for(var entry:c.diff.source.objects.entrySet())if((str(entry.getValue(),"kind").equals("sequences")||entry.getValue().path("autoIncrementSupported").asBoolean())&&source.objects.containsKey(entry.getKey()))entry.getValue().set("state",source.objects.get(entry.getKey()).path("state"));
                for(var entry:c.diff.destination.objects.entrySet())if((str(entry.getValue(),"kind").equals("sequences")||entry.getValue().path("autoIncrementSupported").asBoolean())&&dest.objects.containsKey(entry.getKey()))entry.getValue().set("state",dest.objects.get(entry.getKey()).path("state"));
                CompareSql.Plan plan=c.diff.plan(generationRequest);read(running,c.to,connection->{if(MysqlDialect.supports(plan.engine))MysqlGrants.validate(running,connection,plan);if(plan.engine.equals("postgresql"))PostgresCompare.validate(running,connection,plan);return null;});running.comparisonProgress("Writing ordered destination script","destination","",0,plan.selected.size());
                try(CompareArtifacts.Draft draft=artifacts.create(owner,id)){plan.header(draft.writer);CompareDataSql.write(running,plan,c.data,draft.writer);plan.footer(draft.writer);
                    synchronized(this){check(running);if(c.disposed)throw new IllegalArgumentException("Comparison closed");return draft.publish();}}
            },()->finished(c));
            c.jobIds.add(str(job,"id"));return job;
        }catch(Exception error){c.busy=false;throw error;}
    }
    static boolean structureOnly(JsonNode request){return request.path("dataMode").asText().equals("none");}
    static void validateDataMode(JsonNode request){if(request.has("dataMode")&&!structureOnly(request)&&!CompareSql.DATA_MODES.contains(request.path("dataMode").asText()))throw new IllegalArgumentException("Invalid comparison data mode");}
    static Set<String> kinds(JsonNode request){Set<String> kinds=new HashSet<>();request.path("objectTypes").forEach(n->kinds.add(n.asText()));return kinds;}
    /** Agree the dependency scope on both targets before reading any full definitions. */
    private QueryJobs.ComparisonPair<Inventory> capturePair(QueryJobs.Job job,Target from,Target to,JsonNode options,boolean values)throws Exception{
        Set<String> seeds=objectIds(options),types=kinds(options);String identitySchema=from.allSchemas()?null:from.schema();
        QueryJobs.ComparisonPair<Inventory> roster;
        while(true){
            check(job);Set<String> passSeeds=Set.copyOf(seeds),passTypes=Set.copyOf(types);
            roster=jobs.comparisonReads(job,from.connectionId(),to.connectionId(),
                side->read(side,from,c->capture(side,c,from,passTypes,values,passSeeds,identitySchema,false)),
                side->read(side,to,c->capture(side,c,to,passTypes,values,passSeeds,identitySchema,false)));
            if(roster.source().engine.equals("oracle")||roster.destination().engine.equals("oracle"))return roster;
            Set<String> union=new HashSet<>();
            for(Inventory inventory:List.of(roster.source(),roster.destination()))for(ObjectNode object:inventory.objects.values())
                union.add(TableDesigner.hash(Profiles.JSON.getNodeFactory().textNode(key(identitySchema==null?str(object,"schema"):identitySchema,str(object,"kind"),str(object,"name")))).substring(0,32));
            if(union.isEmpty())return roster;
            if(union.size()>MAX_OBJECTS)throw new IllegalArgumentException("Combined comparison dependency scope exceeds 10,000 objects");
            if(union.equals(seeds))break;
            if(!types.isEmpty()){types=Set.of();seeds=union;}
            else{if(!union.containsAll(seeds))throw new IllegalArgumentException("Database object identities changed during discovery; compare again");seeds=union;}
        }
        Set<String> finalSeeds=Set.copyOf(seeds);
        return jobs.comparisonReads(job,from.connectionId(),to.connectionId(),
            side->read(side,from,c->capture(side,c,from,Set.of(),values,finalSeeds,identitySchema)),
            side->read(side,to,c->capture(side,c,to,Set.of(),values,finalSeeds,identitySchema)));
    }
    static Set<String> objectIds(JsonNode request){Set<String> result=new HashSet<>();request.path("objectIds").forEach(n->result.add(n.asText()));return result;}
    static boolean sequenceValues(JsonNode request){
        if(!request.has("syncSequences"))return true; // Preserve older callers that choose sequence options during review.
        if(request.path("syncSequences").asBoolean())return true;
        for(JsonNode object:request.path("objects"))if(object.path("syncValues").asBoolean())return true;
        return false;
    }
    static JsonNode effectiveRequest(Comparison comparison,JsonNode request){
        validateDataMode(request);ObjectNode effective=request.deepCopy();
        if(structureOnly(comparison.options)||structureOnly(request)){
            effective.put("dataMode","none");for(JsonNode object:effective.path("objects"))((ObjectNode)object).put("includeData",false);
        }
        return effective;
    }
    static void preflightColumns(QueryJobs.Job job,Connection connection,CompareSql.Plan plan)throws Exception{
        for(var choice:plan.selected.values())if(str(choice.source(),"kind").equals("tables")&&choice.destination()!=null){
            Map<String,JsonNode> old=CompareSql.byName(choice.destination().path("columns"));for(JsonNode col:choice.source().path("columns")){
                JsonNode before=old.get(str(col,"name"));if(before==null||!before.path("nullable").asBoolean()||col.path("nullable").asBoolean())continue;
                String sql="SELECT 1 FROM "+plan.target(choice.source())+" WHERE "+CompareSql.q(plan.engine,str(col,"name"))+" IS NULL";
                try(Statement statement=connection.createStatement()){job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());statement.setMaxRows(1);try(ResultSet result=statement.executeQuery(sql)){if(result.next())throw new IllegalArgumentException("Backfill null values before making "+str(choice.source(),"name")+"."+str(col,"name")+" required");}}finally{job.statement=null;}
            }
        }
    }
    synchronized void remove(String owner,String id){Comparison c=comparisons.get(id);if(c==null)return;if(!c.owner.equals(owner))throw new SecurityException("Comparison belongs to another session");c.disposed=true;artifacts.discard(owner,id);for(String job:c.jobIds)try{jobs.cancel(jobs.require(owner,job));}catch(IllegalArgumentException ignored){}if(!c.busy)cleanup(c);}
    private void cleanup(Comparison c){comparisons.remove(c.id);if(c.data!=null)c.data.close();c.lease.close();artifacts.discard(c.owner,c.id);}
    synchronized void reap(){long now=System.currentTimeMillis();receipts.values().removeIf(r->r.expires<now||!alive.test(r.owner));for(Comparison c:new ArrayList<>(comparisons.values()))if(!alive.test(c.owner)||!c.busy&&c.expires<now)remove(c.owner,c.id);artifacts.reap();}
    synchronized ObjectNode telemetry(){ObjectNode n=artifacts.telemetry();return n.put("comparisons",comparisons.size()).put("dataDiskAllowance",dataBudget.maximum).put("dataDiskBytes",dataBudget.used());}
    @Override public synchronized void close(){closed=true;receipts.clear();for(Comparison c:new ArrayList<>(comparisons.values()))remove(c.owner,c.id);artifacts.close();}
}
