# MCP precision and efficiency acceptance

## Outcome

The tested JS module workflows now obtain enough indexed evidence to stop:
five discovery searches and five follow-up source-read operations per run are
eliminated. Java reference correctness is retained. Indexing costs more, and
response payloads grow with the additional evidence; this is not a claim that
every query or complete coding task is faster.

The running user application was not replaced or restarted. No commit, push,
Docker resources, user-database operations, or client configuration changes occurred.
Existing worktree/build artifacts were preserved; builds ran in isolated copies.

## Frozen inputs and environment

- Windows 11 amd64, JDK 25, Maven 3.9.11, eight reported processors.
- Three independent JVM runs per build for adaptive workflows and three per
  build for engine/resource measurements, alternating baseline/candidate order.
- Identical frozen repository plus sixteen authored module fixture files:
  829 files in the hashed inventory, 501 indexed files.
- Each benchmark JVM: 768 MiB maximum heap; hybrid graph/cache allowance 32 MiB.
  These are test settings, not the user's live 1 GiB graph/cache configuration.
- Fresh application stores; the operating-system file cache was not flushed.
  Query sequence measurement includes three warmups and thirty timed sequences.
- One unchanged user server remained running. Final measurements did not overlap
  Maven builds or browser suites. OS/background-process effects remain possible.
- Baseline JAR SHA-256:
  7e6950bd6e6ae6c91618dbe738ef5d7f95011e2cd4c530f1de5f353f1251cdc4.
- Candidate JAR SHA-256:
  0667679f29cfe76d14c6296a04d74cb7c3c915de0413071c0dde2b0b761ebf4b.
- Frozen inventory SHA-256:
  975cdd39cd75716711e0b9131632835b20ff764a31e719dbc54b3b90990d9e98.

The retained local working directory is
%TEMP%/cgraph-efficiency-92332f3e79654ca89d7e3c11fc72f7f4, containing baseline,
candidate and dataset directories. The hashes and raw evidence are copied here;
temporary paths are not durable distribution artifacts.

## Adaptive acquisition and correctness

Medians below summarize three independent runs. One batched source read is one
retrieval operation, not one operation per returned file. The harness chooses a
fallback only when evidence is insufficient; it does not mandate source checks.

| Five JS/CommonJS questions, per run | Baseline | Candidate |
|---|---:|---:|
| MCP calls | 10 | 10 |
| Filesystem discovery searches | 5 | 0 |
| Discovery source-read operations | 5 | 0 |
| Total retrieval operations | 20 | 10 |
| Acquisition time | 536.35 ms | 474.19 ms |
| Retrieved transport/source bytes | 13,548 | 20,176 |
| Correct MCP answers across all 15 trials | 3 | 15 |
| False-positive returned references | 0 | 0 |
| Missing expected references | 12 | 0 |

Questions cover renamed, default, namespace, barrel, and destructured CommonJS
imports. The baseline's namespace answers happen to match the oracle, but weak
binding evidence correctly prevents early stopping. All baseline fallbacks
acquired the expected caller's source. Source interpretation/model reasoning
time is not measured, so fallback acquisition is not graded as a final semantic
answer. A smaller wrong/empty answer is not considered more efficient.

The independent Java javac oracle validates 46 occurrence locations per run
against five reference questions: both builds match all locations in all runs.
Those reference questions require eleven MCP calls and no filesystem work per
run. Median acquisition is 566.58 ms baseline versus 593.38 ms candidate.

The full eight-question Java suite includes declaration discovery, a necessary
implementation read, and exhaustive-deletion safety. Both builds use sixteen
MCP calls plus one implementation read, one filesystem search and one discovery
read (nineteen operations). Median acquisition is 943.70 ms versus 1,005.55 ms;
retrieved bytes are 214,285 versus 216,501. These remaining reads are intentional
controls, not failures of ordinary reference navigation.

Across the thirteen questions, retrieval operations decrease from 39 to 29 per
run. The observed 11.6% JS acquisition improvement is a small deterministic
sample, not a general model-inclusive productivity benchmark.

## Engine and memory tradeoffs

Each timed in-process sequence performs symbol search, incoming-edge retrieval
and depth-two closure. It is not an HTTP/MCP network throughput measurement.

| Median across three runs | Baseline | Candidate |
|---|---:|---:|
| Index time | 51.93 s | 60.58 s |
| Symbols / edges | 5,980 / 24,153 | 6,362 / 25,867 |
| Query sequence p50 | 0.7231 ms | 0.6580 ms |
| Query sequence p95 | 1.6294 ms | 1.1863 ms |
| Query sequence p99 | 1.7571 ms | 1.9194 ms |
| Measured sequences/second | 1,117.86 | 1,272.31 |
| Post-workload heap sample | 78,622,448 B | 96,893,096 B |
| Sum of heap-pool peaks | 174,908,040 B | 203,084,480 B |
| Sampled peak process working set | 352,419,840 B | 348,561,408 B |
| GC count / elapsed GC time | 88 / 167 ms | 91 / 186 ms |
| Disk footprint | 56,795,136 B | 67,846,144 B |
| Accounted cache use estimate | 50,344 B | 50,344 B |
| Cache hits / misses / evictions | 610 / 19 / 0 | 610 / 19 / 0 |
| Peak dirty-threshold overshoot estimate | 18,654 B | 79,699 B |

Indexing increases about 16.6%, and disk use about 19.5%, while this small hot
lookup sequence has similar or lower median/tail latency except p99. The extra
symbols include newly extracted callable forms; counts alone are not correctness.
No statistical significance is claimed from three runs.

The 32 MiB allowance includes a 15 MiB cache capacity, 1 MiB metadata reserve and
16 MiB temporary reserve. Cache-capacity overshoot is zero in these runs, but
dirty buffers do overshoot their threshold as shown. The engine page-cache
telemetry reports zero for this configuration. Parser temporaries, query
results, engine metadata, native/JVM overhead and OS file cache are not fully
accounted or hard-capped. Heap samples are not retained-size measurements;
separate pool peaks need not occur simultaneously. The nearly unchanged sampled
process peak does not prove unchanged memory use under every workload.

## Gates and retained evidence

- Baseline regression fixture: five tests, four expected failures; diagnostic
  evidence retained. Candidate focused fixtures pass, including the 100,000-edge
  work budget, risk-score fallback, pinned read scope, Unicode/oversized pages,
  module/config budgets and memory/hybrid publication consistency.
- Actual isolated HTTP/stdio parity: twenty tools in each identically configured
  graph-only server; fresh/existing sessions agree; stale session rejected.
  Build identity and catalog fingerprint appear in status. Registry tests verify
  notifications on real catalog changes, not ordinary generation changes.
- The complete isolated browser smoke suite passes with no page errors,
  including existing DBA/approval/grid/editor/tree regressions. This is Windows
  Edge headless browser validation, not native cross-platform desktop testing.
- Eighteen Node/installer/diagnostic tests pass. Installer checks use owned fake
  client homes and verify no overwrite of custom skills or MCP configuration.
- Final full Maven reactor: 721 tests, 680 passed, 41 skipped, zero failures or
  errors (3 min 13 s). The final focused package also passes. Logs are retained.
  Environment-gated skips remain incomplete, not successful vendor/vault tests.
- Java installer regressions pass 50 skill-install checks and 34 installer checks;
  the maintained skill passes its frontmatter validator.
- skill-creator guidance and official OpenAI Docs informed the maintained optional
  skill and discovery troubleshooting. Frontmatter/reference validation and
  installation checks supplement, not replace, real client behavior testing.

The existing connected client exposes 32 tools while the unchanged live server's
fresh list exposes 68. This is reproducible discovery mismatch evidence; the
specific host cache/filtering cause is not established. No new aliases or client
configuration workarounds were introduced. Copilot, Claude and Windsurf native
discovery/refresh were unavailable, and a fresh Codex reload was not performed.
The development ledger contains instrumented calls only, not a complete count of
every filesystem read during implementation.

## Reproduction, artifacts and remaining limitations

Use the commands in [the implementation guide](../../mcp-precision-efficiency.md).
Raw final samples and independent oracles are in final-results; resource-summary
and dataset-manifest record the exact aggregation and input hashes. Validation
logs live in logs. Preliminary results are retained separately in preliminary:
they preceded final budget/risk hardening and are not used for final comparisons.
Intermediate failures/retries are preserved in logs, not counted as passes.

Supported static module patterns, hard work bounds and explicitly unresolved
constructs are documented in the implementation guide. The resolver is not a
TypeScript compiler, bundler, or runtime evaluator. Missing edges do not prove
non-use. No real client trial proves that every agent will adopt the skill's
stopping rule.

Hybrid file changes retain the existing full staged rebuild lifecycle. The
250-300 ms watcher start delay is not completion latency; later edits can wait
behind a running rebuild. Incremental disk-delta indexing, cheaper file-owned
lookups and remaining position-coverage questions are recorded in the
[follow-up ledger](../../language-resolution-followups.md), not claimed fixed.

Deployment requires explicit approval to restart the application and a fresh
index so old fragments gain module evidence.
