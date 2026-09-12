package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.lang.reflect.Proxy;
import static org.junit.jupiter.api.Assertions.*;

class TableQueriesTest {
    @TempDir Path root;
    static DatabaseMetaData metadata(String product,String quote,boolean catalogs,boolean atStart){
        return (DatabaseMetaData)Proxy.newProxyInstance(TableQueriesTest.class.getClassLoader(),new Class[]{DatabaseMetaData.class},(p,m,a)->switch(m.getName()){
            case "getDatabaseProductName"->product;case "getIdentifierQuoteString"->quote;case "supportsCatalogsInDataManipulation"->catalogs;case "isCatalogAtStart"->atStart;case "getCatalogSeparator"->".";default->throw new AssertionError(m.getName());
        });
    }
    @Test void quotesAndQualifiesWithoutAssumingTwoPartNames()throws Exception{
        assertEquals("SELECT * FROM \"Sales\".\"odd\"\" table\"",TableQueries.sql(metadata("PostgreSQL","\"",false,true),"db","Sales","odd\" table"));
        assertEquals("SELECT * FROM `db``name`.`select`",TableQueries.sql(metadata("MySQL","`",true,true),"db`name","ignored","select"));
        assertEquals("SELECT * FROM [db].[dbo].[a]]b]",TableQueries.sql(metadata("Microsoft SQL Server","[",true,true),"db","dbo","a]b"));
        assertEquals("SELECT * FROM \"db\".\"s\".\"t\"",TableQueries.sql(metadata("Snowflake","\"",true,true),"db","s","t"));
        assertEquals("SELECT * FROM \"s\".\"t\"",TableQueries.sql(metadata("Oracle","\"",false,true),"db","s","t"));
        assertEquals("SELECT * FROM \"main\".\"t\"",TableQueries.sql(metadata("SQLite","\"",false,true),"","main","t"));
        assertThrows(SQLFeatureNotSupportedException.class,()->TableQueries.sql(metadata("Custom"," ",false,true),"","","t"));
    }
    @Test void preparesVerifiedTablesAndReturnsBoundedTargetedRows()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,64L<<20,2,3,3,10),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var anchor=connections.open(id);var s=anchor.createStatement()){
                anchor.setAutoCommit(true);s.execute("CREATE SCHEMA A");s.execute("CREATE SCHEMA B");s.execute("CREATE TABLE A.ITEMS AS SELECT X AS ID FROM SYSTEM_RANGE(1,8)");s.execute("CREATE TABLE B.ITEMS AS SELECT 99 AS ID");
                var selection=MetadataActionsTest.selection(jobs,id,"tables","A","ITEMS");
                var prepared=HumanSqlTest.finish(jobs,"human",jobs.tablePreparation("human",id,selection));assertEquals("complete",prepared.path("state").asText(),prepared.toString());
                var sql=prepared.path("result").path("sql").asText();assertTrue(sql.contains("\"A\".\"ITEMS\""),sql);
                var rows=HumanSqlTest.finish(jobs,"human",jobs.tableQuery("human",id,sql,Profiles.JSON.createArrayNode(),anchor.getCatalog()));
                assertEquals("complete",rows.path("state").asText(),rows.toString());assertEquals(3,rows.path("result").path("rowCount").asInt());assertTrue(rows.path("result").path("truncated").asBoolean());
                var filtered=HumanSqlTest.finish(jobs,"human",jobs.tableQuery("human",id,sql+" WHERE ID = ?",Profiles.JSON.createArrayNode().add(2),anchor.getCatalog()));assertEquals(1,filtered.path("result").path("rowCount").asInt(),filtered.toString());
                var wrong=HumanSqlTest.finish(jobs,"human",jobs.tableQuery("human",id,sql,Profiles.JSON.createArrayNode(),"wrong_database"));assertEquals("failed",wrong.path("state").asText());
                try(var check=connections.open(id)){assertEquals(anchor.getCatalog(),check.getCatalog());}
                assertThrows(SecurityException.class,()->jobs.tablePreparation("agent:x",id,selection));assertThrows(SecurityException.class,()->jobs.tableQuery("agent:x",id,sql,Profiles.JSON.createArrayNode(),""));
                assertThrows(IllegalArgumentException.class,()->jobs.tableQuery("human",id,"DELETE FROM A.ITEMS",Profiles.JSON.createArrayNode(),""));
                assertThrows(IllegalArgumentException.class,()->jobs.tablePreparation("human",id,selection.deepCopy().set("parent",Profiles.JSON.createObjectNode().put("kind","roles"))));
                s.execute("DROP TABLE A.ITEMS");assertEquals("failed",HumanSqlTest.finish(jobs,"human",jobs.tablePreparation("human",id,selection)).path("state").asText());
                assertEquals(0,jobs.telemetry().path("retainedJobs").asInt());
            }
        }
    }
}
