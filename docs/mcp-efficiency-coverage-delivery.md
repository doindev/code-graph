# MCP efficiency and coverage acceptance

Implementation and local validation checkpoint: **2026-09-19**, Windows x64,
OpenJDK 25+36, Maven 3.9.11, Node 22.22.3. This delivers the bounded subset below,
not the older aspiration of complete native database administration. The user's
running application was not restarted, and nothing was committed or pushed.

## Delivery gates

| Phase | Outcome | Evidence and boundaries |
|---|---|---|
| 0 — frozen baseline | Passed | Source `deffa3fd9e2749577b87403d0cdb61beb85fe202`, frozen archive, catalog and per-call development ledger; adaptive benchmark uses the same frozen repository |
| 1 — contracts | Passed | Canonical `approvalId` versus submitted `requestId` versus execution `jobId`; compatibility aliases, corrected descriptions, generated schemas; graph-only/headless/full catalogs remain distinct |
| 2 — evidence bundles | Passed | `get_symbol_context`: 1–20 IDs, opt-in sections, one generation/result/byte/work budget, cursor paging, per-target errors, exact occurrences and distinct neighbors; no source bodies |
| 3 — freshness and waits | Passed | File hashes/deletions, index-instance and generation guards, 0–5000 ms bounded waits without holding read locks or renewing TTL; job revisions describe the captured response, with ownership/cancellation and race tests |
| 4 — standalone catalogs | Passed | Explicit UUID/name/database/schema; shared cache without shared authority; generation/expiry cursor validation, bounded staging/accounting, revocation, failed-publication preservation and browser catalog manager |
| 5 — method evidence | Passed for documented subsets | Java/JS/TS plus nominal/dynamic adapters, `OVERRIDES` evidence, signature/visibility/receiver checks, generic-parent Java cases, incremental memory/hybrid parity; see [coverage](method-implementation-coverage.md) |
| 6A — MongoDB | Passed for bounded subset | Native definitions/validators/indexes, optional type-only samples, static Spring Data/Mongoose mappings, contracts, public MCP snapshot/compare/catalog flows, standalone/replica-set/sharded fixtures |
| 6B — Redis | Passed for bounded subset | Key/type/prefix/TTL-class observations and mappings/contracts, explicit Sentinel/Cluster, signed bounded scans, real Sentinel promotion, cursor invalidation, cross-slot rejection, public MCP catalog flows |
| 6C — SQL Server | Passed for ordinary-table subset | Native structured definitions, fixed cross-database targets, reviewed creation/alteration/defaults/comments/indexes, rollback, structured migration preparation; special tables/infrastructure remain disabled |
| Performance | Passed measurement gate, mixed outcome | Three independent JVMs per build, 72 graded acquisitions, fewer calls/searches and lower median latency; higher payload bytes and p95/p99 are explicitly reported below |
| Regression | Passed local reactor | All 35 modules verified; 810 tests, 765 passed, 45 skipped, zero failures/errors. Live fixtures run separately; skipped platform/optional gates are not called passes |
| Browser, skill and installer | Passed available local checks | All 18 isolated browser suites, 45 JavaScript syntax checks, skill frontmatter/reference validation; 13 Node installer tests passed (2 client checks skipped), plus 50 skill/149 MCP/39 launcher checks. Native macOS/Linux and real external-client discovery remain unverified |

## What changed

- Shared navigation evidence, generation guards, bounded collection and catalog
  target/storage services replace duplicated paths instead of adding a second graph.
- Context bundles inspect at most 100,000 edges per request; the page limit and
  response-byte limit apply to the whole bundle. Work-limit counts are lower bounds.
- Standalone catalogs admit at most 128 scopes and 128 MiB retained encoded data,
  additionally constrained by shared DBA accounting. Thirty-minute passive expiry
  does not restart on status polls. Old and staged publications are accounted.
- Native snapshots are bounded observations, never complete databases. Mongo
  sampling is opt-in (0–32), type-only and never retains sampled values. Redis
  SCAN/TTL conventions cannot establish absence or relational schema guarantees.
- Existing IDs, read limits, approvals, exact targets, credentials, transport
  restrictions and reduced headless behavior remain intact. Capability discovery
  does not connect every profile and is not authorization.
- Optional skills prefer useful available MCP evidence; unavailable/incomplete
  evidence and actual implementation work still justify ordinary tools. Installers
  copy the maintained references without overwriting customizations.

## Adaptive acquisition benchmark

Question: obtain four known interface-method declarations and their implementations.
The fixture is indexed with the complete frozen repository, using hybrid storage,
a **32 MiB shared graph allowance** and **768 MiB JVM heap maximum**. Each build
runs in three independent JVMs, alternating order, with one warmup and twelve
measured acquisitions per run. The handwritten fixture oracle is not supplied to
the acquisition algorithm; grading rejects false positive/negative answers.

The baseline tries its advertised navigation tools, then searches/reads source
only when method evidence is missing. The candidate uses the new bundle. Neither
workflow performs mandatory verification searches once indexed evidence suffices.

| Per acquisition | Baseline | Candidate |
|---|---:|---:|
| MCP navigation calls | 9 | 2 |
| Filesystem discovery searches | 4 | 0 |
| Discovery source reads | 4 | 0 |
| Median acquisition | 232.3 ms | 118.1 ms |
| p95 | 277.4 ms | 299.3 ms |
| p99 | 306.6 ms | 354.7 ms |
| Median acquired bytes | 8,247 | 12,531 |
| Correct method answers | 144/144 | 144/144 |

Median acquisition improved about **49%**, eliminating eight fallback filesystem
operations for this question. That is the intended benefit: sufficient indexed
evidence avoids discovery work. It is not a blanket speedup claim. Richer evidence
plus backward-compatible text/structured payloads costs more bytes; the measured
tails did not improve. Keep sections opt-in and investigate tail/payload efficiency
before treating this small workload as a universal result.

Index/startup times were 77.9–86.0 s baseline and 78.3–89.0 s candidate. Final
sampled JVM heap was 51.8–100.4 MiB versus 59.6–82.2 MiB; process working set was
303.5–589.7 MiB versus 308.1–337.3 MiB. Disk records were about 84–85 MiB on both.
These noisy shared-host observations do not establish a general memory regression
or improvement. Cache overshoot, heap peaks, GC, process peaks and storage estimates
are retained separately in the raw results. The graph allowance is not a process cap.

Initialization, `tools/list` and `list_projects` are equal bootstrap calls excluded
from navigation counts/timing/bytes. OS caches are warm, not cold-disk measurements.
Only 36 acquisitions/build inform the percentiles. This is an adaptive deterministic
harness, not an LLM/client reasoning or complete coding-task benchmark. The unchanged
Java reference regression fixtures remain in the full test gate.

Raw runs, oracle, summary, development ledger and final reactor evidence:
[validation/mcp-efficiency-coverage](validation/mcp-efficiency-coverage/README.md).
The frozen source ZIP SHA-256 is
`7e7b3ea150bbe3ef87becf846f27515fead2e4d8f4a886115367a0261fbc7b05`.

## Live vendor and security evidence

- Mongo image `sha256:4968f22d0c6c10ef29952f3e807f62872ba22b3312f25803564fbfc08255efc2`,
  Java driver 5.12.0: standalone CRUD/review, public snapshots/compare/cache, replica
  set and sharded router; wrong-topology selection fails, cancellation and no sampled
  value retention verified. No hosted service was provisioned.
- Redis image `sha256:c1e88455c85225310bbea54816e9c3f4b5295815e6dbf80c34d40afc6df28275`,
  Lettuce 7.7.0.RELEASE: standalone public workflows, unsaved Cluster connection
  testing, three-primary scans, cross-slot rejection before writes, Sentinel primary
  promotion and stale-cursor rejection. No automatic uncertain-write replay.
- SQL Server image `sha256:4402d880dd4c34bfa7d8705e56a86cd6c88da80a1f6bbbe741f999e76264a090`,
  Microsoft JDBC 13.4.0.jre11: two live suites passed after correcting collation
  quoting and numeric catalog conversion. EULA accepted only for user-authorized
  disposable development tests. Definition output remains explicitly partial.
- H2 tests cover standalone authorization, revocation, cache generation/TTL,
  resource pressure and snapshot ownership. Native tests reject missing database,
  wrong name, SQL schema on native targets and revoked catalog permission without
  initializing JDBC pools.
- PostgreSQL supplemental regression checks the shared catalog/designer paths;
  its old Explain assertion was corrected to the normalized raw/analysis envelope.
  The supplemental DBA run passed 295 of 321 tests, with 26 optional/platform
  skips and zero failures/errors; its ten PostgreSQL integration tests and three
  target-authorization tests all passed. These overlap the reactor and are not
  added to its test total.

## Remaining limits, not hidden passes

- Methods are bounded declaration evidence, not complete compiler/runtime dispatch.
  Dynamic loading, unsupported generic substitutions, unresolved external types,
  implicit implementations and language-specific exclusions remain documented.
- Native infrastructure administration, subscriptions/change streams, native
  migrations/transactions and custom client TLS
  are outside this increment. SRV DNS, cloud auth/topologies and Azure SQL were
  not certified by local Docker fixtures.
  Separate Sentinel authentication is delivered in the subsequent
  [authenticated Sentinel follow-up](redis-sentinel-auth.md).
  A further [MongoDB collection-rename follow-up](mongodb-collection-rename.md)
  adds bounded, reviewed same-database renames without replacement; broader
  native administration and editing workflows remain incomplete.
  The [collection-settings follow-up](mongodb-collection-settings.md) adds reviewed
  retention/capped settings with bounded preflight; it does not close the broader
  native administration, transaction or graphical-editor backlog.
  The [Redis transaction follow-up](redis-transactions.md) adds bounded reviewed
  WATCH/MULTI/EXEC on standalone, Sentinel and single-slot Cluster targets;
  generic pipelines and broader native administration remain incomplete.
  The subsequent [MongoDB transaction increment](mongodb-transactions.md) adds
  bounded exact-collection atomic CRUD; its live and reactor gates pass, with
  browser acceptance recorded in its separate report. This is not the entire
  native roadmap.
  [Bounded MongoDB change-stream batches](mongodb-change-streams.md) are now a
  separately verified increment, including UI continuation and bounded cursor
  lifecycle. General sessions, persistent subscriptions and broader native
  administration remain incomplete.
  The [Redis stream increment](redis-streams.md) adds finite, reviewed consumer
  delivery/claims/ACKs and group lifecycle, with capped payloads and complete
  delivery-ID receipts. This does not enable permanent consumers or Pub/Sub.
  The [pipeline follow-up](redis-pipelines.md) adds finite scalar/range command
  batching, complete per-command receipts and explicit partial/unknown outcomes.
  Pub/Sub still requires server/channel scope rather than database-local grants;
  scripts/functions and infrastructure administration remain incomplete.
  The [value-operation follow-up](redis-values.md) adds bounded bitmaps/bitfields,
  HyperLogLog and geo commands, with typed results, reviewed PFCOUNT, same-slot
  multi-key validation and standalone/Cluster/ACL-Sentinel acceptance. Module
  operations and graphical native-value editing remain separate unfinished work.
  The subsequent [small-string editor](redis-string-editor.md) adds bounded
  text/base64 drafts, revision-bound exact-value saves and TTL preservation.
  Collection editors and native administration remain unfinished; this increment
  does not claim the whole roadmap is complete.
  The [hash-field editor follow-up](redis-hash-editor.md) extends that shared
  bounded draft/review flow to existing hash fields, explicit field creation and
  staged deletion (see its separate lifecycle acceptance checkpoint); wider collection editors and
  infrastructure administration remain separate unfinished work.
  The [list-item editor follow-up](redis-list-editor.md) adds bounded replacement
  of one current position with length/value checks, not a general list lifecycle
  or historical identity guarantee.
  The [set-member follow-up](redis-set-editor.md) adds explicit staged membership
  changes in existing sets. It does not close general set lifecycle, sorted-set
  editing or infrastructure administration gaps.
- SQL Server support is ordinary disk tables on the pinned 2022 fixture, not all
  versions, editions or special-table operations. Reviewed SQL can still lock or
  fail; uncertain outcomes require reconciliation, never automatic retry.
- The full reactor intentionally skips live credentials/platform/interactive and
  optional performance fixtures without their explicit environment. Local Docker
  fixtures listed above run separately. macOS/Linux vaults and real Copilot,
  Claude/Windsurf discovery were not available. Installer path checks are not
  certification of those native clients.
- The running server's MCP was used for indexed implementation navigation with
  35 purpose/bytes/generation records (including two reported query failures).
  Source reads remained necessary to edit code.
  This is not a complete count of every filesystem tool invocation in development.
- Initial onboarding visibility/progress (about 77 s before publication), client
  catalog caching/filtering and bundle tail/byte efficiency remain explicit
  follow-ups, not silent expansions of this delivery.
  The subsequent [progress/bundle follow-up](mcp-progress-bundle-followup.md)
  implements pending-scan telemetry and optional location-focused bundles;
  its validation and measurements are reported separately below that baseline.

## Recovery, deployment and cleanup

Deploy only after an explicit restart request and reindex to publish method edges
and hashes. Fresh sessions discover the updated catalog; stale client caches may
still require their client's reconnect procedure. Existing page cursors and session
snapshots are not durable across restart. Do not retry writes because a status
request times out; poll/reconcile the existing owned job.

All successful release builds used isolated copies. An early in-place test attempt
failed on a live mapped build file and was abandoned; no restart/deployment followed.
Owned Docker containers/volumes and newly introduced unused Mongo/SQL Server images
were removed. Pre-existing Redis, PostgreSQL, MySQL and unrelated images/containers
were preserved; no broad prune was used. Five obsolete owned build-source copies
were deleted after retaining their logs and final evidence; they are regenerable
from source. The final candidate, frozen baseline and benchmark dataset remain.
No production database was changed.
