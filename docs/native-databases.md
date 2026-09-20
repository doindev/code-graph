# Native MongoDB and Redis; SQL Server expansion

Status: **verified bounded developer workflows, not full administration support**.
The current increment and evidence are recorded in [the acceptance report](mcp-efficiency-coverage-delivery.md).
The broader [native roadmap](native-database-delivery.md) remains incomplete.

## Connection setup

The picker now has native **MongoDB** and **Redis** entries. Their IDs are
mongodb-native and redis-native. Existing MongoDB SQL Interface and Redis Calcite
JDBC profiles are unchanged; no profile is automatically converted.

Native clients are bundled: MongoDB Java 5.12.0 and Lettuce 7.7.0.RELEASE.
Do not supply a JDBC driver, Maven coordinates or a JDBC URL for these profiles.
Enter a native endpoint, explicit default database, username and write-only
password separately. Embedded URI passwords/options are rejected. Passwords use
the existing OS vault; unsaved tests do not persist profiles or credentials.
Keep/replace/remove password semantics, connection colors and test/save workflows
are shared with the existing editor.

- MongoDB endpoint: mongodb://host:27017 or mongodb+srv://hostname.
  SCRAM-SHA-256/SCRAM-SHA-1 and platform-trusted TLS are implemented.
- Redis endpoint: redis://host:6379 or rediss://host:6379, ACL username/password,
  and an explicit logical database number.
- Native profiles default to **Read only**. Select **Allow reviewed writes** to
  make supported mutations available. This setting does not authorize agents.
- Clients are lazy, revision-bound and idle-expiring. Old clients with active
  leases retire after those leases end. Redis operations use isolated connections.
- Choose explicit topology in Network: Mongo standalone/replica set/sharded/SRV,
  or Redis standalone/Sentinel/Cluster. Additional seed endpoints must use the
  primary endpoint's scheme; at most 16 hosts total (15 additional seeds).
  Sentinel requires its exact master name. Cluster allows database 0 only.
- Disposable gates verify Mongo replica-set and sharded-router reads/observations,
  Redis three-primary Cluster scans and cross-slot rejection, and Sentinel primary
  promotion with old-cursor rejection. New operations rediscover topology; writes
  are not automatically replayed. Cursors are opaque and expire after five minutes.
- Redis Sentinel supports separate password-only or ACL authentication on all
  configured seed endpoints. Enter Sentinel credentials in **Authentication & TLS**;
  General's username/password belong only to data nodes. See
  [configuration, security and validation](redis-sentinel-auth.md).
- Custom/client-certificate TLS remains unavailable. Actual SRV DNS, production TLS and cloud authentication/topologies
  are not certified by local containers.

## Workspace and tree

Open **Native workspace** from the connection menu or create a new document while
a native connection is selected. Mongo commands are BSON Extended JSON objects;
Redis commands are argument arrays. Keys and values accept text or explicit
{"base64":"AP8="} objects. Command names, flags, numeric controls, cursors and
MATCH patterns remain text. Base64 must be canonical and padded; binary inputs
are capped at 64 KiB per value and 8 KiB per key/field, within the aggregate
128 KiB request allowance. No shell, JavaScript evaluation, or
implicit SQL translation is involved.

The database is explicit in the editor. Collection commands additionally require
an exactly matching collection name. The tree starts at the configured database,
loads collection/view/index or Redis key metadata lazily, and opens a native draft
on object activation. It does not discover every database automatically.
Metadata pages and loaded tree nodes are bounded; truncated inventories are not
evidence that an object is absent. Redis cursor traversal is live, may have
duplicates, and is not a snapshot.

Command drafts, exact targets, tab order and the active native tab participate in
session recovery. Results and jobs are not restored or automatically rerun.
Results provide JSON and a read-only, virtualized Grid view; BSON
integers/dates/ObjectIds preserve their types, and Redis values retain base64
previews with optional strict UTF-8 text. Grid cells show canonical JSON;
[missing] is distinct from null. At most 256 fields are projected. Resize and
reorder columns without rewriting the native command or triggering execution.
SQL sorting/filtering controls are not exposed for native results. Binary and
empty Redis keys have safe tree labels; activation uses their complete byte
identity, never the displayed label. Oversized keys remain explicitly omitted.

Reads execute as bounded jobs. Mutations require **Review → Apply once** in the
browser. Cancel/dismiss never applies the review. Plans expire after five minutes,
belong to one browser session, and cannot be changed after review. An error keeps
previous successful results. Cancellation is best-effort; successful native writes
are not rolled back as an atomic script and uncertain writes are never replayed.

## Current command coverage

| Transport | Implemented command families | Important boundaries |
|---|---|---|
| MongoDB | find, verified read aggregation stages, queryPlanner Explain, listCollections, listIndexes | No executable expressions, cross-namespace pipelines or automatic executionStats |
| MongoDB | insert, single-document update/delete entries, create/drop collection or verified view, collMod validators/view definitions, create/drop index | At most 100 write entries; update/delete require nonempty selectors; delete limit is 1 per entry; views use validated same-database read pipelines; no w:0 override |
| MongoDB | renameCollection | Exact same-database ordinary/capped collection rename; destination replacement, system namespaces, views and time-series collections are rejected; see [rename workflow](mongodb-collection-rename.md) |
| MongoDB | collMod collection settings | Reviewed existing TTL index changes, time-series retention/granularity and capped limits; bounded metadata preflight and data-loss warnings; see [settings workflow](mongodb-collection-settings.md) |
| Redis | GET preview, GETRANGE, TYPE, TTL/PTTL, cardinality/existence/score reads, HGET, LINDEX | Large values are previews, not complete editable payloads |
| Redis | SCAN/HSCAN/SSCAN/ZSCAN, bounded LRANGE/ZRANGE/ZREVRANGE and XRANGE/XREVRANGE | No automatic KEYS, HGETALL or whole-container materialization; COUNT is managed by the application |
| Redis | SET, DEL/UNLINK, RENAME/RENAMENX, EXPIRE/PEXPIRE/PERSIST, HSET/HDEL, LPUSH/RPUSH, SADD/SREM, ZADD/ZREM | SET supports NX/XX with EX/PX or KEEPTTL and reports applied=false on an unmet condition; text/base64 keys and values; graphical value editing and transactions remain incomplete |

Unknown or unsupported commands fail explicitly. Classification does not grant
execution rights. This is not a claim of support for every command in these families.
Exact argument validation still applies.

Time-series collections appear beneath Collections; their internal system bucket
collections are omitted. Collection-setting changes execute from the native command
workspace or MCP, not a dedicated graphical settings form. The adapter checks the
actual collection kind before writing; connectivity alone does not enable arbitrary
`collMod` options or infrastructure changes.

## MCP and application bindings

dba_request_native_command uses the existing request/status/cancel/job-release
lifecycle. Supply either:

- connectionId, exact connectionName, database; or
- bindingId, with no connection/database/schema overrides.

Add collection for Mongo collection operations. Include requestId, purpose and
command. For example, command can be {"find":"items","filter":{},"limit":100}
or ["HSCAN","cache-key","0"]. For a binary value, use
["SET","cache-key",{"base64":"AP8="}]; this is still a reviewed mutation.

Bindings preserve application, canonical environment, logical role and purpose.
A native binding fixes the database and has no SQL schema. Disabled/unloaded
bindings and stale profile/binding revisions cannot execute. Native profiles use
bounded native catalog adapters, never JDBC queries. Authorized `dba_capture_schema`,
`dba_compare_schemas`, catalog refresh/search/properties and contract validation
support native observations. Standalone targets need no project binding.

Mongo observations contain collection/view definitions, validators, indexes and
explicit declared field types. Optional `sampleLimit` (0 by default; at most 32)
records only field names/types, never record values. Sampling is incomplete evidence,
not a schema declaration. Redis observes bounded key/type/prefix/TTL classes, never
values; SCAN does not prove key absence. Comparisons ignore elapsed TTL milliseconds.
Static Spring Data/Mongoose and Redis prefix/type mappings feed contract checks;
dynamic names remain unknown. SQL grants never authorize native observations;
existing native catalog permission or explicit startup YOLO is required. Reusable
native environment read policies remain unsupported.

Normal native operations use exact one-time review. SQL reusable grants do not
automatically become native permissions. With startup --yolo, the same validated
operations are automatically authorized and audited; read-only profile settings,
scope checks and resource caps still apply. Reduced headless mode omits native
approval-dependent tools. Capabilities never authorize execution.

dba_get_capabilities reports native command coverage instead of JDBC migrations.
With authorized live:true it observes the server version; configuration alone is
not proof of version/capability support. Both exact bindings and standalone
targets are supported. Template discovery makes no connection.

Browser endpoints under /api/dba/native:

- POST /tree — bounded lazy tree load.
- POST /prepare — classify/read or retain a browser-owned mutation review.
- POST /execute — reads only; mutations cannot bypass review here.
- POST /apply — consume an unchanged, owned planId.
- DELETE /reviews/{id} — discard a review.

All retain normal loopback, Host/Origin, session, ownership and CSRF protections.

## Memory and recovery

Native jobs, including unsaved connection tests, reserve 64 MiB of the shared DBA accounting allowance while active,
then the ordinary retained-job allowance after completion. Browser reviews reserve
2 MiB each, with at most eight retained for five minutes. Native clients, including
retired leased clients, are limited to sixteen.

Mongo cursor batches contain at most sixteen documents and retain the server's
16 MiB batch ceiling. Documents remain raw before size validation. Documents above 256 KiB are
omitted with guidance to narrow projection, rather than expanding a large BSON
document into millions of Java objects. Redis closes an operation connection after
8 MiB cumulative inbound wire traffic. A second guard checks plaintext RESP before
driver decoding: 32 nesting levels, 32,768 children per aggregate and 65,536
values per operation connection. Oversized declared arrays/bulk strings and
unverified streamed envelopes are rejected before driver allocation.
A scan page exceeding retention caps returns
a refinement warning instead of a cursor that would silently skip dropped entries.

These are accounting and defensive bounds, **not a hard total-process RAM limit**.
JVM, TLS, drivers, networking and OS buffers remain additional overhead. The DBA
allowance is separate from the graph/cache budget.

On a write transport error or cancellation, inspect actual database state before
retrying. An accepted cancellation request is not proof that no write occurred.
Connection removal does not drop Mongo databases/collections or Redis databases,
and does not remove Docker containers. Open native workspaces stop execution and
retain their command drafts when their connection disappears.

## SQL Server

The existing Microsoft JDBC profile remains the SQL Server transport. Disposable
SQL Server 2022 Developer tests with Microsoft JDBC 13.4.0.jre11 verify populated
tables, views, procedures/functions and execution, estimated plans, schema capture
and explicit cross-database isolation.

Object-DDL inspection now uses bounded, parameterized SQL Server catalogs.
Views/routines/triggers expose available module definitions; encrypted or
inaccessible definitions remain unavailable. Table/constraint rows explicitly
identify their **partial** coverage and must not be treated as complete recreate
scripts. SQL Server 2022 ordinary disk tables support reviewed column/name/type/default/
nullability/comment, key/constraint/index changes and creation. Special tables
(temporal, ledger, graph, CDC/replicated, partitioned, memory-optimized, sparse,
encrypted or user-defined-type cases) remain read-only. Generated-column changes,
owner/schema moves and infrastructure settings are unavailable.

Designer Apply rechecks the fingerprint under an application lock and a database
lock, uses XACT_ABORT and a bounded lock timeout, and supports rollback for the
verified ordinary-table operation subset. Migration preparation has SQL Server
identifier/type support and conservatively reports transaction guarantees separately.
Native column/key/index/constraint capture supports structured comparisons.
See [the designer](dba-table-designer.md) for recovery. Integrated/Kerberos/Entra
auth certification, SQL Agent and backup/restore remain incomplete. Azure SQL is
not certified by a local SQL Server container.

## Reproducible validation

Run from the repository root using JDK 25, Maven and Docker:

    ./code-graph-dba/test-native-vendors.ps1 -Engine mongodb
    ./code-graph-dba/test-native-vendors.ps1 -Engine redis
    ./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
    ./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
    ./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel
    ./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth password -BuildRoot <isolated-source>
    ./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot <isolated-source>
    ./code-graph-dba/test-redis-topologies.ps1 -Topology cluster
    ./code-graph-dba/test-native-vendors.ps1 -Engine mongodb -Performance
    ./code-graph-dba/test-native-vendors.ps1 -Engine redis -Performance
    ./code-graph-dba/test-sqlserver-native.ps1 -AcceptDeveloperEula -DriverJar <existing-microsoft-jdbc-jar>
    mvn test -Djava.awt.headless=true

The SQL Server switch is explicit EULA acceptance for local testing. The scripts
create uniquely named, ownership-labelled containers on loopback ephemeral ports,
remove owned containers and anonymous volumes in finally, and remove newly
introduced unused images. Pre-existing images/containers are preserved. No broad
Docker prune is used.

The native fixture gates use a 256 MiB test heap and a 64 MiB direct-buffer cap.
The optional performance switch runs three independent JVMs, each with 12
warmups and 120 samples per direct-adapter/managed-job path. JSON reports include
raw timings, p50/p95/p99, throughput, payload sizes, heap/GC/direct-buffer readings
and post-release accounting under target/native-performance/{fixture-owner}.
The benchmark asserts the heap cap rather than trusting launcher arguments.
Its 128 MiB DBA allowance is accounting, not total process RAM. It does not
measure OS-cold storage, process RSS, disk I/O, topology failover, concurrent graph
load or approval/HTTP latency; those remain separate incomplete acceptance gates.
See [the performance checkpoint](native-database-performance.md) for measured
before/after results and limitations.

For browser tests, assemble the module's test-lib dependencies with Maven, then use
code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>.
DBA_BROWSER_SUITE=native selects the native suite; omit it for all browser suites.
Live vendor tests skip when their owned fixture environment is absent; a skipped
test is not live certification. The tested local topology subset does not certify every production deployment.
Infrastructure, binary editing, streaming/subscription and cross-platform gates
remain in the broader checklist.
