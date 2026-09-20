package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GridPrivilegesTest {
    @TempDir Path root;
    @Test void databasePrivilegesRemainTheFinalBoundary()throws Exception{
        // A loopback H2 server shares one disposable database across isolated profile
        // classloaders, allowing a genuinely restricted database account.
        var server=org.h2.tools.Server.createTcpServer("-tcpPort","0","-ifNotExists").start();
        String url="jdbc:h2:tcp://localhost:"+server.getPort()+"/mem:grid_privileges;DB_CLOSE_DELAY=-1";
        var config=new DbaConfig(root,128L<<20,2,1000,100,15);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;String admin=profiles.put(null,new DbaTest().input().put("url",url)).path("id").asText();
            try(var c=connections.open(admin);var s=c.createStatement()){
                c.setReadOnly(false);c.setAutoCommit(true);
                s.execute("CREATE TABLE PRIV_GRID(ID INT PRIMARY KEY, LABEL VARCHAR(20))");
                s.execute("INSERT INTO PRIV_GRID VALUES(1,'original')");
                s.execute("CREATE USER GRID_READER PASSWORD 'fixture-only'");
                s.execute("GRANT SELECT ON PRIV_GRID TO GRID_READER");
                String reader=profiles.put(null,new DbaTest().input().put("name","reader").put("url",url.substring(0,url.indexOf(';'))).put("username","GRID_READER").put("password","fixture-only")).path("id").asText();
                var query=HumanSqlTest.finish(jobs,"human",jobs.humanQuery("human",reader,"SELECT * FROM PUBLIC.PRIV_GRID",Profiles.JSON.createArrayNode(),false));
                assertEquals("complete",query.path("state").asText(),query.toString());var grid=query.path("result").path("results").get(0).path("grid");
                var draft=Profiles.JSON.createObjectNode().put("revision",1);draft.putArray("changes").addObject().put("rowId",grid.path("rowIds").get(0).asText()).put("operation","update").putObject("values").putObject("c2").put("kind","value").put("value","not permitted");
                var prepared=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"prepare",draft));
                assertEquals("complete",prepared.path("state").asText(),prepared.toString());
                var save=HumanSqlTest.finish(jobs,"human",grids.operation("human",grid.path("id").asText(),"apply",Profiles.JSON.createObjectNode().put("revision",1).put("planId",prepared.path("result").path("planId").asText())));
                assertEquals("failed",save.path("state").asText(),save.toString());assertFalse(save.toString().contains("fixture-only"));
                try(var rows=s.executeQuery("SELECT LABEL FROM PRIV_GRID WHERE ID=1")){rows.next();assertEquals("original",rows.getString(1));}
            }
        }finally{server.stop();}
    }
}
