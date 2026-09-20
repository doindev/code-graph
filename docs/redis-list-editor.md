# Bounded Redis list-item editor

The native Redis workspace has **Edit Redis list item** beside the string and
hash-field editors. It updates one existing, zero-based position in a list.
Values and nonempty keys use text or canonical base64, with an **8 KiB** editor limit.
Lists must contain at most **10,000 items**; supported positions are **0–9999**.

Load uses one bounded pipeline containing TYPE, LLEN, LINDEX and PTTL. No profile,
database record or persistent draft is created. Incomplete/truncated values,
missing positions, wrong types and expired keys cannot become editable snapshots.
The snapshot retains exact bytes, current length, position and profile revision.

## Save, conflicts and lifecycle

Save reviews one LSET with this managed transaction shape:

```json
{
  "transaction": [["LSET", "items", "1", {"base64": "bmV3"}]],
  "watch": [
    {"key": "items", "index": 1, "length": 3, "expected": {"base64": "b2xk"}}
  ]
}
```

Select an explicit connection/database normally; this shape grants no authority.
Browser mutations still require exact review; MCP uses the existing approval
flow or explicit startup YOLO. Read-only profiles prohibit writes in either mode.

After WATCH, the server verifies list type, exact length and complete original
value at the selected position. Redis rejects changes after WATCH before EXEC.
Conflicts execute no batch commands. No connection remains open while editing
or reviewing. LSET preserves key TTL and neighboring positions; the operation
does not insert, remove, reorder or recreate list items.

**Positions are not stable record identities.** These checks compare current
state, not change history. A prior same-length reorder or remove/reinsert cycle
can be undetectable if the selected position again contains the same bytes.
Other positions are not snapshotted. Do not use this editor as an application
record-version guarantee or as a general list transaction editor.

Revert is local. Closing a dirty editor prompts; switching tabs retains its
draft. Target changes disable the draft. Unknown acknowledgements or lost replies
block another Save until an explicit reload/reconciliation; there is no automatic
retry. Cancellation cannot undo an already executed write. Existing shared DBA
job limits, deadlines, audit, ownership and the 256 KiB browser editor reservation
remain unchanged.

For raw native commands, LSET permits text indexes 0–9999 and values up to 64 KiB.
The optional transaction watch form requires integer index/length, index < length,
and non-null complete expected bytes (at most 64 KiB); it cannot combine with
watch.field. One watch expectation per key and existing 128 KiB aggregate input,
16 watched-key, 32-command and single-slot Cluster limits still apply. Raw LSET
does not imply a watch check; the graphical editor always supplies one.

Pipeline LINDEX returns an 8 KiB preview. Its wire reply is subject to the
operation's predecode RESP allowance (8 MiB); oversized replies fail before full
allocation. A preview is never an editable original value. The editor loads one
position, not a list inventory. LINDEX/LSET can scan list nodes; the supported
index bound limits that traversal but does not guarantee constant latency.

See [LSET](https://redis.io/docs/latest/commands/lset/),
[LINDEX](https://redis.io/docs/latest/commands/lindex/) and
[Redis transactions](https://redis.io/docs/latest/develop/using-commands/transactions/).
Redis transactions do not roll back execution-time errors.

## Reproducible validation

Run in an isolated source build, not against the user's Redis profile:

```powershell
mvn -B -ntp -pl code-graph-mcp-http -am test '-Dtest=NativeRedisListTest,NativeRedisTransactionTest,NativeRedisPipelineTest,DbaToolSchemaTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
./code-graph-dba/test-browser.ps1 -NodeModules '<Playwright node_modules>'
mvn -B -ntp package '-Djava.awt.headless=true'
node --test skills/install-skill.test.mjs installer/skill-bootstrap.test.mjs
```

The harnesses require owned fixtures, run sequentially, and remove only their
labelled containers/volumes and newly introduced unused images. Keep pre-existing
images and databases. Native UI service fixtures and live adapter tests are
separate layers, not a claimed end-to-end production database test.

## Acceptance checkpoint

Validated on Windows with JDK 25, Maven 3.9.11, Node 22.22.3 and Chromium on
2026-09-20. The source checkpoint started at ee1b403; this increment is local,
uncommitted and not deployed at this checkpoint.

- Focused native/MCP tests: 38 passed; three fixture-dependent cases skipped in
  that invocation and exercised in owned Redis runs afterward.
- Redis 7.4.1, Lettuce 7.7.0.RELEASE: all three NativeRedisListTest cases passed
  independently on standalone, three-primary Cluster, and ACL-authenticated
  Sentinel. Each live test checks binary/empty values, neighbors, TTL,
  length/value/WATCH conflicts, cancellation, authority revocation, wrong types,
  missing/expired keys, oversized previews and pipeline LSET receipts.
- Existing transaction, pipeline, stream, value and topology fixture regressions
  passed in the same harness runs. Their reports overlap; do not add them to the
  reactor total as distinct tests.
- All 19 DBA browser suites passed. The native suite includes list-editor
  prefill, fixed target, invalid indexes, local Revert, exact review, cancelled
  review, successful Save, conflicts, unknown receipts, dirty-close protection,
  unmount/remount, narrow layout, read-only profiles, removal and disposal.
  The screenshot was inspected; UI/API fixtures and live Redis adapter tests
  remain distinct validation layers.
- All 24 DBA JavaScript modules passed syntax checks.
- Full 35-module Maven package: 894 tests, **833 passed, 61 skipped, zero
  failures/errors**. This includes graph, DBA, HTTP/stdio MCP and storage
  regressions. Environment-dependent skips remain unverified in that invocation,
  not silently counted as passes.
- Skill frontmatter/reference validation and 12 skill/installer Node tests passed.
  Windows bootstrap checks, 39 Java installer checks, 149 MCP-installer checks,
  50 skill-installer checks and two disposable uninstall tests passed. No real
  client configuration, PATH or customized installed skill was changed.
- The owned Redis containers/volumes were removed. The already-present pinned
  Redis image was reused, not newly introduced; the user's two MySQL containers
  and PostgreSQL container were preserved. No broad prune was performed.

Redis fixture digest:
redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275.
Raw logs, preserved live XML reports, full reactor reports and the isolated
candidate live under the ignored evidence directory
target/mcp-efficiency-coverage/redis-list-editor-a0564d049a524418bf771c4de8b92e00.
Important logs: focused.log, redis-standalone.log, redis-cluster.log,
redis-sentinel.log, browser-all.log, reactor.log, installer-java.log and
uninstall.log. Live list XML reports are in live-reports/{standalone,cluster,sentinel};
the narrow screenshot is source/code-graph-dba/target/redis-list-editor.png.

The previously pushed ee1b403 build was restarted before this work, preserving
MCP 3000, admin UI 8137, DBA, desktop approvals, hybrid storage, 1536 MiB graph/cache
allowance, one-hour project TTL and no automatic project onboarding.
Both UI pages and MCP initialization returned HTTP 200. The coding client's
tool catalog exposed no code-graph tools despite the healthy server; this pass
used focused source reads and makes no claim of indexed navigation savings.
Do not diagnose that client availability limitation as a server registration bug
without a fresh client/session comparison.

The wider native roadmap remains incomplete: general list lifecycle editing,
set/zset/stream editors, native administration and the other vendor gates are
not enabled by this increment.
