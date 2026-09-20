# Bounded Redis command pipelines

The native Redis transport accepts a `pipeline` object through the existing
browser native workspace and `dba_request_native_command`. No additional MCP
tool, database grant or persistent connection is created.

```json
{
  "pipeline": [
    ["SET", "{example}:key", "value"],
    ["GETRANGE", "{example}:key", "0", "8191"],
    ["TTL", "{example}:key"]
  ]
}
```

Use the normal explicit connection UUID/name/database or binding target. Each
batch has one exact approval, or startup YOLO authorization, before execution.
Read-only profiles prohibit any batch containing a mutation, including one whose
first command is a read. Reusable native permissions remain unavailable.

## Supported contract

- 1–32 argument arrays, at most 100 key references and the existing 128 KiB
  command-input limit. The entire batch is validated before any user command is
  dispatched. Its receipt count must fit the job's row limit.
- Existing verified scalar-reply mutations: SET, DEL, UNLINK, RENAME, RENAMENX,
  EXPIRE, PEXPIRE, PERSIST, HSET, HDEL, LPUSH, RPUSH, LSET, SADD, SREM, ZADD and ZREM.
  Each retains the individual adapter's exact supported argument grammar.
- Reads: TYPE, TTL, PTTL, STRLEN, EXISTS (one key), EXPIRETIME, PEXPIRETIME,
  HLEN, LLEN, SCARD, ZCARD, XLEN, HEXISTS, SISMEMBER, ZSCORE, GETBIT, and
  nonnegative GETRANGE slices of at most 8,192 bytes.
  LINDEX accepts a text index 0–9999 and returns at most an 8 KiB preview;
  a truncated preview must never be used as an original value for editing.
- No unbounded GET, HGETALL, scans, aggregate replies, stream delivery, scripts,
  connection controls, WATCH or nested transaction/pipeline objects. Unsupported
  commands fail before dispatch rather than being silently skipped.
- Binary arguments retain the existing canonical base64 representation. Control
  arguments are strings. Large integer replies use `$numberLong` rather than
  rounding through browser JavaScript numbers. GETRANGE retains Redis's own
  empty-range/missing-key semantics; it does not imply a separate existence test.
- Standalone and Sentinel targets retain their explicitly selected database.
  Cluster requires database 0 and all command keys in one hash slot, including
  both rename names and every DEL/UNLINK key. No automatic cross-node fan-out.

## Execution, resources and recovery

The operation owns one primary socket. It queues asynchronous commands with
auto-flush disabled, rechecks authority/revisions, then flushes once. This is real
network pipelining, not a loop of synchronous calls. The socket is closed after
the bounded job and is never returned to a caller with queued commands.

Pipelining is **not a transaction**. Commands execute in order on this connection,
but other clients can interleave. Later commands still run after an earlier
server error. Use the managed transaction workflow when non-interleaving is
required; Redis transactions still do not provide rollback for execution errors.
See [Redis's pipelining explanation](https://redis.io/docs/latest/develop/using-commands/pipelining/).

Each result contains `kind: pipeline`, `atomic: false`, `rollbackSupported: false`,
and indexed `entries` with a command name and state:

- `not_sent`: application dispatch did not occur.
- `acknowledged`: a response was received, not necessarily that a conditional
  mutation changed data. Inspect its value; SET NX can return null.
- `rejected`: Redis returned a command error. Other entries may have succeeded.
- `unknown`: no definitive command response was received.

Top-level `outcome` distinguishes `not_started`, `read`, `read_failed`,
`read_incomplete`, `acknowledged`, `partial` and `partial_or_unknown`.
`dispatchedCount`, `rejectedCount` and `unknownCount` describe receipts, not a
count of changed keys. A failed/cancelled job can still retain meaningful results.

Cancellation before flush sends no user commands. After flush, already-dispatched
commands may execute; cancellation is not undo. Timeout, revoked authority or a
lost connection closes the socket, preserves known receipts and reports unknown
outcomes. There is no retry or reconnect replay. Reconcile using authorized reads
before proposing another mutation. Never blindly repeat the whole batch.

The existing 64 MiB native-job reservation, 8 MiB per-connection wire/RESP guards,
shared admission, deadlines and 1 MiB agent/4 MiB browser result limits apply.
Every receipt survives result-value truncation; `valueOmitted` and `truncated`
make omissions explicit. The browser reserves space before dispatch and can show
compact receipts instead of values when its aggregate allowance is full. Late
cancellation preserves received pipeline/stream receipts. No complete keyspace
or second graph is retained, and no socket is held between jobs.

The native editor includes read-only and mixed pipeline examples. Choosing an
example confirms draft replacement and never executes it. Normal browser review
and Cancel/Apply remain in effect. A pipeline does not authorize native scripts,
Pub/Sub or infrastructure administration.

## Reproducible validation

Run in an isolated source copy, not the directory backing a running server:

```powershell
mvn -pl code-graph-dba,code-graph-mcp-http -am test `
  '-Dtest=NativeRedisPipelineTest,NativeReviewTest,NativeFoundationTest,DbaToolSchemaTest,NativeMcpHttpTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
mvn verify '-Djava.awt.headless=true'
./code-graph-dba/test-browser.ps1 -NodeModules <directory-containing-playwright>
```

Fixtures use Redis 7.4.1 digest
`sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`,
Lettuce 7.7.0.RELEASE and JDK 25. Harnesses run sequentially, preserve pre-existing
images/databases, and remove owned containers/volumes and newly introduced unused
images only. Full native infrastructure and all-version certification remain
separate incomplete roadmap items.

## Acceptance — 2026-09-20

- Final isolated `mvn verify`: all 35 reactor modules passed; 885 tests reported,
  826 passed and 59 explicitly skipped, with zero failures/errors. Skipped optional
  vendor/platform fixtures are not certification of those environments.
- Live Redis 7.4.1 standalone, three-primary Cluster and ACL-authenticated Sentinel:
  all three pipeline tests passed on each topology. The expanded fixture exercises
  every advertised command/reply family, ordered reads after writes, binary values,
  32-command/byte ceilings, server errors with later writes, cancellation,
  revocation and connection cleanup. Cluster cross-slot requests fail pre-dispatch.
- The separate standalone native-vendor suite passed all three tests, including
  normal approval and automatic YOLO pipeline workflows. These runs overlap unit
  tests; they are not added to the reactor total.
- All 19 browser suites passed. Native browser checks cover example selection
  without execution, review/cancel/apply, complete receipt retention under browser
  memory pressure and results arriving after a cancellation request. Graph/DBA,
  editable grids, workspace recovery, approvals and editor pairing regressions pass.
- JavaScript syntax checks and maintained-skill validation passed. The optional
  skill/installer regression selection passed 13 tests, with two opt-in real-client
  checks skipped. Real Copilot/Codex CLI discovery, other client products and
  macOS/Linux desktop behavior are not certified by these path/unit checks.
- The packaged implementation hashes match the tested source. The canonical MCP
  reference was regenerated from the final package. No new MCP tool names or
  browser endpoints were introduced, and no existing permission was broadened.

Local raw evidence is retained under
`target/mcp-efficiency-coverage/redis-pipelines-5ecdfbd80fd242fdbe705ee962ab1faa/`:
`full-reactor-final.log`, `browser-all.log`, `installer-final.log`,
`redis-{standalone,cluster,sentinel}-final.log`, the corresponding
`pipeline-*-final.xml` reports, and `vendor-standalone-final.xml`. Initial failed
development checks are retained too: the expanded schema needed its expected
variant count updated; the isolated browser fixture needed its test dependency
classpath populated. Both corrections passed subsequent gates.

The repository's running MCP index was used for symbol navigation and incremental
discovery of the new implementation. The purpose/latency/bytes/generation ledger
is `target/redis-pipelines-navigation.jsonl`; source reads were still needed for
implementation. This is not a complete filesystem-call comparison or a measured
pipeline performance improvement. No speedup claim is made.

Owned test containers were removed. The pinned Redis image already existed and
was preserved; no newly introduced image or container remains from these runs.
Existing MySQL/PostgreSQL containers and user databases were not changed. No broad
Docker prune, commit, push, user MCP-configuration change or global skill overwrite
was performed.

After the user's requested restart, the tested package serves MCP on **3000** and
admin UI/DBA on **8137**, with desktop approvals and the existing **1.5 GiB shared
hybrid graph/cache allowance**. This is not a total-process RAM limit. Both UI
routes return HTTP 200, initialization succeeds, and a fresh MCP session advertises
69 tools including the new native pipeline contract. Projects are still not
automatically restored; this repository is explicitly onboarded through MCP for
development validation.

This increment closes bounded pipeline execution, not the broader native roadmap.
Redis Pub/Sub needs explicit server/channel authorization because channels are
not isolated by logical database; it must not inherit database-local grants.
Persistent consumers, scripts/functions, infrastructure administration, broader
MongoDB/SQL Server coverage and unavailable platform gates remain tracked in
[the native delivery checklist](native-database-delivery.md).
