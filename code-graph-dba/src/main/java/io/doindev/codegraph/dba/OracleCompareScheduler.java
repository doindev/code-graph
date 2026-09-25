package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import static io.doindev.codegraph.dba.CompareCatalog.*;

/** Scheduler metadata contains identifier-valued literals; only known native calls are transferable. */
final class OracleCompareScheduler {
    private static final Set<String> ATTRIBUTES=Set.of("NLS_ENV","RESTARTABLE","RESTART_ON_RECOVERY","RESTART_ON_FAILURE","JOB_PRIORITY","MAX_RUNS","MAX_FAILURES","LOGGING_LEVEL","STORE_OUTPUT","STOP_ON_WINDOW_CLOSE","INSTANCE_STICKINESS","RAISE_EVENTS","SCHEDULE_LIMIT","MAX_RUN_DURATION","ALLOW_RUNS_IN_RESTRICTED_MODE","COMMENTS");
    private static final Set<String> REFERENCES=Set.of("PROGRAM_NAME","SCHEDULE_NAME","JOB_ACTION");
    private OracleCompareScheduler(){}
    static ArrayNode catalog(QueryJobs.Job job,Connection c,String owner)throws Exception{
        ArrayNode objects=Profiles.JSON.createArrayNode();
        for(String category:List.of("jobs","programs","schedules","chains")){
            String column=category.substring(0,category.length()-1)+"_name";
            for(JsonNode row:query(job,c,"SELECT "+column+" AS name FROM SYS.ALL_SCHEDULER_"+category.toUpperCase(Locale.ROOT)+" WHERE owner=? ORDER BY "+column,owner)){
                if(objects.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Scheduler catalog exceeds 10,000 objects");objects.addObject().put("objectName",category+" / "+str(row,"name"));
            }
        }return objects;
    }
    static void roster(QueryJobs.Job job,Connection c,Inventory inventory,Map<String,ObjectNode> roster,boolean catalogReader)throws Exception{
        String prefix=catalogReader?"SYS.DBA_SCHEDULER_":"SYS.ALL_SCHEDULER_";
        var records=new ArrayList<ObjectNode>();long bytes=0;
        String selected=String.join(",",Collections.nCopies(inventory.schemas.size(),"?"));
        for(String category:List.of("programs","schedules","jobs","chains")){
            String columns=switch(category){
                case "programs"->"p.owner,p.program_name AS object_name,p.program_type,p.program_action,p.number_of_arguments,p.enabled,p.detached";
                case "schedules"->"p.owner,p.schedule_name AS object_name,p.schedule_type,p.repeat_interval";
                case "jobs"->"p.owner,p.job_name AS object_name,p.job_type,p.job_action,p.program_owner,p.program_name,p.schedule_owner,p.schedule_name,p.schedule_type,p.repeat_interval,p.number_of_arguments,p.job_style,p.job_class,p.enabled,p.state,p.credential_name,p.destination,p.file_watcher_name,p.event_queue_name";
                default->"p.owner,p.chain_name AS object_name";
            };
            for(JsonNode row:query(job,c,"SELECT "+columns+" FROM "+prefix+category.toUpperCase(Locale.ROOT)+" p JOIN SYS.ALL_USERS u ON u.username=p.owner WHERE (u.oracle_maintained='N' OR p.owner IN ("+selected+")) ORDER BY p.owner,object_name",inventory.schemas.toArray())){
                ObjectNode object=item(str(row,"owner"),"scheduler",category+" / "+str(row,"object_name")).put("oracleType","PROCOBJ").put("schedulerCategory",category).put("objectName",str(row,"object_name")).put("incomingCoverage",catalogReader?"catalog":"accessible_objects");
                if(records.size()>=MAX_OBJECTS||(bytes+=Profiles.JSON.writeValueAsBytes(row).length*2L+512)>MAX_BYTES)throw new IllegalArgumentException("Scheduler dependency catalog exceeds its resource allowance; narrow the visible owners");
                object.set("schedulerFields",row);object.put("schedulerEnabled",str(row,"enabled").equals("TRUE"));records.add(object);
                if(inventory.schemas.contains(str(object,"schema"))){if(roster.size()>=MAX_OBJECTS)throw new IllegalArgumentException("Oracle comparison exceeds 10,000 objects");roster.put(key(str(object,"schema"),"scheduler",str(object,"name")),object);}
            }
        }
        var verifiedActions=new HashSet<String>();
        for(ObjectNode object:records){
            JsonNode fields=object.path("schedulerFields");String category=str(object,"schedulerCategory"),owner=str(object,"schema");boolean captured=inventory.schemas.contains(owner);
            try{
                if(category.equals("chains"))throw new IllegalArgumentException("Scheduler chains require separate rule and step dependency review");
                if(category.equals("schedules")){calendar(fields);continue;}
                if(category.equals("jobs")){
                    if(!str(fields,"program_name").isEmpty())dependency(object,roster,key(str(fields,"program_owner"),"scheduler","programs / "+str(fields,"program_name")),captured);
                    if(!str(fields,"schedule_name").isEmpty())dependency(object,roster,key(str(fields,"schedule_owner"),"scheduler","schedules / "+str(fields,"schedule_name")),captured);
                    if(!str(fields,"state").isEmpty()&&Set.of("RUNNING","RETRY SCHEDULED","CHAIN_STALLED").contains(str(fields,"state")))throw new IllegalArgumentException("Stop the active Scheduler job before comparison");
                    if(!str(fields,"job_style").equals("REGULAR")||!str(fields,"job_class").equals("DEFAULT_JOB_CLASS")||!str(fields,"credential_name").isEmpty()||!str(fields,"destination").isEmpty()||!str(fields,"file_watcher_name").isEmpty()||!str(fields,"event_queue_name").isEmpty())throw new IllegalArgumentException("Scheduler credentials, external destinations, events, classes or lightweight jobs require independent destination configuration");
                    if(str(fields,"schedule_name").isEmpty())calendar(fields);
                }
                boolean inline=category.equals("programs")||str(fields,"program_name").isEmpty();
                if(inline){
                    String type=str(fields,category.equals("programs")?"program_type":"job_type"),action=str(fields,category.equals("programs")?"program_action":"job_action");
                    if(!type.equals("STORED_PROCEDURE"))throw new IllegalArgumentException("Scheduler comparison requires a stored procedure; blocks and external programs need manual dependency review");
                    List<String> names=names(action);String routineOwner=owner,routineName;
                    if(names.size()==1)routineName=names.getFirst();
                    else if(names.size()==3){routineOwner=names.get(0);routineName=names.get(1);}
                    else if(names.size()==2){if(roster.containsKey(key(owner,"packages",names.get(0))))routineName=names.get(0);else{routineOwner=names.get(0);routineName=names.get(1);}}
                    else throw new IllegalArgumentException("Unresolved Scheduler procedure name");
                    String target=key(routineOwner,names.size()==3||names.size()==2&&routineOwner.equals(owner)&&routineName.equals(names.get(0))?"packages":"procedures",routineName);
                    dependency(object,roster,target,captured);String qualified=OracleDialect.qualified(routineOwner,routineName);
                    boolean packaged=target.contains("\u0000packages\u0000");if(packaged)qualified+="."+OracleDialect.identifier(names.getLast());object.put("schedulerAction",qualified);
                    if(fields.path("number_of_arguments").asInt()!=0||str(fields,"detached").equals("TRUE"))throw new IllegalArgumentException("Scheduler arguments and detached programs require separate review");
                    if(captured&&verifiedActions.add(qualified)){
                        String sql="SELECT p.subprogram_id FROM SYS.ALL_PROCEDURES p WHERE p.owner=? AND p.object_name=? AND "+(packaged?"p.procedure_name=?":"p.procedure_name IS NULL AND p.object_type='PROCEDURE'")+" AND NOT EXISTS (SELECT 1 FROM SYS.ALL_ARGUMENTS a WHERE a.owner=p.owner AND a.object_id=p.object_id AND a.subprogram_id=p.subprogram_id AND a.data_level=0 AND (a.position=0 OR a.defaulted='N'))";
                        ArrayNode signatures=packaged?query(job,c,sql,routineOwner,routineName,names.getLast()):query(job,c,sql,routineOwner,routineName);
                        if(signatures.size()!=1){verifiedActions.remove(qualified);throw new IllegalArgumentException("Scheduler action must resolve to one accessible procedure callable without arguments: "+qualified);}
                    }
                }
            }catch(IllegalArgumentException failure){if(captured)object.put("captureBlocker",failure.getMessage());}
            // Volatile counters/state must not invalidate otherwise unchanged definitions.
            ((ObjectNode)fields).remove("state");
        }
    }
    private static void calendar(JsonNode fields){
        if(!Set.of("CALENDAR","ONCE","").contains(str(fields,"schedule_type")))throw new IllegalArgumentException("Scheduler event/window schedules require independent destination configuration");
        String repeat=str(fields,"repeat_interval").toUpperCase(Locale.ROOT).replace(" ","");
        if(!repeat.isEmpty()&&(!repeat.startsWith("FREQ=")||repeat.contains("INCLUDE=")||repeat.contains("EXCLUDE=")||repeat.contains("INTERSECT=")))throw new IllegalArgumentException("Named calendar references require separate Scheduler dependency review");
    }
    private static void dependency(ObjectNode object,Map<String,ObjectNode> roster,String target,boolean captured){
        ObjectNode required=roster.get(target);if(required==null){if(captured)throw new IllegalArgumentException("Scheduler dependency outside captured scope: "+target.replace('\u0000','.'));return;}
        if(captured){if(!CompareSql.contains(object.path("dependencies"),target))object.withArray("dependencies").add(target);}else required.withArray("outsideDependents").add(str(object,"schema")+"."+str(object,"name"));
    }
    static void definition(QueryJobs.Job job,Connection c,ObjectNode object)throws Exception{
        String ddl=OracleDocuments.capture(job,c,"PROCOBJ",str(object,"schema"),str(object,"objectName"),"DDL");
        object.put("ddl",rewrite(ddl,object,str(object,"schema"),Map.of())).put("supported",true).put("reason","");
    }
    static void review(ObjectNode source,ObjectNode old,String owner,Map<String,String> mapping,ArrayNode changes){
        String desired=rewrite(str(source,"ddl"),source,owner,mapping);
        if(old!=null&&OracleCompareSql.canonical(desired).equals(OracleCompareSql.canonical(str(old,"ddl")))&&source.path("schedulerEnabled").equals(old.path("schedulerEnabled")))return;
        if(old!=null&&!old.path("outsideDependents").isEmpty())throw new IllegalArgumentException("Destination Scheduler dependents outside captured scope: "+old.path("outsideDependents"));
        var change=changes.addObject().put("clause",old==null?"CREATE_SCHEDULER":"REPLACE_SCHEDULER").put("name",str(source,"name")).put("attribute","").put("destructive",old!=null);change.putArray("sql").add(desired);
    }
    static void incoming(Inventory source,Inventory target,Map<String,String> mapping){
        for(ObjectNode object:source.objects.values())if(str(object,"kind").equals("scheduler")&&hasDefinitionChange(object.path("oracleChanges"))){
            String owner=mapping.getOrDefault(str(object,"schema"),str(object,"schema")),identity=key(owner,"scheduler",str(object,"name"));
            for(ObjectNode dependent:target.objects.values())if(CompareSql.contains(dependent.path("dependencies"),identity)){
                String sourceOwner=mapping.entrySet().stream().filter(e->e.getValue().equals(str(dependent,"schema"))).map(Map.Entry::getKey).findFirst().orElse(str(dependent,"schema"));
                if(!source.objects.containsKey(key(sourceOwner,str(dependent,"kind"),str(dependent,"name")))){object.put("supported",false).put("reason","Destination-only incoming Scheduler dependent: "+str(dependent,"name"));object.withArray("oracleChanges").removeAll();}
            }
        }
        boolean changed;
        do{changed=false;for(ObjectNode object:source.objects.values()){
            if(!str(object,"kind").equals("scheduler")||!str(object,"schedulerCategory").equals("jobs")||!object.path("supported").asBoolean()||hasDefinitionChange(object.path("oracleChanges")))continue;
            for(JsonNode dependency:object.path("dependencies")){
                ObjectNode parent=source.objects.get(dependency.asText());if(parent==null||!str(parent,"kind").equals("scheduler")||!hasDefinitionChange(parent.path("oracleChanges")))continue;
                String owner=mapping.getOrDefault(str(object,"schema"),str(object,"schema"));if(!target.objects.containsKey(key(owner,"scheduler",str(object,"name"))))continue;
                var change=object.withArray("oracleChanges").addObject().put("clause","RECREATE_SCHEDULER").put("name",str(object,"name")).put("attribute","Changed scheduler dependency").put("destructive",true);change.putArray("sql").add(rewrite(str(object,"ddl"),object,owner,mapping));changed=true;break;
            }
        }}while(changed);
    }
    static boolean hasDefinitionChange(JsonNode changes){for(JsonNode change:changes)if(str(change,"clause").endsWith("_SCHEDULER"))return true;return false;}
    static void prepare(CompareSql.Plan plan,List<CompareSql.Choice> ordered){
        // Drop incoming jobs first. Native DROP uses force=FALSE so a running job stops the script.
        var reverse=new ArrayList<>(ordered);Collections.reverse(reverse);
        for(var choice:reverse){JsonNode object=choice.source();if(!str(object,"kind").equals("scheduler")||!hasDefinitionChange(object.path("oracleSelectedChanges")))continue;
            String name=OracleDialect.qualified(plan.schema(str(object,"schema")),str(object,"objectName"));
            if(choice.destination()!=null){
                for(var dependent:plan.source.objects.entrySet())if(CompareSql.contains(dependent.getValue().path("dependencies"),key(str(object,"schema"),"scheduler",str(object,"name")))){
                    var selected=plan.selected.get(dependent.getKey());if(selected==null||!hasDefinitionChange(selected.source().path("oracleSelectedChanges")))throw new IllegalArgumentException("Select the Scheduler recreation for incoming dependent "+str(dependent.getValue(),"name"));
                }
                String method=switch(str(object,"schedulerCategory")){case "jobs"->"DROP_JOB";case "programs"->"DROP_PROGRAM";case "schedules"->"DROP_SCHEDULE";default->throw new IllegalArgumentException("Unsupported Scheduler category");};
                plan.before.add("BEGIN SYS.DBMS_SCHEDULER."+method+"("+literal(name)+",force=>FALSE); END;");
            }
        }
        // Enable programs before their jobs, after all row changes, constraints and sequence state.
        for(var choice:ordered){JsonNode object=choice.source();if(str(object,"kind").equals("scheduler")&&hasDefinitionChange(object.path("oracleSelectedChanges"))&&object.path("schedulerEnabled").asBoolean())plan.finish.add("BEGIN SYS.DBMS_SCHEDULER.ENABLE("+literal(OracleDialect.qualified(plan.schema(str(object,"schema")),str(object,"objectName")))+"); END;");}
        if(!plan.finish.isEmpty())plan.warnings.add("Scheduler programs and jobs are enabled only after all generated changes. Run with Scheduler activity paused for the maintenance window; enabled jobs may then start immediately.");
    }
    static List<String> names(String name){
        var tokens=significant(name);var out=new ArrayList<String>();boolean identifier=true;
        for(var token:tokens){if(identifier){if(!token.identifier())throw new IllegalArgumentException("Unresolved Scheduler identifier");out.add(token.name());}else if(!token.text().equals("."))throw new IllegalArgumentException("Unresolved Scheduler identifier");identifier=!identifier;}
        if(identifier||out.size()>3)throw new IllegalArgumentException("Unresolved Scheduler identifier");return out;
    }
    static String literal(String value){return "'"+value.replace("'","''")+"'";}
    private static String value(String expression){var t=significant(expression);if(t.size()!=1||!t.getFirst().text().startsWith("'")||!t.getFirst().text().endsWith("'"))throw new IllegalArgumentException("Scheduler identifier/attribute must be a native string literal");return t.getFirst().text().substring(1,t.getFirst().text().length()-1).replace("''","'");}
    private static List<OracleCompareSql.Token> significant(String sql){return OracleCompareSql.tokens(sql).stream().filter(t->!t.text().isBlank()&&!t.text().startsWith("--")&&!t.text().startsWith("/*")).toList();}
    private record Arg(String name,String expression){}
    static String rewrite(String ddl,JsonNode object,String owner,Map<String,String> mapping){
        List<OracleCompareSql.Token> t=significant(ddl);int i=0,creates=0;StringBuilder out=new StringBuilder("BEGIN\n");if(t.isEmpty()||!t.get(i++).name().equals("BEGIN"))throw new IllegalArgumentException("Unsupported native Scheduler block");
        while(i<t.size()&&!t.get(i).name().equals("END")){
            if(t.get(i).name().equals("COMMIT")){if(++i>=t.size()||!t.get(i++).text().equals(";"))throw new IllegalArgumentException("Unsupported Scheduler COMMIT");continue;}
            if(t.get(i).name().equals("SYS")){i++;if(i>=t.size()||!t.get(i++).text().equals("."))throw new IllegalArgumentException("Invalid Scheduler package");}
            if(i+3>=t.size()||!t.get(i++).name().equals("DBMS_SCHEDULER")||!t.get(i++).text().equals("."))throw new IllegalArgumentException("Only native SYS.DBMS_SCHEDULER calls can be transferred");
            String method=t.get(i++).name();if(!t.get(i++).text().equals("("))throw new IllegalArgumentException("Invalid Scheduler call");
            var args=new ArrayList<Arg>();int start=i,depth=0;
            for(;i<t.size();i++){String x=t.get(i).text();if(x.equals("(") ){depth++;continue;}if(x.equals(")")&&depth>0){depth--;continue;}if(depth==0&&(x.equals(",")||x.equals(")"))){
                if(i>start){List<OracleCompareSql.Token> a=t.subList(start,i);String name="";if(a.size()>3&&a.get(0).identifier()&&a.get(1).text().equals("=")&&a.get(2).text().equals(">")){name=a.get(0).name();a=a.subList(3,a.size());}args.add(new Arg(name,join(a)));}else if(!args.isEmpty()||x.equals(","))throw new IllegalArgumentException("Empty Scheduler argument");start=i+1;if(x.equals(")")){i++;break;}
            }}
            if(i>=t.size()||!t.get(i++).text().equals(";"))throw new IllegalArgumentException("Invalid Scheduler statement");
            // Oracle's exporter emits an internal calendar-check bypass. Use normal validated CREATE_SCHEDULE instead.
            if(method.equals("DISABLE1_CALENDAR_CHECK")&&args.isEmpty())continue;
            if(!Set.of("CREATE_JOB","CREATE_PROGRAM","CREATE_SCHEDULE","SET_ATTRIBUTE","SET_ATTRIBUTE_NULL","ENABLE").contains(method)||args.isEmpty())throw new IllegalArgumentException("Unvalidated native Scheduler method: "+method);
            List<String> self=names(value(args.getFirst().expression()));if(!self.getLast().equals(str(object,"objectName"))||self.size()>2||self.size()==2&&!self.getFirst().equals(str(object,"schema"))&&!self.getFirst().equals(owner))throw new IllegalArgumentException("Scheduler export references a different object");
            args.set(0,new Arg(args.getFirst().name(),literal(OracleDialect.qualified(owner,str(object,"objectName")))));
            if(method.equals("ENABLE")){if(args.size()!=1)throw new IllegalArgumentException("Unsupported Scheduler enable options");continue;}
            if(method.startsWith("CREATE_")){creates++;String expected=switch(str(object,"schedulerCategory")){case "jobs"->"CREATE_JOB";case "programs"->"CREATE_PROGRAM";case "schedules"->"CREATE_SCHEDULE";default->"";};if(!method.equals(expected))throw new IllegalArgumentException("Unexpected Scheduler creation call");}
            if(method.startsWith("SET_ATTRIBUTE")){if(args.size()<2||!ATTRIBUTES.contains(value(args.get(1).expression()).toUpperCase(Locale.ROOT)))throw new IllegalArgumentException("Unvalidated Scheduler attribute");}
            for(int n=1;n<args.size();n++){
                Arg arg=args.get(n);String expression=arg.expression();
                if(arg.name().equals("ENABLED")||method.equals("CREATE_PROGRAM")&&n==4)expression="FALSE";
                else if(REFERENCES.contains(arg.name())||method.equals("CREATE_PROGRAM")&&n==2){
                    if(arg.name().equals("JOB_ACTION")||method.equals("CREATE_PROGRAM")){if(str(object,"schedulerAction").isEmpty())throw new IllegalArgumentException("Unresolved Scheduler action");expression=literal(OracleCompareSql.remap(str(object,"schedulerAction"),mapping));}
                    else {List<String> name=names(value(expression));if(name.size()>2)throw new IllegalArgumentException("Invalid Scheduler reference");String from=name.size()==1?str(object,"schema"):name.getFirst();expression=literal(OracleDialect.qualified(mapping.getOrDefault(from,from),name.getLast()));}
                }
                safeExpression(expression);args.set(n,new Arg(arg.name(),expression));
            }
            out.append("SYS.DBMS_SCHEDULER.").append(method).append('(');for(int n=0;n<args.size();n++){if(n>0)out.append(',');Arg arg=args.get(n);if(!arg.name().isEmpty())out.append(arg.name()).append("=>");out.append(arg.expression());}out.append(");\n");
        }
        if(creates!=1||i+2!=t.size()||!t.get(i).name().equals("END")||!t.get(i+1).text().equals(";"))throw new IllegalArgumentException("Incomplete or compound Scheduler export");return out.append("COMMIT;\nEND;").toString();
    }
    private static String join(List<OracleCompareSql.Token> t){return String.join("",t.stream().map(OracleCompareSql.Token::text).toList());}
    private static void safeExpression(String expression){
        for(var token:significant(expression))if(token.identifier()&&!Set.of("TRUE","FALSE","NULL","TO_TIMESTAMP_TZ","TO_DSINTERVAL").contains(token.name()))throw new IllegalArgumentException("Unvalidated Scheduler argument expression: "+token.name());
        for(var token:significant(expression))if(!token.identifier()&&!token.text().startsWith("'")&&!token.text().matches("[0-9(),.+-]"))throw new IllegalArgumentException("Unvalidated Scheduler argument token");
    }
}
