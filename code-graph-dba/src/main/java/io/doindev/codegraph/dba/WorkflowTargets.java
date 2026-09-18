package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Exact target resolution and existing catalog authorization shared by schema workflows. */
final class WorkflowTargets {
    record Target(ObjectNode profile,ObjectNode scope,ObjectNode request,String authorization){}
    private final Profiles profiles;private final AgentAccess agents;private final ProjectContexts contexts;private final ReusableApprovals policies;
    WorkflowTargets(Profiles profiles,AgentAccess agents,ProjectContexts contexts,ReusableApprovals policies){this.profiles=profiles;this.agents=agents;this.contexts=contexts;this.policies=policies;}
    Target catalog(String principal,String session,JsonNode input){
        if(!agents.alive("agent:"+principal))throw new SecurityException("Agent revoked");
        ObjectNode binding=Profiles.JSON.createObjectNode(),profile;boolean allowed=false;
        ObjectNode request=Profiles.JSON.createObjectNode();
        for(String key:List.of("bindingId","connectionId","connectionName","database","schema"))if(input.has(key))request.set(key,input.get(key));
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
        ObjectNode scope=ApprovalScope.resolve(profile,binding,request);
        if(scope.path("database").asText().isBlank()||scope.path("schema").asText().isBlank())throw new IllegalArgumentException("Schema capture requires an unambiguous database and schema; supply explicit standalone targeting or a binding");
        String reason="Existing catalog read permission",policy="legacy-catalog";
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
