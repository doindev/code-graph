# Experimental graph-storage benchmark

This module is opt-in and is **not an application storage backend**. It compares the production `InMemoryCodeGraph` with direct MVStore (H2 2.4.240, no SQL/JDBC) and RocksDB JNI 9.8.4. Neither experimental dependency is added to the normal application's runtime by this profile.

The decision order is correctness and bounded residency first, then performance. The cache allowance is shared across all projects in a trial; it is not a total-process RAM cap. See the [comparison report](../docs/benchmarks/memory-budgeted-storage.md) for results and limitations.

## Build and run (PowerShell, from the repository root)

```powershell
mvn package -Pstorage-benchmark -pl code-graph-storage-benchmark -am '-Dmaven.jar.forceCreation=true'
./code-graph-storage-benchmark/run-benchmarks.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/my-run
```

Run the same oversized workload under a constrained heap:

```powershell
./code-graph-storage-benchmark/run-benchmarks.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/my-tight-run -Scenarios pressure -HeapMiB 256
```

Each command runs three independent JVMs per backend/scenario. Use a new output directory: existing trial JSON is never overwritten. The default matrix is 36 sequential trials; each has a five-minute timeout and a 3 GiB sampled working-set safety limit. Tight-heap trials have a 256 MiB **heap** limit, not a 256 MiB RSS limit. Background native allocations and the OS file cache remain outside `-Xmx`.

Record environment/source/dependency hashes and summarize the raw trials:

```powershell
./code-graph-storage-benchmark/capture-environment.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/my-run
./code-graph-storage-benchmark/summarize.ps1 -ResultsDirectory ./code-graph-storage-benchmark/results/my-run
./code-graph-storage-benchmark/summarize.ps1 -ResultsDirectory ./code-graph-storage-benchmark/results/my-tight-run -BaselineDirectory ./code-graph-storage-benchmark/results/my-run
```

The summaries use medians of independent trial metrics, not pooled percentiles. They retain failure/unsupported states and label incomplete residency accounting as **unproven**, even when all latency samples completed. A completed workload is not a passed production decision gate.

For a short harness check (not a representative performance result):

```powershell
./code-graph-storage-benchmark/run-benchmarks.ps1 -OutputDirectory ./code-graph-storage-benchmark/results/my-pilot -Scenarios fit -Nodes 1000 -Edges 10000 -Queries 100
```

One custom trial, bypassing the supervising runner:

```powershell
java -Xmx1536m -XX:NativeMemoryTracking=summary --enable-native-access=ALL-UNNAMED `
  -cp 'code-graph-storage-benchmark/target/classes;code-graph-storage-benchmark/target/lib/*' `
  io.doindev.codegraph.bench.BenchMain --backend mvstore --scenario pressure `
  --budget-mib 32 --nodes 100000 --edges 1000000 --projects 1 --queries 1000 `
  --root . --output code-graph-storage-benchmark/results/custom.json
```

`--budget-mib` defaults to 1024 and must be a whole number of MiB, at least 8. `--backend` is `memory`, `mvstore`, or `rocksdb`. `--scenario repository` parses this repository; other scenarios use deterministic synthetic records. The runner supplies scenario-specific dimensions and budgets.

## Workloads and measurements

| Scenario | Graph | Shared allowance |
|---|---|---|
| fit | 100,000 nodes / 1,000,000 edges | 1 GiB |
| pressure | Same single graph | 32 MiB |
| multi | Four isolated projects, total 100,000 nodes / 1,000,000 edges | 32 MiB total |
| repository | Real extraction/resolution with installed Java and JavaScript/TypeScript analyzers | 1 GiB |

Each successful trial includes first-touch and warmed point queries; caller queries; bounded impact traversal; substring symbol lookup; three scan/hot-query recovery cycles; project activity shifts where applicable; concurrent readers and incremental updates; live reduction/recovery where supported; project removal; and owned-directory cleanup. Point queries return nodes containing file paths and symbol locations. The fingerprint checks deterministic sampled node, edge, and traversal results after updates. Unit tests check full results on small graphs, search semantics, confidence filtering, resolver choices, rollback, isolation, and generation consistency.

The JSON contains individual latency samples, p50/p95/p99, throughput, ingestion time, heap/GC/NMT snapshots, live class histograms after diagnostic full GC outside timed query phases, engine statistics, disk file size, and unsupported/error outcomes. Histogram bytes are shallow, not a retained-ownership breakdown. The runner's manifest records exact arguments, wall time, sampled process working set/private bytes, exit status, and forced termination. Cache metrics are engine-specific estimates and are not comparable counts of the same kind of hit. Raw OS physical disk I/O and exact heap-retained object attribution are not provided by this harness; the report must not infer them from logical database operations.

These are fresh engine/JVM trials, **not OS-cold disk trials**: the operating-system file cache is not flushed. Query generation itself is included in timed point lookups. Results are microbenchmarks of the graph facade, not end-to-end MCP/UI latency. Ten symbol-search samples per trial have weak tail statistics despite three warmup queries. Every scan cycle rewarms the point-query working set and records a pre-scan latency window before the scan.

## Safety and prototype limits

- Stores live in unique `code-graph-storage-bench-*` directories under Java's temporary directory, outside the indexed root. Normal completion closes stores before removing only the exact owned directory. Source files and existing application caches are never removed.
- A forcibly killed trial may leave its owned scratch directory behind; its exact path is printed in the trial stdout log. Validate that path and process exit before manually removing it. Never delete a broad temp directory or glob of unrelated directories.
- Disk ingestion uses bounded serialized batches (1 MiB target, 16 MiB maximum record/update), two extraction workers, and at most eight in-flight file fragments. It does not retain a second complete Java graph. Oversized records, updates, candidate sets, or adjacency results fail explicitly.
- Repository extraction reads files up to 2 MiB with fixed directory exclusions, not the application's full configuration/ignore pipeline. Unsupported and larger files are excluded from this fixed workload. It retains production parser and resolver behavior for included files, but is not a replacement full/incremental indexer.
- A fair session-wide read/write lock provides published-generation consistency. Writes block readers and other projects. This is a deliberate simplification, not a production concurrency design.
- `allNodes` is bounded at 100,000 disk nodes; larger consumers must use streaming scans. Substring search streams and decodes every node, retaining only its top-k results; there is no dedicated query search index yet.
- RocksDB live resizing is recorded as unsupported by the pinned Java binding. Reopening the store is not presented as live resizing. The memory baseline has no cache eviction or budget enforcement.
- MVStore's pinned/metadata residency is not fully exposed; RocksDB JNI and allocator overhead are not completely covered by JDK NMT. Accounted cache estimates do not prove a hard memory bound.
- Injected pre-commit failures are tested. Disk-full, abrupt crash, and hardware-failure durability are not validated. Session-only data does not imply silent corruption is acceptable.

No backend selection, production TTL behavior, onboarding restoration, UI controls, or application startup is changed by this module.
