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
for one target's actual JDBC metadata or native server version. A
PostgreSQL-compatible product is not automatically certified for all PostgreSQL
administration. Connectivity does not mean complete vendor support; use reported
capability restrictions rather than the broader feature roadmap as authority.

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
object for MongoDB or an argument array for Redis (text and supported base64
key/value fields). Discover managed transaction/pipeline support from the actual
schema. Supply the exact binding or standalone UUID/name/database; add `collection`
matching Mongo collection commands. Native bindings have no SQL schema. A database or collection discovered
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

Native schema capture and cached catalogs, when advertised, contain bounded
definitions/observations, never a complete document/key inventory. Sampling is
opt-in; field types observed in documents are not declared validator requirements.
Redis prefixes/types/TTL classes are conventions, not a relational schema.
Do not infer removed objects from missing samples or compare volatile TTL
milliseconds as schema changes. Reusable policies, transactions and infrastructure
capabilities must not be assumed merely because a native profile connects.

### Redis pipelines and transactions

For a small string replacement, first establish that the read is complete, not a
truncated preview. When supported, an exact string WATCH expectation followed by
`SET key value XX KEEPTTL` avoids recreating a deleted key and preserves the current
expiry. This is the browser string editor's workflow, not a new permission or a
guarantee that a string contains ordinary text. A conflict requires reconciliation;
never remove its expectation or replay an uncertain write automatically.

Conditional single-command SET reports `applied: false` when its condition did
not match. That is not a transport failure and must not trigger an unconditional
retry. Pipeline receipts instead carry the command's value; an `acknowledged`
receipt means a reply arrived, not necessarily that a conditional write applied.

When advertised, Redis `pipeline` batches submit supported scalar/range operations
in order on one exact target, including read-after-write sequences. For example,
the command portion of a read request can be
`{"pipeline":[["TYPE","key"],["TTL","key"]]}`; supply the ordinary exact target,
request ID and purpose separately. Current bounds are 1–32 commands, 100 key
references and 128 KiB input. GETRANGE is restricted to a nonnegative range of at
most 8,192 bytes; arbitrary GET, scans, aggregate replies, stream commands and
scripts do not become valid merely by placing them inside a pipeline.

Pipelines are not transactions:
other clients may interleave and later commands still execute after errors. Use
the returned per-command receipts (`not_sent`, `acknowledged`, `rejected`,
`unknown`), including omitted values and unknown outcomes;
a cancelled job does not prove its commands were cancelled. Prefer the managed
transaction workflow when non-interleaving is required, and never automatically
replay either batch. A pipeline grants no extra permissions and does not make
an unbounded command safe. Cluster pipelines require one shared hash slot.

Individual native writes are not an atomic script. When advertised, Redis managed
transactions accept a bounded batch plus optional exact string/absent WATCH
expectations. When advertised, `watch[].field` selects an exact hash field instead;
the hash must exist and null means field absence, not key absence. Field checks
require Redis 7.4+ and HPTTL permission and reject field expiry. Do not remove a
rejected expectation or switch to an unguarded HSET/HDEL to force an edit through.
An empty field value is not absence: use `expected: null` only for a new field,
or complete original bytes for an update/deletion. Deleting the last field also
removes the hash key and TTL; disclose this effect before requesting deletion.
When advertised, watch.index and watch.length select a list position instead:
integer 0 <= index < length <= 10000, complete non-null original bytes, no field.
Use LSET with that expectation for a guarded existing-position edit. Indexes are
not stable identities: matching current length/value cannot detect every prior
reorder or remove/reinsert cycle. Pipeline LINDEX accepts text indexes 0–9999;
its 8 KiB preview must be complete before using it as an original value.
Never replace a rejected list expectation with an unguarded retry.
When advertised, watch.member selects one exact set member (up to 8 KiB) with
boolean expected membership. The set must exist even for false; do not confuse
absent membership with an absent key or an empty member. Do not combine member
with field/index/length. Use this guard for reviewed SADD/SREM; removing the last
member removes its key/TTL. Current membership does not detect every historical
remove/reinsert cycle. Never drop a failed guard to force a write through.
When advertised, watch.scoreMember checks an existing sorted-set member against
a finite decimal-string expected score (at most 64 characters, Redis binary64).
Do not confuse a missing member with zero or combine it with other watch kinds.
Guarded ZADD updates normally return zero newly added members; inspect the
transaction outcome instead of interpreting zero as failure. GEO keys also use
zset storage: establish their meaning before changing scores. Current score
matching is not a historical identity guarantee; never remove a failed guard.
Cluster requires all command/watch keys in one hash slot. Redis
does not roll back execution-time errors: inspect per-command results, not just
the job's final state. A WATCH conflict executes no batch commands; reread and
review a new request rather than retrying or removing expectations automatically.
On partial/unknown outcomes, reconcile with authorized reads before requesting
another mutation. Inspect retained results even when the job failed or was
cancelled: the terminal state alone cannot establish which commands ran.

### Redis bitmap, cardinality and geo workflows

Discover the command grammar first. Bitmap/bitfield writes are restricted to the
first 64 KiB and 32 bitfield operations; do not split a larger mutation merely to
bypass those limits. `BITFIELD_RO` supports GET only. Geo searches require explicit
COUNT (at most 100); COUNT bounds returned members, not the server's search work.
Multi-key cardinality/geo storage commands require one Cluster hash slot. These
commands are not accepted inside the managed pipeline/transaction forms.

`PFCOUNT` can update Redis's cached cardinality, so it requires write review and
is unavailable on read-only profiles. Do not infer a read grant from its name.
HyperLogLog counts are approximate. Bitmap and HyperLogLog keys have Redis type
string, and geo indexes have type zset; TYPE alone cannot prove their semantics.
Respect typed integer/null results and binary-preview truncation. After cancellation,
inspect retained outcomes; an acknowledged mutation is not undone by cancellation.

### MongoDB transactions and change streams

When advertised, MongoDB managed transactions use a `transaction` array of CRUD
objects, unlike Redis argument arrays. They require an explicit replica-set or
sharded profile and one existing ordinary collection. Every update/delete entry
must match one document; put expected original values in its filter when optimistic
concurrency matters. Inspect the final outcome: `rollback_acknowledged` differs
from `rollback_unconfirmed`, and `commit_unknown` must never trigger automatic
resubmission. Transactions do not grant permission or enable cross-collection,
DDL, view, capped/time-series or change-stream workflows.

When bounded MongoDB `watch` is advertised, it starts at now, not with a collection
snapshot. Use the returned opaque `nextCursor` as `command.cursor` for the next
authorized batch on the same exact target. Each batch still needs its normal
approval; the cursor grants no access. Release completed jobs and request another
batch only when the task needs it, rather than creating a polling keepalive.
Expiry, collection invalidation and history loss are gaps: never remove the cursor
and silently restart. Oversized events may block continuation; do not claim that
later events were consumed or that an empty batch means complete history.

### Redis key scans and stream delivery

For Sentinel/Cluster, preserve opaque SCAN cursors exactly. They are tied to the
profile/database and observed primary/topology and can expire; restart from 0
after a stale-cursor error rather than substituting a raw cursor. Cluster targets
require logical database 0. Reads remain live and can repeat keys; changing
topology does not authorize configuration or failover commands.

When Redis streams are advertised, use finite single-stream requests, with an
explicit bounded COUNT where required. XREAD/XPENDING are reads; XREADGROUP,
claims and ACKs change delivery state and require write approval. Returned
`deliveredIds` can include messages whose payload previews were truncated or
omitted: never interpret those as fully processed or automatically ACK them.
XACK is a separate exact-ID operation. A missing body may represent a deleted
entry; XAUTOCLAIM reports removed pending IDs separately. Cancellation or lost
replies cannot undo delivery changes. Reconcile pending state before proposing
another mutation; do not turn finite batches into an unrequested subscription.
