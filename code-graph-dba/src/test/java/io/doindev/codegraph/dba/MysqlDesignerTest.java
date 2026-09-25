package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MysqlDesignerTest {
    @Test void losslessAttributesAndPartitionOptions(){
        String ddl="CREATE TABLE `t` (`a` bigint unsigned NOT NULL, `b` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'x,y' COMMENT 'hello' /*!80023 INVISIBLE */, `ts` timestamp(6) NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6), KEY `k` (`b`(8))) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 PARTITION BY HASH (`a`) PARTITIONS 2";
        var table=MysqlTableDefinition.parse(ddl,false);assertEquals(4,table.clauses().size());assertTrue(table.options().contains("PARTITION BY HASH"));
        String b=MysqlTableDefinition.columns(table).get("b");b=MysqlTableDefinition.replace(b,"COMMENT","COMMENT 'changed'",false);b=MysqlTableDefinition.replace(b,"type","varchar(60)",false);
        assertTrue(b.contains("COLLATE utf8mb4_bin"));assertTrue(b.contains("DEFAULT 'x,y'"));assertTrue(b.contains("/*!80023 INVISIBLE */"));assertTrue(b.contains("COMMENT 'changed'"));
        String ts=MysqlTableDefinition.columns(table).get("ts");assertEquals("DEFAULT CURRENT_TIMESTAMP(6)",MysqlTableDefinition.attribute(ts,"DEFAULT",false));assertTrue(MysqlTableDefinition.replace(ts,"NULL","NOT NULL",false).contains("ON UPDATE CURRENT_TIMESTAMP(6)"));
    }
    @Test void generatedAndEnumClausesArePreserved(){
        String value="`e` enum('a,b','it''s','NULL') DEFAULT NULL COMMENT 'x'";assertEquals("enum('a,b','it''s','NULL')",MysqlTableDefinition.attribute(value,"type",false));assertEquals("DEFAULT NULL",MysqlTableDefinition.attribute(value,"DEFAULT",false));
        String generated="`g` int GENERATED ALWAYS AS ((`a` + 1)) STORED INVISIBLE";assertTrue(MysqlTableDefinition.replace(generated,"COMMENT","COMMENT 'kept'",false).contains("GENERATED ALWAYS AS ((`a` + 1)) STORED INVISIBLE"));
    }
    @Test void revisedColumnsPreserveUnknownAttributesAndUseOneAlter()throws Exception{
        ObjectNode snapshot=Profiles.JSON.createObjectNode().put("engine","mysql").put("schema","db").put("name","t").put("editable",true).put("fingerprint","x").put("mysqlSqlMode","");snapshot.putObject("fields").put("schema","db").put("name","t").put("comment","");
        snapshot.putArray("columns").addObject().put("id","1").put("name","a").put("type","varchar(10)").put("default","'x'").put("nullable",true).put("pk",0).put("comment","").put("nativeDefinition","`a` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT 'x' INVISIBLE");
        var draft=TableDesignerTest.draft(snapshot);((ObjectNode)draft.path("columns").get(0)).put("type","varchar(30)").put("default","'next'");var request=Profiles.JSON.createObjectNode().put("fingerprint","x");request.set("draft",draft);var plan=TableDesigner.prepare(snapshot,request);assertFalse(plan.path("atomic").asBoolean());String sql=plan.path("commands").get(0).path("sql").asText();assertTrue(sql.contains("COLLATE utf8mb4_bin"));assertTrue(sql.contains("INVISIBLE"));assertTrue(sql.contains("varchar(30)"));assertTrue(sql.contains("DEFAULT 'next'"));
        ((ObjectNode)draft.path("columns").get(0)).put("identity","d");assertThrows(IllegalArgumentException.class,()->TableDesigner.prepare(snapshot,request));
    }
}
