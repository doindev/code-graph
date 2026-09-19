# Old/new MCP symbol-search comparison

Measured September 18, 2026 (America/Chicago; raw timestamps use UTC).

**Finding: the new incremental-hybrid build regresses name-search latency by
approximately 3.5x on this workload.** Its much faster incremental indexing
must not be presented as a symbol-search speedup.

Follow-up: the [page-size fix and revalidation](../symbol-search-fix/README.md)
restores typical/p95 search performance while preserving incremental indexing.
This original report and its raw data are retained as the regression baseline.

## Compared builds and workload

- Old: frozen commit 6ddfb38, before the bounded hybrid incremental update.
- New: the final locally validated incremental-hybrid candidate from
  [the delivery report](../../hybrid-incremental-delivery.md).
- Both index the exact same frozen old source: 506 indexed files,
  6,563 symbols, 26,702 edges. No edits, indexing or generation changes occur
  during measured requests. The broader source-input manifest contains 1,015
  files, including files not indexed by the language analyzers.
- Source inventory SHA-256:
  fb9747b6d5d5988e5f5f59436477155aaa884d2d0041486dd66606d552fe3c8d.
- Actual HTTP MCP tools/call requests to search_symbols, including response
  receipt and JSON decoding. These are not direct Java point-lookups and do
  not include model reasoning, tool orchestration or agent scheduling latency.
- Twelve query shapes: qualified function names, classes, common names,
  broad first-page searches, JavaScript names, file symbols, and missing names.
  Query arguments and limits are fixed in manifest.json. The openTableTab
  JavaScript query returns no matches in this frozen dataset; it is a negative
  lookup, not evidence of a successfully resolved JavaScript function.
- Three independent JVM/store runs per build, serial and alternating build order.
  Per run: one first search, 24 excluded warmups, 120 mixed requests and
  60 repeated exact-name requests. Total: 1,086 measured plus 144 warmups.
- Fresh application instances, but searches run after indexing and the OS file
  cache is not cleared. The first search is **not** a cold-disk measurement.
- Windows 11, AMD Ryzen 7 4700U (8 logical processors), OpenJDK 25+36-3489,
  Node v22.22.3; 768 MiB JVM heap ceiling and 32 MiB graph/cache allowance
  for both builds. This is not a hard process-memory cap or a production
  1 GiB-budget test. The user's existing application remains running idle.

## Results

Pooled request percentiles across the three runs per build, milliseconds:

| Workload | Samples/build | Old p50 | New p50 | Old p95 | New p95 | Old p99 | New p99 |
|---|---:|---:|---:|---:|---:|---:|---:|
| First search after indexing | 3 | 176.24 | 363.92 | — | — | — | — |
| Warm mixed searches | 360 | 90.04 | 317.54 | 100.27 | 351.92 | 117.84 | 370.80 |
| Repeated exact-name search | 180 | 89.49 | 316.53 | 98.13 | 351.50 | 104.86 | 359.78 |

Only three first-search samples exist; tail percentiles would not be meaningful.
Mixed-search sequential throughput falls from **10.95 to 3.17 requests/second**.
This is inverse measured mean latency, not a concurrent-load throughput test.

Individual mixed-search run medians:

| Run | Old | New |
|---|---:|---:|
| 1 | 92.21 | 321.91 |
| 2 | 90.47 | 306.42 |
| 3 | 87.59 | 319.49 |

The difference is consistent across all runs and query shapes. For example,
the pooled qualified-function median is 89.71 vs 316.00 ms; an absent-name
lookup is 90.03 vs 316.12 ms. Results are workload/machine-specific, not a
guaranteed multiplier for every project or every graph tool.

## Correctness, interpretation and follow-up

- All 1,230 searches matched in returned rows, IDs, signatures, line locations,
  total counts, truncation and page metadata. Session-signed cursors and
  generation IDs are excluded from cross-instance equality comparisons;
  each individual instance's generation is checked for stability.
- Mean response size is identical: 7,100.575 bytes for the mixed workload and
  1,112 bytes for repeated exact-name requests, including text and structured
  MCP payloads. The regression is not caused by larger result responses.
- SearchSymbolsTool.java is byte-identical in both frozen builds. It invokes
  scanNodes and filters the streamed inventory rather than using the cached
  graph findSymbols/point-lookup path.
- Both runs report zero application-cache hits/misses/retained cache bytes
  for these searches. MVStore's page cache is disabled in both builds.
  Repeated searches therefore should not be described as cache hits.
- The changed storage read path is the primary diagnostic target. Candidate
  changes include the cancellation-safe async file channel, pinned immutable
  map versions and stable auxiliary keys. This comparison does **not** isolate
  which individual change causes how much of the regression.
- Next work: profile the scan path, then evaluate a bounded disk-backed name
  index and/or generation-aware query caching. Preserve substring semantics,
  byte-aware pagination, cancellation safety, generation consistency and the
  shared memory budget. Do not simply remove the interrupt-safety fix.
- Require these measured search workloads as a performance gate alongside
  incremental indexing tests before deploying the candidate.
- This is query-speed evidence, not a measurement of filesystem searches
  avoided or total agent task completion time. The earlier tiny node-ID plus
  incoming-edge benchmark measured a different operation.

No production source or runtime configuration was changed for this comparison.
All six isolated servers closed successfully. Only the original Java process
(PID 30660, ports 3000/8137) remained afterward. No Docker resources were used.
No application restart, commit or push was performed.

## Raw evidence and reproduction

- manifest.json: exact inputs, query list, limits and experiment parameters.
- baseline-1..3.json / candidate-1..3.json: individual request timing, bytes,
  result hashes, generation, initialization, index status and storage metrics.
- Matching .log files: isolated server diagnostics.
- summary.json: aggregate percentiles, per-query results and equality witnesses.

The harness checks result equality, unchanged frozen source and stable
generations; timing has no pass threshold. Its ALL PASSED output describes
those integrity checks, **not** acceptable performance.

~~~powershell
$baselineCp = 'C:/path/to/frozen-old/code-graph-mcp-http/target/classes;C:/path/to/frozen-old/code-graph-mcp-http/target/lib/*'
$candidateCp = 'C:/path/to/frozen-new/code-graph-mcp-http/target/classes;C:/path/to/frozen-new/code-graph-mcp-http/target/lib/*'
node --check scripts/benchmark-symbol-search.cjs
node scripts/benchmark-symbol-search.cjs $baselineCp $candidateCp C:/path/to/frozen-old C:/path/to/new-results
~~~

Build both checkouts before running; leave them unchanged and avoid concurrent
builds/tests while timing. The harness uses the existing EfficiencyServer.java
and MCP client, creates only isolated loopback servers and session-owned stores,
and does not attach to the user's application.

Compared server JAR SHA-256 (classpath details are retained in manifest.json):

- Old: BC8B55CFD25A0C0F19790E1EE53485772DF47A0621586FD5DE472417B4B292E3
- New: 59C3F04609F73FFEAF83CCCAC2BCAD347741B3121BCEE4F214E22C03E406FBC1

Index/storage/parser dependency hashes are also retained in
[the earlier artifact manifest](../hybrid-incremental/artifact-hashes.json).
