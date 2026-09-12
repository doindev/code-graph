package io.doindev.codegraph.mcp;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
class DbaToolSchemaTest {
    @Test void connectionToolsRequireNamedTargetAndNeverInferBrowserSelection()throws Exception{
        var mapper=new ObjectMapper();for(var tool:DbaMcpTools.tools(null,()->null)){
            if(java.util.Set.of("dba_list_connections","dba_job_status","dba_cancel_job","dba_release_job").contains(tool.spec().name()))continue;
            var schema=mapper.readTree(tool.spec().inputSchemaJson());assertTrue(schema.path("required").toString().contains("connectionName"));assertTrue(schema.path("properties").has("connectionId"));assertTrue(tool.spec().description().contains("connectionName"));
        }
    }
}
