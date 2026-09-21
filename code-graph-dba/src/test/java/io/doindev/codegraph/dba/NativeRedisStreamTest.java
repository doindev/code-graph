package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class NativeRedisStreamTest {
    @TempDir Path root;
    private static ArrayNode command(String... args){var out=Profiles.JSON.createArrayNode();for(String arg:args)out.add(arg);return out;}
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.UTF_8);}
    private static NativeTarget target(){return new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Streams","0","","standalone");}
    @Test void strictFiniteGrammarAndDeliveryClassification(){
        for(var input:List.of(command("XREAD","COUNT","2","STREAMS","stream","0-0"),command("XPENDING","s","g","-","+","100"))){
            assertTrue(NativeCommand.classify(target(),input).reusableRead());
        }
        for(var input:List.of(command("XREADGROUP","GROUP","g","c","COUNT","2","STREAMS","s","0-0"),
                command("XADD","s","*","field","value"),command("XCLAIM","s","g","c","0","1-0"),
                command("XAUTOCLAIM","s","g","c","0","0-0","COUNT","10"),command("XGROUP","CREATECONSUMER","s","g","c"))){
            var result=NativeCommand.classify(target(),input);assertEquals(NativeCommand.Effect.WRITE,result.effect());assertFalse(result.reusableRead());NativeMutations.validate(target(),input);
        }
        for(var input:List.of(command("XDEL","s","1-0"),command("XTRIM","s","MAXLEN","0"),command("XGROUP","DESTROY","s","g"),command("XGROUP","SETID","s","g","$"),command("XGROUP","DELCONSUMER","s","g","c")))assertEquals(NativeCommand.Effect.DESTRUCTIVE,NativeCommand.classify(target(),input).effect());
        for(var invalid:List.of(command("XREAD","STREAMS","s","0-0"),command("XREAD","COUNT","101","STREAMS","s","0-0"),
                command("XREAD","COUNT","1","BLOCK","10","STREAMS","s","0-0"),command("XREAD","COUNT","1","STREAMS","s","other","0-0","0-0"),
                command("XREADGROUP","GROUP","g","c","COUNT","1","NOACK","STREAMS","s",">"),command("XACK","s","g"),
                command("XPENDING","s","g"),command("XCLAIM","s","g","c","0","1-0","FORCE"),command("XGROUP","HELP","s","g"),
                command("XADD","s","*","field"),command("XADD","s","*","field","v","MAXLEN"),command("XTRIM","s","MAXLEN","~","10"),
                command("XGROUP","CREATE","s","g","0-0","MKSTREAM","ENTRIESREAD","2"),command("XACK","s","g","18446744073709551616-0"),
                command("XREAD","COUNT","1","STREAMS","","0-0"),command("XREAD","COUNT","1","STREAMS","s","0"))){
            assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target(),invalid),invalid.toString());
        }
        NativeRedisStreams.parse(command("XACK","s","g","18446744073709551615-18446744073709551615"));
        var batch=Profiles.JSON.createObjectNode();batch.putArray("transaction").add(command("XADD","s","*","f","v"));assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target(),batch));
    }
    @Test void binaryTargetsStayExactAndControlsNeverBecomeBinary(){
        var input=command("XADD","s","*","field","value");input.set(1,Profiles.JSON.createObjectNode().put("base64","AP8="));input.set(4,Profiles.JSON.createObjectNode().put("base64","/wA="));
        NativeRedisStreams.parse(input);assertEquals("AP8=",NativeRedisStreams.scope(input).path("key").path("base64").asText());
        input.set(2,Profiles.JSON.createObjectNode().put("base64","Kg=="));assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.parse(input));
        input.set(2,TextNode.valueOf("*"));input.set(4,TextNode.valueOf("x".repeat(65537)));assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.parse(input));
    }
    @Test void existingOnlyAppendGrammarAndTypedReceipts()throws Exception{
        var input=command("XADD","stream","NOMKSTREAM","*","","","same","a","same","b");
        assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target(),input).effect());
        assertTrue(NativeCommand.classify(target(),input).reason().contains("NOMKSTREAM"));
        assertFalse(NativeCommand.classify(target(),input).reusableRead());NativeMutations.validate(target(),input);
        input.set(4,Profiles.JSON.createObjectNode().put("base64","AP8="));input.set(5,Profiles.JSON.createObjectNode().put("base64","/wA="));NativeRedisStreams.parse(input);
        input.set(4,TextNode.valueOf("f".repeat(8193)));assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.parse(input));
        input.set(4,TextNode.valueOf("f"));input.set(5,TextNode.valueOf("v".repeat(65536)));NativeRedisStreams.parse(input);
        input.set(5,TextNode.valueOf("v".repeat(65537)));assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.parse(input));
        for(var invalid:List.of(command("XADD","s","NOMKSTREAM","*"),command("XADD","s","NOMKSTREAM","*","f"),
                command("XADD","s","NOMKSTREAM","MAXLEN","1","*","f","v"),command("XADD","s","NOMKSTREAM","IDMP","producer","id","*","f","v"))){
            assertThrows(IllegalArgumentException.class,()->NativeMutations.validate(target(),invalid));
        }
        var hundred=command("XADD","s","NOMKSTREAM","*");for(int i=0;i<100;i++)hundred.add("f").add("v");
        NativeRedisStreams.parse(hundred);hundred.add("f").add("v");assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.parse(hundred));
        var binaryControl=command("XADD","s","NOMKSTREAM","*","f","v");binaryControl.set(3,Profiles.JSON.createObjectNode().put("base64","Kg=="));assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.parse(binaryControl));
        var batch=Profiles.JSON.createObjectNode();batch.putArray("pipeline").add(command("XADD","s","NOMKSTREAM","*","f","v"));assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target(),batch));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,10),_->true)){
            var job=jobs.new Job("human","fixture");var existing=command("XADD","s","NOMKSTREAM","*","f","v");
            var spec=NativeRedisStreams.parse(existing);
            var missing=NativeRedisStreams.project(spec,Collections.singletonList(null),existing,job,8192);
            assertFalse(missing.path("applied").asBoolean());assertTrue(missing.path("value").isNull());assertTrue(missing.path("entryId").isNull());
            var appended=NativeRedisStreams.project(spec,List.of(bytes("18446744073709551615-18446744073709551615")),existing,job,8192);
            assertTrue(appended.path("applied").asBoolean());assertEquals(appended.path("value"),appended.path("entryId"));assertTrue(appended.path("existingStreamOnly").asBoolean());
            for(String invalid:List.of("OK","0-0","18446744073709551616-0"))assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.project(spec,List.of(bytes(invalid)),existing,job,8192));
            var ordinary=command("XADD","s","*","f","v");
            assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.project(NativeRedisStreams.parse(ordinary),Collections.singletonList(null),ordinary,job,8192));
            assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.project(spec,List.of(1L),existing,job,8192));
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Append").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            try(var operations=new NativeOperations(profiles,jobs)){
                var proposal=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Append").put("database","0").set("command",existing);
                var review=operations.prepareBrowser("owner",proposal);assertTrue(review.path("mutation").asBoolean());assertFalse(review.path("eligiblePersistentRead").asBoolean());assertEquals(1,operations.telemetry().path("retainedReviews").asInt());
                assertTrue(review.path("transactionNotice").asText().contains("NOMKSTREAM"));assertTrue(review.path("transactionNotice").asText().contains("recreation"));
                assertThrows(SecurityException.class,()->operations.applyBrowser("other",review.path("id").asText()));
                var changed=((ObjectNode)proposal).deepCopy();changed.withArray("command").set(3,TextNode.valueOf("7-0"));assertThrows(IllegalArgumentException.class,()->operations.validate(review,changed));
                operations.discardBrowser("owner",review.path("id").asText());assertEquals(0,operations.telemetry().path("retainedReviews").asInt());
                profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));assertThrows(IllegalArgumentException.class,()->operations.prepare(proposal));
            }
        }
    }
    @Test void boundedProjectionPreservesDeliveryIdsDuplicatesAndBinaryValues()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,10),_->true)){
            var input=command("XREADGROUP","GROUP","g","c","COUNT","2","STREAMS","s",">");var job=jobs.new Job("human","fixture");int reserve=job.byteLimit-24000;
            List<Object> messages=List.of(List.of(bytes("1-0"),List.of(bytes("same"),new byte[]{0,-1},bytes("same"),bytes("other"))),List.of(bytes("2-0"),List.of(bytes("huge"),bytes("x".repeat(20000)))));
            for(List<Object> response:List.<List<Object>>of(List.of(List.of(bytes("s"),messages)),List.of(bytes("s"),messages))){
                var result=NativeRedisStreams.project(NativeRedisStreams.parse(input),response,input,job,reserve);
                assertEquals(List.of("1-0","2-0"),Profiles.JSON.convertValue(result.path("deliveredIds"),List.class));assertTrue(result.path("truncated").asBoolean());assertFalse(result.path("automaticAcknowledgement").asBoolean());
                assertEquals(2,result.path("entries").get(0).path("fields").size());assertEquals("AP8=",result.path("entries").get(0).path("fields").get(0).path("value").path("base64").asText());
            }
            var missing=List.<Object>of(List.of(bytes("s"),List.of(Arrays.asList(bytes("3-0"),null))));
            assertTrue(NativeRedisStreams.project(NativeRedisStreams.parse(input),missing,input,job,reserve).path("entries").get(0).path("bodyMissing").asBoolean());
            assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.project(NativeRedisStreams.parse(input),List.of(List.of(bytes("other"),messages)),input,job,reserve));
        }
    }
    @Test void reviewOwnershipReadOnlyAndAdmissionRemainEnforced()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var operations=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Streams").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Streams").put("database","0");input.set("command",command("XREADGROUP","GROUP","g","c","COUNT","2","STREAMS","s",">"));
            var review=operations.prepareBrowser("owner",input);assertTrue(review.path("mutation").asBoolean());assertFalse(review.path("eligiblePersistentRead").asBoolean());assertEquals("g",review.path("streamScope").path("group").asText());
            assertThrows(SecurityException.class,()->operations.applyBrowser("other",review.path("id").asText()));
            var changed=input.deepCopy();changed.withArray("command").set(3,TextNode.valueOf("other"));assertThrows(IllegalArgumentException.class,()->operations.validate(review,changed));operations.discardBrowser("owner",review.path("id").asText());
            var limited=jobs.new Job("agent:fixture",profile.path("id").asText());limited.rowLimit=1;
            assertThrows(IllegalArgumentException.class,()->NativeRedisStreams.execute(null,target(),input.path("command"),limited,()->fail("Admission precedes authority/connection")));
            limited.rowLimit=100;limited.cancelled=true;assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisStreams.execute(null,target(),input.path("command"),limited,()->fail("Cancelled job cannot open a connection")));
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));assertThrows(IllegalArgumentException.class,()->operations.prepare(input));
        }
    }
    @Test @Timeout(120) void disposableStreamsPendingClaimsExplicitAcknowledgementsAndCleanup()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var connections=new Connections(profiles);var jobs=new QueryJobs(connections,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","Streams").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}}
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Streams").put("database",options.path("database").asText()));
            String key="{streams-"+UUID.randomUUID()+"}:messages";
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,10)){
                java.util.function.Function<ArrayNode,ObjectNode> run=input->NativeRedisStreams.execute(lease,target,input,jobs.new Job("human",target.connectionId()),()->{});
                try{
                    assertEquals("OK",run.apply(command("XGROUP","CREATE",key,"workers","0-0","MKSTREAM")).path("value").asText());
                    assertEquals("1",run.apply(command("XGROUP","CREATECONSUMER",key,"workers","one")).path("value").asText());
                    for(int i=1;i<=3;i++)assertEquals(i+"-0",run.apply(command("XADD",key,i+"-0","field","value"+i,"field","duplicate")).path("value").asText());
                    assertEquals(3,run.apply(command("XREAD","COUNT","3","STREAMS",key,"0-0")).path("rowCount").asInt());
                    assertEquals(0,run.apply(command("XPENDING",key,"workers","-","+","10")).path("rowCount").asInt());
                    var delivered=run.apply(command("XREADGROUP","GROUP","workers","one","COUNT","3","STREAMS",key,">"));assertEquals(3,delivered.path("deliveredIds").size());assertFalse(delivered.path("automaticAcknowledgement").asBoolean());assertEquals(2,delivered.path("entries").get(0).path("fields").size());
                    assertEquals(3,run.apply(command("XPENDING",key,"workers","-","+","10")).path("rowCount").asInt());
                    if(!topology.equals("cluster")){var other=new NativeTarget(DatabaseTransport.REDIS,target.connectionId(),target.connectionName(),"0","",topology);try(var isolated=NativeRedisSession.open(lease,other,10)){assertEquals(0,isolated.sync().exists(bytes(key)),"Stream operation must not use the default database");}}
                    run.apply(command("XREADGROUP","GROUP","workers","one","COUNT","1","STREAMS",key,"0-0"));assertEquals("2",run.apply(command("XPENDING",key,"workers","1-0","1-0","1")).path("entries").get(0).path("deliveryCount").asText());
                    assertEquals("1-0",run.apply(command("XCLAIM",key,"workers","two","0","1-0")).path("deliveredIds").get(0).asText());
                    run.apply(command("XDEL",key,"2-0"));var claimed=run.apply(command("XAUTOCLAIM",key,"workers","two","0","0-0","COUNT","10"));assertTrue(claimed.path("scanComplete").asBoolean());assertEquals("2-0",claimed.path("deletedPendingIds").get(0).asText());assertEquals(2,claimed.path("deliveredIds").size());
                    assertEquals("2",run.apply(command("XACK",key,"workers","1-0","3-0")).path("value").asText());assertEquals(0,run.apply(command("XPENDING",key,"workers","-","+","10")).path("rowCount").asInt());
                    assertEquals(0,run.apply(command("XREADGROUP","GROUP","workers","one","COUNT","1","STREAMS",key,">")).path("rowCount").asInt());
                    var cancel=jobs.new Job("human",target.connectionId());var checks=new AtomicInteger();assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisStreams.execute(lease,target,command("XADD",key,"4-0","f","v"),cancel,()->{if(checks.incrementAndGet()==2)cancel.cancelled=true;}));assertEquals("not_started",cancel.outcome);assertEquals(2,observer.sync().xlen(bytes(key)));
                    var revoked=jobs.new Job("human",target.connectionId());checks.set(0);assertThrows(SecurityException.class,()->NativeRedisStreams.execute(lease,target,command("XACK",key,"workers","1-0"),revoked,()->{if(checks.incrementAndGet()==2)throw new SecurityException("revoked");}));assertEquals("not_started",revoked.outcome);
                    var lateCancel=jobs.new Job("human",target.connectionId());checks.set(0);assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisStreams.execute(lease,target,command("XADD",key,"4-0","f","v"),lateCancel,()->{if(checks.incrementAndGet()==3)lateCancel.cancelled=true;}));assertEquals("acknowledged",lateCancel.outcome);assertEquals("4-0",lateCancel.result.path("value").asText());run.apply(command("XDEL",key,"4-0"));
                    var binary=command("XADD",key,"5-0","f","");binary.set(4,Profiles.JSON.createObjectNode().put("base64","AP8="));run.apply(binary);assertEquals("AP8=",run.apply(command("XREAD","COUNT","1","STREAMS",key,"4-0")).path("entries").get(0).path("fields").get(0).path("value").path("base64").asText());run.apply(command("XDEL",key,"5-0"));
                    String composing=key+":composer";
                    try{
                        var absent=run.apply(command("XADD",composing,"NOMKSTREAM","*","f","v"));
                        assertFalse(absent.path("applied").asBoolean());assertTrue(absent.path("entryId").isNull());assertEquals(0,observer.sync().exists(bytes(composing)));
                        run.apply(command("XADD",composing,"1-0","seed","kept"));observer.sync().pexpire(bytes(composing),60000);
                        var append=command("XADD",composing,"NOMKSTREAM","*","same","","same","last");append.set(5,Profiles.JSON.createObjectNode().put("base64","AP8="));
                        var appended=run.apply(append);assertTrue(appended.path("applied").asBoolean());assertTrue(appended.path("existingStreamOnly").asBoolean());
                        assertEquals(appended.path("value"),appended.path("entryId"));assertEquals(2,observer.sync().xlen(bytes(composing)));assertTrue(observer.sync().pttl(bytes(composing))>0);
                        var pairRow=run.apply(command("XREAD","COUNT","1","STREAMS",composing,"1-0")).path("entries").get(0);
                        assertEquals(2,pairRow.path("fields").size());assertEquals(pairRow.path("fields").get(0).path("field"),pairRow.path("fields").get(1).path("field"));
                        assertEquals("AP8=",pairRow.path("fields").get(0).path("value").path("base64").asText());
                        var empty=run.apply(command("XADD",composing,"NOMKSTREAM","*","",""));assertTrue(empty.path("applied").asBoolean());
                        var cancelled=jobs.new Job("human",target.connectionId());checks.set(0);
                        assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisStreams.execute(lease,target,append,cancelled,()->{if(checks.incrementAndGet()==2)cancelled.cancelled=true;}));
                        assertEquals(3,observer.sync().xlen(bytes(composing)));assertEquals("not_started",cancelled.outcome);
                        var denied=jobs.new Job("human",target.connectionId());assertThrows(SecurityException.class,()->NativeRedisStreams.execute(lease,target,append,denied,()->{throw new SecurityException("revoked");}));
                        assertEquals(3,observer.sync().xlen(bytes(composing)));
                        var afterDispatch=jobs.new Job("human",target.connectionId());checks.set(0);
                        assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisStreams.execute(lease,target,append,afterDispatch,()->{if(checks.incrementAndGet()==3)afterDispatch.cancelled=true;}));
                        assertEquals("acknowledged",afterDispatch.outcome);assertTrue(afterDispatch.result.path("applied").asBoolean());assertEquals(4,observer.sync().xlen(bytes(composing)));
                        observer.sync().pexpire(bytes(composing),0);assertFalse(run.apply(append).path("applied").asBoolean());assertEquals(0,observer.sync().exists(bytes(composing)));
                        observer.sync().set(bytes(composing),bytes("not a stream"));assertThrows(io.lettuce.core.RedisCommandExecutionException.class,()->run.apply(append));assertArrayEquals(bytes("not a stream"),observer.sync().get(bytes(composing)));
                    }finally{observer.sync().del(bytes(composing));}
                    var wrongType=jobs.new Job("human",target.connectionId());observer.sync().set(bytes(key+":wrong"),bytes("value"));assertThrows(io.lettuce.core.RedisCommandExecutionException.class,()->NativeRedisStreams.execute(lease,target,command("XADD",key+":wrong","*","f","v"),wrongType,()->{}));assertEquals("rejected",wrongType.outcome);
                    assertEquals("OK",run.apply(command("XGROUP","SETID",key,"workers","$")).path("value").asText());
                    assertEquals("0",run.apply(command("XGROUP","DELCONSUMER",key,"workers","one")).path("value").asText());
                    assertEquals("2",run.apply(command("XTRIM",key,"MAXLEN","0")).path("value").asText());assertEquals("1",run.apply(command("XGROUP","DESTROY",key,"workers")).path("value").asText());
                    // Deliver more payload than fits the agent response, without losing ACK identities.
                    String large=key+":large";run.apply(command("XGROUP","CREATE",large,"workers","0-0","MKSTREAM"));
                    for(int i=0;i<60;i++)observer.sync().xadd(bytes(large),Map.of(bytes("payload"),bytes("x".repeat(20000))));
                    var capped=jobs.new Job("agent:fixture",target.connectionId());var receipt=NativeRedisStreams.execute(lease,target,command("XREADGROUP","GROUP","workers","one","COUNT","60","STREAMS",large,">"),capped,()->{});
                    assertTrue(receipt.path("truncated").asBoolean());assertEquals(60,receipt.path("deliveredIds").size());assertTrue(receipt.path("rowCount").asInt()<60);assertTrue(capped.bytes<=1<<20);
                    assertEquals(60,run.apply(command("XPENDING",large,"workers","-","+","100")).path("rowCount").asInt(),"Truncated previews must not automatically acknowledge messages");
                }finally{observer.sync().del(bytes(key));observer.sync().del(bytes(key+":wrong"));observer.sync().del(bytes(key+":large"));}
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());
        }
    }
}
