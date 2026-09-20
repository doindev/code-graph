# Bounded Redis sorted-set member editor

The native Redis workspace offers **Edit Redis sorted-set member**: update a
finite score, explicitly stage an absent member's insertion, or stage a member's
deletion within an existing sorted set. It is not an inventory, key-creation,
rename or bulk editing tool.

1. Enter a nonempty key and member as text or canonical base64, at most 8 KiB
   each. Empty members are valid.
2. **Load member score** runs TYPE, ZSCORE, ZCARD and PTTL as one finite read
   pipeline. A complete response and existing zset are required. Existing
   scores must be finite; an absent member is represented distinctly from zero.
3. Change an existing score locally, **Stage member insertion** and enter a
   finite score for an absent member, or **Mark member for deletion**. Typing
   a score alone never stages insertion. **Revert sorted-set draft** restores
   the last confirmed state without writing.
4. **Save sorted-set member** reviews the fixed target and exact guarded ZADD
   or ZREM. Save rechecks score/absence after WATCH; concurrent key changes
   invalidate EXEC. Key/member identity stays fixed throughout the draft.
   Deletion is destructive review; deleting the last member removes key and TTL.

Switching tabs retains the draft. Closing or choosing another member asks before
discarding changes. Read-only profiles prohibit editing/saving. Removal or
configuration changes disable the workspace. Unknown receipts or connection loss
retain the draft and block Save/Revert until explicit reload/reconciliation.
Neither client nor server automatically retries a write.

## Scores, expectations and limitations

Redis scores use binary64 precision, not arbitrary-precision decimals. The UI and
watch validator accept decimal/exponent text up to 64 characters, reject NaN,
infinity, overflow, nonzero underflow to zero, whitespace, hexadecimal forms and
non-text numeric expectations. Explicit null is absence, not a numeric score.
Finite decimals round according to Redis's binary64
semantics. Positive and negative zero compare equally. Do not use this editor to
promise exact large-integer/decimal accounting.

The managed transaction shape is:

```json
{
  "transaction": [["ZADD", "rankings", "2.5", "member"]],
  "watch": [{"key": "rankings", "scoreMember": "member", "expected": "1.25"}]
}
```

Use the existing native API/MCP target selection, review and job lifecycle.
Discover actual schemas before using scoreMember. It cannot combine with
field/index/length/member; expected must be a finite decimal string for an
existing member, or explicit null for a new member. JSON numbers/booleans are
invalid. Existing key, member, command, aggregate-byte and
Cluster single-slot limits still apply. There is one expectation per key.

The guard always requires an existing sorted set. An update/deletion additionally
requires an existing member matching its original score; insertion requires
explicit absence. Missing/expired keys are never recreated. A successful deletion
clears the baseline and requires another load before editing again. Ordinary raw
ZADD retains its existing creation behavior; the editor always supplies its
expectation. Current-state checks cannot detect every remove/reinsert cycle
before WATCH.

ZADD reports **new members added**, so zero is the expected successful receipt
for an existing-member score update, one for insertion. Guarded single-member
ZREM must report one removal. An unexpected receipt is treated as uncertain,
not blindly retried. Redis transactions do not roll back execution-time errors.
Cancellation can arrive after execution: reconcile retained receipts.

Updating/adding a score can change rank and preserves the existing key TTL.
Deleting a member preserves TTL unless it removes the last member and key. GEO indexes
also use zset storage; TYPE cannot prove a key contains ordinary ranking scores.
The UI and review explicitly warn about this. Only known ordinary sorted-set
fixtures are certified by this increment.
See [ZADD](https://redis.io/docs/latest/commands/zadd/),
[ZSCORE](https://redis.io/docs/latest/commands/zscore/),
[ZREM](https://redis.io/docs/latest/commands/zrem/), and
[Redis transactions](https://redis.io/docs/latest/develop/using-commands/transactions/).

The set and sorted-set editors share binary identity controls, dirty-state
protection, review/job services, cleanup and the existing 256 KiB editor
reservation. No full collection or idle database connection is retained.
Editor-only drafts stay in memory; ordinary native command text retains its
existing session recovery rules. No new REST route, MCP tool, persistent grant
or approval bypass is introduced.

## Reproducible validation

Use an isolated source copy. Test harnesses create only owned disposable
containers and remove those resources and newly introduced unused images.
They never prune unrelated databases or images.

```powershell
mvn -B -ntp -pl code-graph-mcp-http -am test '-Dtest=NativeRedisScoreTest,NativeRedisSetTest,NativeRedisListTest,NativeRedisTransactionTest,NativeRedisPipelineTest,NativeMemoryTest,ApprovalReviewServerTest,DbaToolSchemaTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Djava.awt.headless=true'
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster -BuildRoot .
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl -BuildRoot .
./code-graph-dba/test-browser.ps1 -NodeModules '<Playwright node_modules>'
mvn -B -ntp package '-Djava.awt.headless=true'
node --test skills/install-skill.test.mjs installer/skill-bootstrap.test.mjs
```

## Initial score-update acceptance (prior checkpoint)

Validated on Windows with OpenJDK 25, Maven 3.9.11, Node 22.22.3 and Docker:

- Focused Java/schema/security tests: 55 discovered, 50 passed, five opt-in live
  cases skipped here and executed through the owned fixtures below; no failures.
- Redis standalone: 37 passed, four Mongo-specific cases skipped. Cluster and
  ACL-authenticated Sentinel: 34 passed each, no skips. The three score-editor
  tests passed in each topology. These overlapping suites are not additive.
- Redis 7.4.1, Lettuce 7.7.0.RELEASE, pinned image
  `sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`.
  Fixtures ran sequentially with a 256 MiB JVM heap and 64 MiB direct-memory
  limit. Binary/empty members, finite-score validation, stale/missing members,
  WATCH races, TTL retention, cancellation and revocation-before-EXEC passed.
- Native browser checks passed, including exact reviewed payloads, score-zero
  versus absence, draft recovery, unknown receipts, read-only guards and a
  visually inspected 420 px layout. All 19 browser suites subsequently passed.
  A final native-browser pass against packaged resources also passed after
  tightening JavaScript trailing-whitespace validation to match the server.
  The initial broad run exposed a pre-existing approval-fixture race: it queried
  status immediately after clicking Deny, before the asynchronous POST finished.
  Rejection checks now await the exact decision response and retain their
  server-state assertions. No production approval behavior was relaxed. The
  isolated project-context recheck passed; the initial failed log is retained.
  A second run exposed mutable-cookie teardown timing: a late bootstrap could
  replace the cookie consulted by the logout assertion. Cleanup now blocks new
  bootstrap requests and verifies the exact revoked cookie returns 403, with
  at most three cleanup attempts if a late response changes the cookie jar.
  The catalog fixture passed three rechecks, but the following broad run
  exposed an invalid test-helper wait for requestfinished after page closure.
  That event-drain assumption was removed; production session limits and
  authentication were unchanged. All unsuccessful broad-run logs are retained.
- All 25 DBA JavaScript modules passed syntax checks. Shared skill validation
  and 12 optional skill/bootstrap helper tests passed. Windows bootstrap,
  39 launcher, 149 MCP installer, 50 skill installer and two uninstaller tests
  passed without modifying actual client configuration.
- Full 35-module Maven package passed: 900 tests in 182 suites, 837 passed,
  63 environment/opt-in skips, zero failures/errors, in 3 minutes 56 seconds.
  This includes graph/index/storage, DBA and HTTP/stdio MCP regressions.
  Skipped fixtures are not claimed as passing; live Redis gates ran separately.

Raw logs, preserved per-topology XML and the isolated source build are under
`target/mcp-efficiency-coverage/redis-score-editor-e64a2d9d905a40c985b4d1602c868ebc/`.
The narrow screenshot is `source/code-graph-dba/target/redis-score-editor.png`.
The completed broad run is `browser-all-verified.log`, with
`browser-native-packaged.log` for the final editor recheck and `reactor.log`
for Maven. Earlier browser logs preserve unsuccessful runs and their corrections.
Owned Redis containers were removed. The pinned image was already present and
was preserved; no unrelated images, containers or databases were removed.
The post-reactor inventory contained no newly introduced images or leftover
owned native/Testcontainers fixtures.

At that checkpoint the set-editor build ran on MCP 3000/UI 8137. The score-editor
build was subsequently committed as `8679473` and restarted on those ports.
Code-graph
navigation tools were not exposed to this coding session, so focused source
reads were used; no claim of MCP navigation savings is made for this increment.
Native macOS/Linux UI sessions and a new three-run performance benchmark were
not exercised. The remaining native roadmap is not completed by this editor.

## Member insertion/deletion follow-up

The shared member editor now stages insertion and deletion within existing
sorted sets. An insertion uses explicit null in its scoreMember expectation;
an update/deletion uses the last confirmed finite score. It never recreates a
missing, expired or wrong-type key, and a stale expectation does not fall back
to an unguarded command. Typing an absent member's score is protected as an
unsaved draft but is not sufficient to enable Save: insertion must be staged.

Deletion retains its original-score guard even when an unfinished new-score
draft is invalid. Canceling review preserves the draft. Successful deletion
invalidates the local baseline; unexpected integer receipts and lost/cancelled
outcomes require explicit reload/reconciliation rather than automatic retry.

Validation checkpoint (September 20, 2026):

- Focused Java/security/schema gate: 55 tests, 50 passed and five opt-in live
  skips. Live tests subsequently exercised the score suite in every topology.
- Standalone Redis: 37 passed, four Mongo-only skips. Cluster and authenticated
  Sentinel: 34 passed each, no skips or failures. These overlapping suites
  are separate gates, not additive coverage. New live assertions cover binary
  insertion, pre-existing member rejection, score-change and WATCH races,
  deletion, last-member key/TTL removal, missing/expired/wrong-type keys,
  cancellation and authority revocation before EXEC, and resource release.
- Native browser gate passed: explicit staging, NULL versus zero, keyboard
  staging, Cancel/Revert/close protection, exact destructive review, receipts,
  unknown outcomes, read-only guards, tab remounting, cleanup and 420 px layout.
- All 25 DBA JavaScript modules passed syntax checks. The optional skill passed
  frontmatter/reference validation and its 12 installer/bootstrap tests.
  Windows bootstrap, 39 launcher, 149 MCP installer, 50 skill installer and
  two uninstaller checks passed without modifying actual client configuration.
- All 19 browser suites passed. A final packaged-resource native pass also
  covered unstaging deletion and deleting despite an invalid unfinished score
  draft, using the original score guard. No page errors were reported.
- Full 35-module Maven package: 900 tests across 182 suites, 837 passed, 63
  environment/opt-in skips, zero failures/errors, in 4 minutes 5 seconds.
  Live Redis gates ran separately; skipped environments are not claimed passed.

Reproducible commands are above. Raw logs, isolated source, screenshots and
per-topology XML are retained under
`target/mcp-efficiency-coverage/redis-sorted-set-lifecycle-bdaae8f7379f4c7eb970cdac412ec01d/`.
Fixtures ran sequentially on the same pinned Redis/Lettuce versions and
256 MiB heap/64 MiB direct-memory limits as the prior checkpoint. No user
database was mutated. Owned Redis containers were removed; the pre-existing
Redis image was preserved.
No owned native/Testcontainers resources remained after the reactor. All 55
pre-existing image IDs were preserved and no new image IDs remained. Logs:
`focused.log`, `redis-standalone.log`, `redis-cluster.log`, `redis-sentinel.log`,
`browser-all.log`, `browser-native-packaged.log`, `reactor.log`, `skill-tests.log`
and the five `installer-*.log` files; XML is under `live-reports/`.

This follow-up is not deployed or committed by this validation step. The
running application remains the pushed score-editor build `8679473` on
MCP 3000/admin UI 8137. No new cross-platform native UI certification or
three-run performance claim is made. Whole-key lifecycle, bulk collection
editing, streams UI and broader native administration remain unfinished.
Code-graph navigation tools were still unavailable in this coding session;
focused source reads were used without a claim of MCP navigation savings.
