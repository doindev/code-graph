# Generated MCP tool reference

Generated from tool definitions; do not edit tool entries manually.

Availability depends on DBA, approval and browser-editor configuration. Definitions never authorize execution.

Use `requestId` when submitting an idempotent proposal, returned `approvalId` to poll/cancel it, and `jobId` for execution. Legacy aliases remain supported.

## `add_project`

Onboard a directory on the server: index it and start watching for changes. Returns the project name when the initial scan is ready (may take time). Rejects duplicate roots and children of onboarded or in-flight roots. Does not require the UI or UI admin mode; projects are session-local.

```json
{ "type": "object",
  "properties": {
    "path": { "type": "string", "minLength": 1,
      "description": "Existing directory on the server machine. Prefer an absolute path; relative paths resolve against the server working directory." } },
  "required": ["path"] }

```

## `analyze_change`

Analyze explicit files, symbol IDs, an exact Git working-tree/revision selection, or proposed database-name changes against the current code index. Returns bounded dependencies, static ORM/SQL mappings, migration references, candidate tests and uncertainty. Git evidence records resolved commits; results still describe the current index and do not access live database catalogs.

```json
{"type":"object","additionalProperties":false,"properties":{"symbols":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":4096}},"files":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":4096}},"databaseChanges":{"type":"array","maxItems":100,"items":{"type":"object","additionalProperties":false,"required":["table"],"properties":{"column":{"type":"string","maxLength":128},"schema":{"type":"string","maxLength":128},"table":{"type":"string","maxLength":128},"operation":{"type":"string","enum":["add","alter","remove","rename"]},"newName":{"type":"string","maxLength":128}}}},"git":{"type":"object","additionalProperties":false,"properties":{"kind":{"type":"string","enum":["working_tree","revisions"]},"head":{"type":"string","minLength":1,"maxLength":200},"base":{"type":"string","minLength":1,"maxLength":200}}},"depth":{"type":"integer","minimum":1,"maximum":10,"default":5},"limit":{"type":"integer","minimum":1,"maximum":100,"default":50},"minConfidence":{"type":"number","minimum":0,"maximum":1,"default":0},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `compare_architectural_drift`

Architecture violations vs the code-graph.json blueprint: forbidden module dependencies and module cycles, with witness edges and stable fingerprints.

```json
{"type":"object","properties":{"include":{"type":"array","items":{"type":"string","enum":["layers","cycles"]},"description":"Violation types to report; default both"},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `compare_query_plans`

Compare two completed retained estimated-plan jobs using normalized structural evidence while retaining raw vendor evidence in the source jobs. Never compares cross-vendor cost units or labels every scan a regression.

```json
{"type":"object","additionalProperties":false,"properties":{"leftPlanId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"rightPlanId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"limit":{"type":"integer","minimum":1,"maximum":100}},"required":["leftPlanId","rightPlanId"]}
```

## `dba_analyze_query_plan`

Analyze an estimated SELECT plan without EXPLAIN ANALYZE or database ANALYZE. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1},"sql":{"type":"string","maxLength":16384,"minLength":1},"parameters":{"type":"array","maxItems":128,"description":"Positional JDBC bind values in marker order; JSON scalar types are preserved. Never interpolate SQL.","items":{"anyOf":[{"type":"string","maxLength":8192},{"type":"number"},{"type":"boolean"},{"type":"null"}]}},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","description":"Project-bound target; do not combine with connectionId, connectionName, database or schema.","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","description":"Explicit standalone database. Reusable access requires connectionId and exact connectionName.","maxLength":256},"schema":{"type":"string","description":"Exact schema boundary for reusable permissions.","maxLength":256}},"required":["sql"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_apply_editor_edit`

Apply one bounded UTF-16 text-range edit to a paired Script document only when its exact revision still matches. Conflicts are rejected; edits never execute SQL or save files.

```json
{"type":"object","additionalProperties":false,"properties":{"documentId":{"type":"string","maxLength":36,"format":"uuid"},"expectedRevision":{"type":"string","maxLength":128,"minLength":16},"start":{"type":"integer","minimum":0,"maximum":1048576},"end":{"type":"integer","minimum":0,"maximum":1048576},"text":{"type":"string","maxLength":65536}},"required":["documentId","expectedRevision","start","end","text"]}
```

## `dba_cancel_job`

Request cancellation of this agent's job.

```json
{"type":"object","additionalProperties":false,"properties":{"jobId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["jobId"]}
```

## `dba_cancel_live_request`

Compatibility alias for dba_cancel_request; use the canonical tool with approvalId.

```json
{"type":"object","additionalProperties":false,"properties":{"approvalId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["approvalId"]}
```

## `dba_cancel_request`

Cancel this agent's pending approvalId or request cancellation of its job; the old requestId polling field remains a deprecated alias.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","deprecated":true,"description":"Deprecated polling alias for approvalId. If both are supplied they must match.","maxLength":36,"minLength":1},"approvalId":{"type":"string","description":"Server-returned approvalId (also exposed as legacy id); not the submission's idempotency requestId.","maxLength":36,"format":"uuid","minLength":1}},"required":[],"anyOf":[{"required":["approvalId"]},{"required":["requestId"]}]}
```

## `dba_capture_schema`

Capture a bounded schema observation as an asynchronous retained job, requiring existing catalog permission for bindingId or exact connection UUID/name/database/schema. No data records or migration SQL are executed. Partial inventories never prove removal; release the job when no longer needed.

```json
{"type":"object","additionalProperties":false,"properties":{"schema":{"type":"string","maxLength":256},"connectionName":{"type":"string","maxLength":120,"minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","maxLength":256},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"sampleLimit":{"type":"integer","minimum":0,"maximum":32,"description":"MongoDB only: optional bounded document-type observations, default 0. Values are never returned; sampled types are not declared schema."}},"required":[],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_compare_schemas`

Compare two completed retained schema capture job IDs owned by this agent. Reauthorizes both exact targets. Reports structural, definition-text and cross-engine compatibility differences; never infers renames, removals from incomplete inventories or generates migration SQL.

```json
{"type":"object","additionalProperties":false,"properties":{"leftSnapshotId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"rightSnapshotId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"limit":{"type":"integer","minimum":1,"maximum":100}},"required":["leftSnapshotId","rightSnapshotId"]}
```

## `dba_create_editor_draft`

Create an unsaved Script draft in the paired browser workspace at an expected workspace revision. Never executes SQL, saves a file or changes database permissions.

```json
{"type":"object","additionalProperties":false,"properties":{"title":{"type":"string","maxLength":255,"minLength":1},"sql":{"type":"string","maxLength":16384,"minLength":1},"expectedWorkspaceRevision":{"type":"integer","minimum":0},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["title","sql","expectedWorkspaceRevision"]}
```

## `dba_execute_read_query`

Run one structurally restricted SELECT asynchronously with at most 100 rows/1 MiB. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1},"sql":{"type":"string","maxLength":16384,"minLength":1},"parameters":{"type":"array","maxItems":128,"description":"Positional JDBC bind values in marker order; JSON scalar types are preserved. Never interpolate SQL.","items":{"anyOf":[{"type":"string","maxLength":8192},{"type":"number"},{"type":"boolean"},{"type":"null"}]}},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","description":"Project-bound target; do not combine with connectionId, connectionName, database or schema.","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","description":"Explicit standalone database. Reusable access requires connectionId and exact connectionName.","maxLength":256},"schema":{"type":"string","description":"Exact schema boundary for reusable permissions.","maxLength":256}},"required":["sql"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_explain_query`

Get an estimated SELECT plan without execution-based analysis under legacy or scoped explain permission. May return an approval request or an asynchronous job. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1},"sql":{"type":"string","maxLength":16384,"minLength":1},"parameters":{"type":"array","maxItems":128,"description":"Positional JDBC bind values in marker order; JSON scalar types are preserved. Never interpolate SQL.","items":{"anyOf":[{"type":"string","maxLength":8192},{"type":"number"},{"type":"boolean"},{"type":"null"}]}},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","description":"Project-bound target; do not combine with connectionId, connectionName, database or schema.","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","description":"Explicit standalone database. Reusable access requires connectionId and exact connectionName.","maxLength":256},"schema":{"type":"string","description":"Exact schema boundary for reusable permissions.","maxLength":256}},"required":["sql"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_find_code_references`

Find candidate code locations for an indexed database object; dynamic SQL can remain unresolved.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"objectId":{"type":"string","maxLength":64,"minLength":1},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256},"projectId":{"type":"string","description":"Required for standalone code-reference lookup; does not create a binding.","maxLength":36,"format":"uuid","minLength":1}},"required":["objectId"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database","projectId"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_capabilities`

Report authorized target capabilities and cached vendor/version. live=true submits a bounded JDBC metadata observation job for this exact target (catalog permission required), including standalone targets. Unobserved servers remain unknown, not inferred from templates.

```json
{"type":"object","additionalProperties":false,"properties":{"live":{"type":"boolean"},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","maxLength":256},"schema":{"type":"string","maxLength":256}},"required":[],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_connection_details`

Get an allowlisted non-secret profile by bindingId, or stable connectionId plus exact connectionName. Returns immediately with permission or creates a read approval request. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1}},"required":["requestId","purpose"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_database_dependencies`

Read bounded cached dependency edges for an indexed object.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"objectId":{"type":"string","maxLength":64,"minLength":1},"limit":{"type":"integer","minimum":1,"maximum":100},"offset":{"type":"integer","minimum":0,"maximum":50000},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256}},"required":["objectId"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_editor_document`

Read one paired Script document with its exact revision for conflict-safe edits. Does not execute SQL or save a file.

```json
{"type":"object","additionalProperties":false,"properties":{"documentId":{"type":"string","maxLength":36,"format":"uuid"}},"required":["documentId"]}
```

## `dba_get_indexed_ddl`

Read bounded DDL chunks from an authorized cached snapshot.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"objectId":{"type":"string","maxLength":64,"minLength":1},"length":{"type":"integer","minimum":1,"maximum":64000},"offset":{"type":"integer","minimum":0,"maximum":4194304},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256}},"required":["objectId"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_indexed_properties`

Read cached columns, indexes, primary keys, foreign keys or privileges in bounded pages.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"objectId":{"type":"string","maxLength":64,"minLength":1},"section":{"type":"string","maxLength":256,"enum":["columns","indexes","primaryKeys","foreignKeys","privileges","fieldObservations","nativeColumns","nativeKeys","nativeIndexes","constraints"]},"offset":{"type":"integer","minimum":0,"maximum":10000},"limit":{"type":"integer","minimum":1,"maximum":100},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256}},"required":["objectId"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_metadata`

Read capped metadata under legacy or scoped catalog permissions. Use bindingId or connectionId plus exact connectionName; unapproved access returns an approval request when a review channel is enabled. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1},"schema":{"type":"string","maxLength":256},"object":{"type":"string","maxLength":256},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","description":"Project-bound target; do not combine with connectionId, connectionName, database or schema.","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","description":"Explicit standalone database. Reusable access requires connectionId and exact connectionName.","maxLength":256}},"required":[],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_get_my_permissions`

List legacy grants and scoped exact/category reusable permissions, including active MCP-session grants, lifetimes, targets and last use. Never returns credentials.

```json
{"type":"object","additionalProperties":false,"properties":{},"required":[]}
```

## `dba_get_object_ddl`

Inspect supported PostgreSQL, MySQL/MariaDB and SQL Server object definitions through scoped catalog permission; partial or inaccessible definitions are labeled. Never creates objects. May return an approval request, otherwise an asynchronous job. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1},"schema":{"type":"string","maxLength":256},"object":{"type":"string","maxLength":256},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","description":"Project-bound target; do not combine with connectionId, connectionName, database or schema.","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","description":"Explicit standalone database. Reusable access requires connectionId and exact connectionName.","maxLength":256}},"required":["object"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_job_status`

Get this agent's bounded job state/result and revision. Optional waitMillis (0..5000) waits for a change after afterRevision or terminal completion; timeout does not mean completion.

```json
{"type":"object","additionalProperties":false,"properties":{"jobId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"waitMillis":{"type":"integer","minimum":0,"maximum":5000,"default":0},"afterRevision":{"type":"integer","minimum":0,"description":"Revision from an earlier status of this exact job; required for positive waitMillis."}},"required":["jobId"]}
```

## `dba_list_connections`

List stable connection IDs/names without credentials or URLs. Trusted local clients can discover connections without setup; database access still requires reviewed permission. Optional named-token clients retain their grant-filtered roster.

```json
{"type":"object","additionalProperties":false,"properties":{},"required":[]}
```

## `dba_list_editor_documents`

List bounded Script document metadata and revisions in the explicitly paired browser workspace. Does not expose Table tabs or execute SQL.

```json
{"type":"object","additionalProperties":false,"properties":{},"required":[]}
```

## `dba_list_my_created_connections`

List stable IDs, names, creation request IDs, timestamps and bindings for profiles created through this agent. Creation never grants access.

```json
{"type":"object","additionalProperties":false,"properties":{},"required":[]}
```

## `dba_list_project_databases`

List authorized application database bindings with canonical environment, logical role, purpose, scope, freshness and effective permissions. Listing alone does not renew activity.

```json
{"type":"object","additionalProperties":false,"properties":{},"required":[]}
```

## `dba_list_templates`

Page through public JDBC templates, driver recipes and property descriptors without downloads, credentials, database connections or scans. Template availability is not advanced-workflow certification.

```json
{"type":"object","additionalProperties":false,"properties":{"cursor":{"type":"string","maxLength":16384,"description":"Opaque five-minute continuation; repeat the same filters. Stale or expired generations must restart without a cursor."},"templateId":{"type":"string","maxLength":80},"limit":{"type":"integer","minimum":1,"maximum":50,"default":10}},"required":[]}
```

## `dba_live_request_status`

Compatibility alias for dba_request_status; use the canonical tool with approvalId.

```json
{"type":"object","additionalProperties":false,"properties":{"approvalId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["approvalId"]}
```

## `dba_pair_editor`

Pair this logical MCP session with a user-selected DBA browser workspace using its short-lived code. Pairing grants revision-checked Script collaboration only and no database permissions.

```json
{"type":"object","additionalProperties":false,"properties":{"pairingCode":{"type":"string","maxLength":32,"minLength":8}},"required":["pairingCode"]}
```

## `dba_prepare_migration`

Prepare a bounded migration artifact from a completed retained schema snapshot and either explicit structured changes or supplied DDL. Returns reviewed SQL plus a machine-readable manifest; executes nothing and never infers renames, cascading or backfills.

```json
{"type":"object","additionalProperties":false,"properties":{"snapshotId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"changes":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"object","additionalProperties":true,"properties":{"action":{"type":"string","enum":["create_table","add_column","create_index","create_view"]},"table":{"type":"string","maxLength":16384},"column":{"type":"string","maxLength":16384},"name":{"type":"string","maxLength":16384},"type":{"type":"string","maxLength":16384},"query":{"type":"string","maxLength":16384},"nullable":{"type":"boolean"},"unique":{"type":"boolean"},"columns":{"type":"array","maxItems":256}}}},"name":{"type":"string","maxLength":256},"sql":{"type":"string","maxLength":65536,"minLength":1,"description":"Explicit DDL artifact; 1..32 CREATE/ALTER/DROP/COMMENT/RENAME units. DROP DATABASE/SCHEMA and parameter markers are rejected."},"purpose":{"type":"string","maxLength":2000,"minLength":1}},"required":["snapshotId"],"oneOf":[{"required":["changes"]},{"required":["sql"]}]}
```

## `dba_prepare_migration_rehearsal`

Prepare a separately retained rehearsal plan for an independently authorized disposable schema snapshot. Reviews bounded setup SQL, synthetic fixture INSERTs, the source migration, and validation SELECTs together. Rejects the source target and aliases; executes nothing until exact one-time human approval.

```json
{"type":"object","additionalProperties":false,"properties":{"planId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"rehearsalSnapshotId":{"type":"string","maxLength":256},"name":{"type":"string","maxLength":256},"purpose":{"type":"string","maxLength":2000,"minLength":1},"setupSql":{"type":"string","maxLength":16384,"description":"Optional CREATE/COMMENT-only disposable-target setup SQL."},"fixtureSql":{"type":"string","maxLength":16384,"description":"Optional INSERT-only bounded synthetic fixtures; never copy application records."},"checks":{"type":"array","maxItems":16,"description":"Read-only validation queries evaluated after the migration on the disposable target.","items":{"type":"string","minLength":1,"maxLength":16384}}},"required":["planId","rehearsalSnapshotId"]}
```

## `dba_refresh_catalog`

Request a bounded catalog scan for an authorized binding or explicit standalone connection/database/schema. Coalesces active scans; does not write to the database. Optionally wait for a newer generation.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"afterGeneration":{"type":"integer","minimum":0},"waitMillis":{"type":"integer","minimum":0,"maximum":5000,"default":0},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256}},"required":[],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_release_job`

Release this agent's completed result.

```json
{"type":"object","additionalProperties":false,"properties":{"jobId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["jobId"]}
```

## `dba_request_apply_migration`

Request exact one-time human review and asynchronous application of a retained migration or rehearsal plan. Reusable SQL policies never authorize it; execution rechecks target revision and schema fingerprint and never retries uncertain writes. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"planId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["requestId","purpose","planId"]}
```

## `dba_request_binding_create`

Propose associating an existing connection with an onboarded application, canonical environment, required logical role and purpose. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"binding":{"type":"object","additionalProperties":false,"properties":{"id":{"type":"string","maxLength":36,"format":"uuid"},"projectId":{"type":"string","maxLength":36,"format":"uuid"},"connectionId":{"type":"string","maxLength":36,"format":"uuid"},"database":{"type":"string","maxLength":256},"schema":{"type":"string","maxLength":256},"environment":{"type":"string","maxLength":32,"enum":["local","dev","test","stage","prod"]},"role":{"type":"string","maxLength":80,"minLength":1,"description":"Logical role, unique in this application's environment."},"purpose":{"type":"string","maxLength":500,"minLength":1},"enabled":{"type":"boolean"},"scanIntervalSeconds":{"type":"integer","minimum":10,"maximum":86400},"idleTimeoutSeconds":{"type":"integer","minimum":10,"maximum":604800}},"required":["projectId","environment","role","purpose","connectionId"],"description":"Exact application/database scope. Supply a nonblank database or schema. Updating requires the complete binding; immutable target changes require replacement."}},"required":["requestId","purpose","binding"]}
```

## `dba_request_binding_delete`

Propose removing an application/database association without deleting the connection or database. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["requestId","purpose","bindingId"]}
```

## `dba_request_binding_update`

Propose environment, role, purpose, enabled, scan or idle changes. Immutable target changes require replacement. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"binding":{"type":"object","additionalProperties":false,"properties":{"id":{"type":"string","maxLength":36,"format":"uuid"},"projectId":{"type":"string","maxLength":36,"format":"uuid"},"connectionId":{"type":"string","maxLength":36,"format":"uuid"},"database":{"type":"string","maxLength":256},"schema":{"type":"string","maxLength":256},"environment":{"type":"string","maxLength":32,"enum":["local","dev","test","stage","prod"]},"role":{"type":"string","maxLength":80,"minLength":1,"description":"Logical role, unique in this application's environment."},"purpose":{"type":"string","maxLength":500,"minLength":1},"enabled":{"type":"boolean"},"scanIntervalSeconds":{"type":"integer","minimum":10,"maximum":86400},"idleTimeoutSeconds":{"type":"integer","minimum":10,"maximum":604800}},"required":["projectId","environment","role","purpose","connectionId"],"description":"Exact application/database scope. Supply a nonblank database or schema. Updating requires the complete binding; immutable target changes require replacement."}},"required":["requestId","purpose","bindingId","binding"]}
```

## `dba_request_connection_create`

Propose a connection profile, optional application binding, and optional explicit Maven driver installation. Password and secretProperties are write-only arguments that the MCP client/task transcript may retain; prefer human entry when possible. Creation grants no access. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"profile":{"type":"object","additionalProperties":false,"properties":{"name":{"type":"string","maxLength":120,"minLength":1},"templateId":{"type":"string","maxLength":80,"description":"Template ID from dba_list_templates; custom if omitted."},"url":{"type":"string","maxLength":8192,"pattern":"^(jdbc:|mongodb(?:\\+srv)?://|rediss?://)","description":"JDBC URL or native endpoint; never embed credentials or private-key information. Native endpoints accept hosts/ports only; set database and options separately."},"driverClass":{"type":"string","maxLength":200,"pattern":"^[A-Za-z_$][A-Za-z0-9_$.]+$"},"username":{"type":"string","maxLength":8192},"password":{"type":"string","maxLength":32768,"writeOnly":true,"description":"Write-only; the MCP client transcript may retain this argument. Prefer human entry. Omission keeps an existing password."},"removePassword":{"type":"boolean"},"replaceSecretProperties":{"type":"boolean"},"readOnly":{"type":"boolean"},"color":{"type":"string","maxLength":11,"pattern":"^(transparent|#[0-9a-fA-F]{6})$"},"jar":{"type":"string","maxLength":4096,"description":"Existing server-local JDBC JAR path; jars is preferred."},"transport":{"type":"string","maxLength":16,"enum":["jdbc","mongodb","redis"]},"nativeOptions":{"type":"object","additionalProperties":false,"properties":{"database":{"type":"string","maxLength":256},"authDatabase":{"type":"string","maxLength":256},"replicaSet":{"type":"string","maxLength":256},"topology":{"type":"string","maxLength":32,"enum":["standalone","replica_set","sharded","srv","sentinel","cluster"]},"authMechanism":{"type":"string","maxLength":32,"enum":["SCRAM-SHA-256","SCRAM-SHA-1"]},"readPreference":{"type":"string","maxLength":32,"enum":["primary","primaryPreferred","secondary","secondaryPreferred","nearest"]},"sentinelMaster":{"type":"string","maxLength":256},"seeds":{"type":"array","minItems":1,"maxItems":15,"items":{"type":"string","maxLength":8192}},"sentinelUsername":{"type":"string","maxLength":256,"minLength":1,"description":"Redis Sentinel ACL username only; omit for password-only or unauthenticated discovery. Separate from the Redis data-node username. Supply its write-only password in secretProperties.sentinelPassword."},"tls":{"type":"boolean"},"connectTimeoutMS":{"type":"integer","minimum":100,"maximum":300000},"socketTimeoutMS":{"type":"integer","minimum":100,"maximum":300000},"maximumPoolSize":{"type":"integer","minimum":1,"maximum":16},"idleTimeoutMS":{"type":"integer","minimum":1000,"maximum":3600000}}},"jars":{"type":"array","minItems":1,"maxItems":64,"items":{"type":"string","maxLength":4096}},"driverBundle":{"type":"object","additionalProperties":false,"properties":{"groupId":{"type":"string","maxLength":200},"artifactId":{"type":"string","maxLength":200},"version":{"type":"string","maxLength":200},"classifier":{"type":"string","maxLength":200},"source":{"type":"string","maxLength":200},"bundleId":{"type":"string","maxLength":200}}},"properties":{"type":"object","maxProperties":1024,"description":"Explicit driver overrides. Unknown properties are secret by default; resolved values are never returned.","propertyNames":{"minLength":1,"maxLength":180},"additionalProperties":{"anyOf":[{"type":"string","maxLength":32768},{"type":"number"},{"type":"boolean"}]}},"secretProperties":{"type":"object","maxProperties":1024,"description":"Write-only secret property changes. Null removes one; omission keeps it. replaceSecretProperties first clears the old set. Native Redis Sentinel supports only sentinelPassword (string up to 32768 characters), separate from the data-node password; other native transports reject secret properties. Client transcripts may retain submitted secrets.","propertyNames":{"minLength":1,"maxLength":180},"additionalProperties":{"anyOf":[{"type":"string","maxLength":32768},{"type":"number"},{"type":"boolean"},{"type":"null"}]},"writeOnly":true},"pool":{"type":"object","additionalProperties":false,"properties":{"maximumPoolSize":{"type":"integer","minimum":1,"maximum":16},"minimumIdle":{"type":"integer","minimum":0,"maximum":16},"connectionTimeout":{"type":"integer","minimum":250,"maximum":86400000},"validationTimeout":{"type":"integer","minimum":250,"maximum":86400000},"idleTimeout":{"type":"integer","minimum":0,"maximum":86400000},"maxLifetime":{"type":"integer","minimum":0,"maximum":86400000}}}},"required":["name","url"],"anyOf":[{"required":["templateId"],"properties":{"templateId":{"enum":["mongodb-native","redis-native"]}}},{"required":["driverClass"],"not":{"properties":{"templateId":{"enum":["mongodb-native","redis-native"]}},"required":["templateId"]}}]},"binding":{"type":"object","additionalProperties":false,"properties":{"id":{"type":"string","maxLength":36,"format":"uuid"},"projectId":{"type":"string","maxLength":36,"format":"uuid"},"connectionId":{"type":"string","maxLength":36,"format":"uuid"},"database":{"type":"string","maxLength":256},"schema":{"type":"string","maxLength":256},"environment":{"type":"string","maxLength":32,"enum":["local","dev","test","stage","prod"]},"role":{"type":"string","maxLength":80,"minLength":1,"description":"Logical role, unique in this application's environment."},"purpose":{"type":"string","maxLength":500,"minLength":1},"enabled":{"type":"boolean"},"scanIntervalSeconds":{"type":"integer","minimum":10,"maximum":86400},"idleTimeoutSeconds":{"type":"integer","minimum":10,"maximum":604800}},"required":["projectId","environment","role","purpose"],"description":"Exact application/database scope. Supply a nonblank database or schema. Updating requires the complete binding; immutable target changes require replacement."},"driverInstall":{"type":"object","additionalProperties":false,"properties":{"groupId":{"type":"string","maxLength":200},"artifactId":{"type":"string","maxLength":200},"version":{"type":"string","maxLength":200,"default":"latest","description":"Human review resolves and pins the version. YOLO requires an explicit exact version; latest is rejected."}},"required":["groupId","artifactId"]},"testBeforeSave":{"type":"boolean","description":"YOLO requires exactly one of testBeforeSave or saveUntested. Test the unchanged draft before saving; failure saves nothing."},"saveUntested":{"type":"boolean","description":"Explicitly save without connectivity validation; required configuration and vault checks still apply."},"confirmDriverEffects":{"type":"boolean"}},"required":["requestId","purpose","profile"]}
```

## `dba_request_connection_delete`

Request removal of application profile configuration, vault entries, pools, bindings and policies. Never drops the database or removes a container. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1}},"required":["requestId","purpose","connectionId","connectionName"]}
```

## `dba_request_connection_test`

Request a safe connection test by bindingId, or stable connectionId plus exact name. Test results are ephemeral. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"confirmDriverEffects":{"type":"boolean"}},"required":["requestId","purpose"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_request_connection_update`

Propose an exact connection profile change. Secrets are write-only and never returned or audited. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"connectionId":{"type":"string","description":"Stable connection UUID; must match connectionName.","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","description":"Exact saved connection name; no browser/default connection is inferred.","maxLength":120,"minLength":1},"profile":{"type":"object","additionalProperties":false,"properties":{"name":{"type":"string","maxLength":120,"minLength":1},"templateId":{"type":"string","maxLength":80,"description":"Template ID from dba_list_templates; custom if omitted."},"url":{"type":"string","maxLength":8192,"pattern":"^(jdbc:|mongodb(?:\\+srv)?://|rediss?://)","description":"JDBC URL or native endpoint; never embed credentials or private-key information. Native endpoints accept hosts/ports only; set database and options separately."},"driverClass":{"type":"string","maxLength":200,"pattern":"^[A-Za-z_$][A-Za-z0-9_$.]+$"},"username":{"type":"string","maxLength":8192},"password":{"type":"string","maxLength":32768,"writeOnly":true,"description":"Write-only; the MCP client transcript may retain this argument. Prefer human entry. Omission keeps an existing password."},"removePassword":{"type":"boolean"},"replaceSecretProperties":{"type":"boolean"},"readOnly":{"type":"boolean"},"color":{"type":"string","maxLength":11,"pattern":"^(transparent|#[0-9a-fA-F]{6})$"},"jar":{"type":"string","maxLength":4096,"description":"Existing server-local JDBC JAR path; jars is preferred."},"transport":{"type":"string","maxLength":16,"enum":["jdbc","mongodb","redis"]},"nativeOptions":{"type":"object","additionalProperties":false,"properties":{"database":{"type":"string","maxLength":256},"authDatabase":{"type":"string","maxLength":256},"replicaSet":{"type":"string","maxLength":256},"topology":{"type":"string","maxLength":32,"enum":["standalone","replica_set","sharded","srv","sentinel","cluster"]},"authMechanism":{"type":"string","maxLength":32,"enum":["SCRAM-SHA-256","SCRAM-SHA-1"]},"readPreference":{"type":"string","maxLength":32,"enum":["primary","primaryPreferred","secondary","secondaryPreferred","nearest"]},"sentinelMaster":{"type":"string","maxLength":256},"seeds":{"type":"array","minItems":1,"maxItems":15,"items":{"type":"string","maxLength":8192}},"sentinelUsername":{"type":"string","maxLength":256,"minLength":1,"description":"Redis Sentinel ACL username only; omit for password-only or unauthenticated discovery. Separate from the Redis data-node username. Supply its write-only password in secretProperties.sentinelPassword."},"tls":{"type":"boolean"},"connectTimeoutMS":{"type":"integer","minimum":100,"maximum":300000},"socketTimeoutMS":{"type":"integer","minimum":100,"maximum":300000},"maximumPoolSize":{"type":"integer","minimum":1,"maximum":16},"idleTimeoutMS":{"type":"integer","minimum":1000,"maximum":3600000}}},"jars":{"type":"array","minItems":1,"maxItems":64,"items":{"type":"string","maxLength":4096}},"driverBundle":{"type":"object","additionalProperties":false,"properties":{"groupId":{"type":"string","maxLength":200},"artifactId":{"type":"string","maxLength":200},"version":{"type":"string","maxLength":200},"classifier":{"type":"string","maxLength":200},"source":{"type":"string","maxLength":200},"bundleId":{"type":"string","maxLength":200}}},"properties":{"type":"object","maxProperties":1024,"description":"Explicit driver overrides. Unknown properties are secret by default; resolved values are never returned.","propertyNames":{"minLength":1,"maxLength":180},"additionalProperties":{"anyOf":[{"type":"string","maxLength":32768},{"type":"number"},{"type":"boolean"}]}},"secretProperties":{"type":"object","maxProperties":1024,"description":"Write-only secret property changes. Null removes one; omission keeps it. replaceSecretProperties first clears the old set. Native Redis Sentinel supports only sentinelPassword (string up to 32768 characters), separate from the data-node password; other native transports reject secret properties. Client transcripts may retain submitted secrets.","propertyNames":{"minLength":1,"maxLength":180},"additionalProperties":{"anyOf":[{"type":"string","maxLength":32768},{"type":"number"},{"type":"boolean"},{"type":"null"}]},"writeOnly":true},"pool":{"type":"object","additionalProperties":false,"properties":{"maximumPoolSize":{"type":"integer","minimum":1,"maximum":16},"minimumIdle":{"type":"integer","minimum":0,"maximum":16},"connectionTimeout":{"type":"integer","minimum":250,"maximum":86400000},"validationTimeout":{"type":"integer","minimum":250,"maximum":86400000},"idleTimeout":{"type":"integer","minimum":0,"maximum":86400000},"maxLifetime":{"type":"integer","minimum":0,"maximum":86400000}}}}},"driverInstall":{"type":"object","additionalProperties":false,"properties":{"groupId":{"type":"string","maxLength":200},"artifactId":{"type":"string","maxLength":200},"version":{"type":"string","maxLength":200,"default":"latest","description":"Human review resolves and pins the version. YOLO requires an explicit exact version; latest is rejected."}},"required":["groupId","artifactId"]},"testBeforeSave":{"type":"boolean","description":"YOLO requires exactly one of testBeforeSave or saveUntested. Test the unchanged draft before saving; failure saves nothing."},"saveUntested":{"type":"boolean","description":"Explicitly save without connectivity validation; required configuration and vault checks still apply."},"confirmDriverEffects":{"type":"boolean"}},"required":["requestId","purpose","connectionId","connectionName","profile"]}
```

## `dba_request_live_sql`

Request SQL using bindingId, or standalone connectionId plus exact connectionName with optional database/schema. Server-verified categories may use exact or scoped reusable human permissions. Dangerous or unknown statements always require one-time review. Session policies end with this logical MCP session. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"sql":{"type":"string","maxLength":16384,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"connectionName":{"type":"string","description":"Exact saved name matching connectionId; no browser-selected connection is inferred.","maxLength":120,"minLength":1},"autoCommit":{"type":"boolean"},"database":{"type":"string","maxLength":256},"schema":{"type":"string","maxLength":256},"connectionId":{"type":"string","description":"Standalone saved connection UUID; requires its exact connectionName. No project binding is needed.","maxLength":36,"format":"uuid","minLength":1},"bindingId":{"type":"string","description":"Project-bound target. Do not combine with connectionId/connectionName.","maxLength":36,"format":"uuid","minLength":1},"parameters":{"type":"array","maxItems":128,"description":"Positional JDBC bind values in marker order; JSON scalar types are preserved. Never interpolate SQL.","items":{"anyOf":[{"type":"string","maxLength":8192},{"type":"number"},{"type":"boolean"},{"type":"null"}]}}},"required":["requestId","sql","purpose"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName"],"not":{"required":["bindingId"]}}]}
```

## `dba_request_native_command`

Request an exact bounded native MongoDB/Redis read or supported single-target mutation. Supply either bindingId or an explicit standalone native connection UUID/name/database, and for collection commands the exact MongoDB collection. A binding fixes the connection and database. Commands are BSON Extended JSON documents (MongoDB), Redis argument arrays, or the explicitly described managed transaction/pipeline objects; Redis keys/values can use base64 objects while control arguments stay text. Bitmap/bitfield writes address at most 64 KiB; geo searches require COUNT <= 100; PFCOUNT requires write review because it can update cached cardinality. These workflows execute separately, not in pipelines/transactions. Pipelines are non-atomic: inspect all per-command receipts even after cancellation or failure. No shell syntax, JDBC translation, default target, implicit read grant, or automatic write retry. Unsupported commands fail explicitly. Uses the existing exact approval and async job lifecycle. Authorization is required; startup --yolo supplies automatic consent without bypassing validation or changing read-only semantics.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","description":"Caller-generated idempotency key for this exact submission; use the returned approvalId for polling or cancellation.","maxLength":100,"minLength":1},"purpose":{"type":"string","maxLength":2000,"minLength":1},"command":{"description":"Bounded BSON Extended JSON command document or Redis argument vector. Mongo renameCollection requires the exact selected database.collection source and a same-database to namespace; dropTarget must be false or omitted. No system/view/time-series renames or destination replacement. Reviewed collMod supports existing TTL index changes, time-series retention/granularity and capped limits, one settings family per command; retention/capped changes may permanently delete data. Redis keys/values may be {base64: canonical-padded-base64}; command names, flags, cursors, patterns and numbers must be strings. Redis bitmap/bitfield writes address at most 64 KiB, bitfields contain at most 32 operations, and GEOSEARCH/GEOSEARCHSTORE require COUNT 1..100. PFCOUNT is a reviewed write because it may change cached cardinality. These operations are not accepted inside pipelines/transactions. 128 KiB aggregate input limit; binary values at most 64 KiB, keys/fields 8 KiB. Redis also accepts {transaction: [argument arrays], watch: [{key, expected}]}: 1..32 supported mutations, at most 16 string/absent expectations and 100 total key references. expected is text/base64 or null for absence. One Cluster hash slot; exact review, no rollback or automatic retries. MongoDB accepts {transaction: [CRUD command objects]} on explicit replica_set/sharded profiles: one exact existing ordinary collection, at most 32 commands and 100 total write entries. Atomic insert/update/delete; each update/delete entry must match one document. No DDL, views, capped/time-series collections, cross-collection commands or automatic commit retry. MongoDB watch returns bounded resumable event batches. Supply its opaque nextCursor for the next explicit request; missing cursor starts at now. No pipelines, raw resume tokens, update lookup or preimages. Gaps and oversized events are explicit; no silent history skipping. Redis {pipeline: [argument arrays]} batches 1..32 verified scalar-reply mutations, scalar key metadata/membership reads, or nonnegative GETRANGE of at most 8192 bytes. All commands validated before one dispatch; at most 100 key references, one Cluster slot. Not atomic: later commands still execute after errors; no rollback/retry. No GET, aggregate/scan/stream replies, nested batches, WATCH, scripts, or connection controls. Inspect every command receipt, including unknown outcomes and omitted values. Redis streams: XREAD COUNT n STREAMS key id; XREADGROUP GROUP group consumer COUNT n STREAMS key id; XPENDING key group start end count [consumer]; XADD key id field value [...]; XACK/XDEL explicit IDs; XCLAIM key group consumer idle-ms id [...]; XAUTOCLAIM key group consumer idle-ms start COUNT n; XGROUP CREATE [MKSTREAM], CREATECONSUMER, SETID, DELCONSUMER, DESTROY; exact XTRIM MAXLEN/MINID. Counts/IDs at most 100, one stream, complete milliseconds-sequence IDs. No BLOCK, NOACK, scripts, implicit acknowledgement, replay or transaction nesting. Group reads/claims change pending delivery state and require exact write approval. Preserve deliveredIds even when payloads are truncated; do not acknowledge incomplete values as processed.","oneOf":[{"type":"object","minProperties":1,"maxProperties":64,"not":{"anyOf":[{"required":["transaction"]},{"required":["watch"]},{"maxProperties":1,"required":["pipeline"]}]}},{"type":"array","minItems":1,"maxItems":1024,"items":{"oneOf":[{"type":"string","maxLength":131072},{"type":"object","additionalProperties":false,"required":["base64"],"properties":{"base64":{"type":"string","contentEncoding":"base64","maxLength":87384}}}]}},{"type":"object","additionalProperties":false,"required":["transaction"],"properties":{"transaction":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"array","minItems":1,"maxItems":1024,"items":{"oneOf":[{"type":"string","maxLength":131072},{"type":"object","additionalProperties":false,"required":["base64"],"properties":{"base64":{"type":"string","contentEncoding":"base64","maxLength":87384}}}]}}},"watch":{"type":"array","maxItems":16,"items":{"type":"object","additionalProperties":false,"required":["key","expected"],"properties":{"key":{"oneOf":[{"type":"string","maxLength":8192},{"type":"object","additionalProperties":false,"required":["base64"],"properties":{"base64":{"type":"string","contentEncoding":"base64","maxLength":87384}}}]},"expected":{"oneOf":[{"type":"string","maxLength":65536},{"type":"object","additionalProperties":false,"required":["base64"],"properties":{"base64":{"type":"string","contentEncoding":"base64","maxLength":87384}}},{"type":"null"}]}}}}}},{"type":"object","additionalProperties":false,"required":["transaction"],"properties":{"transaction":{"type":"array","minItems":1,"maxItems":32,"items":{"oneOf":[{"type":"object","additionalProperties":false,"required":["insert","documents"],"properties":{"insert":{"type":"string","minLength":1,"maxLength":255,"description":"Must exactly match the selected collection."},"ordered":{"type":"boolean","const":true},"documents":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"object","minProperties":1}}}},{"type":"object","additionalProperties":false,"required":["update","updates"],"properties":{"update":{"type":"string","minLength":1,"maxLength":255,"description":"Must exactly match the selected collection."},"ordered":{"type":"boolean","const":true},"updates":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"object","additionalProperties":false,"required":["q","u"],"properties":{"q":{"type":"object","minProperties":1,"description":"Exact one-document filter; include expected original values for optimistic concurrency."},"collation":{"type":"object"},"u":{"type":["object","array"]},"multi":{"type":"boolean","const":false},"upsert":{"type":"boolean"},"arrayFilters":{"type":"array","maxItems":100,"items":{"type":"object"}}}}}}},{"type":"object","additionalProperties":false,"required":["delete","deletes"],"properties":{"delete":{"type":"string","minLength":1,"maxLength":255,"description":"Must exactly match the selected collection."},"ordered":{"type":"boolean","const":true},"deletes":{"type":"array","minItems":1,"maxItems":100,"items":{"type":"object","additionalProperties":false,"required":["q","limit"],"properties":{"q":{"type":"object","minProperties":1,"description":"Exact one-document filter; include expected original values for optimistic concurrency."},"collation":{"type":"object"},"limit":{"type":"integer","const":1}}}}}}]}}}},{"type":"object","additionalProperties":false,"required":["watch"],"properties":{"watch":{"type":"string","minLength":1,"maxLength":255,"description":"Exact selected collection on replica_set/sharded MongoDB; finite change batch, no initial snapshot or automatic subscription."},"cursor":{"type":"string","pattern":"^mcs1\\.","maxLength":6144,"description":"Opaque nextCursor returned to this owner/target by a previous batch; five-minute expiry, invalid after restart or target revision changes. Never grants access."},"waitMillis":{"type":"integer","minimum":0,"maximum":10000,"default":1000},"limit":{"type":"integer","minimum":1,"maximum":100,"default":100}}},{"type":"object","additionalProperties":false,"required":["pipeline"],"properties":{"pipeline":{"type":"array","minItems":1,"maxItems":32,"items":{"type":"array","minItems":1,"maxItems":1024,"items":{"oneOf":[{"type":"string","maxLength":131072},{"type":"object","additionalProperties":false,"required":["base64"],"properties":{"base64":{"type":"string","contentEncoding":"base64","maxLength":87384}}}]}}}}}]},"connectionName":{"type":"string","maxLength":120,"minLength":1},"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"database":{"type":"string","maxLength":256},"collection":{"type":"string","maxLength":256},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["requestId","purpose","command"],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_request_status`

Poll using the server-returned approvalId, not the submission's idempotency requestId; the old polling field remains a deprecated alias. Includes classification, eligible choices, exact scope, matched policy and authorization reason when available.

```json
{"type":"object","additionalProperties":false,"properties":{"requestId":{"type":"string","deprecated":true,"description":"Deprecated polling alias for approvalId. If both are supplied they must match.","maxLength":36,"minLength":1},"approvalId":{"type":"string","description":"Server-returned approvalId (also exposed as legacy id); not the submission's idempotency requestId.","maxLength":36,"format":"uuid","minLength":1}},"required":[],"anyOf":[{"required":["approvalId"]},{"required":["requestId"]}]}
```

## `dba_scan_status`

Inspect snapshot freshness without renewing activity. Optionally wait up to 5000ms for generation > afterGeneration; at most four waiters, cancellation on interruption.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"afterGeneration":{"type":"integer","minimum":0},"waitMillis":{"type":"integer","minimum":0,"maximum":5000,"default":0},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256}},"required":[],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_search_objects`

Search an authorized cached database snapshot. Supply bindingId OR standalone connectionId, exact connectionName and database, with optional schema. Standalone scopes scan on demand and expire after 30 idle minutes. Prefer generation-bound cursors.

```json
{"type":"object","additionalProperties":false,"properties":{"bindingId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"searchDefinitions":{"type":"boolean"},"limit":{"type":"integer","minimum":1,"maximum":100},"cursor":{"type":"string","maxLength":16384,"description":"Opaque five-minute continuation; repeat the same filters. Stale or expired generations must restart without a cursor."},"offset":{"type":"integer","minimum":0,"maximum":50000},"query":{"type":"string","maxLength":256},"kind":{"type":"string","maxLength":256},"connectionId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"connectionName":{"type":"string","maxLength":120,"minLength":1},"database":{"type":"string","description":"Explicit exact standalone database/catalog; no default is inferred.","maxLength":256},"schema":{"type":"string","description":"Optional exact schema; omission inventories accessible objects in the selected database.","maxLength":256}},"required":[],"oneOf":[{"required":["bindingId"],"not":{"anyOf":[{"required":["connectionId"]},{"required":["connectionName"]},{"required":["database"]},{"required":["schema"]}]}},{"required":["connectionId","connectionName","database"],"not":{"required":["bindingId"]}}]}
```

## `dba_validate_migration`

Reauthorize the exact target and recapture its bounded schema fingerprint before application. Executes no migration SQL and does not claim a rehearsal.

```json
{"type":"object","additionalProperties":false,"properties":{"planId":{"type":"string","maxLength":36,"format":"uuid","minLength":1}},"required":["planId"]}
```

## `find_affected_tests`

Recommend candidate tests from explicit file/symbol/database-name changes using bounded indexed dependency paths and confidence. Does not execute tests or claim complete test coverage.

```json
{"type":"object","additionalProperties":false,"properties":{"symbols":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":4096}},"files":{"type":"array","maxItems":100,"items":{"type":"string","minLength":1,"maxLength":4096}},"databaseChanges":{"type":"array","maxItems":100,"items":{"type":"object","additionalProperties":false,"required":["table"],"properties":{"column":{"type":"string","maxLength":128},"schema":{"type":"string","maxLength":128},"table":{"type":"string","maxLength":128},"operation":{"type":"string","enum":["add","alter","remove","rename"]},"newName":{"type":"string","maxLength":128}}}},"git":{"type":"object","additionalProperties":false,"properties":{"kind":{"type":"string","enum":["working_tree","revisions"]},"head":{"type":"string","minLength":1,"maxLength":200},"base":{"type":"string","minLength":1,"maxLength":200}}},"depth":{"type":"integer","minimum":1,"maximum":10,"default":5},"limit":{"type":"integer","minimum":1,"maximum":100,"default":50},"minConfidence":{"type":"number","minimum":0,"maximum":1,"default":0},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `find_code_smells`

Structural code smells (god class, long method, hub, cyclic files, unstable dependencies...) with the measured evidence behind every finding.

```json
{"type":"object","properties":{"scope":{"type":"string","description":"Glob narrowing the search, e.g. 'src/main/**'"},"smell":{"type":"string","description":"Single detector id, e.g. 'god-class'"},"severity":{"type":"string","enum":["info","warning","error"]},"limit":{"type":"integer","minimum":1,"maximum":200,"default":50},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `find_dead_code`

Symbols with no inbound references, with confidence tiers (reflection/DI are invisible to static analysis — read the caveats).

```json
{"type":"object","properties":{"scope":{"type":"string","description":"Glob narrowing the search, e.g. 'src/main/**'"},"kind":{"type":"string","enum":["function","class","variable"]},"limit":{"type":"integer","minimum":1,"maximum":200,"default":50},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `find_implementations`

Find direct type inheritance or indexed method override/implementation evidence. Includes confidence and coverage limits; this is not exhaustive runtime dispatch.

```json
{"type":"object","additionalProperties":false,"properties":{"symbol_id":{"type":"string","minLength":1,"maxLength":4096},"minConfidence":{"type":"number","minimum":0,"maximum":1,"default":0},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20},"cursor":{"type":"string","maxLength":16384},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["symbol_id"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `find_references`

Find indexed incoming call/reference/read/write/import relationships, with confidence and exact-versus-containing location precision. Not a complete textual occurrence search.

```json
{"type":"object","additionalProperties":false,"properties":{"symbol_id":{"type":"string","minLength":1,"maxLength":4096},"minConfidence":{"type":"number","minimum":0,"maximum":1,"default":0},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20},"cursor":{"type":"string","maxLength":16384},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["symbol_id"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_blast_score`

Auditable change-risk score (0-100) for a symbol or file: weighted factors (transitive reach, fan-in, module spread, cross-language reach, test coverage), each itemized.

```json
{"type":"object","properties":{"target":{"type":"string","description":"Symbol ID or repo-relative file path"},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["target"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_call_graph`

Callers (up) and callees (down) of a function, as a compact adjacency graph.

```json
{"type":"object","properties":{"function":{"type":"string","description":"Symbol ID of the function"},"direction":{"type":"string","enum":["up","down","both"],"default":"both"},"depth":{"type":"integer","minimum":1,"maximum":6,"default":2},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["function"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_file_outline`

List indexed declarations in one relative file, with source spans and stable symbol IDs.

```json
{"type":"object","additionalProperties":false,"properties":{"file":{"type":"string","minLength":1,"maxLength":4096},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20},"cursor":{"type":"string","maxLength":16384},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["file"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_impact_radius`

What breaks if this changes: direct and transitive dependents (or dependencies) of a symbol or file, compressed to counts and spread.

```json
{"type":"object","properties":{"target":{"type":"string","description":"Symbol ID or repo-relative file path"},"direction":{"type":"string","enum":["dependents","dependencies","both"],"default":"dependents"},"depth":{"type":"integer","minimum":1,"maximum":10,"default":3},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["target"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_symbol`

Signature, location and dependency counts for a symbol ID (from search_symbols).

```json
{"type":"object","properties":{"symbol_id":{"type":"string","description":"Full symbol ID, e.g. 'java:src/A.java#A.m/1'"},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["symbol_id"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_symbol_context`

Get declarations and optional references, distinct callers/callees, or implementations for 1..20 known symbols in one published generation. Defaults to declarations plus coverage; no source bodies. Use detail=locations when IDs and source locations suffice; it omits display name/kind/signature, never relationship confidence or coverage. One shared result/byte/work budget, not one per symbol. Follow the cursor unchanged; incomplete counts are lower bounds.

```json
{"type":"object","additionalProperties":false,"required":["symbol_ids","project"],"properties":{"symbol_ids":{"type":"array","minItems":1,"maxItems":20,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":4096}},"include":{"type":"array","minItems":1,"maxItems":5,"uniqueItems":true,"default":["declaration"],"items":{"type":"string","enum":["declaration","references","callers","callees","implementations"]}},"detail":{"type":"string","enum":["full","locations"],"default":"full"},"minConfidence":{"type":"number","minimum":0,"maximum":1,"default":0},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20},"cursor":{"type":"string","maxLength":16384},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Exact onboarded project name; required for bundled evidence.","minLength":1}},"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `get_workspace_context`

Get bounded authorized project, environment, role, connection and freshness summaries without connecting profiles, scanning catalogs or renewing activity. Discovery grants no permissions.

```json
{"type":"object","additionalProperties":false,"properties":{"cursor":{"type":"string","maxLength":16384,"description":"Opaque five-minute continuation; repeat the same filters. Stale or expired generations must restart without a cursor."},"limit":{"type":"integer","minimum":1,"maximum":100}},"required":[]}
```

## `index_status`

Observe published index freshness without renewing project TTL. Optionally wait up to 5 seconds for an index instance/generation and up to 20 exact file hashes or deletions. Timeout is not success; no read lock is held while waiting.

```json
{"type":"object","additionalProperties":false,"properties":{"indexInstanceId":{"type":"string","format":"uuid","description":"Instance from an earlier status; a replacement index is rejected."},"minGeneration":{"type":"integer","minimum":0,"description":"Minimum published generation. Requires indexInstanceId."},"waitMillis":{"type":"integer","minimum":0,"maximum":5000,"default":0},"files":{"type":"array","minItems":1,"maxItems":20,"items":{"type":"object","additionalProperties":false,"required":["path"],"properties":{"path":{"type":"string","minLength":1,"maxLength":4096},"sha256":{"type":"string","pattern":"^[a-fA-F0-9]{64}$","description":"SHA-256 of UTF-8 decoded source, re-encoded as UTF-8."},"deleted":{"type":"boolean","const":true}},"oneOf":[{"required":["sha256"],"not":{"required":["deleted"]}},{"required":["deleted"],"not":{"required":["sha256"]}}]}},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"dependentRequired":{"minGeneration":["indexInstanceId"]}}
```

## `list_projects`

Ready projects plus a separate onboarding array with initial-scan phase/counts. Only projects are queryable; polling does not renew TTL. Pass a ready name as the 'project' parameter of other tools.

```json
{ "type": "object", "properties": {} }
```

## `reindex`

Trigger re-indexing (async; poll index_status). Optional path narrows the scope.

```json
{"type":"object","properties":{"path":{"type":"string","description":"Repo-relative path to re-index; omit for full"},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}}}
```

## `remove_project`

Remove a project from this server: drops its in-memory graph and stops its file watcher. Never touches source on disk.

```json
{ "type": "object",
  "properties": {
    "project": { "type": "string", "description": "Project name (see list_projects)" } },
  "required": ["project"] }

```

## `resolve_symbol_at_position`

Resolve indexed reference-expression candidates at a 1-based position, or return containing declarations when no reference is indexed. Confidence and range precision are explicit; this is not a token-level language server.

```json
{"type":"object","additionalProperties":false,"properties":{"file":{"type":"string","minLength":1,"maxLength":4096},"line":{"type":"integer","minimum":1},"column":{"type":"integer","minimum":1,"default":1},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20},"cursor":{"type":"string","maxLength":16384},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["file","line"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `search_symbols`

Find symbols by name to obtain stable IDs and declaration locations for symbol-based navigation. Case-insensitive substring match over simple and qualified names. Optional cursors expire after five minutes; restart discovery when the index generation changes.

```json
{"type":"object","properties":{"query":{"type":"string","description":"Name or qualified-name substring, e.g. 'Service.validate'"},"kind":{"type":"string","enum":["function","class","file","variable"]},"lang":{"type":"string","description":"Language id, e.g. java, ts, js, py"},"cursor":{"type":"string","maxLength":16384,"description":"Opaque continuation from this exact query and project; does not retain a graph generation."},"limit":{"type":"integer","minimum":1,"maximum":100,"default":20},"expectedIndexInstance":{"type":"string","format":"uuid","description":"Index instance returned by index_status; protects against removal/re-onboarding."},"expectedGeneration":{"type":"integer","minimum":0,"description":"Require this exact published generation. Requires expectedIndexInstance; mismatches never execute."},"project":{"type":"string","description":"Project to query. If omitted, the first onboarded project is used; call list_projects to see available names."}},"required":["query"],"dependentRequired":{"expectedGeneration":["expectedIndexInstance"]}}
```

## `validate_database_contracts`

Compare incrementally indexed static SQL/ORM mappings with an owned authorized schema snapshot. Reports evidence, confidence and uncertainty; missing observations never prove that an object is unused or absent.

```json
{"type":"object","additionalProperties":false,"properties":{"snapshotId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"projectId":{"type":"string","maxLength":36,"format":"uuid","minLength":1},"limit":{"type":"integer","minimum":1,"maximum":100}},"required":["snapshotId"]}
```

