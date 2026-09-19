# Sustained searches during real file-watcher updates

Status: measured on the optimized hybrid candidate; no deployment or production
code changes. September 18, 2026, America/Chicago (raw timestamps use UTC).

## Conclusion

Frequent method-body edits preserve approximately idle search performance.
Declaration changes cause moderate search contention, but their much larger
problem is freshness: the current conservative invalidation re-resolves all
507 files, taking about 15 seconds per update. Saves arriving every ten seconds
therefore build a backlog and can coalesce.

Keep the bounded incremental engine and the 32 KiB page-size improvement.
The next optimization target should be safely narrowing declaration-change
re-resolution, with explicit handling of unresolved references, overloads,
inheritance and module evidence. Do not trade away resolution correctness.
This report does not implement that change.

## Search results

Three independent JVMs, three 60-second scenarios per JVM, rotated order.
One sequential, continuous HTTP MCP search stream; twelve query shapes.
Timing includes response receipt and JSON decoding. No artificial think time.

| Workload | Measured searches | p50 | p95 | p99 | Maximum | Queries/sec |
|---|---:|---:|---:|---:|---:|---:|
| No file edits | 2,005 | 88.17 ms | 104.37 ms | 125.50 ms | 189.63 ms | 11.13 |
| Body edit every second | 1,993 | 88.76 ms | 104.48 ms | 126.22 ms | 219.75 ms | 11.06 |
| Declaration edit every ten seconds | 1,729 | 100.26 ms | 129.54 ms | 168.08 ms | 236.93 ms | 9.59 |

All **5,727 measured searches** and 72 excluded warmup searches matched the
retained baseline witnesses for rows, IDs, signatures, locations, totals,
truncation and page metadata. Instance-specific cursors/generation values are
excluded from cross-instance equality; per-stream generations never regressed.
There were no MCP errors, timeouts or result mismatches.

Declaration workload increases pooled p50 approximately 13.7%, p95 24.1%, and
p99 33.9%. This is contention, not a total search freeze during indexing.
Body-workload pooled differences are below 1%; individual runs vary, so this
is not a claim that updates make searching faster.

Per-run p50/p95, in milliseconds:

| Run / phase order | Idle | Body | Declaration |
|---|---:|---:|---:|
| 1: idle, body, declaration | 91.92 / 108.06 | 89.80 / 101.13 | 105.11 / 140.49 |
| 2: body, declaration, idle | 88.42 / 101.90 | 91.20 / 108.27 | 97.41 / 114.74 |
| 3: declaration, idle, body | 83.19 / 96.16 | 84.46 / 96.11 | 100.59 / 124.72 |

## Freshness and publication correctness

File writes run independently of query completion, through real filesystem
events. There are no manual reindex calls. Visibility starts when the write
finishes and ends at the first observed, verified published probe snapshot.

- **Body edits:** all 180 writes published separately. Median visibility
  **341 ms**, p95 **398 ms**, maximum **483 ms**. Indexer work itself had a
  64 ms median; watcher debounce and publication/observation waiting account
  for additional end-to-end time. Every update parsed/resolved one file.
- **Declaration edits:** 18 writes produced 15 published versions; three
  superseded intermediate saves coalesced. Median observed visibility
  **19.93 seconds**, range **15.20-31.72 seconds**. Each update parsed one
  file but re-resolved all 507 files; median indexer work **14.89 seconds**.
  After the search/write phase stopped, draining the remaining indexing took
  12.30-21.54 seconds. Every scenario's latest save became visible.
- For every published version, a compound read verified the exact source
  fragment hash and expected call-edge targets/occurrence counts together.
  No published hash/edge mismatches were observed.

The observer checks the last completed update every 10 ms, then performs one
small graph read per new generation. Its timing includes observer scheduling
and read admission, so visibility is an **upper-bound observation**, not an
exact internal publication timestamp. It does not poll full symbol inventories.

Declaration visibility includes the final drain after search traffic stops.
Intermediate coalesced saves have no independent visibility sample and are
reported explicitly, not counted as successes or silently omitted.

There is **no updates-only control in this experiment**. Do not attribute the
entire declaration delay to concurrent searches: broad re-resolution itself
is expensive. The earlier 74-file fixture is not a valid latency baseline for
this 507-file workload.

## Environment and scope

- Windows 11, OpenJDK 25, eight reported processors.
- Optimized candidate built 2026-09-19T04:02:54Z, 32 KiB MVStore page-split
  target and cancellation-safe asynchronous I/O.
- 768 MiB JVM heap ceiling, **32 MiB shared graph/cache allowance**, matching
  the preceding controlled search comparison; not the user's 1 GiB live
  application configuration.
- Frozen real repository plus one deterministic Java probe: **507 indexed
  files, 6,568 symbols, 26,708 edges** initially. Body edits change call
  targets/counts; declaration edits additionally rename a probe method.
- One source file is repeatedly saved. Many simultaneous clients, bulk branch
  switches, multi-project contention, larger repositories and non-Windows
  watcher performance are not established by these measurements.
- Three fresh JVMs/stores; 24 warmups each. Phase order rotates but phases
  within a run share the store/JIT history. No OS cache flushing, so this is
  not cold-disk performance. It is a closed-loop stream, not an open-loop
  arrival-rate or multi-agent saturation benchmark.
- The user's original application remained running and unchanged. No builds
  ran alongside measured scenarios. This was not a dedicated idle machine.

Frozen source inventory SHA-256:
`fb9747b6d5d5988e5f5f59436477155aaa884d2d0041486dd66606d552fe3c8d`.
The source remained unchanged; edits occurred only in owned disposable copies.

Storage JAR SHA-256:
`F4F971BF87D4D86E38770DFF014B701A0E551A9B934FF6349B957A4BB7D6F76E`.
Server/classpath artifacts are the same validated candidate as the
[search regression fix](../symbol-search-fix/README.md).

## Resources, validation and cleanup

- Sampled post-startup heap high-water marks: 270,851,808; 282,941,080;
  151,835,416 bytes. Sampling is not a hard peak bound or process-memory
  measurement; native/JVM/OS overhead is not included.
- Initial stores were about 82 MB; end-of-run stores approximately 107-118 MB.
  Each run retained one physical store. Old generations and staged writes
  still consume disk/temporary memory; the allowance is not a process RAM cap.
- These full-scan searches did not use the point-lookup record cache. No
  engine page cache was enabled. Dirty-write overshoot remains explicit in
  raw telemetry (maximum 271,592 bytes above the existing flush threshold).
- All three hosts exited normally. Each reported zero residual session
  stores before its owned scratch tree was removed. All copied source,
  graph stores and extracted native libraries were removed. Existing frozen
  builds, user sources and the live server were preserved. No Docker used.
- Java benchmark host compiled/executed in all three runs. JavaScript syntax
  and benchmark-helper unit checks pass. No production changes: the Maven,
  UI and protocol regression results from the preceding validated build
  are reused, not represented as newly rerun here.
- The first harness attempt failed before timed scenarios due to serializing
  an unsupported Java Instant in a metrics record. The helper now emits
  explicit scalar fields. That failed attempt is retained in
  `harness-failure/`; it is excluded from all measurements.

## Reproduce

Use the frozen source and the already packaged candidate; do not overwrite a
running server's binaries. The harness creates/removes only its owned copies
and starts loopback-only servers on ephemeral ports.

~~~powershell
$fixed = 'C:/fixed/code-graph-mcp-http/target/classes;C:/fixed/code-graph-mcp-http/target/lib/*'
node --check scripts/benchmark-mixed-search.cjs
node --test scripts/benchmark-mixed-search.test.cjs
node scripts/benchmark-mixed-search.cjs $fixed C:/frozen-source docs/validation/symbol-search-fix/search/summary.json C:/mixed-results 60 3
~~~

The supplied witness file must correspond to the frozen dataset. `manifest.json`
records exact inputs and settings; `run-1.json` through `run-3.json` contain every
latency sample, edit, observed generation, source hash, probe-edge check,
resource snapshot and cleanup result. Companion logs retain server diagnostics.
`summary.json` contains pooled and per-run results.
