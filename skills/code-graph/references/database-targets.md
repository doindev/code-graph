# Select the database deliberately

Use actual MCP schemas, not remembered argument shapes. Start with
`get_workspace_context` or `dba_list_project_databases`; inspect capabilities
and effective permissions only for the relevant target. Do not connect all
profiles or scan every environment merely to orient yourself.

A project binding fixes application, environment (`local`, `dev`, `test`,
`stage`, `prod`), logical role, connection UUID, database and schema. Use its
`bindingId`; do not override its scope. For a standalone database, use the stable
connection UUID and exact name, with explicit database/schema where supported.
No project is mandatory. Never infer a target from the active browser selection.
When several bindings match, resolve the ambiguity before submitting work.

`dba_list_templates` returns recipes, not proof a driver is installed or an
operation is verified. `dba_get_capabilities` distinguishes cached observations
from unknown servers. `live: true`, when advertised and authorized, returns a job
for one target's actual JDBC metadata or native server version. A PostgreSQL-compatible product is not
automatically certified for all PostgreSQL administration.

Cached catalog tools also accept a standalone connection UUID, exact name, explicit
database/catalog, and optional schema when advertised. Do not invent a project
binding. Standalone code-reference searches additionally need an explicit indexed
project. Cache sharing never shares permissions; passive status does not renew
its 30-minute idle retention.

Catalog data is cached, scoped and potentially incomplete. Inspect generation,
scan time, coverage, truncation and warnings. Request `dba_refresh_catalog` only
within authorized scope, then use bounded status waits/polling. Listing and
polling should not be used to prevent idle cleanup. An inaccessible object or
partial inventory cannot prove an object was removed.

Connection creation/removal is application configuration, not Docker lifecycle.
An agent-created connection grants its creator no database permissions. Discover
its stable ID through the creation result/created-connections tool. Driver
installation and secret submission require the advertised review workflow.
Prefer human credential entry: write-only MCP arguments can remain in the host's
task transcript. Never ask the server to return credentials or private-key files.

## Native MongoDB and Redis

Distinguish native templates (`mongodb-native`, `redis-native`) from legacy
Mongo SQL Interface/Redis Calcite JDBC profiles. Discover capabilities first;
do not send SQL, JavaScript shell snippets, or redis-cli command strings to native
connections. Native clients are bundled and require no JDBC driver installation.

When advertised, `dba_request_native_command` accepts an Extended JSON command
object for MongoDB or a string-argument array for Redis. Supply the exact binding
or standalone UUID/name/database; add `collection` matching Mongo collection
commands. Native bindings have no SQL schema. A database or collection discovered
in metadata does not grant permission to use it.

Native operations currently use exact one-time review, or automatic authorization
only when the server was started with YOLO. Existing reusable SQL permissions do
not authorize native commands. Read-only profiles still prohibit writes under
YOLO. Follow the returned request/job IDs for status, cancellation and release.

Preserve BSON Extended JSON types: do not round `$numberLong` values through
JavaScript numbers or flatten missing/null into the same value. Redis base64
previews may be truncated; do not write a preview back as if it were a complete
value. Use projections, ranges or supported scans to keep results bounded.
SCAN is live and may repeat entries; a truncated page without a continuation
requires refinement rather than guessing a cursor.

Conditional Redis SET reports `applied: false` when its condition did not match.
That is not a transport failure and should not trigger an unconditional retry.
Native writes are not an atomic script. On partial/unknown outcomes, reconcile
with authorized reads before requesting another mutation. Native schema capture and cached catalogs, when advertised, contain bounded
definitions/observations, never a complete document/key inventory. Sampling is
opt-in; field types observed in documents are not declared validator requirements.
Redis prefixes/types/TTL classes are conventions, not a relational schema.
Do not infer removed objects from missing samples or compare volatile TTL
milliseconds as schema changes. Reusable policies, transactions and infrastructure
capabilities must not be assumed merely because a native profile connects.

For Sentinel/Cluster, preserve opaque SCAN cursors exactly. They are tied to the
profile/database and observed primary/topology and can expire; restart from 0
after a stale-cursor error rather than substituting a raw cursor. Cluster targets
require logical database 0. Reads remain live and can repeat keys; changing
topology does not authorize configuration or failover commands.
