package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Shared public input descriptions; runtime validators remain the security boundary. */
final class DbaInputSchemas {
    static void enrich(String operation, ObjectNode schema) {
        ObjectNode props = schema.withObject("properties");
        if(Set.of("dba_request_status","dba_cancel_request").contains(operation)) {
            props.putObject("approvalId").put("type","string").put("description","Server-returned approvalId (also exposed as legacy id); not the submission's idempotency requestId.");
            props.withObject("requestId").put("deprecated",true).put("description","Deprecated polling alias for approvalId. If both are supplied they must match.");
            schema.putArray("required");
            var alternatives=schema.putArray("anyOf");
            alternatives.addObject().putArray("required").add("approvalId");
            alternatives.addObject().putArray("required").add("requestId");
        } else if(props.has("requestId")) props.withObject("requestId").put("description","Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.");
        if(operation.equals("dba_request_native_command")){
            ObjectNode command=props.putObject("command").put("description","Bounded BSON Extended JSON command document or Redis argument vector. Mongo renameCollection requires the exact selected database.collection source and a same-database to namespace; dropTarget must be false or omitted. No system/view/time-series renames or destination replacement. Reviewed collMod supports existing TTL index changes, time-series retention/granularity and capped limits, one settings family per command; retention/capped changes may permanently delete data. Redis keys/values may be {base64: canonical-padded-base64}; command names, flags, cursors, patterns and numbers must be strings. 128 KiB aggregate input limit; binary values at most 64 KiB, keys/fields 8 KiB.");
            var alternatives=command.putArray("oneOf");
            alternatives.addObject().put("type","object").put("minProperties",1).put("maxProperties",64);
            var redisArguments=alternatives.addObject().put("type","array").put("minItems",1).put("maxItems",1024).putObject("items").putArray("oneOf");
            redisArguments.addObject().put("type","string").put("maxLength",131072);
            var binary=redisArguments.addObject().put("type","object").put("additionalProperties",false);
            binary.putArray("required").add("base64");
            binary.putObject("properties").putObject("base64").put("type","string").put("contentEncoding","base64").put("maxLength",87384);
            var targets=schema.putArray("oneOf");var bound=targets.addObject();bound.putArray("required").add("bindingId");
            var excluded=bound.putObject("not").putArray("anyOf");
            for(String key:List.of("connectionId","connectionName","database","schema"))excluded.addObject().putArray("required").add(key);
            var standalone=targets.addObject();standalone.putArray("required").add("connectionId").add("connectionName").add("database");
            standalone.putObject("not").putArray("required").add("bindingId");
        }
        if(Set.of("dba_search_objects","dba_get_indexed_ddl","dba_get_indexed_properties","dba_get_database_dependencies","dba_find_code_references","dba_scan_status","dba_refresh_catalog").contains(operation)){
            for(String key:List.of("bindingId","connectionId","connectionName","database","schema"))props.putObject(key).put("type","string");
            if(operation.equals("dba_find_code_references"))props.putObject("projectId").put("type","string").put("description","Required for standalone code-reference lookup; does not create a binding.");
            var required=schema.putArray("required");
            if(!Set.of("dba_search_objects","dba_scan_status","dba_refresh_catalog").contains(operation))required.add("objectId");
            var forms=schema.putArray("oneOf");var bound=forms.addObject();bound.putArray("required").add("bindingId");
            var excludes=bound.putObject("not").putArray("anyOf");
            for(String key:List.of("connectionId","connectionName","database","schema"))excludes.addObject().putArray("required").add(key);
            var standalone=forms.addObject();standalone.putArray("required").add("connectionId").add("connectionName").add("database");
            if(operation.equals("dba_find_code_references"))standalone.withArray("required").add("projectId");
            standalone.putObject("not").putArray("required").add("bindingId");
            props.withObject("database").put("description","Explicit exact standalone database/catalog; no default is inferred.");
            props.withObject("schema").put("description","Optional exact schema; omission inventories accessible objects in the selected database.");
        }
        props.properties().forEach(entry -> {
            ObjectNode value = (ObjectNode) entry.getValue();
            if (value.path("type").asText().equals("string")) value.put("maxLength", 256);
        });
        for (String key : List.of("connectionId", "bindingId", "jobId", "approvalId", "leftSnapshotId", "rightSnapshotId", "snapshotId", "planId", "leftPlanId", "rightPlanId", "projectId"))
            if (props.has(key)) props.withObject(key).put("format", "uuid").put("minLength", 1).put("maxLength", 36);
        if (props.has("requestId")) props.withObject("requestId").put("minLength", 1).put("maxLength",
                Set.of("dba_request_status", "dba_cancel_request").contains(operation) ? 36 : 100);
        if (props.has("purpose")) props.withObject("purpose").put("minLength", 1).put("maxLength", 2000);
        if(props.has("pairingCode"))props.withObject("pairingCode").put("minLength",8).put("maxLength",32);
        if(props.has("documentId"))props.withObject("documentId").put("format","uuid").put("maxLength",36);
        if(props.has("expectedRevision"))props.withObject("expectedRevision").put("minLength",16).put("maxLength",128);
        if(props.has("expectedWorkspaceRevision"))props.withObject("expectedWorkspaceRevision").put("minimum",0);
        for(String key:List.of("start","end"))if(props.has(key))props.withObject(key).put("minimum",0).put("maximum",1<<20);
        if(operation.equals("dba_create_editor_draft")){props.withObject("title").put("minLength",1).put("maxLength",255);props.withObject("sql").put("maxLength",65536);}
        if(operation.equals("dba_apply_editor_edit"))props.withObject("text").put("maxLength",65536);
        if (props.has("connectionName")) props.withObject("connectionName").put("minLength", 1).put("maxLength", 120);
        if (props.has("objectId")) props.withObject("objectId").put("minLength", 1).put("maxLength", 64);
        if (props.has("sql")) props.withObject("sql").put("minLength", 1).put("maxLength", 16384);
        if (props.has("offset")) props.withObject("offset").put("minimum", 0).put("maximum",
                operation.equals("dba_get_indexed_ddl") ? 4 << 20 : operation.equals("dba_get_indexed_properties") ? 10000 : 50000);
        if (props.has("limit")) props.withObject("limit").put("minimum", 1).put("maximum", 100);
        if (props.has("cursor")) props.withObject("cursor").put("maxLength", 16384).put("description", "Opaque five-minute continuation; repeat the same filters. Stale or expired generations must restart without a cursor.");
        if (props.has("templateId")) props.withObject("templateId").put("maxLength", 80);
        if (operation.equals("dba_list_templates")) props.withObject("limit").put("maximum", 50).put("default", 10);
        if (props.has("length")) props.withObject("length").put("minimum", 1).put("maximum", 64000);
        if (props.has("waitMillis")) props.withObject("waitMillis").put("minimum", 0).put("maximum", 5000).put("default", 0);
        if (props.has("afterGeneration")) props.withObject("afterGeneration").put("minimum", 0);
        if (props.has("afterRevision")) props.withObject("afterRevision").put("minimum",0).put("description","Revision from an earlier status of this exact job; required for positive waitMillis.");
        if (props.has("section")) props.withObject("section").putArray("enum")
                .add("columns").add("indexes").add("primaryKeys").add("foreignKeys").add("privileges").add("fieldObservations").add("nativeColumns").add("nativeKeys").add("nativeIndexes").add("constraints");
        if (props.has("parameters")) {
            ObjectNode params = props.withObject("parameters").put("maxItems", 128)
                    .put("description", "Positional JDBC bind values in marker order; JSON scalar types are preserved. Never interpolate SQL.");
            var choices = params.putObject("items").putArray("anyOf");
            choices.addObject().put("type", "string").put("maxLength", 8192);
            for (String type : List.of("number", "boolean", "null")) choices.addObject().put("type", type);
        }
        if(operation.equals("dba_prepare_migration")){
            props.withObject("sql").put("maxLength",65536).put("description","Explicit DDL artifact; 1..32 CREATE/ALTER/DROP/COMMENT/RENAME units. DROP DATABASE/SCHEMA and parameter markers are rejected.");
            ObjectNode changes=props.withObject("changes").put("minItems",1).put("maxItems",32);ObjectNode change=changes.putObject("items").put("type","object").put("additionalProperties",true);ObjectNode cp=change.putObject("properties");
            cp.putObject("action").put("type","string").putArray("enum").add("create_table").add("add_column").add("create_index").add("create_view");
            for(String key:List.of("table","column","name","type","query"))cp.putObject(key).put("type","string").put("maxLength",16384);
            cp.putObject("nullable").put("type","boolean");cp.putObject("unique").put("type","boolean");cp.putObject("columns").put("type","array").put("maxItems",256);
            var one=schema.putArray("oneOf");one.addObject().putArray("required").add("changes");one.addObject().putArray("required").add("sql");
        }
        if(operation.equals("dba_prepare_migration_rehearsal")){
            for(String key:List.of("setupSql","fixtureSql"))props.withObject(key).put("maxLength",16384).put("description",key.equals("setupSql")?"Optional CREATE/COMMENT-only disposable-target setup SQL.":"Optional INSERT-only bounded synthetic fixtures; never copy application records.");
            ObjectNode checks=props.withObject("checks").put("maxItems",16).put("description","Read-only validation queries evaluated after the migration on the disposable target.");checks.putObject("items").put("type","string").put("minLength",1).put("maxLength",16384);
        }
        if(Set.of("dba_request_connection_create","dba_request_connection_update").contains(operation)){
            props.putObject("driverInstall").put("type","object");
            bool(props,"testBeforeSave");bool(props,"saveUntested");bool(props,"confirmDriverEffects");
            props.withObject("testBeforeSave").put("description","YOLO requires exactly one of testBeforeSave or saveUntested. Test the unchanged draft before saving; failure saves nothing.");
            props.withObject("saveUntested").put("description","Explicitly save without connectivity validation; required configuration and vault checks still apply.");
        }
        if(operation.equals("dba_request_connection_test"))bool(props,"confirmDriverEffects");
        if(operation.equals("dba_capture_schema")){number(props,"sampleLimit",0,32);props.withObject("sampleLimit").put("description","MongoDB only: optional bounded document-type observations, default 0. Values are never returned; sampled types are not declared schema.");}
        if (props.has("profile")) profile(props.withObject("profile"), operation.endsWith("_create"));
        if (props.has("binding")) binding(props.withObject("binding"), operation);
        if (props.has("driverInstall")) driver(props.withObject("driverInstall"), true);
        if (Set.of("dba_capture_schema","dba_get_capabilities","dba_get_connection_details","dba_request_connection_test","dba_request_live_sql").contains(operation)) {
            var choices=schema.putArray("oneOf");
            var bound=choices.addObject();bound.putArray("required").add("bindingId");
            var excluded=bound.putObject("not").putArray("anyOf");
            for(String key:List.of("connectionId","connectionName","database","schema"))excluded.addObject().putArray("required").add(key);
            var standalone=choices.addObject();standalone.putArray("required").add("connectionId").add("connectionName");
            standalone.putObject("not").putArray("required").add("bindingId");
        }
    }

    private static ObjectNode object(ObjectNode node) {
        node.put("type", "object").put("additionalProperties", false);
        return node.putObject("properties");
    }
    private static ObjectNode text(ObjectNode props, String name, int max) {
        return props.putObject(name).put("type", "string").put("maxLength", max);
    }
    private static void bool(ObjectNode props, String name) { props.putObject(name).put("type", "boolean"); }
    private static void number(ObjectNode props, String name, long min, long max) {
        props.putObject(name).put("type", "integer").put("minimum", min).put("maximum", max);
    }
    private static void profile(ObjectNode node, boolean create) {
        ObjectNode props = object(node);
        if (create) {
            node.putArray("required").add("name").add("url");
            var choices=node.putArray("anyOf");
            var nativeProfile=choices.addObject();nativeProfile.putArray("required").add("templateId");
            nativeProfile.putObject("properties").putObject("templateId").putArray("enum").add("mongodb-native").add("redis-native");
            var jdbcProfile=choices.addObject();jdbcProfile.putArray("required").add("driverClass");
            jdbcProfile.putObject("not").putObject("properties").putObject("templateId").putArray("enum").add("mongodb-native").add("redis-native");
            jdbcProfile.withObject("not").putArray("required").add("templateId");
        }
        text(props, "name", 120).put("minLength", 1);
        text(props, "templateId", 80).put("description", "Template ID from dba_list_templates; custom if omitted.");
        text(props, "url", 8192).put("pattern", "^(jdbc:|mongodb(?:\\+srv)?://|rediss?://)").put("description", "JDBC URL or native endpoint; never embed credentials or private-key information. Native endpoints accept hosts/ports only; set database and options separately.");
        text(props, "driverClass", 200).put("pattern", "^[A-Za-z_$][A-Za-z0-9_$.]+$");
        text(props, "username", 8192);
        text(props, "password", 32768).put("writeOnly", true).put("description", "Write-only; the MCP client transcript may retain this argument. Prefer human entry. Omission keeps an existing password.");
        bool(props, "removePassword"); bool(props, "replaceSecretProperties"); bool(props, "readOnly");
        text(props, "color", 11).put("pattern", "^(transparent|#[0-9a-fA-F]{6})$");
        text(props, "jar", 4096).put("description", "Existing server-local JDBC JAR path; jars is preferred.");
        text(props,"transport",16).putArray("enum").add("jdbc").add("mongodb").add("redis");
        ObjectNode nativeOptions=object(props.putObject("nativeOptions"));
        text(nativeOptions,"database",256);text(nativeOptions,"authDatabase",256);text(nativeOptions,"replicaSet",256);
        text(nativeOptions,"topology",32).putArray("enum").add("standalone").add("replica_set").add("sharded").add("srv").add("sentinel").add("cluster");
        text(nativeOptions,"authMechanism",32).putArray("enum").add("SCRAM-SHA-256").add("SCRAM-SHA-1");
        text(nativeOptions,"readPreference",32).putArray("enum").add("primary").add("primaryPreferred").add("secondary").add("secondaryPreferred").add("nearest");
        text(nativeOptions,"sentinelMaster",256);nativeOptions.putObject("seeds").put("type","array").put("minItems",1).put("maxItems",15).putObject("items").put("type","string").put("maxLength",8192);
        text(nativeOptions,"sentinelUsername",256).put("minLength",1).put("description","Redis Sentinel ACL username only; omit for password-only or unauthenticated discovery. Separate from the Redis data-node username. Supply its write-only password in secretProperties.sentinelPassword.");
        bool(nativeOptions,"tls");number(nativeOptions,"connectTimeoutMS",100,300000);number(nativeOptions,"socketTimeoutMS",100,300000);
        number(nativeOptions,"maximumPoolSize",1,16);number(nativeOptions,"idleTimeoutMS",1000,3600000);
        var jars = props.putObject("jars").put("type", "array").put("minItems", 1).put("maxItems", 64);
        jars.putObject("items").put("type", "string").put("maxLength", 4096);
        driver(props.putObject("driverBundle"), false);
        for (String name : List.of("properties", "secretProperties")) {
            ObjectNode values = props.putObject(name).put("type", "object").put("maxProperties", 1024)
                    .put("description", "Explicit driver overrides. Unknown properties are secret by default; resolved values are never returned.");
            values.putObject("propertyNames").put("minLength", 1).put("maxLength", 180);
            var choices = values.putObject("additionalProperties").putArray("anyOf");
            choices.addObject().put("type", "string").put("maxLength", 32768);
            choices.addObject().put("type", "number"); choices.addObject().put("type", "boolean");
            if (name.equals("secretProperties")) {
                values.put("writeOnly", true).put("description", "Write-only secret property changes. Null removes one; omission keeps it. replaceSecretProperties first clears the old set. Native Redis Sentinel supports only sentinelPassword (string up to 32768 characters), separate from the data-node password; other native transports reject secret properties. Client transcripts may retain submitted secrets.");
                choices.addObject().put("type", "null");
            }
        }
        ObjectNode pool = object(props.putObject("pool"));
        number(pool, "maximumPoolSize", 1, 16); number(pool, "minimumIdle", 0, 16);
        for (String key : List.of("connectionTimeout", "validationTimeout")) number(pool, key, 250, 86400000);
        for (String key : List.of("idleTimeout", "maxLifetime")) number(pool, key, 0, 86400000);
    }
    private static void driver(ObjectNode node, boolean install) {
        ObjectNode props = object(node);
        for (String name : List.of("groupId", "artifactId", "version")) text(props, name, 200);
        if (install) {
            node.putArray("required").add("groupId").add("artifactId");
            props.withObject("version").put("default", "latest").put("description", "Human review resolves and pins the version. YOLO requires an explicit exact version; latest is rejected.");
        } else for (String name : List.of("classifier", "source", "bundleId")) text(props, name, 200);
    }
    private static void binding(ObjectNode node, String operation) {
        ObjectNode props = object(node);
        var required = node.putArray("required").add("projectId").add("environment").add("role").add("purpose");
        if (!operation.equals("dba_request_connection_create")) required.add("connectionId");
        for (String key : List.of("id", "projectId", "connectionId")) text(props, key, 36).put("format", "uuid");
        text(props, "database", 256); text(props, "schema", 256);
        text(props, "environment", 32).putArray("enum").add("local").add("dev").add("test").add("stage").add("prod");
        text(props, "role", 80).put("minLength", 1).put("description", "Logical role, unique in this application's environment.");
        text(props, "purpose", 500).put("minLength", 1);
        bool(props, "enabled"); number(props, "scanIntervalSeconds", 10, 86400); number(props, "idleTimeoutSeconds", 10, 604800);
        node.put("description", "Exact application/database scope. Supply a nonblank database or schema. Updating requires the complete binding; immutable target changes require replacement.");
    }
    private DbaInputSchemas() {}
}
