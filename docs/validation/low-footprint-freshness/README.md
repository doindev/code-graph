# Footprint-first deployment and freshness profiling

September 19, 2026; Windows 11, OpenJDK 25, H2 2.4.240, Maven 3.9.11.

## Decision

The retained production implementation from commit `9108cd3` was packaged, tested,
deployed on MCP **3000** / admin UI **8137**, and this repository was explicitly
onboarded again through MCP. DBA and desktop approvals remain enabled, with the
existing **1 GiB graph/cache allowance** and existing DBA data directory.
Startup still defaults to no automatically onboarded projects.

A bounded scratch-reuse candidate passed correctness tests and made declaration
updates faster, but did not demonstrate a smaller actual RAM footprint. In keeping
with the user's footprint preference, **it is not selected or deployed**. Its
production changes and dependent tests were removed from the working source and
preserved in `scratch-reuse.patch` (applies to `9108cd3`). The larger persistent
dependency-store experiment also remains excluded.

No cache budget was increased, no database was changed, and no Docker resources
were used. The profiling tools and evidence are retained; they are not part of
normal application execution.

## What the profile found

Broad declaration invalidation still visits every stored fragment. JFR samples
show substantial MVStore page decoding/scanning, JSON decoding and temporary
allocation work, in addition to relationship writes. These are sampled stacks,
not exact exclusive timings. Allocation weights are allocation traffic, **not
retained memory**.

The candidate reused the existing 256-entry / 2 MiB estimated lookup scratch
across files in one immutable resolution phase, then discarded it. It did not
reuse entries across updates or add disk records. Declaration/configuration
writes still finished before resolution; the same exact resolution rules applied.

A separate diagnostic attempted to enable a page cache after opening the pinned
engine with caching disabled. It remained at zero: that resize call did not create
a cache. This diagnostic is **not evidence for a page-cache speedup**, is excluded
from the comparison, and caused no production change. The installed
`com.h2database:h2:2.4.240:sources` implementation was inspected to verify this.

## Three independent JVMs per build

See `summary.json` and the six `baseline-N/` / `candidate-N/` directories.
Each JVM indexes the same frozen 507-file repository plus its controlled probe,
then performs three declaration renames. All 18 updates resolved 507 fragments,
parsed one file, retained expected graph counts, and completed normally.

Frozen inventory: 1,015 files,
`fb9747b6d5d5988e5f5f59436477155aaa884d2d0041486dd66606d552fe3c8d`.

| Measurement | Selected baseline | Unselected scratch reuse |
|---|---:|---:|
| Declaration index work, median | 13,993 ms | 11,817 ms |
| Initial index time, median | 73.60 s | 71.06 s |
| Initial disk size, median, decimal MB | 81.95 | 82.00 |
| Final disk size, median, decimal MB | 118.13 | 118.77 |
| Sampled peak heap, median, MiB | 313.0 | 328.4 |
| Sampled peak resident process memory, median, MiB | 507.5 | 527.7 |
| Sampled peak resident range, MiB | 417.3–577.6 | 350.7–591.8 |

Declaration work improved **15.55%**. Sampled resident-memory median was about
**4% higher**, but the ranges overlap substantially. Three JVMs do not establish
a statistically reliable RAM regression or improvement. In particular, an unchanged
cache allowance does not establish unchanged total process RAM. This uncertainty
is why the candidate was not adopted.

The JVM ceiling was 768 MiB and the graph allowance 32 MiB for both builds.
Heap was sampled every 100 ms; Windows working set and private committed bytes
approximately every second. The OS-reported peak working set is included separately
in the raw data and summary. NMT snapshots distinguish JVM categories and committed
versus reserved space. Private committed bytes and NMT totals are not RSS.

JFR/NMT instrumentation and Java source-launcher compilation are included in
process measurements. Profile timing covers direct indexing, not watcher debounce,
MCP latency or simultaneous search traffic. Stores/JVMs are fresh; the OS file
cache was not flushed. The existing user server was left running, generally idle.
Small machine/JVM differences remain possible; do not treat this as a universal
speedup or subsecond freshness guarantee.

Candidate runtime index JAR SHA-256:
`7797CFB2D6A409F5F5254556BF0B839B6F250F7E766158A4EDE10F92D1A936CE`.
The runtime was frozen before parallel correctness/build checks.

## Verification

- Deployed baseline full Maven reactor: **739 tests; 697 passed, 42 skipped,
  zero failures/errors** (`deployed-baseline-reactor.log`).
- Candidate full reactor: **741 tests; 699 passed, 42 skipped, zero
  failures/errors** (`candidate-reactor.log`).
- Candidate's two added tests cover shared entry/byte limits, immutable results,
  cache eviction, and fresh phase/update state. The first fixture used an incorrect
  language-family key; it was corrected, not relaxed. Initial and final focused
  logs are retained.
- Candidate **256 MiB JVM ceiling**: full frozen-repository indexing and all
  **120 MCP searches** matched the retained older-build witnesses
  (`candidate-memory-256m.json`).
- HTTP/stdio reduced catalogs matched: 20 tools, fresh/existing session parity,
  stale-session rejection. Fingerprint:
  `f7fffc0bc1c6e198b29755c1ef20d807c143be8cc05df58b2adca26a2f9f1e2a`.
- The 74-file incremental fixture matched a fresh in-memory index, including
  nodes and outgoing-edge multiplicities after no-op, body, declaration and
  configuration changes (`candidate-parity.json`).
- The 256 MiB/catalog/parity checks overlapped the full Maven build to save time.
  Their latency samples are correctness/resource-gate diagnostics, **not** a
  before/after performance comparison. The six timed profile runs did not overlap
  builds.
- Live `/`, `/dba`, and fresh MCP initialization returned HTTP 200. Startup
  reported actions enabled. MCP symbol queries located implementation files and
  verified indexing of the temporary candidate's new `forSource` symbol.
- The archived patch passes `git apply --check`; production index/storage
  sources match the deployed baseline.
- After restoring source, MCP `search_symbols` returned zero matches for the
  candidate-only `HybridSymbolLookup.forSource` (generation 13), verifying removal
  through the live watcher rather than manual filesystem search.
- Profiling-summary/helper checks passed (5 Node tests and PowerShell parsing).
- Removed the two owned, inactive profiling/candidate-build directories after
  retaining evidence, reclaiming 190,633,571 bytes. Raw JFR recordings and disposable
  binaries were removed; text summaries, native/process measurements and patches
  remain. The deployed build and previously existing source/build directories remain.

The 42 skips cover unavailable/opt-in external database, vault, desktop, performance,
GitHub and platform facilities. These are not reported as passed. No UI assets or
DBA behavior changed; interactive browser/desktop suites were not repeated.

## Reproduce

Use separate clean baseline/candidate builds and a frozen source copy. Apply
`scratch-reuse.patch` only in the candidate checkout. Build with `mvn -B package`.
Run sequentially, alternating builds, with new output directories:

```powershell
./scripts/Measure-HybridFreshness.ps1 -Classpath 'C:/baseline/target/classes;C:/baseline/target/lib/*' -Source C:/frozen-source -Output C:/results/baseline-1
./scripts/Measure-HybridFreshness.ps1 -Classpath 'C:/candidate/target/classes;C:/candidate/target/lib/*' -Source C:/frozen-source -Output C:/results/candidate-1
# Repeat as baseline-2, candidate-2, baseline-3, candidate-3.
node scripts/summarize-hybrid-freshness.cjs C:/results
java -cp 'C:/baseline/target/classes;C:/baseline/target/lib/*' scripts/SummarizeHybridRecording.java C:/results/baseline-1/updates.jfr
node scripts/test-symbol-search-memory.cjs 'C:/candidate/target/classes;C:/candidate/target/lib/*' C:/frozen-source docs/validation/symbol-search-fix/search/summary.json C:/results/memory.json
node scripts/test-catalog-transports.cjs 'C:/candidate/target/classes;C:/candidate/target/lib/*'
java -Xmx768m --enable-native-access=ALL-UNNAMED -cp 'C:/candidate/target/classes;C:/candidate/target/lib/*' scripts/IncrementalBenchmark.java C:/frozen-source candidate
```

Use the HTTP module's actual `target/classes` and `target/lib` paths above.
The profiler edits only its newly created source copy, closes its store, then
deletes that copy. Each successful run confirms both removals. Source files,
existing databases, and the running application are never profiler targets.

## Next low-footprint candidate

The symbol lookup maps currently serialize complete nodes but the resolver only
uses identity, kind, arity and attributes. A compact, versioned lookup record could
reduce repeated decoding and disk bytes without keeping entries alive longer.
That is a follow-up hypothesis, **not implemented or claimed as a measured gain**.
It needs old-format rebuild handling, result/precision parity, memory and hybrid
tests, and the same fixed-budget before/after measurements.
