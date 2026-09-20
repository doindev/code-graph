package io.doindev.codegraph.dba;

import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One operation owns its connection. Cluster scans are signed, topology-bound and bounded to 16 nodes. */
final class NativeRedisSession implements AutoCloseable {
    private final StatefulRedisConnection<byte[],byte[]> single;
    private final StatefulRedisClusterConnection<byte[],byte[]> cluster;
    private final List<String> nodes;
    private final String topology;
    private final byte[] secret;
    private final String database;
    private final boolean sentinel;
    private NativeRedisSession(StatefulRedisConnection<byte[],byte[]> single,StatefulRedisClusterConnection<byte[],byte[]> cluster,List<String> nodes,byte[] secret,String database,boolean sentinel){
        this.single=single;this.cluster=cluster;this.nodes=nodes;this.secret=secret;this.database=database;this.sentinel=sentinel;
        this.topology=CatalogScanner.hash(String.join(",",nodes)+(cluster==null?(sentinel?single.sync().info("server").lines().filter(line->line.startsWith("run_id:")).findFirst().orElseThrow(()->new IllegalArgumentException("Sentinel primary identity unavailable")):""):cluster.getPartitions().stream().map(n->n.getNodeId()+n.getSlots()).sorted().toList()));
    }
    static NativeRedisSession open(NativeConnections.Lease lease,NativeTarget target,int seconds){
        if(lease.cluster==null){
            var connection=lease.redis.connect(ByteArrayCodec.INSTANCE);
            try{connection.setTimeout(Duration.ofSeconds(seconds));connection.sync().select(Integer.parseInt(target.database()));return new NativeRedisSession(connection,null,List.of(),lease.cursorSecret,target.database(),target.topology().equals("sentinel"));}
            catch(RuntimeException failure){connection.close();throw failure;}
        }
        if(!target.database().equals("0"))throw new IllegalArgumentException("Redis Cluster supports database 0 only");
        // No background refresh/replay: a new operation may discover changed topology; uncertain writes are never retried.
        synchronized(lease.cluster){lease.cluster.reloadPartitions();if(lease.cluster.getPartitions().size()>16)throw new IllegalArgumentException("Redis topology exceeds the 16-node interactive allowance");}
        var connection=lease.cluster.connect(ByteArrayCodec.INSTANCE);
        try{
            connection.setTimeout(Duration.ofSeconds(seconds));
            var nodes=connection.getPartitions().stream().filter(n->n.is(RedisClusterNode.NodeFlag.UPSTREAM)).map(RedisClusterNode::getNodeId).sorted().toList();
            if(nodes.isEmpty()||nodes.size()>16)throw new IllegalArgumentException("Redis Cluster has no available primary nodes or exceeds the node allowance");
            // Establish bounded primary routes before any user command. REJECT_COMMANDS also rejects a first lazy connection.
            for(var node:connection.getPartitions())if(nodes.contains(node.getNodeId()))for(var intent:io.lettuce.core.protocol.ConnectionIntent.values()){
                connection.getConnection(node.getUri().getHost(),node.getUri().getPort(),intent).sync().ping();
            }
            return new NativeRedisSession(null,connection,nodes,lease.cursorSecret,target.database(),false);
        }catch(RuntimeException failure){connection.close();throw failure;}
    }
    RedisClusterCommands<byte[],byte[]> sync(){return cluster==null?single.sync():cluster.sync();}
    /** Pin MULTI/EXEC to one primary socket. Never use the cluster routing facade for a transaction. */
    StatefulRedisConnection<byte[],byte[]> transactionConnection(byte[] key){
        if(cluster==null)return single;
        var primary=cluster.getPartitions().getPartitionBySlot(io.lettuce.core.cluster.SlotHash.getSlot(key));
        if(primary==null||!primary.is(RedisClusterNode.NodeFlag.UPSTREAM))throw new IllegalArgumentException("Redis transaction primary is unavailable; refresh topology and review again");
        return cluster.getConnection(primary.getUri().getHost(),primary.getUri().getPort(),io.lettuce.core.protocol.ConnectionIntent.WRITE);
    }
    String serverInfo(){return cluster==null?single.sync().info("server"):cluster.sync().getConnection(nodes.getFirst()).info("server");}
    record Page(List<byte[]> keys,String cursor,boolean complete){}
    Page scan(String cursor,ScanArgs args){
        if(cluster==null){String position=sentinel&&!cursor.equals("0")?decode(cursor)[4]:cursor;if(!position.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid Redis scan cursor");var page=single.sync().scan(ScanCursor.of(position),args);return new Page(page.getKeys(),page.isFinished()?"0":sentinel?encode(0,page.getCursor()):page.getCursor(),page.isFinished());}
        int node=0;String position="0";
        if(!cursor.equals("0")){
            String[] parts=decode(cursor);node=Integer.parseInt(parts[3]);position=parts[4];
            if(node<0||node>=nodes.size()||!position.matches("[0-9]{1,20}"))throw new IllegalArgumentException("Invalid cluster scan position");
        }
        var page=cluster.sync().getConnection(nodes.get(node)).scan(ScanCursor.of(position),args);
        if(page.isFinished()){node++;position="0";}else position=page.getCursor();
        boolean done=node==nodes.size();return new Page(page.getKeys(),done?"0":encode(node,position),done);
    }
    private String encode(int node,String position){String value=(System.currentTimeMillis()+300000)+"|"+topology+"|"+database+"|"+node+"|"+position;String payload=Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));return "cg1."+payload+"."+sign(payload);}
    private String[] decode(String cursor){
        try{if(cursor.length()>1024)throw new IllegalArgumentException();String[] token=cursor.split("\\.");if(token.length!=3||!token[0].equals("cg1")||!java.security.MessageDigest.isEqual(sign(token[1]).getBytes(StandardCharsets.US_ASCII),token[2].getBytes(StandardCharsets.US_ASCII)))throw new IllegalArgumentException();
            String[] value=new String(Base64.getUrlDecoder().decode(token[1]),StandardCharsets.UTF_8).split("\\|");if(value.length!=5||Long.parseLong(value[0])<System.currentTimeMillis()||!value[1].equals(topology)||!value[2].equals(database))throw new IllegalArgumentException();return value;
        }catch(RuntimeException bad){throw new IllegalArgumentException("Native scan cursor expired or primary/topology/profile changed; restart SCAN at 0");}
    }
    private String sign(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException(impossible);}}
    public void close(){if(cluster!=null)cluster.close();if(single!=null)single.close();}
}
