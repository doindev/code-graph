package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Shared matcher for versioned SELECT, metadata and cached-observation permissions. */
final class ReadPermissions {
    static final String ACTION="allow_selects";
    static final class Conflict extends IllegalArgumentException { Conflict(){super("Permission changed; reload before saving");} }
    final ReusableApprovals store;private final Profiles profiles;private final AgentAccess agents;
    private java.util.function.Consumer<String> changed=id->{};
    ReadPermissions(ReusableApprovals store,Profiles profiles,AgentAccess agents){this.store=store;this.profiles=profiles;this.agents=agents;}
    void onChange(java.util.function.Consumer<String> listener){changed=listener;}
    static void fields(JsonNode n,Set<String> allowed){if(!n.isObject())throw new IllegalArgumentException("Expected an object");n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unknown property: "+k);});}
    ObjectNode prepare(String principal,String session,JsonNode input,String id,Long revision){
        agents.agent(principal);fields(input,Set.of("selectors","lifetime","sessionLabel","enabled","revision"));
        String lifetime=input.path("lifetime").asText();if(!Set.of("mcp_session","until_revoked").contains(lifetime))throw new IllegalArgumentException("Choose a permission duration");
        if(lifetime.equals("mcp_session")&&(session==null||!store.sessions.alive(session,principal)))throw new IllegalArgumentException("Choose a live MCP session belonging to this identity");
        JsonNode selectors=input.path("selectors");if(!selectors.isArray()||selectors.isEmpty()||selectors.size()>256)throw new IllegalArgumentException("Select 1..256 permission targets");
        ObjectNode policy=Profiles.JSON.createObjectNode().put("kind","select_read").put("version",2).put("id",id==null?UUID.randomUUID().toString():id).put("revision",revision==null?1:revision+1)
            .put("agentId",principal).put("identity",agents.isTrustedLocal(principal)?"Trusted local agents":agents.agent(principal).path("name").asText()).put("lifetime",lifetime)
            .put("enabled",input.path("enabled").asBoolean(true)).put("createdAt",System.currentTimeMillis()).put("lastUsedAt",0);
        if(input.has("enabled")&&!input.path("enabled").isBoolean())throw new IllegalArgumentException("enabled must be a boolean");
        if(lifetime.equals("mcp_session"))policy.put("session",session);
        if(id!=null)for(ObjectNode old:store.readEntries(principal))if(old.path("id").asText().equals(id)){policy.set("createdAt",old.path("createdAt"));policy.set("lastUsedAt",old.path("lastUsedAt"));}
        ArrayNode checked=policy.putArray("selectors");Set<String> unique=new HashSet<>();
        for(JsonNode s:selectors){
            fields(s,Set.of("connectionId","level","database","schema","object"));
            String connection=Profiles.text(s,"connectionId",36),level=Profiles.text(s,"level",20);ObjectNode profile=profiles.get(connection);
            String vendor=profile.path("templateId").asText();if(!ReadQueries.VENDORS.contains(vendor))throw new IllegalArgumentException("This connection has no verified SELECT permission adapter");
            if(!Set.of("connection","database","schema","object").contains(level))throw new IllegalArgumentException("Unknown permission scope");
            ObjectNode v=Profiles.JSON.createObjectNode().put("connectionId",connection).put("level",level).put("profileRevision",ProjectContexts.profileRevision(profile));
            if(!level.equals("connection"))v.put("database",name(s,"database"));else if(s.has("database"))throw new IllegalArgumentException("Connection scope must not include a database");
            if(Set.of("schema","object").contains(level)){
                String schema=name(s,"schema");if(Set.of("mysql","mariadb").contains(vendor)&&!schema.equals(v.path("database").asText()))throw new IllegalArgumentException("MySQL/MariaDB schema must equal database");v.put("schema",schema);
            }else if(s.has("schema"))throw new IllegalArgumentException("This scope must not include a schema");
            if(level.equals("object"))v.put("object",name(s,"object"));else if(s.has("object"))throw new IllegalArgumentException("This scope must not include an object");
            if(!unique.add(v.toString()))throw new IllegalArgumentException("Duplicate permission target");checked.add(v);
        }
        return policy;
    }
    private static String name(JsonNode n,String key){String s=Profiles.text(n,key,128);if(s.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid identifier");return s;}
    ObjectNode save(String reviewer,ObjectNode prepared,Long revision){
        String principal=prepared.path("agentId").asText();store.auditUse(prepared.path("id").asText(),principal,revision==null?"human_create_select_permission":"human_update_select_permission");
        ObjectNode result=store.saveRead(prepared,revision);changed.accept(principal);return result;
    }
    List<ObjectNode> active(String principal,String session,JsonNode scope){
        if(!agents.alive("agent:"+principal)||!store.sessions.alive(session,principal))return List.of();
        List<ObjectNode> out=new ArrayList<>();for(ObjectNode p:store.readEntries(principal))if(p.path("enabled").asBoolean()&&(!p.has("session")||p.path("session").asText().equals(session)))out.add(p);return out;
    }
    private boolean current(JsonNode selector,JsonNode scope){
        if(!selector.path("connectionId").equals(scope.path("connectionId")))return false;
        try{return selector.path("profileRevision").asText().equals(ProjectContexts.profileRevision(profiles.get(selector.path("connectionId").asText())));}catch(IllegalArgumentException removed){return false;}
    }
    static boolean covers(JsonNode selector,ReadQueries.Relation relation){
        String level=selector.path("level").asText();if(level.equals("connection"))return true;
        if(!selector.path("database").asText().equals(relation.database()))return false;
        if(level.equals("database"))return true;
        if(!selector.path("schema").asText().equals(relation.schema()))return false;
        return level.equals("schema")||selector.path("object").asText().equals(relation.object());
    }
    ArrayNode match(String principal,String session,JsonNode scope,List<ReadQueries.Relation> relations){return match(active(principal,session,scope),scope,relations);}
    ArrayNode match(List<ObjectNode> policies,JsonNode scope,List<ReadQueries.Relation> relations){
        ArrayNode proof=Profiles.JSON.createArrayNode();Set<String> used=new HashSet<>();
        if(relations.isEmpty())relations=List.of(new ReadQueries.Relation(scope.path("database").asText(),scope.path("schema").asText(),""));
        for(var relation:relations){
            ObjectNode found=null;
            for(ObjectNode p:policies){for(JsonNode selector:p.path("selectors"))if(current(selector,scope)&&covers(selector,relation)){found=p;break;}if(found!=null)break;}
            if(found==null)return null;
            if(used.add(found.path("id").asText()))proof.addObject().put("id",found.path("id").asText()).put("revision",found.path("revision").asLong());
        }
        return proof;
    }
    ArrayNode catalogMatch(String principal,String session,JsonNode scope,String object){
        return catalogMatch(active(principal,session,scope),scope,object);
    }
    ArrayNode catalogMatch(List<ObjectNode> policies,JsonNode scope,String object){
        if(!object.isEmpty())return match(policies,scope,List.of(new ReadQueries.Relation(scope.path("database").asText(),scope.path("schema").asText(),object)));
        ArrayNode proof=Profiles.JSON.createArrayNode();
        for(ObjectNode p:policies)for(JsonNode s:p.path("selectors"))if(current(s,scope)&&intersects(s,scope)){
            proof.addObject().put("id",p.path("id").asText()).put("revision",p.path("revision").asLong());break;
        }
        return proof.isEmpty()?null:proof;
    }
    ArrayNode catalogMatch(String principal,String session,JsonNode scope,JsonNode catalog){
        return catalogMatch(active(principal,session,scope),scope,catalog);
    }
    ArrayNode catalogMatch(List<ObjectNode> policies,JsonNode scope,JsonNode catalog){
        String kind=catalog.path("kind").asText(),type=catalog.path("objectType").asText("table");
        if(Set.of("function","procedure","database").contains(type)){
            JsonNode selected=scope;if(type.equals("database")){ObjectNode copy=scope.deepCopy();copy.put("schema","");selected=copy;}
            return match(policies,selected,List.of());
        }
        if(!Set.of("databases","schemas").contains(kind))return catalogMatch(policies,scope,catalog.path("object").asText());
        ArrayNode proof=Profiles.JSON.createArrayNode();for(ObjectNode p:policies)for(JsonNode s:p.path("selectors"))if(current(s,scope)&&(kind.equals("databases")||s.path("level").asText().equals("connection")||s.path("database").equals(scope.path("database")))){
            proof.addObject().put("id",p.path("id").asText()).put("revision",p.path("revision").asLong());break;
        }
        return proof.isEmpty()?null:proof;
    }
    static String metadataObject(JsonNode row){
        String kind=row.path("kind").asText();return kind.isEmpty()||Set.of("table","base_table","view","materialized_view","foreign_table","partitioned_table").contains(kind)?row.path("name").asText():"";
    }
    boolean coveredByProof(String principal,String session,JsonNode scope,JsonNode proof,ReadQueries.Relation relation){
        require(principal,session,proof);List<ObjectNode> selected=store.readEntries(principal).stream().filter(p->{for(JsonNode used:proof)if(used.path("id").equals(p.path("id")))return true;return false;}).toList();
        return match(selected,scope,List.of(relation))!=null;
    }
    private boolean parentCovered(String principal,String session,JsonNode scope,JsonNode proof,String database,String schema){
        require(principal,session,proof);for(ObjectNode p:store.readEntries(principal))for(JsonNode used:proof)if(used.path("id").equals(p.path("id")))for(JsonNode s:p.path("selectors"))if(current(s,scope)&&
            (s.path("level").asText().equals("connection")||s.path("database").asText().equals(database)&&(schema.isEmpty()||!s.has("schema")||s.path("schema").asText().equals(schema))))return true;
        return false;
    }
    static boolean intersects(JsonNode s,JsonNode scope){
        return s.path("level").asText().equals("connection")||(s.path("database").equals(scope.path("database"))&&(scope.path("schema").asText().isEmpty()||!s.has("schema")||s.path("schema").equals(scope.path("schema"))));
    }
    void require(String principal,String session,JsonNode proof){
        if(!agents.alive("agent:"+principal)||!store.sessions.alive(session,principal))throw new SecurityException("SELECT permission session ended");
        List<ObjectNode> current=store.readEntries(principal);
        if(!proof.isArray()||proof.isEmpty())throw new SecurityException("No SELECT permission covers this request");
        for(JsonNode used:proof){
            ObjectNode p=current.stream().filter(v->v.path("id").equals(used.path("id"))).findFirst().orElseThrow(()->new SecurityException("SELECT permission revoked"));
            if(!p.path("enabled").asBoolean()||p.path("revision").asLong()!=used.path("revision").asLong()||p.has("session")&&!p.path("session").asText().equals(session))throw new SecurityException("SELECT permission changed; request authorization again");
            for(JsonNode s:p.path("selectors"))if(!s.path("profileRevision").asText().equals(ProjectContexts.profileRevision(profiles.get(s.path("connectionId").asText()))))throw new SecurityException("Permission connection changed");
        }
    }
    void filterMetadata(String principal,String session,JsonNode scope,JsonNode catalog,JsonNode proof,ObjectNode result){
        if(!catalog.path("object").asText().isEmpty()&&!Set.of("databases","schemas").contains(catalog.path("kind").asText())||catalog.path("objectType").asText().equals("database"))return;
        JsonNode columns=result.path("columns");int table=-1,schema=-1,databaseColumn=-1;
        for(int i=0;i<columns.size();i++){String name=columns.get(i).path("label").asText(columns.get(i).path("name").asText(columns.get(i).asText())).toLowerCase(Locale.ROOT);if(name.equals("table_name"))table=i;if(name.equals("table_schema")||name.equals("schema_name"))schema=i;if(name.equals("database_name"))databaseColumn=i;}
        if(table<0&&databaseColumn<0&&schema<0)throw new SecurityException("Metadata projection cannot be safely filtered");
        ArrayNode rows=(ArrayNode)result.path("rows");int removed=0;int fetched=rows.size();
        for(int i=rows.size()-1;i>=0;i--){JsonNode row=rows.get(i);String sn=schema<0?scope.path("schema").asText():row.path(schema).asText();
            String db=databaseColumn>=0?row.path(databaseColumn).asText():Set.of("postgresql","oracle").contains(scope.path("vendor").asText())?scope.path("database").asText():sn;
            if(!(table<0?parentCovered(principal,session,scope,proof,db,schema<0?"":sn):coveredByProof(principal,session,scope,proof,new ReadQueries.Relation(db,sn,row.path(table).asText())))){rows.remove(i);removed++;}
        }
        result.put("rowCount",rows.size()).put("permissionFiltered",true).put("nextOffset",catalog.path("offset").asInt()+fetched);if(removed>0)result.put("coverage","Only objects covered by active SELECT permissions; server result limits still apply");
    }
    void used(String principal,JsonNode proof,String operation){for(JsonNode p:proof)store.auditUse(p.path("id").asText()+":"+p.path("revision").asLong(),principal,operation);store.touchReads(principal,proof);}
}
