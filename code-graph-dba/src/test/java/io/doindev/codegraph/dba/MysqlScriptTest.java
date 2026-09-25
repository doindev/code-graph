package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MysqlScriptTest {
    private java.util.List<SqlScript.Unit> parse(String sql,String mode){return MysqlScript.extract(sql,65536,MysqlScript.Mode.parse(mode));}
    @Test void preservesStoredProgramsAndRemovesClientDirectives(){
        var units=parse("DELIMITER $$\nCREATE PROCEDURE p() BEGIN SELECT ';?'; SELECT 2; END$$\nDELIMITER ;\nCALL p(); SELECT ?;","");
        assertEquals(3,units.size());assertTrue(units.getFirst().sql().endsWith("END"));assertEquals(0,units.getFirst().parameters());assertEquals(1,units.getLast().parameters());
        assertEquals(2,parse("DELIMITER //\nCREATE TRIGGER t BEFORE INSERT ON x FOR EACH ROW BEGIN SET NEW.a=1; END//\nDELIMITER ;\nSELECT 1;","").size());
    }
    @Test void honorsEscapingAndIdentifierModes(){
        assertEquals(1,parse("SELECT 'a\\';?b', ?;","").getFirst().parameters());
        assertEquals(2,parse("SELECT 'a\\'; SELECT ?;","NO_BACKSLASH_ESCAPES").size());
        assertEquals(2,parse("SELECT \"a\\\"; SELECT ?;","ANSI_QUOTES").size());
        assertEquals(0,parse("SELECT `a``?;b`, 'x''?;y';","").getFirst().parameters());
        assertEquals(1,parse("SELECT 1--2, ?; # ? ;\n","").getFirst().parameters());
    }
    @Test void rejectsAmbiguousOrIncompleteScriptsBeforeExecution(){
        assertThrows(IllegalArgumentException.class,()->parse("CREATE PROCEDURE p() BEGIN SELECT 1; END;", ""));
        assertThrows(IllegalArgumentException.class,()->parse("SELECT 'unfinished", ""));
        assertEquals(2,parse("SET SESSION sql_mode='ANSI_QUOTES'; SELECT 1;", "").size());
        assertThrows(IllegalArgumentException.class,()->parse("SET SESSION sql_mode=@saved; SELECT 1;", ""));
        assertThrows(IllegalArgumentException.class,()->parse("/*!80000 SELECT 1 */;", ""));
        assertThrows(IllegalArgumentException.class,()->parse("SELECT 1\nDELIMITER $$\n", ""));
        assertThrows(IllegalArgumentException.class,()->parse("SELECT 1;".repeat(33), ""));
    }
    @Test void nativeSingleDefinitionAndLargeMigrationStayIntact(){
        String sql="CREATE PROCEDURE p() BEGIN SELECT '"+"a".repeat(17000)+"'; END";
        assertEquals(sql,MysqlScript.single(sql,65536,MysqlScript.Mode.parse("")).getFirst().sql());
        assertEquals(1,SqlScript.extract("DELIMITER $$\n"+sql+"$$", "mysql",65536).size());
    }
    @Test void delimitersInsideCommentsAndStringsAreData(){
        var units=parse("DELIMITER $$\nSELECT '$$\\\'x', ? /* $$ */ # $$\n$$\nDELIMITER ;\nSELECT 2;", "");
        assertEquals(2,units.size());assertEquals(1,units.getFirst().parameters());
    }
}
