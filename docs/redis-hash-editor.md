# Bounded Redis hash-field editor

In a Redis Native workspace, choose **Edit Redis hash field**. HGET/HSET/HDEL/
HEXISTS command drafts prefill the exact key and field; HSCAN/HLEN prefill only
the key. Otherwise enter them explicitly. Nothing executes when opening it.

The shared value editor loads one existing field or explicitly prepares one new
field in an existing hash, never the whole hash. Key,
field and value inputs support UTF-8 text or canonical base64, capped at 8 KiB
each. Empty field names and empty values are valid; this editor requires a
nonempty key. Missing, truncated or oversized values cannot become editable
snapshots. Invalid UTF-8 and carriage-return-containing data stay in base64.
Key/field identity and database selection stay fixed until the editor closes.

For an existing-value update, Save opens the exact native review using one HSET inside a
managed WATCH/MULTI/EXEC operation, carrying the loaded profile revision and
complete original field bytes. The server checks the existing hash and field
immediately before execution; deletion, type changes, original-value changes,
or a subsequent WATCH conflict prevent the edit. Changes to other fields after
WATCH also invalidate the transaction. No socket stays open while editing.

**Redis 7.4+ and permission to execute HPTTL are required for these field
expectations.** HSET clears a field's own expiry. Expiring fields are therefore
rejected before MULTI; the editor never silently removes that expiry. Hash-key
TTL is preserved by HSET. See the Redis [field-expiration contract](https://redis.io/docs/latest/commands/hexpire/)
and [WATCH semantics](https://redis.io/docs/latest/develop/using-commands/transactions/).
Older Redis versions can still use previously supported commands, but cannot
save through this guarded editor.

The editor reuses string-editor review, Revert, dirty-close/unload protection,
tab unmount retention, cancellation, read-only checks and a 256 KiB browser
reservation. Draft values are memory-only, not workspace recovery data. Declining
review sends no write; failures keep the draft. Only an acknowledged HSET receipt
with zero newly added fields confirms an existing-field edit. Unknown or unexpected
outcomes disable Save until explicit reload/reconciliation; never retry blindly.

## MCP expectation extension

The existing `dba_request_native_command` transaction schema adds optional
`watch[].field`. Without it, existing whole-string/absent-key semantics stay
unchanged. With it, the hash itself must exist; `expected: null` means that field
must be absent, while text/base64 means its complete original value. There is
still at most one expectation per key, sixteen expectations total, 8 KiB keys/
fields, 64 KiB expected values, and the existing aggregate request allowance.
Cluster requires one hash slot. No permission or reusable-policy expansion occurs.

```json
{
  "transaction": [["HSET", "{cart}:item", "quantity", "4"]],
  "watch": [{"key": "{cart}:item", "field": "quantity", "expected": "3"}]
}
```

Field expectations reject fields with their own expiry, including agent requests.
They do not make an arbitrary batch atomic on failure: Redis still cannot roll
back successful commands after execution-time errors. Field renaming, multi-field
drafts and other collection editors remain separate unfinished work.

## Creating and deleting fields

Enter the exact key and field, then choose **Prepare new hash field**. A bounded
TYPE/HEXISTS pipeline checks that the hash exists and the field is absent. This
creates a memory-only draft; it does not write anything. Empty field names and
empty values are supported. Save reviews one HSET with `watch[].expected: null`,
so a competing insertion or removed/retyped hash fails without replacement.
The initial read is not a frozen snapshot; the server rechecks on Save.

For an existing loaded field, **Mark hash field for deletion** toggles a local
deletion draft. It locks/dims the value but does not execute HDEL. Revert restores
the loaded value, or discards a new-field draft completely. Save opens destructive
review with the exact field/original bytes and warns that deleting the last field
removes the hash key and its TTL. Only Apply submits the guarded HDEL. See the
[Redis HDEL contract](https://redis.io/docs/latest/commands/hdel/).

The editor requires a receipt of exactly one created/deleted field (zero newly
created fields for an update). Unexpected counts and lost responses block Save
and Revert until explicit reload/reconciliation; cancellation is not rollback.
After successful creation the draft becomes an existing-field editor. After
successful deletion it clears the snapshot and unlocks key/field selection.
Deletion never automatically recreates a hash. Reopen the editor to select
another field while a loaded snapshot locks the current identity.

All operations retain the same 8 KiB limits, 256 KiB browser reservation,
read-only-profile checks, review ownership and target revisions. Expiring fields
remain rejected even for deletion; this iteration does not change field-expiry
policy or add new MCP tools. Guarded saves require Redis 7.4+ and HPTTL permission.

## Validation

Use an isolated build, not classes used by a running application:

```powershell
mvn -pl code-graph-mcp-http -am test -Djava.awt.headless=true
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
$env:DBA_BROWSER_SUITE = 'native'
./code-graph-dba/test-browser.ps1 -NodeModules '<Playwright node_modules>'
Remove-Item Env:DBA_BROWSER_SUITE
```

Live fixtures use pinned Redis 7.4.1 and Lettuce 7.7.0.RELEASE, with a 256 MiB
test JVM and 64 MiB direct-memory ceiling. The native browser suite includes
the actual shared editor/review/job flow with controlled replies, while live
tests verify the adapter independently. Neither is presented as a complete
browser-to-live-vendor test. Owned resources are removed; pre-existing images
and databases are preserved.

## Acceptance checkpoint — 2026-09-20

- Focused transaction, foundation, ownership, memory and MCP schema tests passed.
- Redis standalone, three-primary Cluster and ACL-authenticated Sentinel each
  passed all four transaction tests without skips. New assertions cover binary
  fields/values, complete original-byte comparisons, key-TTL preservation,
  field-expiry rejection, concurrent changes, removed fields/keys, bounded values
  and explicit absent-field expectations. Existing transaction cancellation,
  read-only, approval/revision, partial-failure and cleanup cases remain passing.
- The isolated 35-module Maven reactor passed: **890 tests, 830 passed, 60
  explicit optional/environment skips, zero failures/errors**. Live Redis gates
  run separately and overlap those test classes; skips are not counted as passes.
- All **19 isolated browser suites passed**. A subsequent strengthened native
  rerun passed read-only protection, unexpected HSET receipts, exact conflict
  feedback, explicit base64 selection after reload and reachable narrow-screen
  footer controls. JavaScript syntax checks passed; the screenshot was inspected.
- Skill frontmatter/reference validation and all **12 skill/bootstrap installer
  tests** passed. The validator's YAML dependency was installed only in the owned
  validation directory, not global Python. The optional maintained skill/reference
  was updated using skill-creator guidance; no MCP configuration was changed.

Evidence is retained under
`target/mcp-efficiency-coverage/redis-hash-editor-6a616c921936462b853a4ddc8e4b75ca/`:
`focused.log`, `full-reactor.log`, `redis-standalone.log`, `redis-cluster.log`,
`redis-sentinel.log`, `live-reports/`, `browser-all.log`,
`browser-native-final.log`, `skill-install.log`, and `skill-validation.log`.
The strengthened browser test initially assumed reload would preserve base64
display for valid UTF-8 bytes. It now explicitly selects the encoding; the failed
attempt remains in `browser-native-test-encoding-assumption.log`. No application
behavior was changed to satisfy that test assumption.

At that checkpoint, the running application's MCP tools were not exposed in the coding session,
so focused filesystem reads were used. No artificial MCP/search-efficiency claim
is made. Existing application processes were not restarted or replaced. Docker
checks confirmed all owned fixtures were removed, the existing Redis image was
reused, and the user's three running database containers remained unchanged.

The preceding editor/UI changes were pushed as `2c98f0b` before this increment.
The existing-field implementation was subsequently committed/pushed as `ff42e5e`
and deployed on request. No live user database was modified. Unsupported
older Redis field-expiry APIs, cloud variants, broader editors, native
administration and remaining platform/performance gates are not claimed complete.

## Field lifecycle follow-up — 2026-09-20

The explicit new-field and staged-deletion implementation is a separate
increment after `ff42e5e`. The requested restart deployed that committed version
on MCP 3000/UI 8137, with desktop approval, admin/DBA enabled, hybrid storage and
the existing 1536 MiB graph/cache allowance. Both pages and MCP initialization
returned HTTP 200; startup reported actions enabled and zero onboarded projects.
Subsequent implementation/testing uses a separate source snapshot, not the live
runtime. No user database/profile was changed by this work.

Validation:

- Focused native ownership, transaction, foundation, memory and MCP schema tests
  passed. The first run caught a real review-path issue: it discarded the
  classifier's last-field-deletion warning. Review now uses that exact reason;
  the original failed run is retained as evidence.
- Redis 7.4.1 standalone, three-primary Cluster and ACL Sentinel each passed all
  four transaction tests with no failures, errors or skips. Added assertions
  exercise the actual TYPE/HEXISTS preview, empty-value insertion, insertion
  races, exact-value deletion conflicts, WATCH races, cancellation before EXEC,
  preservation of other fields/key TTL, duplicate submission rejection and
  last-field deletion. Removed hashes cannot be recreated by the guarded insert.
- The full 35-module package reactor passed: 890 tests, 830 passed, 60 explicit
  optional/environment skips, zero failures/errors. Separate live gates overlap
  those test classes; skips are not represented as passes.
- Native browser tests passed the shared workspace/editor/review flow with
  controlled replies: create/update/delete, empty/absent distinction, destructive
  review cancellation, Revert, read-only guards, keyboard staging, unmount
  retention, bad receipts and uncertain outcomes. The narrow screenshot was
  inspected. This is not a claim of browser-to-live-Redis integration.
- All 19 complete browser suites passed, including graph/DBA layout, editable
  grids/exports, Table/View/query-builder workflows, project contexts, standalone
  catalogs, editor pairing, normal/YOLO approvals and approval-only review.
- JavaScript syntax, maintained skill validation and 12 skill/bootstrap installer
  tests passed. Skill-creator guidance kept the new reference focused on exact
  absence/value expectations, last-field deletion and no unguarded retry; it did
  not widen the optional skill's authority or overwrite installed custom skills.

Evidence:
`target/mcp-efficiency-coverage/redis-hash-lifecycle-581b9bf6ea0944249e13faf5330f9351/`
contains `focused.log` (initial failure), `focused-final.log`, `redis-standalone.log`,
`redis-cluster.log`, `redis-sentinel.log`, `live-reports/`, `full-reactor.log`,
`browser-native.log`, `browser-all.log`, `skill-install.log`, and
`skill-validation.log`. Implementation/test hashes match this isolated build.

Docker harnesses removed their owned containers/volumes and reused the existing
Redis image. The user's three database containers remain running. No broad
pruning was used. At this validation checkpoint, the lifecycle follow-up had not
replaced the running `ff42e5e` feature build. MCP was healthy but its tools were not
exposed to this agent, so focused local reads were used; no search-efficiency
claim is made. Remaining collection editors, native administration, older/cloud
vendor certification and platform/performance gates remain unfinished.
