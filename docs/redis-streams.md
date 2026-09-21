# Bounded Redis streams and consumer groups

The native workspace and `dba_request_native_command` accept these finite,
single-stream argument arrays. Target the exact profile and logical database;
Cluster always uses database 0. No new tool or subscription endpoint is added.

| Command form | Effect |
| --- | --- |
| `XREAD COUNT n STREAMS key id` | Read, no wait; `$` means the current end |
| `XPENDING key group start end count [consumer]` | Read bounded pending-entry details; `-`/`+` or full ID bounds, optional exclusive `(` |
| `XADD key id field value [field value ...]` | Add an entry; `*` generates an ID |
| `XADD key NOMKSTREAM id field value [field value ...]` | Append only to a currently existing stream; a missing key returns an explicit no-change receipt |
| `XREADGROUP GROUP group consumer COUNT n STREAMS key id` | Changes delivery/pending state; `>` selects never-delivered messages |
| `XACK key group id [id ...]` | Explicitly acknowledge only those message IDs |
| `XCLAIM key group consumer min-idle-ms id [id ...]` | Claim explicit pending IDs |
| `XAUTOCLAIM key group consumer min-idle-ms start COUNT n` | Bounded pending scan/claim; inspect returned next cursor |
| `XGROUP CREATE key group id [MKSTREAM]` | Create group; stream creation must be explicitly requested |
| `XGROUP CREATECONSUMER key group consumer` | Create consumer |
| `XGROUP SETID key group id` | Destructive delivery-position change; `$` is supported |
| `XGROUP DELCONSUMER key group consumer` | Destructive consumer/pending removal |
| `XGROUP DESTROY key group` | Destructive group/pending removal |
| `XDEL key id [id ...]` | Delete entries; pending records may remain |
| `XTRIM key MAXLEN count` or `XTRIM key MINID id` | Exact destructive trimming, not approximate trimming |

Use complete `milliseconds-sequence` IDs, retaining their unsigned 64-bit parts
as strings. No shorthand `0`, `BLOCK`, `NOACK`, multiple streams, arbitrary extra
options, stream commands inside managed MULTI/EXEC, or implicit group creation.
Counts and explicit ID lists are limited to 100 and the job's row ceiling.
Commands remain within the existing 128 KiB aggregate input limit; key, group,
consumer and field names accept text/canonical base64 up to 8 KiB; values up to
64 KiB. Controls, IDs and counts remain text. Duplicate stream field names are
preserved as ordered field/value pairs rather than collapsed into a map.

## Approval, resources and recovery

The [stream-entry composer](redis-stream-editor.md) stages bounded ordered pairs
in the UI and uses the existing-only form. XADD receipts include applied, entryId
and existingStreamOnly; an acknowledged null receipt is not a successful append.
NOMKSTREAM does not establish historical key identity. No retries or newer
idempotency/trimming options are added.

XREADGROUP changes pending entries and delivery counters even when reading a
consumer's already-pending history. It is **not a safe read**. See the
[Redis command contract](https://redis.io/docs/latest/commands/xreadgroup/).
Mutations use ordinary exact review, or explicit startup YOLO, and still obey
read-only profiles, revision/ownership checks and audit-before-execution. There
are no reusable native write grants. UI example selection only replaces a draft
after confirmation; Run still performs the review. Each operation owns a primary
connection; none stays open between jobs or automatically replays after failover.

Retained results obey the existing row/byte limits, with receipt space reserved
before dispatch. Decoding uses the existing 8 MiB per-socket wire guard and RESP
depth/value-count bounds under native job admission. Payloads show bounded
base64/UTF-8 previews; at most 100 field pairs and approximately 16 KiB of preview
bytes per message. This is not a hard cap on total JVM/native RAM or remote Redis
work. Exact trimming of a large stream can still be expensive.

`deliveredIds` retains **all** IDs returned by a group read/claim even when payload
projection omits/truncates entries. `automaticAcknowledgement` is always false.
The browser reserves 512 KiB of its existing result allowance before dispatch;
when full payload display would exceed that allowance it retains a compact
receipt instead. Insufficient receipt space rejects admission before execution.
Do not ACK incomplete payloads as processed. Pending history can have a missing
body after deletion. XAUTOCLAIM exposes `nextCursor`, `scanComplete` and (Redis 7+)
`deletedPendingIds`; those removed pending records are not delivered messages.
Its scan can examine up to ten times COUNT; see [XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/).
Neither an empty batch nor an end cursor promises a frozen stream snapshot.

- `not_started`: cancellation/rejection before dispatch.
- `acknowledged`: Redis returned this mutation's receipt; inspect counts/IDs.
- `rejected`: Redis explicitly rejected the single command.
- `partial_or_unknown`: dispatch may have occurred without a confirmed receipt.

Cancellation, timeout, disconnection and later authorization revocation cannot
undo already delivered, claimed, acknowledged or deleted messages. Reconcile with
authorized pending/range reads before a new request. Never blindly replay a write.
Closing a workspace requests cancellation and releases the operation connection;
it does not delete the Redis group. No background timers or subscriptions exist.

## Reproducible validation

Use an isolated build (do not overwrite classes used by a running application):

```powershell
./scripts/Test-IsolatedReactor.ps1 -Name redis-streams -Modules code-graph-mcp-http -Tests 'NativeRedisStreamTest,NativeRedisTransactionTest,NativeReviewTest,NativeMcpHttpTest,DbaToolSchemaTest'
# From the printed isolated source directory:
./code-graph-dba/test-native-vendors.ps1 -Engine redis
./code-graph-dba/test-redis-topologies.ps1 -Topology cluster
./code-graph-dba/test-redis-topologies.ps1 -Topology sentinel -SentinelAuth acl
```

Pinned target: Redis 7.4.1 image
`redis@sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`,
Lettuce 7.7.0.RELEASE, JDK 25. Harnesses use unique ownership-labelled containers
and clean only those resources and newly introduced unused images.

The compound native roadmap remains incomplete: this is finite consumer-group
handling, not Pub/Sub, permanent consumers, Redis modules, infrastructure
administration, or certification of all Redis versions/TLS configurations.
## Acceptance — 2026-09-20

- Live Redis stream fixture: five cases passed in each of standalone, three-primary
  Cluster and ACL-authenticated Sentinel. Includes exact grammar/binary data,
  duplicate fields, pending history, claims, Redis 7 deleted-pending receipts,
  explicit ACKs, group lifecycle, logical-database isolation, early/late
  cancellation, revocation, wrong-type errors and cleanup.
- The large-payload case delivers 60 messages, retains every delivery ID while
  the response stays within the 1 MiB agent allowance, and proves that all 60
  messages remain pending. Runs use `-Xmx256m -XX:MaxDirectMemorySize=64m`.
- Native vendor workflows: three cases passed, including real normal approval
  and YOLO stream requests through the shared agent service and job lifecycle.
  HTTP/MCP tests cover ownership, CSRF, denial, read classification and no
  connection/execution before approval.
- Full Maven reactor: 35 modules passed; 881 tests reported, 823 passed,
  58 environment/optional tests skipped, no failures/errors. Live fixture results
  above are separate from those deliberate skips.
- Installer/skill regression: 13 passed, two opt-in real-client CLI smoke checks
  skipped. The official skill validator passed using task-local PyYAML 6.0.2;
  no global client configuration or customized skill was overwritten.
- Self-hosted MCP found the new adapter and executor at generations 6 and 10;
  acquisitions are retained in `target/redis-streams-navigation.jsonl`. Source
  reads were still used for implementation and tests. This is dogfooding evidence,
  not a claimed end-to-end speed benchmark.
- Raw logs, screenshots and retained XML are in
  `target/mcp-efficiency-coverage/redis-streams-e378f3d2ac2c4fb483bb3f6fa61e545c/`.
  Early runs exposed a blank pre-dispatch cancellation outcome and an outdated
  malformed classifier fixture; both were corrected. A concurrent browser/build
  class-file race was diagnosed and final suites run sequentially.
- Owned containers/volumes were removed. Redis's image was already installed and
  was preserved; no new image remains. Existing user databases were untouched.

All 19 browser suites passed sequentially. The native suite then passed again
with the final browser-budget guard: admission reserves compact-receipt space,
overflow displays exact delivery IDs/outcome in JSON instead of an empty grid,
no automatic ACK/retry occurs, and rejected admission never calls the API.
It also covers example confirmation, explicit review/cancel/apply, disabled busy
controls, independent command drafts, narrow layout and disposal. JavaScript
syntax and patch-whitespace checks passed.

The packaged build was deployed on loopback MCP **3000** and admin/DBA UI **8137**,
with normal desktop approvals, hybrid graph storage and the existing **1.5 GiB**
shared graph/cache allowance. Startup had zero automatically onboarded projects;
this repository was subsequently onboarded explicitly for MCP navigation.
Root/DBA pages returned HTTP 200; fresh MCP discovery returned 69 tools and the
new stream schema. The served native UI contains the compact-receipt guard.
No commit/push or changes to user database records were performed.
