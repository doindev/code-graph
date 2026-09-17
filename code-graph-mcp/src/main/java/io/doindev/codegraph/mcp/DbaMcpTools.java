package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.dba.DbaRuntime;
import io.doindev.codegraph.tools.*;
import java.util.*;
import java.util.function.Supplier;

/** Local-identity catalog tools plus exact human approvals for administration and SQL. */
public final class DbaMcpTools {
    public static final String PRINCIPAL="codegraph.dba.principal";
    private static final ObjectMapper JSON=new ObjectMapper();
    private DbaMcpTools(){}
    public static List<GraphTool> tools(DbaRuntime runtime,Supplier<String> stdioPrincipal){
        List<GraphTool> tools=new ArrayList<>();
        tools.add(tool(runtime,stdioPrincipal,"dba_list_project_databases","List authorized application database bindings with canonical environment, logical role, purpose, scope, freshness and effective permissions. Listing alone does not renew activity.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_my_permissions","List the authenticated agent's legacy grants, exact binding grants, persistent read policies and effective application/environment access. Never returns credentials.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_list_my_created_connections","List stable IDs, names, creation request IDs, timestamps and bindings for profiles created through this agent. Creation never grants access.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_search_objects","Search an authorized application database snapshot and renew its activity lease. Returns bounded pages and freshness.",List.of("bindingId"),Map.of("query","string","kind","string","offset","integer","limit","integer","searchDefinitions","boolean")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_indexed_ddl","Read bounded DDL chunks from an authorized cached snapshot.",List.of("bindingId","objectId"),Map.of("offset","integer","length","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_indexed_properties","Read cached columns, indexes, primary keys, foreign keys or privileges in bounded pages.",List.of("bindingId","objectId"),Map.of("section","string","offset","integer","limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_database_dependencies","Read bounded cached dependency edges for an indexed object.",List.of("bindingId","objectId"),Map.of("offset","integer","limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_find_code_references","Find candidate code locations for an indexed database object; dynamic SQL can remain unresolved.",List.of("bindingId","objectId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_scan_status","Inspect snapshot freshness and scan state without renewing activity.",List.of("bindingId"),Map.of()));

        tools.add(tool(runtime,stdioPrincipal,"dba_list_connections","List stable connection IDs/names without credentials or URLs. Trusted local clients can discover connections without setup; database access still requires reviewed permission. Optional named-token clients retain their grant-filtered roster.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_metadata","List granted objects or capped column metadata.",List.of("connectionName"),Map.of("schema","string","object","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_object_ddl","Read PostgreSQL catalog definition fragments for a granted object; never executes DDL.",List.of("connectionName","schema","object"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_explain_query","Get a PostgreSQL estimated SELECT plan without executing it.",List.of("connectionName","sql"),Map.of("parameters","array")));
        tools.add(tool(runtime,stdioPrincipal,"dba_analyze_query_plan","Analyze an estimated SELECT plan without EXPLAIN ANALYZE or database ANALYZE.",List.of("connectionName","sql"),Map.of("parameters","array")));
        tools.add(tool(runtime,stdioPrincipal,"dba_execute_read_query","Run one structurally restricted SELECT asynchronously with at most 100 rows/1 MiB.",List.of("connectionName","sql"),Map.of("parameters","array")));
        tools.add(tool(runtime,stdioPrincipal,"dba_job_status","Get this agent's bounded job state/result.",List.of("jobId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_cancel_job","Request cancellation of this agent's job.",List.of("jobId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_release_job","Release this agent's completed result.",List.of("jobId"),Map.of()));

        boolean approvals=runtime==null||runtime.approvalsEnabled();
        if(approvals){
            tools.add(tool(runtime,stdioPrincipal,"dba_get_connection_details","Get an allowlisted non-secret profile by bindingId, or stable connectionId plus exact connectionName. Returns immediately with permission or creates a read approval request.",List.of("requestId","purpose"),Map.of("bindingId","string","connectionId","string","connectionName","string")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_create","Propose a connection profile, optional application binding, and optional explicit Maven driver installation. Password and secretProperties are write-only arguments that the MCP client/task transcript may retain; prefer human entry when possible. Creation grants no access.",List.of("requestId","purpose","profile"),Map.of("binding","object","driverInstall","object")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_update","Propose an exact connection profile change. Secrets are write-only and never returned or audited.",List.of("requestId","purpose","connectionId","connectionName","profile"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_delete","Request removal of application profile configuration, vault entries, pools, bindings and policies. Never drops the database or removes a container.",List.of("requestId","purpose","connectionId","connectionName"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_test","Request a safe connection test by bindingId, or stable connectionId plus exact name. Test results are ephemeral.",List.of("requestId","purpose"),Map.of("bindingId","string","connectionId","string","connectionName","string")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_binding_create","Propose associating an existing connection with an onboarded application, canonical environment, required logical role and purpose.",List.of("requestId","purpose","binding"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_binding_update","Propose environment, role, purpose, enabled, scan or idle changes. Immutable target changes require replacement.",List.of("requestId","purpose","bindingId","binding"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_binding_delete","Propose removing an application/database association without deleting the connection or database.",List.of("requestId","purpose","bindingId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_live_sql","Request exact SQL using either bindingId or a standalone connectionId plus exact connectionName. No project is required for standalone SQL; it uses the saved connection's configured database. Mutations always require one-time approval; structurally verified reads may use a persistent read policy.",List.of("requestId","sql","purpose"),Map.of("bindingId","string","connectionId","string","connectionName","string","parameters","array","autoCommit","boolean")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_status","Poll a generalized administration, read or SQL approval request.",List.of("requestId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_cancel_request","Cancel this agent's pending request or request cancellation of its job.",List.of("requestId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_cancel_live_request","Compatibility alias for cancelling a live-SQL approval.",List.of("approvalId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_live_request_status","Compatibility alias for polling a live-SQL approval.",List.of("approvalId"),Map.of()));
        }
        return List.copyOf(tools);
    }
    private static GraphTool tool(DbaRuntime runtime,Supplier<String> stdio,String name,String description,List<String> required,Map<String,String> optional){
        var schema=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var properties=schema.putObject("properties");
        for(String key:required)properties.putObject(key).put("type",Set.of("profile","binding").contains(key)?"object":"string");optional.forEach((key,type)->properties.putObject(key).put("type",type));schema.set("required",JSON.valueToTree(required));
        if(required.contains("connectionName")){properties.putObject("connectionId").put("type","string").put("description","Stable connection UUID; must match connectionName.");properties.withObject("connectionName").put("description","Exact saved connection name; no browser/default connection is inferred.");}
        if(name.equals("dba_request_live_sql")){
            properties.withObject("bindingId").put("description","Project-bound target. Do not combine with connectionId/connectionName.");
            properties.withObject("connectionId").put("description","Standalone saved connection UUID; requires its exact connectionName. No project binding is needed.");
            properties.withObject("connectionName").put("description","Exact saved name matching connectionId; no browser-selected connection is inferred.");
            var choices=schema.putArray("oneOf");var bound=choices.addObject();bound.putArray("required").add("bindingId");var excluded=bound.putObject("not").putArray("anyOf");excluded.addObject().putArray("required").add("connectionId");excluded.addObject().putArray("required").add("connectionName");
            var standalone=choices.addObject();standalone.putArray("required").add("connectionId").add("connectionName");standalone.putObject("not").putArray("required").add("bindingId");
        }
        return new AgentTool(runtime,stdio,new ToolSpec(name,description,schema.toString()));
    }
    static final class AgentTool implements GraphTool {
        private final DbaRuntime runtime;private final Supplier<String> stdio;private final ToolSpec spec;
        AgentTool(DbaRuntime runtime,Supplier<String> stdio,ToolSpec spec){this.runtime=runtime;this.stdio=stdio;this.spec=spec;}
        public ToolSpec spec(){return spec;}
        public ToolResponse call(JsonNode args){return execute(null,args);}
        ToolResponse execute(String transportPrincipal,JsonNode args){try{String principal=stdio!=null?stdio.get():transportPrincipal;JsonNode result=runtime.agentCall(principal,spec.name(),args);boolean error=Set.of("failed","cancelled").contains(result.path("state").asText());return new ToolResponse(result.toString(),error);}catch(IllegalStateException e){if(e.getMessage()!=null&&e.getMessage().startsWith("approval_unavailable:"))return ToolResponse.fail("{\"error\":{\"code\":\"approval_unavailable\",\"message\":\"No interactive approval channel is available\"}}");return ToolResponse.fail("DBA operation unavailable");}catch(SecurityException e){return ToolResponse.fail("DBA access denied; authenticate and use reviewed permissions");}catch(IllegalArgumentException e){return ToolResponse.fail(e.getMessage());}catch(Exception e){return ToolResponse.fail("DBA operation failed");}}
    }
}
