package io.doindev.codegraph.mcp;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DbaToolSchemaTest {
    @Test void liveSqlAcceptsEitherBindingOrExactStandaloneConnection()throws Exception {
        var tool=DbaMcpTools.tools(null,()->null).stream().filter(t->t.spec().name().equals("dba_request_live_sql")).findFirst().orElseThrow();
        var schema=new ObjectMapper().readTree(tool.spec().inputSchemaJson());assertFalse(schema.path("required").toString().contains("bindingId"));
        assertEquals(2,schema.path("oneOf").size());assertEquals("bindingId",schema.path("oneOf").get(0).path("required").get(0).asText());
        assertEquals("connectionId",schema.path("oneOf").get(1).path("required").get(0).asText());assertEquals("connectionName",schema.path("oneOf").get(1).path("required").get(1).asText());
        assertTrue(schema.path("oneOf").get(0).has("not"));assertTrue(schema.path("oneOf").get(1).has("not"));
        assertTrue(tool.spec().description().contains("No project"));
    }
    @Test void schemasAreBoundedAndNeverAcceptAgentApprovalBooleans()throws Exception{
        var mapper=new ObjectMapper();Set<String> names=new HashSet<>();for(var tool:DbaMcpTools.tools(null,()->null)){assertTrue(names.add(tool.spec().name()));var schema=mapper.readTree(tool.spec().inputSchemaJson());assertFalse(schema.path("properties").has("approved"),tool.spec().name());assertFalse(schema.path("properties").has("acknowledged"),tool.spec().name());assertFalse(schema.path("additionalProperties").asBoolean(true),tool.spec().name());}
        for(String required:List.of("dba_get_my_permissions","dba_get_connection_details","dba_request_connection_create","dba_request_connection_update","dba_request_connection_delete","dba_request_connection_test","dba_request_binding_create","dba_request_binding_update","dba_request_binding_delete","dba_list_my_created_connections","dba_request_status","dba_cancel_request"))assertTrue(names.contains(required),required);
    }
    @Test void administrationTargetsUseStableIdsAndExactNames()throws Exception{
        var mapper=new ObjectMapper();for(var tool:DbaMcpTools.tools(null,()->null)){var schema=mapper.readTree(tool.spec().inputSchemaJson());String name=tool.spec().name();
            if(Set.of("dba_request_connection_update","dba_request_connection_delete").contains(name)){assertTrue(schema.path("required").toString().contains("connectionId"));assertTrue(schema.path("required").toString().contains("connectionName"));}
            if(Set.of("dba_request_binding_update","dba_request_binding_delete").contains(name))assertTrue(schema.path("required").toString().contains("bindingId"));
            if(name.equals("dba_request_connection_create"))assertEquals("object",schema.path("properties").path("profile").path("type").asText());
        }
    }
}
