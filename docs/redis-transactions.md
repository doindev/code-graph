# Reviewed, bounded Redis transactions

The Redis native workspace and `dba_request_native_command` accept a managed
transaction object as well as the existing single-command argument array:

```json
{
  "transaction": [
    ["SET", "{cart}:version", "2"],
    ["HSET", "{cart}:items", "sku-1", "3"]
  ],
  "watch": [
    {"key": "{cart}:version", "expected": "1"}
  ]
}
```

Select the exact connection and logical database as usual. `watch` is optional.
Each expectation is a complete string value (text or canonical `{ "base64": "…" }`)
or `null`, which means the key must not exist. Empty text means an existing empty
string, not absence. Optional `field` selects an exact hash-field expectation
instead: the hash must exist, and null then means only that field must be absent.
Field expectations require Redis 7.4+ and HPTTL permission; expiring fields are
rejected to avoid silently clearing their expiry. See the [hash editor](redis-hash-editor.md).
Alternatively, integer index and length select a list-position expectation
(0 <= index < length <= 10000), with non-null complete expected bytes and no field.
This checks current position/length, not change history; see the
[list-item editor](redis-list-editor.md).
Watching starts during execution, **not while the approval is open**. The server
checks the supplied expectations after WATCH and Redis detects subsequent changes
before EXEC. Expiration/eviction can also cause conflicts.

## Boundaries and authorization

- 1–32 commands, no more than the configured result-row limit; 16 watched keys;
  100 total key references; existing 128 KiB aggregate request allowance.
- Only existing verified scalar-reply mutation adapters: SET, DEL/UNLINK,
  RENAME/RENAMENX, EXPIRE/PEXPIRE/PERSIST, HSET/HDEL, LPUSH/RPUSH/LSET, SADD/SREM,
  ZADD/ZREM. Their existing argument and binary-size restrictions still apply.
- No raw MULTI, EXEC, WATCH, scripts, nested transactions, read arrays,
  blocking commands, administrative commands or cross-database changes.
- Standalone and Sentinel use an operation-owned connection. Cluster pins all
  commands to one primary socket and requires every command/watch key to share
  one hash slot, including rename destinations and every deletion key. Hash tags
  such as `{cart}` provide an explicit way to select a common slot.
- Exact one-time review (or startup YOLO), normal profile read-only protection,
  audit, session ownership, deadlines, cancellation and revisions remain in force.
  The batch cannot inherit reusable native read permissions. A destructive child
  makes the entire review destructive.
- All commands validate before connection/queueing. Authority is checked again
  immediately before EXEC. The driver queue remains bounded (34 entries, including
  protocol headroom); no connection or watched keys remain open between jobs.

## Outcomes and recovery

Redis executes a transaction without interleaving other clients' commands, but
**does not roll back commands that succeeded before or after an execution-time
error**. See the [Redis transaction contract](https://redis.io/docs/latest/develop/using-commands/transactions/).

| Outcome | Meaning | Next action |
| --- | --- | --- |
| `acknowledged` | All command replies received | Inspect each reply; SET NX/XX can return null without making a change |
| `conflict` | Expected value mismatch or WATCH invalidation; no batch commands executed | Read current values; submit a new reviewed request if still appropriate |
| `partial` | EXEC returned per-command failures; other commands may have succeeded | Inspect retained indexed replies and reconcile actual state; do not replay the batch blindly |
| `not_started` | Rejected/cancelled before EXEC submission | No batch commands were submitted for execution |
| `partial_or_unknown` | Failure/cancellation after EXEC may have been sent | Inspect actual database state before any new request |

Conflicts and partial failures are failed jobs with bounded retained results.
The browser displays these details and keeps the command draft. Neither the
server nor UI retries writes. Closing the operation-owned socket clears queued
commands and WATCH state if EXEC was not sent; it cannot undo an EXEC already
received by Redis. Failover or slot migration never triggers transaction replay.

## Validation

Run in an isolated source build to avoid replacing the running application:

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name redis-transactions -Modules code-graph-dba -Tests NativeRedisTransactionTest,NativeReviewTest,NativeFoundationTest,NativeMemoryTest
# From that command's printed isolated source directory:
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl
```

Harnesses use the pinned Redis 7.4.1 image
`redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`
and Lettuce 7.7.0.RELEASE. They delete only owned containers/volumes and newly
introduced images that are unused; pre-existing databases and images survive.

Tests cover typed/bounded validation, binary expectations, one-slot scope,
ownership/revisions/read-only profiles, normal and YOLO approvals, expected-value
and concurrent WATCH conflicts, cancellation/revocation after queueing but before
EXEC, execution-time partial failure, full 32-command batches and repeated cleanup.
Browser regression covers exact review, denial and visible partial results.

This is not arbitrary pipelining, script/function execution, Pub/Sub or complete
Redis administration. Cross-platform interactive certification, failover/slot
migration during an in-flight EXEC, transport-loss commit uncertainty and the
broader native performance matrix remain separate incomplete gates.

## Implementation evidence — 2026-09-20

The already-tested committed application was started on MCP 3000 and admin
UI/DBA 8137, desktop approval, hybrid 1.5 GiB graph/cache allowance. Only this
repository was onboarded for self-hosted navigation; saved database profiles and
the existing local database containers were not modified. New transaction changes
are validated in an isolated candidate, not deployed to that running process.

The coding task had no registered code-graph connector tools. With the user's
explicit request to exercise the application, the existing repository MCP client
performed actual initialize/tools-list/tools-call requests. Six ledgered evidence
queries located the native integration files, found the new executor at generation
8 after watcher indexing, and returned its static-bound caller at generation 10.
The final file-hash freshness barrier matched generation 11 with no pending changes.
The caller bundle reported its coverage limitations and needed no filesystem
caller search. Local reads were still required to implement edits. These are
development observations, not a comparative speed benchmark or a complete tally
of filesystem calls. No MCP configuration was changed.

Evidence directory:
`target/mcp-efficiency-coverage/redis-transactions-39c28faa841544afa7882ce41991767c/`.
The MCP ledger is `target/redis-transactions-navigation.jsonl`.
`maven.log` retains a corrected test-helper compile error. `redis-standalone.log`
retains the initial 32-command queue saturation failure: the driver now reserves
two bounded protocol slots, and the full batch passes. The successful live runs
are `redis-standalone-final.log`, `redis-cluster.log`, `redis-sentinel-acl.log`
and `live-reports/`. The Sentinel run also includes the existing authenticated
failover/cursor tests; it does not certify failover during EXEC.

The clean 35-module reactor passed 810 of 861 tests, with 51 explicit optional
skips and zero failures/errors (`reactor-final.log`). The skill frontmatter/reference
validator and all 11 skill installer/bootstrap regressions passed. The validator's
missing PyYAML dependency was installed only in the owned validation directory,
not globally. Final retention-accounting and browser results are recorded below.

- Final 35-module reactor after retention-accounting changes: **861 tests,
  810 passed, 51 explicit skips, zero failures/errors**, in
  `reactor-retention-final.log` and `reactor-reports/`.
- All **19 isolated browser suites passed** (`browser-all.log`), including
  exact transaction review/Cancel/Apply, failed-job per-command details, retained
  drafts, ordinary approvals, grids, editor pairing and session behavior. The
  native-workspace screenshot was visually inspected as well.
- Final-source live reruns passed all four transaction tests on each of standalone,
  three-primary Cluster and ACL-authenticated Sentinel. See `redis-final-source.log`,
  `redis-cluster-final-source.log`, `redis-sentinel-final-source.log` and their
  `live-reports/final-*` XML reports. Normal/YOLO public native-request workflows
  also passed on the standalone fixture.
- All 23 DBA JavaScript modules, the native browser test and both modified
  PowerShell harnesses passed syntax checks. Edited implementation/test/harness
  hashes match the isolated candidate.
- Owned containers/volumes were removed after each run. Redis reused its existing
  pinned image; no new Redis image was left behind. Existing user databases and
  unrelated images were preserved. No broad pruning was performed.

No commit, push or deployment of the transaction candidate was performed. The
initial restarted application remains on the previously tested committed build.
The broader unchecked native roadmap remains incomplete; these tests are not
evidence for unsupported administration, streaming or migration operations.
