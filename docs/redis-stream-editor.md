# Redis stream-entry composer

The Redis native workspace's **Compose Redis stream entry** action opens a
bounded, memory-only ordered field/value draft. It is not an in-place editor
for existing entries or a persistent stream consumer.

## Workflow and limits

1. Choose a nonempty text/base64 stream key and **Load stream**. Only TYPE, XLEN
   and PTTL metadata is requested; the composer never reads the entire stream.
2. **Stage new stream entry** creates an empty pair. Add/remove pairs explicitly.
   Empty field names/values and duplicate names are valid; their order is preserved.
3. Choose UTF-8 text or canonical base64 separately per field and value.
   The encoding selector interprets the text; it does not silently convert it.
4. **Save stream entry** validates and opens the existing exact native review.
   Only Apply dispatches XADD key NOMKSTREAM * field value ... .
5. A confirmed append displays its full, string-valued entry ID and clears the
   draft. Load again before preparing another entry. Revert never performs a write.

The UI permits 1–32 pairs, 8 KiB per field/value, 32 KiB combined decoded pair
bytes, and 48 Ki characters of retained draft input. It reserves 512 KiB from
existing workspace accounting before opening. Server native jobs retain their
ordinary resource, result, deadline, ownership, audit and concurrency bounds.
Browser DOM, driver/native overhead and temporary allocations are not a hard RAM cap.
No files, local/browser storage, background timer or idle Redis socket are added.

Key/database targeting is locked while the composer is open. Tab switching
preserves drafts; closing a dirty/uncertain draft asks for confirmation. Removing
or changing a connection invalidates further operations. Read-only profiles
allow metadata loading but not staged additions. Disposal clears values and
releases the reservation; cancellation uses the existing owned job lifecycle.

## Command and receipt contract

The existing native command tool accepts XADD key [NOMKSTREAM] id field value ...
with 1–100 ordered pairs under its existing 128 KiB input bound. The server permits
8 KiB fields and 64 KiB values; the UI intentionally uses smaller draft bounds.
Controls/IDs remain text; supported key/field/value positions accept canonical
base64 objects. No trim, IDMP or other newer options are inferred.

NOMKSTREAM requires Redis 6.2+, prevents creation of an absent key and preserves
the current key TTL on append. It does not detect a key deleted and recreated
under the same name between metadata loading and execution. Wrong types and
insufficient ACLs fail normally. See the
[Redis XADD contract](https://redis.io/docs/latest/commands/xadd/).
Ordinary XADD without NOMKSTREAM retains its existing key-creation behavior.

XADD receipts preserve value and add:

- applied: true with matching entryId/value strings after confirmed append.
- applied: false with null entryId/value when NOMKSTREAM found no key.
- existingStreamOnly: whether NOMKSTREAM was part of the exact reviewed command.

Acknowledged does not itself mean an append happened. No-key results retain the
draft and disable Save until metadata is loaded again. Invalid/missing receipts,
lost replies or uncertain cancellation retain the draft and block Save, Revert
and metadata reload. Use authorized range reads to reconcile the append, then
explicitly **Discard reconciled stream draft**; this never retries or undoes it.
An acknowledged entry ID is retained even if cancellation arrived after execution.
Automatic-ID appends are not idempotent; never blindly resubmit.

This does not add reusable native write approval, implicit acknowledgement,
entry replacement, trimming, key creation, group administration or a subscription.
Existing command-based reviewed XDEL/XTRIM and group operations are unchanged.

## Validation

Focused Java/HTTP/MCP checks passed: 51 discovered, 49 passed and two opt-in live
cases skipped. Redis standalone passed 39 tests (four Mongo-only skips);
three-primary Cluster and ACL-authenticated Sentinel each passed 36 with no skips.
All six stream tests ran in each topology, including real missing/expired keys,
binary/duplicate fields, preserved TTLs, wrong types and early/late cancellation.
The standalone agent-service fixture also exercised normal review and YOLO.
These overlapping topology runs are separate gates, not additional unique tests.

All 19 browser suites passed. The final native browser gate also passed against
the packaged server JAR, including staged drafts, read-only behavior, strict
receipts, uncertainty/reconciliation, bounds and narrow layout. Browser fixtures
and live adapter tests are separate layers; mocked UI writes are not represented
as live user-database tests. The full 35-module Maven reactor passed in 4:02:
902 tests across 182 suites, 839 passed, 63 opt-in/environment skips, no failures
or errors. All 26 DBA JavaScript files and the new browser fixture passed syntax
checks. Skill validation, 12 optional skill/bootstrap helper tests,
Windows bootstrap, 39 launcher, 149 MCP installer, 50 skill installer and two
uninstaller tests passed without modifying real client configuration or PATH.

An initial isolated browser launch failed because its test dependency directory
was missing. It was populated with unchanged third-party JARs from the previous
validated fixture (excluding application JARs), then the gate passed. The system
and bundled Python lacked YAML; skill validation passed with the existing
project-local validation dependency. The bootstrap test's intentionally failing
prerequisite probes leave a native exit status; the wrapper was corrected to
inspect PowerShell script success. No production security rules were weakened.

Narrow-layout screenshot inspection caught a flex-shrink overlap between the
field list and footer. The composer now keeps those children at their natural
height inside its scroller; a geometric no-overlap browser assertion covers it.
Review notices use the actual classified stream operation, so NOMKSTREAM's
missing-key and same-name-recreation limits are visible during Apply review.

The isolated source/log directory is
target/mcp-efficiency-coverage/redis-stream-composer-863b2a1f07e0499d8319dc4f6bf7df15.
Evidence includes reactor.log, browser-all.log, browser-native-packaged.log,
the three redis-*.log live runs and their live-reports directories. The final
source/code-graph-dba/target/redis-stream-editor-fields.png shows the corrected
narrow layout; redis-stream-editor-fields-before.png preserves the original
overlap evidence. Fixtures used Redis 7.4.1 pinned to
redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275
and Lettuce 7.7.0.RELEASE. Owned containers were removed. The final Docker image
inventory exactly matched the 55-image baseline: none added, none removed.
Existing user containers/images were preserved; no broad pruning was used.

Before this increment, the previously validated list-end lifecycle was committed
and pushed as fc47da9. That build was restarted with MCP 3000, admin/DBA UI 8137,
desktop approval, hybrid storage, a 1536 MiB graph/cache budget and no automatic
project onboarding; both pages and MCP initialization returned HTTP 200.
This stream-composer continuation is locally implemented and tested but was not
committed, pushed or deployed again during this turn.

Subsequent checkpoint (2026-09-20): committed and pushed as c7fa379, then
restarted with the same ports and configuration before the
[MongoDB document-editor continuation](mongodb-document-editor.md).

The connected client exposed no code-graph navigation tools despite the healthy
server. Focused filesystem reads were used; no MCP search savings are claimed.
The maintained optional skill reference was updated using skill-creator guidance;
installed/customized client skills were not overwritten.

Reproduction from an isolated checkout:

~~~powershell
mvn -B -ntp -pl code-graph-mcp-http -am test '-Djava.awt.headless=true' '-Dtest=NativeRedisStreamTest,NativeRedisTransactionTest,NativeReviewTest,NativeFoundationTest,NativeMcpHttpTest,DbaToolSchemaTest,ApprovalReviewServerTest' '-Dsurefire.failIfNoSpecifiedTests=false'
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl
./code-graph-dba/test-browser.ps1 -NodeModules <path-to-playwright-node_modules>
mvn -B -ntp package '-Djava.awt.headless=true'
~~~

The broader native roadmap remains incomplete. Native macOS/Linux desktop
verification and a new three-run performance benchmark are separate unverified
gates, not implied by browser mocks or a passing Java build.
