# Bounded Redis set-member editor

The native Redis workspace offers **Edit Redis set member**. Enter a nonempty
key and one member as UTF-8 text or canonical base64 (at most 8 KiB each). An empty
member is valid and distinct from absence. No entire set is loaded or copied.

1. **Check set membership** reads TYPE, SISMEMBER, SCARD and PTTL in one finite
   pipeline. The key must already be a set; incomplete replies, wrong types and
   expired/missing keys cannot prepare a draft.
2. Explicitly **Stage member insertion** for an absent member or **Mark member
   for deletion** for a present member. Staging sends no write.
3. **Save set member** reviews the exact key/member/database and one SADD or SREM.
   **Revert** only removes the pending local change.
4. **Choose another member** releases the checked target after dirty-state
   confirmation. Switching tabs preserves the current draft. Closing/removing
   the connection uses the existing cancellation, ownership and memory cleanup.

The member identity is fixed after checking. This is not a rename/replacement,
set-creation, multi-member or bulk-import editor. Existing raw native SADD/SREM
commands remain available through their reviewed command workflows.

## Exact membership expectations

The browser submits a single-command managed transaction, for example:

```json
{
  "transaction": [["SADD", "members", {"base64": "AP8="}]],
  "watch": [
    {"key": "members", "member": {"base64": "AP8="}, "expected": false}
  ]
}
```

For removal, use SREM with expected true. This is a command shape, not authority:
select the exact connection UUID/name/database or binding through the existing
native API/MCP workflow. No new MCP tool name or persistent permission is added.

- member accepts text/canonical base64, at most 8 KiB decoded/UTF-8.
- expected must be a JSON boolean, not text, null or 0/1. Other expectation forms
  remain unchanged and reject booleans.
- Do not combine member with field/index/length. Existing limits remain: one
  expectation per key, 16 watched keys, 32 commands, 100 total key references,
  128 KiB aggregate input, and one hash slot for Cluster/database 0.
- After WATCH, the server verifies the key is still a set and the exact member's
  membership matches. Even expected false requires an existing set: a removed or
  expired set cannot be silently recreated.
- Other-member changes before WATCH are not a conflict by themselves. Changes
  after WATCH invalidate EXEC. Current membership is not historical identity;
  delete/recreate or remove/reinsert cycles before WATCH may be undetectable.
- The editor requires an acknowledged count of exactly one. An unexpected receipt
  or lost reply retains the draft and blocks another Save/Revert until an explicit
  check/reconciliation. Neither UI nor server automatically retries a write.
- Read-only profiles prohibit staging/saving. Normal human review, exact target
  revisions, audit-before-write, deadlines, cancellation and session ownership
  remain in force. Startup YOLO does not change profile read-only protection.
- The graphical draft uses the existing 256 KiB editor reservation, shared native
  job accounting and operation-owned sockets; no connection is held during review.

SADD preserves the existing key's TTL. SREM preserves TTL while members remain;
**removing the last member deletes the key and TTL**. This warning appears in
destructive review even when an earlier count was greater than one, because the
set may have changed. See [SADD](https://redis.io/docs/latest/commands/sadd/),
[SREM](https://redis.io/docs/latest/commands/srem/) and
[SISMEMBER](https://redis.io/docs/latest/commands/sismember/).

A successful check is not a frozen snapshot. A cancelled job may already have
executed: inspect its receipts. Redis transactions do not roll back execution-time
errors. Reconcile uncertain outcomes before any new write. Editor-only membership
drafts are not persisted to workspace recovery or general logs. Native command
text retains the existing session-workspace recovery rules.

## Validation

Use an isolated source tree; the harnesses require disposable owned Redis fixtures
and remove only their labelled containers/volumes and newly introduced unused
images. They never prune unrelated resources.

```powershell
mvn -B -ntp -pl code-graph-mcp-http -am test '-Dtest=NativeRedisSetTest,NativeRedisListTest,NativeRedisTransactionTest,NativeRedisPipelineTest,NativeMemoryTest,ApprovalReviewServerTest,DbaToolSchemaTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
./code-graph-dba/test-browser.ps1 -NodeModules '<Playwright node_modules>'
mvn -B -ntp package '-Djava.awt.headless=true'
node --test skills/install-skill.test.mjs installer/skill-bootstrap.test.mjs
```

## Acceptance checkpoint

Validated on Windows with JDK 25, Maven 3.9.11, Node 22.22.3 and headless
Microsoft Edge (Chromium) on 2026-09-20. The source checkpoint started at eeb2824.
The set increment remains local and undeployed at this checkpoint.

- Focused native/MCP tests: 48 passed, four fixture-dependent cases skipped in
  that invocation. Live Redis tests ran separately, not counted as those skips.
- Redis 7.4.1 with Lettuce 7.7.0.RELEASE: all three NativeRedisSetTest cases
  passed on standalone, three-primary Cluster and ACL-authenticated Sentinel.
  Coverage includes typed/binary/empty members, absent-key protection, TTL,
  current-membership/WATCH conflicts, cancellation/revocation before EXEC,
  expired/wrong-type keys and last-member removal. Existing transaction,
  pipeline, stream, value and topology regressions passed in the same harnesses.
  These overlapping harness reports are not added to reactor totals.
- All 19 isolated DBA browser suites passed across the complete run and focused
  continuations. Initial runs exposed fixture cleanup and approval-presentation
  races: test pages could renew a session after logout, and a test clicked the
  approval button after the broker already opened its modal. Tests now close
  pages before logout, handle owned-context reentrancy, assert session release,
  and accept either valid approval-opening path. Production limits/approvals
  were not weakened. Initial failed logs remain in the evidence directory.
- The set UI fixture covers staged insertion/deletion, exact review and target
  revision, empty/binary members, conflicts, unexpected receipts, reconciliation,
  cancelled review, local Revert, dirty closure, unmount/remount, read-only
  profiles, connection removal, disposal and narrow layout. Its screenshot was
  inspected. Mock browser/API coverage is distinct from the live Redis tests.
- All 25 DBA JavaScript modules and the three new/changed browser fixture files
  passed syntax checks. Skill frontmatter/reference validation and all 12
  skill/installation Node tests passed. Windows bootstrap checks, 39 Java
  installer checks, 149 MCP-installer checks, 50 skill-installer checks and two
  disposable uninstall tests passed. No user PATH, client configuration or
  customized installed skill was changed.

- Full 35-module Maven package: 897 tests across 181 suites, **835 passed,
  62 skipped, zero failures/errors**, in 3 minutes 57 seconds. Graph/index/storage,
  DBA and HTTP/stdio MCP regressions are included. Environment-dependent skips
  are not passes; owned Redis runs above exercised the set fixture separately.

The owned Redis containers/volumes were removed. The existing pinned image was
reused, not newly introduced; existing databases and unrelated images were
preserved. No broad prune was performed. Redis fixture digest:
redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275.
The live fixture JVMs used a 256 MiB heap and 64 MiB direct-memory ceiling; this
is not a new full-workload memory/performance benchmark.

Raw evidence is retained under the ignored directory
target/mcp-efficiency-coverage/redis-set-editor-67e9b1d276524aa38716c3e5cdc03a63.
It includes focused.log, redis-standalone.log, redis-cluster.log,
redis-sentinel.log, browser-all-approval-race.log, browser-native.log, the six
browser-{project-context,catalog,editor-pairing,approvals,approval-review,core}.log
continuations, reactor.log, bootstrap.log, installer-java.log,
mcp-installer.log, skill-installer.log, skill-node.log and uninstall.log.
Live XML reports are in live-reports/{standalone,cluster,sentinel}; the narrow
screenshot is source/code-graph-dba/target/redis-set-editor.png.

Before this increment, eeb2824 (the prior list editor) was pushed and the app was
restarted on MCP 3000 / admin UI 8137 with DBA, desktop approvals, hybrid storage,
1536 MiB graph/cache allowance and one-hour project TTL, without a startup root.
Both UI pages and MCP initialization returned HTTP 200. The coding client's
catalog still exposed no code-graph tools; source reads were used without claims
of MCP navigation savings. This is a client-availability limitation, not evidence
of a server registration defect.

The wider native roadmap remains incomplete: general set/list lifecycle,
sorted-set/stream graphical editors, TTL/rename controls, infrastructure
administration and the remaining MongoDB/SQL Server/platform/performance gates
are not completed by this increment. No production database was changed.
