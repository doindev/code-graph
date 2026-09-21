# Bounded Redis string editor

Current lifecycle follow-up evidence is recorded in
[the checkpoints below](#string-lifecycle-checkpoint).

In a Redis **Native workspace**, choose **Edit Redis string value**. GET,
GETRANGE, TYPE and STRLEN command drafts prefill the exact key; otherwise enter
it explicitly. Keys and values support UTF-8 text or canonical base64. The
editor updates existing strings, explicitly creates absent keys and stages
whole-string deletion, up to **8,192 complete bytes**. Unsupported
types, oversized/truncated reads and missing keys cannot establish a loaded
replacement/deletion snapshot. Carriage-return-containing or invalid UTF-8 values stay in base64 to
avoid textarea normalization. Empty string values are valid; empty keys are not
offered by this editor.

**Prepare new string** checks TYPE only; it never writes or reads an existing
value. If absent, it starts a dirty draft (including a valid empty value). Save
reviews one `SET key value NX` with `watch.expected: null`. Absence is rechecked
inside the managed WATCH transaction, and a competing creation conflicts rather
than being overwritten. New strings default to **no expiry**, shown before Save;
an explicit **Expire after** choice adds EX seconds to the same SET.
Revert discards a new draft. After confirmed creation, Load again to edit it;
the retained submitted text is not represented as a fresh database read.

**Mark string key for deletion** stages removal of the loaded whole key and TTL,
not an empty-string assignment. Save reviews one `DEL key` with the complete
original bytes as its WATCH expectation. The editor requires an acknowledged
one-key deletion receipt. Changed, expired, missing or differently typed keys
conflict; no deletion is sent while drafting or canceling review. Revert undoes
the staged deletion. New, unsaved drafts cannot be marked for database deletion.

Load uses the existing bounded TYPE/STRLEN/GETRANGE/PTTL pipeline. These reads
are live, not one frozen snapshot. Save performs a fresh exact-byte WATCH check
on the selected key, followed by one reviewed `SET ... XX KEEPTTL` transaction.
The current expiry is preserved, not reset to the earlier displayed TTL.
Deleted/expired keys are not recreated. Concurrent changes cause a conflict;
drafts remain available for reconciliation. Redis TYPE string also covers
application-specific binary formats (including bitmaps/HyperLogLogs): this editor
does not infer their semantics or make arbitrary value replacement harmless.

## Staged expiry

The string editor offers **Preserve current expiry**, **No expiry**, and
**Expire after**. Existing strings default to preserve; new strings default to
no expiry and cannot select preserve. Expire after accepts 1–2147483647 whole
ASCII-digit seconds. Invalid, fractional, negative, zero, empty and oversized
durations disable Save and show corrective guidance. Typing does not send a write.

Expiry-only edits are dirty drafts. Revert restores preserve mode for a loaded
string, and review Cancel retains the draft. Save generates exactly one SET:
XX KEEPTTL to preserve an existing expiry, XX (without expiry options) to remove
it, or XX EX seconds to set it. Creation uses NX, optionally with EX seconds.
This rewrites the same string bytes even for expiry-only changes and may trigger
SET/keyspace-notification effects. It is not a metadata-only EXPIRE operation.
Deletion ignores the expiry draft and reviews DEL instead.

The duration starts when Redis executes the command, not when loaded, drafted or
reviewed. Exact original-byte/absence WATCH guards remain required. The earlier
PTTL reading is informational, not a compare-and-set condition: expiry changes
before WATCH are not detected by a value expectation alone; expiry changes during
the watched transaction conflict. Expired/deleted keys are never recreated by an
existing-string save. Explicit expiry saves require Load again before further
editing; displayed submitted values are not claimed to be a fresh read.
Hash/list/other-key expiry editors and rename remain separate unfinished work.

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

The browser fixture also needs its test-scope dependency JARs under
`code-graph-dba/target/test-lib`; assemble those with Maven (or copy an unchanged,
verified dependency directory). Missing fixture dependencies are a setup failure,
not a passing browser test. Do not compile the same classes while a browser
fixture is running.

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
preserved. Further key lifecycle, rename, other-key TTL editing and server administration
are tracked separately in the [native roadmap](native-database-delivery.md).

## Original replacement-editor acceptance evidence — 2026-09-20

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

## String lifecycle checkpoint

The 2026-09-20 follow-up adds explicit New drafts and staged whole-string
deletion without new endpoints, MCP schemas or approval permissions. It reuses
the existing managed transaction, exact target revision, review, cancellation,
byte accounting and cleanup services. Browser-only drafts remain bounded to
8 KiB values, preserve binary/empty content and survive tab remounts, not refreshes.

- Focused DBA, ownership/review and HTTP/MCP checks: 46 passed, two explicit
  live-fixture skips, zero failures/errors.
- Redis 7.4.1 standalone: 39 passed, four Mongo-only skips; Cluster and ACL
  Sentinel: 36 passed each, no skips/failures. All use Lettuce 7.7.0.RELEASE,
  the pinned Redis image above, a 256 MiB test heap and 64 MiB direct-memory limit.
- Live additions verify the exact UI SET NX and DEL transactions, empty/binary
  content, no-expiry creation, changed values, wrong types, missing keys,
  competing insert/delete races, pre-execution cancellation and no replay after
  confirmed deletion. Existing normal-agent/YOLO and topology checks also run.
- Focused native browser checks passed: no preparation writes, existing-key
  rejection, draft Revert, denied reviews, exact commands/revisions, staged
  deletion, conflicts, lost replies, read-only profiles, tab remount, disposal
  and narrow layout. Screenshot inspection found no overlapping controls.
- Skill validation, 11 grid unit tests, Windows bootstrap checks and 14 Node
  installer/skill checks passed. Two optional real-client checks remain skipped.

The full Maven reactor/package passed: 35 modules, 183 suites, 910 tests;
845 passed, 65 explicit opt-in/platform skips, zero failures/errors (4:14).
All 20 final browser suite groups passed sequentially after packaging. All 29
DBA JavaScript modules passed syntax checks. Production/test source hashes match
the isolated snapshot; no compiler replaced browser fixture classes during this run.
Evidence: target/redis-string-lifecycle-a6b4b471663e4a428a0dadd84eccaca9,
including focused.log, browser-native-rerun.log, redis-standalone.log,
redis-cluster.log, redis-sentinel.log, live-reports and final regression logs.
The first browser-native.log is retained as a failed setup attempt: missing
test-lib dependencies prevented the fixture starting. Supplying the unchanged
verified dependency directory fixed setup; the complete native rerun passed.

Only owned fixture containers/volumes were removed. The 55-image baseline is
unchanged; no existing databases/images or installed client configuration were
modified. Native rename/general TTL editors, broader infrastructure support and
platform/performance certification are not completed by this increment.
Commit/push/restart follow the user's explicit per-task delivery instruction.

## String expiry checkpoint — 2026-09-21

This increment adds staged expiry-only/value-and-expiry edits and expiring new
strings, with no new endpoint, MCP permission or live-setting changes.

- Focused DBA/review/HTTP/MCP: 47 passed, two explicit live-fixture skips.
- Redis 7.4.1 standalone: 40 passed, four Mongo-only skips; three-primary Cluster
  and ACL-authenticated Sentinel: 37 passed each, no skips/failures. Fixtures use
  Lettuce 7.7.0.RELEASE, a 256 MiB heap and 64 MiB direct-memory limit.
- Live checks cover the exact single-command EX/KEEPTTL/no-expiry forms, unchanged
  binary bytes for expiry-only edits, absent-key expiring creation, competing
  value/expiry changes, expiry removal and refusal to recreate expired keys.
- Focused native browser suite passed: numeric bounds, explicit expiry drafts,
  cancellation without writes, Revert, remount retention, exact reviewed commands,
  required reload after confirmed changes, uncertainty locks and read-only modes.
  Existing hash/list/set/sorted-set/stream and Mongo editor checks also passed.
- Full reactor/package: 35 modules, 183 suites, 911 tests; 846 passed, 65 explicit
  opt-in/platform skips, zero failures/errors (4:02). This is not a claim that
  skipped vendor/platform gates passed.
- All 29 DBA JavaScript modules pass syntax checks; skill validation, 11 grid
  unit tests, Windows bootstrap checks and 14 installer/skill checks pass.
  Two optional real-client checks remain unverified.

All 20 final browser suite groups passed sequentially after packaging, including
native editors, grids/search/export, object/table/view designers, catalogs,
workspace recovery, editor pairing and ordinary/YOLO approval behavior. No
compiler modified fixture classes during these runs. Evidence is retained
under target/redis-string-expiry-f396647ef1ff47958b30bd8a3a917816:
focused.log, browser-native.log, redis-standalone.log, redis-cluster.log,
redis-sentinel.log, live-reports, full-reactor.log and all-browser.log.
Owned Redis containers/volumes were removed. Docker image identities match the
55-image pre-test baseline; pre-existing resources were preserved.
The code-graph MCP tools were not exposed to this client, so implementation used
focused local reads; no MCP-versus-filesystem performance claim is made.
