package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class HumanSqlTest {
    @TempDir Path directory;
    static JsonNode finish(QueryJobs jobs,String owner,JsonNode submitted)throws Exception{
        return finish(jobs,owner,submitted,state->"cancel");
    }
    static JsonNode finish(QueryJobs jobs,String owner,JsonNode submitted,Function<JsonNode,String> decision)throws Exception{
        String id=submitted.path("id").asText();long end=System.nanoTime()+15_000_000_000L;JsonNode result;
        String handled="";do{result=jobs.status(owner,id);if(result.path("finished").asLong()>0)break;if(result.path("state").asText().equals("awaiting_decision")&&!result.path("decision").path("id").asText().equals(handled)){handled=result.path("decision").path("id").asText();jobs.decision(owner,id,handled,decision.apply(result));}Thread.sleep(10);}while(System.nanoTime()<end);
        assertTrue(result.path("finished").asLong()>0,result.toString());jobs.remove(owner,id);return result;
    }
    static JsonNode run(QueryJobs jobs,String id,String sql,boolean auto)throws Exception{return finish(jobs,"human",jobs.humanQuery("human",id,sql,Profiles.JSON.createArrayNode(),auto));}
    @Test void humansCreateWriteReadAndDropWhileAgentsRemainRestricted()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,10,5,10),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var anchor=connections.open(id);var verify=anchor.createStatement()){
                anchor.setAutoCommit(true);
                assertEquals("complete",run(jobs,id,"CREATE TABLE myusers (id INTEGER PRIMARY KEY, name VARCHAR(50), signup_date DATE DEFAULT CURRENT_DATE)",false).path("state").asText());
                var insert=finish(jobs,"human",jobs.humanQuery("human",id,"INSERT INTO myusers (id,name) VALUES (?,?)",Profiles.JSON.createArrayNode().add(1).add("Jane"),false));
                assertEquals(1,insert.path("result").path("affectedRows").asInt());assertEquals("commit_acknowledged",insert.path("outcome").asText());
                try(var rs=verify.executeQuery("SELECT name FROM myusers")){assertTrue(rs.next());assertEquals("Jane",rs.getString(1));}
                assertEquals(1,run(jobs,id,"UPDATE myusers SET name='John'",false).path("result").path("affectedRows").asInt());
                assertEquals("John",run(jobs,id,"SELECT name FROM myusers",false).path("result").path("rows").get(0).get(0).asText());
                var bad=run(jobs,id,"CREATE TABLE bad_users (signup_date data)",false);assertEquals("cancelled",bad.path("state").asText());assertTrue(bad.path("result").path("errors").get(0).path("message").asText().contains("Unknown data type"),bad.toString());
                assertEquals(1,run(jobs,id,"DELETE FROM myusers",true).path("result").path("affectedRows").asInt());
                assertEquals("complete",run(jobs,id,"DROP TABLE myusers",false).path("state").asText());
                assertThrows(IllegalArgumentException.class,()->jobs.query("agent:restricted",id,"CREATE TABLE forbidden (id int)",Profiles.JSON.createArrayNode()));
                assertThrows(SecurityException.class,()->jobs.humanQuery("agent:restricted",id,"CREATE TABLE forbidden (id int)",Profiles.JSON.createArrayNode(),true));
                assertEquals(0,jobs.telemetry().path("retainedJobs").asInt());
            }
        }
    }
    @Test void statementProvenanceParametersAndErrorDecisionsAreBounded()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(directory,64L<<20,2,100,10,10),s->true)){
            String id=profiles.put(null,new DbaTest().input()).path("id").asText();
            try(var anchor=connections.open(id)){
            assertEquals("complete",run(jobs,id,"CREATE TABLE decisions (id INT PRIMARY KEY)",false).path("state").asText());
            var parameters=Profiles.JSON.createArrayNode().add(7).add(8);var two=finish(jobs,"human",jobs.humanQuery("human",id,"SELECT ? AS first_value; SELECT ? AS second_value",parameters,false));
            assertEquals(2,two.path("result").path("statements").size());assertEquals("SELECT ? AS first_value",two.path("result").path("statements").get(0).path("sql").asText());assertEquals(2,two.path("result").path("results").get(1).path("statementIndex").asInt());assertEquals("8",two.path("result").path("results").get(1).path("rows").get(0).get(0).asText());
            var continued=finish(jobs,"human",jobs.humanQuery("human",id,"INSERT INTO decisions VALUES (1); INSERT INTO decisions VALUES (1); INSERT INTO decisions VALUES (2); SELECT COUNT(*) FROM decisions",Profiles.JSON.createArrayNode(),false),state->"continue");
            assertEquals("complete",continued.path("state").asText(),continued.toString());assertTrue(continued.path("result").path("completedWithErrors").asBoolean(),continued.toString());assertEquals(1,continued.path("result").path("errors").size(),continued.toString());assertEquals(3,continued.path("result").path("results").size(),continued.toString());assertEquals("continue",continued.path("result").path("errors").get(0).path("resolution").asText());assertEquals("2",continued.path("result").path("results").get(2).path("rows").get(0).get(0).asText());
            var skipped=finish(jobs,"human",jobs.humanQuery("human",id,"INSERT INTO decisions VALUES (1); INSERT INTO decisions VALUES (1); SELECT COUNT(*) FROM decisions",Profiles.JSON.createArrayNode(),false),state->"skip_similar");
            assertEquals("complete",skipped.path("state").asText());assertEquals(2,skipped.path("result").path("errors").size());assertEquals("skip_similar",skipped.path("result").path("errors").get(1).path("resolution").asText());
            var cancelled=finish(jobs,"human",jobs.humanQuery("human",id,"SELECT 1; SELECT * FROM absent_table; SELECT 2",Profiles.JSON.createArrayNode(),false));assertEquals("cancelled",cancelled.path("state").asText());assertTrue(cancelled.path("result").path("interrupted").asBoolean());assertEquals(1,cancelled.path("result").path("results").size());
            var mismatch=finish(jobs,"human",jobs.humanQuery("human",id,"INSERT INTO decisions VALUES (?); INSERT INTO decisions VALUES (?)",Profiles.JSON.createArrayNode().add(3),false));assertEquals("failed",mismatch.path("state").asText());assertTrue(mismatch.path("error").asText().contains("nothing was executed"));
            }
        }
    }
    @Test void databaseMessagesRedactCredentialsAndStayBounded()throws Exception{
        try(var profiles=new Profiles(directory,new DbaTest.MemoryVault())){
            String id=profiles.put(null,new DbaTest().input().put("password","credential-secret-value")).path("id").asText();
            assertEquals("ERROR: type data does not exist",profiles.redactError(id,"ERROR: type data does not exist"));
            assertEquals("ERROR: [redacted]",profiles.redactError(id,"ERROR: credential-secret-value"));
            assertTrue(profiles.redactError(id,"x".repeat(10000)).length()<8300);
        }
    }
}
