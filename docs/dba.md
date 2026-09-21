# DBA implementation status and preview

For browser row editing, paging, downloads, API routes, and recovery, see
[Editable, pageable Data Grids](dba-editable-grids.md). These additions do not
expand MCP permissions or change agent result limits.

**Startup override:** explicitly launching with `--dba --yolo` (or `cgraph --yolo`)
automatically authorizes local-agent operations without existing grants or review.
All normal approval descriptions below assume YOLO is off. Validation, audit,
resource limits and browser confirmations still apply; see [YOLO setup and recovery](yolo.md).

The `/dba` JDBC administration plan is **not fully implemented**. This change delivers an
opt-in, authenticated UI and a restricted-read foundation. It must not be presented as the
completed administration/agent suite. Authenticated restricted-read DBA MCP tools are enabled;
agent writes require human approval, with [scoped reusable permissions](reusable-approvals.md)
only for verified new-object creation; destructive/unknown SQL stays one-time. Paired editor operations remain unavailable. Human browser SQL supports
JDBC DDL/DML and statement-aware scripts with savepoint-backed error decisions, commit-on-success,
or explicit auto-commit; see [execution behavior and limitations](dba-connections.md#script-files-and-execution-targets).

## Available now

Native MongoDB/Redis transport coverage and SQL Server additions are documented
separately in [native databases](native-databases.md). They do not inherit JDBC-only
schema tools or SQL permissions; the full native administration plan remains incomplete.

- `/dba` on the existing native Java UI server; existing graph routes and transports remain.
- Empty graph workspaces work. DBA startup does not connect saved database profiles.
- Local-only requests, validated Host/Origin, automatic local browser sessions, HttpOnly
  SameSite browser sessions, CSRF checks, session ownership, and one-hour session expiry.
- Atomic profile persistence, exclusive runtime ownership of a data directory, credential
  references hidden from responses, and write-only browser password submission.
- Windows Credential Manager and macOS Keychain native adapters, and Linux Secret Service
  integration through `secret-tool`. No plaintext fallback when vault access fails.
- Embedded Maven driver installation, multi-JAR classpaths, one isolated classloader per profile, lazy HikariCP pools,
  connection tests, schema/table/column metadata, and explicit profile editing/removal.
- Human statement-aware SQL execution and separate restricted agent SELECT execution, positional
  prepared parameters, savepoint-backed Continue/Skip/Cancel decisions, a bounded queue,
  cancellation requests, result expiry, row/byte/cell caps, and duplicate column-label preservation.
- A dark vanilla UI with resizable navigation, reorderable SQL tabs, column views, comment
  toggling, reusable virtualized Data Grids, direct/modal column reordering, and live resource settings.
- Overlap protection prevents indexing the DBA data directory or its ancestors/descendants,
  including subsequent project onboarding. Configuration must not be stored in indexed roots.

## Start explicitly

Build the runtime first; no server is started by this build:

```powershell
mvn -pl code-graph-mcp-http -am package
```

Example HTTP launch, with no code projects onboarded:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -cp "code-graph-mcp-http\target\classes;code-graph-mcp-http\target\lib\*" `
  io.doindev.codegraph.mcp.http.HttpMain `
  --port 3000 --viz 8137 --viz-admin `
  --dba --dba-dir "C:\CodeGraphData\dba" `
  --dba-memory 256m --dba-concurrency 4
```

Open `http://localhost:8137/dba` directly; there is no browser token or login dialog.
An automatic HttpOnly/SameSite session retains CSRF, Host/Origin validation, ownership and
one-hour expiration. **Local users/processes can administer connections**: browser-origin
protections do not authenticate local processes. The toolbar settings gear contains **RAM**
and **Agent access**, plus **Driver downloads** for persistent corporate Maven/mirror and
certificate preferences. There is no End session button; session expiration and the protected
logout API remain unchanged. Existing obsolete `browser-token` files are removed on UI startup.
Local MCP clients need no token or Agent access setup. Both HTTP listeners bind to
`127.0.0.1`, and token-free HTTP/stdio clients share a built-in **Trusted local agents**
identity. Database operation approvals and grants remain enforced; local transport trust
is not blanket SQL authorization. Optional named tokens keep separate identities.

See [connection setup, drivers, Snowflake and test-result behavior](dba-connections.md).

The same flags work with the stdio entry point and `--viz`. See headless/agent setup below.
Stdio can own a DBA runtime; it is not yet an attachment bridge to another running owner.

| Startup flag | Default | Meaning |
|---|---|---|
| `--dba` | off | Enable the preview runtime/page |
| `--dba-dir PATH` | `${user.home}/.code-graph/dba` | Persistent, owner-private profile directory; must not overlap indexed roots |
| `--dba-memory SIZE` | `256m` | Shared DBA admission/accounting allowance; whole `m` or `g`, minimum `32m` |
| `--dba-concurrency N` | `4` | Running query/test/metadata jobs, 1–16; queue holds at most 16 jobs |
| `--dba-ui-rows N` | `1000` | UI result row cap, 1–10,000 |
| `--dba-agent-rows N` | `100` | Agent result cap; effective maximum is always 100, even if a legacy configuration specifies a larger value |
| `--dba-timeout N` | `30` | Query deadline in seconds, 1–300 |
| `--dba-decision-timeout N` | `60` | Human script-error decision timeout in seconds, 10–600 |
| `--dba-approval-mode MODE` | `auto` | `auto`, `browser`, `desktop`, `none`; startup-only human approval routing. See [approval broker](approval-broker.md). |

DBA-specific settings without `--dba` and unknown DBA flags are rejected. Memory, concurrency,
UI row cap, query timeout, and decision timeout can be changed through the RAM dialog for the current run; these
changes are not persisted. Profile settings and [driver download settings](dba-driver-downloads.md)
are persisted separately. Native file pickers have a fixed 90-second deadline. The graph's memory/TTL settings
remain independent.

Download startup overrides: `--dba-driver-download embedded|maven`,
`--dba-maven-command PATH`, `--dba-maven-settings PATH`, `--dba-maven-cert PATH`, and
`--dba-maven-insecure-tls true|false` (unsafe, default false). Omit overrides to use the
saved `driverDownloads` section of `<dba-dir>/settings.json`.

Use a new or empty dedicated data directory on first startup. An ownership marker is created
for subsequent runs. The runtime refuses to adopt a non-empty, unowned directory rather than
changing permissions on unrelated user data.

## Credentials, drivers, and query restrictions

Passwords submitted through the browser are sent only to the authenticated local endpoint
and stored in the OS vault, never returned in profile responses. Extra JDBC properties may
be supplied through the API's write-only `secretProperties` object and are stored in the same
vault record. Username/URL are non-secret profile fields: do not put credentials in them.
Known credential-bearing URL forms are rejected, but the application cannot recognize every
vendor-specific encoding. Secret-bearing URLs are unsupported.

Secret configuration is bounded at 64 KiB and split into 2,400-byte OS-vault records for
cross-platform compatibility. Existing single-record credentials remain readable. Editing
supports keep/replace/remove; deleting a profile removes its credential chunks.
`${ENV_VAR_NAME}` placeholders resolve at connection creation, fail if missing, and
are never written back expanded. Linux requires a running, unlocked Secret Service and the
`secret-tool` executable. The Linux adapter has been tested against an actual Secret Service
inside Docker; the macOS adapter still requires real-host validation.

Choose a trusted local driver JAR and its class, such as `org.postgresql.Driver`. Classloader
isolation prevents version mixing; it is **not a sandbox for malicious drivers**. Dependency-rich
driver bundles are installed explicitly through embedded Maven Resolver, with pinned versions
and SHA-256 integrity records. No installed `mvn` command is required at runtime. Pools default
to two physical connections per profile, minimum idle zero, and a 60-second idle timeout;
the connection editor exposes bounded overrides.
Removing/editing a profile requires its jobs to have finished. Removal closes its pool and
attempts classloader-owned DriverManager deregistration.

The agent path accepts one SELECT, optionally ending in a semicolon. It rejects write statements,
multiple statements, CTEs, SELECT INTO, locking clauses, explicit casts, and function calls.
It is intentionally not a complete SQL policy engine. Use a least-privilege read-only database
account. PostgreSQL execution requests read-only transactions and bounded fetching; other
drivers may implement read-only/fetch hints differently and are not certified for equivalent
resource or safety behavior. PostgreSQL 16 has passed the initial Docker-backed integration
test; this does not complete the broader policy, paging-pressure, or performance test matrix.

Use `?` parameters and provide a JSON array in the editor. Results are held only in memory;
they are never spooled to disk. The agent runner rolls back after each read. Human Run SQL
splits scripts into bounded execution units, commits successful transactional runs, and also
accepts explicit auto-commit for administrative commands. A transactional failure rolls back
to its per-unit savepoint and pauses for a bounded browser decision when safe. Cancellation is
best effort through JDBC and cannot undo committed effects. Human errors
include bounded database messages with credential redaction; agent errors remain sanitized.

## Resource limits and accounting

- Each queued/running/retained job reserves a conservative 12 MiB admission slot; at most 32
  jobs are retained, further constrained by the allowance. This is an estimate, not a measured
  Java object-size accounting system.
- UI query data collection stops below 4 MiB with serialization headroom, and the final
  serialized result must fit 4 MiB. Also capped: 256 columns, 16,384 cells, 8,192 characters per
  cell, and the configured row count. Binary values are marked as omitted previews.
- Agent collection uses a separate 1 MiB ceiling (with serialization headroom) and a hard
  100-row maximum across SELECT, metadata and definition results. Smaller configured agent
  limits are honored. Each result declares truncation. An agent job reserves the same conservative
  12 MiB admission slot as a browser job; this is not a claim of exact retained heap size.
- Results explicitly distinguish row/byte truncation from shortened cell previews. A row cap
  does not bound the database's scan/sort work or the driver's own internal buffers.
- Completed backend jobs expire after five minutes or loss of their browser owner. The UI
  releases backend results after retrieval and caps local retention at 12 tabs and a 32 MiB
  serialized-size-based estimate. Browser DOM rendering is virtualized.
- Settings report reserved bytes, serialized result bytes, running/queued jobs, pools, and
  JVM heap usage. Parser temporaries, metadata fetched internally by a driver, pool/driver
  overhead, native allocations, and browser memory are not hard-capped by `--dba-memory`.
- Budget reduction blocks new admissions when existing reservations exceed the new allowance;
  it does not silently abort active work. Existing retained results expire or can be released.

## REST preview contract

All paths are under `/api/dba`. Non-GET requests require same-origin `Origin`, JSON content
type, and—except bootstrap—the session's `X-Dba-CSRF` token. JSON payloads are capped at 128 KiB
unless noted below. The separate JAR-only upload endpoint accepts `application/java-archive`,
at most 128 MiB per JAR.

| Method/path | Purpose |
|---|---|
| `POST /bootstrap` | Automatically create/reuse a local browser session; returns an HttpOnly cookie and `{csrf}` |
| `GET /templates` | Ordered database templates and initial property catalogs |
| `POST /setup/{operation}` | Bounded driver status/install/inspect, descriptors, file selection, key validation and unsaved draft tests; see connection guide |
| `POST /drivers/import` | JAR-only browser upload fallback; never accepts private keys |
| `GET /session` | CSRF token, settings, telemetry and explicit feature availability |
| `GET/PUT /workspace` | Read/replace the current browser session's memory-only Script workspace snapshot (16 MiB request cap; 12 tabs; 1 MiB UTF-8 text per tab) |
| `POST /logout` | End browser session and request job cancellation |
| `GET/PUT /settings` | Inspect/change live limits (`memory`, `concurrency`, `uiRows`, `agentRows`, `timeoutSeconds`, `decisionTimeoutSeconds`) |
| `GET/POST /connections` | List/create saved profiles |
| `GET/PUT/DELETE /connections/{id}` | Read, replace, or remove a profile |
| `POST /connections/test` | Start test job with `{connectionId}` |
| `GET /connections/{id}/state` | Current pool connection state and active-job flag; no connection is opened |
| `POST /connections/{id}/connect` | Start an owned asynchronous connection job; only when disconnected and idle |
| `POST /connections/{id}/reconnect` | Replace the established idle pool and start an owned connection job |
| `POST /connections/{id}/disconnect` | Close the established idle pool; retain the profile, grants, and Script tabs |
| `POST /metadata/node` | Start metadata job with `{connectionId, schema?, table?}` |
| `POST /query/execute` | Start human SQL with `{connectionId, sql, parameters: [], autoCommit?: false, rowLimit?: 200}`; commit on success by default. The optional rowLimit is an integer from 1 through the configured UI row ceiling; byte/cell limits may return fewer rows. Table grids add textual `database` for a single validated SELECT with explicit targeting and rollback-only read execution. |
| `POST /metadata/table-query` | Browser-only asynchronous preparation from `{connectionId, parent, key}`; verifies a Tables child and returns quoted SELECT SQL and explicit database context |
| `POST /query/explain` | Estimated PostgreSQL SELECT plan, never `EXPLAIN ANALYZE` |
| `POST /metadata/ddl` | PostgreSQL catalog definition fragments with `{connectionId, schema, object}` |
| `GET/POST /agents` | List grants or create a named read-only agent; creation returns its token once |
| `DELETE /agents/{id}` | Revoke agent and request cancellation of its active jobs |
| `GET/DELETE /jobs/{id}` | Poll or release a completed, session-owned job |
| `POST /jobs/{id}/cancel` | Request cancellation |
| `POST /jobs/{id}/decision` | Resolve the current owned error decision with `{decisionId, action}` where action is `cancel`, `continue`, or `skip_similar` |

Profile creation requires `name`, `url`, `driverClass`, and `jars` (legacy `jar` remains accepted),
plus a matching temporary test receipt or explicit `saveUntested:true`. Optional configuration
includes `templateId`, `username`, `password`, `properties`, `secretProperties`, and `pool`.
Connection tests do not create profiles, vault entries or persisted test data. Pool defaults
remain read-only, but human SQL explicitly opens a writable session and discards it after the
run. Restricted agent read tools enforce their separate read-only path, regardless of profile
flags; human-approved live SQL uses the separately reviewed approval path.

## Headless MCP and agent access

Omit `--viz` to run without the main UI. DBA remains opt-in with `--dba`; graph tools are unchanged.
The default approval mode uses a native consent prompt when an interactive desktop is available,
and a temporary request-scoped browser site for complex reviews. Truly headless execution or
`--dba-approval-mode none` exposes only the restricted safe read/catalog/plan toolset.
Authentication, object authorization and database permissions remain mandatory.
See [approval modes, APIs and platform validation](approval-broker.md).

Local clients connect without configuration. The built-in local identity is created lazily
on the first DBA-capable MCP connection/call and persists without a token. It exposes only
connection IDs/names and binding summaries by default so a client can request access.
Profile inspection, connection tests, database reads and mutations still use the reviewed
permission/approval workflows. Requesting SQL review needs no separate local-agent grant.
With no human approval channel, approval-dependent tools remain unavailable.

All token-free clients share read policies, approval/job ownership, request IDs and created
connection history; they are not cryptographically distinguishable. Persistent read policies
can be revoked in Project databases. The built-in identity cannot be deleted. Do not rely on
client-supplied names to isolate privileges. For separate named identities, optionally prepare
profiles and grants in `/dba` before a headless run:

1. Save the PostgreSQL connection using a least-privilege account and a trusted driver JAR.
2. Open **Agent access**, choose a connection and an exact schema, then enter exact object names
   (one per line, without SQL quoting). Create a separate token for each agent.
3. Copy the token once to the MCP client's secret/environment configuration. Only its SHA-256
   hash is persisted in owner-private `agents.json`. It is never returned by MCP or by grant listing.
   Closing the dialog clears the displayed token. No tokens are kept in browser local storage.
4. Stop the UI-owning process before launching a headless owner with the same `--dba-dir`.
   Profiles and grants persist; jobs, results, connections and browser sessions do not.

HTTP example:

```powershell
java --enable-native-access=ALL-UNNAMED `
  -cp "code-graph-mcp-http\target\classes;code-graph-mcp-http\target\lib\*" `
  io.doindev.codegraph.mcp.http.HttpMain `
  --port 3000 --dba --dba-dir "C:\CodeGraphData\dba" --dba-agent-rows 100
```

No Authorization header is needed for trusted local access. To use an optional named identity,
send `Authorization: Bearer <agent token>` on **every** request, including initialization,
polling and SSE. Invalid supplied tokens are rejected. All HTTP MCP access, even graph-only,
requires a loopback peer, a loopback Host and a matching Origin if supplied; proxy forwarding
headers are not trusted. Cross-site browser requests are rejected. Sessions remain bound to
their initial local/named identity and expire after one hour. Missing/expired sessions return
404 for reinitialization; a different identity replaying a valid session receives 403.

For stdio, leave `CODE_GRAPH_DBA_AGENT_TOKEN` unset to use the shared local identity, or set it
for an optional named identity. Use `io.doindev.codegraph.mcp.Main` with the same `--dba`/`--dba-dir`
arguments. Do not place tokens in command or tool arguments. Stdio uses one identity per process.
The directory has one exclusive owner; the future stdio attachment bridge is not implemented.
Headless operation uses the existing OS vault and fails closed if credentials are unavailable;
it does not fall back to plaintext credentials or automatically grant access.

| MCP tool | Behavior |
|---|---|
| `dba_list_connections` | Local clients discover opaque IDs/names; optional named identities retain grant filtering. No JDBC URLs or credentials; discovery grants no database access. |
| `dba_get_metadata` | Granted object roster, or bounded column metadata for an exact schema/object |
| `dba_get_object_ddl` | Read PostgreSQL catalog definition fragments; never execute DDL |
| `dba_explain_query` | `EXPLAIN (ANALYZE FALSE, FORMAT JSON)` for an authorized SELECT |
| `dba_analyze_query_plan` | Same non-executing estimated plan with interpretation guidance |
| `dba_execute_read_query` | One authorized SELECT as a bounded asynchronous job; prepared `?` parameters |
| `dba_job_status` | Own job state/result; failed or cancelled jobs return MCP `isError: true` |
| `dba_cancel_job` | Best-effort JDBC cancellation of an owned job |
| `dba_release_job` | Release an owned completed result immediately |

Job-producing calls return an ID; poll it with `{jobId}` and release after consuming the result.
Query arguments are `{connectionName, connectionId?: "...", sql, parameters?: []}`.
Metadata/definition arguments require `connectionName`, with optional matching stable ID,
`schema`, and `object`; metadata may omit schema/object to list granted names. Names and IDs
must both match the agent grant. Rename requires grant review/reissue; no UI target is inferred.
Agents cannot create/edit/remove connections, issue grants, retrieve credentials, load drivers,
execute writes or DDL, run SQL `ANALYZE`/`EXPLAIN ANALYZE`, or control browser editors.

Agent queries require explicitly schema-qualified granted objects, including every referenced
object in nested SELECTs. Agent PostgreSQL transactions use a `pg_catalog`-only search path.
Functions, CTEs, explicit casts, session-control statements and unknown
grammar fail closed. Metadata exposes only the granted roster/objects. Revocation is immediate
for new requests and result access; active jobs receive cancellation, not a promise of instant
database interruption. Completed agent results expire after five minutes. Token holders for
the same agent share that agent's job ownership; use separate identities when isolation matters.

SELECT execution and catalog operations can still invoke database-defined behavior through
views, types, operators and policies. Application object grants do not recursively authorize
view dependencies or bound all external side effects. Read-only transactions and least-privilege
accounts are complementary safeguards, not a sandbox for arbitrary database code. PostgreSQL
is the only verified agent query/plan/DDL engine. UI generic JDBC reads retain their earlier limits.

The **Explain** button never executes the query. Plan costs are estimates, not measured runtime;
a sequential scan alone does not establish a missing index. **DDL** returns catalog fragments
for tables, views, materialized views and indexes. These are explicitly **not a complete restore
script**: identity/generated attributes, ownership, grants, partitioning, storage and dependent
objects are not reconstructed. Row and cell truncation also apply to plan/definition output.

## Validation and remaining gates

See [restricted-read validation evidence](dba-validation.md) for tested scenarios and explicit
limits on what the results establish.

Run local tests:

```powershell
mvn -pl code-graph-mcp-http -am test
mvn -pl code-graph-dba -am test "-Ddba.vault.integration=true"
```

The opt-in vault test writes, reads, replaces, and removes **only a uniquely named test-owned
credential**. It is not enabled in ordinary test runs. Repeat it on each supported OS.

Current evidence: module/HTTP tests pass against disposable H2 databases, the application
reactor regression tests pass, the Windows native vault round-trip passes, and PostgreSQL 16
passes capped reads, metadata, read-only enforcement and cancellation checks. A Docker Linux
fixture passes real Secret Service create/read/replace/delete, locked-vault rejection and
unavailable-service rejection. macOS validation and Windows locked-vault scenarios remain open.

Reproduce the container checks from PowerShell with a running Docker Linux engine:

```powershell
powershell -NoProfile -File code-graph-dba/test-postgres.ps1
mvn -pl code-graph-dba dependency:copy-dependencies "-DincludeScope=test" "-DoutputDirectory=target/test-lib"
powershell -NoProfile -File code-graph-dba/test-linux-vault.ps1
```

To include isolated Edge/Playwright UI checks against the disposable PostgreSQL database:

```powershell
powershell -NoProfile -File code-graph-dba/test-postgres.ps1 -Browser -NodeModules "PATH\TO\node_modules"
```

The supplied module directory must contain Playwright and Microsoft Edge must be installed.
`test-browser.ps1` can also run the H2 browser fixture after compiling tests and copying test
dependencies. It launches a hidden, test-only Java process and stops it after browser checks.
Test logs/screenshots are retained under the ignored `code-graph-dba/target` directory.

The scripts use uniquely named, labeled test resources, not existing database containers.
PostgreSQL binds a random **loopback-only** port and uses disposable tmpfs database storage.
The Linux vault container has no host vault mounted. Cleanup verifies ownership labels before
removing test containers/images. Docker's shared image/build cache is not pruned. The JDBC
PostgreSQL driver dependency is test-scoped; production drivers are installed explicitly.

`BrowserFixture` and `browser-smoke.cjs` provide a disposable loopback UI test using Playwright
and headless Edge. They never launch the production application and clean their owned test
directory when stopped. The fixture's test-only stop route is not part of the application.

Still required before the original plan is complete:

- Cross-platform desktop picker validation and broader driver/proxy compatibility testing.
- Validated per-connection proxy routing and vendor capability reporting.
- Persistent authenticated agent identities and object/function-scoped policies, PostgreSQL
  plan thresholds, DML/selected DDL, affected-row rollback, and adversarial SQL-policy tests.
- Richer transaction controls, uncertain commit-outcome handling, SQL formatting, catalog
  completion, populated output/log/variables panels, and richer metadata.
- Write-capable DBA MCP policies, paired editor sessions, authenticated editor SSE, edit
  revisions, presence indicators, and the stdio attachment bridge. The restricted read catalog,
  per-request authentication, exact-object read grants and job ownership are implemented.
- Expanded PostgreSQL integration tests, real macOS vault tests, Windows locked-vault tests,
  mixed graph/DBA memory-pressure tests, and three independent performance runs with raw data.

Do not enable unattended writes or claim the complete suite is production-ready based on
the restricted-read milestone's tests.
## Catalog navigation

The connection tree now uses lazy, vendor-specific database/schema/object branches, including
Oracle's Queues, Packages, Java, Jobs and Scheduler groups. Every node has a Refresh menu;
existing expanded descendants are restored where their catalog identities still exist.
See the [capability matrix and metadata API](dba-catalog-tree.md) for all supported templates,
bounds, cross-database behavior and validation limits.

## Project database context

See [project/database bindings, activity-driven catalog scans, stored versions and approved live SQL](project-database-context.md). All connection templates participate through native metadata adapters or an explicitly partial JDBC fallback. The Project databases settings page manages bindings and agent grants; the Approvals toolbar button reviews each new live request.
