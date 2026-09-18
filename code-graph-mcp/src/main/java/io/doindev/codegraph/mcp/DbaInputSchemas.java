package io.doindev.codegraph.mcp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;

/** Shared public input descriptions; runtime validators remain the security boundary. */
final class DbaInputSchemas {
    static void enrich(String operation, ObjectNode schema) {
        ObjectNode props = schema.withObject("properties");
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
        if (props.has("section")) props.withObject("section").putArray("enum")
                .add("columns").add("indexes").add("primaryKeys").add("foreignKeys").add("privileges");
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
        if (create) node.putArray("required").add("name").add("url").add("driverClass");
        text(props, "name", 120).put("minLength", 1);
        text(props, "templateId", 80).put("description", "Template ID from dba_list_templates; custom if omitted.");
        text(props, "url", 8192).put("pattern", "^jdbc:").put("description", "JDBC URL with no credentials or private-key information.");
        text(props, "driverClass", 200).put("pattern", "^[A-Za-z_$][A-Za-z0-9_$.]+$");
        text(props, "username", 8192);
        text(props, "password", 32768).put("writeOnly", true).put("description", "Write-only; the MCP client transcript may retain this argument. Prefer human entry. Omission keeps an existing password.");
        bool(props, "removePassword"); bool(props, "replaceSecretProperties"); bool(props, "readOnly");
        text(props, "color", 11).put("pattern", "^(transparent|#[0-9a-fA-F]{6})$");
        text(props, "jar", 4096).put("description", "Existing server-local JDBC JAR path; jars is preferred.");
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
                values.put("writeOnly", true).put("description", "Write-only secret property changes. Null removes one; omission keeps it. replaceSecretProperties first clears the old set.");
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
            props.withObject("version").put("default", "latest").put("description", "Human review resolves and pins the exact version before any installation.");
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
