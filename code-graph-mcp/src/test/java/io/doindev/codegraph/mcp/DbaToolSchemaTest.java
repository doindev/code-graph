package io.doindev.codegraph.mcp;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class DbaToolSchemaTest {
    @Test void approvalPollingHasCanonicalIdsAndLegacyCompatibility() throws Exception {
        var mapper=new ObjectMapper();
        for(var tool:DbaMcpTools.tools(null,()->null)) {
            String name=tool.spec().name();
            if(Set.of("dba_request_status","dba_cancel_request").contains(name)) {
                var schema=mapper.readTree(tool.spec().inputSchemaJson());
                assertEquals("uuid",schema.path("properties").path("approvalId").path("format").asText(),name);
                assertTrue(schema.path("properties").path("requestId").path("deprecated").asBoolean(),name);
                assertEquals(2,schema.path("anyOf").size(),name);
                assertTrue(tool.spec().description().contains("approvalId"),name);
            }
            if(Set.of("dba_list_templates","dba_job_status","dba_list_connections").contains(name))
                assertFalse(tool.spec().description().contains("Standalone execution then requires"),name);
            if(name.equals("dba_get_object_ddl"))assertTrue(tool.spec().description().contains("SQL Server"));
        }
    }
    @Test void nativeConnectionAndCommandSchemasDoNotRequireJdbc()throws Exception{
        var mapper=new ObjectMapper();var tools=DbaMcpTools.tools(null,()->null);
        var create=mapper.readTree(tools.stream().filter(t->t.spec().name().equals("dba_request_connection_create")).findFirst().orElseThrow().spec().inputSchemaJson()).path("properties").path("profile");
        assertFalse(create.path("required").toString().contains("driverClass"));
        assertTrue(create.path("anyOf").get(0).toString().contains("mongodb-native"));
        assertTrue(create.path("anyOf").get(1).path("required").toString().contains("driverClass"));
        String pattern=create.path("properties").path("url").path("pattern").asText();
        for(String url:List.of("mongodb://localhost:27017","mongodb+srv://example.org","redis://localhost:6379","rediss://localhost:6379","jdbc:h2:mem:test"))assertTrue(java.util.regex.Pattern.compile(pattern).matcher(url).find(),url);
        var command=mapper.readTree(tools.stream().filter(t->t.spec().name().equals("dba_request_native_command")).findFirst().orElseThrow().spec().inputSchemaJson());
        assertEquals(2,command.path("properties").path("command").path("oneOf").size());
        var binary=command.path("properties").path("command").path("oneOf").get(1).path("items").path("oneOf").get(1);
        assertFalse(binary.path("additionalProperties").asBoolean(true));
        assertEquals("base64",binary.path("required").get(0).asText());
        assertEquals(87384,binary.path("properties").path("base64").path("maxLength").asInt());
        assertTrue(command.path("oneOf").get(1).path("required").toString().contains("database"));
        assertTrue(command.path("oneOf").get(0).path("required").toString().contains("bindingId"));
        assertFalse(command.path("properties").has("approved"));
    }
    @Test void connectionDraftsAndParametersAreFullyDescribed() throws Exception {
        var mapper = new ObjectMapper();
        var schemas = new HashMap<String,com.fasterxml.jackson.databind.JsonNode>();
        for (var tool : DbaMcpTools.tools(null, () -> null)) schemas.put(tool.spec().name(), mapper.readTree(tool.spec().inputSchemaJson()));
        var create = schemas.get("dba_request_connection_create").path("properties");
        var profile = create.path("profile").path("properties");
        assertTrue(profile.path("password").path("writeOnly").asBoolean());
        assertTrue(profile.path("secretProperties").path("writeOnly").asBoolean());
        assertEquals(64, profile.path("jars").path("maxItems").asInt());
        assertEquals(16, profile.path("pool").path("properties").path("maximumPoolSize").path("maximum").asInt());
        assertEquals(5, create.path("binding").path("properties").path("environment").path("enum").size());
        assertTrue(create.path("driverInstall").path("properties").has("version"));
        var params = schemas.get("dba_request_live_sql").path("properties").path("parameters");
        assertEquals(128, params.path("maxItems").asInt());
        assertEquals(4, params.path("items").path("anyOf").size());
        assertFalse(profile.has("approved"));
    }
    @Test void liveSqlAcceptsEitherBindingOrExactStandaloneConnection()throws Exception {
        var tool=DbaMcpTools.tools(null,()->null).stream().filter(t->t.spec().name().equals("dba_request_live_sql")).findFirst().orElseThrow();
        var schema=new ObjectMapper().readTree(tool.spec().inputSchemaJson());assertFalse(schema.path("required").toString().contains("bindingId"));
        assertEquals(2,schema.path("oneOf").size());assertEquals("bindingId",schema.path("oneOf").get(0).path("required").get(0).asText());
        assertEquals("connectionId",schema.path("oneOf").get(1).path("required").get(0).asText());assertEquals("connectionName",schema.path("oneOf").get(1).path("required").get(1).asText());
        assertTrue(schema.path("oneOf").get(0).has("not"));assertTrue(schema.path("oneOf").get(1).has("not"));
        assertTrue(tool.spec().description().contains("standalone"));
        assertEquals("string",schema.path("properties").path("database").path("type").asText());
        assertEquals("string",schema.path("properties").path("schema").path("type").asText());
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
