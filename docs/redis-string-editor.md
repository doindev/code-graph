# Bounded Redis string editor

In a Redis **Native workspace**, choose **Edit Redis string value**. GET,
GETRANGE, TYPE and STRLEN command drafts prefill the exact key; otherwise enter
it explicitly. Keys and values support UTF-8 text or canonical base64. The
editor updates existing strings only, up to **8,192 complete bytes**. Unsupported
types, oversized/truncated reads and missing keys cannot establish an editable
snapshot. Carriage-return-containing or invalid UTF-8 values stay in base64 to
avoid textarea normalization. Empty string values are valid; empty keys are not
offered by this editor.

Load uses the existing bounded TYPE/STRLEN/GETRANGE/PTTL pipeline. These reads
are live, not one frozen snapshot. Save performs a fresh exact-byte WATCH check
on the selected key, followed by one reviewed `SET ... XX KEEPTTL` transaction.
The current expiry is preserved, not reset to the earlier displayed TTL.
Deleted/expired keys are not recreated. Concurrent changes cause a conflict;
drafts remain available for reconciliation. Redis TYPE string also covers
application-specific binary formats (including bitmaps/HyperLogLogs): this editor
does not infer their semantics or make arbitrary value replacement harmless.

Save opens the ordinary native review showing the exact target and command.
Cancel in that review sends no write. Revert discards the local draft, not database
changes. Successful SET receipts confirm Save; unknown submission/commit outcomes
disable another Save until the user explicitly reloads and reconciles the key.
No automatic write retries occur. This is a single-key developer workflow, not
a multi-command rollback guarantee. See Redis's [SET options](https://redis.io/docs/latest/commands/set/)
and [WATCH transaction behavior](https://redis.io/docs/latest/develop/using-commands/transactions/).

The database and native Run control are locked while the editor is open.
Connection configuration changes invalidate it rather than silently retargeting
the draft. Read-only profiles cannot Save. Tab switching retains drafts; closing
a dirty/uncertain editor or native tab asks before discarding it. Page unload
warns about drafts. Values are memory-only and are not serialized into workspace
recovery. Closing the native tab disposes its controller and cancels active work;
unmounting it does not.

The editor reserves 256 KiB within the shared browser result allowance, in addition
to normal job/result accounting. It holds no Redis socket while the user edits.
Existing native prepare/apply/execute/job/release paths are reused; browser requests
can include `expectedTargetRevision` from prepare. A mismatch rejects execution.
Loaded drafts carry that revision through Save, including profile changes not yet
observed by the browser. MCP schemas and approval eligibility are unchanged.
The shared optional agent skill documents the corresponding exact-expectation
workflow without granting permission to use it.

## Reproducible validation

Run from an isolated source checkout/copy so tests do not replace running classes:

```powershell
mvn -pl code-graph-dba -am test -Djava.awt.headless=true
$env:DBA_BROWSER_SUITE = 'native'
./code-graph-dba/test-browser.ps1 -NodeModules '<Playwright node_modules>'
Remove-Item Env:DBA_BROWSER_SUITE
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
```

The browser suite exercises the actual component and review/job lifecycle with
controlled replies, separate from live Redis adapter tests. Neither alone claims
full end-to-end browser-to-live-vendor coverage. Disposable Redis fixtures use the
existing pinned 7.4.1 image and ownership-scoped cleanup; existing resources are
preserved. Remaining hash/list/set/zset/stream graphical editors and server
administration are tracked separately in the [native roadmap](native-database-delivery.md).

## Acceptance evidence — 2026-09-20

- Final isolated Maven reactor: **35 modules passed; 890 tests, 830 passed,
  60 explicitly skipped, zero failures/errors**. The skipped cases require
  explicit live/platform/interactive/performance environments and are not claimed
  as passing. Redis live fixtures below run separately from this default gate.
- Redis 7.4.1 standalone, three-primary Cluster and ACL-authenticated Sentinel:
  transaction suites passed, including binary keys, original-value conflicts,
  existing/no-expiry TTL preservation and refusal to recreate removed keys.
  Client: Lettuce 7.7.0.RELEASE; fixture JVM heap 256 MiB/direct memory 64 MiB.
- Native browser regression passed: real component/review modal with controlled
  replies; binary/CRLF handling, empty values, rejected/truncated reads, exact
  configuration revision, denied review, conflicts, lost submission replies,
  read-only profiles, narrow layout, unmount retention and disposal/cancellation.
- The final **19-suite browser smoke run passed**, including native, normal/YOLO
  approvals, connection proposals, editor pairing, grids/exports, catalog/tree,
  table/view/query-builder workflows and workspace recovery. No compiler ran
  concurrently with this final run.
- JavaScript syntax and skill validation passed. Installer/skill regressions:
  **14 passed, 2 opt-in real-client checks skipped**. The four existing stock
  client references were checked against their prior maintained source before
  updating; their final hashes match. MCP configuration was not changed.

Raw evidence is under
`target/mcp-efficiency-coverage/redis-key-editor-dff333ce4e004da08547d87176492a8c/`:
`dba-tests.log`, `full-reactor.log`, `redis-standalone.log`, `redis-cluster.log`,
`redis-sentinel.log`, and browser logs/screenshots in the candidate tree.
The first broad browser attempt is retained as `browser-first-attempt.log`:
one fixture could not load Vault while a concurrent reactor compilation replaced
its classes. It was a test-scheduling failure, not a passing test; final browser
validation is run after compilation in `all-browser-final.log`.

The running application's MCP indexed the new editor at generation 7.
`target/redis-key-editor-navigation.jsonl` contains three purpose/latency/bytes/
generation records (200.8 ms, 132.6 ms and 168.3 ms). Source reads were still used
for implementation. This is not a comprehensive filesystem-call comparison,
benchmark or speedup claim.

Owned test containers were removed; the existing Redis image and all pre-existing
MySQL/PostgreSQL/other user containers were preserved. No broad Docker prune,
production data mutation or application restart is part of this increment.
The preceding pipeline/value changes were committed and pushed as `a6d3b3b`
before editor implementation. The string editor and subsequent toolbar/grid
polish were then committed and pushed as `2c98f0b` on 2026-09-20.
