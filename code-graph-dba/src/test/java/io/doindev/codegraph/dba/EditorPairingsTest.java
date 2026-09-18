package io.doindev.codegraph.dba;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EditorPairingsTest {
    @TempDir Path directory;

    @Test void pairingIsExplicitRevisionCheckedAndNeverExecutesOrSaves()throws Exception{
        try(var auth=new BrowserAuth(directory);var pairings=new EditorPairings(auth,(session,principal)->session.equals("mcp-1")&&principal.equals("agent"))){
            BrowserAuth.Session browser=auth.create(System.currentTimeMillis()+60_000);
            String code=pairings.create(browser.id()).path("code").asText();
            assertEquals("paired",pairings.pair("agent","mcp-1",code).path("state").asText());
            var listed=pairings.documents("agent","mcp-1");assertEquals(0,listed.path("documents").size());
            var draft=Profiles.JSON.createObjectNode().put("title","Agent draft").put("sql","").put("expectedWorkspaceRevision",listed.path("workspaceRevision").asLong());
            var created=pairings.createDraft("agent","mcp-1",draft);assertFalse(created.path("executed").asBoolean());assertFalse(created.path("fileSaved").asBoolean());
            var document=pairings.document("agent","mcp-1",created.path("id").asText());
            var edit=Profiles.JSON.createObjectNode().put("documentId",document.path("id").asText()).put("expectedRevision",document.path("revision").asText()).put("start",0).put("end",0).put("text","SELECT 1");
            var changed=pairings.edit("agent","mcp-1",edit);assertFalse(changed.path("executed").asBoolean());assertThrows(IllegalArgumentException.class,()->pairings.edit("agent","mcp-1",edit));
            assertTrue(new String(auth.workspace(browser.id()),StandardCharsets.UTF_8).contains("SELECT 1"));
            assertFalse(pairings.events(browser.id(),0).isEmpty());pairings.revokeBrowser(browser.id());
            assertThrows(SecurityException.class,()->pairings.documents("agent","mcp-1"));
        }
    }
}
