package io.doindev.codegraph.dba;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRoadmapTest {
    @Test void nativeNormalizationPreservesLiteralStateTextAndPartitionBounds(){
        String a="CREATE TABLE `t` (`id` int, `note` varchar(100) DEFAULT ' AUTO_INCREMENT=123') ENGINE=InnoDB AUTO_INCREMENT=42 PARTITION BY HASH (`id`) PARTITIONS 2";
        String normalized=CompareSql.normalizeMysql(a);
        assertTrue(normalized.contains("' AUTO_INCREMENT=123'"));assertFalse(normalized.contains("AUTO_INCREMENT=42"));assertTrue(normalized.contains("PARTITION BY HASH (`id`) PARTITIONS 2"));
        assertEquals(normalized,CompareSql.normalizeMysql(a.replace("AUTO_INCREMENT=42","AUTO_INCREMENT=99")));
        assertNotEquals(normalized,CompareSql.normalizeMysql(a.replace("AUTO_INCREMENT=123","AUTO_INCREMENT=124")));
        var object=CompareCatalog.item("db","tables","t").put("nativeDdl",a);var advanced=object.deepCopy().put("nativeDdl",a.replace("AUTO_INCREMENT=42","AUTO_INCREMENT=99"));assertEquals(CompareCatalog.definition(object),CompareCatalog.definition(advanced));
    }
    @Test void mysqlCanonicalizationPreservesStringsAndHonorsIdentifierModes(){
        var ordinary=MysqlScript.Mode.parse("");var ansi=MysqlScript.Mode.parse("ANSI_QUOTES,NO_BACKSLASH_ESCAPES");
        assertEquals("SELECT n, \"n\", 'n'",CompareSql.canonical("SELECT `n`, \"n\", 'n'","mysql",ordinary));
        assertEquals("SELECT n",CompareSql.canonical("SELECT \"n\"","mariadb",ansi));
        assertEquals(java.util.List.of("GRANT OPTION"),MysqlGrants.privileges(Profiles.JSON.createObjectNode().put("privileges","Grant")));
    }
    @Test void comparisonMapsNativeProgramUsingItsOwnQuoteMode(){
        var source=new CompareCatalog.Inventory("mysql","source_db","8.4");var dest=new CompareCatalog.Inventory("mysql","target_db","8.4");
        var plan=new CompareSql.Plan(source,dest,new CompareCatalog.Target("s","source_db","source_db",false,""),new CompareCatalog.Target("d","target_db","target_db",false,""),Profiles.JSON.createObjectNode());
        var object=Profiles.JSON.createObjectNode().put("ddl","SELECT 'slash\\' FROM source_db.t");object.putObject("mysqlProgram").put("sqlMode","NO_BACKSLASH_ESCAPES");
        var mapped=CompareSql.mappedNode(plan,object);assertEquals("SELECT 'slash\\' FROM `target_db`.t",mapped.path("ddl").asText());
    }
    @Test void routineCannotBeEmittedBeforeItsNewViewDependency(){
        var source=new CompareCatalog.Inventory("postgresql","db","16.14");var dest=new CompareCatalog.Inventory("postgresql","db","16.14");var target=new CompareCatalog.Target("c","db","public",false,"");
        var plan=new CompareSql.Plan(source,dest,target,target,Profiles.JSON.createObjectNode());var view=CompareCatalog.item("public","views","v");var routine=CompareCatalog.item("public","functions","f()");String dependency=CompareCatalog.key("public","views","v");routine.withArray("dependencies").add(dependency);
        plan.selected.put(dependency,new CompareSql.Choice(view,null,Profiles.JSON.createObjectNode()));plan.selected.put(CompareCatalog.key("public","functions","f()"),new CompareSql.Choice(routine,null,Profiles.JSON.createObjectNode()));
        assertTrue(assertThrows(IllegalArgumentException.class,()->PostgresCompare.prepareRebuilds(plan)).getMessage().contains("staged migration"));
    }
    @Test void legacyZeroAndInvalidDatesDoNotProduceUnexecutableDataScripts(){
        assertThrows(IllegalArgumentException.class,()->CompareData.validateTemporal("0000-00-00",java.sql.Types.DATE,"mysql"));assertThrows(IllegalArgumentException.class,()->CompareData.validateTemporal("2026-02-31 01:02:03",java.sql.Types.TIMESTAMP,"mariadb"));
        assertDoesNotThrow(()->CompareData.validateTemporal("2024-02-29 01:02:03.123456",java.sql.Types.TIMESTAMP,"mysql"));assertDoesNotThrow(()->CompareData.validateTemporal("-838:59:59",java.sql.Types.TIME,"mariadb"));
    }
    @Test void nativeColumnIdentityDoesNotReparseModeSensitiveDefaults(){
        assertEquals("n",MysqlTableDefinition.columnName("`n` varchar(40) DEFAULT 'backslash\\'"));
        String sql="SELECT \"a\\\"b\"";assertEquals(2,MysqlTableDefinition.tokens(sql,new MysqlScript.Mode(false,false)).size());
    }
    @Test void exactParametersRejectUnboundedExponentsAndUnsupportedNativeTypes(){
        var values=Profiles.JSON.createArrayNode();values.addObject().put("mode","in").put("type","DECIMAL").put("value","123456789012345678901234567890.123456789012");assertDoesNotThrow(()->NativeParameters.check(values,"mysql"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)values.get(0)).put("value","1e999999999");assertThrows(IllegalArgumentException.class,()->NativeParameters.check(values,"mysql"));
        values.removeAll();values.addObject().put("mode","out").put("type","REF_CURSOR");assertThrows(IllegalArgumentException.class,()->NativeParameters.check(values,"postgresql"));
    }
    @Test void administrationRejectsClientSqlAndKeepsMariaRolesSeparateFromAccounts(){
        assertThrows(IllegalArgumentException.class,()->RelationalAdminPlans.request(Profiles.JSON.createObjectNode().put("action","create_user").put("sql","DROP TABLE t")));
        assertEquals(java.util.List.of("name"),RelationalAdminPlans.definitions("mariadb").stream().filter(a->a.id().equals("create_role")).findFirst().orElseThrow().fields());
        assertTrue(RelationalAdminPlans.definitions("mysql").stream().filter(a->a.id().equals("create_role")).findFirst().orElseThrow().fields().contains("host"));
        assertEquals("'a'\"'\"'b'",RelationalBackupScripts.shell("a'b"));assertThrows(IllegalArgumentException.class,()->RelationalBackupScripts.shell("a\nb"));
    }
}
