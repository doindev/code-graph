# Dependency precision acceptance report

Completed on 2026-09-18. Java receiver-aware and broader lexical type-aware resolution is implemented, tested, and deployed. Other language resolvers remain heuristic; this is not compiler-complete whole-program reference discovery.

## Correctness

- The reviewed NativeRedisArguments.bytes fixture has six call sites in five distinct caller methods.
- Old resolver: four sites / three callers. New resolver: six sites / five callers in all three controlled runs and the final live MCP check.
- Recovered the explicit calls inside NativeMutations.bytes and NativeRedisReads.bytes instead of binding them to their same-named local methods.
- A qualified-name rg search returns five lines and misses the internal unqualified call. Neither textual matches nor graph edge counts alone establish a complete semantic reference inventory.
- Twelve focused tests cover receiver collisions, imports, nested/shadowed types, locals/parameters/fields, inferred locals, chains, casts, inheritance, overloads, boxing/widening, varargs, generic bounds, wildcard uncertainty, incremental invalidation, codec round trips, hybrid parity and a 120-type common-name stress fixture.
- MCP reference responses now expose binding/candidate evidence and explicit incomplete-coverage caveats.

## Controlled performance

Three fresh JVMs per engine on the same frozen 471-file source dataset; Windows 11 amd64, JDK 25, eight reported processors. Each run uses -Xmx768m and a 32 MiB hybrid graph allowance, with three warmup sequences and 30 measured sequences. Percentiles below pool 90 measured sequences. A sequence is symbol discovery + incoming calls + a bounded depth-two closure, not a transport round trip.

| Measurement | Previous | New |
|---|---:|---:|
| Median indexing | 34.14 s | 51.57 s |
| Query-sequence p50 | 0.968 ms | 0.878 ms |
| Query-sequence p95 | 1.790 ms | 2.370 ms |
| Query-sequence p99 | 3.204 ms | 7.586 ms |
| Sequence throughput | 966.0/s | 866.9/s |
| Median sampled peak working set | 289.4 MiB | 319.2 MiB |
| Median graph disk usage | 94.5 MiB | 57.2 MiB |
| Published edges | 73337 | 26973 |

Indexing is 51.0% slower in this workload. These are engine-cost measurements, not measurements of agent productivity: median navigation is similar, while tail latency and aggregate sequence throughput are worse. They do not establish whether completing a coding task is faster or slower. More informative MCP answers can eliminate discovery searches and source-verification calls, outweighing additional query cost. Disk usage falls while peak process memory increases. Fewer edges include both removed speculative matches and references now left unresolved; they are not all proven false positives.

Source-launch compiler overhead is included in working-set measurements. Heap-after-query values include collectible garbage and are not retained-heap measurements. OS file caches were not cleared. These results describe hybrid mode; memory-mode correctness was tested, but no whole-repository comparative memory-mode performance claim is made. The graph/cache allowance is not a hard process RAM cap.

## MCP and filesystem-call logging

- scripts/benchmark-navigation.cjs logs actual MCP requests and rg invocations, elapsed time, output bytes and results.
- Each before/after phase intentionally performs six symbol/reference queries and three filesystem searches. This fixed sequence cannot measure the intended benefit: filesystem calls becoming unnecessary because MCP already answered the question. It is a result-quality/lookup-cost comparison only, not an agent-workflow benchmark.
- activity-ledger.json records instrumented development navigation, including real connected MCP usage. It excludes uninstrumented diagnostics/builds and cannot observe filesystem calls made by other agents.
- The new response contains all source-reviewed callers for this fixture, so a search to recover those missing callers is no longer needed for that bounded question. This establishes an opportunity to eliminate fallback work, not a measured reduction across agent tasks. Confidence in returned edges alone does not prove that an inventory contains every reference.

## Adaptive workflow results

Executed on 2026-09-18 against isolated baseline/candidate HTTP MCP servers on ports 13001/13002, using the identical frozen repository snapshot, 32 MiB hybrid allowance and 768 MiB JVM heap cap. The normal application on 3000/8137 was not restarted. Each engine completed three runs in fresh MCP sessions; application/OS caches were warm. Run order alternated. The same evidence rule applied to both versions; expected answers were unavailable to that rule.

Five tasks asked for production-Java callers and exact locations of NativeRedisArguments.bytes/validate and NativeResults.add/finish/binary. Three controls asked for a declaration/signature, implementation content for an edit, and exhaustive-reference confidence before deletion. No editing or deletion was performed.

### The benefit being measured

| Retrieval operations per eight-task run | Previous | New |
|---|---:|---:|
| MCP calls | 15 | 18 |
| Filesystem discovery searches | 6 | 1 |
| Batched discovery/source-verification reads | 6 | 1 |
| Implementation read needed for editing | 1 | 1 |
| Total retrieval operations | 28 | 21 |

**25% fewer retrieval operations overall; 83.3% fewer filesystem discovery operations.** For the five caller-discovery tasks alone, all filesystem fallbacks disappeared: ten discovery operations per run became zero, while total retrieval operations fell from 20 to 13 (35%). Across three runs this removed 15 searches and 15 batched source reads. A batch counts as one read operation, not one per file.

This is evidence of the intended mechanism: more precise MCP answers can make searches unnecessary, rather than merely make an unchanged search sequence faster.

### Why the decisions changed

- bytes: previous output was ambiguous and missed two production call sites; the new response supplies all four production sites (plus two test sites outside this task's scope).
- validate: old name-based matches required inspection; the new response supplies both actual production callers.
- add: the old inventory contained 982 speculative references, exceeding the bounded page budget. The new inventory contains 75 references across languages; all 21 production-Java sites are precise, but unrelated JavaScript matches still force four pages. The task's explicit Java scope permits ignoring those other-language matches.
- finish and binary: old heuristic bindings still required verification under the benchmark's evidence rule; new single-candidate binding evidence supplies all seven and twelve production sites respectively.
- Declaration lookup already needed no filesystem fallback in either version.
- Obtaining an implementation body still requires a source read because these MCP tools return locations rather than content.
- Exhaustive deletion safety still requires source investigation and an uncertainty warning; neither a precise graph nor a text search proves the absence of dynamic/unindexed references.

### Accuracy and timing

An independent JDK javac analysis of the frozen production DBA sources completed with zero compiler errors. It is not the application's resolver. All **46 exact file/line/column call-site locations** across the five questions matched compiler attribution in every candidate run: **15/15 MCP-only reference answers passed**, with no incorrect early stopping.

Baseline fallbacks obtained source containing all compiler-identified sites; semantic review of that source remains additional work. Its reasoning time was not measured, and source acquisition is not claimed to be an automatically completed semantic answer.

| Acquisition measurement, median of three runs | Previous | New |
|---|---:|---:|
| Five discovery tasks, tool/runtime time | 648 ms | 562 ms |
| Five discovery tasks, returned bytes | 357,328 | 225,184 |
| All eight tasks, tool/runtime time | 950 ms | 1,000 ms |
| All eight tasks, returned bytes | 439,102 | 312,544 |

The discovery subset acquired evidence about 13.4% faster and returned 37.0% fewer bytes. Mixed-suite local acquisition time was about 5.3% higher despite fewer operations. These times exclude model reasoning, model/tool round-trip overhead, source-review reasoning, indexing and compiler grading. They therefore cannot establish total coding-session speed. The demonstrated win is reduced discovery and fewer retrieval steps, with independently checked MCP answers.

### Limits and artifacts

This is a reproducible **scripted, evidence-adaptive acquisition benchmark**, not independent fresh LLM agents completing edits. Questions have no cross-task source cache; real agents could reuse previously read files. Five reference questions share two helper classes in one Java subsystem. The conservative resolved-binding requirement may cause more baseline verification than another agent would choose; this is not a claim that these are the minimum possible baseline calls.

The initial pilot exposed an evaluator scope issue: it treated out-of-scope JavaScript ambiguity as a reason to read Java source. The same scope-aware rule was then applied to both engines before the three final runs. Pilot files remain preserved and excluded. Initial launcher attempts also encountered the application's default UI-port collision; final servers used the raw graph-only entry point. No failed attempt was relabeled as a passing sample.

- [Raw counts, timings and independent grades](validation/dependency-precision/workflow-summary.json)
- [Compiler ground truth](validation/dependency-precision/workflow-oracle.json)
- Final workflow-baseline-1..3.json and workflow-candidate-1..3.json contain every request, response, fallback reason and source-read payload.
- [Adaptive runner](../scripts/benchmark-agent-navigation.cjs), [independent compiler grader](../scripts/NavigationWorkflowOracle.java), [summary](../scripts/summarize-agent-navigation.cjs)
- Four evidence-rule tests passed; JavaScript syntax checks passed. No production code changed, so no application rebuild/restart was needed for this experiment.
- Both isolated projects were removed through their own MCP endpoints before their verified benchmark processes were stopped. Their owned empty graph-store directories were removed. The original app process remained running; both UI routes returned HTTP 200 afterward. Benchmark logs and raw results remain available; no Docker resources were used.

## Broader agent-workflow acceptance

The primary performance goal is fewer tool round trips and less discovery work to reach a correct, sufficiently supported answer. Keep the task and correctness requirement constant between versions; let the agent choose different actions from the evidence available. Do not force a filesystem search after a sufficient MCP answer merely to keep call counts equal.

For each representative task, record the question, starting knowledge, MCP evidence, remaining knowledge gap, chosen next action and stopping reason. Every filesystem call must have a contemporaneous reason: locating a declaration, resolving ambiguous candidates, recovering missing coverage, obtaining an exact span, reading implementation details for an edit, or performing an independent evaluation check. Log its elapsed time and output size. Independent evaluator checks are validation overhead, not agent fallback work.

Separate avoidable discovery/verification searches and reads from implementation reads, edits, builds and tests. A source read is not automatically required for a navigation answer; it is appropriate when the task needs implementation content that MCP did not supply. Report total agent-task wall time, MCP calls, filesystem searches, discovery reads, implementation reads, response volume and answer accuracy. Record indexing/setup separately and report its amortization across tasks rather than hiding it.

Use independently reviewed reference inventories and held-out cases to evaluate answers after the agent stops. The agent must not use the expected reference count as its stopping rule. Include same-name collisions, overloads, inheritance/chains, incremental changes and unsupported/dynamic cases. Missing results and confidently incorrect early stopping are failures, not performance wins. Repeat task runs with fresh task context and matched source generations; distinguish live runs from replayed evidence.

The bounded adaptive experiment above demonstrates fewer discovery operations with compiler-verified MCP answers. The earlier fixed-sequence samples and partial development ledger do not establish that result on their own. Broader acceptance still needs representative coding tasks with actual model decisions, context reuse and full completion timing; no universal coding-productivity percentage is claimed.

## Validation and deployment

- Full Maven reactor: 680 tests, 639 passed, 41 skipped, zero failures/errors, 133 suites. Skips remain explicit opt-in/environment gates, not passes.
- JavaScript syntax checks and diff whitespace checks passed.
- Final live MCP reference and call-graph checks recovered the expected callers.
- Graph /, DBA /dba and MCP initialization each returned HTTP 200; startup reports actions enabled.
- Running configuration: MCP 3000; admin UI/DBA 8137; desktop approvals; hybrid storage; 1 GiB graph/cache allowance. The repository was explicitly re-onboarded for this session; startup itself still has no implicit root.
- Built from an isolated source snapshot because Windows locks generated Maven status files in the workspace. The runtime record identifies the deployed process and log directory.
- No UI assets changed; no new browser interaction suite was run for this backend-only change. Existing HTTP/MCP and DBA Java regressions are included in the reactor.
- No Docker resources were needed, no production database was mutated, and no commit or push was performed.

## Evidence and reproduction

- [Implementation and reproducible commands](dependency-precision.md)
- [Machine-readable comparison](validation/dependency-precision/summary.json)
- [Before MCP/filesystem workload](validation/dependency-precision/navigation-before.json)
- [After MCP/filesystem workload](validation/dependency-precision/navigation-after.json)
- [Final live call graph](validation/dependency-precision/final-mcp.json)
- [Instrumented activity ledger](validation/dependency-precision/activity-ledger.json)
- [Final reactor log](validation/dependency-precision/reactor-final.log)
- [Runtime verification](validation/dependency-precision/runtime.json)

Raw engine-baseline-1..3 and engine-candidate-1..3 files contain final independent runs. preliminary-* files preserve the preview comparison; engine-before-1.json is an exploratory run performed while tests were active and is excluded from the final comparison. dataset-manifest.json records hashes for the enumerated Java/JS/TS/XML candidate source files; normal indexing exclusions still apply.
