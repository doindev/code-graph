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
    public static final String SESSION="codegraph.dba.session";
    private static final ObjectMapper JSON=new ObjectMapper();
    private DbaMcpTools(){}
    public static List<GraphTool> tools(DbaRuntime runtime,Supplier<String> stdioPrincipal){
        List<GraphTool> tools=new ArrayList<>();
        tools.add(tool(runtime,stdioPrincipal,"dba_capture_schema","Capture a bounded schema observation as an asynchronous retained job, requiring existing catalog permission for bindingId or exact connection UUID/name/database/schema. No data records or migration SQL are executed. Partial inventories never prove removal; release the job when no longer needed.",List.of(),Map.of("bindingId","string","connectionId","string","connectionName","string","database","string","schema","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_compare_schemas","Compare two completed retained schema capture job IDs owned by this agent. Reauthorizes both exact targets. Reports structural, definition-text and cross-engine compatibility differences; never infers renames, removals from incomplete inventories or generates migration SQL.",List.of("leftSnapshotId","rightSnapshotId"),Map.of("limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_prepare_migration","Prepare a bounded migration artifact from a completed retained schema snapshot and either explicit structured changes or supplied DDL. Returns reviewed SQL plus a machine-readable manifest; executes nothing and never infers renames, cascading or backfills.",List.of("snapshotId"),Map.of("changes","array","sql","string","name","string","purpose","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_validate_migration","Reauthorize the exact target and recapture its bounded schema fingerprint before application. Executes no migration SQL and does not claim a rehearsal.",List.of("planId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_prepare_migration_rehearsal","Prepare a separately retained rehearsal plan for an independently authorized disposable schema snapshot. Reviews bounded setup SQL, synthetic fixture INSERTs, the source migration, and validation SELECTs together. Rejects the source target and aliases; executes nothing until exact one-time human approval.",List.of("planId","rehearsalSnapshotId"),Map.of("setupSql","string","fixtureSql","string","checks","array","name","string","purpose","string")));
        tools.add(tool(runtime,stdioPrincipal,"validate_database_contracts","Compare incrementally indexed static SQL/ORM mappings with an owned authorized schema snapshot. Reports evidence, confidence and uncertainty; missing observations never prove that an object is unused or absent.",List.of("snapshotId"),Map.of("projectId","string","limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"compare_query_plans","Compare two completed retained estimated-plan jobs using normalized structural evidence while retaining raw vendor evidence in the source jobs. Never compares cross-vendor cost units or labels every scan a regression.",List.of("leftPlanId","rightPlanId"),Map.of("limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_capabilities","Report authorized target capabilities and cached vendor/version. live=true submits a bounded JDBC metadata observation job for this exact target (catalog permission required), including standalone targets. Unobserved servers remain unknown, not inferred from templates.",List.of(),Map.of("bindingId","string","connectionId","string","connectionName","string","database","string","schema","string","live","boolean")));
        tools.add(tool(runtime,stdioPrincipal,"get_workspace_context","Get bounded authorized project, environment, role, connection and freshness summaries without connecting profiles, scanning catalogs or renewing activity. Discovery grants no permissions.",List.of(),Map.of("limit","integer","cursor","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_list_templates","Page through public JDBC templates, driver recipes and property descriptors without downloads, credentials, database connections or scans. Template availability is not advanced-workflow certification.",List.of(),Map.of("templateId","string","limit","integer","cursor","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_list_project_databases","List authorized application database bindings with canonical environment, logical role, purpose, scope, freshness and effective permissions. Listing alone does not renew activity.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_my_permissions","List legacy grants and scoped exact/category reusable permissions, including active MCP-session grants, lifetimes, targets and last use. Never returns credentials.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_list_my_created_connections","List stable IDs, names, creation request IDs, timestamps and bindings for profiles created through this agent. Creation never grants access.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_search_objects","Search an authorized application database snapshot and renew its activity lease. Prefer generation-bound cursor pages; legacy offsets do not protect against scans between calls.",List.of("bindingId"),Map.of("query","string","kind","string","offset","integer","limit","integer","searchDefinitions","boolean","cursor","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_indexed_ddl","Read bounded DDL chunks from an authorized cached snapshot.",List.of("bindingId","objectId"),Map.of("offset","integer","length","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_indexed_properties","Read cached columns, indexes, primary keys, foreign keys or privileges in bounded pages.",List.of("bindingId","objectId"),Map.of("section","string","offset","integer","limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_database_dependencies","Read bounded cached dependency edges for an indexed object.",List.of("bindingId","objectId"),Map.of("offset","integer","limit","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_find_code_references","Find candidate code locations for an indexed database object; dynamic SQL can remain unresolved.",List.of("bindingId","objectId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_scan_status","Inspect snapshot freshness without renewing activity. Optionally wait up to 5000ms for generation > afterGeneration; at most four waiters, cancellation on interruption.",List.of("bindingId"),Map.of("afterGeneration","integer","waitMillis","integer")));
        tools.add(tool(runtime,stdioPrincipal,"dba_refresh_catalog","Request a bounded catalog scan for an authorized enabled binding. Coalesces with existing scans and renews project activity; no database writes. Optionally wait for a newer generation.",List.of("bindingId"),Map.of("afterGeneration","integer","waitMillis","integer")));

        tools.add(tool(runtime,stdioPrincipal,"dba_list_connections","List stable connection IDs/names without credentials or URLs. Trusted local clients can discover connections without setup; database access still requires reviewed permission. Optional named-token clients retain their grant-filtered roster.",List.of(),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_metadata","Read capped metadata under legacy or scoped catalog permissions. Use bindingId or connectionId plus exact connectionName; unapproved access returns an approval request when a review channel is enabled.",List.of("connectionName"),Map.of("schema","string","object","string")));
        tools.add(tool(runtime,stdioPrincipal,"dba_get_object_ddl","Inspect PostgreSQL definition fragments or MySQL/MariaDB SHOW CREATE through scoped catalog permission; never creates objects. May return an approval request, otherwise an asynchronous job.",List.of("connectionName","schema","object"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_explain_query","Get an estimated SELECT plan without execution-based analysis under legacy or scoped explain permission. May return an approval request or an asynchronous job.",List.of("connectionName","sql"),Map.of("parameters","array")));
        tools.add(tool(runtime,stdioPrincipal,"dba_analyze_query_plan","Analyze an estimated SELECT plan without EXPLAIN ANALYZE or database ANALYZE.",List.of("connectionName","sql"),Map.of("parameters","array")));
        tools.add(tool(runtime,stdioPrincipal,"dba_execute_read_query","Run one structurally restricted SELECT asynchronously with at most 100 rows/1 MiB.",List.of("connectionName","sql"),Map.of("parameters","array")));
        tools.add(tool(runtime,stdioPrincipal,"dba_job_status","Get this agent's bounded job state/result.",List.of("jobId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_cancel_job","Request cancellation of this agent's job.",List.of("jobId"),Map.of()));
        tools.add(tool(runtime,stdioPrincipal,"dba_release_job","Release this agent's completed result.",List.of("jobId"),Map.of()));
        if(runtime!=null&&runtime.editorPairingEnabled()){
            tools.add(tool(runtime,stdioPrincipal,"dba_pair_editor","Pair this logical MCP session with a user-selected DBA browser workspace using its short-lived code. Pairing grants revision-checked Script collaboration only and no database permissions.",List.of("pairingCode"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_list_editor_documents","List bounded Script document metadata and revisions in the explicitly paired browser workspace. Does not expose Table tabs or execute SQL.",List.of(),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_get_editor_document","Read one paired Script document with its exact revision for conflict-safe edits. Does not execute SQL or save a file.",List.of("documentId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_create_editor_draft","Create an unsaved Script draft in the paired browser workspace at an expected workspace revision. Never executes SQL, saves a file or changes database permissions.",List.of("title","sql","expectedWorkspaceRevision"),Map.of("connectionId","string")));
            tools.add(tool(runtime,stdioPrincipal,"dba_apply_editor_edit","Apply one bounded UTF-16 text-range edit to a paired Script document only when its exact revision still matches. Conflicts are rejected; edits never execute SQL or save files.",List.of("documentId","expectedRevision","start","end","text"),Map.of()));
        }

        boolean approvals=runtime==null||runtime.approvalsEnabled();
        if(approvals){
            tools.add(tool(runtime,stdioPrincipal,"dba_request_native_command","Request an exact bounded native MongoDB/Redis read or supported single-target CRUD mutation. Supply either bindingId or an explicit standalone native connection UUID/name/database, and for collection commands the exact MongoDB collection. A binding fixes the connection and database. Commands are BSON Extended JSON objects (MongoDB) or argument arrays (Redis), including explicit base64 key/value objects; command names and control arguments stay text. No shell syntax, JDBC translation, default target, implicit read grant, or automatic write retry. Unsupported commands fail explicitly. Uses the existing one-time approval and async job lifecycle.",List.of("requestId","purpose","command"),Map.of("collection","string","bindingId","string","connectionId","string","connectionName","string","database","string")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_apply_migration","Request exact one-time human review and asynchronous application of a retained migration or rehearsal plan. Reusable SQL policies never authorize it; execution rechecks target revision and schema fingerprint and never retries uncertain writes.",List.of("requestId","purpose","planId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_get_connection_details","Get an allowlisted non-secret profile by bindingId, or stable connectionId plus exact connectionName. Returns immediately with permission or creates a read approval request.",List.of("requestId","purpose"),Map.of("bindingId","string","connectionId","string","connectionName","string")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_create","Propose a connection profile, optional application binding, and optional explicit Maven driver installation. Password and secretProperties are write-only arguments that the MCP client/task transcript may retain; prefer human entry when possible. Creation grants no access.",List.of("requestId","purpose","profile"),Map.of("binding","object","driverInstall","object")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_update","Propose an exact connection profile change. Secrets are write-only and never returned or audited.",List.of("requestId","purpose","connectionId","connectionName","profile"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_delete","Request removal of application profile configuration, vault entries, pools, bindings and policies. Never drops the database or removes a container.",List.of("requestId","purpose","connectionId","connectionName"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_connection_test","Request a safe connection test by bindingId, or stable connectionId plus exact name. Test results are ephemeral.",List.of("requestId","purpose"),Map.of("bindingId","string","connectionId","string","connectionName","string")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_binding_create","Propose associating an existing connection with an onboarded application, canonical environment, required logical role and purpose.",List.of("requestId","purpose","binding"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_binding_update","Propose environment, role, purpose, enabled, scan or idle changes. Immutable target changes require replacement.",List.of("requestId","purpose","bindingId","binding"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_binding_delete","Propose removing an application/database association without deleting the connection or database.",List.of("requestId","purpose","bindingId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_live_sql","Request SQL using bindingId, or standalone connectionId plus exact connectionName with optional database/schema. Server-verified categories may use exact or scoped reusable human permissions. Dangerous or unknown statements always require one-time review. Session policies end with this logical MCP session.",List.of("requestId","sql","purpose"),Map.of("bindingId","string","connectionId","string","connectionName","string","database","string","schema","string","parameters","array","autoCommit","boolean")));
            tools.add(tool(runtime,stdioPrincipal,"dba_request_status","Poll an approval using the returned approval id (not the caller's idempotency requestId). Includes classification, eligible choices, exact scope, matched policy and authorization reason when available.",List.of("requestId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_cancel_request","Cancel this agent's pending request or request cancellation of its job.",List.of("requestId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_cancel_live_request","Compatibility alias for cancelling a live-SQL approval.",List.of("approvalId"),Map.of()));
            tools.add(tool(runtime,stdioPrincipal,"dba_live_request_status","Compatibility alias for polling a live-SQL approval.",List.of("approvalId"),Map.of()));
        }
        if(runtime!=null&&stdioPrincipal!=null){
            String principal=stdioPrincipal.get(),session="stdio:"+UUID.randomUUID();
            runtime.registerMcpSession(session,principal,Long.MAX_VALUE);
            for(GraphTool tool:tools)((AgentTool)tool).stdioSession=session;
        }
        return List.copyOf(tools);
    }
    private static GraphTool tool(DbaRuntime runtime,Supplier<String> stdio,String name,String description,List<String> required,Map<String,String> optional){
        var schema=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var properties=schema.putObject("properties");
        for(String key:required)properties.putObject(key).put("type",Set.of("profile","binding").contains(key)?"object":Set.of("start","end","expectedWorkspaceRevision").contains(key)?"integer":"string");optional.forEach((key,type)->properties.putObject(key).put("type",type));schema.set("required",JSON.valueToTree(required));
        if(required.contains("connectionName")){properties.putObject("connectionId").put("type","string").put("description","Stable connection UUID; must match connectionName.");properties.withObject("connectionName").put("description","Exact saved connection name; no browser/default connection is inferred.");}
        if(Set.of("dba_get_metadata","dba_get_object_ddl","dba_execute_read_query","dba_explain_query","dba_analyze_query_plan").contains(name)){
            properties.putObject("bindingId").put("type","string").put("description","Project-bound target; do not combine with connectionId, connectionName, database or schema.");
            var readRequired=schema.putArray("required");for(String key:required)if(!key.equals("connectionName")&&!key.equals("schema"))readRequired.add(key);
            var targetChoices=schema.putArray("oneOf");var bound=targetChoices.addObject();bound.putArray("required").add("bindingId");var excluded=bound.putObject("not").putArray("anyOf");for(String key:List.of("connectionId","connectionName","database","schema"))excluded.addObject().putArray("required").add(key);
            var standalone=targetChoices.addObject();standalone.putArray("required").add("connectionName");standalone.putObject("not").putArray("required").add("bindingId");
            properties.putObject("database").put("type","string").put("description","Explicit standalone database. Reusable access requires connectionId and exact connectionName.");
            if(!properties.has("schema"))properties.putObject("schema").put("type","string").put("description","Exact schema boundary for reusable permissions.");
        }
        if(name.equals("dba_request_live_sql")){
            properties.withObject("bindingId").put("description","Project-bound target. Do not combine with connectionId/connectionName.");
            properties.withObject("connectionId").put("description","Standalone saved connection UUID; requires its exact connectionName. No project binding is needed.");
            properties.withObject("connectionName").put("description","Exact saved name matching connectionId; no browser-selected connection is inferred.");
            var choices=schema.putArray("oneOf");var bound=choices.addObject();bound.putArray("required").add("bindingId");var excluded=bound.putObject("not").putArray("anyOf");excluded.addObject().putArray("required").add("connectionId");excluded.addObject().putArray("required").add("connectionName");
            var standalone=choices.addObject();standalone.putArray("required").add("connectionId").add("connectionName");standalone.putObject("not").putArray("required").add("bindingId");
        }
        DbaInputSchemas.enrich(name,schema);
        description+=" With startup --yolo, approval-dependent operations are automatically authorized once for validated local sessions; exact targets and all validation remain required. Standalone execution then requires connectionId, exact connectionName, database and applicable schema. Read tools remain read-only.";
        return new AgentTool(runtime,stdio,new ToolSpec(name,description,schema.toString()));
    }
    static final class AgentTool implements GraphTool {
        private final DbaRuntime runtime;private final Supplier<String> stdio;private final ToolSpec spec;
        private String stdioSession;
        void endSession(){if(runtime!=null&&stdioSession!=null)runtime.endMcpSession(stdioSession);}
        AgentTool(DbaRuntime runtime,Supplier<String> stdio,ToolSpec spec){this.runtime=runtime;this.stdio=stdio;this.spec=spec;}
        public ToolSpec spec(){return spec;}
        public ToolResponse call(JsonNode args){return execute(null,args);}
        ToolResponse execute(String transportPrincipal,JsonNode args){return execute(transportPrincipal,null,args);}
        ToolResponse execute(String transportPrincipal,String transportSession,JsonNode args){try{String principal=stdio!=null?stdio.get():transportPrincipal;String session=stdio!=null?stdioSession:transportSession;JsonNode result=runtime.agentCall(principal,session==null||session.isBlank()?null:session,spec.name(),args);boolean error=Set.of("failed","cancelled").contains(result.path("state").asText());return new ToolResponse(result.toString(),error);}catch(IllegalStateException e){if(e.getMessage()!=null&&e.getMessage().startsWith("approval_unavailable:"))return ToolResponse.fail("{\"error\":{\"code\":\"approval_unavailable\",\"message\":\"No interactive approval channel is available\"}}");return ToolResponse.fail("DBA operation unavailable");}catch(SecurityException e){return ToolResponse.fail("DBA access denied; authenticate and use reviewed permissions");}catch(IllegalArgumentException e){return ToolResponse.fail(e.getMessage());}catch(Exception e){return ToolResponse.fail("DBA operation failed");}}
    }
}
