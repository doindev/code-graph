package io.doindev.codegraph.dba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.*;

/** Native connection configuration; never accepts inline URI credentials or JDBC emulation. */
final class NativeProfile {
    private static final Set<String> OPTIONS = Set.of("topology", "database", "authDatabase", "replicaSet",
            "authMechanism", "readPreference", "tls", "connectTimeoutMS", "socketTimeoutMS",
            "maximumPoolSize", "idleTimeoutMS", "sentinelMaster", "sentinelUsername", "seeds");

    static ConnectionDraft create(JsonNode input, ObjectNode oldSecret) {
        DatabaseTransport transport = DatabaseTransport.of(input);
        if (transport == DatabaseTransport.JDBC) throw new IllegalArgumentException("Native profile required");
        for (String field : List.of("jar", "jars", "driverClass", "driverBundle", "driverInstall"))
            if (input.has(field)) throw new IllegalArgumentException("Native profiles do not accept JDBC driver configuration");
        String url = NativeTarget.text(input, "url", 8192);
        validateEndpoint(transport, url);
        ObjectNode profile = Profiles.JSON.createObjectNode().put("name", NativeTarget.text(input, "name", 120))
                .put("templateId", input.path("templateId").asText()).put("transport", transport.id)
                .put("url", url).put("color", ConnectionDraft.color(input));
        if (input.has("readOnly") && !input.path("readOnly").isBoolean()) throw new IllegalArgumentException("readOnly must be a boolean");
        profile.put("readOnly", input.path("readOnly").asBoolean(true));
        if (input.has("username")) {
            if (!input.path("username").isTextual() || input.path("username").asText().length() > 256) throw new IllegalArgumentException("Invalid native username");
            profile.put("username", input.path("username").asText());
        }
        ObjectNode options = profile.putObject("nativeOptions");
        JsonNode supplied = input.path("nativeOptions");
        if (!supplied.isMissingNode() && !supplied.isObject()) throw new IllegalArgumentException("nativeOptions must be an object");
        supplied.fields().forEachRemaining(entry -> {
            String key = entry.getKey(); JsonNode value = entry.getValue();
            if (!OPTIONS.contains(key)) throw new IllegalArgumentException("Unsupported native connection option: " + key);
            if (key.equals("tls")) {
                if (!value.isBoolean()) throw new IllegalArgumentException("tls must be a boolean");
            } else if (key.endsWith("MS") || key.equals("maximumPoolSize")) {
                int min = key.equals("idleTimeoutMS") ? 1000 : key.equals("maximumPoolSize") ? 1 : 100;
                int max = key.equals("maximumPoolSize") ? 16 : key.equals("idleTimeoutMS") ? 3600000 : 300000;
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) throw new IllegalArgumentException("Native connection limit out of range: " + key);
            } else if (key.equals("seeds")) {
                if (!value.isArray() || value.isEmpty() || value.size() > 15) throw new IllegalArgumentException("Supply 1..15 additional native seed endpoints");
                for (JsonNode endpoint : value) {
                    if (!endpoint.isTextual()) throw new IllegalArgumentException("Seed endpoints must be text");
                    validateEndpoint(transport, endpoint.asText());
                }
            } else if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 256 || value.asText().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Invalid native connection option: " + key);
            }
            options.set(key, value.deepCopy());
        });
        String topology = options.path("topology").asText(url.startsWith("mongodb+srv:") ? "srv" : url.startsWith("mongodb:") && url.contains(",") ? "replica_set" : "standalone");
        Set<String> topologies = transport == DatabaseTransport.MONGODB ? Set.of("standalone", "replica_set", "sharded", "srv") : Set.of("standalone", "sentinel", "cluster");
        if (!topologies.contains(topology)) throw new IllegalArgumentException("Unsupported native topology");
        options.put("topology", topology);
        if (options.has("seeds") && Set.of("standalone", "srv").contains(topology))
            throw new IllegalArgumentException("Additional seeds require replica_set, sharded, Sentinel or Cluster topology");
        int hosts = URI.create(url).getRawAuthority().split(",").length;
        for (JsonNode seed : options.path("seeds")) {
            URI endpoint = URI.create(seed.asText());
            if (!endpoint.getScheme().equals(URI.create(url).getScheme()))
                throw new IllegalArgumentException("All seed endpoint schemes must match the primary endpoint");
            hosts += endpoint.getRawAuthority().split(",").length;
        }
        if (hosts > 16) throw new IllegalArgumentException("Native topology seed allowance is 16 hosts total");
        if ((options.has("sentinelMaster") || options.has("sentinelUsername")) && !topology.equals("sentinel"))
            throw new IllegalArgumentException("Sentinel name and username are only valid for Redis Sentinel topology");
        if (transport == DatabaseTransport.REDIS) {
            for (String key : List.of("authDatabase", "authMechanism", "replicaSet", "readPreference"))
                if (options.has(key)) throw new IllegalArgumentException("MongoDB options cannot be used with Redis");
            String db = options.path("database").asText("0");
            if (!db.matches("0|[1-9][0-9]{0,4}") || Integer.parseInt(db) > 65535 || topology.equals("cluster") && !db.equals("0")) throw new IllegalArgumentException("Invalid Redis database; Cluster requires database 0");
            options.put("database", db);
            if (topology.equals("sentinel") && !options.has("sentinelMaster")) throw new IllegalArgumentException("Redis Sentinel requires sentinelMaster");
        } else {
            if (url.startsWith("mongodb+srv:") != topology.equals("srv")) throw new IllegalArgumentException("MongoDB SRV endpoints require srv topology");
            if (topology.equals("standalone") && url.contains(",")) throw new IllegalArgumentException("MongoDB standalone requires a single endpoint");
            if (options.has("sentinelMaster")) throw new IllegalArgumentException("Sentinel is a Redis topology");
            if (options.has("readPreference") && !Set.of("primary", "primaryPreferred", "secondary", "secondaryPreferred", "nearest").contains(options.path("readPreference").asText())) throw new IllegalArgumentException("Invalid MongoDB read preference");
            if (options.has("authMechanism") && !Set.of("SCRAM-SHA-256", "SCRAM-SHA-1", "MONGODB-X509").contains(options.path("authMechanism").asText())) throw new IllegalArgumentException("Authentication method requires a separately verified adapter");
        }
        if (input.path("properties").size() > 0) throw new IllegalArgumentException("Use verified nativeOptions and dedicated secret fields; arbitrary native options are not yet supported");
        JsonNode changes = input.path("secretProperties");
        if (!changes.isMissingNode() && !changes.isObject()) throw new IllegalArgumentException("secretProperties must be an object");
        changes.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("sentinelPassword")) throw new IllegalArgumentException("Unsupported native secret property; Redis Sentinel accepts only sentinelPassword");
            JsonNode value=entry.getValue();
            if (!value.isNull() && (!value.isTextual() || value.asText().length()>32768))
                throw new IllegalArgumentException("Sentinel password must be text and at most 32768 characters, or null to remove");
        });
        ObjectNode secret = oldSecret.deepCopy();
        ObjectNode hidden = secret.withObject("properties");
        if (input.path("replaceSecretProperties").asBoolean()) hidden.removeAll();
        changes.fields().forEachRemaining(entry -> {
            if(entry.getValue().isNull())hidden.remove(entry.getKey());else hidden.set(entry.getKey(),entry.getValue().deepCopy());
        });
        if (!hidden.isEmpty() && (hidden.size()!=1 || !hidden.has("sentinelPassword") || transport!=DatabaseTransport.REDIS || !topology.equals("sentinel")))
            throw new IllegalArgumentException("Remove saved Sentinel credentials before changing topology; only Redis Sentinel accepts sentinelPassword");
        if (input.path("removePassword").asBoolean()) secret.remove("password");
        if (input.has("password")) {
            if (!input.path("password").isTextual() || input.path("password").asText().length() > 32768) throw new IllegalArgumentException("Password must be text and at most 32768 characters");
            secret.put("password", input.path("password").asText());
        }
        return new ConnectionDraft(profile, secret);
    }

    static void validateEndpoint(DatabaseTransport transport, String value) {
        try {
            if (value.length() > 8192 || value.contains("${") || value.contains("%")) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            Set<String> schemes = transport == DatabaseTransport.MONGODB ? Set.of("mongodb", "mongodb+srv") : Set.of("redis", "rediss");
            String authority = uri.getRawAuthority();
            if (!schemes.contains(uri.getScheme()) || authority == null || authority.isBlank()
                    || authority.contains("@") || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() != null && !Set.of("", "/").contains(uri.getRawPath())) throw new IllegalArgumentException();
            String[] hosts = authority.split(",", -1);
            if (hosts.length > 16 || (transport == DatabaseTransport.REDIS || uri.getScheme().equals("mongodb+srv")) && hosts.length != 1) throw new IllegalArgumentException();
            for (String host : hosts) {
                URI endpoint = URI.create("tcp://" + host);
                if (endpoint.getHost() == null || endpoint.getPort() == 0 || endpoint.getPort() > 65535
                        || uri.getScheme().equals("mongodb+srv") && endpoint.getPort() != -1) throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Use native scheme://host[:port] endpoints without credentials, database paths, query options or fragments; configure these separately");
        }
    }

    private NativeProfile() { }
}
