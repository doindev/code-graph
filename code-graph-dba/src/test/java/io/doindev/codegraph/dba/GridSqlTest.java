package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GridSqlTest {
    @Test void quotedTableDateFiltersPreserveQualifiedIdentifiers(){
        for(String source:List.of("\"public\".\"myusers\"", "\"schema.with.dot\".\"table\"\"quote\"", "\"catalog\".\"schema\".\"table\"", "\"public\".\"myusers\" AS \"user_alias\"")){
            String sql="SELECT * FROM "+source,qualifier=source.contains(" AS ")?"\"user_alias\".":"";
            var request=input(sql,"filter").put("columnLabel","signup_date").put("operator","=").put("jdbcType",java.sql.Types.DATE).put("value","2026-09-04");
            request.putArray("columns").addObject().put("name","signup_date").put("label","signup_date");
            var result=GridSql.prepare(request);
            String predicate=qualifier+"signup_date = CAST('2026-09-04' AS DATE)";
            assertEquals(predicate,result.path("filterExpression").asText());
            assertEquals(sql+" WHERE "+predicate,result.path("displaySql").asText());
            request.put("sql",result.path("sql").asText()).put("action","order").put("direction","ASC").set("parameters",result.path("parameters"));
            assertTrue(GridSql.prepare(request).path("sql").asText().endsWith("ORDER BY "+qualifier+"signup_date ASC"));
        }
    }
    @Test void joinedQuotedTablesUseUnambiguousNamesOrSchemaQualifiers(){
        for(boolean sameName:List.of(false,true)){
            String right=sameName?"myusers":"other",sql="SELECT * FROM \"public\".\"myusers\" JOIN \"archive\".\""+right+"\" ON TRUE";
            var request=input(sql,"filter").put("columnLabel","signup_date").put("operator","=").put("jdbcType",java.sql.Types.DATE).put("value","2026-09-04");
            var cols=request.putArray("columns");cols.addObject().put("name","signup_date").put("table","myusers").put("schema","public");cols.addObject().put("name","signup_date").put("table",right).put("schema","archive");
            String qualifier=sameName?"\"public\".\"myusers\"":"\"myusers\"";
            assertEquals(qualifier+".signup_date = CAST('2026-09-04' AS DATE)",GridSql.prepare(request).path("filterExpression").asText());
            request.put("columnIndex",1);qualifier=sameName?"\"archive\".\"myusers\"":"\"other\"";
            assertEquals(qualifier+".signup_date = CAST('2026-09-04' AS DATE)",GridSql.prepare(request).path("filterExpression").asText());
        }
    }
    @Test void unsupportedDottedAliasFailsBeforeProducingCorruptedSql(){
        var request=input("SELECT * FROM \"public\".\"myusers\" AS \"user.alias\"","filter").put("operator","=").put("value",1);
        assertTrue(assertThrows(IllegalArgumentException.class,()->GridSql.prepare(request)).getMessage().contains("alias containing a dot"));
    }
    ObjectNode input(String sql,String action){return Profiles.JSON.createObjectNode().put("sql",sql).put("action",action).put("columnIndex",0).put("columnLabel","id").set("parameters",Profiles.JSON.createArrayNode());}
    @Test void orderUpdatesExistingDirectionAndAppendsInSelectionOrder(){
        var request=input("SELECT t.id, t.name FROM items t ORDER BY t.name DESC LIMIT 20","order").put("direction","ASC");
        var first=GridSql.edit(request);assertEquals("SELECT t.id, t.name FROM items t ORDER BY t.name DESC, t.id ASC LIMIT 20",first.sql());
        request.put("sql",first.sql()).put("direction","DESC");assertEquals("SELECT t.id, t.name FROM items t ORDER BY t.name DESC, t.id DESC LIMIT 20",GridSql.edit(request).sql());
        request.put("sql","SELECT id AS item_id FROM items ORDER BY item_id");assertEquals("SELECT id AS item_id FROM items ORDER BY item_id DESC",GridSql.edit(request).sql());
        request.put("sql","SELECT id FROM items ORDER BY 1");assertEquals("SELECT id FROM items ORDER BY 1 DESC",GridSql.edit(request).sql());
    }
    @Test void filtersReplaceMatchingOperatorsPreserveBoundsAndBindValues(){
        var request=input("SELECT id FROM items WHERE id > ? AND id < ? AND name = ?","filter").put("operator",">").put("value","20").put("jdbcType",java.sql.Types.INTEGER);
        request.set("parameters",Profiles.JSON.createArrayNode().add(10).add(100).add("O'Brien"));
        var edit=GridSql.edit(request);assertEquals("SELECT id FROM items WHERE id > ? AND id < ? AND name = ?",edit.sql());assertEquals("[20,100,\"O'Brien\"]",edit.parameters().toString());
        request.put("sql",edit.sql()).put("value","30").set("parameters",edit.parameters());var second=GridSql.edit(request);assertEquals(edit.sql(),second.sql());assertEquals("30",second.parameters().get(0).asText());
        request=input("SELECT name FROM items WHERE enabled = 1 OR enabled = 2","filter").put("columnLabel","name").put("operator","=").put("value","x' OR 1=1 --");
        var string=GridSql.edit(request);assertEquals("SELECT name FROM items WHERE (enabled = 1 OR enabled = 2) AND name = ?",string.sql());assertFalse(string.sql().contains("x'"));assertEquals("x' OR 1=1 --",string.parameters().get(0).asText());
    }
    @Test void aliasesStarsNullAndClearAreStructured(){
        var request=input("SELECT t.id AS identifier FROM items t WHERE t.id = 1","filter").put("columnLabel","identifier").put("operator","=").put("value",9);assertTrue(GridSql.edit(request).sql().contains("WHERE t.id = ?"));
        request=input("SELECT * FROM items WHERE id = ? ORDER BY name","clear_all");request.set("parameters",Profiles.JSON.createArrayNode().add(4));var cleared=GridSql.edit(request);assertEquals("SELECT * FROM items",cleared.sql());assertTrue(cleared.parameters().isEmpty());
        request=input("SELECT * FROM items","filter").put("operator","IS NULL");assertEquals("SELECT * FROM items WHERE id IS NULL",GridSql.edit(request).sql());
    }
    @Test void ambiguousOrUnsafeQueriesAreNotSilentlyRewritten(){
        for(String sql:new String[]{"DELETE FROM items RETURNING id","SELECT id INTO copy FROM items","WITH gone AS (DELETE FROM items RETURNING id) SELECT id FROM gone","SELECT id FROM items; SELECT id FROM other","SELECT id FROM a UNION SELECT id FROM b","SELECT COUNT(*) FROM items","SELECT * FROM a JOIN b ON a.id=b.id"})assertThrows(IllegalArgumentException.class,()->GridSql.edit(input(sql,"filter").put("operator","=").put("value",1)),sql);
        for(String sql:new String[]{"SELECT id FROM items WHERE id = 1 OR id = 2","SELECT id FROM items WHERE id = 1 AND id = 2","SELECT id FROM items WHERE id = other_id"})assertThrows(IllegalArgumentException.class,()->GridSql.edit(input(sql,"filter").put("operator","=").put("value",1)),sql);
        assertThrows(IllegalArgumentException.class,()->GridSql.edit(input("SELECT id FROM items WHERE id=?","order").put("direction","ASC")));
    }
    @Test void numberedMarkerNormalizationNeverRewritesStringContents(){
        var request=input("SELECT id FROM items WHERE name='?2' AND id=? ORDER BY id","order").put("direction","DESC");request.set("parameters",Profiles.JSON.createArrayNode().add(7));var edit=GridSql.edit(request);assertEquals("SELECT id FROM items WHERE name = '?2' AND id = ? ORDER BY id DESC",edit.sql());assertEquals(7,edit.parameters().get(0).asInt());
    }
    @Test void duplicateNamesUsePositionAndTableOrDerivedAliases(){
        var request=input("SELECT a.id, b.id FROM items a JOIN items b ON a.id=b.id WHERE a.id > 1 AND b.id > 2","filter").put("columnIndex",1).put("operator",">").put("value",5);
        assertEquals("SELECT a.id, b.id FROM items a JOIN items b ON a.id = b.id WHERE a.id > 1 AND b.id > ?",GridSql.edit(request).sql());
        request.put("sql","SELECT q.id, t.id FROM (SELECT id FROM items) q JOIN items t ON q.id=t.id").put("columnIndex",0);
        assertTrue(GridSql.edit(request).sql().endsWith("WHERE q.id > ?"));
        request.put("sql","WITH q AS (SELECT id FROM items WHERE id > 0) SELECT q.id FROM q");assertTrue(GridSql.edit(request).sql().endsWith("WHERE q.id > ?"));
        var result=Profiles.JSON.createObjectNode();result.putArray("columns").addObject().put("id","c1").put("label","id");((ArrayNode)result.get("columns")).addObject().put("id","c2").put("label","id");
        GridSql.describeColumns("SELECT a.id,b.id FROM items a JOIN items b ON a.id=b.id",result);
        assertEquals("a.id",result.path("columns").get(0).path("displayLabel").asText());assertEquals("b.id",result.path("columns").get(1).path("displayLabel").asText());
    }
    @Test void wildcardOriginUsesJdbcTableMetadataAndRejectsSelfJoinAmbiguity(){
        var request=input("SELECT * FROM items a JOIN other b ON a.id=b.id","order").put("columnIndex",1).put("direction","DESC");
        var cols=request.putArray("columns");cols.addObject().put("name","id").put("table","items");cols.addObject().put("name","id").put("table","other");
        assertTrue(GridSql.edit(request).sql().endsWith("ORDER BY b.id DESC"));
        request.put("sql","SELECT * FROM items a JOIN items b ON a.id=b.id");((ObjectNode)cols.get(1)).put("table","items");assertThrows(IllegalArgumentException.class,()->GridSql.edit(request));
        request.put("sql","SELECT q.* FROM (SELECT id FROM items) q").put("columnIndex",0);cols.remove(1);assertTrue(GridSql.edit(request).sql().endsWith("ORDER BY q.id DESC"));
    }
    @Test void editedQueriesExecuteAgainstH2()throws Exception{
        try(var c=java.sql.DriverManager.getConnection("jdbc:h2:mem:grid_edit;DB_CLOSE_DELAY=-1");var s=c.createStatement()){
            s.execute("CREATE TABLE items(id INT, name VARCHAR(50))");s.execute("INSERT INTO items VALUES (1,'one'),(2,'two'),(3,'three')");
            var edit=GridSql.edit(input("SELECT id,name FROM items WHERE id > 0 ORDER BY name","filter").put("operator",">").put("value","1").put("jdbcType",java.sql.Types.INTEGER));
            try(var q=c.prepareStatement(edit.sql())){q.setString(1,edit.parameters().get(0).asText());try(var rows=q.executeQuery()){assertTrue(rows.next());assertEquals(3,rows.getInt(1));assertTrue(rows.next());assertEquals(2,rows.getInt(1));assertFalse(rows.next());}}
        }
    }
    @Test void editableDeltaPreservesOriginalPredicatesAndBooleanGrouping(){
        var request=input("SELECT id,price FROM items WHERE price > ? AND price < 100 ORDER BY id","filter").put("operator","=").put("jdbcType",java.sql.Types.INTEGER).put("value",2);request.set("parameters",Profiles.JSON.createArrayNode().add(10));
        ObjectNode edited=GridSql.prepare(request);assertEquals("id = 2",edited.path("filterExpression").asText());
        assertFalse(edited.path("filterExpression").asText().contains("price"));assertTrue(edited.path("baseSql").asText().contains("price > ?"));
        var apply=edited.deepCopy().put("action","expression").put("expression","(id = 1 OR id = 3) AND (price > 15 OR price IS NULL)");
        var result=GridSql.prepare(apply);assertTrue(result.path("sql").asText().contains("(price > ? AND price < 100) AND ((id = 1 OR id = 3) AND (price > 15 OR price IS NULL))"),result.toString());assertEquals(10,result.path("parameters").get(0).asInt());
        apply=result.deepCopy().put("action","expression").put("expression","");assertEquals(edited.path("baseSql").asText(),GridSql.prepare(apply).path("sql").asText());
        for(String bad:List.of("id = 1; DELETE FROM items","id = ?","(id = 1","id = 1 ORDER BY id")){
            var invalid=edited.deepCopy().put("action","expression").put("expression",bad);assertThrows(IllegalArgumentException.class,()->GridSql.prepare(invalid),bad);
        }
    }
    @Test void customValuesAreTypedInBothPreviewsAndRemainBoundForExecution(){
        Object[][] cases={{java.sql.Types.INTEGER,"42","42"},{java.sql.Types.DECIMAL," 15.350 ","15.350"},{java.sql.Types.BOOLEAN,"TRUE","TRUE"},{java.sql.Types.BIT,"false","CAST(0 AS BIT)"},{java.sql.Types.DATE,"2026-09-09","CAST('2026-09-09' AS DATE)"},{java.sql.Types.TIME,"13:45:12","CAST('13:45:12' AS TIME)"},{java.sql.Types.TIMESTAMP,"2026-09-09 13:45:12","CAST('2026-09-09 13:45:12' AS TIMESTAMP)"},{java.sql.Types.VARCHAR,"O'Brien\n42","'O''Brien\n42'"}};
        for(Object[] c:cases){var request=input("SELECT id FROM items","filter").put("operator","=").put("jdbcType",(Integer)c[0]).put("value",(String)c[1]);var result=GridSql.prepare(request);assertEquals("id = "+c[2],result.path("filterExpression").asText());assertEquals("SELECT id FROM items WHERE id = "+c[2],result.path("displaySql").asText());assertTrue(result.path("sql").asText().contains("?"));assertEquals(1,result.path("parameters").size());}
        for(Object[] c:new Object[][]{{java.sql.Types.INTEGER,"1.2"},{java.sql.Types.DECIMAL,"NaN"},{java.sql.Types.BOOLEAN,"perhaps"},{java.sql.Types.DATE,"2026-02-30"},{java.sql.Types.TIME,"25:00"},{java.sql.Types.TIMESTAMP,"2026-09-09"}}){var request=input("SELECT id FROM items","filter").put("operator","=").put("jdbcType",(Integer)c[0]).put("value",(String)c[1]);assertThrows(IllegalArgumentException.class,()->GridSql.prepare(request));}
    }
    @Test void refreshKeepsFiltersAndParametersAndRejectsWriteResults(){
        String exact="select /* keep source */ id\nfrom items where id > ? order by id desc;";var unchanged=input(exact,"refresh");unchanged.set("parameters",Profiles.JSON.createArrayNode().add(7));assertEquals(exact,GridSql.prepare(unchanged).path("sql").asText());assertEquals(7,GridSql.prepare(unchanged).path("parameters").get(0).asInt());
        var request=input("SELECT id FROM items WHERE id > ? ORDER BY id DESC","refresh");request.set("parameters",Profiles.JSON.createArrayNode().add(5));request.put("filterExpression","id > 5");var refreshed=GridSql.prepare(request);
        assertEquals("SELECT id FROM items WHERE id > ? ORDER BY id DESC",refreshed.path("sql").asText());assertEquals(5,refreshed.path("parameters").get(0).asInt());assertEquals("id > 5",refreshed.path("filterExpression").asText());
        for(String sql:List.of("DELETE FROM items RETURNING id","SELECT id INTO other FROM items","SELECT id FROM items; DELETE FROM items"))assertThrows(IllegalArgumentException.class,()->GridSql.edit(input(sql,"refresh")));
        assertEquals("SELECT version()",GridSql.edit(input("SELECT version()","refresh")).sql());
    }
}
