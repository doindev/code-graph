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

class NativeRedisValueTest {
    @TempDir Path root;
    private static ArrayNode command(String... args){var c=Profiles.JSON.createArrayNode();for(String a:args)c.add(a);return c;}
    private static NativeTarget target(String topology){return new NativeTarget(DatabaseTransport.REDIS,UUID.randomUUID().toString(),"Values","0","",topology);}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    @Test void structuralBoundsAndEffects(){
        var target=target("standalone");
        for(String[] invalid:List.of(new String[]{"SETBIT","k","524288","1"},new String[]{"SETBIT","k","0","2"},
                new String[]{"BITCOUNT","k","0"},new String[]{"BITCOUNT","k","5","1"},new String[]{"BITCOUNT","k","0","1","garbage"},
                new String[]{"BITPOS","k","2"},new String[]{"BITFIELD","k","GET","u64","0"},new String[]{"BITFIELD","k","GET","i64","#8192"},
                new String[]{"BITFIELD_RO","k","SET","i8","0","1"},new String[]{"BITFIELD","k","OVERFLOW","FAIL","GET","u8","0"},
                new String[]{"PFMERGE","k"},new String[]{"PFCOUNT"},new String[]{"GEOADD","k","NaN","0","a"},new String[]{"GEOADD","k","0","86","a"},
                new String[]{"GEOADD","k","NX","XX","1","2","a"},new String[]{"GEOSEARCH","k","FROMMEMBER","a","BYRADIUS","1","km"},
                new String[]{"GEOSEARCH","k","FROMMEMBER","a","BYRADIUS","1","km","COUNT","101"},
                new String[]{"GEOSEARCH","k","FROMMEMBER","a","BYRADIUS","1","km","COUNT","1","ASC","DESC"},
                new String[]{"GEOSEARCHSTORE","dest","k","FROMMEMBER","a","BYRADIUS","1","km","COUNT","1","WITHCOORD"}))
            assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,command(invalid)),Arrays.toString(invalid));
        for(String[] read:List.of(new String[]{"BITCOUNT","k"},new String[]{"BITPOS","k","0"},new String[]{"BITFIELD_RO","k","GET","i64","0"},new String[]{"GEODIST","k","a","b"},new String[]{"GEOPOS","k","a"},new String[]{"GEOHASH","k","a"},new String[]{"GEOSEARCH","k","FROMLONLAT","0","0","BYBOX","1","2","km","COUNT","100","ANY","WITHHASH"}))
            assertEquals(NativeCommand.Effect.READ,NativeCommand.classify(target,command(read)).effect());
        assertEquals(NativeCommand.Effect.WRITE,NativeCommand.classify(target,command("PFCOUNT","k")).effect());
        var c=command("BITFIELD_RO","k");for(int i=0;i<32;i++)c.add("GET").add("i64").add("#"+i);NativeCommand.classify(target,c);c.add("GET").add("i64").add("#32");assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,c));
        for(String kind:List.of("pipeline","transaction")){var nested=Profiles.JSON.createObjectNode();nested.putArray(kind).add(command("SETBIT","k","0","1"));assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,nested));}
    }
    @Test void binaryScopePrecisionAndByteProjection(){
        var target=target("cluster");NativeCommand.classify(target,command("PFCOUNT","{a}:1","{a}:2"));
        for(String[] invalid:List.of(new String[]{"PFCOUNT","{a}:1","{b}:2"},new String[]{"PFMERGE","{a}:dest","{b}:source"},new String[]{"GEOSEARCHSTORE","{a}:dest","{b}:source","FROMMEMBER","a","BYRADIUS","1","km","COUNT","1"}))assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,command(invalid)));
        var binary=command("GEOADD","k","1","2");binary.addObject().put("base64","AP8=");NativeCommand.classify(target,binary);
        binary.set(2,Profiles.JSON.createObjectNode().put("base64","MQ=="));assertThrows(IllegalArgumentException.class,()->NativeCommand.classify(target,binary));
        var spec=NativeRedisValues.parse(target,command("BITFIELD_RO","k","GET","i64","0"));var out=NativeRedisValues.project(spec,List.of(Long.MAX_VALUE),100,16384,false);assertEquals("9223372036854775807",out.path("entries").get(0).path("value").path("$numberLong").asText());
        var geo=command("GEOPOS","k");var data=new ArrayList<Object>();for(int i=0;i<100;i++){geo.add("member"+i);data.add(List.of(new byte[8192],new byte[8192]));}
        var bounded=NativeRedisValues.project(NativeRedisValues.parse(target,geo),data,100,16384,true);assertTrue(bounded.path("truncated").asBoolean());assertTrue(bounded.toString().getBytes(StandardCharsets.UTF_8).length<16384);
    }
    @Test void reviewOwnershipReadOnlyAndHash()throws Exception{
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,10),_->true);var ops=new NativeOperations(profiles,jobs)){
            var profile=profiles.put(null,Profiles.JSON.createObjectNode().put("name","Values").put("templateId","redis-native").put("url","redis://localhost:1").put("readOnly",false));
            var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Values").put("database","0");input.set("command",command("PFCOUNT","k"));
            var review=ops.prepareBrowser("owner",input);assertTrue(review.path("mutation").asBoolean());assertFalse(review.path("eligiblePersistentRead").asBoolean());assertTrue(review.path("transactionNotice").asText().contains("cached cardinality"));
            assertThrows(SecurityException.class,()->ops.applyBrowser("other",review.path("id").asText()));input.set("command",command("PFCOUNT","different"));assertThrows(IllegalArgumentException.class,()->ops.validate(review,input));ops.discardBrowser("owner",review.path("id").asText());
            profiles.put(profile.path("id").asText(),Profiles.JSON.createObjectNode().put("readOnly",true));assertThrows(IllegalArgumentException.class,()->ops.prepare(input));input.set("command",command("GEOPOS","k","a"));assertFalse(ops.prepare(input).path("mutation").asBoolean());
            var limited=jobs.new Job("agent:fixture",profile.path("id").asText());limited.rowLimit=1;assertThrows(IllegalArgumentException.class,()->NativeRedisValues.execute(null,target("standalone"),command("GEOPOS","k","a","b"),limited,()->fail("No dispatch")));
            assertEquals(0,jobs.telemetry().path("reservedBytes").asLong());
        }
    }
    @Test @Timeout(120) void disposableValuesOnStandaloneSentinelAndCluster()throws Exception{
        String topology=Objects.toString(System.getenv("NATIVE_TEST_TOPOLOGY"),"standalone"),port=System.getenv("NATIVE_TEST_PORT"),owner=Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"");
        Assumptions.assumeTrue(port!=null&&(owner.startsWith("cgraph-native-redis-")||owner.startsWith("cgraph-topology-")),"Owned Redis fixture required");
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,15),_->true);var clients=new NativeConnections(profiles);var ops=new NativeOperations(profiles,jobs)){
            var draft=Profiles.JSON.createObjectNode().put("name","Values").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3");
            if(topology.equals("sentinel")){options.put("sentinelMaster","cgraph");String auth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");if(!auth.equals("none")){draft.put("username","data-worker").put("password","fixture-data-secret");draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");if(auth.equals("acl"))options.put("sentinelUsername","sentinel-worker");}}
            var profile=profiles.put(null,draft);var input=Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","Values").put("database",options.path("database").asText());var target=NativeTarget.resolve(profile,input);
            String key="{values-"+UUID.randomUUID()+"}",bits=key+":bits",hll=key+":hll",geo=key+":geo";
            try(var lease=clients.acquire(target.connectionId());var observer=NativeRedisSession.open(lease,target,15)){
                java.util.function.Function<ArrayNode,ObjectNode> run=c->{input.set("command",c);var review=ops.prepare(input);try{return (ObjectNode)ops.execute(jobs.new Job("human",target.connectionId()),review,input,()->{});}catch(Exception e){throw new RuntimeException(e);}};
                assertEquals(0,first(run.apply(command("SETBIT",bits,"7","1"))).asInt());assertEquals(1,first(run.apply(command("BITCOUNT",bits))).asInt());assertEquals(7,first(run.apply(command("BITPOS",bits,"1","0","7","BIT"))).asInt());
                assertEquals(1,first(run.apply(command("BITCOUNT",bits,"0","0","BYTE"))).asInt());
                assertEquals(1,first(NativeReadExecutor.execute(lease,target,command("BITCOUNT",bits),new NativeReadExecutor.Limits(100,16384,10),()->false)).asInt());
                assertThrows(IllegalArgumentException.class,()->NativeReadExecutor.execute(lease,target,command("PFCOUNT",hll),new NativeReadExecutor.Limits(100,16384,10),()->false));
                run.apply(command("BITFIELD",bits,"SET","i64","0",Long.toString(Long.MAX_VALUE)));
                assertEquals(Long.toString(Long.MAX_VALUE),first(run.apply(command("BITFIELD_RO",bits,"GET","i64","0"))).path("$numberLong").asText());
                var overflow=run.apply(command("BITFIELD",bits,"SET","u8","0","255","OVERFLOW","FAIL","INCRBY","u8","0","1","GET","u8","0"));assertTrue(overflow.path("entries").get(1).path("value").isNull());assertEquals(255,overflow.path("entries").get(2).path("value").asInt());
                run.apply(command("BITFIELD",bits,"OVERFLOW","SAT","INCRBY","u8","0","1"));assertEquals(255,first(run.apply(command("BITFIELD_RO",bits,"GET","u8","0"))).asInt());
                run.apply(command("BITFIELD",bits,"OVERFLOW","WRAP","INCRBY","u8","0","1"));assertEquals(0,first(run.apply(command("BITFIELD_RO",bits,"GET","u8","0"))).asInt());
                run.apply(command("PFADD",hll,"a","b","a"));assertEquals(2,first(run.apply(command("PFCOUNT",hll))).asInt());run.apply(command("PFADD",hll+"2","c"));run.apply(command("PFMERGE",hll+"3",hll,hll+"2"));assertEquals(3,first(run.apply(command("PFCOUNT",hll+"3"))).asInt());assertEquals(3,first(run.apply(command("PFCOUNT",hll,hll+"2"))).asInt());
                assertEquals(2,first(run.apply(command("GEOADD",geo,"NX","CH","13.361389","38.115556","Palermo","15.087269","37.502669","Catania"))).asInt());
                run.apply(command("GEOADD",geo,"XX","CH","13.361389","38.115556","Palermo"));assertFalse(first(run.apply(command("GEODIST",geo,"Palermo","Catania","km"))).path("text").asText().isBlank());
                var positions=run.apply(command("GEOPOS",geo,"Palermo","missing"));assertEquals(2,positions.path("entries").size());assertTrue(positions.path("entries").get(1).path("value").isNull());
                assertEquals(11,first(run.apply(command("GEOHASH",geo,"Palermo"))).path("text").asText().length());
                assertEquals(2,run.apply(command("GEOSEARCH",geo,"FROMMEMBER","Palermo","BYRADIUS","200","km","ASC","COUNT","10","WITHCOORD","WITHDIST","WITHHASH")).path("entries").size());
                assertEquals(2,run.apply(command("GEOSEARCH",geo,"FROMLONLAT","15","37","BYBOX","400","400","km","COUNT","2","ANY","DESC")).path("entries").size());
                assertEquals(2,first(run.apply(command("GEOSEARCHSTORE",geo+"2",geo,"FROMMEMBER","Palermo","BYRADIUS","200","km","COUNT","10","STOREDIST"))).asInt());
                var binary=command("GEOADD",geo,"1","2");binary.addObject().put("base64","AP8=");run.apply(binary);var search=run.apply(command("GEOSEARCH",geo,"FROMLONLAT","1","2","BYRADIUS","1","m","COUNT","1"));assertEquals("AP8=",first(search).path("base64").asText());
                run.apply(command("GEOADD",geo+"big","1","2","x".repeat(65536)));
                var preview=run.apply(command("GEOSEARCH",geo+"big","FROMLONLAT","1","2","BYRADIUS","1","m","COUNT","1"));assertTrue(preview.path("truncated").asBoolean());assertEquals(8192,first(preview).path("previewBytes").asInt());
                var before=new AtomicInteger();var cancelled=jobs.new Job("human",target.connectionId());assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisValues.execute(lease,target,command("SETBIT",bits,"0","1"),cancelled,()->{if(before.incrementAndGet()==2)cancelled.cancelled=true;}));assertEquals("not_started",cancelled.outcome);
                var late=jobs.new Job("human",target.connectionId());assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisValues.execute(lease,target,command("SETBIT",bits,"0","1"),late,()->{if(late.result!=null)late.cancelled=true;}));assertEquals("acknowledged",late.outcome);assertNotNull(late.result);
                var revoked=jobs.new Job("human",target.connectionId());assertThrows(SecurityException.class,()->NativeRedisValues.execute(lease,target,command("PFCOUNT",hll),revoked,()->{throw new SecurityException("revoked");}));assertEquals("not_started",revoked.outcome);
                if(topology.equals("standalone")){
                    var waiting=jobs.new Job("human",target.connectionId());var checks=new AtomicInteger();
                    assertThrows(java.util.concurrent.CancellationException.class,()->NativeRedisValues.execute(lease,target,command("SETBIT",bits,"1","1"),waiting,()->{int n=checks.incrementAndGet();if(n==2)observer.sync().clientPause(500);if(n==3)waiting.cancelled=true;}));
                    assertEquals("partial_or_unknown",waiting.outcome,"Cancellation cannot certify a dispatched write did not execute");
                }
                var rejected=jobs.new Job("human",target.connectionId());assertThrows(io.lettuce.core.RedisCommandExecutionException.class,()->NativeRedisValues.execute(lease,target,command("GEOADD",bits,"1","2","a"),rejected,()->{}));assertEquals("rejected",rejected.outcome);
                observer.sync().del(bytes(bits),bytes(hll),bytes(hll+"2"),bytes(hll+"3"),bytes(geo),bytes(geo+"2"),bytes(geo+"big"));
            }
            assertEquals(0,clients.telemetry().path("activeLeases").asInt());assertEquals(0,ops.telemetry().path("activeLeases").asInt());
        }
    }
    private static JsonNode first(JsonNode result){return result.path("entries").get(0).path("value");}
}
