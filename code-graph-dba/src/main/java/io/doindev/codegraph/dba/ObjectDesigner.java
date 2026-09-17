package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;

/** Browser-owned object drafts. Apply executes only the statements retained during review. */
final class ObjectDesigner {
    static final Set<String> GROUPS;
    static {
        var kinds=new HashSet<>(MetadataActions.OBJECT_PARENTS);
        kinds.remove("relation");
        kinds.addAll(List.of("table_columns","table_constraints","table_foreign_keys","table_indexes",
                "table_triggers","table_policies","table_rules","table_partitions"));
        GROUPS=Set.copyOf(kinds);
    }
    static String str(JsonNode n,String key){return n.path(key).asText("");}
    static String kind(JsonNode n){String k=str(n,"kind");return switch(k){
        case "table_columns"->"columns";case "table_constraints","table_foreign_keys"->"constraints";
        case "table_indexes"->"indexes";case "table_triggers"->"triggers";case "table_policies"->"policies";
        case "table_rules"->"rules";case "table_partitions"->"partitions";default->k;};}
    static String label(String kind){return switch(kind){
        case "materialized_views"->"Materialized View";case "foreign_tables"->"Foreign Table";
        case "external_tables"->"External Table";case "types"->"Type";case "indexes"->"Index";
        case "policies"->"Policy";case "schemas"->"Schema";case "sequences"->"Sequence";
        case "functions"->"Function";case "procedures"->"Procedure";case "views"->"View";
        case "aliases"->"Alias";
        case "table_columns","columns"->"Column";case "constraints"->"Constraint";
        default->{String text=kind.replace('_',' ');if(text.endsWith("s"))text=text.substring(0,text.length()-1);yield text.isEmpty()?"Object":Character.toUpperCase(text.charAt(0))+text.substring(1);}
    };}
    static ObjectNode load(QueryJobs.Job job,Connection c,JsonNode input,boolean generic)throws Exception{
        boolean creating=input.path("creation").asBoolean();
        ObjectNode parent=MetadataTree.request(creating?input.path("target"):input.path("parent"));
        if(!GROUPS.contains(str(parent,"kind")))throw new IllegalArgumentException("Choose an object category in the database tree");
        String engine=generic?"jdbc":ObjectCreation.engine(c),kind=kind(parent);
        String quote=Objects.toString(c.getMetaData().getIdentifierQuoteString(),"").strip();if(quote.isBlank())quote="\"";
        ObjectNode out=Profiles.JSON.createObjectNode().put("engine",engine).put("kind",kind)
                .put("label",label(kind)).put("database",Objects.toString(c.getCatalog(),""))
                .put("quote",quote)
                .put("version",c.getMetaData().getDatabaseProductVersion()).put("creation",creating)
                .put("ddl","").put("ddlComplete",false);
        out.set("target",parent);if(!creating)out.set("selection",MetadataActions.request(input));
        // Creation has no catalog key. Keep its target separate from the persisted object identity.
        if(creating)out.remove("selection");
        ObjectNode values=out.putObject("fields");values.put("name","").put("schema",str(parent,"schema"));
        values.put("owner","").put("comment","");
        out.putArray("categories").add("General");out.putArray("controls");out.putObject("details");out.putArray("warnings");
        JsonNode node=null;
        if(!creating){
            for(JsonNode candidate:MetadataTree.browse(job,c,parent,job.remainingSeconds()).path("nodes"))
                if(str(candidate,"key").equals(str(input,"key"))){node=candidate;break;}
            if(node==null)throw new IllegalArgumentException("Object changed or is no longer on this page. Refresh its parent and reopen it.");
            out.set("node",node);values.put("name",node.path("objectName").asText(str(node,"name")));
        }
        ObjectCatalog.populate(job,c,out,node);
        ObjectForms.configure(c,out);
        MaterializedViewSchedules.populate(job,c,out);
        category(out,"DDL");
        ArrayNode ordered=Profiles.JSON.createArrayNode();for(String name:List.of("General","Definition","Columns","Parameters","Return Type"))if(out.path("categories").toString().contains("\""+name+"\""))ordered.add(name);
        java.util.stream.StreamSupport.stream(out.path("categories").spliterator(),false).map(JsonNode::asText).filter(n->!List.of("General","Definition","Columns","Parameters","Return Type","Advanced","Datatypes","DDL").contains(n)).sorted().forEach(ordered::add);
        for(String name:List.of("Advanced","Datatypes","DDL"))for(JsonNode n:out.path("categories"))if(n.asText().equals(name))ordered.add(name);out.set("categories",ordered);
        fingerprint(c,out);
        if(Profiles.JSON.writeValueAsBytes(out).length>1<<20)throw new IllegalArgumentException("Object metadata exceeds the 1 MiB editor limit");
        return out;
    }
    static void fingerprint(Connection c,ObjectNode out)throws Exception {
        // Stable catalog metadata and schedule configuration participate in conflict detection.
        // Run history, last/next run, and other volatile scheduler status remain outside it.
        ObjectNode identity=Profiles.JSON.createObjectNode().put("engine",str(out,"engine")).put("database",str(out,"database"));
        identity.set("target",out.path("target"));identity.set("fields",out.path("fields"));identity.put("ddl",str(out,"ddl"));
        if(out.has("refreshSchedule"))identity.set("refreshSchedule",MaterializedViewSchedules.stable(out.path("refreshSchedule")));
        if(out.path("details").has("Permissions"))identity.set("permissions",out.path("details").path("Permissions"));
        if(out.has("node"))identity.set("node",out.path("node"));if(out.has("replacement"))identity.set("replacement",out.path("replacement"));
        out.put("fingerprint",TableDesigner.hash(identity));
        out.put("connectionFingerprint",TableDesigner.hash(Profiles.JSON.createObjectNode()
                .put("url",c.getMetaData().getURL()).put("user",c.getMetaData().getUserName())
                .put("database",Objects.toString(c.getCatalog(),""))));
    }
    static void category(ObjectNode out,String name){ArrayNode categories=(ArrayNode)out.path("categories");for(JsonNode n:categories)if(n.asText().equals(name))return;categories.add(name);}
    static void detail(ObjectNode out,String category,JsonNode data){category(out,category);((ObjectNode)out.path("details")).set(category,data);}
    static void warning(ObjectNode out,String text){((ArrayNode)out.path("warnings")).add(text);}
    static ObjectNode prepare(ObjectNode snapshot,JsonNode input)throws Exception{
        if(!str(snapshot,"fingerprint").equals(str(input,"fingerprint")))throw new IllegalArgumentException("Object changed. Refresh and review your draft against the current database.");
        JsonNode draft=input.path("draft");if(!draft.isObject())throw new IllegalArgumentException("Object draft is required");
        boolean sqlMode=draft.path("sqlMode").asBoolean();
        List<String> objectCommands;
        if(sqlMode){String source=str(draft,"sql");objectCommands=source.isBlank()&&!snapshot.path("creation").asBoolean()?List.of():sqlCommands(draft);}
        else objectCommands=ObjectForms.compile(snapshot,draft);
        List<MaterializedViewSchedules.Command> scheduleCommands=MaterializedViewSchedules.compile(snapshot,draft);
        if(objectCommands.isEmpty()&&scheduleCommands.isEmpty())throw new IllegalArgumentException("There are no changes to save");
        if(objectCommands.size()+scheduleCommands.size()>128)throw new IllegalArgumentException("At most 128 statements can be reviewed together");
        String database=str(snapshot,"database");boolean oneDatabase=scheduleCommands.stream().allMatch(x->x.database().isBlank()||x.database().equals(database));
        boolean atomic=!sqlMode&&str(snapshot,"engine").equals("postgresql")&&!Set.of("databases","tablespaces").contains(str(snapshot,"kind"))&&oneDatabase;
        ObjectNode out=Profiles.JSON.createObjectNode().put("objectDesignerPlan",true)
                .put("atomic",atomic).put("risky",true).put("sqlMode",sqlMode).put("expiresAt",System.currentTimeMillis()+300000);
        out.set("snapshot",snapshot.deepCopy());out.set("draft",draft.deepCopy());
        ArrayNode sql=out.putArray("commands");int index=0;
        for(String command:objectCommands)sql.addObject().put("sql",command).put("database",database).put("phase","object").put("purpose",(snapshot.path("creation").asBoolean()?"Create ":"Update ")+str(snapshot,"label")+(objectCommands.size()>1?" (step "+(++index)+")":""));
        for(MaterializedViewSchedules.Command command:scheduleCommands)sql.addObject().put("sql",command.sql()).put("database",command.database()).put("phase",command.phase()).put("purpose",command.purpose());
        List<String> commands=new ArrayList<>();for(JsonNode command:sql)commands.add(command.path("sql").asText());out.put("sql",String.join(";\n\n",commands)+";");
        out.put("warning",sqlMode?"Execute the reviewed SQL on the displayed connection/database. Custom SQL can affect objects beyond this tab.":"Review the exact object and refresh-schedule changes. Definitions can run database code and acquire locks.");
        if(!oneDatabase||!atomic&&!objectCommands.isEmpty()&&!scheduleCommands.isEmpty())out.put("partialCommitWarning","Object definition and scheduler configuration cannot be atomic. If scheduling fails after object creation, the object remains and the Refresh page can retry only its schedule.");
        return out;
    }
    static List<String> sqlCommands(JsonNode draft){
        String sql=str(draft,"sql");if(sql.isBlank()||sql.length()>65536)throw new IllegalArgumentException("Enter 1–65536 characters of SQL in DDL");
        // A single JDBC unit preserves vendor routine bodies containing semicolons. Multiple units
        // are opt-in and use the same bounded lexical extraction as the Script editor.
        if(!draft.path("splitSql").asBoolean())return List.of(sql);
        return SqlScript.extract(sql).stream().map(SqlScript.Unit::sql).toList();
    }
    static ObjectNode annotate(ObjectNode result){
        for(JsonNode n:result.path("nodes"))if(GROUPS.contains(str(n,"kind"))&&n.path("branch").asBoolean())((ObjectNode)n).put("canCreate",true);
        return result;
    }
    private ObjectDesigner(){}
}
