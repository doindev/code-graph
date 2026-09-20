package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CatalogPageTest {
    @Test void byteBoundPreservesPrefixWithoutSkippingOversizedRows(){
        var rows=Profiles.JSON.createArrayNode();var page=new CatalogQueries.Page(rows,100);
        page.add(Profiles.JSON.createObjectNode().put("id","first").put("text","漢".repeat(170_000)));
        page.add(Profiles.JSON.createObjectNode().put("id","second").put("text","\\\"".repeat(150_000)));
        page.add(Profiles.JSON.createObjectNode().put("id","third"));
        assertEquals(1,rows.size());assertEquals("first",rows.get(0).path("id").asText());
        var out=Profiles.JSON.createObjectNode().put("truncated",true);page.metadata(out);
        assertEquals("byte_limit",out.path("pageEndReason").asText());
        assertTrue(rows.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length<(1<<20));
    }
    @Test void oneUnreturnableRowFailsInsteadOfLooping(){
        var page=new CatalogQueries.Page(Profiles.JSON.createArrayNode(),100);
        assertTrue(assertThrows(IllegalArgumentException.class,()->page.add(Profiles.JSON.getNodeFactory().textNode("x".repeat(1<<20)))).getMessage().contains("item_too_large"));
    }
    @Test void requestedCountRemainsAMaximum(){
        var rows=Profiles.JSON.createArrayNode();var page=new CatalogQueries.Page(rows,1);
        page.add(Profiles.JSON.getNodeFactory().textNode("a"));page.add(Profiles.JSON.getNodeFactory().textNode("b"));
        assertEquals(1,rows.size());assertFalse(page.stopped);
    }
}
