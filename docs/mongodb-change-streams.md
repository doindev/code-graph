# Bounded MongoDB change-stream batches

This workflow extends the native MongoDB command workspace and
`dba_request_native_command`. It is a finite read, not a background subscription
or an initial collection snapshot. Ordinary approvals, exact targets and the
existing job/status/cancel/release lifecycle remain authoritative.

## Start and continue

Use an explicit replica-set or sharded profile and an existing ordinary collection:

```json
{"watch":"items","waitMillis":1000,"limit":100}
```

Supply the selected connection UUID, exact connection name, database and matching
collection, or an existing project binding plus collection. Omitting `cursor`
deliberately starts at now; it does not retrieve existing documents or promise
coverage before the watch was established.

A completed batch includes `entries`, `stopReason` and, when continuity is
available, an opaque `nextCursor`. The next explicit request uses:

```json
{"watch":"items","cursor":"<returned nextCursor>","waitMillis":1000,"limit":100}
```

The native workspace provides **Read next change batch**. It replaces the displayed
batch, never appends an unlimited history. Editing the command/target invalidates
the button's continuation. Its managed cursor is not saved in the command draft;
page reload does not reconnect a subscription. JSON and read-only Grid displays
preserve the existing result layout behavior.

## Scope, limits and continuity

- At most 100 events and 0–10,000 ms requested wait per operation (defaults 100
  and 1,000 ms). Existing job deadlines and row/byte allowances may stop earlier.
  Connection setup and cursor cleanup can take additional bounded driver time.
- One raw driver event per requested batch, at most the server's bounded BSON
  batch envelope. Events above 256 KiB are not expanded into Java/JSON objects.
  No full-document lookup, preimages, custom pipeline or cross-collection watch.
- Native jobs retain their shared 64 MiB admission reservation while executing;
  output remains capped at the existing browser/agent byte limits. There is no
  second retained event inventory and no cursor/session held for the next request.
  Lazy native pools still follow their normal idle lifecycle.
- Continuations are authenticated/encrypted, expire in five minutes, and bind to
  the browser owner or agent identity, exact binding/target, profile revision and
  collection UUID. Restart invalidates them. They grant no permissions: every
  use is separately authorized. Trusted local agents still share their existing
  principal; possession of a cursor never bypasses exact request authorization.
  Reusing an older cursor can return events again; this is not exactly-once delivery.
- `event_limit`/`byte_limit` are caller-driven backpressure. Resume only advances
  over returned events, or an empty server batch's acknowledged high-water mark.
  A byte-rejected or oversized event is not silently skipped. An oversized event
  can block further progress; starting a new watch is an explicit acceptance of
  potentially missed history, not a safe retry.
- `invalidated` and `history_lost` terminate continuity and return no next cursor.
  Collection replacement is rejected. There is no automatic `startAfter` or
  restart-at-now fallback. An empty batch means no events were returned within
  this poll, not that the stream is complete.
  Quiet shards/configuration members can delay router delivery even after a write
  has completed. Continue from the returned cursor, not by removing it.
- Driver read resumption may occur within the job deadline, per the MongoDB
  change-stream contract. The application adds no resubmission loop. No database
  writes occur. Cancellation asks the driver to close its cursor and preserves
  bounded partial evidence where authorized; server cleanup is best-effort if
  the connection fails. Revocation discards the in-flight result.

The server emits `activeChangeStreamCursors` and `retainedChangeSubscriptions`
(always zero) in native-client telemetry. The allowance is not a total process-RAM
cap; wire/native/OS overhead remains separately bounded or unaccounted as documented.
MongoDB's own database privileges and oplog retention remain decisive.

## Boundaries and validation

Standalone/SRV, views, capped/time-series collections, server/database-wide
subscriptions, change-stream pipelines, preimages/update lookup and persistent
subscriptions remain unsupported here. This increment does not complete general
session workflows, GridFS, native migrations or infrastructure administration.

Run in an isolated source copy, never rebuild the running server's classpath:

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name mongo-streams -Modules code-graph-mcp-http `
  -Tests 'NativeMongoStreamTest,NativeMongoTransactionTest,NativeReviewTest,DbaToolSchemaTest,NativeMcpHttpTest'
# In the returned source directory:
./code-graph-dba/test-mongo-topologies.ps1 -Topology replica_set
./code-graph-dba/test-mongo-topologies.ps1 -Topology sharded
```

The harness pins MongoDB 8.0 image
`sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2`,
Java driver 5.12.0, a 256 MiB test heap and 64 MiB direct-memory limit. It removes
only its labelled containers/volumes and introduced unused images.
The disposable router fixture sets `periodicNoopIntervalSecs=1` on its own shard
and configuration members to bound cold-member ordering latency; production
connections are never tuned by this feature.
## Validation evidence (2026-09-20)

The isolated candidate and raw reports are under
`target/mcp-efficiency-coverage/mongo-streams-39fe9cb7b4924f1bb69546a319d93fc0/`.
Do not rebuild or delete that candidate after it is deployed.

- The full 35-module reactor passes: **875 discovered, 818 passed, 57 skipped,
  zero failures/errors**. Original XML is retained in `reactor-reports/`; skipped
  vendor/platform/interactive fixtures are not claimed as passing.
- Both owned replica-set and single-shard-router gates pass **13 tests each**:
  seven stream cases, five transaction cases, and one topology case. Evidence:
  `replica-streams-confirmed.log`, `sharded-streams-third.log` and `live-reports/`.
  These include ordinary approvals and explicit YOLO, no implicit grants,
  resumable event/byte boundaries, oversized-event obstruction, cancellation,
  authority loss, invalidation and replacement detection. Tests check release of
  accounted jobs, leases and active cursors; remote cleanup during network failure
  remains best-effort, not proven by the application's local counters.
- Cursor tests cover tampering, wrong owner/target scope, expiry, restart,
  oversized positions and use after shutdown. HTTP tests retain ownership,
  approval and CSRF enforcement. History-loss **classification** is unit-tested;
  deliberate live oplog-history exhaustion is not part of this fixture.
- All **19 isolated browser suites pass** (`browser-final.log`). The native suite
  passes again after final SVG styling (`browser-native-final.log`); its screenshot
  is `source/code-graph-dba/target/native-change-stream.png`. It checks explicit
  continuation, unchanged command drafts, no automatic reads after reload,
  input invalidation, exact review behavior and delayed session bootstrap.
  Native result delivery is mocked in the browser harness; the separate live
  topology tests exercise real MongoDB events and authorization services.
- All 23 DBA JavaScript modules pass syntax checks. Skill frontmatter/reference
  validation and 11 skill-installation/bootstrap tests pass. The broader installer
  invocation reports four passes and two explicitly skipped real-client checks;
  this overlaps the bootstrap gate and is not a unique-test total.
- The running hybrid MCP supplied symbol paths/callers and verified the changed
  source hash at generation 12. `target/mongo-change-streams-navigation.jsonl`
  records purpose, latency and bytes; this is not a complete filesystem-call
  audit or a comparative performance benchmark. Source reads were still needed
  for implementation and diagnosis.

Initial router tests assumed immediate delivery and failed. Their logs remain
available. The corrected tests wait for exact fixture counts within a bounded
window and explicitly tune only the owned router fixture's progress interval.
No production connection setting or authorization rule was weakened.

Browser validation also exposed a pre-existing startup race: Add Connection could
run before the session bootstrap completed. Workspace interaction and shortcuts
now wait for authenticated restoration; a delayed-bootstrap test covers it.
Native toolbar SVGs now use the dark theme's foreground strokes instead of the
browser's black default fill.

Broader multi-shard/election/outage, real authenticated TLS/SRV/cloud deployments,
interactive platform certification and three independent performance runs remain
unverified. Persistent subscriptions, GridFS and the other unchecked native
roadmap items are not completed by this increment. No user database was modified.

## Deployment

The requested restart deploys the validated candidate as PID **25316**, with MCP
**3000**, admin/DBA UI **8137**, desktop approvals and hybrid storage using the
existing **1.5 GiB graph/cache allowance**. YOLO remains off. Both UI pages return
HTTP 200; a fresh MCP session lists 69 tools and five native-command input forms.
Startup logs are under
`target/mcp-efficiency-coverage/restart-streams-4911ebf86bd14abb9ba4ecac7eae6b09/`.
Repository onboarding is explicit via MCP; no automatic startup root was added.
The fresh index is ready at generation 1: 613 files, 7,836 symbols, 33,046 edges
and zero pending changes. MCP symbol discovery locates `NativeMongoStreams` in
the deployed index. An initial status probe incorrectly supplied `waitMillis`
without a freshness predicate and was rejected; the corrected status-only read
succeeded. This was a harness argument error, not a server freshness failure.
Owned test containers/volumes and the newly introduced Mongo image were removed;
the user's existing MySQL/PostgreSQL containers and other images were preserved.
The optional agent reference was updated, without changing installed client
configurations. No commit or push was requested for this increment.

Protocol references: [change streams](https://www.mongodb.com/docs/manual/changeStreams/),
[invalidation](https://www.mongodb.com/docs/v8.0/reference/change-events/invalidate/),
[driver resume contract](https://github.com/mongodb/specifications/blob/master/source/change-streams/change-streams.md),
[router latency](https://www.mongodb.com/docs/v8.0/administration/change-streams-production-recommendations/),
[raw Java change-stream example](https://github.com/mongodb/mongo-java-driver/blob/main/driver-sync/src/examples/documentation/ChangeStreamSamples.java).
