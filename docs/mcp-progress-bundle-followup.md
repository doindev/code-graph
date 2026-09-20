# Onboarding visibility and bounded bundle efficiency

This follow-up preserves synchronous MCP onboarding and full-detail navigation
responses. It does not broaden database capabilities, approvals, or network access.

## Contract

- `list_projects.projects` still contains only published, queryable projects.
  `onboarding` is a separate, non-renewing snapshot of initial scans: name, state,
  `queryable: false`, elapsed milliseconds and a progress object.
- Progress contains phase, discovered/parsed/resolved/failed files and
  `inventoryComplete`. Hybrid parsing streams alongside discovery; the final
  inventory count is unknown until scanning ends. No percentage or completion-time
  estimate is invented. Configuration failures release the reservation.
- Runtime onboarding admits at most four concurrent initial scans. Excess requests
  fail with `onboarding_busy`; there is no hidden unbounded queue.
- UI add/reindex job status includes the same progress object. Existing cancellation
  and publication semantics remain unchanged; a cancellation acknowledgement does
  not claim that an already-running scan has stopped.
- `get_symbol_context.detail` accepts `full` (default) or `locations`.
  Locations removes only name, kind and display signature from each item. IDs,
  declaration spans, exact occurrences, relationships, confidence, resolution,
  per-target errors and mandatory risk reports are unchanged. Projection occurs
  before byte-aware pagination; cursors bind to detail.
- Repeated symbol lookup within a bundle reuses up to 128 entries, including misses.
  Nothing is cached between requests or generations. Final page JSON is serialized
  once for both size validation and return. There is no second complete graph.
- Completed UI jobs retain only scalar progress snapshots, not indexers/graphs.

## Verification

Focused checks cover default compatibility, evidence-preserving projection, cursor
scope, request-local node reuse, memory/hybrid stage parity, configuration failure,
admission saturation and UI/MCP pending-versus-published visibility. Root browser
checks exercise progress text, discovery without a denominator and cancellation.

Reproduce the warmed, three-independent-JVM comparison using the retained frozen
dataset and independent fixture oracle:

```powershell
node scripts/benchmark-context-followup.cjs BASELINE_JAR CANDIDATE_JAR DATASET ORACLE_JSON OUTPUT
```

Each JVM uses a 32 MiB shared hybrid allowance and a 768 MiB maximum heap, with ten
warmup and 100 measured acquisitions. Both builds answer the same four method
implementation questions. The client selects locations detail only when advertised;
it does not perform mandatory filesystem verification when indexed evidence is
sufficient. Initialization/catalog/roster bootstrap is excluded from navigation
timing and bytes. OS file caches are warm; these are deterministic acquisition
measurements, not total agent task time or proof of production tail bounds.

The 35-module Maven package reactor passed: 816 tests, 771 passed, 45
optional/live/platform skips, zero failures or errors. Supplemental full/locations
pagination checks also passed. All 18 DBA browser suites and the root-page browser
suite passed; JavaScript syntax checks, skill validation and all 50 optional
skill-installer checks passed. Tests used owned temporary profiles, not installed
client configuration. No Docker resources were introduced.

The first focused run caught a new test's illegal compound `var` declaration;
the second caught a wrong expected node-lookup count (the fixture also includes a
Child caller). Both tests were corrected before the passing reactor. No failed
performance sample is excluded from the benchmark.

## Development navigation

The running application indexed this checkout in approximately 95 seconds.
Subsequent MCP search and bundle queries found source locations and callers, and
normal watching published the new progress methods at generation 21 without a
manual reindex. Local reads remained necessary for source edits and documentation.
The purpose/latency/bytes/generation ledger is retained with validation artifacts;
it is not a complete count of all filesystem tool invocations.

A client-side batch was initially given `get_file_outline.path`, while the actual
schema requires `file`. The diagnostic helper rejected it before execution. The
schema was inspected and the call corrected. This was a caller mistake, not a
resolver failure. A remaining diagnostic-helper limitation is that a later batch
schema error hides earlier successful results from its console output (their
ledger entries remain); it does not justify retrying mutations.

## Measured comparison

The previous verified build and this candidate each answered 1,200 fixture method
questions across 300 measured acquisitions with zero false positives/negatives.
Both used two navigation calls and zero discovery searches/source reads per
acquisition. This increment reduces bytes/latency, not that already-minimal call count.

| Measure | Previous build | Candidate, locations detail |
|---|---:|---:|
| Whole-acquisition p50 | 105.06 ms | 100.50 ms |
| Whole-acquisition p95 | 129.25 ms | 112.61 ms |
| Whole-acquisition p99 | 150.17 ms | 131.68 ms |
| Navigation transport bytes | 12,531 | 11,475 |
| Bundle-only p95 | 12.56 ms | 10.27 ms |
| Bundle-only transport bytes | 10,839 | 9,783 |

Total bytes fell 8.4%, measured p95 12.9%, and p99 12.3%. Search still dominates
acquisition latency; the results do not establish that every timing difference is
caused by these edits or guarantee production tail latency. Both text and structured
MCP content remain included. Default full detail remains available.

Index/startup time was 80.1–82.6 seconds before and 79.0–81.5 seconds after.
Steady process working sets overlapped (320.6–339.3 MiB versus 329.1–335.4 MiB).
Sampled used heap varied (60.8–89.6 versus 44.1–102.5 MiB); no general reduction in
total RAM is claimed. Both reported a 4,684-byte hot cache estimate and approximately
85 MiB on disk. Heap/GC, process peaks, dirty-write overshoot and incomplete memory
accounting are retained in the [summary](validation/mcp-progress-bundle/summary.json)
and all six raw run files beside it. Benchmark stores closed after each run.

Regenerate the summary with
`node scripts/summarize-context-followup.cjs OUTPUT`. See also the
[reactor log](validation/mcp-progress-bundle/reactor.log),
[browser log](validation/mcp-progress-bundle/browser.log),
[pagination checks](validation/mcp-progress-bundle/pagination.log) and
[development navigation ledger](validation/mcp-progress-bundle/navigation.jsonl).

## Deployment check

The tested candidate was started with MCP 3000, admin/DBA UI 8137, desktop
approvals and a 1.5 GiB shared hybrid graph allowance, without automatic roots.
Both UI pages returned HTTP 200 and MCP initialization succeeded. Explicit MCP
onboarding of this repository exposed streaming parsing and resolution progress,
then completed in 88.8 seconds with 582 files, 7,411 symbols and 30,952 edges;
the pending list was empty after publication. These are the live checkout counts,
not the frozen benchmark dataset. A locations-only caller bundle also passed
against the deployed server.

Two failed isolated build-source copies were removed after retaining logs.
Successful baseline/candidate builds and all benchmark evidence remain. No
commit, push, production database mutation, global skill overwrite or Docker
resource creation was performed in this follow-up.

## Remaining scope

Broader native database and method-resolution gaps remain in their existing
coverage documents. Onboarding is still synchronous through MCP; these changes
make pending work observable, not an asynchronous onboarding-job API. Progress is
not a hard whole-process memory cap, and client catalog refresh remains outside
the server's control. Cross-platform desktop/client checks were not rerun here.
