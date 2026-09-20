package io.doindev.codegraph.dba;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GridBudgetTest {
    @TempDir Path root;
    @Test void retainedWidePagesRejectAdmissionWithoutEvictingExistingSnapshots()throws Exception{
        var config=new DbaConfig(root,64L<<20,2,1000,100,15);
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,config,s->true);var grids=new GridResults(jobs,connections,()->config,s->true)){
            jobs.grids=grids;var input=new DbaTest().input();input.put("url",input.path("url").asText()+";DB_CLOSE_DELAY=-1");String id=profiles.put(null,input).path("id").asText();
            try(var c=connections.open(id);var s=c.createStatement()){c.setAutoCommit(true);s.execute("CREATE TABLE WIDE_GRID(ID INT PRIMARY KEY, CONTENT VARCHAR(8000))");s.execute("INSERT INTO WIDE_GRID SELECT X, REPEAT('x',7000) FROM SYSTEM_RANGE(1,200)");}
            var retained=new ArrayList<String>();boolean refused=false;
            for(int i=0;i<20;i++){
                var submitted=jobs.humanQuery("human",id,"SELECT * FROM WIDE_GRID",Profiles.JSON.createArrayNode(),false);
                var completed=HumanSqlTest.finish(jobs,"human",submitted);assertEquals("complete",completed.path("state").asText(),completed.toString());
                var row=completed.path("result").path("results").get(0);
                if(row.has("grid"))retained.add(row.path("grid").path("id").asText());else{assertTrue(row.has("gridUnavailable"));refused=true;}
                assertTrue(jobs.telemetry().path("reservedBytes").asLong()<=config.memoryBytes());
                for(String grid:retained)assertNotNull(grids.require("human",grid),"Admission must not evict prior contexts");
                if(refused)break;
            }
            assertTrue(refused,"Wide contexts must eventually hit the shared allowance");assertTrue(retained.size()>1);
            for(String grid:retained)grids.release("human",grid);
            assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
}
