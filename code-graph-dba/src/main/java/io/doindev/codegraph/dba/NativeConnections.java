package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.*;
import com.mongodb.client.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.resource.DefaultClientResources;
import org.bson.Document;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native client ownership is separate from JDBC pools; clients are lazy and revision-bound. */
final class NativeConnections implements AutoCloseable {
    static final class Lease implements AutoCloseable {
        private final Runnable release;
        final MongoClient mongo;
        final RedisClient redis;
        final String revision;
        private boolean closed;
        Lease(MongoClient mongo, RedisClient redis, String revision, Runnable release) { this.mongo=mongo;this.redis=redis;this.revision=revision;this.release=release; }
        public synchronized void close() { if(!closed) { closed=true;release.run(); } }
    }
    private static final class Entry {
        final String revision;
        final MongoClient mongo;
        final RedisClient redis;
        final long idleMillis;
        int uses;
        long lastUse=System.currentTimeMillis();
        boolean retired;
        Entry(String revision,MongoClient mongo,RedisClient redis,long idleMillis) { this.revision=revision;this.mongo=mongo;this.redis=redis;this.idleMillis=idleMillis; }
        void close() { if(mongo!=null)mongo.close();if(redis!=null)redis.shutdown(Duration.ZERO,Duration.ofSeconds(2)); }
    }
    private final Profiles profiles;
    private final Map<String,Entry> clients=new HashMap<>();
    private final Set<Entry> retired=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> invalidated=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private DefaultClientResources redisResources;
    private boolean closed;

    NativeConnections(Profiles profiles) { this.profiles=profiles; }

    synchronized Lease acquire(String id) {
        if(closed)throw new IllegalStateException("Native connections are closed");
        reap();ObjectNode profile=profiles.get(id);
        String revision=ProjectContexts.profileRevision(profile);
        Entry entry=clients.get(id);
        if(entry!=null&&!entry.revision.equals(revision)) { retire(id,entry);entry=null; }
        if(entry==null) {
            if(clients.size()+retired.size()>=16)throw new IllegalArgumentException("Native client allowance full; wait for active jobs or disconnect idle profiles");
            Properties credentials=profiles.credentials(profile);
            try { entry=create(profile,credentials,revision);clients.put(id,entry); }
            finally { credentials.clear(); }
        }
        entry.uses++;Entry owned=entry;
        return new Lease(entry.mongo,entry.redis,entry.revision,()->release(owned));
    }

    private Entry create(JsonNode profile,Properties credentials,String revision) {
        validateSupported(profile);
        DatabaseTransport transport=DatabaseTransport.of(profile);
        JsonNode opts=profile.path("nativeOptions");long idle=opts.path("idleTimeoutMS").asLong(60000);
        if(transport==DatabaseTransport.MONGODB)return new Entry(revision,mongo(profile,credentials),null,idle);
        if(redisResources==null)redisResources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).nettyCustomizer(new NativeWireBudget()).build();
        RedisURI uri=redisUri(profile,credentials);
        RedisClient client=RedisClient.create(redisResources,uri);
        client.setOptions(ClientOptions.builder().autoReconnect(false).requestQueueSize(32)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build());
        return new Entry(revision,null,client,idle);
    }

    static void validateSupported(JsonNode profile) {
        DatabaseTransport transport=DatabaseTransport.of(profile);
        if(transport==DatabaseTransport.JDBC)throw new IllegalArgumentException("Native profile required");
        JsonNode opts=profile.path("nativeOptions");
        if(transport==DatabaseTransport.REDIS&&!opts.path("topology").asText("standalone").equals("standalone"))throw new IllegalArgumentException("Redis Sentinel/Cluster client adapters are pending; no standalone fallback is permitted");
        if(opts.has("seeds"))throw new IllegalArgumentException("Explicit seed-list adapter is pending; MongoDB can use a multi-host endpoint");
        if(opts.path("authMechanism").asText().equals("MONGODB-X509"))throw new IllegalArgumentException("X.509 requires a configured, verified client-certificate adapter");
        String url=profile.path("url").asText();
        if(url.startsWith("rediss:")&&opts.has("tls")&&!opts.path("tls").asBoolean())throw new IllegalArgumentException("rediss requires TLS; conflicting tls=false is not permitted");
        if(url.startsWith("mongodb+srv:")&&opts.has("tls")&&!opts.path("tls").asBoolean())throw new IllegalArgumentException("SRV TLS downgrade requires a separately reviewed adapter");
    }

    static MongoClient mongo(JsonNode profile,Properties credentials) {
        JsonNode opts=profile.path("nativeOptions");
        MongoClientSettings.Builder settings=MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(profile.path("url").asText()))
                .applicationName("code-graph").retryReads(false).retryWrites(false)
                .applyToConnectionPoolSettings(pool->pool.minSize(0).maxSize(opts.path("maximumPoolSize").asInt(2))
                        .maxConnecting(2).maxWaitTime(10000,TimeUnit.MILLISECONDS).maxConnectionIdleTime(opts.path("idleTimeoutMS").asLong(60000),TimeUnit.MILLISECONDS))
                .applyToSocketSettings(socket->socket.connectTimeout(opts.path("connectTimeoutMS").asInt(10000),TimeUnit.MILLISECONDS)
                        .readTimeout(opts.path("socketTimeoutMS").asInt(30000),TimeUnit.MILLISECONDS))
                .applyToClusterSettings(cluster->{
                    cluster.serverSelectionTimeout(opts.path("connectTimeoutMS").asLong(10000),TimeUnit.MILLISECONDS);
                    if(opts.has("replicaSet"))cluster.requiredReplicaSetName(opts.path("replicaSet").asText());
                    if(opts.path("topology").asText().equals("standalone"))cluster.mode(com.mongodb.connection.ClusterConnectionMode.SINGLE);
                });
        if(opts.has("tls"))settings.applyToSslSettings(ssl->ssl.enabled(opts.path("tls").asBoolean()).invalidHostNameAllowed(false));
        if(opts.has("readPreference"))settings.readPreference(ReadPreference.valueOf(opts.path("readPreference").asText()));
        String user=credentials.getProperty("user","");char[] password=credentials.getProperty("password","").toCharArray();
        try {
            if(!user.isEmpty()) {
                String source=opts.path("authDatabase").asText("admin");
                MongoCredential credential=opts.path("authMechanism").asText("SCRAM-SHA-256").equals("SCRAM-SHA-1")
                        ?MongoCredential.createScramSha1Credential(user,source,password):MongoCredential.createScramSha256Credential(user,source,password);
                settings.credential(credential);
            } else if(password.length>0)throw new IllegalArgumentException("MongoDB password requires a username");
            return MongoClients.create(settings.build());
        } finally { Arrays.fill(password,'\0'); }
    }

    static RedisURI redisUri(JsonNode profile,Properties credentials) {
        JsonNode opts=profile.path("nativeOptions");
        RedisURI uri=RedisURI.create(profile.path("url").asText());
        if(opts.has("tls"))uri.setSsl(opts.path("tls").asBoolean());
        uri.setVerifyPeer(true);uri.setDatabase(Integer.parseInt(opts.path("database").asText("0")));
        uri.setTimeout(Duration.ofMillis(opts.path("socketTimeoutMS").asLong(30000)));
        String user=credentials.getProperty("user","");char[] password=credentials.getProperty("password","").toCharArray();
        try { if(!user.isEmpty())uri.setAuthentication(user,password);else if(password.length>0)uri.setAuthentication(password); }
        finally {Arrays.fill(password,'\0');}
        return uri;
    }

    static ObjectNode testDraft(ConnectionDraft draft) {
        validateSupported(draft.profile());Properties credentials=draft.properties();
        try {
            if(DatabaseTransport.of(draft.profile())==DatabaseTransport.MONGODB)try(MongoClient client=mongo(draft.profile(),credentials)) {
                Document info=client.getDatabase("admin").runCommand(new Document("buildInfo",1));
                return Profiles.JSON.createObjectNode().put("connected",true).put("database","MongoDB")
                        .put("version",info.getString("version")).put("versionQuery","{ buildInfo: 1 }");
            }
            // Draft connections never enter saved-profile pools, and their resources are closed.
            var resources=DefaultClientResources.builder().ioThreadPoolSize(2).computationThreadPoolSize(2).nettyCustomizer(new NativeWireBudget()).build();
            try {
                RedisClient client=RedisClient.create(resources,redisUri(draft.profile(),credentials));
                try(var connection=client.connect(ByteArrayCodec.INSTANCE)) {
                    String info=connection.sync().info("server");
                    String version=info.lines().filter(line->line.startsWith("redis_version:")).map(line->line.substring(14).strip()).findFirst().orElse("unknown");
                    return Profiles.JSON.createObjectNode().put("connected",true).put("database","Redis").put("version",version).put("versionQuery","INFO server");
                } finally { client.shutdown(Duration.ZERO,Duration.ofSeconds(2)); }
            } finally { resources.shutdown(0,2,TimeUnit.SECONDS).syncUninterruptibly(); }
        } finally { credentials.clear(); }
    }

    private synchronized void release(Entry entry) { entry.uses--;entry.lastUse=System.currentTimeMillis();if(entry.retired&&entry.uses==0){retired.remove(entry);entry.close();}shutdownResourcesIfIdle(); }
    synchronized void remove(String id) { Entry entry=clients.get(id);if(entry!=null)retire(id,entry); }
    // Called under the profile lock: never acquire the client lock in that callback.
    void invalidate(String id){invalidated.add(id);}
    private void retire(String id,Entry entry) { clients.remove(id);entry.retired=true;if(entry.uses==0)entry.close();else retired.add(entry); }
    synchronized void reap() {for(String id:List.copyOf(invalidated))if(invalidated.remove(id))remove(id);long now=System.currentTimeMillis();for(String id:List.copyOf(clients.keySet())){Entry entry=clients.get(id);if(entry.uses==0&&now-entry.lastUse>=entry.idleMillis)retire(id,entry);} }
    synchronized ObjectNode telemetry() { return Profiles.JSON.createObjectNode().put("clients",clients.size()).put("retiredPinnedClients",retired.size()).put("activeLeases",clients.values().stream().mapToInt(e->e.uses).sum()+retired.stream().mapToInt(e->e.uses).sum()).put("hardMemoryLimit",false).put("driverNativeOverhead","unaccounted; client count and pool/queue sizes are bounded"); }
    private void shutdownResourcesIfIdle(){if(closed&&clients.isEmpty()&&retired.isEmpty()&&redisResources!=null){redisResources.shutdown(0,2,TimeUnit.SECONDS);redisResources=null;}}
    public synchronized void close() { if(closed)return;closed=true;for(String id:List.copyOf(clients.keySet()))retire(id,clients.get(id));shutdownResourcesIfIdle(); }
}
