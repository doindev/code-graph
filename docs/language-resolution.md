# Language-isolated resolution and MCP-first navigation

Historical iteration report. The subsequent
[module-aware precision and efficiency work](mcp-precision-efficiency.md)
supersedes its module-binding limitations and manual smaller-page guidance;
the measurements below remain the original experiment, not the new benchmark.

## Scope

This iteration fixes generic resolution that could bind a JavaScript call to a
same-named Java method. Resolution is scoped **before** import lookup, same-file/
directory ranking, unique-name selection, ambiguity caps and hybrid record limits.

JavaScript and TypeScript share a family (including their existing JSX/TSX/module
adapters). Other language IDs are isolated. No heuristic JVM or C/C++ language
bridge is implied; explicit code/database mappings remain separate and unchanged.
Java's type-aware resolver still requires Java candidates.

Both in-memory symbol indexes and hybrid auxiliary keys include language family.
This replaces the existing lookup indexes, not a second graph. Hybrid lookups
retain their 10,000-record/record-byte bounds and per-file 256-key/2 MiB encoded-size
estimate cache. These allowances are not total-process RAM guarantees.

Unresolved foreign matches stay unresolved. They are not evidence of non-use.
Existing symbol IDs, MCP schemas and browser/DBA behavior are unchanged. Reindex
an existing project to remove previously published false edges; a restart followed
by onboarding builds a fresh session graph.

## Skill and usage

The maintained source is skills/code-graph/SKILL.md, with focused navigation
guidance in references/code-navigation.md. It now directs agents to:

- Prefer indexed evidence for structural discovery; reuse known context/locations.
- Stop when the evidence answers the scoped question, without automatic source
  reads or duplicate filesystem searches.
- Read source for an implementation/edit or a specific unresolved knowledge gap.
- Use local tools directly for literals, configs, unindexed files and builds.
- Preserve freshness, ambiguity, coverage, pagination and permission boundaries.

Install explicitly with skills/install-skill.mjs and the selected client/project,
or choose current-user/global installation with --global. The application installers
also offer optional global skills; see [installation](installation.md#optional-global-agent-skills).
The helper copies all references, refuses customized destinations and does not
alter MCP configuration. The project-scoped locations remain Codex .agents/skills,
Copilot .github/skills, Claude .claude/skills and Windsurf .windsurf/skills.

Official discovery guidance:
[Codex](https://learn.chatgpt.com/docs/build-skills),
[Copilot](https://docs.github.com/en/copilot/concepts/agents/about-agent-skills),
[Claude](https://code.claude.com/docs/en/skills),
[Windsurf](https://docs.windsurf.com/windsurf/cascade/skills).
Installation-layout tests are not proof of discovery or behavior in every client.

## Correctness gates

- Eight focused resolver tests: seven failed before the fix, all eight pass after.
- Four real-adapter integration tests pass: memory/hybrid parity, JS/TS/TSX/file
  calls, incremental rename/delete/addition, old false-edge removal on full
  reindex, and 10,010 unrelated declarations excluded before hybrid lookup limits.
- Existing Java precision and full/incremental/hybrid tests remain passing.
- Full Maven reactor: 692 reported, 651 passed, 41 skipped, zero failures/errors.
  Skips include opt-in vendor, vault, Swing, GitHub and large stress environments;
  they are not represented as passing live integration tests.
- Skill frontmatter validation passes. Eight Node tests cover installation for
  all four destinations, full-reference copying, idempotence, custom-file/junction
  protection, reference links and adaptive evidence decisions.
- The core browser regression passes, including direct access, connection testing,
  query execution, Script recovery, grids, keyboard/responsive controls and sidebar
  persistence, with no page errors. Initial fixture launch lacked its test-lib
  dependencies; rerunning with the candidate's packaged runtime libraries passed.

The behavioral benchmark uses a fixed adaptive harness, not fresh LLM agents.
Independent client skill triggering was not tested. No database changes or Docker
resources are required by this fix.

## Reproducible measurements

Use two packaged builds and one frozen source dataset. Baseline is the version
with Java type-aware precision, immediately before this language-family fix.
Candidate adds language-family partitioning. Keep builds/indexing out of timed
query runs; OS caches are not flushed and are not described as cold.

~~~powershell
./scripts/benchmark-navigation.ps1 -BaselineLib C:/baseline/target/lib -CandidateLib C:/candidate/target/lib -Dataset C:/frozen-source -OutputDirectory docs/validation/language-resolution

# Start graph-only servers separately, ports 13001 / 13002; each owns a unique
# temporary store directory outside the frozen indexed dataset.
java -Xmx768m --enable-native-access=ALL-UNNAMED -cp "C:/baseline/target/code-graph-server.jar;C:/baseline/target/lib/*" io.doindev.codegraph.mcp.http.HttpMain --port 13001 --root C:/frozen-source --graph-storage hybrid --graph-memory 32m
java -cp "C:/baseline/target/lib/*" scripts/NavigationWorkflowOracle.java C:/frozen-source docs/validation/language-resolution/workflow-oracle.json
node scripts/benchmark-agent-navigation.cjs http://127.0.0.1:13001/mcp C:/frozen-source baseline 1 docs/validation/language-resolution/workflow-baseline-1.json
# Repeat alternating baseline/candidate for three independent MCP sessions each.
node scripts/summarize-agent-navigation.cjs docs/validation/language-resolution
node --test scripts/benchmark-agent-navigation.test.cjs skills/install-skill.test.mjs

# Reproduce remaining module/alias issues without changing any project source:
java --enable-native-access=ALL-UNNAMED -cp "C:/candidate/target/lib/*" scripts/ProbeLanguageResolution.java
~~~

Every benchmark MCP call and filesystem search/read records its reason and
evidence. The compiler oracle is run separately and never supplied to acquisition.
This measures whether MCP evidence avoids searches, not merely identical tool
sequences timed twice. Setup, indexing, model reasoning and semantic source-review
time are separate from evidence-acquisition timing.

Results and environment details are recorded in the validation artifacts.
Remaining issues are tracked in [language-resolution-followups.md](language-resolution-followups.md).

## Measured results (2026-09-18)

Dataset: one frozen 471-file mixed-language checkout, 5,876 symbols. Windows 11
amd64, Java 25, eight reported processors. Three fresh JVM runs per engine:
768 MiB maximum heap, 32 MiB hybrid allowance. One initial baseline sample with the
old background application present is retained as engine-baseline-1.with-background-app.json;
it was replaced by a separately recorded uncontended run, not silently omitted.

| Engine measurement (median of three runs) | Before | After |
|---|---:|---:|
| Full indexing | 53.87 s | 54.20 s |
| Query-sequence p50 | 0.653 ms | 0.690 ms |
| Query-sequence p95 | 1.606 ms | 1.500 ms |
| Query-sequence p99 | 2.407 ms | 1.580 ms |
| Sampled peak process working set | 338,878,464 B | 349,327,360 B |
| Post-workload heap sample (not retained heap) | 42,676,136 B | 51,192,792 B |
| Session-store disk bytes | 59,928,576 B | 55,861,248 B |
| Total published edges | 26,973 | 23,627 |

These short timing/heap samples do not demonstrate a general engine speed or
memory improvement. The graph/cache allowance remains accounting, not a hard
process limit. No second complete Java graph was introduced.

The adaptive MCP benchmark used five reference questions and three controls:
declaration lookup, an implementation read, and exhaustive-deletion safety.
It includes all needed pages and records why it stops or falls back to source.

| Per eight-task run | Before | After |
|---|---:|---:|
| MCP operations | 18 | 16 |
| Filesystem operations | 3 | 3 |
| Total retrieval operations | 21 | 19 |
| Median acquired response bytes | 312,544 | 214,285 |
| Median acquisition time | 930.5 ms | 892.6 ms |

The five bounded reference questions required **zero filesystem searches or source
reads** on both versions; the earlier Java precision work already eliminated those
fallbacks. This fix preserves that gain and removes unnecessary MCP pages:
NativeResults.add fell from 75 references (54 non-Java) across four pages to 21
Java references across two pages. Across the five questions, all 46 production
Java occurrence locations exactly match the independent javac oracle in all
three runs on both engines. No actual Java call site was lost in these questions.

The three filesystem operations remain justified controls: one source-content
read for implementation and one search plus one batched read for the exhaustive
deletion question. Claiming that those can be removed would ignore the tasks'
knowledge requirements. The modest observed time change is not an end-to-end
agent productivity guarantee; model reasoning/tool orchestration and source-review
time are not measured, and tiered compilation/OS caching introduce noise.

Raw evidence: [workflow summary](validation/language-resolution/workflow-summary.json),
[validation](validation/language-resolution/validation.json), and individual
engine/workflow records in docs/validation/language-resolution/.
The development-navigation log is explicitly an instrumented subset, not a claim
to capture every filesystem access by this agent or other processes.

## Deployment verification

The old application was stopped after isolated reactor tests passed. Final
packaging completed successfully and the new build was started with MCP 3000,
UI 8137, admin actions, DBA, desktop approvals and a 1 GiB hybrid graph/cache
allowance, preserving the existing DBA data directory. No startup root was added.
The repository was explicitly onboarded through MCP for the authorized self-host
test, and the live indexed occurrence check returned 21 Java references across
two pages with no unrelated-language matches.

Both UI routes and fresh MCP initialization returned HTTP 200. Isolated benchmark
servers were removed via MCP and stopped; their owned temporary stores were
deleted after closing. The running build remains in its isolated build directory
to avoid overwriting the pre-existing workspace build outputs. No commit or push
was performed. Browser-client skill discovery and skipped external environments
remain explicitly unverified.
