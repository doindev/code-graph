package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GridValuesTest {
    @TempDir Path root;
    final class Fixture implements AutoCloseable{
        final DbaConfig config=new DbaConfig(root,256L<<20,2,1000,100,15);
        final Profiles profiles=new Profiles(root,new DbaTest.MemoryVault());
        final Connections connections=new Connections(profiles);
        final QueryJobs jobs=new QueryJobs(connections,config,s->true);
        final GridResults grids=new GridResults(jobs,connections,()->config,s->true);
        final String id=profiles.put(null,new DbaTest().input()).path("id").asText();
        final Connection connection=connections.open(id);
        Fixture()throws Exception{jobs.grids=grids;connection.setAutoCommit(true);}
        void sql(String sql)throws Exception{try(var statement=connection.createStatement()){statement.execute(sql);}}
        JsonNode finish(ObjectNode job)throws Exception{JsonNode result=HumanSqlTest.finish(jobs,"human",job);assertEquals("complete",result.path("state").asText(),result.toString());return result.path("result");}
        JsonNode query(String sql)throws Exception{return finish(jobs.humanQuery("human",id,sql,Profiles.JSON.createArrayNode(),false)).path("results").get(0);}
        ObjectNode input(JsonNode grid,String column){return Profiles.JSON.createObjectNode().put("revision",grid.path("revision").asLong()).put("columnId",column);}
        JsonNode values(JsonNode grid,ObjectNode input)throws Exception{return finish(grids.operation("human",grid.path("id").asText(),"values",input));}
        public void close()throws Exception{connection.close();grids.close();jobs.close();connections.close();profiles.close();}
    }
    @Test void wholeSourceValuesArePagedCountedAndDoNotChangeGrid()throws Exception{
        try(var f=new Fixture()){
            f.sql("CREATE TABLE ITEMS(ID INT, LABEL VARCHAR(40))");
            f.sql("INSERT INTO ITEMS SELECT X, 'value-'||LPAD(CAST(X AS VARCHAR),3,'0') FROM SYSTEM_RANGE(1,451)");
            f.sql("INSERT INTO ITEMS VALUES(452,NULL),(453,NULL),(454,''),(455,'NULL'),(456,'value-001')");
            JsonNode rows=f.query("SELECT ID, LABEL AS CHOICE FROM PUBLIC.ITEMS WHERE ID=1"),grid=rows.path("grid");
            assertFalse(grid.path("capabilities").path("edit").asBoolean(),"No primary key is required for choices");
            String before=f.grids.require("human",grid.path("id").asText()).result.toString();
            var input=f.input(grid,"c2").put("showRowCount",true).put("showDistinctValuesCount",true);
            JsonNode first=f.values(grid,input);assertEquals(200,first.path("values").size());assertTrue(first.path("hasMore").asBoolean());
            assertEquals(454,first.path("totalDistinct").asInt());assertEquals(454,first.path("matchingDistinct").asInt());
            assertEquals("2",first.path("values").get(0).path("count").asText());assertTrue(first.path("values").get(0).path("value").isNull());
            var seen=new HashSet<String>();for(JsonNode v:first.path("values"))seen.add(v.path("value").toString());
            for(int offset:new int[]{200,400}){JsonNode page=f.values(grid,input.deepCopy().put("offset",offset));for(JsonNode v:page.path("values"))assertTrue(seen.add(v.path("value").toString()));if(offset==400)assertFalse(page.path("hasMore").asBoolean());}
            assertEquals(454,seen.size());
            JsonNode searched=f.values(grid,input.deepCopy().put("search","value-451"));
            assertEquals(1,searched.path("values").size());assertEquals(454,searched.path("totalDistinct").asInt());assertEquals(1,searched.path("matchingDistinct").asInt());
            JsonNode plain=f.values(grid,f.input(grid,"c2").put("search","value-001"));
            assertFalse(plain.has("totalDistinct"));assertFalse(plain.path("values").get(0).has("count"));
            assertEquals(before,f.grids.require("human",grid.path("id").asText()).result.toString());assertEquals(1,f.grids.status("human",grid.path("id").asText()).path("revision").asLong());
            assertThrows(SecurityException.class,()->f.grids.operation("other",grid.path("id").asText(),"values",input));
            assertThrows(IllegalArgumentException.class,()->f.grids.operation("human",grid.path("id").asText(),"values",input.deepCopy().put("revision",9)));
        }
    }
    @Test void quotedViewsJoinsEscapedSearchAndExactNumbers()throws Exception{
        try(var f=new Fixture()){
            f.sql("CREATE TABLE \"odd.table\"(\"Big Value\" DECIMAL(30,3), NAME VARCHAR(100))");
            f.sql("INSERT INTO \"odd.table\" VALUES(9007199254740993.125,'100%_!'),(9007199254740994.126,'x'' OR 1=1 --')");
            f.sql("CREATE VIEW CHOICES AS SELECT * FROM \"odd.table\"");
            JsonNode rows=f.query("SELECT x.\"Big Value\", x.NAME FROM PUBLIC.CHOICES x");
            JsonNode values=f.values(rows.path("grid"),f.input(rows.path("grid"),"c1"));
            assertEquals("9007199254740993.125",values.path("values").get(0).path("value").asText());
            values=f.values(rows.path("grid"),f.input(rows.path("grid"),"c2").put("search","%_!"));
            assertEquals(1,values.path("values").size());assertEquals("100%_!",values.path("values").get(0).path("value").asText());
            values=f.values(rows.path("grid"),f.input(rows.path("grid"),"c2").put("search","x' OR 1=1 --"));assertEquals(1,values.path("values").size());
            JsonNode joined=f.query("SELECT a.NAME,b.NAME FROM PUBLIC.CHOICES a JOIN PUBLIC.CHOICES b ON a.NAME=b.NAME");
            assertEquals(2,f.values(joined.path("grid"),f.input(joined.path("grid"),"c2")).path("values").size());
            JsonNode computed=f.query("SELECT 1+1 AS CALCULATED FROM PUBLIC.CHOICES");
            ObjectNode job=f.grids.operation("human",computed.path("grid").path("id").asText(),"values",f.input(computed.path("grid"),"c1"));
            JsonNode failure=HumanSqlTest.finish(f.jobs,"human",job);assertEquals("failed",failure.path("state").asText());assertFalse(failure.path("error").asText().isBlank());
        }
    }
    @Test void invalidOversizedCancelledAndChangedContextsFailWithoutChangingRows()throws Exception{
        try(var f=new Fixture()){
            f.sql("CREATE TABLE LIMITS(ID INT, VALUE_TEXT VARCHAR(9000))");f.sql("INSERT INTO LIMITS VALUES(1,REPEAT('x',8500))");
            JsonNode rows=f.query("SELECT * FROM LIMITS WHERE 1=0"),grid=rows.path("grid");String id=grid.path("id").asText();
            for(ObjectNode request:List.of(f.input(grid,"missing"),f.input(grid,"c1").put("offset",-1),f.input(grid,"c1").put("offset","0"),f.input(grid,"c1").put("search","x".repeat(513)),f.input(grid,"c1").put("showRowCount","true"),f.input(grid,"c2"))){
                JsonNode failed=HumanSqlTest.finish(f.jobs,"human",f.grids.operation("human",id,"values",request));assertEquals("failed",failed.path("state").asText(),failed.toString());
                assertEquals(1,f.grids.status("human",id).path("revision").asInt());assertFalse(f.grids.status("human",id).path("busy").asBoolean());
            }
            var gate=new java.util.concurrent.CountDownLatch(1);var entered=new java.util.concurrent.CountDownLatch(2);
            var blockers=new ArrayList<ObjectNode>();
            try{
                for(int i=0;i<2;i++)blockers.add(f.jobs.local("human",job->{entered.countDown();gate.await();return Profiles.JSON.createObjectNode();},()->{}));
                assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));
                ObjectNode queued=f.grids.operation("human",id,"values",f.input(grid,"c1"));
                f.jobs.cancel(f.jobs.require("human",queued.path("id").asText()));gate.countDown();
                JsonNode cancelled=HumanSqlTest.finish(f.jobs,"human",queued);assertEquals("cancelled",cancelled.path("state").asText(),cancelled.toString());
                assertFalse(f.grids.status("human",id).path("busy").asBoolean());assertFalse(f.grids.status("human",id).path("uncertain").asBoolean());
            }finally{gate.countDown();for(ObjectNode job:blockers)HumanSqlTest.finish(f.jobs,"human",job);}
            ObjectNode replacement=new DbaTest().input().put("name","changed");f.profiles.put(f.id,replacement);
            assertThrows(IllegalArgumentException.class,()->f.grids.operation("human",id,"values",f.input(grid,"c1")));
        }
    }
    private ObjectNode filter(String sql,int index,String column,int type,String... values){
        ObjectNode input=Profiles.JSON.createObjectNode().put("sql",sql).put("action","filter_values").put("columnId","c"+(index+1)).put("columnIndex",index).put("columnLabel",column).put("jdbcType",type);
        input.putArray("parameters");var picked=input.putArray("values");for(String value:values)if(value==null)picked.addNull();else picked.add(value);return input;
    }
    @Test void clearingSelectionsRemovesOnlyTheRequestedPickerPredicate(){
        ObjectNode input=filter("SELECT ID,NAME FROM ITEMS WHERE NAME <> ? ORDER BY ID DESC LIMIT 17",0,"ID",Types.INTEGER,"1",null);
        input.putArray("parameters").add("authored");
        ObjectNode first=GridSql.prepare(input),other=first.deepCopy();
        other.setAll(filter(first.path("sql").asText(),1,"NAME",Types.VARCHAR,"shown",""));other.set("parameters",first.path("parameters"));
        ObjectNode both=GridSql.prepare(other),clearName=both.deepCopy().put("action","filter_values").put("columnId","c2");clearName.putArray("values");
        ObjectNode remaining=GridSql.prepare(clearName);
        assertEquals(first.path("sql"),remaining.path("sql"));assertEquals(first.path("parameters"),remaining.path("parameters"));
        assertEquals(1,remaining.path("valueFilters").size());assertEquals("c1",remaining.path("valueFilters").get(0).path("columnId").asText());
        ObjectNode clearId=remaining.deepCopy().put("action","filter_values").put("columnId","c1");clearId.putArray("values");
        ObjectNode cleared=GridSql.prepare(clearId);
        assertEquals(input.path("sql"),cleared.path("sql"));assertEquals(input.path("parameters"),cleared.path("parameters"));assertTrue(cleared.path("valueFilters").isEmpty());
        ObjectNode again=cleared.deepCopy().put("action","filter_values").put("columnId","c1");again.putArray("values");assertThrows(IllegalArgumentException.class,()->GridSql.prepare(again));
        ObjectNode missing=both.deepCopy().put("action","filter_values").put("columnId","c3");missing.putArray("values");assertThrows(IllegalArgumentException.class,()->GridSql.prepare(missing));
        assertEquals(2,both.path("valueFilters").size());assertEquals(4,both.path("parameters").size());
    }
    @Test void pickerReplacesOnlyItsOwnPredicatesAndPreservesTypedBindings()throws Exception{
        ObjectNode input=filter("SELECT ID,NAME FROM ITEMS WHERE ID > ? ORDER BY NAME",0,"ID",Types.BIGINT,"9007199254740993",null);
        input.putArray("parameters").add(1);
        ObjectNode first=GridSql.prepare(input);assertTrue(first.path("sql").asText().contains("IN (CAST(? AS DECIMAL"),first.toString());
        assertEquals("9007199254740993",first.path("parameters").get(1).asText());
        ObjectNode second=first.deepCopy();second.setAll(filter(first.path("sql").asText(),0,"ID",Types.BIGINT,"2","3"));second.set("parameters",first.path("parameters"));
        second=GridSql.prepare(second);assertEquals("[1,\"2\",\"3\"]",second.path("parameters").toString());assertFalse(second.path("sql").asText().contains("IS NULL"));
        ObjectNode other=second.deepCopy();other.setAll(filter(second.path("sql").asText(),1,"NAME",Types.VARCHAR,"x' OR 1=1 --",""));other.set("parameters",second.path("parameters"));
        ObjectNode third=GridSql.prepare(other);assertEquals(5,third.path("parameters").size());assertFalse(third.path("sql").asText().contains("x'"));assertTrue(third.path("sql").asText().endsWith("ORDER BY NAME"));
        ObjectNode refresh=third.deepCopy().put("action","refresh");assertEquals(third.path("sql"),GridSql.prepare(refresh).path("sql"));
        ObjectNode ordered=third.deepCopy().put("action","order").put("columnIndex",0).put("columnLabel","ID").put("direction","DESC");assertEquals(5,GridSql.prepare(ordered).path("parameters").size());
        ObjectNode cleared=third.deepCopy().put("action","clear_filters");assertTrue(GridSql.prepare(cleared).path("valueFilters").isEmpty());
        ObjectNode empty=input.deepCopy();empty.putArray("values");assertThrows(IllegalArgumentException.class,()->GridSql.prepare(empty));
        ObjectNode excessive=input.deepCopy();var values=excessive.putArray("values");for(int i=0;i<128;i++)values.add(i);assertThrows(IllegalArgumentException.class,()->GridSql.prepare(excessive));
        for(int type:new int[]{Types.BIGINT,Types.DECIMAL})assertThrows(IllegalArgumentException.class,()->GridSql.prepare(filter("SELECT ID FROM ITEMS",0,"ID",type,"1e999999999")));
        ObjectNode nullOnly=GridSql.prepare(filter("SELECT ID FROM ITEMS",0,"ID",Types.INTEGER,(String)null));assertTrue(nullOnly.path("sql").asText().endsWith("ID IS NULL"));assertTrue(nullOnly.path("parameters").isEmpty());
    }
}
