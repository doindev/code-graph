# Targeted hybrid declaration freshness — archived experiment

Validation: September 19, 2026 UTC, Windows 11 / OpenJDK 25 / Maven 3.9.11.
Checkpoint `904e647` was committed and pushed before this follow-up.

**Decision: not selected.** The user preferred the smaller footprint. The
experimental production changes and their dependent tests have been removed from
the working source; checkpoint `904e647`'s incremental indexing and search-latency
fixes remain. The running application was not restarted or changed.

The measured increase was disk storage (about 82 MB to 328 MB initially), not a
larger configured RAM allowance. This decision does not establish a RAM reduction.
The tradeoff is slower declaration/module updates, which again conservatively
re-resolve stored fragments without reparsing unchanged files.

All measurements and test results below describe the archived candidate, not the
selected implementation. Recoverable source/documentation patches are retained in
`archived-patches/`; apply them together only to a separate clean checkout of
`904e647` if revisiting the experiment. The benchmark helper retains both modes
and a phase-boundary scheduling correction; neither changes production behavior.

### Smaller-footprint selection verification

- Production index/storage sources and their tests match checkpoint `904e647`.
- Isolated `mvn -B -pl code-graph-index,code-graph-storage -am test`: 140 tests,
  135 passed, 5 skipped, zero failures/errors. See `smaller-footprint-tests.log`.
- Benchmark helper syntax check and all 3 helper tests pass.
- Archived implementation patches pass `git apply --check` against the restored
  source. The historical delivery-note patch requires that document's original
  checkpoint text, not the new decision note.
- Removed the owned temporary test workspace after retaining its log. Existing
  generated files, earlier builds and the running user server were left untouched.

## Design

- Record the name/module lookups actually performed during each file's resolution,
  including empty lookups. An added declaration can repair an unresolved reference;
  invalidating only existing graph edges would miss it.
- Store forward/reverse evidence and a deduplicated affected-file queue in the
  project's existing paged store. These records publish and roll back with the
  graph, never in a second project-wide Java graph or resolver index.
- Invalidate old and new declaration names and changed JS/TS module paths. Resolve
  the changed fragment and its recorded lookup consumers. Conservative name
  lookups can still have large fan-in; this is not a promise that every edit is local.
- Bound tracking to 4,096 uncached observations / 1 MiB estimated serialized
  tracking records per file. Overflow sources are explicitly global consumers.
  Configuration changes still resolve all stored fragments. Existing inventory,
  watcher overflow, ignore-rule and oversized-batch rebuild fallbacks remain.
- Advance the internal fragment format to 2. Legacy/missing evidence causes a
  fresh rebuild, never a partial dependency assumption.
- Initial/config resolution walks current fragment keys without retaining a
  mutable cursor throughout all callback writes. It does not change fragment keys
  during that walk. Ordinary stable-root scans, published snapshots, bounded
  dirty writes and failed-update recovery retain their existing protections.

## Evidence and acceptance

Final controlled measurements are recorded under `final-mixed/`; the earlier
`mixed/` results are the first dependency prototype before the current-head scan
refinement. Do not combine these two candidates' latency samples.

The before-state is [the prior mixed-load measurement](../mixed-search/README.md).
The fixture contains 507 indexed files, 6,568 symbols and 26,708 edges initially;
only an owned Java probe is edited. The frozen source inventory remains
`fb9747b6d5d5988e5f5f59436477155aaa884d2d0041486dd66606d552fe3c8d`.

The local candidate gates below passed. Targeted invalidation made the isolated
declaration workload about **60 times fresher** without sacrificing search correctness.
Do not describe this as a universal subsecond guarantee: configuration changes,
large dependency fan-in, overflow and full rebuilds remain more expensive.
The disk/initial-index cost below led to setting this candidate aside.

### Three-run sustained workload

| Measurement | Selected baseline | Archived candidate |
|---|---:|---:|
| Declaration write-to-visible p50 | 19,930 ms | **333 ms** |
| Declaration write-to-visible maximum | 31,719 ms | **424 ms** |
| Declaration indexer work p50 | 14,893 ms | **47 ms** |
| Declaration resolved fragments per save | 507 | **1** |
| Declaration saves / published / coalesced | 18 / 15 / 3 | **18 / 18 / 0** |
| Body write-to-visible p50 / p95 | 341 / 398 ms | 347 / 395 ms |
| Body saves / published / coalesced | 180 / 180 / 0 | 180 / 180 / 0 |
| Initial index/startup median | 78.87 s | 95.25 s |
| Initial store size | about 82 MB | 327.5-329.8 MB |
| Final store size | 107.3-118.0 MB | 327.6-330.5 MB |

Search latency is end-to-end HTTP MCP acquisition time, in milliseconds:

| Workload | Baseline p50 / p95 / p99 | Candidate p50 / p95 / p99 | Candidate queries/sec |
|---|---:|---:|---:|
| Idle | 88.17 / 104.37 / 125.50 | 87.48 / 100.08 / 122.94 | 11.26 |
| Body saves every second | 88.76 / 104.48 / 126.22 | 87.66 / 106.06 / 130.34 | 11.17 |
| Declaration saves every ten seconds | 100.26 / 129.54 / 168.08 | 88.05 / 100.48 / 125.73 | 11.21 |

All **6,059 measured searches**, plus 72 excluded warmups, matched retained
baseline witnesses. All 198 saves published separately. Every observed publication
had the expected source hash and call targets/occurrence counts in one compound
read. There were no request errors, timeouts, result mismatches or backwards
generations. Final declaration drains were 1.5-2.3 ms, versus 12.3-21.5 seconds.

Per-run p50 / p95 search latency:

| Run | Idle | Body | Declaration |
|---|---:|---:|---:|
| 1 | 88.15 / 101.41 | 88.03 / 99.86 | 88.58 / 101.65 |
| 2 | 89.42 / 101.74 | 90.99 / 116.54 | 89.36 / 99.47 |
| 3 | 82.87 / 96.28 | 82.56 / 96.79 | 83.89 / 99.18 |

The third JVM was faster across all phases. Body p95/p99 increased about 1.5%/3.3%
in the pooled before/after comparison; this is retained, not hidden. These runs
demonstrate removal of broad declaration work and its backlog; they do not establish
statistical significance for small search differences or eliminate every contention
source. There is no updates-only control or process-memory measurement.

### Regression and resource gates

- Full candidate reactor: **749 tests, 707 passed, 42 skipped, zero failures/errors**.
  See `accepted-reactor.log`. Skips include opt-in database/driver/vault/desktop
  tests, performance/stress profiles, real GitHub posting and unavailable Windows
  symlink privileges. Those environments are not reported as passed. No browser
  assets changed; interactive browser/desktop suites were not rerun for this fix.
- Positive and negative dependencies, new overloads, inherited overrides, return
  chains, generic name ambiguity, JS module misses/re-exports/moves, config changes,
  deleted files, old-format fallback, bounded overflow, fan-in queue deduplication,
  project isolation and failed-publication rollback have focused regressions.
- Current-head iteration has explicit add/remove semantics and is tested across
  intermediate commits. The original stable-root snapshot tests remain unchanged.
- HTTP/stdio fresh/existing catalog parity and stale-session rejection pass on the
  final build, with the unchanged 20-tool reduced catalog. See
  `accepted-catalog-transports.log`; the fingerprint remains
  `f7fffc0bc1c6e198b29755c1ef20d807c143be8cc05df58b2adca26a2f9f1e2a`.
- The candidate no-compaction/current-head path also completed the 256 MiB heap gate:
  full frozen-repository indexing and 120 matching MCP queries (`memory-256m.json`).
  It was run before, and is distinct from, the rejected compaction experiments.
- `parity-fixture.json`: final 74-file graph equals a fresh in-memory index, including
  all nodes and outgoing-edge multiplicities after the edit sequence. A real
  cross-file declaration change resolved 2 files in 27 ms; a config edit deliberately
  resolved all 74 files in 2,024 ms. This is a correctness check, not another
  three-run performance claim or the 507-file latency baseline.
- Final mixed-run sampled post-startup heap peaks: 262,239,872; 272,221,568;
  264,520,680 bytes. These exclude an unsampled initial-index peak and are not RSS.
  GC time totals: 825 / 820 / 872 ms. One physical store per run; no page cache or
  populated query-record cache in this workload. Maximum dirty-threshold overshoot
  was 393,717 bytes; accounting remains estimated, not a hard total-RAM bound.
- Every mixed host exited normally, reported zero residual session stores and
  removed its owned copied source/runtime directory. The parity fixture removed its
  owned store. Frozen inputs, isolated build artifacts and the user's server remain.

Final artifact SHA-256 values (build timestamp `2026-09-19T05:55:58Z`):

- Storage JAR: `1CCAA5A14F004F1132AB01A6B647F00C668CCFCA53426C21553191D48F51BB94`
- Index JAR: `C85A09DB4D09502749EA50C25294DB5B4DCC31960689075B2A15724979AB8FC2`

The isolated build path was reused during exploration. These hashes identify the
final packaged candidate; prototype logs are retained diagnostics, not claims that
the earlier binaries still exist at their original classpath.

## Resource tradeoff and rejected experiment

The first dependency prototype retained roughly 530 MB on disk. Releasing the
initial scan root between files reduced this to roughly 327 MB in the full-repository
256 MiB heap gate, versus approximately 82 MB in the prior build. This is a
substantial disk tradeoff, not a free optimization or a new RAM cap.

`residency-diagnostic.json` inventories 61,397 lookup/source pairs in each
direction: approximately 23 MB of serialized keys/path values, excluding engine
overhead. Chunk statistics show significant sparse write-history storage as well.
The diagnostic operates only on its own disposable store. Its optional compaction
is **not production behavior**.

Several bounded fresh-store compaction variants were explored. They could reduce
space in one workload, but did not consistently shrink physical files in the
partially live-chunk regression fixture. Compacting a pinned published snapshot
could increase disk use. The production compaction experiment and its acceptance
fixture were withdrawn, not marked passed or relaxed to hide those failures.
Retained `compact-*.log`, `final-reactor.log` and `residency-compacted.*` files
document this rejected experiment. Further dependency-record packing / verified
reclamation is a follow-up before claiming disk efficiency.

The archived candidate retained cache/page-cache settings, the 1 MiB dirty
flush target and explicit overshoot reporting. The allowance still excludes some
parser, JVM, native and OS memory. No compactor or additional application cache runs
in that candidate. No candidate dependency tracking remains in production source.

## Reproduction

First restore the archived patches in a clean, isolated checkout of `904e647`.
The commands below reproduce the candidate, not the selected smaller-store source.
Use an isolated packaged build; do not overwrite binaries used by a running server.

```powershell
$cp = 'C:/candidate/code-graph-mcp-http/target/classes;C:/candidate/code-graph-mcp-http/target/lib/*'
mvn -B package
node --check scripts/benchmark-mixed-search.cjs
node --test scripts/benchmark-mixed-search.test.cjs
node scripts/test-catalog-transports.cjs $cp
node scripts/test-symbol-search-memory.cjs $cp C:/frozen-source docs/validation/symbol-search-fix/search/summary.json C:/results/memory.json
node scripts/benchmark-mixed-search.cjs $cp C:/frozen-source docs/validation/symbol-search-fix/search/summary.json C:/results/mixed 60 3 targeted
java -Xmx256m --enable-native-access=ALL-UNNAMED -cp $cp scripts/InspectHybridResidency.java C:/frozen-source
```

The `targeted` benchmark option asserts one resolved probe file; the compatibility
default `all` verifies the previous broad behavior. Final scheduling excludes a
save whose intended time is exactly at the phase deadline. The original prototype
could issue an extra boundary save when a timer woke fractionally early; its 19
declaration saves are retained honestly, not dropped to match the old 18.

Three independent JVMs use a 768 MiB heap and 32 MiB shared graph allowance,
rotated 60-second idle/body/declaration phases, 24 warmups and the same 12 search
shapes and independent retained result witnesses. Body saves occur every second;
declaration saves every ten seconds. Fresh stores are not a flushed OS file cache.
Visibility includes debounce, indexing, publication and observer admission; it is
an observed upper bound sampled every 10 ms, not an internal commit timestamp.

One closed-loop search stream and one independently edited file are tested, not
multi-client saturation, bulk checkouts, high-fan-in changes or all platforms.
The existing user application remains running. Final measured phases do not run
alongside builds or source edits. Exploratory prototype runs are not the final
controlled result. No database or Docker resources are used.
