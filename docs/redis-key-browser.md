# Bounded Redis key browsing

The native Redis connection tree's **Keys** branch uses SCAN, never KEYS *. It
supports standalone, Sentinel and Cluster profiles; Cluster walks the bounded
primary topology through the existing opaque cursor. Changing topology/profile
or allowing a cursor to expire requires an explicit refresh, not a guessed cursor.

## Browser behavior

- Key pattern accepts a Redis MATCH glob (default `*`). Scan or Enter starts a
  new scan; typing does not execute. The UI accepts 1,024 characters; the server
  also rejects non-text and patterns larger than 4 KiB UTF-8.
- Continue scan reads exactly one bounded batch. Empty batches and repeated
  keys are normal: continuation remains available until the server reports
  completion. There is no automatic loop through empty batches.
- Complete key bytes identify nodes. Duplicate keys are merged across loaded
  batches; distinct binary keys with identical preview labels remain distinct.
  Loaded keys stay alphabetized. No complete keyspace or unbounded seen-set is
  retained.
- All metadata roots share a 2,000-node / 2 MiB serialized-descriptor allowance.
  This accounts payload, not total browser/DOM memory. A page exceeding a bound
  is rejected as a whole and its cursor is not consumed. Refine the pattern or
  refresh/remove other roots to release entries. Normal job/result budgets apply.
- Refresh starts at cursor zero and replaces old keys only after success. It
  never replays every earlier batch. A failed refresh keeps old results; changing
  a pattern successfully discards the previous scan. Status explicitly describes
  live, non-snapshot coverage. Completion is not a stable inventory guarantee.
- Removing/reloading a connection invalidates its old loads, requests cancellation
  even if submission returns a job ID late, ignores obsolete results, and still
  polls/releases the job through the existing bounded lifecycle.

SCAN COUNT is a hint, not a row/byte guarantee. Over-budget server replies and
unrepresentable key names remain visibly incomplete. No cursor is invented after
a truncated response. Redis itself permits duplicates and empty nonterminal
batches; keys can change/disappear while browsing. See the
[Redis SCAN contract](https://redis.io/docs/latest/commands/scan/).

## Browser API

`POST /api/dba/native/tree` keeps its existing authenticated job contract.
`kind: "native_keys"` accepts `pattern` and `offset` (zero or returned cursor),
alongside the fixed connection/database target. Results include `nodes`, optional
`nextOffset`, `scanComplete`, `inventoryComplete: false`, `requiresRefinement`,
and `coverage: "bounded_live_non_snapshot"`. Warnings remain explicit.
No MCP schemas, permissions, approval rules or database writes changed.

## Reproducible validation

Use the ordinary isolated build/browser dependency setup documented in the
[native database guide](native-databases.md), then run:

```powershell
mvn -B -ntp -pl code-graph-mcp-http -am test -Djava.awt.headless=true -Dtest=NativeFoundationTest,NativeTopologyTest,NativeVendorTest,NativeMcpHttpTest -Dsurefire.failIfNoSpecifiedTests=false
$env:DBA_BROWSER_SUITE='native'
./code-graph-dba/test-browser.ps1 -NodeModules <playwright-node-modules>
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
```

Fixture harnesses remove only owned containers/volumes and newly introduced
unused images. Existing images and databases must remain untouched.

Acceptance evidence is recorded below after validation; broader native database
work remains tracked in the [delivery checklist](native-database-delivery.md).

## Acceptance checkpoint — 2026-09-21

- Focused native/HTTP tests: 26 passed, four explicit unconfigured-fixture skips.
- Redis 7.4.1 standalone: 40 passed, four Mongo-only skips. Three-primary Cluster
  and ACL Sentinel: 37 passed each. Existing expired/topology-changed cursors,
  failover and authentication tests remain passing; new tree integration checks
  traverse all matching keys and empty MATCH batches. Lettuce 7.7.0.RELEASE;
  topology fixtures use a 256 MiB heap / 64 MiB direct-memory allowance.
- Full Maven package: 35 modules, 183 suites, 912 tests; 847 passed, 65 explicit
  opt-in/platform skips, zero failures/errors. Elapsed time 4:31.
- JavaScript syntax: 29 modules; reusable grid unit tests: 11 passed. Installer
  and optional skill tests: 18 passed, two real-client checks unavailable. Skill
  frontmatter validation passed using an isolated test-only PyYAML dependency.
- All 20 final browser regression groups passed sequentially against the packaged
  build, including JDBC trees, native editors, grids, designers, catalog workflows,
  session recovery, approvals, and editor pairing. No compiler changed fixture
  classes while browser suites were running.
- Browser component coverage includes duplicate/empty batches, binary identity,
  keyboard activation, narrow controls, pattern changes, node/byte bounds with
  same-cursor retries, refresh failures, submission/poll cancellation and cleanup.
  The first full native run reached the production eight-session ceiling because
  the new component test opened another browser session. It now mounts without
  authentication in an isolated fixture; production limits were not changed.

Raw evidence: `target/redis-key-browser-de2702498ef24aadbc6267bdf619756c`
(`focused.log`, `redis-standalone.log`, `redis-cluster.log`, `redis-sentinel.log`,
`live-reports`, `full-reactor.log`, `all-browser.log`, `installer.log`).
Live report directories retain earlier report files too; the counts above refer
only to each harness's selected suites, not every XML file in that directory.
Owned containers/volumes were removed; the six existing containers and 55-image
baseline were preserved. No newly introduced unused images remain.

MCP navigation used the existing repository client at the user's explicit request;
native code-graph tools were not exposed in this Codex session. Requests and
acquisition metadata are in `target/redis-key-browser/navigation.jsonl`. Watcher
publication advanced from generation 1 to 7 without manual reindexing. A confirmed
file-language search defect is tracked as LR-4 in
[the navigation follow-ups](language-resolution-followups.md). Indexed caller
coverage remains incomplete, particularly callback wiring; no overall speedup or
exhaustive-reference claim is made.
