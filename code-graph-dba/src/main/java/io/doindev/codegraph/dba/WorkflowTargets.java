package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Exact target resolution and existing catalog authorization shared by schema workflows. */
final class WorkflowTargets {
    record Target(ObjectNode profile,ObjectNode scope,ObjectNode request,String authorization){}
    private final Profiles profiles;private final AgentAccess agents;private final ProjectContexts contexts;private final ReusableApprovals policies;
    WorkflowTargets(Profiles profiles,AgentAccess agents,ProjectContexts contexts,ReusableApprovals policies){this.profiles=profiles;this.agents=agents;this.contexts=contexts;this.policies=policies;}
    /** Explicit standalone inventory scope: an omitted schema means the selected database, never another catalog. */
    Target cached(String principal,String session,String operation,JsonNode input){return cached(principal,session,operation,input,true);}
    Target cached(String principal,String session,String operation,JsonNode input,boolean recordAudit){
        if(input.has("bindingId"))throw new IllegalArgumentException("Use the project-bound catalog path for a binding");
        if(!agents.alive("agent:"+principal))throw new SecurityException("Agent revoked");
        agents.authorization.requireSession(principal,session);
        ObjectNode profile=profiles.get(Profiles.text(input,"connectionId",36));
        if(!profile.path("name").asText().equals(Profiles.text(input,"connectionName",120)))throw new IllegalArgumentException("Exact connectionName must match connectionId");
        ObjectNode request=explicitCacheTarget(profile,input);
        ObjectNode scope=request.deepCopy();scope.remove("connectionName");
        scope.put("vendor",profile.path("templateId").asText()).put("profileRevision",ProjectContexts.profileRevision(profile));
        if(agents.authorization.automatic){if(recordAudit)agents.authorization.audit(principal,session,operation,scope);return new Target(profile,scope,request,"startup_yolo");}
        boolean allowed=agents.permitsRead(principal,"catalog",Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()));
        String reason="Existing connection catalog permission",policy="legacy-catalog";
        if(!allowed){
            if(DatabaseTransport.of(profile)!=DatabaseTransport.JDBC)throw new SecurityException("Native catalog permission required; SQL policies do not authorize native observations");
            var category=new ReusableOperation.Result("ddl_inspection",true,true,"Bounded cached catalog observation");
            ObjectNode invocation=Profiles.JSON.createObjectNode().put("sql",operation).put("autoCommit",false);invocation.set("parameters",input);
            ObjectNode match=policies.match(principal,session,invocation,scope,category);
            if(match==null)throw new SecurityException("Catalog permission required for this exact standalone connection/database/schema");
            policy=match.path("id").asText();policies.require(policy,principal,session,invocation,scope,category);reason="Scoped reusable catalog permission";
        }
        if(recordAudit)policies.auditUse(policy,principal,operation);
        return new Target(profile,scope,request,reason);
    }
    static ObjectNode explicitCacheTarget(ObjectNode profile,JsonNode input){
        if(DatabaseTransport.of(profile)!=DatabaseTransport.JDBC){
            var target=NativeTarget.resolve(profile,input);if(!target.collection().isEmpty())throw new IllegalArgumentException("Cached catalog scope is the selected database, not a collection");
            return Profiles.JSON.createObjectNode().put("connectionId",target.connectionId()).put("connectionName",target.connectionName()).put("database",target.database());
        }
        String database=Profiles.text(input,"database",128),schema=ProjectContexts.bounded(input,"schema",128);
        if(database.contains("$"+"{")||schema.contains("$"+"{"))throw new IllegalArgumentException("Use explicit database/schema names, not environment interpolation");
        if(java.util.Set.of("mysql","mariadb").contains(profile.path("templateId").asText())){
            if(!schema.isEmpty()&&!schema.equals(database))throw new IllegalArgumentException("MySQL/MariaDB schema must equal database");
            schema=database;
        }
        return Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText())
                .put("connectionName",profile.path("name").asText()).put("database",database).put("schema",schema);
    }
    Target catalog(String principal,String session,JsonNode input){
        if(!agents.alive("agent:"+principal))throw new SecurityException("Agent revoked");
        agents.authorization.requireSession(principal,session);
        ObjectNode binding=Profiles.JSON.createObjectNode(),profile;boolean allowed=false;
        ObjectNode request=Profiles.JSON.createObjectNode();
        for(String key:List.of("bindingId","connectionId","connectionName","database","schema","sampleLimit"))if(input.has(key))request.set(key,input.get(key));
        if(request.has("bindingId")){
            for(String key:List.of("connectionId","connectionName","database","schema"))if(request.has(key))throw new IllegalArgumentException("A binding fixes the complete database target; no overrides are allowed");
            binding=contexts.authorized(principal,Profiles.text(request,"bindingId",36),true);
            try{contexts.authorized(principal,binding.path("id").asText(),false);allowed=true;}catch(SecurityException denied){}
            profile=profiles.get(binding.path("connectionId").asText());
        }else{
            profile=profiles.get(Profiles.text(request,"connectionId",36));
            if(!profile.path("name").asText().equals(Profiles.text(request,"connectionName",120)))throw new IllegalArgumentException("Exact connectionName must match connectionId");
            allowed=agents.permitsRead(principal,"catalog",Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()));
        }
        if(DatabaseTransport.of(profile)!=DatabaseTransport.JDBC){
            ObjectNode selector=request.deepCopy();
            if(binding.has("id")){
                selector.put("connectionId",profile.path("id").asText()).put("connectionName",profile.path("name").asText()).put("database",binding.path("database").asText());
                if(!binding.path("schema").asText().isEmpty())throw new IllegalArgumentException("Native bindings cannot have a SQL schema");
            }
            NativeTarget target=NativeTarget.resolve(profile,selector);ProjectContexts.number(request,"sampleLimit",0,0,32);
            if(!allowed&&!agents.authorization.automatic)throw new SecurityException("Native catalog permission is required; SQL reusable grants do not authorize native snapshots");
            ObjectNode scope=target.json().put("profileRevision",ProjectContexts.profileRevision(profile));
            if(binding.has("id")){
                for(String key:List.of("projectId","environment","role"))scope.set(key,binding.path(key));
                scope.put("bindingId",binding.path("id").asText()).put("bindingRevision",ProjectContexts.profileRevision(binding));
            }
            if(agents.authorization.automatic)agents.authorization.audit(principal,session,"native_schema_observation",scope);
            else policies.auditUse("legacy-native-catalog",principal,"native_schema_observation");
            return new Target(profile,scope,request,agents.authorization.automatic?"startup_yolo":"Existing native catalog read permission");
        }
        if(request.path("sampleLimit").asInt()!=0)throw new IllegalArgumentException("sampleLimit applies only to native MongoDB observations");
        ObjectNode scope=agents.authorization.scope(profile,binding,request);
        if(scope.path("database").asText().isBlank()||scope.path("schema").asText().isBlank())throw new IllegalArgumentException("Schema capture requires an unambiguous database and schema; supply explicit standalone targeting or a binding");
        String reason="Existing catalog read permission",policy="legacy-catalog";
        if(agents.authorization.automatic){agents.authorization.audit(principal,session,"schema_observation",scope);return new Target(profile,scope,request,"startup_yolo");}
        if(!allowed){
            var operation=new ReusableOperation.Result("ddl_inspection",true,true,"Bounded schema observation");
            ObjectNode invocation=Profiles.JSON.createObjectNode().put("sql","dba_capture_schema").put("autoCommit",false);invocation.set("parameters",request);
            ObjectNode match=policies.match(principal,session,invocation,scope,operation);
            if(match==null)throw new SecurityException("Catalog permission is required for this exact schema. Use reviewed catalog access; legacy object-only grants are not broadened to a schema inventory.");
            policy=match.path("id").asText();policies.require(policy,principal,session,invocation,scope,operation);reason="Scoped reusable DDL-inspection permission";
        }
        policies.auditUse(policy,principal,"schema_observation");
        return new Target(profile,scope,request,reason);
    }
}
