# Memory-budgeted graph storage: benchmark evidence

Date: 2026-09-03. Scope: isolated prototypes, not a production migration.

## Decision rule

**Correctness and bounded memory residency are mandatory; speed is evaluated only after those gates.** A 1 GiB shared allowance must not become 1 GiB per project. The eventual feature remains opt-in; in-memory is still the production default. Paging must not onboard/restore projects, extend TTLs, or change the existing idle-removal policy.

The present accounting is deliberately labeled incomplete. A configured engine cache size is not a proof that graph-related residency, indexing, native allocations, or total process RAM is bounded. Unsupported live resizing and resource-exhausted trials are failures of those requirements, not successful benchmark samples.

**Recommendation: neither disk candidate qualifies for production in its tested form.** MVStore exceeded the accounted allowance and incurred high write amplification. RocksDB demonstrated a substantially smaller observed footprint on large graphs, but one constrained run failed an incremental write at its strict cache limit, and the tested Java adapter cannot resize the cache live. Keep in-memory as the default; do not migrate production based on the faster latency samples.

The next decision gate should require complete shared-residency accounting, bounded writer/result admission with backpressure, reliable incremental writes near capacity, safe live shrink/recovery, and a compact search/adjacency representation. Do not make the RocksDB failure disappear by disabling its strict cap. Investigating RocksDB's reservation/admission and Java resizing options is reasonable given its observed footprint, but that is a follow-up experiment, not an engine selection. MVStore would need stricter residency control/headroom and a different bounded-ingestion layout before another comparison.

## Main comparison results

All **36/36** main trials completed, with matching sampled fingerprints and graph counts, successful concurrent-generation checks, project removal, and store cleanup. This means the workloads completed correctly within their test coverage—not that all feature requirements passed. The in-memory baseline and RocksDB both explicitly report live resizing as unsupported. MVStore completed live reduction/restoration, but exceeded its accounted allowance in pressure scenarios.

Values below are medians of three independent trials. Heap is live heap after diagnostic GC **before resize**, when the graph/cache is still in its working state. Store estimates and heap/process figures overlap and must not be blindly added. Store column is the median of each trial's maximum observed checkpoint, not a continuous peak.

| Scenario | Engine | Live heap MiB | Accounted store MiB | Peak process MiB | Index/load seconds |
|---|---|---:|---:|---:|---:|
| fit, 1 GiB | Memory | 413.4 | Not enforced | 1068.2 | 2.49 |
| fit, 1 GiB | MVStore | 640.4 | 1023.2 | 1689.9 | 124.03 |
| fit, 1 GiB | RocksDB | 4.3 | 277.0 | 528.5 | 10.58 |
| pressure, 32 MiB | Memory | 413.5 | Not enforced | 1085.3 | 2.43 |
| pressure, 32 MiB | MVStore | 71.0 | 64.0 | 1084.6 | 99.44 |
| pressure, 32 MiB | RocksDB | 4.4 | 28.8 | 328.2 | 23.92 |
| multi, 32 MiB shared | Memory | 405.9 | Not enforced | 888.3 | 1.98 |
| multi, 32 MiB shared | MVStore | 39.1 | 37.0 | 1030.5 | 61.97 |
| multi, 32 MiB shared | RocksDB | 4.4 | 29.3 | 328.3 | 17.98 |
| repository, 1 GiB | Memory | 10.7 | Not enforced | 208.8 | 3.44 |
| repository, 1 GiB | MVStore | 44.2 | 130.0 | 297.5 | 4.35 |
| repository, 1 GiB | RocksDB | 4.6 | 38.0 | 301.5 | 4.16 |

**Bounded-residency gate:** MVStore's worst observed accounted overshoot was 20.02 MiB at a 1 GiB allowance, **33.07 MiB at a 32 MiB allowance**, and 5.06 MiB with four projects sharing 32 MiB. A configured 32 MiB page cache reported substantially greater estimated residency after scans; the cap is not a demonstrated strict bound in this configuration. Batches targeting 1 MiB also produced approximately 19 MiB of dirty-page estimates during ingestion. These are failed allowance checks, even where the hot-query results look attractive. RocksDB's observed checkpoints stayed below their allowances, but incomplete native/pinned/temporary accounting means its full boundedness gate remains **unproven**.

The synthetic baseline's live histogram contains 100,000 `Node` objects (4,000,000 shallow bytes) and 1,000,000 `Edge` objects (32,000,000 shallow bytes); IDs, strings, adjacency and ownership maps account for additional heap. Neither disk prototype retained those complete Java object sets. That verifies the absence of a second full Java graph, not the absence of storage memory.

The real workload produced 2,433 declarations (including 195 files), 2,238 symbols, and 14,193 edges. Both ingestion paths processed 15,211 raw references with 8,656 pending. The memory path retained 195 fragments and one full symbol table; the disk paths retained zero fragments as an in-memory collection and used at most 264 candidates for a fragment. Serialized fragments totaled 7,929,170 bytes, with a maximum of 274,252 bytes per fragment. Those serialized byte counts are not heap-size measurements. For this small repository, pure memory uses less overall memory and is faster than either disk prototype, supporting the decision to keep it the default.

### Query and indexing tradeoffs

| Scenario | Engine | Hot node p50 / p95 / p99, microseconds | Caller p99, ms | Impact p99, ms | Substring search p50, ms |
|---|---|---:|---:|---:|---:|
| fit | Memory | 1.5 / 1.8 / 4.1 | 0.004 | 1.33 | 21.67 |
| fit | MVStore | 11.9 / 17.6 / 67.7 | 0.057 | 7.02 | 211.51 |
| fit | RocksDB | 20.1 / 35.7 / 65.5 | 0.087 | 13.10 | 256.48 |
| pressure | Memory | 1.3 / 1.7 / 3.6 | 0.008 | 1.28 | 21.60 |
| pressure | MVStore | 10.6 / 14.8 / 34.8 | 0.031 | 4.46 | 187.37 |
| pressure | RocksDB | 43.0 / 61.7 / 93.6 | 0.068 | 18.26 | 288.43 |
| multi | Memory | 1.5 / 1.8 / 5.2 | 0.006 | 0.61 | 5.63 |
| multi | MVStore | 9.1 / 17.4 / 38.8 | 0.025 | 2.87 | 45.48 |
| multi | RocksDB | 14.7 / 31.6 / 143.6 | 0.075 | 6.10 | 61.74 |
| repository | Memory | 1.1 / 1.8 / 3.9 | 0.004 | 0.028 | 0.77 |
| repository | MVStore | 6.6 / 9.8 / 27.8 | 0.016 | 0.114 | 6.15 |
| repository | RocksDB | 9.3 / 13.1 / 33.6 | 0.026 | 0.222 | 7.66 |

The MVStore pressure latency advantage does **not** override its memory-gate failure. RocksDB needs substantially less observed process memory than MVStore in these configurations, but has JNI complexity and unsupported live adjustment. Both disk candidates need a compact symbol-search index: decoding all nodes for a substring query is costly even when result memory is bounded.

This is a custom workload harness, not a JMH study with confidence intervals. JIT progression, GC, compaction and workstation activity affect short windows; a lower pressure p99 than a roomy-cache p99 is not evidence that paging inherently improves latency. The raw independent runs and phase counters are the evidence, not a universal speed ranking.

The measured MVStore store occupied approximately 9.7–11.0 GiB for the synthetic workloads, compared with roughly 106–165 MiB for RocksDB. A representative single-project MVStore pressure run reported about 18.5 billion file-write bytes for about 652 million submitted key/value-accounting bytes. Random small commits, tree-page rewriting, version retention, and different compression defaults make this prototype expensive; these are not optimized-engine limits. Raw counters distinguish this from physical SSD traffic. Repository store size was approximately 183 MiB for MVStore and 36 MiB for RocksDB.

### Scan pollution and recovery

Each table cell is the median of each trial's three-cycle medians. The raw [scan evidence](../../code-graph-storage-benchmark/results/comparison/scan-evidence.csv) retains all nine cycles per engine/scenario and cache-miss/file-read deltas.

| Scenario | Engine | Scan ms | Pre-scan hot p99, us | First 100 after scan p99, us | Next 1,000 p99, us |
|---|---|---:|---:|---:|---:|
| pressure | Memory | 21.1 | 0.7 | 9.0 | 1.2 |
| pressure | MVStore | 180.4 | 5.7 | 16.5 | 13.5 |
| pressure | RocksDB | 282.1 | 15.0 | 36.3 | 18.3 |
| multi | Memory | 7.2 | 0.8 | 7.7 | 0.8 |
| multi | MVStore | 46.5 | 5.6 | 9.0 | 6.7 |
| multi | RocksDB | 62.4 | 12.9 | 20.3 | 16.8 |

RocksDB single-project pressure scans incurred approximately 9,969–9,972 block-cache misses per scan, followed by **21–23 misses** in the combined immediate/recovery hot windows: the default scan admitted cold blocks and displaced some hot data. By the next 1,000-query window, median p99 was close to the pre-scan window, though individual cycles varied. This does not establish exact recovery time between those windows.

MVStore's single-project pressure scan cycles incurred **zero additional file reads in the following hot windows**, consistent with retaining the hot pages. Its latency still varied because allocation, JIT and GC can affect timing independently of cache misses. Crucially, its residency exceeded the allowance: this is scan resistance with an unsuccessful budget bound, not an overall win. Fit/scanned cases also show latency movement without page misses, so latency alone is not proof of cache pollution.

Live MVStore reduction queries are in each trial's `resize.queries`; the cache is cleared on both reduction and restoration. Restoration verifies the capacity, but a separate post-restoration latency window was not measured. RocksDB's live resize is explicitly unsupported in every trial. Reopening or allocating another full cache would not satisfy this requirement.

All p50/p95/p99, throughput, GC, private-memory, indexing/removal and disk metrics remain available in [per-trial CSV](../../code-graph-storage-benchmark/results/comparison/summary-trials.csv), [group summaries](../../code-graph-storage-benchmark/results/comparison/summary-groups.json), and raw trial JSON. No failed or unsupported requirement is converted into a passing latency result.

## Constrained-heap results: the decisive failure checks

The additional nine trials use the same 100,000-node/million-edge workload and 32 MiB shared allowance, with `-Xmx256m`. The [tight-run manifest](../../code-graph-storage-benchmark/results/tight/manifest.json) includes every attempt; [summaries](../../code-graph-storage-benchmark/results/tight/summary-trials.json) retain failed rows. Five workloads completed and four failed. Every trial, including failures, closed its store and reported successful scratch cleanup.

| Engine | Completed / attempted | Live heap MiB | Accounted store MiB | Peak process MiB | Load seconds | Result |
|---|---:|---:|---:|---:|---:|---|
| Memory | 0 / 3 | Unavailable | No eviction | 323.2 / 323.2 / 322.9 | Failed before load completed | Heap exhaustion in all three trials |
| MVStore | 3 / 3 | 56.2 | 49.1 | 371.7 | 103.55 | Queries completed, but allowance exceeded by up to 17.12 MiB |
| RocksDB | 2 / 3 | 4.3 | 28.7 | 330.0 | 23.16 | One strict-cache write failure; live resize unsupported |

MVStore values are medians of three completed trials. RocksDB performance/residency medians use **only its two completed trials**; the third is not silently discarded from the outcome. Its third trial failed during the concurrent incremental-update phase with `RocksDBException: Insert failed due to LRU cache being full` ([raw failure log](../../code-graph-storage-benchmark/results/tight/pressure-rocksdb-3.stderr.log)). The preceding checkpoint showed only 87 pinned bytes and approximately 28.8 MiB accounted residency; that checkpoint does not reveal the transient state at failure. These data do not establish that the Java heap limit caused the native cache failure. They do establish that this configuration is not reliable near capacity under the tested mixed workload.

The baseline failed with `OutOfMemoryError: Java heap space` while generating/retaining the synthetic graph ([example log](../../code-graph-storage-benchmark/results/tight/pressure-memory-1.stderr.log)). The disk prototypes show that the workload can be processed without retaining the full Java graph, but neither satisfies all required memory/control/reliability gates. A 32 MiB graph/cache setting is clearly **not** a 32 MiB process: even these constrained JVMs used substantially more total RAM. That distinction is central to the eventual feature's startup validation and admin telemetry.

## Reproduce and audit

From the repository root in PowerShell:

```powershell
mvn package -Pstorage-benchmark -pl code-graph-storage-benchmark -am '-Dmaven.jar.forceCreation=true'
./code-graph-storage-benchmark/run-benchmarks.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/new-comparison -Backends rocksdb,memory,mvstore
./code-graph-storage-benchmark/capture-environment.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/new-comparison
./code-graph-storage-benchmark/run-benchmarks.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/new-tight -Scenarios pressure -HeapMiB 256
./code-graph-storage-benchmark/summarize.ps1 -ResultsDirectory ./code-graph-storage-benchmark/results/new-comparison
./code-graph-storage-benchmark/summarize.ps1 -ResultsDirectory ./code-graph-storage-benchmark/results/new-tight -BaselineDirectory ./code-graph-storage-benchmark/results/new-comparison
```

Use a different output directory for every run. The runner preserves errors and refuses to overwrite trial JSON. All processes run sequentially. Three independent JVMs are required per backend/scenario, with a five-minute per-trial timeout and 3 GiB sampled process working-set safety limit. These guardrails do not implement the graph budget.

Environment: Windows 11 Pro 10.0.26200 x64; AMD Ryzen 7 4700U, eight cores/logical processors; 15.36 GiB visible physical RAM; Samsung SSD 990 PRO 1 TB; OpenJDK 25+36-3489; Maven 3.9.11. This was a shared developer workstation, not an isolated performance host. Free physical memory fluctuated; the environment snapshot was taken during a trial. Standard trials use `-Xmx1536m`; constrained trials use `-Xmx256m`. All use JDK Native Memory Tracking (`summary`).

Pinned dependencies: H2/MVStore 2.4.240 and RocksDB JNI 9.8.4. The full version list, source and dependency SHA-256 hashes, base Git revision, and hardware snapshot are in [environment.json](../../code-graph-storage-benchmark/results/comparison/environment.json). Raw output paths and commands are in the [comparison manifest](../../code-graph-storage-benchmark/results/comparison/manifest.json). Every trial stores individual latency samples, not just percentiles. The [module guide](../../code-graph-storage-benchmark/README.md) describes each field and invocation.

A [post-run source audit](../../code-graph-storage-benchmark/results/final-environment/environment.json) also hashes JavaScript/TypeScript sources. Its dependency artifacts may reflect the verification rebuild; use the original comparison environment for benchmark-runtime hashes. No indexed source was edited during the final comparison or constrained matrix.

The earlier `results/pilot`, `results/main`, and `results/measured` directories are retained harness/exploratory evidence. They are **not** pooled with the final comparison. Two early matrices were stopped to bound removal and correct scan warmup; their notes identify the aborted samples. This is experimental iteration, not evidence that those samples passed.

## Workloads and comparability

The existing `EnginePerfTest` supplied the 100,000-node/1,000,000-edge scale. This harness uses seed 42, deterministic IDs and paths, mixed CALLS/REFERENCES edges, confidence values, and identical generated records and query sequences for each backend. Four projects divide the same total size, use isolated key prefixes, and share one cache. Synthetic generation does not build a complete intermediate graph for either disk engine.

Synthetic edges reconstruct equal-valued IDs rather than interning references to the original node IDs. This increases baseline object/string residency compared with a canonical-ID fixture. Consequently, the absolute synthetic heap footprint must not be presented as the production cost of every million-edge graph. Binary encoding and ID canonicalization are optimization opportunities, not changes silently applied to one candidate.

| Scenario | Nodes / edges | Shared graph/cache allowance |
|---|---:|---:|
| fit | 100,000 / 1,000,000 | 1 GiB |
| pressure | 100,000 / 1,000,000 in one project | 32 MiB |
| multi | Four projects totaling 100,000 / 1,000,000 | 32 MiB total |
| repository | This repository's supported source files | 1 GiB |
| tight pressure | Same single oversized synthetic graph | 32 MiB, with a 256 MiB JVM heap |

The smaller allowance tests a graph larger than cache without requiring a multi-gigabyte dataset on this workstation. It is not a demonstration of a graph larger than 1 GiB successfully running at the eventual 1 GiB default.

`fit` is the roomy-allowance control, not a guarantee that every engine can cache the entire graph: decoded page/object overhead differs. The deliberately small hot-query set fits comfortably; MVStore's reported cache occupancy can reach the roomy limit during ingestion.

Each completed trial measures 100 first-touch point queries, 500 warmup queries, 1,000 hot point queries, 500 caller queries, 200 bounded impact traversals (depth 3, limit 500, confidence 0.6), and ten substring-search queries after three warmups. Each of three scan cycles independently warms the hot 100-node set, measures 100 pre-scan queries, streams all nodes, then measures 100 immediate and 1,000 recovery queries. Multi-project runs shift activity between projects. Two readers execute 400 generation-checked reads while 20 incremental node updates publish. Live resizing reduces the allowance to a quarter (minimum 8 MiB), queries again, then restores it where supported.

These are **fresh JVM/engine stores, not OS-cold disks**. The OS file cache was not flushed. A page-cache miss may still be served from RAM by Windows. No reopened store is labeled fully cold. Operations/second is single-thread query throughput derived from summed operation times, not server throughput. Query-ID construction and facade locking are included. The JSON codec and duplicated incoming/outgoing edge payloads are prototype costs shared by both disk candidates, not inherent lower bounds for either engine. Ten search samples do not support precise p99 claims.

First-touch queries follow ingestion, which may already warm the engine cache. They must not be described as empty-application-cache measurements. MVStore's resize does explicitly clear its cache, but Windows caching still applies to subsequent page reads.

Storage/compression and compaction defaults are engine-specific: MVStore compression was not enabled; RocksDB's default table compression/compaction options were retained. Write amplification and disk sizes compare these documented prototype configurations, not equally tuned optimal engines. No synchronous crash-durability requirement was imposed on these disposable session stores.

## Prototype architecture and residency

Direct MVStore uses a single file/map and its supported LIRS page cache; no SQL or JDBC is involved. MVStore documents page-level caching and scan resistance in its [storage overview](https://h2database.github.io/html/mvstore.html). The pinned version's builder takes MiB, while `MVStore.setCacheSize` takes KiB. The resize adapter and tests account for this difference. In this version resizing clears the page cache; restoration therefore also has a warmup cost.

RocksDB uses one database with project-prefixed keys and one strict-capacity shared LRU cache. One eighth of the allowance is reserved for two memtables; the remainder is block cache. Index/filter blocks use the cache with high priority. Native table readers, allocator effects, background operations, and pinned data remain important; RocksDB explicitly documents [block-cache capacity and pinning](https://github.com/facebook/rocksdb/wiki/Block-Cache) and [memory beyond the block cache](https://github.com/facebook/rocksdb/wiki/Memory-usage-in-RocksDB). The pinned Java binding has no public live cache-capacity setter. The prototype reports that scenario as unsupported rather than reopening the database and calling it live resizing.

Deployment cost: the measured `rocksdbjni-9.8.4.jar` is 71,683,718 bytes (68.36 MiB), versus 2,685,418 bytes (2.56 MiB) for `h2-2.4.240.jar`. RocksDB adds native-library loading/extraction, platform compatibility, JNI lifecycle, and allocator diagnostics. MVStore is Java-only and easier to package with this Java application. These artifact sizes are not runtime memory measurements. The benchmark profile adds neither prototype to normal MCP/UI wiring; the repository's pre-existing optional H2 module is unchanged.

The production `InMemoryCodeGraph` is the baseline, with its original immutable-state publication and top-level map copies per delta. It has no eviction and ignores the experimental budget. A fair session-wide read/write lock surrounds **all three** backends to expose comparable callback-scoped generation views. Disk ingestion and updates block other project reads; this coarse locking validates consistency but does not validate a scalable production concurrency design.

Disk records preserve node IDs, file paths/locations, node/edge attributes, confidence, duplicate-edge order, case-insensitive substring search, filtering, and traversal limits. Query symbol search streams all nodes and retains only a bounded top-k heap. There is no extra full Java graph or application cache. Node/edge adjacency retrieval remains materialized with a 100,000-edge safety bound; `allNodes` rejects more than 100,000 matching nodes and directs callers to streaming scans.

### Bounded ingestion experiment

The repository workload uses the actual Java and JavaScript/TypeScript analyzers and `NameResolver`, with fixed directory exclusions and a 2 MiB maximum input file. It does not claim equivalence to the application's full ignore/configuration pipeline. Two extraction workers have at most eight in-flight fragments. Disk engines spool fragments and simple/qualified-name lookup records, then resolve one fragment at a time using only needed candidates (maximum 10,000). Resolved and pending references are stored per file. The memory baseline retains all fragments and its full symbol table through the residency checkpoint.

Disk write batches target 1 MiB of serialized values plus UTF-16 key-byte estimates. A single record and an atomic update have a 16 MiB safety ceiling; exceeding it fails before applying that update. Parser expansion, per-object overhead, caller-owned deltas, candidate maps, and queued fragment sizes are not fully byte-accounted. These bounds avoid full-project materialization; they are not a complete admission-control system.

MVStore project removal first removes the publication metadata and then commits bounded deletion batches. A reader cannot observe partial removal under the writer lock. If deletion fails, that namespace remains unpublished and cleanup may be retried; other project keys are not deleted. RocksDB uses a project-prefix range tombstone, so logical removal does not promise immediate native-cache or disk compaction reclamation. Store files are removed only after close, from exact unique temporary directories outside indexed roots. No existing source directory or application cache is deleted.

### What the measurements mean

- Heap: phase snapshots, a 20 ms sampled peak, and live class histograms after diagnostic full GC before resize, after resize, and after removal. Histograms give class instance counts and **shallow** bytes, not a dominator-tree retained-ownership analysis. Full GCs are outside hot-query timings, but affect later phases; GC totals include diagnostics.
- Accounted store estimate: MVStore page cache plus unsaved-memory estimate; RocksDB block cache plus memtables and table readers. RocksDB pinned bytes are reported separately and are already part of cache usage, so they are not double-counted. MVStore pinned bytes are unavailable, not zero. Dirty-memory maxima are additionally reported for MVStore writes/removal.
- Process/native: the runner samples peak working set and private bytes every 100 ms. JDK NMT and buffer-pool snapshots distinguish JVM-managed native categories, but NMT does not account for every third-party JNI allocation. Process memory must not be added to heap/cache values: it already contains overlapping categories.
- I/O: MVStore file read/write operation and byte counters; RocksDB logical read/write bytes and block-cache hit/miss counters; store directory size before removal. These are not equivalent physical disk-I/O counters. OS physical disk I/O, steady process RSS, native allocator attribution, and exact component-retained ownership remain **unmeasured**, not zero.
- Peaks: sampled peaks can miss short bursts. Store residency checkpoints are not continuous peak-cache measurements. An absent observed overshoot is therefore **unproven boundedness**, not a pass. Dirty pages, MVStore metadata/TOC cache, snapshots/cursors, RocksDB allocations, parser scratch, and result buffers need separate accounting/headroom.

## Correctness and failure coverage

Final verification: `mvn package -Pstorage-benchmark -pl code-graph-storage-benchmark -am '-Dmaven.jar.forceCreation=true'` succeeded. The selected reactor ran **65 tests: 63 passed, 2 skipped, 0 failures/errors**, including all five new prototype tests. The skipped tests are existing opt-in/environment-dependent tests, not discarded benchmark failures. The final comparison and constrained matrices recorded 45 attempts, with all 45 scratch directories successfully cleaned and subsequently verified absent. Java/POM source hashes matched before and after the measured runs. Report links and `git diff --check` passed.

The prototype test suite compares complete small-graph node, edge, search, and closure results against production, including duplicates and confidence filters. It checks real two-file Java resolution, callback generation consistency, failed pre-commit writes, failed onboarding, project isolation/removal/re-addition, resize units, explicit unsupported resizing, and scoped temporary cleanup. Repeated trial fingerprints compare sampled nodes/edges/traversals plus graph counts after updates. Fingerprints are not an exhaustive proof for every possible graph or resolver input.

One existing baseline discrepancy is explicitly tested: deleting an edge removes all edges sharing endpoints and kind, but decrements the baseline edge count only once. The prototype matches actual edge-query behavior and counts remaining records correctly. Production code is untouched. An unrelated status-field parity gap also remains: prototype `IndexStatus` does not populate every production telemetry field.

Injected pre-commit failure is not a disk-full or crash-durability test. Disk-full, abrupt termination during commit, enormous symbol-candidate/adjacency cases, cross-project concurrent indexing, and end-to-end MCP/UI load require further tests. Resource-exhausted trials in this matrix stay failures. Missing results from an externally terminated JVM remain explicit manifest failures/aborts.

## Required production work after review

1. **Backend selection and lifecycle:** decouple `Workspace` and `IncrementalIndexer` from concrete `InMemoryCodeGraph`; introduce a session storage owner and project handles with one shared controller. Keep pure memory default, explicitly opt into hybrid, and never restore onboarding from scratch files. Preserve TTL activity rules; paging changes residency, while TTL removes the whole project.
2. **Memory controller:** reserve allowance for pinned graph data, metadata, dirty writes, resolver bookkeeping, and cache; prevent per-project multiplication. Add byte-weighted admission/backpressure for indexing and large results. Specify shrink convergence, pinned-data refusal/overshoot reporting, and allocation headroom. Treat `-Xmx`, native allocations, and OS page cache as separate process-level planning inputs.
3. **Bounded indexing:** `FullIndexer` currently retains all fragments, builds `SymbolTable`, and collects all resolved edges/deltas. `IncrementalIndexer` retains `fragments`, `resolvedBySource`, `resolvedInto`, and `withPending`, rebuilding resolver state. Persist these relationships, stream affected-file sets, page candidate lookup, cap queued bytes rather than only task count, and publish complete generations without retaining a second graph. The prototype does not yet implement the production reverse-resolution bookkeeping/healing pipeline.
4. **Streaming analyses and queries:** add closeable generation-pinned cursors/pagination to `GraphQuery`; convert `DeadCode` and the repeated `VizApi.allNodes` consumers to streaming bounded reducers. Bound adjacency, traversal frontiers, top-k collections, JSON output, and cancellation. Evaluate a compact disk-backed normalized-name/n-gram index with bounded candidate verification that preserves substring/kind/language semantics; a prefix-only index would change behavior. Test compact binary IDs/adjacency encoding before adding another application cache; full JSON scans are not an adequate hot-search design.
5. **Consistency and eviction:** replace the session-wide writer lock with tested project-scoped generation handles/snapshots. Bound snapshot lifetime/pins and version retention. Maintain atomic publication for large updates and safe removal during active reads. Resolve the baseline duplicate-edge counter discrepancy separately with its own regression test and review.
6. **Telemetry/admin controls:** expose configured shared allowance, accounted cache/metadata/dirty/pinned residency, temporary indexing/result estimates, heap, native/process measurements, cache misses, paging latency, overshoot, and shrink status. Add startup configuration and authenticated admin live adjustment only after an engine demonstrates safe resizing. Do not reset TTL on cache management operations.
7. **Operational validation:** fail cleanly on full/unwritable disks, native load failure, oversized input, interrupted updates, and process death; verify owned-directory cleanup and no source deletion. Test Windows/Linux/macOS native packaging if RocksDB remains a candidate. Test a >1 GiB graph at the actual 1 GiB allowance and run longer mixed workloads on an otherwise idle host.

No production storage migration, UI/API change, application restart, commit, or push is part of this phase.

Workspace note: Maven refreshed repository-tracked `target` JARs/test reports. Those generated changes were left visible; no unrelated user changes were reset. Raw benchmark artifacts occupy about 19.2 MiB and are retained with the new module.
