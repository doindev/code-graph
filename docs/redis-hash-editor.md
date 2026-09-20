# Bounded Redis hash-field editor

In a Redis Native workspace, choose **Edit Redis hash field**. HGET/HSET/HDEL/
HEXISTS command drafts prefill the exact key and field; HSCAN/HLEN prefill only
the key. Otherwise enter them explicitly. Nothing executes when opening it.

The shared value editor loads one existing field, never the whole hash. Key,
field and value inputs support UTF-8 text or canonical base64, capped at 8 KiB
each. Empty field names and empty values are valid; this editor requires a
nonempty key. Missing, truncated or oversized values cannot become editable
snapshots. Invalid UTF-8 and carriage-return-containing data stay in base64.
Key/field identity and database selection stay fixed until the editor closes.

Save opens the existing exact native review. It uses a single HSET inside a
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
back successful commands after execution-time errors. GUI field insertion,
deletion, renaming, multi-field drafts and other collection editors remain
separate unfinished work; this increment updates existing fields only.

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

The running application's MCP tools were not exposed in this coding session,
so focused filesystem reads were used. No artificial MCP/search-efficiency claim
is made. Existing application processes were not restarted or replaced. Docker
checks confirmed all owned fixtures were removed, the existing Redis image was
reused, and the user's three running database containers remained unchanged.

The preceding editor/UI changes were pushed as `2c98f0b` before this increment.
This hash-editor implementation remains a new local change. No live user database
was modified, and no deployment or additional commit/push occurred. Unsupported
older Redis field-expiry APIs, cloud variants, broader editors, native
administration and remaining platform/performance gates are not claimed complete.
