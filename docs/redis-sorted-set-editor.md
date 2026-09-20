# Bounded Redis sorted-set score editor

The native Redis workspace offers **Edit Redis sorted-set score**. This increment
edits one existing member's finite score. It is not a sorted-set inventory,
creation/deletion/rename tool or complete sorted-set lifecycle editor.

1. Enter a nonempty key and member as text or canonical base64, at most 8 KiB
   each. Empty members are valid.
2. **Load member score** runs TYPE, ZSCORE, ZCARD and PTTL as one finite read
   pipeline. A complete response, existing zset, existing member and finite
   score are required. A missing member is not score zero.
3. Change the score locally. Save remains disabled for invalid or numerically
   unchanged scores; **Revert score draft** restores the last confirmed score.
4. **Save member score** reviews the fixed target and exact guarded ZADD. Save
   rechecks the member's score after WATCH; concurrent key changes invalidate
   EXEC. The key and member cannot change within that checked draft.

Switching tabs retains the draft. Closing or choosing another member asks before
discarding changes. Read-only profiles prohibit editing/saving. Removal or
configuration changes disable the workspace. Unknown receipts or connection loss
retain the draft and block Save/Revert until explicit reload/reconciliation.
Neither client nor server automatically retries a write.

## Scores, expectations and limitations

Redis scores use binary64 precision, not arbitrary-precision decimals. The UI and
watch validator accept decimal/exponent text up to 64 characters, reject NaN,
infinity, overflow, nonzero underflow to zero, whitespace, hexadecimal forms and
non-text expectations. Finite decimals round according to Redis's binary64
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
field/index/length/member; expected must be a finite decimal string, never null,
a JSON number or boolean. Existing key, member, command, aggregate-byte and
Cluster single-slot limits still apply. There is one expectation per key.

The guard requires both an existing sorted set and an existing member. A removed
or expired member/key is not silently recreated by this editor. Ordinary raw ZADD
commands retain their previously supported creation behavior; the graphical
editor always supplies its score expectation. Current score checks cannot detect
every remove/reinsert cycle before WATCH.

ZADD reports **new members added**, so zero is the expected successful receipt
for an existing-member score update. An unexpected receipt is treated as uncertain,
not blindly retried. Redis transactions do not roll back execution-time errors.
Cancellation can arrive after execution: reconcile retained receipts.

Updating a score can change rank and preserves the existing key TTL. GEO indexes
also use zset storage; TYPE cannot prove a key contains ordinary ranking scores.
The UI and review explicitly warn about this. Only known ordinary sorted-set
fixtures are certified by this increment.
See [ZADD](https://redis.io/docs/latest/commands/zadd/),
[ZSCORE](https://redis.io/docs/latest/commands/zscore/) and
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

## Acceptance

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

The already-committed set-editor build was restarted on MCP 3000/UI 8137 before
this increment. This score-editor increment has not been deployed. Code-graph
navigation tools were not exposed to this coding session, so focused source
reads were used; no claim of MCP navigation savings is made for this increment.
Native macOS/Linux UI sessions and a new three-run performance benchmark were
not exercised. The remaining native roadmap is not completed by this editor.
