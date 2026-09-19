# Symbol-search regression fix

Status: implemented, packaged and tested locally; no deployment/restart.
Validation date: September 18, 2026 (America/Chicago; raw timestamps use UTC).

Follow-up: [sustained search plus watcher-edit measurements](../mixed-search/README.md)
now cover concurrent operation. Ordinary body edits retain near-idle search
latency; declaration changes expose a broad re-resolution freshness bottleneck.

## Decision

Keep bounded incremental indexing. Fresh answers are essential, and the large
name-search slowdown can be addressed without reverting incremental publication
or weakening cancellation safety.

The production change is limited to PagedGraph's MVStore page-split target:
**4 KiB -> 32 KiB**. The asynchronous file channel, immutable version leases,
1 MiB dirty-write threshold, shared cache allowance, scan-resistant caching,
query bounds and search semantics are unchanged. No new cache, complete Java
graph copy, search index, configuration option or public API is introduced.

The split setting describes estimated in-memory page contents before splitting,
not a hard maximum page/record size. Existing 8 MiB record limits still apply.
[Pinned H2 2.4.240 builder contract](https://raw.githubusercontent.com/h2database/h2database/version-2.4.240/h2/src/main/org/h2/mvstore/MVStore.java).

## Diagnosis

SearchSymbolsTool streams the node inventory; it does not use the cached
point-lookup path. Switching to cancellation-safe asynchronous I/O had made its
many small page reads expensive.

StorageReadProbe.java holds records, compression, disabled page caching and
snapshot semantics constant while varying the channel and page-split target.
Three rotated passes in one JVM use 6,563 deterministic synthetic records:

| Channel / split target | Median of scan medians | Read operations per scan |
|---|---:|---:|
| Synchronous / 4 KiB | 29.90 ms | 2,281 |
| Asynchronous / 4 KiB | 73.19 ms | 2,281 |
| Asynchronous / 16 KiB | 33.84 ms | 619 |
| Asynchronous / 32 KiB | 27.76 ms | 308 |
| Asynchronous / 64 KiB | 26.53 ms | 283 |

The probe supports the small-read overhead explanation; it is not itself an
MCP or independent-process benchmark. 32 KiB captures most of the measured
scan benefit without choosing the largest pages. In this probe, uncached point
lookups also improve relative to asynchronous 4 KiB, but remain slower than
synchronous I/O; the latter cannot replace the cancellation-safe path.

## Real MCP comparison

The existing symbol-search harness runs **three independent JVMs per build**
against the identical frozen repository: 506 indexed files, 6,563 symbols and
26,702 edges. Both use a 768 MiB JVM ceiling and a 32 MiB shared graph/cache
allowance. No concurrent builds/tests/source edits ran during these comparisons.
The user's existing application was left running.

Each build receives 3 first searches, 72 excluded warmups, 360 mixed searches
and 180 repeated exact-name searches. Transport timing includes HTTP response
receipt and JSON decoding, not agent/model scheduling. OS file caches are not
flushed; the first query follows indexing and is not cold-disk timing.

Pooled mixed-search latency in milliseconds:

| Percentile | Old engine, rerun | Regressed incremental build, prior comparison | Fixed incremental build |
|---|---:|---:|---:|
| p50 | 90.46 | 317.54 | 89.70 |
| p95 | 100.72 | 351.92 | 99.98 |
| p99 | 110.83 | 370.80 | 126.43 |

Old/fixed results are interleaved in this comparison. The regressed-build
column comes from the [preceding comparison](../symbol-search-comparison/README.md),
not a third build interleaved in this run.

**Typical and p95 search performance are restored, not universally faster.**
Fixed p99 is about 15.6 ms above the old rerun. Repeated exact-name p50 is
88.72 vs 89.79 ms; its p99 is 121.80 vs 98.71 ms. These residual tail differences
are recorded rather than claiming that every request improved.

All 1,230 old/fixed searches, including warmups, matched their result rows,
signatures, locations, counts, truncation and page metadata. Signed cursors
and instance generation IDs are excluded from cross-instance equality;
generation stability is checked within each instance. Mean response sizes
also match. Application-cache activity remains zero for these scan queries.

The fixed mixed-search medians for the three runs are 90.25, 88.74 and 90.18 ms.
The old rerun medians are 92.98, 89.50 and 89.01 ms.

## Freshness retained

Separate serial three-run indexing comparisons use the original 74-file edit
fixture (69 frozen source files plus five probes). Each resulting graph matches
a freshly built in-memory graph, including edge occurrence multiplicities.

Median apply-to-publication latency in milliseconds:

| Operation | Old engine | Fixed incremental engine | Fixed parsed / resolved files |
|---|---:|---:|---|
| Initial index | 5,536.21 | 6,393.28 | 74 / 74 |
| Unchanged save | 4,588.30 | 12.87 | 0 / 0 |
| Body edit | 4,368.20 | 21.52 | 1 / 1 |
| Declaration edit | 4,206.54 | 966.89 | 1 / 74 |
| Configuration edit | 4,094.36 | 721.46 | 0 / 74 |

Body edits remain about 203x faster than the old whole-rebuild behavior.
The usual watcher debounce, queue delay and scheduling overhead are additional.
No-op saves deliberately do not advance the generation. Initial indexing
remains slower than the old engine; this tradeoff is not hidden.

## Resources and tradeoffs

- No additional retained cache allowance or second graph is introduced.
  The graph/cache budget remains accounting, not a hard total-process RAM cap.
- The 74-file fixture's sampled pre-oracle heap peak is approximately 114 MiB
  in both builds. Dirty-write estimates briefly exceed the 1 MiB flush threshold:
  87,773 bytes maximum overshoot in the fixed fixture.
- Full-repository store size is about 81.8 MB fixed vs 69.5 MB old and 74.3 MB
  in the earlier regressed build. The small edit fixture instead uses 4.54 MB
  fixed vs 4.84 MB old. Larger pages can increase write amplification and disk
  usage; the outcome depends on record sizes and access patterns.
- Full-repository 768 MiB runs also report higher heap-pool high-water sums in
  the fixed build (235-442 MB vs 206-209 MB old). These include initial indexing,
  source-launcher/JIT and queries; sums of separate pool maxima are **not**
  simultaneous heap peaks or retained-graph measurements. Do not describe the
  change as having zero temporary-allocation cost.
- The million-edge constrained-heap gate passes: 100,000 nodes, 1,000,000 edges,
  256 MiB JVM ceiling, 32 MiB allowance, 25 updates, and one physical store.
  Initial load: 265,294 ms; 25 updates: 168 ms combined; disk: 700,743,680 bytes.
  Dirty overshoot: 31,195 bytes. Final heap sample: 181,489,576 bytes, not peak.
  This single resource gate is not a controlled bulk-ingestion speedup claim.
- The separate **full-repository 256 MiB MCP gate passed**: all 506 files,
  6,563 symbols and 26,702 edges indexed; all 120 searches match the old result
  witnesses. Index/startup: 77,185 ms. Search median: 90.20 ms (single gate run,
  including initial queries, not a separate controlled performance comparison).
  The confirmed heap ceiling is 268,435,456 bytes; final heap is 110,477,048 bytes.
  Peak dirty estimate is 1,278,503 bytes, including a 229,927-byte threshold
  overshoot. This validates the actual repository under a confined JVM heap
  rather than assuming the synthetic stress covers it. Process/native/OS RAM
  remains outside that heap limit.

## Validation

- Focused storage/indexing regressions passed.
- Added two tests covering large records, Unicode search, cache invalidation
  after publication, flushed rollback, project isolation, live budget resize,
  cancelled scans, and preservation of hot cached records.
- Full Maven test/package reactor: **739 tests, 697 passed, 42 skipped,
  zero failures/errors**. reactor-tests.json was captured before the separate
  opt-in stress invocation could replace Surefire reports.
- HTTP/stdio catalogs agree; fresh/existing sessions agree and stale sessions
  are rejected. Tool schemas/fingerprint are unchanged.
- Three-run MCP search and incremental comparisons passed all integrity checks.
  An integrity PASS is not a claim of universally improved latency.
- Million-edge constrained-heap test passed separately.
- Actual repository plus 120 MCP searches passed with a 256 MiB heap separately.
- JavaScript syntax checks passed for benchmark helpers.
- The initial focused command failed before tests because of PowerShell argument
  quoting; the corrected invocation passed. Both logs are retained.
- No UI/application transport code changed. Browser interaction suites were not
  rerun for this storage-only change. macOS/Linux performance is unverified on
  this Windows host; existing environment-gated skips remain incomplete.
- No Docker, production databases, application restart, commit or push.

## Reproduction and artifacts

Build old commit 6ddfb38 and the fixed source in separate snapshots before timing.
Do not overwrite a running application's binaries. Keep the source dataset fixed.

~~~powershell
$old = 'C:/old/code-graph-mcp-http/target/classes;C:/old/code-graph-mcp-http/target/lib/*'
$fixed = 'C:/fixed/code-graph-mcp-http/target/classes;C:/fixed/code-graph-mcp-http/target/lib/*'
java -Xmx256m -cp 'C:/fixed/code-graph-mcp-http/target/lib/*' scripts/StorageReadProbe.java
node scripts/benchmark-symbol-search.cjs $old $fixed C:/frozen-source C:/results/search
node scripts/benchmark-incremental.cjs 'C:/old/code-graph-mcp-http/target/lib/*' 'C:/fixed/code-graph-mcp-http/target/lib/*' C:/frozen-source C:/results/incremental
node scripts/test-catalog-transports.cjs $fixed
node scripts/test-symbol-search-memory.cjs $fixed C:/frozen-source C:/results/search/summary.json C:/results/full-repository-256m.json
mvn -B -f C:/fixed/pom.xml -pl code-graph-storage -am test '-Dtest=PagedGraphTest#graphLargerThanCacheUnderConstrainedHeap' '-Dhybrid.stress=true' '-Dsurefire.failIfNoSpecifiedTests=false'
~~~

Raw data:

- page-probe.json / .log: controlled I/O/page-size experiment.
- search/: identical query lists, manifest, six raw runs, equality witnesses,
  percentiles, startup/storage metrics and diagnostics.
- incremental/: six raw indexing runs, parsing/resolution counters, source hash,
  sampled memory and fresh-index oracle checks.
- focused-tests-final.log, reactor.log, reactor-tests.json, stress.log,
  catalog-transports.log, catalog-transports-final.log and incremental-run.log:
  validation evidence. The final catalog run also exercises the unchanged
  default heap behavior of the now-configurable isolated-host helper.
- full-repository-256m.json / .log: additional real-workload memory gate.

Fixed server JAR SHA-256:
C7B1ECDCCB29E15925F141063ABC78AFA988CA18B47C58EBFDE0C6BF7759CA2D.
Fixed storage JAR SHA-256:
F4F971BF87D4D86E38770DFF014B701A0E551A9B934FF6349B957A4BB7D6F76E.
Validated PagedGraph.java SHA-256:
1718BC7A48AD13CA042E63EAEC37C77C46A79B9D8184FC3490C2E63554D46620
(the working source matches the isolated build source).

The running application was not changed. Disposable store cleanup is checked;
frozen source/build snapshots remain available for reproduction.
