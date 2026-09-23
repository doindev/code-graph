package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.tools.ToolResponse;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Transport metadata only. These hints never participate in authorization. */
final class McpToolContracts {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> LOCAL_READS = Set.of(
            "search_symbols", "get_symbol", "get_symbol_context", "get_impact_radius", "get_call_graph",
            "get_blast_score", "find_dead_code", "find_code_smells", "compare_architectural_drift",
            "index_status", "list_projects", "get_workspace_context", "get_file_outline",
            "resolve_symbol_at_position", "find_references", "find_implementations", "analyze_change", "find_affected_tests",
            "dba_list_templates", "dba_list_connections", "dba_list_project_databases",
            "dba_get_my_permissions", "dba_list_my_created_connections", "dba_search_objects",
            "dba_get_indexed_ddl", "dba_get_indexed_properties", "dba_get_database_dependencies",
            "dba_find_code_references", "dba_scan_status", "dba_job_status", "dba_request_status",
            "dba_live_request_status", "dba_prepare_migration", "dba_prepare_migration_rehearsal", "validate_database_contracts", "compare_query_plans",
            "dba_list_editor_documents", "dba_get_editor_document");
    private static final Set<String> LIVE_READS = Set.of(
            "dba_get_metadata", "dba_get_object_ddl", "dba_explain_query",
            "dba_analyze_query_plan", "dba_execute_read_query", "dba_get_connection_details",
            "dba_request_connection_test", "dba_get_capabilities", "dba_capture_schema", "dba_compare_schemas",
            "dba_validate_migration");
    private static final Set<String> NON_DESTRUCTIVE_WRITES = Set.of(
            "add_project", "reindex", "dba_refresh_catalog", "dba_request_connection_create",
            "dba_request_binding_create", "dba_request_editor_access", "dba_pair_editor", "dba_create_editor_draft", "dba_apply_editor_edit");

    static final String OUTPUT_SCHEMA = """
            {"type":"object","required":["data","meta"],"additionalProperties":false,
             "properties":{"data":{"description":"Original tool JSON payload, also returned verbatim as text for older clients."},
               "meta":{"type":"object","required":["contractVersion","tool","invocationState"],
                 "additionalProperties":false,"properties":{
                   "contractVersion":{"type":"integer","const":1},
                   "tool":{"type":"string"},
                   "invocationState":{"type":"string","enum":["returned","error"],
                     "description":"Invocation only, not asynchronous operation completion; inspect data.state. Queued operations are nonterminal."},
                   "operationState":{"type":"string"},
                   "target":{"type":"object"},
                   "generation":{"type":["integer","string"]},
                   "freshness":{"type":["string","object"]},
                   "completeness":{"type":"string","enum":["complete","partial","unknown"]},
                   "truncated":{"type":"boolean"},
                   "continuation":{"type":"object","required":["cursor"],"additionalProperties":false,"properties":{"cursor":{"type":"string"}}},
                   "authorization":{"type":"object","additionalProperties":false,"properties":{"reason":{"type":"string"},"policyId":{"type":"string"}}}}}}}
            """;

    static McpSchema.ToolAnnotations annotations(String name) {
        boolean localRead = LOCAL_READS.contains(name), liveRead = LIVE_READS.contains(name);
        // Live reads can submit jobs/approval requests: do not invite automatic retries.
        // Unknown operations retain conservative destructive/open-world hints.
        return new McpSchema.ToolAnnotations(null, localRead || liveRead,
                !(localRead || liveRead || NON_DESTRUCTIVE_WRITES.contains(name)),
                localRead, !localRead, null);
    }

    static Map<String, Object> structured(ToolResponse response, String name) {
        try {
            var result = new LinkedHashMap<String, Object>();
            Object payload=JSON.readValue(response.json(), Object.class);
            result.put("data", payload);
            var meta=new LinkedHashMap<String,Object>();
            meta.put("contractVersion",1);meta.put("tool",name);meta.put("invocationState",response.error()?"error":"returned");
            meta.put("completeness","unknown");
            if(payload instanceof Map<?,?> data){
                if(data.get("state") instanceof String state)meta.put("operationState",state);
                if(data.get("target") instanceof Map<?,?> target)meta.put("target",target);
                Object generation=data.get("generation");if(generation instanceof Number||generation instanceof String)meta.put("generation",generation);
                Object freshness=data.get("freshness");if(freshness instanceof String||freshness instanceof Map<?,?>)meta.put("freshness",freshness);
                if(data.get("inventoryComplete") instanceof Boolean complete)meta.put("completeness",complete?"complete":"partial");
                if(data.get("truncated") instanceof Boolean truncated)meta.put("truncated",truncated);
                if(data.get("nextCursor") instanceof String cursor)meta.put("continuation",Map.of("cursor",cursor));
                var authorization=new LinkedHashMap<String,Object>();
                if(data.get("authorizationReason") instanceof String reason)authorization.put("reason",reason);
                if(data.get("matchedPolicy") instanceof Map<?,?> policy&&policy.get("id") instanceof String id)authorization.put("policyId",id);
                if(!authorization.isEmpty())meta.put("authorization",authorization);
            }
            result.put("meta",meta);
            return result;
        } catch (java.io.IOException invalidJson) {
            throw new IllegalStateException("Tool returned an invalid JSON payload");
        }
    }

    private McpToolContracts() {}
}
