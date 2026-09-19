# Opt-in hybrid graph storage

The server can keep graph records on session-only disk storage and cache hot reads in memory. Pure in-memory storage remains the default. This implementation uses MVStore directly, not H2 SQL/JDBC, and does not load RocksDB or depend on the experimental benchmark module.

## Start

Stop a running application before rebuilding its runtime JARs on Windows (the JVM locks them).

```powershell
mvn -pl code-graph-mcp-http -am package
java -Xmx1g --enable-native-access=ALL-UNNAMED `
  -cp "code-graph-mcp-http\target\classes;code-graph-mcp-http\target\lib\*" `
  io.doindev.codegraph.mcp.http.HttpMain `
  --port 3000 --viz 8137 --viz-admin `
  --graph-storage hybrid --graph-memory 1g
```

Both HTTP and stdio entry points accept `--graph-storage memory|hybrid` and `--graph-memory SIZE`. The shared allowance defaults to 1 GiB; sizes use whole MiB/GiB (`32m`, `256MiB`, `1g`). Minimum: 32 MiB. No roots are onboarded unless supplied explicitly or through `CODE_GRAPH_ROOT`. Use the UI or MCP `add_project` afterward.

The admin **RAM** button shows mode, effective cache capacity, estimated use, disk size and cache hits/misses. In hybrid mode it changes the allowance immediately, including with zero projects onboarded. Shrinking evicts records; it does not remove projects or change their generations. Read-only UIs cannot mutate this setting. Backend selection requires a restart.

Programmatic administration: `PUT /api/settings` with `{"graphMemory":"256m"}`. Send TTL updates separately (`{"projectTtl":"1h"}`). Storage telemetry is under `graphStorage` in `GET /api/server`.

## What the memory setting means

- One shared weighted cache, not one allowance per project. Cache weights estimate serialized values, keys and entry overhead.
- MVStore's page cache is disabled. Point-node, adjacency and repeated symbol-search results enter the shared LRU; streaming scans and their nested edge/node reads bypass it. This avoids scans replacing the hot working set.
- Effective cache capacity is the smaller of the allowance and half the maximum JVM heap, minus 16 MiB temporary reserve and 1 MiB per active/staged store. With `-Xmx1g`, one active project and a 1 GiB allowance, the effective cache is 495 MiB. The UI reports both requested allowance and effective capacity.
- Indexing is serialized across projects. Writes commit when MVStore's dirty-memory estimate reaches 1 MiB. A single record can overshoot this threshold; the peak is reported. Files are parsed one at a time, with a 2 MiB source-file limit, an 8 MiB serialized-record limit and a bounded resolver candidate set.
- Up to four compound graph reads run concurrently. Published generations are immutable; query readers pin the active generation only until their callback returns.
- This is **not a hard process-RAM limit**, nor complete memory accounting. Parser objects, decoded query results, MVStore root/chunk metadata, write/compression buffers, JVM/native allocations and OS file cache still require memory. The reserves are estimates, not measured caps. `-Xmx` independently limits Java heap. An OS/container limit is needed for a process-wide ceiling.

## Consistency, indexing and lifecycle

Each project has a temporary MVStore file inside a session directory created under the OS temporary directory. Incremental generations share copy-on-write pages in this file. Full rebuilds create a separate staged file and remove the old one after publication. Project roots overlapping the session directory are rejected. No complete second Java graph, project-wide fragment collection or resolver table is retained in hybrid mode.

Indexing stages fragments and name indexes on disk, resolves one fragment at a time, then publishes the complete graph atomically. Reads continue using a pinned immutable version during updates; publication waits for active compound reads. Failed full rebuilds remove the staged file. Failed incremental updates roll the writable head back, including intermediate dirty-memory flushes. If storage recovery itself fails, queries fail closed with an explicit reindex instruction. H2's asynchronous file channel avoids closing the shared store when an indexing thread is interrupted.

Ordinary file saves parse only changed eligible files. Content hashes discard unchanged saves before parsing or publication. Method-body edits with unchanged declarations and module evidence re-resolve only the changed fragments. Declaration, import/export, file addition/deletion, and module-configuration changes conservatively re-resolve all stored fragments; unchanged files are not reparsed.

The watcher retains at most 1,024 paths / 1 MiB of path text. Directory/ignore-rule changes, event overflow, and larger batches trigger a staged full rebuild. A failed update is retried as a full rebuild with bounded backoff. Edits arriving during indexing stay queued. Existing 250 ms quiet / 1 s maximum coalescing windows remain; they are debounce delays, not completion guarantees.

**Tradeoffs:** broad reference invalidation still costs a full disk-fragment resolution pass. Generation publication invalidates the old hot-query cache; selective carryover is not implemented. Copy-on-write versions and stable lookup keys can use more disk space, and initial indexing can be slower. First-time substring searches and whole-graph projections still scan disk. Prefer scoped symbol/caller/impact queries. See [the three-run incremental comparison and acceptance report](hybrid-incremental-delivery.md).

Materialized graph queries are limited to 20,000 records; adjacency and resolver payloads have additional bounds. Oversized queries fail explicitly, never silently return an incomplete answer. Overview/module scans and galaxy top-K selection stream their input; projections still cap retained file/module structures. Some existing whole-graph analyses still use `allNodes` and will fail beyond this bound rather than exhausting heap. This is an initial opt-in implementation, not a guarantee that every unbounded analysis can run under every allowance.

Project removal and TTL expiry stop monitoring, invalidate cached records, close the store and delete its owned file. TTL activity rules are unchanged. Normal workspace/JVM shutdown cleans the owned session directory. Forced termination or power loss may leave orphan temporary files; they are never restored or automatically swept. Cleanup never recursively deletes unknown files or touches indexed sources or existing caches.

## Verification

```powershell
# Full relevant regression suite; skip copying locked runtime dependencies if a server is running.
mvn -pl code-graph-mcp-http -am test

# Storage tests, including the 100k-node / 1m-edge workload with a verified 256 MiB heap.
mvn -pl code-graph-storage -am test `
  '-Dtest=PagedGraphTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dhybrid.stress=true'
```

The storage module's test JVM is capped at 256 MiB in its POM; the stress test asserts that ceiling. A command-line `-DargLine` alone does not override this repository's configured Surefire argument line. Results are emitted as `HYBRID_STRESS` with indexing time, hot node/caller query latency and storage telemetry. This is a regression/stress check, not a replacement for the three-run comparative benchmark in `benchmarks/memory-budgeted-storage.md`. It does not flush the operating-system file cache.

### Observed validation (September 3, 2026)

Windows / OpenJDK 25 / Maven 3.9.11. One constrained regression run, not a statistically robust engine comparison:

| Workload | Result |
|---|---|
| 100,000 symbols / 1,000,000 edges, 256 MiB max heap, 32 MiB allowance | Passed; 134,438 ms indexing |
| Effective cache capacity | 15,728,640 bytes (15 MiB) |
| Disk store | 694,771,712 bytes; removed after store close |
| Peak MVStore dirty-memory estimate | 1,063,191 bytes |
| Heap used at final sample | 35,840,208 bytes (not peak heap or process RSS) |
| 300 repeated hot node + incoming CALLS queries | p50 42,100 ns; p95 130,900 ns; p99 785,200 ns |
| Hot cache counters | 599 hits, 2 misses; 2,195 estimated cached bytes |
| This repository, Java/JS analyzers on index test classpath | 203 files, 2,419 symbols, 16,263 edges; 10,152 ms; 17,399,808 disk bytes |

The repository run used the normal test heap, **not** the constrained stress-test heap. The stress run's hot working set was intentionally tiny; these latencies do not represent cold searches or random paging across the whole graph. Separate regression tests fill and shrink the shared cache, interleave a node/adjacency scan, check result parity against the in-memory engine, inject failed staged writes, pin compound readers during publication, and exercise HTTP/MCP onboarding, watcher updates, removal, TTL expiration and read-only admin rejection.
