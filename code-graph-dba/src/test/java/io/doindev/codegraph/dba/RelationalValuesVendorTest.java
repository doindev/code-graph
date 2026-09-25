package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="DBA_COMPARE_DISPOSABLE",matches="cgraph-compare-qa-[a-f0-9]+")
class RelationalValuesVendorTest {
    @TempDir Path directory;
    @Test @Timeout(180) void exactNativeValuesRoundTripThroughEveryDataMode()throws Exception {
        String engine=System.getenv("DBA_COMPARE_VENDOR");boolean pg=engine.equals("postgresql");String schema=pg?"value_checks":"compare_test";
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,384L<<20,2,100,100,120),s->true);var compare=new DatabaseCompare(profiles,connections,jobs,directory,s->true)) {
            String source=profiles.put(null,CompareVendorIntegrationTest.profile(engine,"Values source",System.getenv("DBA_COMPARE_SOURCE"))).path("id").asText(),dest=profiles.put(null,CompareVendorIntegrationTest.profile(engine,"Values destination",System.getenv("DBA_COMPARE_DESTINATION"))).path("id").asText();
            try(var a=connections.open(source);var b=connections.open(dest);var left=a.createStatement();var right=b.createStatement()) {
                a.setAutoCommit(true);b.setAutoCommit(true);if(!pg){left.execute("SET SESSION time_zone='+05:30'");right.execute("SET SESSION time_zone='-04:00'");}
                for(var st:List.of(left,right)) {
                    if(pg){st.execute("CREATE SCHEMA value_checks");st.execute("CREATE TYPE value_checks.colour AS ENUM ('red','blue')");st.execute("CREATE DOMAIN value_checks.exact AS numeric(38,12)");}
                    st.execute("CREATE TABLE "+schema+".native_values(id int PRIMARY KEY, amount "+(pg?"value_checks.exact":"decimal(65,20)")+", wide "+(pg?"numeric(20,0)":"bigint unsigned")+", payload "+(pg?"bytea":"longblob")+", title "+(pg?"text":"longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin")+", moment "+(pg?"timestamptz":"datetime(6)")+", doc "+(pg?"jsonb":"json")+", choice "+(pg?"value_checks.colour":"enum('red','blue')")+", extra "+(pg?"text[]":"bit(16)")+(pg?"":", instant timestamp(6) NULL")+")");
                }
                String extra=pg?"ARRAY['semi;colon',NULL,'quote\"slash\\']":"b'1010101000000011'";
                String values="12345678901234567890.123456789012,18446744073709551615,"+(pg?"decode('0001ff','hex')":"X'0001ff'")+",'Unicode \\u03bb \\u4e2d \\uD83D\\uDE00; empty next',"+(pg?"'2026-09-24 12:34:56.123456+05:30'":"'2026-09-24 12:34:56.123456'")+",'{\"exact\":9007199254740993}', 'blue',"+extra;
                // Java Unicode escapes are materialized without relying on the shell code page.
                values=values.replace("\\u03bb","\u03bb").replace("\\u4e2d","\u4e2d").replace("\\uD83D\\uDE00","\uD83D\uDE00");
                left.execute("INSERT INTO "+schema+".native_values VALUES(1,"+values+(pg?"":",'2026-09-24 12:34:56.123456'")+"),(2,NULL,NULL,NULL,'',NULL,NULL,NULL,NULL"+(pg?"":",NULL")+")");
                try(var valuesCheck=left.executeQuery("SELECT * FROM "+schema+".native_values ORDER BY id")){while(valuesCheck.next())for(int col=1;col<=valuesCheck.getMetaData().getColumnCount();col++){try{CompareData.cell(valuesCheck,col,valuesCheck.getMetaData().getColumnType(col),valuesCheck.getMetaData().getColumnTypeName(col),engine);}catch(Exception failure){throw new IllegalArgumentException("Native codec column "+col+" type "+valuesCheck.getMetaData().getColumnTypeName(col)+" JDBC "+valuesCheck.getMetaData().getColumnType(col)+" precision "+valuesCheck.getMetaData().getPrecision(col),failure);}}}
                String objectId=TableDesigner.hash(Profiles.JSON.getNodeFactory().textNode(CompareCatalog.key(schema,"tables","native_values"))).substring(0,32);
                var input=Profiles.JSON.createObjectNode().put("sourceReceipt",DatabaseCompareTest.receipt(compare,jobs,source,schema)).put("destinationReceipt",DatabaseCompareTest.receipt(compare,jobs,dest,schema)).put("dataMode","upsert");input.putArray("objectTypes").add("tables");input.putArray("objectIds").add(objectId);input.putArray("tableData").addObject().put("id",objectId).put("includeData",true);
                for(String mode:List.of("insert","upsert","replace","mirror")) {
                    right.execute("DELETE FROM "+schema+".native_values");right.execute("INSERT INTO "+schema+".native_values(id,title) VALUES(3,'destination only')");
                    String id=DatabaseCompareTest.finish(jobs,compare.start("human",input)).path("comparisonId").asText();var selected=DatabaseCompareTest.select(compare,id).put("dataMode",mode);for(var option:selected.path("objects"))if(option.path("id").asText().equals(objectId))((ObjectNode)option).put("includeData",true);
                    var artifact=DatabaseCompareTest.finish(jobs,compare.generate("human",id,selected));String sql=compare.artifacts.preview("human",artifact.path("artifactId").asText()).path("sql").asText();if(pg)right.execute(sql);else for(var unit:MysqlScript.extract(sql,1<<20,MysqlScript.Mode.parse("")))right.execute(unit.sql());compare.remove("human",id);
                    if(!pg){left.execute("SET SESSION time_zone='+00:00'");right.execute("SET SESSION time_zone='+00:00'");}
                    try(var x=left.executeQuery("SELECT * FROM "+schema+".native_values ORDER BY id");var y=right.executeQuery("SELECT * FROM "+schema+".native_values WHERE id<3 ORDER BY id")) {
                        while(x.next()){assertTrue(y.next());for(int col=1;col<=x.getMetaData().getColumnCount();col++)assertEquals(CompareData.cell(x,col,x.getMetaData().getColumnType(col),x.getMetaData().getColumnTypeName(col),engine),CompareData.cell(y,col,y.getMetaData().getColumnType(col),y.getMetaData().getColumnTypeName(col),engine),mode+" column "+col);}assertFalse(y.next());
                    }
                    try(var rows=right.executeQuery("SELECT count(*) FROM "+schema+".native_values")){assertTrue(rows.next());assertEquals(Set.of("mirror","replace").contains(mode)?2:3,rows.getInt(1));}
                }
                if(!pg){left.execute("ALTER TABLE compare_test.native_values ADD generator bigint unsigned NOT NULL AUTO_INCREMENT UNIQUE");left.execute("ALTER TABLE compare_test.native_values AUTO_INCREMENT=1001");}
                System.out.println("RELATIONAL_VALUES_VERIFIED "+engine+" exact numerics, unsigned, Unicode, temporal, binary, JSON, enum/domain/array/BIT; four data modes");
            }
        }
    }
}
