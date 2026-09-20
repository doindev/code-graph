package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Structural native-command review. Classifications are not authorization tokens. */
final class NativeCommand {
    enum Effect { READ, WRITE, DESTRUCTIVE, ADMINISTRATION }
    record Classification(String category, Effect effect, boolean reusableRead, String reason) {
        ObjectNode json() {
            return Profiles.JSON.createObjectNode().put("category", category)
                    .put("effect", effect.name().toLowerCase(Locale.ROOT))
                    .put("readOnly", effect == Effect.READ).put("reusableRead", reusableRead)
                    .put("reason", reason);
        }
    }

    private static final Set<String> SAFE_MONGO_EXPRESSIONS = Set.of(
            "$and", "$or", "$nor", "$not", "$eq", "$ne", "$gt", "$gte", "$lt", "$lte",
            "$in", "$nin", "$exists", "$type", "$regex", "$options", "$all", "$elemMatch", "$size",
            "$expr", "$literal", "$add", "$subtract", "$multiply", "$divide", "$mod", "$abs",
            "$ceil", "$floor", "$round", "$sum", "$avg", "$min", "$max", "$first", "$last",
            "$push", "$addToSet", "$count", "$cond", "$ifNull", "$switch", "$let", "$map", "$filter",
            "$reduce", "$concat", "$concatArrays", "$arrayElemAt", "$isArray", "$objectToArray",
            "$arrayToObject", "$toString", "$toInt", "$toLong", "$toDouble", "$toDecimal", "$toDate",
            "$convert", "$dateToString", "$dateFromString", "$year", "$month", "$dayOfMonth",
            "$sortArray", "$slice", "$setUnion", "$setIntersection", "$setDifference", "$mergeObjects",
            "$oid", "$date", "$numberLong", "$numberInt", "$numberDecimal", "$numberDouble",
            "$binary", "$timestamp", "$regularExpression", "$minKey", "$maxKey", "$uuid");
    private static final Set<String> READ_STAGES = Set.of("$match", "$project", "$sort", "$limit", "$skip",
            "$group", "$unwind", "$addFields", "$set", "$unset", "$replaceRoot", "$replaceWith",
            "$count", "$sortByCount", "$sample", "$bucket", "$bucketAuto");
    private static final Set<String> MONGO_META = Set.of("listCollections", "listIndexes", "collStats", "dbStats");
    private static final Set<String> REDIS_READ = Set.of("GET", "GETRANGE", "STRLEN", "MGET", "TYPE", "EXISTS",
            "TTL", "PTTL", "EXPIRETIME", "PEXPIRETIME", "HGET", "HMGET", "HGETALL", "HLEN", "HEXISTS",
            "HKEYS", "HVALS", "HSCAN", "LRANGE", "LINDEX", "LLEN", "SMEMBERS", "SSCAN", "SCARD",
            "SISMEMBER", "SMISMEMBER", "ZRANGE", "ZREVRANGE", "ZRANGEBYSCORE", "ZCARD", "ZSCORE",
            "ZMSCORE", "ZRANK", "ZREVRANK", "ZCOUNT", "ZSCAN", "XRANGE", "XREVRANGE", "XLEN",
            "GETBIT", "BITCOUNT", "BITPOS", "GEOPOS", "GEODIST", "GEOSEARCH", "SCAN", "DBSIZE", "PING");
    private static final Set<String> REDIS_WRITE = Set.of("SET", "MSET", "SETNX", "MSETNX", "APPEND",
            "INCR", "INCRBY", "INCRBYFLOAT", "DECR", "DECRBY", "SETRANGE", "HSET", "HSETNX", "HMSET",
            "HINCRBY", "HINCRBYFLOAT", "LPUSH", "RPUSH", "LPUSHX", "RPUSHX", "LSET", "LINSERT",
            "SADD", "ZADD", "ZINCRBY", "XADD", "SETBIT", "BITFIELD", "PFADD", "PFMERGE", "GEOADD",
            "PUBLISH", "SPUBLISH", "XACK", "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST");
    private static final Set<String> REDIS_DESTRUCTIVE = Set.of("DEL", "UNLINK", "GETDEL", "RENAME", "RENAMENX",
            "HDEL", "LPOP", "RPOP", "BLPOP", "BRPOP", "LMOVE", "BLMOVE", "LTRIM", "LREM", "SREM",
            "SPOP", "SMOVE", "ZREM", "ZPOPMIN", "ZPOPMAX", "BZPOPMIN", "BZPOPMAX", "XDEL", "XTRIM",
            "FLUSHDB", "FLUSHALL", "RESTORE", "MIGRATE");

    static Classification classify(NativeTarget target, JsonNode command) {
        bound(command);
        return switch (target.transport()) {
            case MONGODB -> command.has("transaction") ? NativeMongoTransactions.classify(target,command) : mongo(target, command);
            case REDIS -> command.has("pipeline") ? NativeRedisPipelines.classify(target,command) : command.isObject() ? NativeRedisTransactions.classify(target,command) : NativeRedisValues.handles(command) ? NativeRedisValues.classify(target,command) : redis(command);
            default -> throw new IllegalArgumentException("Use SQL tools for JDBC connections");
        };
    }

    private static Classification mongo(NativeTarget target, JsonNode command) {
        if (!command.isObject() || command.isEmpty()) throw new IllegalArgumentException("MongoDB command must be a nonempty Extended JSON object");
        String name = command.fieldNames().next();
        if(name.equals("watch"))return NativeMongoStreams.classify(target,command);
        if (command.has("$db") || command.has("lsid") || command.has("txnNumber") || command.has("autocommit")
                || command.has("startTransaction") || command.has("$clusterTime") || command.has("apiStrict")) {
            throw new IllegalArgumentException("Database and session context are managed by the application");
        }
        if (Set.of("find", "aggregate", "insert", "update", "delete", "findAndModify", "listIndexes",
                "createIndexes", "dropIndexes", "collStats", "count", "distinct", "drop", "create", "collMod").contains(name)) {
            if (target.collection().isEmpty() || !command.path(name).isTextual()
                    || !command.path(name).asText().equals(target.collection())) {
                throw new IllegalArgumentException("Command collection must exactly match the selected collection");
            }
        }
        if (name.equals("renameCollection")) {
            MongoCollectionRename.validate(target,command);
            return new Classification("mongo.renameCollection",Effect.DESTRUCTIVE,false,"Exact same-database rename; locks and invalidates cursors, never replaces the destination");
        }
        if(MongoCollectionSettings.handles(command)){
            MongoCollectionSettings.validate(target,command);
            return new Classification("mongo.collMod",MongoCollectionSettings.destructive(command)?Effect.DESTRUCTIVE:Effect.WRITE,false,
                    "Exact collection-setting review; retention/capped changes may permanently remove data");
        }
        if (name.equals("find")) {
            fields(command, Set.of("find", "filter", "projection", "sort", "skip", "limit", "hint", "collation", "comment", "maxTimeMS", "batchSize"));
            return read("mongo.find", safeExpressions(command), "Unrecognized or executable expressions require exact one-time review");
        }
        if (name.equals("aggregate")) {
            fields(command, Set.of("aggregate", "pipeline", "cursor", "allowDiskUse", "hint", "collation", "comment", "maxTimeMS"));
            if (!command.path("pipeline").isArray() || command.path("pipeline").size() > 64) throw new IllegalArgumentException("Pipeline must be an array of at most 64 stages");
            boolean safe = true, writes = false;
            for (JsonNode stage : command.path("pipeline")) {
                if (!stage.isObject() || stage.size() != 1) throw new IllegalArgumentException("Each pipeline stage must have one operator");
                String key = stage.fieldNames().next();
                if (Set.of("$out", "$merge").contains(key)) writes = true;
                if (!READ_STAGES.contains(key)) safe = false;
                else if (!safeExpressions(stage.path(key))) safe = false;
            }
            if(writes)return new Classification("mongo.aggregate_write", Effect.WRITE, false, "Pipeline writes may target another collection/database; validate all namespaces before execution");
            return read("mongo.aggregate", safe, "Joins, nested/unknown pipelines and executable expressions require explicit review and namespace validation");
        }
        if (name.equals("explain")) {
            fields(command, Set.of("explain", "verbosity", "comment", "maxTimeMS"));
            String verbosity = command.path("verbosity").asText("queryPlanner");
            if (!Set.of("queryPlanner", "executionStats", "allPlansExecution").contains(verbosity)) throw new IllegalArgumentException("Unsupported Explain verbosity");
            Classification nested = mongo(target, command.path("explain"));
            if (!Set.of("mongo.find", "mongo.aggregate").contains(nested.category())) throw new IllegalArgumentException("Explain requires a find or aggregation command");
            return read("mongo.explain", verbosity.equals("queryPlanner") && nested.reusableRead(), "Execution statistics execute work; unknown nested operations require exact review");
        }
        if(name.equals("listCollections")){
            fields(command,Set.of("listCollections","filter"));
            if(!command.path(name).isIntegralNumber()||command.path(name).asInt()!=1)throw new IllegalArgumentException("listCollections must be 1");
            return read("mongo.metadata",safeExpressions(command),"Only validated metadata filters are permitted");
        }
        if(name.equals("listIndexes")){fields(command,Set.of("listIndexes"));return read("mongo.metadata",true,"Bounded collection-index metadata");}
        if (MONGO_META.contains(name)) return read("mongo.metadata", false, "Metadata options and server version require verified capability checks");
        if (Set.of("insert", "update", "findAndModify", "create", "createIndexes", "collMod").contains(name)) {
            return new Classification("mongo." + name, Effect.WRITE, false, "Exact operation review required; writes are not reusable reads");
        }
        if (Set.of("delete", "drop", "dropDatabase", "dropIndexes", "renameCollection").contains(name)) {
            return new Classification("mongo." + name, Effect.DESTRUCTIVE, false, "Destructive operation; verify every affected namespace");
        }
        return new Classification("mongo.administration", Effect.ADMINISTRATION, false, "Unknown/admin commands require a verified operation adapter and exact review");
    }

    private static Classification redis(JsonNode command) {
        if (!command.isArray() || command.isEmpty() || command.size() > 1024) throw new IllegalArgumentException("Redis command must be an array of 1..1024 arguments");
        if(NativeRedisStreams.handles(command))return NativeRedisStreams.classify(command);
        NativeRedisArguments.validate(command);
        String name = NativeRedisArguments.text(command,0).toUpperCase(Locale.ROOT);
        if (!name.matches("[A-Z][A-Z0-9_.]{0,63}")) throw new IllegalArgumentException("Invalid Redis command name");
        if (Set.of("AUTH", "HELLO", "SELECT", "QUIT", "RESET", "READONLY", "READWRITE", "ASKING", "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH").contains(name)) {
            throw new IllegalArgumentException("Connection/session commands require the dedicated managed workflow");
        }
        if (REDIS_READ.contains(name)) return read("redis.read", true, "Bounded command adapter and exact key scope still required");
        if (REDIS_WRITE.contains(name)) return new Classification("redis.write", Effect.WRITE, false, "Native mutation; exact key and argument review required");
        if (REDIS_DESTRUCTIVE.contains(name)) return new Classification("redis.destructive", Effect.DESTRUCTIVE, false, "May destroy/consume values or affect the entire database");
        // Server command flags alone cannot establish key scope, reply bounds or module safety.
        return new Classification("redis.administration", Effect.ADMINISTRATION, false, "Scripts, modules and unknown/admin commands require verified adapters and exact review");
    }

    private static Classification read(String category, boolean verified, String reason) {
        // An unverified expression must not inherit a read policy, including broad environment policies.
        return new Classification(category, verified ? Effect.READ : Effect.ADMINISTRATION, verified, reason);
    }

    private static boolean safeExpressions(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (field.getKey().startsWith("$") && !SAFE_MONGO_EXPRESSIONS.contains(field.getKey())) return false;
                if (!safeExpressions(field.getValue())) return false;
            }
        } else if (node.isArray()) for (JsonNode child : node) if (!safeExpressions(child)) return false;
        return true;
    }

    private static void fields(JsonNode value, Set<String> permitted) {
        value.fieldNames().forEachRemaining(key -> { if (!permitted.contains(key)) throw new IllegalArgumentException("Unsupported MongoDB command option: " + key); });
    }

    static void bound(JsonNode input) {
        if (input == null) throw new IllegalArgumentException("Command is required");
        var nodes = new ArrayDeque<Map.Entry<JsonNode, Integer>>();
        nodes.add(Map.entry(input, 0));
        int count = 0;
        long bytes = 0;
        while (!nodes.isEmpty()) {
            var item = nodes.removeLast();
            if (++count > 16_384 || item.getValue() > 32) throw new IllegalArgumentException("Native command exceeds structural limits");
            JsonNode node = item.getKey();
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var field = fields.next();
                    if (field.getKey().indexOf('\0') >= 0 || field.getKey().length() > 1024) throw new IllegalArgumentException("Invalid command field name");
                    bytes += field.getKey().getBytes(StandardCharsets.UTF_8).length;
                    nodes.add(Map.entry(field.getValue(), item.getValue() + 1));
                }
            } else if (node.isArray()) {
                for (JsonNode child : node) nodes.add(Map.entry(child, item.getValue() + 1));
            } else {
                String text = node.asText();
                if (text.length() > 131072) throw new IllegalArgumentException("Native value exceeds request allowance");
                bytes += text.getBytes(StandardCharsets.UTF_8).length;
            }
            if (bytes > 131072 || nodes.size() > 16384) throw new IllegalArgumentException("Native command exceeds request allowance");
        }
    }

    private NativeCommand() { }
}
