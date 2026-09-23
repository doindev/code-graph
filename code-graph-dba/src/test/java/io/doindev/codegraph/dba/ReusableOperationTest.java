package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReusableOperationTest {
    static ObjectNode scope(String vendor){return Profiles.JSON.createObjectNode().put("vendor",vendor).put("database","app").put("schema",vendor.equals("postgresql")?"public":vendor.equals("h2")?"PUBLIC":"app").put("connectionId","fixed");}
    @Test void separatesInspectionReadPlanAndCreation(){
        for(String vendor:List.of("mysql","mariadb"))for(String kind:List.of("TABLE","VIEW","PROCEDURE","FUNCTION")){
            var r=ReusableOperation.classify("SHOW /* ordinary comment */ CREATE "+kind+" app.item;",scope(vendor));
            assertTrue(r.eligible(),r.reason());assertEquals("ddl_inspection",r.category());assertTrue(r.readOnly());
        }
        for(String vendor:List.of("postgresql","mysql","mariadb")){
            var s=scope(vendor);
            for(String sql:List.of("SELECT 1","VALUES (1)","SELECT id FROM "+s.path("schema").asText()+".items WHERE id=?","EXPLAIN SELECT 1")){
                var r=ReusableOperation.classify(sql,s);assertTrue(r.eligible(),sql+": "+r.reason());
            }
            assertEquals("explain",ReusableOperation.classify("EXPLAIN SELECT 1",s).category());
        }
        for(String vendor:List.of("postgresql","mysql","mariadb","h2")){
            var s=scope(vendor);
            for(String sql:List.of("CREATE TABLE sample(id INT PRIMARY KEY, name VARCHAR(80) NOT NULL, created DATE DEFAULT CURRENT_DATE)","CREATE VIEW sample AS SELECT 1")){
                var r=ReusableOperation.classify(sql,s);assertTrue(r.eligible(),vendor+" "+sql+": "+r.reason());
            }
        }
    }
    @Test void booleanLiteralsInSelectKeepReusableReadChoicesAvailable(){
        for(String vendor:List.of("postgresql","mysql","mariadb")){
            var target=scope(vendor);
            for(String sql:List.of("SELECT TRUE", "SELECT FALSE", "SELECT id FROM "+target.path("schema").asText()+".items WHERE active = TRUE", "SELECT id FROM "+target.path("schema").asText()+".items WHERE active <> FALSE")){
                var operation=ReusableOperation.classify(sql,target);
                assertTrue(operation.eligible(),vendor+" "+sql+": "+operation.reason());
                assertTrue(operation.readOnly());assertEquals("read",operation.category());
                for(var choice:ApprovalQueue.choices(operation,true))assertTrue(choice.path("enabled").asBoolean(),choice.toString());
            }
        }
    }
    @Test void hardDeniesDangerousUnknownOrCrossScopeEvenForExact(){
        for(String vendor:List.of("postgresql","mysql","mariadb","h2"))for(String sql:List.of(
                "DROP TABLE item","DELETE FROM item","TRUNCATE TABLE item","UPDATE item SET x=1","INSERT INTO item VALUES(1)",
                "MERGE INTO item USING other ON 1=1 WHEN MATCHED THEN DELETE","ALTER TABLE item ADD x INT",
                "CREATE OR REPLACE VIEW v AS SELECT 1","CREATE TABLE item AS SELECT 1","CREATE TABLE item LIKE other",
                "CREATE TABLE item(id INT DEFAULT evil())","GRANT SELECT ON item TO public","CREATE USER x",
                "CALL item()","DO $$BEGIN END$$","EXPLAIN ANALYZE SELECT 1","EXPLAIN (ANALYZE TRUE) SELECT 1",
                "SELECT evil()","SELECT TRUE WHERE evil()","SELECT CAST(TRUE AS INT)","SELECT 1; DELETE FROM item","SELECT 1; SELECT 2","/*!50000 DROP TABLE item */ SELECT 1",
                "/*M! DROP TABLE item */ SELECT 1","SELECT * FROM unrelated.secret","CREATE TABLE unrelated.item(id INT)",
                "CREATE VIEW item AS SELECT * FROM unrelated.secret","SELECT * FROM item FOR UPDATE",
                "WITH d AS (DELETE FROM item RETURNING *) SELECT * FROM d","SELECT 1 INTO item",
                "CREATE FUNCTION item() RETURNS INT SQL SECURITY DEFINER RETURN 1","CREATE ALIAS item FOR 'external.code'")){
            assertFalse(ReusableOperation.classify(sql,scope(vendor)).eligible(),vendor+" "+sql);
        }
    }
    @Test void routineBodiesAndSecurityAreExplicitlyLimited(){
        var pg=scope("postgresql");
        assertTrue(ReusableOperation.classify("CREATE FUNCTION public.plusone(x INT) RETURNS INT LANGUAGE SQL SECURITY INVOKER AS $$ SELECT x + 1 $$",pg).eligible());
        assertTrue(ReusableOperation.classify("CREATE PROCEDURE public.simple() LANGUAGE SQL AS $$ SELECT 1 $$",pg).eligible());
        for(String sql:List.of("CREATE FUNCTION f() RETURNS INT LANGUAGE SQL SECURITY DEFINER AS $$ SELECT 1 $$",
                "CREATE FUNCTION f() RETURNS INT LANGUAGE plpgsql AS $$ BEGIN EXECUTE 'DELETE FROM x'; RETURN 1; END $$",
                "CREATE FUNCTION f() RETURNS INT LANGUAGE SQL AS $$ SELECT 1; DELETE FROM x $$",
                "CREATE FUNCTION f() RETURNS INT LANGUAGE SQL AS $$ SELECT other.f() $$",
                "CREATE PROCEDURE p() LANGUAGE SQL AS $$ SELECT * FROM other.secret $$"))
            assertFalse(ReusableOperation.classify(sql,pg).eligible(),sql);
        for(String vendor:List.of("mysql","mariadb")){
            assertTrue(ReusableOperation.classify("CREATE FUNCTION app.plusone(x INT) RETURNS INT DETERMINISTIC NO SQL SQL SECURITY INVOKER RETURN x + 1",scope(vendor)).eligible());
            assertTrue(ReusableOperation.classify("CREATE PROCEDURE app.simple() READS SQL DATA SQL SECURITY INVOKER SELECT id FROM app.items",scope(vendor)).eligible());
            assertFalse(ReusableOperation.classify("CREATE FUNCTION f() RETURNS INT RETURN 1",scope(vendor)).eligible());
        }
    }
    @Test void quotedNamesCommentsAndUnsupportedVendorsFailClosed(){
        var references=ReusableOperation.classify("SELECT a.id FROM public.items a JOIN other_items b ON a.id=b.id",scope("postgresql")).references();
        assertEquals(List.of(new ReusableOperation.Reference("items",true),new ReusableOperation.Reference("other_items",false)),references);
        assertTrue(ReusableOperation.classify("CREATE TABLE public.\"odd;name\"(\"a\" INT)",scope("postgresql")).eligible());
        assertFalse(ReusableOperation.classify("CREATE TABLE \"public.other\".item(id INT)",scope("postgresql")).eligible());
        assertFalse(ReusableOperation.classify("SELECT 1",scope("oracle")).eligible());
        assertFalse(ReusableOperation.classify("SELECT 1",scope("h2")).eligible());
        assertFalse(ReusableOperation.classify("SELECT 1",scope("mysql").put("database","")).eligible());
        assertFalse(ReusableOperation.classify("SELECT 'x\\'; DELETE FROM a; -- '",scope("mysql")).eligible());
    }
}
