package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

/** Fixed admission queue, bounded result retention and no result files. */
final class QueryJobs implements AutoCloseable {
    private static final long JOB_RESERVATION=12L<<20;
    private static final int MAX_CELL=8192,MAX_COLUMNS=256;
    final class Job {
        final String id=UUID.randomUUID().toString(),owner,connection;
        final int rowLimit,byteLimit;
        final long created=System.currentTimeMillis();
        volatile long started,finished,bytes;
        volatile String state="queued",error="";
        volatile Statement statement;
        volatile String progress="";
        volatile Thread thread;
        volatile boolean cancelled;
        volatile JsonNode result;
        volatile JsonNode exception;
        volatile ObjectNode decision;
        volatile String outcome="";
        boolean designerPlanUsed;
        private String decisionAction="";
        private ScheduledFuture<?> activeDeadline;
        private long activeRemainingNanos,activeStartedNanos;
        Job(String owner,String connection){this.owner=owner;this.connection=connection;rowLimit=owner.startsWith("agent:")?Math.min(100,config.agentRows()):config.uiRows();byteLimit=owner.startsWith("agent:")?1<<20:4<<20;}
        ObjectNode json(){long completed=finished;ObjectNode n=Profiles.JSON.createObjectNode().put("id",id).put("state",state).put("created",created).put("started",started).put("finished",completed).put("bytes",bytes).put("error",error).put("progress",progress);if(!outcome.isEmpty())n.put("outcome",outcome);if(result!=null)n.set("result",result);if(exception!=null)n.set("exception",exception);if(decision!=null)n.set("decision",decision.deepCopy());return n;}
        synchronized void beginActiveBudget(int seconds){activeRemainingNanos=TimeUnit.SECONDS.toNanos(seconds);resumeActiveBudget();}
        synchronized void pauseActiveBudget(){if(activeStartedNanos==0)return;activeRemainingNanos=Math.max(0,activeRemainingNanos-(System.nanoTime()-activeStartedNanos));activeStartedNanos=0;if(activeDeadline!=null)activeDeadline.cancel(false);activeDeadline=null;}
        synchronized void resumeActiveBudget(){if(cancelled)return;if(activeRemainingNanos<=0){QueryJobs.this.cancel(this);return;}activeStartedNanos=System.nanoTime();activeDeadline=timer.schedule(()->QueryJobs.this.cancel(this),activeRemainingNanos,TimeUnit.NANOSECONDS);}
        synchronized void stopActiveBudget(){pauseActiveBudget();activeRemainingNanos=0;}
        synchronized int remainingSeconds(){if(activeRemainingNanos==0&&activeStartedNanos==0)return config.timeoutSeconds();long remaining=activeRemainingNanos;if(activeStartedNanos!=0)remaining-=System.nanoTime()-activeStartedNanos;return (int)Math.max(1,Math.min(300,TimeUnit.NANOSECONDS.toSeconds(Math.max(0,remaining)+999_999_999L)));}
        synchronized String awaitDecision(ObjectNode prompt,int seconds)throws InterruptedException {
            pauseActiveBudget();decisionAction="";long expires=System.currentTimeMillis()+seconds*1000L;prompt.put("expiresAt",expires);decision=prompt;state="awaiting_decision";
            while(decisionAction.isEmpty()&&!cancelled){long remaining=expires-System.currentTimeMillis();if(remaining<=0)break;wait(remaining);}
            String action=cancelled||decisionAction.isEmpty()?"cancel":decisionAction;if(decisionAction.isEmpty())cancelled=true;decision=null;decisionAction="";
            if(!cancelled){state="running";resumeActiveBudget();}return action;
        }
        synchronized void decide(String decisionId,String action){if(decision==null||!state.equals("awaiting_decision"))throw new IllegalArgumentException("Job is not awaiting a decision");if(!decision.path("id").asText().equals(decisionId))throw new IllegalArgumentException("Stale SQL error decision");boolean allowed=false;for(JsonNode candidate:decision.path("actions"))if(candidate.asText().equals(action)){allowed=true;break;}if(!allowed)throw new IllegalArgumentException("Decision is not safe for this statement");decisionAction=action;notifyAll();}
    }
    interface Task {JsonNode run(Job job,Connection connection)throws Exception;}
    private interface CatalogTask extends Task {}
    interface LocalTask {JsonNode run(Job job)throws Exception;}
    private final Connections connections;
    private final Predicate<String> alive;
    private final Map<String,Job> jobs=new LinkedHashMap<>();
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("dba-deadlines").factory());
    private volatile DbaConfig config;
    QueryJobs(Connections connections,DbaConfig config,Predicate<String> alive){this.connections=connections;this.config=config;this.alive=alive;
        workers=new ThreadPoolExecutor(config.concurrency(),config.concurrency(),30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),Thread.ofPlatform().daemon().name("dba-query-",0).factory(),new ThreadPoolExecutor.AbortPolicy());
        timer.scheduleAtFixedRate(this::reap,1,1,TimeUnit.SECONDS);
    }
    synchronized ObjectNode submit(String owner,String connection,Task task) {
        return submit(owner,connection,task,false,false);
    }
    private synchronized ObjectNode submit(String owner,String connection,Task task,boolean human,boolean autoCommit) {
        reap();if(jobs.size()>=32 || (jobs.size()+1)*JOB_RESERVATION>config.memoryBytes())throw new IllegalArgumentException("DBA result allowance full; close results before submitting more work");
        Job job=new Job(owner,connection);jobs.put(job.id,job);
        try{workers.execute(()->{if(human)runHuman(job,task,autoCommit);else run(job,task);});}catch(RejectedExecutionException e){jobs.remove(job.id);throw new IllegalArgumentException("DBA queue full");}
        return job.json();
    }
    private void runHuman(Job job,Task task,boolean autoCommit){
        if(job.cancelled||!alive.test(job.owner)){job.state="cancelled";job.finished=System.currentTimeMillis();return;}
        job.started=System.currentTimeMillis();job.state="running";job.outcome="not_started";
        Connection c=null;boolean executed=false,committing=false;
        try{
            job.beginActiveBudget(config.timeoutSeconds());
            c=connections.open(job.connection);c.setReadOnly(false);c.setAutoCommit(autoCommit);
            if(c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"))try(Statement setup=c.createStatement()){
                setup.execute((autoCommit?"SET ":"SET LOCAL ")+"statement_timeout = "+config.timeoutSeconds()*1000);
                setup.execute((autoCommit?"SET ":"SET LOCAL ")+"lock_timeout = 3000");
            }
            if(job.cancelled||!alive.test(job.owner))throw new CancellationException();
            executed=true;ObjectNode result=(ObjectNode)task.run(job,c);
            if(Profiles.JSON.writeValueAsBytes(result).length>job.byteLimit-8192)throw new IllegalArgumentException("Result exceeds byte allowance");
            synchronized(job){if(job.cancelled||!alive.test(job.owner))throw new CancellationException();committing=true;}
            if(!autoCommit)c.commit();
            job.outcome=autoCommit?"auto_commit_completed":"commit_acknowledged";
            result.put("outcome",job.outcome).put("message",autoCommit?"SQL completed in auto-commit mode.":"SQL completed; commit acknowledged.");
            job.result=result;job.bytes=Profiles.JSON.writeValueAsBytes(result).length;job.state="complete";
        }catch(HumanSql.ScriptCancelled e){
            job.exception=exceptionInfo(e);job.result=e.partial();job.bytes=safeSize(e.partial());job.outcome=!executed?"not_started":autoCommit?"partial_or_unknown":"rollback_requested";
            if(c!=null&&!autoCommit&&!committing)try{c.rollback();}catch(SQLException rollback){job.outcome="unknown";}
            job.state="cancelled";job.error=e.getMessage()+" "+(autoCommit?"Earlier statements may already have committed.":"Rollback requested; nontransactional operations or vendor auto-committing DDL may already have taken effect.");
        }catch(Exception e){
            job.exception=exceptionInfo(e);job.result=null;job.bytes=0;
            job.outcome=!executed?"not_started":autoCommit?"partial_or_unknown":committing?"unknown":"rollback_requested";
            if(c!=null&&!autoCommit&&!committing)try{c.rollback();}catch(SQLException rollback){job.outcome="unknown";}
            job.state=job.cancelled&&!committing?"cancelled":"failed";
            job.error=(job.cancelled?"Cancelled or timed out":connections.humanError(job.connection,e))+". "+switch(job.outcome){
                case "not_started"->"SQL was not started.";
                case "rollback_requested"->"Rollback requested. Explicit commits, nontransactional operations, or vendor auto-committing DDL may already have taken effect.";
                default->"Changes may already have committed; inspect the database before retrying.";
            };
        }finally{
            job.stopActiveBudget();job.statement=null;
            if(c!=null)try{connections.discard(job.connection,c);}catch(Exception ignored){}
            job.finished=System.currentTimeMillis();
        }
    }
    private void run(Job job,Task task){
        if(job.cancelled||!alive.test(job.owner)){job.state="cancelled";job.finished=System.currentTimeMillis();return;}
        job.started=System.currentTimeMillis();job.state="running";
        ScheduledFuture<?> deadline=timer.schedule(()->cancel(job),config.timeoutSeconds(),TimeUnit.SECONDS);
        try(Connection c=connections.open(job.connection)){
            if(job.cancelled)throw new CancellationException();
            c.setAutoCommit(false);
            // CatalogTask accepts no supplied SQL: its fixed catalog reads remain safe on drivers
            // that cannot change JDBC readOnly (notably SQLite and DuckDB).
            if(!(task instanceof CatalogTask))c.setReadOnly(true);
            boolean postgres=c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL");
            if(postgres){try(Statement setup=c.createStatement()){setup.execute("SET LOCAL statement_timeout = "+(config.timeoutSeconds()*1000));setup.execute("SET LOCAL lock_timeout = 3000");setup.execute("SET TRANSACTION READ ONLY");if(job.owner.startsWith("agent:"))setup.execute("SET LOCAL search_path = pg_catalog");}}
            try {JsonNode result=task.run(job,c);if(job.cancelled||!alive.test(job.owner))throw new CancellationException();byte[] serialized=Profiles.JSON.writeValueAsBytes(result);
                if(serialized.length>job.byteLimit)throw new IllegalArgumentException("Result exceeds byte limit");
                job.bytes=serialized.length;job.result=result;job.state="complete";
            }finally{c.rollback();}
        }catch(Exception e){job.result=null;job.bytes=0;job.state=job.cancelled?"cancelled":"failed";
            job.exception=exceptionInfo(e);
            // SQLException messages can contain passwords, SQL literals and driver URLs.
            job.error=job.cancelled?"Cancelled or timed out":task instanceof CatalogTask?connections.humanError(job.connection,e): e instanceof IllegalArgumentException?e.getMessage():"Database operation failed; verify connection, permissions, and driver settings";
        }finally{deadline.cancel(false);job.statement=null;job.finished=System.currentTimeMillis();}
    }
    synchronized ObjectNode local(String owner,LocalTask task,Runnable cleanup){
        return local(owner,"",task,cleanup);
    }
    private synchronized ObjectNode local(String owner,String connection,LocalTask task,Runnable cleanup){
        reap();if(jobs.size()>=32||(jobs.size()+1)*JOB_RESERVATION>config.memoryBytes())throw new IllegalArgumentException("DBA allowance full; release completed jobs");
        Job job=new Job(owner,connection);jobs.put(job.id,job);
        try{workers.execute(()->{
            job.started=System.currentTimeMillis();job.state="running";job.thread=Thread.currentThread();
            var deadline=timer.schedule(()->cancel(job),config.timeoutSeconds(),TimeUnit.SECONDS);
            try{if(job.cancelled||!alive.test(owner))throw new CancellationException();JsonNode result=task.run(job);
                if(job.cancelled||!alive.test(owner))throw new CancellationException();byte[] bytes=Profiles.JSON.writeValueAsBytes(result);
                if(bytes.length>job.byteLimit)throw new IllegalArgumentException("Setup result too large");job.bytes=bytes.length;job.result=result;job.state="complete";
            }catch(Exception e){job.state=job.cancelled?"cancelled":"failed";job.error=job.cancelled?"Cancelled or deadline exceeded; a driver may take time to stop":e instanceof IllegalArgumentException?e.getMessage():"Connection setup failed; check driver, network, credentials and vault availability";job.exception=exceptionInfo(e);}
            finally{deadline.cancel(false);job.thread=null;Thread.interrupted();cleanup.run();job.finished=System.currentTimeMillis();}
        });}catch(RejectedExecutionException e){jobs.remove(job.id);cleanup.run();throw new IllegalArgumentException("DBA queue full");}return job.json();
    }
    /** Only ApprovalQueue calls this after an authenticated human consumes a stored request. */
    ObjectNode approved(String principal,JsonNode request,Runnable validate,Runnable completed){
        String id=request.path("connectionId").asText();
        return local("agent:"+principal,id,job->{
            validate.run();boolean attempted=false,committing=false;Connection c=null;
            Connections.Target target=null;
            try{target=connections.target(id,request.path("database").asText());
                c=target.connection();Connections.selectSchema(c,request.path("schema").asText());boolean transactions=c.getMetaData().supportsTransactions();boolean verifiedRead=request.path("eligiblePersistentRead").asBoolean();boolean autoCommit=verifiedRead?false:request.path("autoCommit").asBoolean()||!transactions;
                if(verifiedRead){String product=c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);if(!product.contains("postgresql")&&!product.equals("h2")&&!product.contains("mysql")&&!product.contains("mariadb"))throw new IllegalArgumentException("This driver has no verified read-only session implementation; request one-time review instead");try{c.setReadOnly(true);}catch(SQLException e){throw new IllegalArgumentException("The driver could not establish a verified read-only session");}if(!c.isReadOnly())throw new IllegalArgumentException("The driver did not confirm read-only session mode");}
                else if(transactions)try{c.setReadOnly(false);}catch(SQLFeatureNotSupportedException ignored){}if(transactions)c.setAutoCommit(autoCommit);
                if(c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"))try(Statement st=c.createStatement()){st.execute((autoCommit?"SET ":"SET LOCAL ")+"statement_timeout = "+config.timeoutSeconds()*1000);st.execute((autoCommit?"SET ":"SET LOCAL ")+"lock_timeout = 3000");}
                validate.run();if(job.cancelled||!alive.test(job.owner))throw new CancellationException();
                ObjectNode result=Profiles.JSON.createObjectNode();ArrayNode results=result.putArray("results");long affected=0;int remaining=job.rowLimit;
                try(PreparedStatement statement=c.prepareStatement(request.path("sql").asText())){
                    job.statement=statement;statement.setQueryTimeout(config.timeoutSeconds());
                    JsonNode parameters=request.path("parameters");for(int i=0;i<parameters.size();i++)statement.setObject(i+1,Profiles.JSON.convertValue(parameters.get(i),Object.class));
                    attempted=true;boolean hasRows=statement.execute();int count=0;
                    while(true){
                        if(job.cancelled||!alive.test(job.owner))throw new CancellationException();if(++count>64)throw new IllegalArgumentException("Too many statement results");
                        if(hasRows){try(ResultSet rs=statement.getResultSet()){ObjectNode data=rows(rs,remaining,Math.max(8192,job.byteLimit/2));remaining=Math.max(0,remaining-data.path("rows").size());results.add(data);}}
                        else{long n=statement.getLargeUpdateCount();if(n==-1)break;affected+=n;results.addObject().put("affectedRows",n);}
                        if(result.toString().length()*2L>job.byteLimit-16384)throw new IllegalArgumentException("Approved query result exceeds the agent result allowance");
                        hasRows=statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                    }
                }
                validate.run();if(job.cancelled||!alive.test(job.owner))throw new CancellationException();committing=true;if(transactions&&!autoCommit)c.commit();
                job.outcome=autoCommit?"auto_commit_completed":"commit_acknowledged";return result.put("affectedRows",affected).put("outcome",job.outcome);
            }catch(Exception e){job.outcome=!attempted?"not_started":committing?"unknown":"partial_or_unknown";if(c!=null&&!committing)try{if(!c.isClosed()&&!c.getAutoCommit()){c.rollback();job.outcome="rollback_requested";}}catch(SQLException ignored){}throw e;
            }finally{job.statement=null;try{if(target!=null)target.close();}finally{/* Completion runs in the job wrapper, including pre-execution cancellation. */}}
        },completed);
    }

    static ObjectNode exceptionInfo(Throwable error){ObjectNode out=Profiles.JSON.createObjectNode().put("type",error.getClass().getName()).put("details","Raw driver exception text is withheld because it may contain credentials or connection parameters.");ArrayNode chain=out.putArray("causes");for(int i=0;error!=null&&i<6;i++,error=error.getCause()){ObjectNode n=chain.addObject().put("type",error.getClass().getName());if(error instanceof SQLException sql){String state=sql.getSQLState();if(state!=null&&state.matches("[A-Za-z0-9]{5}"))n.put("sqlState",state);n.put("vendorCode",sql.getErrorCode());}}return out;}
    private static String validateSql(String owner,String sql){return owner.startsWith("agent:")?SqlReadGuard.validate(sql):SqlReadGuard.validateBrowser(sql);}
    ObjectNode query(String owner,String id,String sql,JsonNode parameters){
        if(!owner.startsWith("agent:"))return humanQuery(owner,id,sql,parameters,false);
        String validated=validateSql(owner,sql);
        return read(owner,id,validated,parameters,false);
    }
    ObjectNode humanQuery(String owner,String id,String sql,JsonNode parameters,boolean autoCommit){
        if(owner.startsWith("agent:"))throw new SecurityException("Human SQL is not available to agents");
        if(sql==null||sql.isBlank()||sql.length()>16384)throw new IllegalArgumentException("SQL must contain 1..16384 characters");
        HumanSql.checkParameters(parameters);JsonNode values=parameters.deepCopy();
        return submit(owner,id,(job,c)->HumanSql.execute(job,c,sql,values,config.decisionTimeoutSeconds(),e->connections.humanError(id,e)),true,autoCommit);
    }
    /** Browser table grids: one validated SELECT, fixed catalog context, no script decisions. */
    ObjectNode tableQuery(String owner,String id,String sql,JsonNode parameters,String database){
        if(owner.startsWith("agent:"))throw new SecurityException("Table grids are browser-only");
        if(database==null||database.length()>256||database.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid target database");
        ObjectNode validation=Profiles.JSON.createObjectNode().put("sql",sql).put("action","refresh");validation.set("parameters",parameters);
        String validated=GridSql.prepare(validation).path("sql").asText();HumanSql.checkParameters(parameters);JsonNode values=parameters.deepCopy();
        return catalogRead(owner,id,Profiles.JSON.createObjectNode().put("database",database),(job,c)->{
            try(PreparedStatement statement=c.prepareStatement(validated,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
                job.statement=statement;statement.setQueryTimeout(config.timeoutSeconds());statement.setFetchSize(64);statement.setMaxRows(job.rowLimit+1);
                for(int i=0;i<values.size();i++){JsonNode v=values.get(i);if(v.isNull())statement.setNull(i+1,Types.NULL);else if(v.isBoolean())statement.setBoolean(i+1,v.asBoolean());else if(v.isNumber())statement.setBigDecimal(i+1,v.decimalValue());else statement.setString(i+1,v.asText());}
                try(ResultSet rs=statement.executeQuery()){
                    ObjectNode entry=rows(rs,job.rowLimit,job.byteLimit/2).put("kind","rows").put("statementIndex",1);GridSql.describeColumns(validated,entry);
                    ObjectNode result=Profiles.JSON.createObjectNode().put("rowCount",entry.path("rowCount").asInt()).put("truncated",entry.path("truncated").asBoolean());
                    result.putArray("results").add(entry);result.putArray("statements").addObject().put("index",1).put("sql",validated);return result;
                }
            }finally{job.statement=null;}
        });
    }
    ObjectNode tablePreparation(String owner,String id,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Table grids are browser-only");
        ObjectNode selection=MetadataActions.request(input);((ObjectNode)selection.path("parent")).put("genericOnly",connections.genericOnly(id));
        if(!TableQueries.RELATIONS.contains(selection.path("parent").path("kind").asText()))throw new IllegalArgumentException("Select a table, view or materialized view");
        return catalogRead(owner,id,(ObjectNode)selection.path("parent"),(job,c)->TableQueries.prepare(job,c,selection,config.timeoutSeconds()));
    }
    ObjectNode queryBuilder(String owner,String id,JsonNode input,boolean source){
        if(owner.startsWith("agent:"))throw new SecurityException("The visual query builder is browser-only");
        if(input.has("sourceConnectionId")&&!id.equals(input.path("sourceConnectionId").asText()))throw new IllegalArgumentException("Use sources from the same database connection");
        ObjectNode selection=source?MetadataActions.request(input):null;
        String database=input.path("database").asText(source?selection.path("parent").path("database").asText():"");
        if(database.length()>256)throw new IllegalArgumentException("Invalid database target");
        if(source&&!database.isEmpty()&&!selection.path("parent").path("database").asText().isEmpty()&&!database.equals(selection.path("parent").path("database").asText()))throw new IllegalArgumentException("Use sources from the same database; cross-database joins are not supported by the visual builder");
        ObjectNode request=Profiles.JSON.createObjectNode().put("database",source?selection.path("parent").path("database").asText():database);
        return catalogRead(owner,id,request,(job,c)->{
            if(source&&!database.isEmpty()&&selection.path("parent").path("database").asText().isEmpty()&&!database.equals(c.getCatalog()))throw new IllegalArgumentException("Use sources from the same database; the selected source belongs to the connection's current database");
            return source?QueryBuilder.source(job,c,selection,config.timeoutSeconds()):QueryBuilder.analyze(job,c,Profiles.text(input,"sql",16384),config.timeoutSeconds());
        });
    }
    ObjectNode functions(String owner,String id,JsonNode request){
        if(owner.startsWith("agent:"))throw new SecurityException("Visual function discovery is browser-only");
        return catalogRead(owner,id,Profiles.JSON.createObjectNode().put("database",request.path("database").asText("")),(job,c)->VisualFunctions.read(job,c,request));
    }
    ObjectNode browserExplain(String owner,String id,String sql,JsonNode parameters,String database){
        if(owner.startsWith("agent:"))throw new SecurityException("Browser Explain is browser-only");
        if(database==null||database.length()>256||database.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid target database");
        ObjectNode check=Profiles.JSON.createObjectNode().put("sql",sql).put("action","refresh");check.set("parameters",parameters);String validated=GridSql.prepare(check).path("sql").asText();HumanSql.checkParameters(parameters);JsonNode values=parameters.deepCopy();
        return catalogRead(owner,id,Profiles.JSON.createObjectNode().put("database",database),(job,c)->ExplainPlans.collect(job,c,validated,values,connections.driverLoader(id)));
    }
    ObjectNode explain(String owner,String id,String sql,JsonNode parameters){return read(owner,id,validateSql(owner,sql),parameters,true);}
    private ObjectNode read(String owner,String id,String validated,JsonNode parameters,boolean explain){
        if(!parameters.isArray()||parameters.size()>128)throw new IllegalArgumentException("parameters must be an array of at most 128 values");
        JsonNode values=parameters.deepCopy();
        return submit(owner,id,(job,c)->{
            if(explain||owner.startsWith("agent:")){if(connections.genericOnly(id))throw new IllegalArgumentException("Advanced PostgreSQL operations are not certified for this database template");postgres(c);}
            try(PreparedStatement statement=c.prepareStatement((explain?"EXPLAIN (ANALYZE FALSE, FORMAT JSON) ":"")+validated,ResultSet.TYPE_FORWARD_ONLY,ResultSet.CONCUR_READ_ONLY)){
                job.statement=statement;statement.setQueryTimeout(config.timeoutSeconds());statement.setFetchSize(64);statement.setMaxRows(job.rowLimit+1);
                for(int i=0;i<values.size();i++){JsonNode v=values.get(i);if(v.isNull())statement.setNull(i+1,Types.NULL);else if(v.isBoolean())statement.setBoolean(i+1,v.asBoolean());else if(v.isIntegralNumber())statement.setBigDecimal(i+1,v.decimalValue());else if(v.isNumber())statement.setBigDecimal(i+1,v.decimalValue());else if(v.isTextual()&&v.asText().length()<=MAX_CELL)statement.setString(i+1,v.asText());else throw new IllegalArgumentException("Unsupported or oversized parameter");}
                try(ResultSet rs=statement.executeQuery()){ObjectNode result=rows(rs,job.rowLimit,job.byteLimit);if(explain)PlanObservations.attach(result);return result;}
            }
        });
    }
    ObjectNode test(String owner,String id){return submit(owner,id,(job,c)->Profiles.JSON.createObjectNode().put("connected",true).put("database",c.getMetaData().getDatabaseProductName()).put("version",c.getMetaData().getDatabaseProductVersion()));}
    ObjectNode metadata(String owner,String id,String schema,String table){
        if((schema!=null&&schema.length()>128)||(table!=null&&table.length()>128))throw new IllegalArgumentException("Metadata identifier too long");
        return submit(owner,id,(job,c)->{
            DatabaseMetaData m=c.getMetaData();
            try(ResultSet rs=schema==null?m.getSchemas():table==null?m.getTables(c.getCatalog(),literal(m,schema),"%",new String[]{"TABLE","VIEW"}):m.getColumns(c.getCatalog(),literal(m,schema),literal(m,table),"%")){return rows(rs,Math.min(1000,job.rowLimit),job.byteLimit);}
        });
    }
    ObjectNode metadataTree(String owner,String id,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Catalog tree navigation is browser-only");
        ObjectNode request=MetadataTree.request(input);request.put("genericOnly",connections.genericOnly(id));int timeout=config.timeoutSeconds();
        return catalogRead(owner,id,request,(job,c)->ObjectDesigner.annotate(ObjectCreation.annotate(c,MetadataTree.browse(job,c,request,timeout),connections.genericOnly(id))));
    }
    ObjectNode prepareCreation(String owner,String id,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Object creation is browser-only");
        ObjectNode target=MetadataTree.request(input);JsonNode draft=input.deepCopy();
        return catalogRead(owner,id,target,(job,c)->ObjectCreation.prepare(c,draft,connections.genericOnly(id)));
    }
    ObjectNode applyCreation(String owner,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Object creation is browser-only");
        Job preview=require(owner,Profiles.text(input,"planId",36));ObjectNode plan;
        synchronized(preview){
            if(!preview.state.equals("complete")||preview.result==null||!preview.result.path("creationPlan").asBoolean()||preview.designerPlanUsed||preview.result.path("expiresAt").asLong()<System.currentTimeMillis())throw new IllegalArgumentException("Creation review expired or was used; review again");
            if(!input.path("confirmed").asBoolean())throw new IllegalArgumentException("Explicit Apply confirmation is required");
            plan=((ObjectNode)preview.result).deepCopy();preview.designerPlanUsed=true;
        }
        String id=preview.connection;
        return local(owner,id,job->{
            Connection c=null;Connections.DatabaseConnection external=null;boolean committing=false;
            ObjectNode report=Profiles.JSON.createObjectNode();
            try{
                if(job.cancelled||!alive.test(owner))throw new CancellationException();
                c=connections.open(id);String database=plan.path("database").asText();
                if(!database.isBlank()&&!database.equals(c.getCatalog())){
                    if(!plan.path("engine").asText().equals("postgresql"))throw new SQLFeatureNotSupportedException("Use a connection targeting this database for creation");
                    c.close();c=null;external=connections.openDatabase(id,database,config.timeoutSeconds());c=external.connection();
                }
                c.setReadOnly(false);c.setAutoCommit(!plan.path("atomic").asBoolean());
                ObjectNode checked=ObjectCreation.prepare(c,plan.path("draft"),connections.genericOnly(id));
                if(!checked.path("sql").equals(plan.path("sql"))||!checked.path("engine").equals(plan.path("engine"))||!checked.path("targetFingerprint").equals(plan.path("targetFingerprint")))throw new IllegalArgumentException("Target configuration changed; review again");
                try(var st=c.createStatement()){
                    job.statement=st;st.setQueryTimeout(config.timeoutSeconds());
                    if(plan.path("atomic").asBoolean()){st.execute("SET LOCAL statement_timeout = "+config.timeoutSeconds()*1000);st.execute("SET LOCAL lock_timeout = 3000");}
                    if(job.cancelled||!alive.test(owner))throw new CancellationException();
                    st.execute(plan.path("sql").asText());
                }finally{job.statement=null;}
                if(job.cancelled||!alive.test(owner))throw new CancellationException();
                committing=true;if(plan.path("atomic").asBoolean())c.commit();
                report.put("status","success").put("message","Created "+plan.path("name").asText()+".");
            }catch(Exception e){
                String outcome="unknown";
                if(c!=null&&plan.path("atomic").asBoolean()&&!committing)try{c.rollback();outcome="rolled_back";}catch(SQLException ignored){}
                report.put("status","failed").put("outcome",outcome).put("message",connections.humanError(id,e)+" Refresh metadata before retrying if the outcome is uncertain.");
            }finally{job.statement=null;if(external!=null)try{external.close();}catch(Exception ignored){}else if(c!=null)try{connections.discard(id,c);c.close();}catch(Exception ignored){}}
            return report;
        },()->{});
    }
    ObjectNode metadataObject(String owner,String id,JsonNode input,boolean execute){
        if(owner.startsWith("agent:"))throw new SecurityException("Catalog object actions are browser-only");
        if(connections.genericOnly(id))throw new IllegalArgumentException("Object mutations are not certified for this database template; use the SQL editor with the vendor's documented syntax");
        ObjectNode selection=MetadataActions.request(input);int timeout=config.timeoutSeconds();
        if(!execute)return catalogRead(owner,id,(ObjectNode)selection.path("parent"),(job,c)->{
            MetadataActions.Plan plan=objectActionPlan(job,id,c,selection,timeout);ObjectNode result=plan.json(Objects.toString(c.getCatalog(),""));
            // Cross-database browsing is supported, but mutations require an explicit saved target.
            result.put("database",c.getCatalog());
            return result;
        });
        JsonNode commandInput=input.deepCopy();String action=Profiles.text(input,"action",16);
        if(!Set.of("delete","rename","truncate","refresh").contains(action))throw new IllegalArgumentException("Unsupported object action");
        if(Set.of("delete","truncate","refresh").contains(action)&&(!input.path("confirmed").isBoolean()||!input.path("confirmed").asBoolean()))throw new IllegalArgumentException("Explicit destructive-action confirmation is required");
        return submit(owner,id,(job,c)->{
            String database=selection.path("parent").path("database").asText("");
            if(!database.isEmpty()&&!database.equals(c.getCatalog()))throw new IllegalArgumentException("For object changes, use a saved connection targeting the selected database. No other database was modified.");
            MetadataActions.Plan plan=objectActionPlan(job,id,c,selection,job.remainingSeconds());
            String product=c.getMetaData().getDatabaseProductName(),engine=product.equalsIgnoreCase("PostgreSQL")?"postgresql":VendorMetadata.engine(product);
            String sql=MetadataActions.command(plan,engine,action,commandInput);List<MaterializedViewSchedules.Command> commands=new ArrayList<>();
            if(action.equals("delete"))commands.addAll(plan.deleteCleanup());commands.add(new MaterializedViewSchedules.Command(sql,Objects.toString(c.getCatalog(),""),"object",action+" the selected object"));
            for(MaterializedViewSchedules.Command command:commands){
                if(job.cancelled||!alive.test(owner))throw new CancellationException();String commandDatabase=command.database();
                if(commandDatabase.isBlank()||commandDatabase.equals(c.getCatalog()))executeActionStatement(job,c,command.sql());
                else try(var other=connections.openDatabase(id,commandDatabase,job.remainingSeconds())){Connection target=other.connection();target.setReadOnly(false);target.setAutoCommit(true);try(Statement setup=target.createStatement()){setup.setQueryTimeout(job.remainingSeconds());setup.execute("SET statement_timeout = "+config.timeoutSeconds()*1000);setup.execute("SET lock_timeout = 3000");}executeActionStatement(job,target,command.sql());}
            }
            return Profiles.JSON.createObjectNode().put("action",action).put("name",plan.name()).put("changed",true).put("statements",commands.size());
        },true,true);
    }
    private MetadataActions.Plan objectActionPlan(Job job,String id,Connection c,ObjectNode selection,int timeout)throws Exception {
        MetadataActions.Plan plan=MetadataActions.resolve(job,c,selection,timeout);JsonNode parent=selection.path("parent");
        if(!parent.path("kind").asText().equals("materialized_views")||!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"))return plan;
        String database=Objects.toString(c.getCatalog(),parent.path("database").asText()),control=MaterializedViewSchedules.postgresControlDatabase(job,c,database);
        if(control.equals(database))return plan;
        List<MaterializedViewSchedules.Command> cleanup;
        try(var other=connections.openDatabase(id,control,job.remainingSeconds())){Connection target=other.connection();target.setReadOnly(true);target.setAutoCommit(false);try{cleanup=MaterializedViewSchedules.deleteCleanup(job,target,"postgresql",database,parent.path("schema").asText(),plan.name());}finally{target.rollback();}}
        List<String> warnings=cleanup.isEmpty()?List.of():List.of("The managed pg_cron job is stored in "+control+". Its cleanup commits there before the materialized view is dropped from "+database+"; these steps cannot be atomic.");
        return MetadataActions.withDeleteCleanup(plan,cleanup,warnings);
    }
    private static void executeActionStatement(Job job,Connection c,String sql)throws Exception {try(Statement st=c.createStatement()){job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.execute(sql);}finally{job.statement=null;}}
    private static final java.util.concurrent.locks.ReentrantLock[] DESIGNER_LOCKS=java.util.stream.IntStream.range(0,64).mapToObj(i->new java.util.concurrent.locks.ReentrantLock()).toArray(java.util.concurrent.locks.ReentrantLock[]::new);
    private ObjectNode objectSnapshot(Job job,String id,Connection target,JsonNode request)throws Exception {
        ObjectNode snapshot=ObjectDesigner.load(job,target,request,connections.genericOnly(id));
        JsonNode schedule=snapshot.path("refreshSchedule");String control=schedule.path("controlDatabase").asText(),database=snapshot.path("database").asText();
        if(snapshot.path("engine").asText().equals("postgresql")&&schedule.path("state").asText().equals("control_database")&&!control.isBlank()&&!control.equals(target.getCatalog())){
            boolean eligible=schedule.path("capabilities").path("concurrentEligible").asBoolean();
            try(var scheduler=connections.openDatabase(id,control,job.remainingSeconds())){
                Connection other=scheduler.connection();other.setAutoCommit(false);other.setReadOnly(true);
                try{MaterializedViewSchedules.populate(job,other,snapshot);((ObjectNode)snapshot.path("refreshSchedule").path("capabilities")).put("concurrentEligible",eligible);}
                finally{other.rollback();}
            }catch(Exception unavailable){
                ((ObjectNode)snapshot.path("refreshSchedule")).put("editable",false).put("state","control_database_unavailable").put("message","The pg_cron control database "+control+" could not be inspected with this saved connection. Verify CONNECT permission and scheduler grants.");
            }
            ObjectDesigner.fingerprint(target,snapshot);
        }
        return snapshot;
    }
    ObjectNode objectProperties(String owner,String id,JsonNode input,boolean prepare){
        if(owner.startsWith("agent:"))throw new SecurityException("Object editors are browser-only");
        JsonNode request=input.deepCopy();ObjectNode target=MetadataTree.request(input.path(input.path("creation").asBoolean()?"target":"parent"));
        return catalogRead(owner,id,target,(job,c)->{
            ObjectNode snapshot=objectSnapshot(job,id,c,request);
            return prepare?ObjectDesigner.prepare(snapshot,request):snapshot;
        });
    }
    ObjectNode applyObjectProperties(String owner,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Object editors are browser-only");
        Job preview=require(owner,Profiles.text(input,"planId",36));ObjectNode plan;
        synchronized(preview){
            if(!preview.state.equals("complete")||preview.result==null||!preview.result.path("objectDesignerPlan").asBoolean()||preview.designerPlanUsed||preview.result.path("expiresAt").asLong()<System.currentTimeMillis())throw new IllegalArgumentException("Object review expired or was already used; review again");
            if(!input.path("confirmed").isBoolean()||!input.path("confirmed").asBoolean())throw new IllegalArgumentException("Explicit Apply acknowledgement is required");
            plan=((ObjectNode)preview.result).deepCopy();preview.designerPlanUsed=true;
        }
        String id=preview.connection;
        return local(owner,id,job->{
            ObjectNode snapshot=(ObjectNode)plan.path("snapshot");boolean creating=snapshot.path("creation").asBoolean(),atomic=plan.path("atomic").asBoolean();
            String lockKey=id+snapshot.path("database")+snapshot.path("target");
            var lock=DESIGNER_LOCKS[Math.floorMod(lockKey.hashCode(),DESIGNER_LOCKS.length)];
            Connection c=null;Connections.DatabaseConnection external=null;boolean locked=false,committing=false,attempted=false,objectCommitted=false;int completed=0;String activePhase="";
            ObjectNode report=Profiles.JSON.createObjectNode().put("status","failed").put("atomic",atomic);ArrayNode steps=report.putArray("steps");job.result=report;
            try{
                if(!lock.tryLock(config.timeoutSeconds(),TimeUnit.SECONDS))throw new IllegalArgumentException("Another object operation is active");locked=true;
                if(job.cancelled||!alive.test(owner))throw new CancellationException();
                c=connections.open(id);String database=snapshot.path("database").asText();
                if(!database.isBlank()&&!database.equals(c.getCatalog())){
                    if(!snapshot.path("engine").asText().equals("postgresql"))throw new SQLFeatureNotSupportedException("Use a saved connection targeting this database for object changes");
                    c.close();c=null;external=connections.openDatabase(id,database,config.timeoutSeconds());c=external.connection();
                }
                c.setReadOnly(false);c.setAutoCommit(!atomic);
                if(snapshot.path("engine").asText().equals("postgresql"))try(var st=c.createStatement()){
                    job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.execute((atomic?"SET LOCAL ":"SET ")+"statement_timeout = "+config.timeoutSeconds()*1000);st.execute((atomic?"SET LOCAL ":"SET ")+"lock_timeout = 3000");
                }finally{job.statement=null;}
                ObjectNode request=creating?Profiles.JSON.createObjectNode().put("creation",true):(ObjectNode)snapshot.path("selection").deepCopy();
                if(creating)request.set("target",snapshot.path("target"));
                ObjectNode current=objectSnapshot(job,id,c,request);
                if(!current.path("fingerprint").equals(snapshot.path("fingerprint"))||!current.path("connectionFingerprint").equals(snapshot.path("connectionFingerprint")))throw new IllegalArgumentException("Object or connection changed after review. Refresh and review again.");
                Set<String> previousRoutines=new HashSet<>();
                boolean nativeRoutine=snapshot.path("engine").asText().equals("postgresql")&&Set.of("functions","procedures").contains(snapshot.path("kind").asText());
                if(nativeRoutine&&creating)for(JsonNode row:ObjectCatalog.query(job,c,"SELECT p.oid::text AS oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=? AND p.proname=?",snapshot.path("fields").path("schema").asText(),plan.path("draft").path("fields").path("name").asText()))previousRoutines.add(row.path("oid").asText());
                for(JsonNode command:plan.path("commands")){
                    if(job.cancelled||!alive.test(owner))throw new CancellationException();
                    String commandDatabase=command.path("database").asText(snapshot.path("database").asText());
                    if(!commandDatabase.isBlank()&&!commandDatabase.equals(c.getCatalog())){
                        if(atomic||!snapshot.path("engine").asText().equals("postgresql"))throw new SQLFeatureNotSupportedException("A reviewed scheduler command targets a different database, but this provider cannot switch databases safely");
                        if(external!=null){external.close();external=null;}else connections.discard(id,c);c=null;
                        external=connections.openDatabase(id,commandDatabase,job.remainingSeconds());c=external.connection();c.setReadOnly(false);c.setAutoCommit(true);
                        try(var setup=c.createStatement()){job.statement=setup;setup.setQueryTimeout(job.remainingSeconds());setup.execute("SET statement_timeout = "+config.timeoutSeconds()*1000);setup.execute("SET lock_timeout = 3000");}finally{job.statement=null;}
                    }
                    activePhase=command.path("phase").asText("object");job.progress="Applying "+activePhase+" operation "+(completed+1)+" of "+plan.path("commands").size();attempted=true;
                    try(var st=c.createStatement()){job.statement=st;st.setQueryTimeout(job.remainingSeconds());st.execute(command.path("sql").asText());}finally{job.statement=null;}
                    completed++;if(!atomic&&activePhase.equals("object"))objectCommitted=true;
                    steps.addObject().put("index",completed).put("status",atomic?"executed_pending_commit":"committed").put("database",command.path("database").asText(snapshot.path("database").asText())).put("phase",activePhase).put("purpose",command.path("purpose").asText());
                }
                if(job.cancelled||!alive.test(owner))throw new CancellationException();
                committing=true;if(atomic){c.commit();objectCommitted=plan.path("commands").findValues("phase").stream().anyMatch(n->n.asText().equals("object"));}
                report.put("status","success").put("outcome","commit_acknowledged").put("message","Object and refresh-schedule changes saved.").put("objectCommitted",objectCommitted);
                report.set("fields",plan.path("draft").path("fields"));report.put("sqlMode",plan.path("sqlMode").asBoolean());
                if(nativeRoutine){
                    // Resolve a newly created overload using its new catalog identity, not its display name.
                    try{
                        List<String> matches=new ArrayList<>();
                        for(JsonNode row:ObjectCatalog.query(job,c,"SELECT p.oid::text AS oid FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname=? AND p.proname=?",plan.path("draft").path("fields").path("schema").asText(),plan.path("draft").path("fields").path("name").asText())){
                            String oid=row.path("oid").asText();if(creating?!previousRoutines.contains(oid):oid.equals(snapshot.path("node").path("oid").asText()))matches.add(oid);
                        }
                        if(matches.size()==1)for(int offset=0;offset<=2000;offset+=200){
                            ObjectNode parent=((ObjectNode)snapshot.path("target")).deepCopy().put("schema",plan.path("draft").path("fields").path("schema").asText()).put("offset",offset);
                            ObjectNode page=MetadataTree.browse(job,c,parent,job.remainingSeconds());boolean found=false;
                            for(JsonNode node:page.path("nodes"))if(node.path("oid").asText().equals(matches.get(0))){ObjectNode identity=report.putObject("selection");identity.set("parent",parent);identity.put("key",node.path("key").asText());report.put("title",node.path("name").asText());found=true;break;}
                            if(found||!page.has("nextOffset"))break;
                        }
                    }catch(Exception resolution){report.put("resolutionWarning","Changes committed; refresh the routine category to resolve its identity.");}
                }
                for(JsonNode step:steps)((ObjectNode)step).put("status","committed");
            }catch(Exception e){
                String outcome=committing?"unknown":atomic?"rolled_back":attempted?"partial_or_unknown":"not_applied";
                if(c!=null&&atomic&&!committing)try{c.rollback();}catch(SQLException rollback){outcome="unknown";}
                if(e instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().startsWith("08"))outcome="unknown";
                report.put("outcome",outcome).put("failedPhase",activePhase).put("objectCommitted",objectCommitted);
                report.set("fields",plan.path("draft").path("fields"));
                if(objectCommitted&&activePhase.equals("scheduler"))report.put("message","The materialized view exists, but its requested refresh schedule was not applied: "+connections.humanError(id,e)).put("scheduleRetryAvailable",true);
                else report.put("message",connections.humanError(id,e));
                for(JsonNode step:steps)if(atomic)((ObjectNode)step).put("status",outcome);
            }finally{
                job.statement=null;if(external!=null)try{external.close();}catch(Exception ignored){}else if(c!=null)try{connections.discard(id,c);c.close();}catch(Exception ignored){}
                if(locked)lock.unlock();
            }
            return report;
        },()->{});
    }
    ObjectNode tableProperties(String owner,String id,JsonNode input,boolean prepare){
        if(owner.startsWith("agent:"))throw new SecurityException("Table designer is browser-only");
        if(input.path("creation").asBoolean()){
            JsonNode request=input.deepCopy();ObjectNode target=MetadataTree.request(input.path("target"));
            return catalogRead(owner,id,target,(job,c)->{ObjectNode baseline=TableCreation.initialize(c,target,connections.genericOnly(id));return prepare?TableCreation.prepare(c,baseline,request):baseline;});
        }
        ObjectNode selection=MetadataActions.request(input);JsonNode request=input.deepCopy();
        return catalogRead(owner,id,(ObjectNode)selection.path("parent"),(job,c)->{
            ObjectNode snapshot=TableDesigner.load(job,c,selection,connections.genericOnly(id));
            return prepare?TableDesigner.prepare(snapshot,request).put("designerPlan",true):snapshot;
        });
    }
    ObjectNode tablePropertyDetails(String owner,String id,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Table designer is browser-only");ObjectNode selection=MetadataActions.request(input);String category=Profiles.text(input,"category",32);
        return catalogRead(owner,id,(ObjectNode)selection.path("parent"),(job,c)->TableDesigner.details(job,c,TableDesigner.load(job,c,selection,connections.genericOnly(id)),category));
    }
    ObjectNode applyTableProperties(String owner,JsonNode input){
        if(owner.startsWith("agent:"))throw new SecurityException("Table designer is browser-only");
        Job preview=require(owner,Profiles.text(input,"planId",36));ObjectNode plan;
        synchronized(preview){
            if(!preview.state.equals("complete")||preview.result==null||!preview.result.path("designerPlan").asBoolean()||preview.designerPlanUsed||preview.result.path("expiresAt").asLong()<System.currentTimeMillis())throw new IllegalArgumentException("Schema review expired or was already used; review the changes again");
            if(preview.result.path("risky").asBoolean()&&!input.path("confirmed").asBoolean())throw new IllegalArgumentException("Explicit risk acknowledgement is required");
            plan=((ObjectNode)preview.result).deepCopy();preview.designerPlanUsed=true;
        }
        String id=preview.connection;
        return local(owner,id,job->{
            ObjectNode snapshot=(ObjectNode)plan.path("snapshot"),selection=plan.path("createTable").asBoolean()?null:(ObjectNode)snapshot.path("selection");boolean creating=plan.path("createTable").asBoolean();
            String lockKey=id+snapshot.path("database")+snapshot.path("schema")+snapshot.path("name");var lock=DESIGNER_LOCKS[Math.floorMod(lockKey.hashCode(),DESIGNER_LOCKS.length)];
            Connection c=null;Connections.DatabaseConnection external=null;boolean locked=false,committing=false;int completed=0;
            ObjectNode report=Profiles.JSON.createObjectNode().put("status","failed").put("atomic",plan.path("atomic").asBoolean());ArrayNode steps=report.putArray("steps");job.result=report;
            try{
                if(!lock.tryLock(config.timeoutSeconds(),TimeUnit.SECONDS))throw new IllegalArgumentException("Another schema operation is active for this table");locked=true;
                if(job.cancelled||!alive.test(owner))throw new CancellationException();
                c=connections.open(id);String database=snapshot.path("database").asText();
                if(!database.isBlank()&&!database.equals(c.getCatalog())){
                    if(!snapshot.path("engine").asText().equals("postgresql"))throw new SQLFeatureNotSupportedException("This driver cannot safely target a different database for schema edits");
                    c.close();c=null;external=connections.openDatabase(id,database,config.timeoutSeconds());c=external.connection();
                }
                c.setReadOnly(false);c.setAutoCommit(!plan.path("atomic").asBoolean());
                if(plan.path("atomic").asBoolean())try(var st=c.createStatement()){job.statement=st;st.setQueryTimeout(config.timeoutSeconds());st.execute("SET LOCAL statement_timeout = "+config.timeoutSeconds()*1000);st.execute("SET LOCAL lock_timeout = 3000");if(!creating)st.execute("LOCK TABLE "+TableDesigner.target(snapshot)+" IN ACCESS EXCLUSIVE MODE");}finally{job.statement=null;}
                ObjectNode current=creating?TableCreation.initialize(c,snapshot.path("creationTarget"),connections.genericOnly(id)):TableDesigner.load(job,c,selection,connections.genericOnly(id));if(!current.path("fingerprint").equals(snapshot.path("fingerprint")))throw new IllegalArgumentException("Table or target changed after review. Refresh and review the draft again.");
                if(creating)TableCreation.absent(c,snapshot.path("schema").asText(),snapshot.path("name").asText());
                for(JsonNode command:plan.path("commands")){
                    if(job.cancelled||!alive.test(owner))throw new CancellationException();
                    job.progress="Applying schema operation "+(completed+1)+" of "+plan.path("commands").size();
                    try(var st=c.createStatement()){job.statement=st;st.setQueryTimeout(config.timeoutSeconds());st.execute(command.path("sql").asText());}finally{job.statement=null;}
                    completed++;steps.addObject().put("index",completed).put("status",plan.path("atomic").asBoolean()?"executed_pending_commit":"committed");
                }
                if(job.cancelled||!alive.test(owner))throw new CancellationException();
                committing=true;if(plan.path("atomic").asBoolean())c.commit();
                report.put("status","success").put("outcome","commit_acknowledged");for(JsonNode step:steps)((ObjectNode)step).put("status","committed");
                report.set("fields",plan.path("draft").path("fields"));report.put("message","Table changes saved successfully.");
            }catch(Exception e){
                String outcome=committing?"unknown":plan.path("atomic").asBoolean()?"rolled_back":completed>0?"partial":"not_applied";
                if(c!=null&&plan.path("atomic").asBoolean()&&!committing)try{c.rollback();}catch(SQLException failed){outcome="unknown";}
                if(e instanceof SQLException sql&&sql.getSQLState()!=null&&sql.getSQLState().startsWith("08"))outcome="unknown";
                report.put("status","failed").put("outcome",outcome).put("message",connections.humanError(id,e));for(JsonNode step:steps)if(plan.path("atomic").asBoolean())((ObjectNode)step).put("status",outcome);
            }finally{
                job.statement=null;if(external!=null)try{external.close();}catch(Exception ignored){}else if(c!=null)try{connections.discard(id,c);c.close();}catch(Exception ignored){}
                if(locked)lock.unlock();
            }
            return report;
        },()->{});
    }
    private ObjectNode catalogRead(String owner,String id,ObjectNode request,Task catalogTask){
        int timeout=config.timeoutSeconds();
        return submit(owner,id,(CatalogTask)(job,c)->{
            String database=request.path("database").asText("");
            if(database.isEmpty()||database.equals(c.getCatalog()))return catalogTask.run(job,c);
            if(!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL")){
                String original=c.getCatalog();
                // Pool proxies can cache the requested catalog even when a driver silently ignores it.
                Connection physical=c.unwrap(Connection.class);
                try{c.setCatalog(database);if(!database.equals(physical.getCatalog()))throw new SQLFeatureNotSupportedException("This driver cannot switch databases; create a separate connection for this database");return crossCatalogResult(catalogTask.run(job,c));}
                finally{try{c.rollback();c.setCatalog(original);if(!Objects.equals(original,physical.getCatalog()))throw new SQLException("Driver failed to restore the original database");}catch(SQLException failedRestore){connections.discard(id,c);throw failedRestore;}}
            }
            try(PreparedStatement allowed=c.prepareStatement("SELECT 1 FROM pg_catalog.pg_database WHERE datname=? AND datallowconn AND pg_catalog.has_database_privilege(oid,'CONNECT')")){
                job.statement=allowed;allowed.setQueryTimeout(timeout);allowed.setString(1,database);try(ResultSet rs=allowed.executeQuery()){if(!rs.next())throw new IllegalArgumentException("Database is unavailable or CONNECT permission is missing");}finally{job.statement=null;}
            }
            try(var other=connections.openDatabase(id,database,timeout)){
                if(job.cancelled)throw new CancellationException();Connection target=other.connection();target.setReadOnly(true);target.setAutoCommit(false);
                try{return crossCatalogResult(catalogTask.run(job,target));}finally{target.rollback();}
            }
        });
    }
    private static String literal(DatabaseMetaData m,String s)throws SQLException {String e=m.getSearchStringEscape();return s.replace(e,e+e).replace("%",e+"%").replace("_",e+"_");}
    private static JsonNode crossCatalogResult(JsonNode result){if(result instanceof ObjectNode object&&object.has("canDelete"))object.put("canDelete",false).put("canRename",false).put("canTruncate",false).put("canRefresh",false).put("reason","Use a saved connection targeting this database before changing its objects. Browsing does not retarget execution.");return result;}
    static ObjectNode rows(ResultSet rs,int limit)throws Exception{
        return rows(rs,limit,4<<20);
    }
    static ObjectNode rows(ResultSet rs,int limit,int byteLimit)throws Exception{
        ObjectNode result=Profiles.JSON.createObjectNode();ArrayNode cols=result.putArray("columns"),rows=result.putArray("rows");
        ResultSetMetaData m=rs.getMetaData();int count=m.getColumnCount();if(count>MAX_COLUMNS)throw new IllegalArgumentException("Maximum 256 result columns");
        for(int i=1;i<=count;i++){
            var col=cols.addObject().put("id","c"+i).put("label",m.getColumnLabel(i)).put("type",m.getColumnTypeName(i)).put("jdbcType",m.getColumnType(i));
            // Optional JDBC provenance: drivers may omit or not implement these fields.
            try{col.put("name",m.getColumnName(i)).put("table",m.getTableName(i)).put("schema",m.getSchemaName(i));}catch(SQLException|AbstractMethodError unsupported){}
        }
        long bytes=Profiles.JSON.writeValueAsBytes(cols).length;boolean truncated=false,cellsTruncated=false;
        while(rs.next()){
            if(rows.size()>=limit || (rows.size()+1L)*count>16_384){truncated=true;break;}
            ArrayNode row=Profiles.JSON.createArrayNode();
            for(int i=1;i<=count;i++){
                int type=m.getColumnType(i);
                if(type==Types.BINARY||type==Types.VARBINARY||type==Types.LONGVARBINARY||type==Types.BLOB){row.add("[binary preview omitted]");cellsTruncated=true;continue;}
                // MariaDB (among others) does not implement character streams for numeric values.
                // Scalar types have bounded representations; retain streaming only for text/LOBs.
                if(Set.of(Types.BOOLEAN,Types.BIT,Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT,Types.FLOAT,Types.REAL,Types.DOUBLE,Types.NUMERIC,Types.DECIMAL,Types.DATE,Types.TIME,Types.TIMESTAMP,Types.TIME_WITH_TIMEZONE,Types.TIMESTAMP_WITH_TIMEZONE).contains(type)){
                    if(m.getPrecision(i)>MAX_CELL){row.add("[oversized numeric preview omitted]");cellsTruncated=true;continue;}
                    String value=rs.getString(i);if(value==null)row.addNull();else{if(value.length()>MAX_CELL){value=value.substring(0,MAX_CELL);cellsTruncated=true;}row.add(value);}continue;
                }
                try(Reader reader=ExplainPlans.textReader(rs,i,MAX_CELL)){
                    if(reader==null){row.addNull();continue;}
                    char[] buffer=new char[MAX_CELL+1];int offset=0,n;while(offset<buffer.length&&(n=reader.read(buffer,offset,buffer.length-offset))>0)offset+=n;
                    if(offset>MAX_CELL){offset=MAX_CELL;cellsTruncated=true;}row.add(new String(buffer,0,offset));
                }
            }
            long size=Profiles.JSON.writeValueAsBytes(row).length;
            if(bytes+size>byteLimit-32_768L){truncated=true;break;}
            rows.add(row);bytes+=size;
        }
        return result.put("truncated",truncated).put("cellsTruncated",cellsTruncated).put("rowCount",rows.size());
    }
    static void postgres(Connection c)throws SQLException {if(!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("PostgreSQL"))throw new IllegalArgumentException("This operation is verified for PostgreSQL only");}
    ObjectNode ddl(String owner,String id,String schema,String object){
        if(schema==null||object==null||schema.length()>128||object.length()>128)throw new IllegalArgumentException("Schema and object are required (maximum 128 characters)");
        return submit(owner,id,(job,c)->{
            postgres(c);
            // Trusted catalog query: names are parameters, never executable SQL supplied by the agent.
            String sql="""
                SELECT 'object' AS section, c.relkind::text AS kind,
                  CASE WHEN c.relkind IN ('v','m') THEN pg_catalog.pg_get_viewdef(c.oid, true)
                       WHEN c.relkind='i' THEN pg_catalog.pg_get_indexdef(c.oid)
                       ELSE pg_catalog.format('%I.%I', n.nspname,c.relname) END AS definition
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p','v','m','i')
                UNION ALL
                SELECT 'column', a.attnum::text, pg_catalog.format('%I %s%s%s',a.attname,
                  pg_catalog.format_type(a.atttypid,a.atttypmod),
                  CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END,
                  CASE WHEN d.oid IS NOT NULL THEN ' DEFAULT ' || pg_catalog.pg_get_expr(d.adbin,d.adrelid) ELSE '' END)
                FROM pg_catalog.pg_attribute a JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped AND c.relkind IN ('r','p')
                UNION ALL
                SELECT 'constraint', con.conname, pg_catalog.pg_get_constraintdef(con.oid,true)
                FROM pg_catalog.pg_constraint con JOIN pg_catalog.pg_class c ON c.oid=con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=?
                """;
            try(PreparedStatement s=c.prepareStatement(sql)){job.statement=s;s.setFetchSize(64);s.setMaxRows(job.rowLimit+1);s.setQueryTimeout(config.timeoutSeconds());for(int i=1;i<=6;i+=2){s.setString(i,schema);s.setString(i+1,object);}try(ResultSet rs=s.executeQuery()){
                return rows(rs,job.rowLimit,job.byteLimit).put("executableScript",false).put("limitations","Catalog definition fragments, not a complete restore script. Identity/generated attributes, ownership, grants, storage, partitioning and dependent objects are not reconstructed.");
            }}
        });
    }
    synchronized Job require(String owner,String id){Job job=jobs.get(id);if(job==null||!job.owner.equals(owner))throw new IllegalArgumentException("Unknown job");return job;}
    synchronized ObjectNode status(String owner,String id){return require(owner,id).json();}
    void decision(String owner,String id,String decisionId,String action){if(!Set.of("cancel","continue","skip_similar").contains(action))throw new IllegalArgumentException("Unknown SQL error decision");require(owner,id).decide(decisionId,action);}
    synchronized void remove(String owner,String id){Job job=require(owner,id);if(job.finished==0)throw new IllegalArgumentException("Cancel and wait for completion before releasing this job");jobs.remove(id);}
    void cancel(Job job){synchronized(job){if(job.cancelled)return;job.cancelled=true;if(job.activeDeadline!=null)job.activeDeadline.cancel(false);job.notifyAll();}Thread thread=job.thread;if(thread!=null)thread.interrupt();Statement s=job.statement;if(s!=null)Thread.startVirtualThread(()->{try{s.cancel();}catch(SQLException ignored){}});}
    synchronized void cancelOwner(String owner){jobs.values().stream().filter(j->j.owner.equals(owner)).forEach(this::cancel);}
    synchronized boolean activeConnection(String id){return jobs.values().stream().anyMatch(j->j.connection.equals(id)&&j.finished==0);}
    synchronized ObjectNode connectionState(String id){return Profiles.JSON.createObjectNode().put("connected",connections.connected(id)).put("busy",activeConnection(id));}
    synchronized ObjectNode connectionAction(String owner,String id,String action){
        if(owner.startsWith("agent:"))throw new SecurityException("Connection management is browser-only");
        if(activeConnection(id))throw new IllegalArgumentException("Connection has active jobs; wait for completion or cancel them first");
        boolean connected=connections.connected(id);
        if(action.equals("connect")){if(connected)throw new IllegalArgumentException("Connection is already established");return test(owner,id);}
        if(!action.equals("disconnect")&&!action.equals("reconnect"))throw new IllegalArgumentException("Unknown connection action");
        if(!connected)throw new IllegalArgumentException("Connection is not established");
        connections.remove(id);
        return action.equals("reconnect")?test(owner,id):connectionState(id);
    }
    synchronized void reap(){long now=System.currentTimeMillis();for(Job j:jobs.values())if(!alive.test(j.owner)&&j.finished==0)cancel(j);jobs.values().removeIf(j->j.finished!=0&&(!alive.test(j.owner)||now-j.finished>300_000));}
    synchronized void configure(DbaConfig next){int n=next.concurrency();if(n>workers.getMaximumPoolSize()){workers.setMaximumPoolSize(n);workers.setCorePoolSize(n);}else{workers.setCorePoolSize(n);workers.setMaximumPoolSize(n);}config=next;}
    synchronized ObjectNode telemetry(){return Profiles.JSON.createObjectNode().put("memoryBudget",config.memoryBytes()).put("reservedBytes",jobs.size()*JOB_RESERVATION).put("retainedResultBytes",jobs.values().stream().mapToLong(j->j.bytes).sum()).put("activeJobs",workers.getActiveCount()).put("queuedJobs",workers.getQueue().size()).put("retainedJobs",jobs.size()).put("concurrency",config.concurrency()).put("heapUsed",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()).put("hardProcessLimit",false);}
    private static long safeSize(JsonNode value){try{return Profiles.JSON.writeValueAsBytes(value).length;}catch(Exception ignored){return 0;}}
    public void close(){synchronized(this){jobs.values().forEach(this::cancel);}workers.shutdownNow();timer.shutdownNow();try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
