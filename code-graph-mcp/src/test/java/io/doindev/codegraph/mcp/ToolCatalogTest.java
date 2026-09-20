package io.doindev.codegraph.mcp;

import io.doindev.codegraph.tools.*;
import io.modelcontextprotocol.spec.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {
    @Test void referenceComesFromAllDefinitionsWithoutStartingDba() {
        String reference=ToolReference.markdown();
        assertTrue(reference.contains("## `search_symbols`"));
        assertTrue(reference.contains("## `dba_pair_editor`"));
        assertTrue(reference.contains("## `dba_request_status`"));
        assertTrue(reference.contains("deprecated"));
        assertFalse(reference.contains("required by every other tool"));
    }
    private GraphTool tool(String name,String description,String schema) {
        return new GraphTool() {
            public ToolSpec spec(){return new ToolSpec(name,description,schema);}
            public ToolResponse call(com.fasterxml.jackson.databind.JsonNode args){return ToolResponse.ok("{}");}
        };
    }
    @Test void buildAndCatalogIdentityAreDerivedAndCanonical() {
        var first=tool("a","description","{\"type\":\"object\",\"properties\":{}}");
        var same=tool("a","description","{\"properties\":{},\"type\":\"object\"}");
        var changed=tool("a","changed","{\"type\":\"object\",\"properties\":{}}");
        var a=CodeGraphMcpServer.toSpecification(first).tool();
        var b=CodeGraphMcpServer.toSpecification(same).tool();
        assertEquals(ToolCatalogIdentity.fingerprint(List.of(a)),ToolCatalogIdentity.fingerprint(List.of(b)));
        assertNotEquals(ToolCatalogIdentity.fingerprint(List.of(a)),
                ToolCatalogIdentity.fingerprint(List.of(CodeGraphMcpServer.toSpecification(changed).tool())));
        assertEquals("0.0.1-SNAPSHOT",BuildIdentity.version());
        assertNotEquals("unknown",BuildIdentity.builtAt());
    }
    @Test void onlyActualCatalogChangesPublishNotifications() {
        var notifications=new ArrayList<String>();
        var transport=(McpServerTransportProvider)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{McpServerTransportProvider.class},(proxy,method,args)->{
                    if(method.getName().equals("notifyClients"))notifications.add((String)args[0]);
                    if(method.getName().equals("protocolVersions"))return List.of("2025-11-25","2025-06-18","2025-03-26");
                    if(method.getReturnType().equals(reactor.core.publisher.Mono.class))return reactor.core.publisher.Mono.empty();
                    return null;
                });
        var first=tool("a","description","{\"type\":\"object\"}");
        try(var server=CodeGraphMcpServer.serve("test",BuildIdentity.version(),List.of(first),transport)) {
            assertFalse(server.replaceTools(List.of(first)));
            assertTrue(notifications.isEmpty());
            var second=tool("b","second","{\"type\":\"object\"}");
            assertTrue(server.replaceTools(List.of(first,second)));
            assertEquals(List.of("notifications/tools/list_changed"),notifications);
            assertFalse(server.replaceTools(List.of(second,first)));
            assertEquals(1,notifications.size());
            assertTrue(server.replaceTools(List.of(first)));
            assertEquals(2,notifications.size());
        }
    }
}
