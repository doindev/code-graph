# Bounded hybrid incremental indexing

Status: implemented and locally validated; retained build deployed September 19, 2026.
Baseline commit: 6ddfb38. Validation date: September 18, 2026 (Windows).

The [declaration-freshness experiment](validation/declaration-freshness/README.md)
was set aside in favor of this implementation's smaller disk footprint. Its source
patches and measurements remain archived; targeted dependency tracking is not active.
The incremental-indexing and search-latency fixes described here remain in place.
The subsequent [footprint-first profiling](validation/low-footprint-freshness/README.md)
measured heap, process memory and declaration work separately. Its faster scratch-reuse
candidate was also archived rather than adopting an unproven RAM tradeoff.

Follow-up performance gate: actual MCP name-search measurements initially found
a **3.5x latency regression** (90 ms to 318 ms median). A subsequent 32 KiB
page-split fix restores median/p95 performance while preserving incremental
updates; p99 remains slightly higher than the old engine. See the
[fix and revalidation report](validation/symbol-search-fix/README.md) and the
[original comparison](validation/symbol-search-comparison/README.md).
The warm node/edge timings below are not name-search measurements.

## Design and gates

1. Preserve immutable, generation-consistent reads while updating MVStore
   copy-on-write pages. Keep the prior version pinned until publication; roll
   back failed updates, including intermediate flushes. No full graph copy in
   heap, extra application cache, or second per-project cache allowance.
2. Store fragments and symbol/module lookup records under stable file/symbol
   keys. Parse only changed eligible files; ignore unchanged-content events.
   Replace owned nodes/edges and re-resolve changed files. Declaration, module,
   addition/deletion and configuration changes conservatively re-resolve stored
   fragments without reparsing unrelated files.
3. Retain bounded path batches instead of a single hybrid rebuild flag. Preserve
   events arriving during indexing. Directory/ignore-rule changes, watcher
   overflow and oversized batches explicitly fall back to a staged full rebuild.
4. Verify graph/reference parity against fresh indexing, failure isolation,
   pinned concurrent readers, cancellation, deletion/rename, config changes,
   exclusions, symlinks, no-op saves, repeated updates and shared-budget pressure.
5. Measure three independent baseline/candidate runs on identical frozen source:
   change-to-publication latency, parsed/resolved work, heap/process/cache/disk.
   Separate ordinary edits from broad invalidation and full-rebuild fallbacks.
6. Run focused tests, the full Maven reactor and existing browser/MCP regressions;
   record unsupported or unavailable environments rather than claiming passes.

The graph/cache allowance remains accounting, not a hard process-RAM ceiling.
Only owned temporary fixtures/stores are created. No Docker, user-database
mutation, application restart, commit or push is included.

## Storage rationale

MVStore supports copy-on-write versions and old-version reads. The implementation
must explicitly pin retained versions during incremental flushes and test rollback
against the installed H2 2.4.240; merely calling commit is not atomic publication
of a complete code graph.
[MVStore versions](https://h2database.github.io/html/mvstore.html).

## Delivered behavior

- Ordinary saves update the existing project store. Stable fragment/symbol/module
  keys replace ordinal-only fragment addressing. No extra complete graph or
  project-wide in-memory resolver inventory is retained.
- Content-identical saves do not parse, write or advance the generation.
  Body edits with unchanged declaration/module evidence resolve only changed
  fragments. Broader invalidation streams all stored fragments, without
  reparsing their source files.
- Published versions and mutating-scan cursor roots are explicitly pinned
  through periodic flushes. Publication waits for compound readers. Failed
  writes/cancellation roll back the writable head; failed rollback fails closed.
- A bounded, synchronized watcher queue prevents the earlier drain/remove race.
  Events arriving during work remain queued. Overflow, directory/ignore changes
  and oversized batches retain a full-rebuild recovery path with bounded retry
  backoff. Nonrecursive platforms re-register directories after overflow.
- Direct file updates honor include/exclude/ignore rules, source-size limits and
  root/symlink checks. In-memory full-reindex fallback also removes obsolete files.
- Existing MCP/REST inputs, symbol identities, shared budgets and TTL semantics
  are unchanged. Last hybrid work counters are available programmatically as
  IncrementalIndexer.lastHybridUpdate() and captured by the benchmark.

## Three-run comparison

The final packaged candidate and frozen baseline were run in six independent
JVMs, alternating order. There were no concurrent builds, source edits or test
suites during this final comparison. The user's existing server was left running.

Dataset: **69 frozen main-source files** from core, parsers, index, Java and
JavaScript modules, plus **five deterministic edit-probe source files** and a
module configuration. Final inventory: 74 files, 727 symbols, 1,758 edges.
Dataset SHA-256:
1dbee544c7fe601db0decf32877441118a907fd8d0fb08ef2cee3ed609d8d269.
This is a focused repository workload, **not** the earlier 501-file benchmark.

Windows 11 amd64, OpenJDK 25, eight logical processors, Maven 3.9.11.
Each JVM used a 768 MiB heap ceiling and a **32 MiB shared graph/cache allowance**
(15 MiB effective cache capacity with one store). Stores/JVMs started fresh;
the operating-system file cache was not cleared.

Median elapsed milliseconds across the three runs:

| Operation | Baseline | Candidate | Candidate parse / resolve work |
|---|---:|---:|---|
| Initial full index | 5,160.84 | 6,294.41 | 74 / 74 files |
| Unchanged save | 4,511.93 | 10.22 | 0 / 0 files |
| Body-only edit | 4,157.83 | 18.53 | 1 / 1 file |
| Declaration edit | 4,041.18 | 1,332.44 | 1 / 74 files |
| Configuration edit | 3,932.62 | 1,038.08 | 0 / 74 files |

Baseline reparsed all 74 files on every event. All six final graphs matched a
fresh in-memory index, including node metadata and edge occurrence multiplicities.
Every benchmark-owned store was removed after close.

These measure **apply-to-publication**, not OS-event-to-publication. Add the
usual 250–300 ms idle debounce delay, writer queue time and scheduling overhead
for a watched save. No-op events intentionally leave the generation unchanged.

### Resource and performance tradeoffs

- The scoped body edit was about 224× faster, but **initial indexing was 22%
  slower**. The change is an incremental-freshness improvement, not a claim
  that every graph operation is faster.
- Final disk high-water size was 4,841,472 bytes baseline versus 6,701,056 bytes
  candidate (about 38% higher). Stable lookup keys and retained copy-on-write
  pages have disk costs. Old pages become reusable; physical files need not
  shrink after every update.
- Sampled peak Java heap before the correctness oracle was about 114 MiB in
  both builds. Process-memory samples, GC and storage estimates are in each raw
  result. Java source-launcher/JIT allocations and OS/native overhead are not
  attributed to the graph budget; 2-second process sampling can miss late peaks.
- For the tiny warmed node/incoming-edge query pair, median per-run p50/p95/p99
  was 0.0389/0.0634/0.1099 ms baseline and 0.0309/0.0536/0.0584 ms candidate.
  There were three warmups and 30 samples per run. This is not a large paging
  workload or a complete MCP transport latency measurement.
- Broad declaration/configuration changes still re-resolve the entire stored
  inventory. Cache contents are invalidated on generation publication; selective
  cache carryover is not implemented.

Raw runs and complete regression output:
[validation artifacts](validation/hybrid-incremental/README.md).

## Acceptance gates

| Gate | Result |
|---|---|
| Atomic publication, old readers, periodic-flush rollback, cancellation, duplicate/self edges | Passed |
| Mutating cursor remains valid across replacement and flushes | Passed |
| Body/no-op parse counts, declaration changes, additions/deletions/moves, configuration aliases, fresh-index parity | Passed |
| Directory/ignore/simulated-overflow fallback, bounded queue, events during indexing, automatic transient-failure recovery | Passed |
| Direct-path exclusions, ignored files, oversized files, outside-root rejection | Passed |
| Live Windows filesystem watcher | Passed |
| New Windows symlink-creation test | Skipped: this process cannot create the required symbolic link |
| Final full Maven reactor | 737 tests: 695 passed, 42 skipped, zero failures/errors |
| Browser regressions | All 17 isolated suites passed; H2 or mocked transport fixtures, no user databases |
| HTTP/stdio discovery, stale-session protection | Passed |
| Node workflow/discovery/install regressions | 18 passed |
| Java installer / skill-installer checks | 34 / 50 passed |
| Final million-edge / 256 MiB heap and full frozen repository | Passed; separate results below |
| macOS/Linux native watcher execution | Unverified on this Windows host |
| Actual disk-full/device-failure recovery | Not fault-injected; ordinary failed writes and cancellation are covered |

The 42 reactor skips include environment-gated vendor/desktop/live tests and
opt-in stress/repository tests. Separate stress/repository runs do not turn
unavailable vendor or desktop environments into passes.

### Final large-workload checks

- **100,000 nodes / 1,000,000 edges, 256 MiB JVM ceiling, 32 MiB allowance:**
  passed. Initial load 238,709 ms; 25 subsequent updates completed in 106 ms
  combined, with unchanged node/edge counts and one physical store. Disk:
  685,305,856 bytes. Peak dirty-memory estimate: 1,063,735 bytes (15,159 bytes
  beyond the 1 MiB flush threshold). The last heap sample was 174,702,512 bytes,
  not a peak or full process-RAM measurement.
- **Full frozen repository:** 506 files, 6,563 symbols, 26,702 edges; initial index
  79,822 ms. This separate integration test used the normal index-test JVM
  (reported maximum heap 4,125,097,984 bytes), **not** the 256 MiB stress JVM.
  Cache allowance remained 32 MiB; final heap sample 98,797,992 bytes; disk
  74,219,520 bytes. This is one integration run, not a comparative benchmark.
- Both checks used disposable session-owned stores and completed successfully.
  They ran separately from the six-run timing comparison; their load/query
  timings are diagnostics, not a second controlled speedup claim.

Raw output: [final stress/repository log](validation/hybrid-incremental/logs/final-stress-repository.log).

One initial browser invocation used an unseeded fixture and failed the expected
trusted-local-agent assertion. Re-running every suite with its documented seeded
fixture passed; application code was not changed to accommodate that setup error.
The first storage cancellation test exposed an interrupt-closing-channel failure,
which was fixed using the async file channel and covered by regression tests.

## Reproduction

Build baseline 6ddfb38 and the candidate in separate disposable checkouts, with
separate target directories. Freeze source from the baseline; do not benchmark
against a source tree being edited. Keep the user's runtime directories untouched.

~~~powershell
# Run in each isolated checkout:
mvn package

# From the candidate source checkout (paths are examples):
node scripts/benchmark-incremental.cjs 'C:/baseline/code-graph-mcp-http/target/lib/*' 'C:/candidate/code-graph-mcp-http/target/lib/*' 'C:/frozen-baseline-source' 'C:/benchmark-results'

# Ordinary focused regressions:
mvn -pl code-graph-index -am test

# Final constrained-memory + complete repository checks:
mvn -pl code-graph-index -am test '-Dtest=PagedGraphTest,PagedIncrementalTest,HybridWorkspaceTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dhybrid.stress=true' '-Dhybrid.repository=C:/frozen-baseline-source'
~~~

The benchmark copies source into uniquely owned temporary directories and deletes
only those directories after stores close. No Docker resources are used.

## MCP use and remaining follow-ups

MCP symbol discovery helped locate storage/indexing interfaces and existing module
fixtures. Source reads were required for implementation and correctness review.
The [navigation ledger](validation/hybrid-incremental/navigation-ledger.json)
is an explicitly partial instrumented sample, not a filesystem-call census or a
claim of end-to-end agent productivity gains from this change.

The connected client still exposes an older catalog; discovery refresh remains
an external limitation. Initial live queries were slow while the old deployed
hybrid build served this project; their cause was not isolated. File-owned
outline/position lookups and top-level JS position coverage remain separate
follow-ups. No alias tools or recurring HTTP workarounds were added.

The user's application was **not restarted**, and no commit or push was performed.
Deployment of this implementation requires an explicit restart request.
