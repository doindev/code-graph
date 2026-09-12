package io.doindev.codegraph.viz;

import io.doindev.codegraph.dba.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DbaRouteTest {
    @TempDir Path root;
    private final VizControl empty=new VizControl(){public List<VizProject> projects(){return List.of();}public String mcpEndpoint(){return "test";}};
    @Test void optInPageDoesNotRequireGraphProjects()throws Exception{
        try(var client=HttpClient.newHttpClient();var disabled=VizServer.start(empty,0)){
            assertEquals(404,get(client,disabled.port(),"/dba").statusCode());
            assertTrue(get(client,disabled.port(),"/api/server").body().contains("\"dbaEnabled\":false"));
        }
        Vault unavailable=new Vault(){public void put(String id,byte[] s){throw new AssertionError();}public byte[] get(String id){throw new AssertionError();}public void remove(String id){throw new AssertionError();}};
        try(var runtime=new DbaRuntime(new DbaConfig(root,64L<<20,1,100,10,10),unavailable);var server=VizServer.start(empty,InetAddress.getLoopbackAddress(),0,runtime);var client=HttpClient.newHttpClient()){
            var page=get(client,server.port(),"/dba");assertEquals(200,page.statusCode());assertTrue(page.body().contains("aria-label=\"Database connections\""));assertTrue(page.body().contains("id=\"appname\" href=\"/\""));assertTrue(page.body().contains("id=\"counterpart-new-tab\" class=\"header-new-tab\" href=\"/\" target=\"_blank\" rel=\"noopener\""));assertFalse(page.body().contains("force-graph"));
            var graph=get(client,server.port(),"/");assertEquals(200,graph.statusCode());assertTrue(graph.body().contains("id=\"appname\" href=\"/dba\""));assertTrue(graph.body().contains("id=\"counterpart-new-tab\" class=\"header-new-tab\" href=\"/dba\" target=\"_blank\" rel=\"noopener\""));assertFalse(graph.body().contains("id=\"dba-link\""));assertEquals(200,get(client,server.port(),"/dba/app.js").statusCode());
            var objectEditor=get(client,server.port(),"/dba/object-properties.js");assertEquals(200,objectEditor.statusCode());assertTrue(objectEditor.body().contains("class ObjectProperties"));
            var dataGrid=get(client,server.port(),"/dba/data-grid.js");assertEquals(200,dataGrid.statusCode());assertTrue(dataGrid.body().contains("class DataGridView"));
            assertTrue(get(client,server.port(),"/api/server").body().contains("\"dbaEnabled\":true"));
            assertEquals(403,get(client,server.port(),"/api/dba/connections").statusCode());
        }
    }
    private static HttpResponse<String> get(HttpClient client,int port,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
}
