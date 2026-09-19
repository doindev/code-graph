# Native database performance checkpoint — 2026-09-18

This is a bounded developer-workflow benchmark, **not full native database
acceptance**. Advanced topology, infrastructure, simultaneous graph load,
transport latency and SQL Server performance gates remain incomplete.

## Method and environment

- Windows 11, JDK 25, eight logical processors; Linux Docker engine.
- One owned database container at a time, two-CPU/1-GiB container limit, loopback
  ephemeral port. Exact image digests are included in every raw report.
- Three independent test JVMs per case, 256 MiB maximum heap, 64 MiB maximum
  direct buffers, 128 MiB DBA accounting allowance, two configured job workers.
- Sequential workload: 12 warmups followed by 120 measured operations for each
  direct-adapter and managed-job path. Managed latency includes review preparation,
  job admission, execution, result polling (1 ms polling interval) and validation;
  it excludes human review, MCP/HTTP transport and final result-release calls.
- Mongo fixture: 160 documents with an integer ID, group and 256-character text.
  The repeating sequence is indexed ID lookup, a 100-row projected scan, and a
  filtered aggregation. Redis: 160 short values plus a 100-entry list; the
  sequence is GET preview, SCAN and bounded LRANGE. SCAN is not a snapshot and
  page cardinality can differ between runs.
- Fresh JVMs/clients are **not cold OS file caches**. Server caches were not
  flushed. The existing self-hosted graph application was present, but there
  was no controlled concurrent indexing workload.

Run from the repository root:

    ./code-graph-dba/test-native-vendors.ps1 -Engine mongodb -Performance
    ./code-graph-dba/test-native-vendors.ps1 -Engine redis -Performance

The harness asserts the heap cap. Each run preserves raw samples and exact
environment details under target/native-performance/{fixture-owner}.

## Results

Ranges below cover the three independent runs, not confidence intervals.
Throughput is the reciprocal of summed operation latency for this sequential
pipeline; it is not a server saturation or end-to-end client throughput test.

| Case | Managed p50 (ms) | p95 (ms) | p99 (ms) | Operations/s | Heap used after managed workload (MiB) |
|---|---:|---:|---:|---:|---:|
| MongoDB, original batch size 1 | 51.32–56.50 | 131.44–177.93 | 151.50–262.63 | 13.54–16.82 | 142.82–145.81 |
| MongoDB, raw batches up to 16 | 8.40–9.04 | 17.81–19.70 | 20.73–21.33 | 95.30–102.50 | 65.07–65.98 |
| Redis, isolated operation connections | 11.23–11.68 | 15.07–15.28 | 16.41–17.22 | 83.68–86.32 | 20.50–38.27 |

All valid runs had a maximum 64 MiB accounted active job reservation, with zero
job reservation and zero active client leases after each released operation.
Removing the measured connection left zero cached clients. The heap numbers
above include temporary/uncollected allocations; they are not retained-size or
whole-process measurements.

The Mongo improvement supports keeping sixteen-document raw batches. MongoDB
also limits cursor batches by its 16 MiB ceiling; batch count alone is not a
byte budget. Raw BSON remains unexpanded until each document is size-checked.
The live gate additionally reads forty 300,000-character documents under the
256 MiB heap and verifies explicit omission of their oversized content.
[MongoDB cursor behavior](https://www.mongodb.com/docs/v8.0/core/cursors/)

Do not generalize this improvement to large documents, remote servers, competing
writers, or complex aggregation plans without further measurements. Redis still
opens isolated operation connections; connection/TLS establishment may dominate
remote workloads. This checkpoint does not justify changing that isolation.

## Evidence and invalid samples

Raw reports are in [validation/native-performance-2026-09-18](validation/native-performance-2026-09-18).

- mongodb-batch1-1.json through -3.json: capped baseline before batching.
- mongodb-batch16-1.json through -3.json: capped optimized case.
- redis-capped-1.json through -3.json: capped text-command workload.
- redis-uncapped-invalid-1.json through -3.json: retained diagnostic samples,
  **excluded from confined-memory evidence**. The first invocation's heap
  argument was ignored by Maven's fixed test configuration (about 3.84 GiB
  maximum heap). An explicit test-JVM property and in-test heap assertion fixed
  the harness before the valid runs. The initial benchmark also had a compile
  error using the wrong job-status API; no timing sample was produced by it.

Redis's bounded text-command measurements preceded the binary-argument editor
extension; binary reads/writes have separate correctness/approval tests, not a
binary-payload performance claim.

## Limits and remaining gates

- GC counts/times and direct-buffer observations are recorded. The reported sum
  of heap-pool peaks is an upper bound assembled from different pool peak times,
  not an instantaneous process peak.
- Process RSS/native overhead, disk I/O, queue-wait decomposition, memory-budget
  reductions, concurrent indexing, saturation, TLS and cross-platform runs were
  not measured here.
- No SQL Server performance certification or Redis Cluster/Mongo replica/shard
  certification is implied.
- These results do not turn the DBA accounting allowance into a total-RAM cap.
  In particular, the original Mongo heap observation exceeded 128 MiB even
  though job reservations stayed within the 128 MiB DBA allowance.
- All benchmark containers/anonymous volumes were removed; newly introduced
  Mongo images were removed. The pre-existing Redis image was preserved.
