package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** Native transports have distinct identities; legacy JDBC recipes remain unchanged. */
final class NativeCatalog {
    static final Set<String> IDS=Set.of("mongodb-native","redis-native");
    static ArrayNode merge(ArrayNode jdbc) {
        List<ObjectNode> entries=new ArrayList<>();
        jdbc.forEach(value->{ObjectNode entry=value.deepCopy();entry.put("transport","jdbc");entries.add(entry);});
        entries.add(template("mongodb-native","MongoDB","mongodb","mongodb://localhost:27017","5.12.0"));
        entries.add(template("redis-native","Redis","redis","redis://localhost:6379","7.7.0.RELEASE"));
        entries.sort(Comparator.comparing((ObjectNode n)->n.path("id").asText().equals("custom"))
                .thenComparing(n->n.path("name").asText(),String.CASE_INSENSITIVE_ORDER));
        ArrayNode out=Profiles.JSON.createArrayNode();entries.forEach(out::add);return out;
    }
    private static ObjectNode template(String id,String name,String transport,String endpoint,String version) {
        ObjectNode result=Profiles.JSON.createObjectNode().put("id",id).put("name",name).put("transport",transport)
                .put("url",endpoint).put("icon","/dba/database.svg#"+transport).put("advancedMcp",false)
                .put("driverSource","bundled_native").put("clientVersion",version)
                .put("notes","Native protocol, not JDBC. Bounded reads and reviewed single-target CRUD are available. Bounded native catalogs/observations and explicit Mongo replica/sharded and Redis Sentinel/Cluster topologies are supported; infrastructure administration and transactions remain unavailable.");
        result.putArray("properties");
        result.putObject("capabilities").put("draftTest",true).put("boundedReads",true)
                .put("sql",false).put("writes",true).put("administration",false)
                .put("verification","Disposable standalone, Mongo replica/sharded, Redis Sentinel failover and Cluster read/scan fixtures tested; see native-databases.md for exact boundaries");
        return result;
    }
    static ObjectNode describe(com.fasterxml.jackson.databind.JsonNode profile,NativeTarget target,boolean approvalsEnabled){
        var result=Profiles.JSON.createObjectNode().put("state","complete").put("engine",target.transport().id)
                .put("configuredTemplate",profile.path("templateId").asText()).put("freshness","not_observed")
                .put("verificationStatus","configured_native_adapter").put("approvalChannelAvailable",approvalsEnabled);
        result.set("target",target.json());result.put("clientVersion",target.transport()==DatabaseTransport.MONGODB?"5.12.0":"7.7.0.RELEASE");
        var operations=result.putObject("operations");
        operations.putObject("boundedReads").put("available",true).put("requiresPermission",true);
        operations.putObject("reviewedMutations").put("available",approvalsEnabled&&!profile.path("readOnly").asBoolean(true)).put("requiresPermission",true)
                .put("restriction","Exact one-time approval or startup YOLO; no reusable native write policies; no automatic retry");
        for(String name:List.of("sql","migrationApplication","transactions","changeStreams","pubSub","administration"))
            operations.putObject(name).put("available",false).put("reason","No verified native workflow is enabled for this feature yet");
        for(String name:List.of("cachedCatalog","schemaCapture","schemaComparison","contractValidation"))operations.putObject(name).put("available",true).put("requiresPermission",true).put("restriction","Bounded native observations; samples are optional type-only evidence, never a complete schema");
        var commands=result.putArray("readCommands");
        if(target.transport()==DatabaseTransport.MONGODB)for(String name:List.of("find","aggregate (verified read stages)","explain (queryPlanner)","listCollections","listIndexes"))commands.add(name);
        else for(String name:List.of("GET (preview)","GETRANGE","TYPE","TTL","PTTL","STRLEN","EXISTS","EXPIRETIME","PEXPIRETIME","HLEN","LLEN","SCARD","ZCARD","XLEN","HEXISTS","SISMEMBER","ZSCORE","GETBIT","HGET","LINDEX","LRANGE","ZRANGE","ZREVRANGE","HSCAN","SSCAN","ZSCAN","XRANGE","XREVRANGE","SCAN","DBSIZE","PING"))commands.add(name);
        var writes=result.putArray("mutationCommands");
        if(target.transport()==DatabaseTransport.MONGODB)for(String name:List.of("insert","update (single-document entries)","delete (limit 1 entries)","create (collection or verified view)","collMod (validator or verified view definition)","createIndexes","drop","dropIndexes"))writes.add(name);
        else for(String name:List.of("SET","DEL","UNLINK","RENAME","RENAMENX","EXPIRE","PEXPIRE","PERSIST","HSET","HDEL","LPUSH","RPUSH","SADD","SREM","ZADD","ZREM"))writes.add(name);
        result.putArray("restrictions").add("Capabilities describe adapters, not authorization or observed server support")
                .add("Mongo documents above 256 KiB remain raw and are omitted with an explicit truncation notice")
                .add("Redis has an 8 MiB wire allowance per operation connection; COUNT is not a hard page limit")
                .add("Redis key/value arguments accept text or canonical base64 objects; binary values are capped at 64 KiB, keys/fields 8 KiB; controls remain text")
                .add("Native jobs reserve 64 MiB of shared DBA accounting; this is not a hard total-RAM cap")
                .add("Redis Sentinel/Cluster and Mongo replica/sharded/SRV endpoints are explicit; custom client TLS credentials and native environment policies remain incomplete");
        return result;
    }
    private NativeCatalog(){}
}
