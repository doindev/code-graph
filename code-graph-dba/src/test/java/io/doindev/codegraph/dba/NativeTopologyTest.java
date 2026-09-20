package io.doindev.codegraph.dba;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeTopologyTest {
    @TempDir Path root;
    @Test void configurationIsExplicitAndBounded(){
        var input=Profiles.JSON.createObjectNode().put("name","cluster").put("templateId","redis-native").put("url","redis://localhost:7000");
        var options=input.putObject("nativeOptions").put("topology","cluster").put("database","0");
        var profile=NativeProfile.create(input,Profiles.JSON.createObjectNode()).profile();NativeConnections.validateSupported(profile);
        options.put("database","1");assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
        options.put("database","0").put("topology","sentinel");assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
        options.put("sentinelMaster","mymaster");profile=NativeProfile.create(input,Profiles.JSON.createObjectNode()).profile();
        var uri=NativeConnections.redisUri(profile,new Properties());assertEquals("mymaster",uri.getSentinelMasterId());assertEquals(1,uri.getSentinels().size());assertNull(uri.getHost());
        options.putArray("seeds").add("rediss://localhost:26380");assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
        options.remove("seeds");options.put("topology","standalone");assertThrows(IllegalArgumentException.class,()->NativeProfile.create(input,Profiles.JSON.createObjectNode()));
    }
    @Test @Timeout(120) void disposableTopologyReadsScansAndRevisionIsolation()throws Exception{
        String topology=System.getenv("NATIVE_TEST_TOPOLOGY"),port=System.getenv("NATIVE_TEST_PORT");
        Assumptions.assumeTrue(Set.of("cluster","sentinel").contains(Objects.toString(topology,""))&&port!=null,"Owned Redis topology fixture not configured");
        assertTrue(Objects.toString(System.getenv("NATIVE_TEST_OWNER"),"").startsWith("cgraph-topology-"));
        try(var profiles=new Profiles(root,new DbaTest.MemoryVault());var clients=new NativeConnections(profiles)){
            var draft=Profiles.JSON.createObjectNode().put("name","topology").put("templateId","redis-native").put("url","redis://127.0.0.1:"+port).put("readOnly",false);
            var options=draft.putObject("nativeOptions").put("topology",topology).put("database",topology.equals("cluster")?"0":"3").put("connectTimeoutMS",3000).put("socketTimeoutMS",5000);
            if(topology.equals("sentinel"))options.put("sentinelMaster","cgraph");
            String sentinelAuth=Objects.toString(System.getenv("NATIVE_TEST_SENTINEL_AUTH"),"none");
            if(topology.equals("sentinel")&&!sentinelAuth.equals("none")){
                draft.put("username","data-worker").put("password","fixture-data-secret");
                draft.putObject("secretProperties").put("sentinelPassword","fixture-sentinel-secret");
                if(sentinelAuth.equals("acl"))options.put("sentinelUsername","sentinel-worker");
                for(String failure:List.of("missing-sentinel","wrong-sentinel","wrong-data")){
                    var bad=draft.deepCopy();
                    if(failure.equals("missing-sentinel"))bad.remove("secretProperties");
                    else if(failure.equals("wrong-sentinel"))bad.withObject("secretProperties").put("sentinelPassword","wrong-secret");
                    else bad.put("password","wrong-secret");
                    var invalid=NativeProfile.create(bad,Profiles.JSON.createObjectNode());
                    try{assertThrows(RuntimeException.class,()->NativeConnections.testDraft(invalid),failure);}finally{invalid.clear();}
                }
            }
            var tested=NativeProfile.create(draft,Profiles.JSON.createObjectNode());
            try{assertTrue(NativeConnections.testDraft(tested).path("connected").asBoolean());}finally{tested.clear();}
            if(topology.equals("sentinel")&&!sentinelAuth.equals("none")){
                var alternative=draft.deepCopy().put("url","redis://127.0.0.1:1");
                alternative.withObject("nativeOptions").putArray("seeds").add("redis://127.0.0.1:"+port);
                var withSeeds=NativeProfile.create(alternative,Profiles.JSON.createObjectNode());
                try{assertTrue(NativeConnections.testDraft(withSeeds).path("connected").asBoolean(),"Every additional Sentinel seed must authenticate independently");}finally{withSeeds.clear();}
            }
            assertTrue(profiles.list().isEmpty(),"Draft testing must not save a profile");
            var profile=profiles.put(null,draft);var target=NativeTarget.resolve(profile,Profiles.JSON.createObjectNode().put("connectionId",profile.path("id").asText()).put("connectionName","topology").put("database",options.path("database").asText()));
            String continuation="",primaryBefore="";
            try(var lease=clients.acquire(target.connectionId());var connection=NativeRedisSession.open(lease,target,5)){
                primaryBefore=connection.serverInfo().lines().filter(line->line.startsWith("run_id:")).findFirst().orElseThrow();
                for(int i=0;i<100;i++)connection.sync().set(("cg:topology:"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8),"value".getBytes());
                if(topology.equals("sentinel"))assertEquals(1L,connection.sync().waitForReplication(1,5000));
                var limits=new NativeReadExecutor.Limits(100,1<<20,5);
                assertTrue(NativeReadExecutor.execute(lease,target,Profiles.JSON.readTree("[\"GET\",\"cg:topology:4\"]"),limits,()->false).toString().contains("value"));
                Set<String> keys=new HashSet<>();String cursor="0";
                for(int page=0;page<200;page++){
                    var next=connection.scan(cursor,io.lettuce.core.ScanArgs.Builder.limit(4).match("cg:topology:*"));
                    for(byte[] key:next.keys())keys.add(new String(key,java.nio.charset.StandardCharsets.UTF_8));
                    if(next.complete())break;cursor=next.cursor();continuation=cursor;
                }
                assertEquals(100,keys.size());
                {
                    String token=continuation;assertTrue(token.startsWith("cg1."));
                    assertThrows(IllegalArgumentException.class,()->connection.scan(token+"x",io.lettuce.core.ScanArgs.Builder.limit(4)));
                    if(topology.equals("cluster")){var altered=new NativeTarget(target.transport(),target.connectionId(),target.connectionName(),"1","",topology);assertThrows(IllegalArgumentException.class,()->NativeRedisSession.open(lease,altered,5));}
                    try(var next=NativeRedisSession.open(lease,target,5)){assertNotNull(next.scan(token,io.lettuce.core.ScanArgs.Builder.limit(4)));}
                }
                var snapshot=NativeSchemaObservations.capture(lease,target,"snapshot",100,1<<20,10,0,()->false);assertFalse(snapshot.path("objects").isEmpty());assertFalse(snapshot.path("inventoryComplete").asBoolean());
            }
            if(topology.equals("sentinel")){
                try(var lease=clients.acquire(target.connectionId());var sentinel=lease.redis.connectSentinel()){
                    assertEquals("OK",sentinel.sync().failover("cgraph"));
                }
                boolean changed=false;long until=System.nanoTime()+45_000_000_000L;
                while(System.nanoTime()<until&&!changed){
                    try(var lease=clients.acquire(target.connectionId());var connection=NativeRedisSession.open(lease,target,2)){
                        String current=connection.serverInfo().lines().filter(line->line.startsWith("run_id:")).findFirst().orElseThrow();
                        if(!current.equals(primaryBefore)){String stale=continuation;assertThrows(IllegalArgumentException.class,()->connection.scan(stale,io.lettuce.core.ScanArgs.Builder.limit(4)));assertArrayEquals("value".getBytes(),connection.sync().get("cg:topology:4".getBytes()));changed=true;}
                    }catch(io.lettuce.core.RedisException recovering){/* bounded failover window; never retry writes */}
                    if(!changed)Thread.sleep(200);
                }
                assertTrue(changed,"A new operation must discover the promoted primary without accepting the old cursor");
            }else{
                try(var jdbc=new Connections(profiles);var jobs=new QueryJobs(jdbc,new DbaConfig(root,128L<<20,2,100,100,5),_->true);
                    var lease=clients.acquire(target.connectionId());var connection=NativeRedisSession.open(lease,target,5)){
                    var job=jobs.new Job("human",target.connectionId());
                    assertThrows(IllegalArgumentException.class,()->NativeMutations.execute(lease,target,Profiles.JSON.readTree("[\"DEL\",\"cg:topology:1\",\"cg:topology:2\"]"),job,()->{}));
                    assertEquals("not_started",job.outcome);
                    assertNotNull(connection.sync().get("cg:topology:1".getBytes()));assertNotNull(connection.sync().get("cg:topology:2".getBytes()));
                }
            }
            clients.remove(target.connectionId());
            try(var lease=clients.acquire(target.connectionId());var connection=NativeRedisSession.open(lease,target,5)){
                String stale=continuation;assertThrows(IllegalArgumentException.class,()->connection.scan(stale,io.lettuce.core.ScanArgs.Builder.limit(4)));
            }
        }
    }
}
