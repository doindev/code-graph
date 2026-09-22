package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Browser-owned, bounded original row snapshots. No browser-supplied SQL can authorize a save. */
final class GridResults implements AutoCloseable {
    static final int MAX_CONTEXTS=128;
    final QueryJobs jobs;
    final Connections connections;
    final Supplier<DbaConfig> config;
    final Predicate<String> alive;
    final GridExports exports;
    final GridPageCache pages;
    final Map<String,Context> contexts=new LinkedHashMap<>();
    private final Path audit;
    static final class Context {
        final String id=UUID.randomUUID().toString(),owner,connection,profileRevision,database,schema,sql,originJob;
        final JsonNode parameters; final long baseBytes;
        final QueryJobs.RetainedReservation reservation;
        ObjectNode result;
        GridRelation relation;
        String reason="",orderedSql,countSql,countReason="Exact counts are unavailable for this result.";
        List<String> rowIds=new ArrayList<>();
        long revision=1,offset,capturedAt=System.currentTimeMillis();
        boolean cacheHit,refreshRequired;
        Long total; long totalAt;
        int limit;
        boolean ready,busy,disposed,uncertain,hasMore,fullExport,canLimitRows;
        QueryJobs.Job activeJob;
        Plan plan;
        Context(String owner,String connection,String profileRevision,String database,String schema,String sql,JsonNode parameters,String originJob,QueryJobs.RetainedReservation reservation){
            this.owner=owner;this.connection=connection;this.profileRevision=profileRevision;this.database=database;this.schema=schema;this.sql=sql;this.parameters=parameters.deepCopy();this.baseBytes=baseBytes(sql,parameters);this.originJob=originJob;this.reservation=reservation;
        }
    }
    record Binding(JsonNode value,int type){}
    record Command(String sql,List<Binding> values,String rowId,String operation){}
    record Plan(String id,long revision,long expires,List<Command> commands,boolean deletes){}
    private static long baseBytes(String sql,JsonNode parameters){return 262144+sql.length()*6L+parameters.toString().length()*3L;}
    private static String catalog(Connection c){try{return Objects.toString(c.getCatalog(),"");}catch(SQLException|UnsupportedOperationException ignored){return "";}}
    private static String schema(Connection c){try{return Objects.toString(c.getSchema(),"");}catch(SQLException|UnsupportedOperationException|AbstractMethodError ignored){return "";}}
    static boolean sqlExport(Context c){return c.relation!=null&&!c.result.path("cellsTruncated").asBoolean()&&c.relation.columns.stream().noneMatch(GridRelation.Column::generated);}
    synchronized ObjectNode status(String owner,String id){return descriptor(require(owner,id));}
    synchronized ObjectNode telemetry(){var out=Profiles.JSON.createObjectNode().put("contexts",contexts.size()).put("maximumContexts",MAX_CONTEXTS);out.set("exports",exports.telemetry());out.set("pageCache",pages.telemetry());return out;}
    GridResults(QueryJobs jobs,Connections connections,Supplier<DbaConfig> config,Predicate<String> alive)throws java.io.IOException{this.jobs=jobs;this.connections=connections;this.config=config;this.alive=alive;this.audit=config.get().directory().resolve("browser-grid-audit.jsonl");this.exports=new GridExports(this);this.pages=new GridPageCache(jobs,()->config.get().memoryBytes());}
    void capture(QueryJobs.Job job,Connection c,ObjectNode output,JsonNode parameters)throws Exception {
        if(job.owner.startsWith("agent:"))return;
        for(JsonNode item:output.path("results")){
            if(!item.path("kind").asText().equals("rows"))continue;ObjectNode row=(ObjectNode)item;JsonNode source=null;
            for(JsonNode statement:output.path("statements"))if(statement.path("index").asInt()==row.path("statementIndex").asInt()){source=statement;break;}
            if(source==null)continue;String sql=source.path("sql").asText();ArrayNode values=Profiles.JSON.createArrayNode();
            if(parameters.size()>0){if(!source.has("parameterOffset")||!source.has("parameterCount"))continue;int start=source.path("parameterOffset").asInt(),count=source.path("parameterCount").asInt();if(start<0||count<0||start+count>parameters.size())continue;for(int i=start;i<start+count;i++)values.add(parameters.get(i));}
            QueryJobs.RetainedReservation reservation=null;
            try{
                synchronized(this){if(contexts.size()>=MAX_CONTEXTS)throw new IllegalArgumentException("Close result tabs before creating more grid contexts.");}
                reservation=jobs.retainAllowance(Math.max(4096,Profiles.JSON.writeValueAsBytes(row).length*3L+baseBytes(sql,values)));
                Context context=new Context(job.owner,job.connection,ProjectContexts.profileRevision(connections.profile(job.connection)),catalog(c),schema(c),sql,values,job.id,reservation);
                context.result=row.deepCopy();context.limit=job.rowLimit;context.hasMore=row.path("truncated").asBoolean();
                try{validateReload(context);context.canLimitRows=true;}catch(IllegalArgumentException unsupported){context.canLimitRows=false;}
                try{SqlReadGuard.validate(sql);context.fullExport=output.path("statements").size()==1&&GridRelation.ENGINES.contains(GridRelation.engine(c.getMetaData().getDatabaseProductName()));}catch(Exception unsupported){context.fullExport=false;}
                try{context.countSql=GridCounts.sql(context,c);}catch(Exception unsupported){context.countReason=unsupported.getMessage();}
                Savepoint observation=null;
                try{
                    // Connector/J rejects SAVEPOINT through a read-only connection.
                    // MySQL/MariaDB metadata statement errors do not poison a read-only
                    // transaction. Human write scripts still get their isolation savepoint.
                    boolean readOnlyMysql=c.isReadOnly()&&Set.of("mysql","mariadb").contains(GridRelation.engine(c.getMetaData().getDatabaseProductName()));
                    if(!c.getAutoCommit()&&!readOnlyMysql){if(!c.getMetaData().supportsSavepoints())throw new IllegalArgumentException("Grid target inspection requires savepoints on this transactional connection.");observation=c.setSavepoint();}
                    context.relation=GridRelation.inspect(c,sql,row);
                    if(output.path("statements").size()!=1)throw new IllegalArgumentException("Rerun this SELECT separately to enable server paging; script results preserve their original statement snapshot.");
                    context.orderedSql=GridPaging.ordered(context.relation,c.getMetaData());
                    ObjectNode ordered=GridPaging.read(context,job,c,0,context.limit);
                    ordered.set("columns",row.path("columns").deepCopy());ordered.put("statementIndex",row.path("statementIndex").asInt());
                    reservation.resize(Math.max(4096,Profiles.JSON.writeValueAsBytes(ordered).length*3L+context.baseBytes));
                    row.removeAll();row.setAll(ordered);context.result=ordered.deepCopy();context.hasMore=ordered.path("truncated").asBoolean();
                }catch(Exception unavailable){
                    if(observation!=null)c.rollback(observation);
                    if(job.cancelled||unavailable instanceof CancellationException)throw unavailable;
                    context.orderedSql=null;context.reason=unavailable instanceof IllegalArgumentException?unavailable.getMessage():"Metadata could not establish a safe editable target; check database permissions.";
                }finally{if(observation!=null)try{c.releaseSavepoint(observation);}catch(SQLException ignored){}}
                for(JsonNode ignored:row.path("rows"))context.rowIds.add(UUID.randomUUID().toString());
                synchronized(this){if(contexts.size()>=MAX_CONTEXTS)throw new IllegalArgumentException("Grid context allowance full.");contexts.put(context.id,context);}
                row.set("grid",descriptor(context));reservation=null;
            }catch(IllegalArgumentException unavailable){row.put("gridUnavailable",unavailable.getMessage());}
            finally{if(reservation!=null)reservation.close();}
        }
    }
    synchronized void finished(QueryJobs.Job job){for(Context c:new ArrayList<>(contexts.values()))if(c.originJob.equals(job.id)){if(job.state.equals("complete")){c.ready=true;pages.put(c);}else dispose(c);}}
    synchronized ObjectNode descriptor(Context c){
        ObjectNode out=Profiles.JSON.createObjectNode().put("id",c.id).put("revision",c.revision).put("rowCeiling",config.get().uiRows()).put("connectionId",c.connection).put("connectionName",connections.profile(c.connection).path("name").asText()).put("database",c.database).put("schema",c.schema).put("uncertain",c.uncertain).put("refreshRequired",c.refreshRequired).put("busy",c.busy);
        out.putArray("rowIds").addAll(c.rowIds.stream().map(TextNode::valueOf).toList());
        out.putObject("page").put("offset",c.offset).put("limit",c.limit).put("hasMore",c.hasMore).put("capturedAt",c.capturedAt).put("cached",c.cacheHit);
        if(c.total!=null)((ObjectNode)out.path("page")).put("total",c.total).put("totalCapturedAt",c.totalAt);
        // A bounded replay of one SELECT does not require a unique key or verified paging.
        out.putObject("capabilities").put("edit",c.relation!=null&&!c.refreshRequired&&!c.uncertain&&!c.result.path("cellsTruncated").asBoolean()).put("delete",c.relation!=null&&!c.refreshRequired&&!c.uncertain&&!c.result.path("cellsTruncated").asBoolean()).put("insert",c.relation!=null&&c.relation.insert&&!c.refreshRequired&&!c.uncertain&&!c.result.path("cellsTruncated").asBoolean()).put("page",c.orderedSql!=null).put("fullExport",c.fullExport).put("sqlExport",sqlExport(c)).put("reason",c.result.path("cellsTruncated").asBoolean()?"Truncated values make this page read-only.":c.reason).put("sqlExportReason",sqlExport(c)?"":"SQL export needs a verified target without generated/identity columns or truncated values.").put("pageReason",c.orderedSql==null?c.reason:"");
        ((ObjectNode)out.path("capabilities")).put("count",c.countSql!=null).put("countReason",c.countSql==null?c.countReason:"");
        ((ObjectNode)out.path("capabilities")).put("rowLimit",c.orderedSql!=null||c.canLimitRows).put("rowLimitReason",c.canLimitRows||c.orderedSql!=null?"":"This result cannot safely replay one SELECT; rerun it from the SQL editor.");
        if(c.relation!=null){out.set("columns",c.relation.descriptor().path("columns"));out.put("table",c.relation.table);}else out.putArray("columns");
        return out;
    }
    static void validateReload(Context c){
        ObjectNode request=Profiles.JSON.createObjectNode().put("sql",c.sql).put("action","refresh");
        request.set("parameters",c.parameters);GridSql.prepare(request);
    }
    synchronized Context require(String owner,String id){Context c=contexts.get(id);if(c==null||!c.owner.equals(owner)||!alive.test(owner)||c.disposed||!c.ready)throw new SecurityException("Grid is unavailable or not owned by this browser session.");if(!c.profileRevision.equals(ProjectContexts.profileRevision(connections.profile(c.connection))))throw new IllegalArgumentException("Connection changed; rerun the query before using this grid.");return c;}
    void check(Context c,QueryJobs.Job job){if(job.cancelled||c.disposed||!alive.test(c.owner))throw new CancellationException();require(c.owner,c.id);}
    synchronized ObjectNode operation(String owner,String id,String action,JsonNode input){
        Context c=require(owner,id);if(c.busy)throw new IllegalArgumentException("This grid already has an active operation.");
        if(c.refreshRequired&&Set.of("prepare","apply").contains(action))throw new IllegalArgumentException("Changes were saved. Refresh this grid before editing again.");
        if(c.uncertain&&!action.equals("reconcile"))throw new IllegalArgumentException("Save outcome is unknown. Reconcile with the database before retrying.");
        if(input.path("revision").asLong(-1)!=c.revision)throw new IllegalArgumentException("Stale grid revision; refresh before retrying.");
        pages.reap();
        boolean window=action.equals("page")&&input.path("direction").asText().equals("window");
        if(window)GridPaging.windowOffset(this,c,input);
        if(action.equals("prepare")||action.equals("apply")||action.equals("reconcile")||action.equals("reload")
                ||action.equals("page")&&(Set.of("first","refresh").contains(input.path("direction").asText("refresh"))||input.path("limit").asInt(c.limit)!=c.limit))pages.removeContext(c.id);
        if(action.equals("apply"))pages.close(); // Writes can invalidate cached pages of other grids too.
        c.busy=true;JsonNode request=input.deepCopy();
        return jobs.local(owner,c.connection,job->{
            c.activeJob=job;
            if(window){var cached=pages.get(c.id,request.path("offset").asLong(),c.limit);if(cached!=null)return GridPaging.publishWindow(this,c,job,cached,true);}
            try(var target=connections.target(c.connection,c.database)){
            c.activeJob=job;Connection connection=target.connection();
            // Target discovery can already have started a driver transaction. End that read
            // before choosing isolation; PostgreSQL rejects isolation changes mid-transaction.
            if(!connection.getAutoCommit())connection.rollback();
            connection.setAutoCommit(true);Connections.selectSchema(connection,c.schema);connection.setReadOnly(action.equals("values")||action.equals("count"));
            if(action.equals("page")||action.equals("reconcile"))connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try{check(c,job);return switch(action){case "count"->GridCounts.count(this,c,job,connection);case "values"->GridValues.read(this,c,job,connection,request);case "prepare"->prepare(c,connection,request);case "apply"->apply(c,job,connection,request);case "export"->exports.create(c,job,connection,request);case "reload"->GridPaging.reload(this,c,job,connection,request);case "page","reconcile"->window?GridPaging.window(this,c,job,connection,request):action.equals("reconcile")&&c.orderedSql==null?GridPaging.reload(this,c,job,connection,request):GridPaging.page(this,c,job,connection,request,action.equals("reconcile"));default->throw new IllegalArgumentException("Unsupported grid operation");};}
            catch(Exception error){if(!c.uncertain)try{connection.rollback();}catch(SQLException rollback){if(!Set.of("values","count").contains(action))c.uncertain=true;}if(c.uncertain)job.outcome="unknown";else if(action.equals("apply"))job.outcome="rolled_back";if(error instanceof SQLException)throw new IllegalArgumentException(connections.humanError(c.connection,error),error);throw error;}
            finally{if(action.equals("apply"))pages.close();job.statement=null;try{connection.rollback();}catch(SQLException ignored){}}
        }},()->{synchronized(this){if(c.activeJob!=null&&c.activeJob.cancelled)exports.cancelled(c.activeJob.id);c.activeJob=null;c.busy=false;if(c.disposed)c.reservation.close();}});
    }
    ObjectNode prepare(Context c,Connection connection,JsonNode input)throws Exception {
        if(c.relation==null)throw new IllegalArgumentException(c.reason);
        GridRelation current=GridRelation.inspect(connection,c.sql,c.result);if(!current.fingerprint.equals(c.relation.fingerprint))throw new IllegalArgumentException("Table definition changed; reload before saving.");
        JsonNode changes=input.path("changes");if(!changes.isArray()||changes.isEmpty()||changes.size()>Math.min(10000,config.get().uiRows()))throw new IllegalArgumentException("Supply a bounded, non-empty row draft.");
        List<Command> commands=new ArrayList<>();Set<String> seen=new HashSet<>();boolean deletes=false;
        for(JsonNode change:changes){String rowId=Profiles.text(change,"rowId",80),operation=Profiles.text(change,"operation",10);
            if(!seen.add(rowId)||!Set.of("delete","update","insert").contains(operation))throw new IllegalArgumentException("Invalid or repeated row action.");
            int index=c.rowIds.indexOf(rowId);if(!operation.equals("insert")&&index<0)throw new IllegalArgumentException("Unknown original row.");
            if(operation.equals("insert")&&(!rowId.startsWith("new:")||!current.insert))throw new IllegalArgumentException("This result cannot insert a complete row.");
            commands.add(command(connection,current,change,index<0?null:c.result.path("rows").get(index)));deletes|=operation.equals("delete");
        }
        commands.sort(Comparator.comparingInt(command->switch(command.operation){case "delete"->0;case "update"->1;default->2;}));
        long bytes=commands.stream().mapToLong(command->command.sql.length()*2L+command.values.toString().length()*2L).sum();
        if(bytes>1<<20)throw new IllegalArgumentException("Prepared grid save exceeds 1 MiB.");
        c.reservation.resize(Math.max(4096,Profiles.JSON.writeValueAsBytes(c.result).length*3L+c.baseBytes+bytes*3));
        c.plan=new Plan(UUID.randomUUID().toString(),c.revision,System.currentTimeMillis()+300000,List.copyOf(commands),deletes);
        ObjectNode response=Profiles.JSON.createObjectNode().put("planId",c.plan.id).put("revision",c.revision).put("deletes",deletes).put("atomic",true).put("connectionName",connections.profile(c.connection).path("name").asText()).put("database",c.database).put("target",current.qualified);
        var statements=response.putArray("statements");for(Command command:commands){ObjectNode statement=statements.addObject().put("sql",command.sql).put("operation",command.operation);ArrayNode parameters=statement.putArray("parameters");for(Binding b:command.values)parameters.add(b.value);}
        return response.put("warning","Triggers/cascades may affect additional data. Key swaps or dependency-sensitive changes can fail and roll back.");
    }
    static Command command(Connection c,GridRelation relation,JsonNode change,JsonNode original)throws Exception {
        String op=change.path("operation").asText();JsonNode edited=change.path("values");if(!edited.isObject())throw new IllegalArgumentException("Expected column values.");Map<String,GridRelation.Column> columns=new HashMap<>();for(var column:relation.columns)columns.put(column.id(),column);
        var names=new ArrayList<String>();var assignments=new ArrayList<String>();var placeholders=new ArrayList<String>();var parameters=new ArrayList<Binding>();
        for(var entry:edited.properties()){var column=columns.get(entry.getKey());if(column==null||column.generated()||op.equals("delete"))throw new IllegalArgumentException("Unknown or generated column cannot be edited.");
            String kind=entry.getValue().path("kind").asText();String quoted=GridRelation.quote(c.getMetaData(),column.name());
            if(!Set.of("value","null","default").contains(kind))throw new IllegalArgumentException("Invalid cell value mode.");
            if(kind.equals("default")&&!column.hasDefault())throw new IllegalArgumentException("Column has no default.");
            if(kind.equals("null")&&!column.nullable())throw new IllegalArgumentException("Column does not allow NULL.");
            JsonNode value=kind.equals("null")?NullNode.instance:entry.getValue().path("value");
            if(kind.equals("value")&&(!value.isTextual()||value.asText().length()>8192))throw new IllegalArgumentException("Cell text exceeds its allowance.");
            if(!kind.equals("default"))GridRelation.validate(column,value);
            String expression=kind.equals("default")?"DEFAULT":"?";if(!kind.equals("default"))parameters.add(new Binding(value,column.type()));
            names.add(quoted);assignments.add(quoted+" = "+expression);placeholders.add(expression);
        }
        String sql;
        if(op.equals("insert")){for(var col:relation.columns)if(!col.generated()&&!col.nullable()&&!col.hasDefault()&&!edited.has(col.id()))throw new IllegalArgumentException("Required column is missing: "+col.name());
            sql=names.isEmpty()?relation.engine.equals("mysql")||relation.engine.equals("mariadb")?"INSERT INTO "+relation.qualified+" () VALUES ()":"INSERT INTO "+relation.qualified+" DEFAULT VALUES":"INSERT INTO "+relation.qualified+" ("+String.join(", ",names)+") VALUES ("+String.join(", ",placeholders)+")";
        }else{if(op.equals("update")&&assignments.isEmpty())throw new IllegalArgumentException("No changed values.");var predicates=new ArrayList<String>();
            for(var col:relation.columns){JsonNode value=original.get(col.source());String name=GridRelation.quote(c.getMetaData(),col.name());predicates.add(name+(value.isNull()?" IS NULL":" = ?"));if(!value.isNull())parameters.add(new Binding(value,col.type()));}
            sql=(op.equals("delete")?"DELETE FROM "+relation.qualified:"UPDATE "+relation.qualified+" SET "+String.join(", ",assignments))+" WHERE "+String.join(" AND ",predicates);
        }
        return new Command(sql,List.copyOf(parameters),change.path("rowId").asText(),op);
    }
    ObjectNode apply(Context c,QueryJobs.Job job,Connection connection,JsonNode request)throws Exception {
        Plan plan=c.plan;if(plan==null||!plan.id.equals(request.path("planId").asText())||plan.revision!=c.revision||plan.expires<System.currentTimeMillis())throw new IllegalArgumentException("Save review is stale; prepare changes again.");
        if(plan.deletes&&!request.path("confirmed").asBoolean())throw new IllegalArgumentException("Confirm deletion before saving.");
        check(c,job);lockTarget(c,job,connection);
        if(!GridRelation.inspect(connection,c.sql,c.result).fingerprint.equals(c.relation.fingerprint))throw new IllegalArgumentException("Table definition changed; no edits were applied.");
        c.plan=null;boolean committing=false;try{
            audit(c,plan);for(Command command:plan.commands){check(c,job);if(!command.operation.equals("insert"))lockOriginal(c,job,connection,command.rowId);
                try(PreparedStatement statement=connection.prepareStatement(command.sql)){job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());int at=1;for(Binding b:command.values)GridRelation.bind(statement,at++,b.value,b.type);if(statement.executeLargeUpdate()!=1)throw new IllegalArgumentException("Row conflict: exactly one row must match each change. All changes were rolled back.");}}
            check(c,job);committing=true;connection.commit();job.outcome="commit_acknowledged";c.revision++;c.refreshRequired=true;c.total=null;c.totalAt=0;
            return Profiles.JSON.createObjectNode().put("saved",true).put("refreshRequired",true).put("revision",c.revision).put("outcome","commit_acknowledged").put("message","Changes saved. Refresh to read authoritative values.");
        }catch(Exception error){if(committing){c.uncertain=true;job.outcome="unknown";throw new IllegalArgumentException("Commit outcome is unknown. Reconcile this grid with the database before any retry.",error);}else{try{connection.rollback();job.outcome="rolled_back";}catch(SQLException failed){c.uncertain=true;job.outcome="unknown";}}throw error;}
    }
    private void lockTarget(Context c,QueryJobs.Job job,Connection connection)throws Exception{
        GridRelation r=c.relation;String key=GridRelation.quote(connection.getMetaData(),r.keys.getFirst());
        // Acquire target locks before the final fingerprint check. Never use MySQL
        // LOCK TABLES (which can implicitly commit). SQL Server takes an explicit
        // table lock; ordinary saves may therefore block other sessions until commit.
        String sql=r.engine.equals("sqlserver")?"SELECT TOP (1) "+key+" FROM "+r.qualified+" WITH (TABLOCKX,HOLDLOCK)"
                :"SELECT "+key+" FROM "+r.qualified+" LIMIT 1 FOR UPDATE";
        try(var statement=connection.prepareStatement(sql)){job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());
            try(var rows=statement.executeQuery()){if(rows.next())rows.getObject(1);}
        }finally{job.statement=null;}
    }
    private void lockOriginal(Context c,QueryJobs.Job job,Connection connection,String rowId)throws Exception{
        GridRelation r=c.relation;JsonNode original=c.result.path("rows").get(c.rowIds.indexOf(rowId));var selected=new ArrayList<String>();var predicates=new ArrayList<String>();var values=new ArrayList<Binding>();
        for(var col:r.columns){String name=GridRelation.quote(connection.getMetaData(),col.name());selected.add(name);if(r.keys.contains(col.name())){predicates.add(name+" = ?");values.add(new Binding(original.get(col.source()),col.type()));}}
        String sql="SELECT "+String.join(", ",selected)+" FROM "+r.qualified+(r.engine.equals("sqlserver")?" WITH (UPDLOCK,HOLDLOCK)":"")+" WHERE "+String.join(" AND ",predicates)+(r.engine.equals("sqlserver")?"":" FOR UPDATE");
        try(var statement=connection.prepareStatement(sql)){job.statement=statement;statement.setQueryTimeout(job.remainingSeconds());int at=1;for(Binding b:values)GridRelation.bind(statement,at++,b.value,b.type);
            try(var rs=statement.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Row was deleted or its key changed. Cancel/reload to reconcile.");for(var col:r.columns){String current=rs.getString(col.source()+1);JsonNode old=original.get(col.source());if(!Objects.equals(current,old.isNull()?null:old.asText()))throw new IllegalArgumentException("Row was changed by another transaction. All edits were rolled back.");}if(rs.next())throw new IllegalArgumentException("Row identity is not unique.");}}
    }
    private synchronized void audit(Context c,Plan plan)throws Exception {if(Files.exists(audit)&&Files.size(audit)>4L<<20)Files.move(audit,audit.resolveSibling("browser-grid-audit.previous.jsonl"),StandardCopyOption.REPLACE_EXISTING);if(!Files.exists(audit)){Files.createFile(audit);Profiles.protect(audit);}ObjectNode event=Profiles.JSON.createObjectNode().put("at",System.currentTimeMillis()).put("connectionId",c.connection).put("targetHash",CatalogScanner.hash(c.database+"|"+c.schema+"|"+c.relation.table)).put("planId",plan.id).put("changeCount",plan.commands.size()).put("action","browser_grid_save");Files.writeString(audit,event+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND);}
    synchronized void release(String owner,String id){Context c=contexts.get(id);if(c==null)return;if(!c.owner.equals(owner))throw new SecurityException("Grid is not owned by this session.");dispose(c);}
    private void dispose(Context c){c.disposed=true;pages.removeContext(c.id);contexts.remove(c.id);if(c.activeJob!=null)jobs.cancel(c.activeJob);if(!c.busy)c.reservation.close();}
    synchronized void reap(){for(Context c:new ArrayList<>(contexts.values()))if(!alive.test(c.owner))dispose(c);pages.reap();exports.reap();}
    public synchronized void close(){for(Context c:new ArrayList<>(contexts.values()))dispose(c);pages.close();exports.close();}
}
