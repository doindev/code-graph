package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.doindev.codegraph.dba.DbaRuntime;
import io.doindev.codegraph.tools.*;
import java.util.*;
import java.util.function.Supplier;

/** Read-only catalog shared by UI-enabled and headless launches. Credentials are not tool arguments. */
public final class DbaMcpTools {
    public static final String PRINCIPAL="codegraph.dba.principal";
    private static final ObjectMapper JSON=new ObjectMapper();
    private DbaMcpTools(){}
    public static List<GraphTool> tools(DbaRuntime runtime,Supplier<String> stdioPrincipal){
        return List.of(
            tool(runtime,stdioPrincipal,"dba_list_connections","List authorized connection IDs/names; no credentials or JDBC URLs.",List.of(),Map.of()),
            tool(runtime,stdioPrincipal,"dba_get_metadata","List granted objects, or capped column metadata for a granted schema/object.",List.of("connectionName"),Map.of("schema","string","object","string")),
            tool(runtime,stdioPrincipal,"dba_get_object_ddl","Read PostgreSQL catalog definition fragments for a granted object; never execute DDL. Not a complete restore script.",List.of("connectionName","schema","object"),Map.of()),
            tool(runtime,stdioPrincipal,"dba_explain_query","Get PostgreSQL estimated SELECT plan without executing it. Only explicitly granted schema-qualified objects.",List.of("connectionName","sql"),Map.of("parameters","array")),
            tool(runtime,stdioPrincipal,"dba_analyze_query_plan","Inspect estimated SELECT plan; no EXPLAIN ANALYZE or database ANALYZE execution. Estimates are not measured runtime.",List.of("connectionName","sql"),Map.of("parameters","array")),
            tool(runtime,stdioPrincipal,"dba_execute_read_query","Run one restricted PostgreSQL SELECT asynchronously. At most 100 rows/1 MiB, timeout, no writes/functions/CTEs; use ? parameters. Poll returned job ID.",List.of("connectionName","sql"),Map.of("parameters","array")),
            tool(runtime,stdioPrincipal,"dba_job_status","Get the authenticated agent's job state and capped result; failed/cancelled jobs are tool errors.",List.of("jobId"),Map.of()),
            tool(runtime,stdioPrincipal,"dba_cancel_job","Request cancellation of the authenticated agent's job.",List.of("jobId"),Map.of()),
            tool(runtime,stdioPrincipal,"dba_release_job","Release the authenticated agent's completed result from memory.",List.of("jobId"),Map.of())
        );
    }
    private static GraphTool tool(DbaRuntime runtime,Supplier<String> stdio,String name,String description,List<String> required,Map<String,String> optional){
        var schema=JSON.createObjectNode().put("type","object").put("additionalProperties",false);var properties=schema.putObject("properties");
        for(String key:required)properties.putObject(key).put("type","string");optional.forEach((key,type)->properties.putObject(key).put("type",type));schema.set("required",JSON.valueToTree(required));
        if(required.contains("connectionName")){properties.putObject("connectionId").put("type","string").put("description","Optional stable ID; must match connectionName and the agent grant.");properties.withObject("connectionName").put("description","Required connection name from dba_list_connections; checked against name-and-ID permissions. Never infer a default from browser selection.");description+=" Required: connectionName from dba_list_connections. Name AND stable connection ID must be granted; no browser/default connection is assumed.";}
        return new AgentTool(runtime,stdio,new ToolSpec(name,description,schema.toString()));
    }
    static final class AgentTool implements GraphTool {
        private final DbaRuntime runtime;private final Supplier<String> stdio;private final ToolSpec spec;
        AgentTool(DbaRuntime runtime,Supplier<String> stdio,ToolSpec spec){this.runtime=runtime;this.stdio=stdio;this.spec=spec;}
        public ToolSpec spec(){return spec;}
        public ToolResponse call(JsonNode args){return execute(null,args);}
        ToolResponse execute(String transportPrincipal,JsonNode args){try{
            String principal=stdio!=null?stdio.get():transportPrincipal;
            JsonNode result=runtime.agentCall(principal,spec.name(),args);
            boolean error=Set.of("failed","cancelled").contains(result.path("state").asText());return new ToolResponse(result.toString(),error);
        }catch(SecurityException e){return ToolResponse.fail("DBA access denied; authenticate and use granted objects");}
        catch(IllegalArgumentException e){return ToolResponse.fail(e.getMessage());}
        catch(Exception e){return ToolResponse.fail("DBA operation failed");}}
    }
}
