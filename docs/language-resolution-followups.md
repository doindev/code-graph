# Follow-ups from MCP-first resolver development

These are remaining issues, not claims that this iteration fixes all reference
coverage. No database credentials or private source payloads are logged here.

## LR-1: JavaScript module source and imported aliases are discarded

Status: reproduced on the earlier resolver; addressed for the documented static
subset by the subsequent module-aware implementation. Original evidence follows.

MCP discovery located EcmaAnalyzer.importsOf in
code-graph-lang-javascript/src/main/java/io/doindev/codegraph/lang/javascript/EcmaAnalyzer.java.
A focused source read showed collectImportedNames retaining the local alias/name
but not its module specifier or original exported name.

The disposable reproduction is scripts/ProbeLanguageResolution.java:

- lib/right.js exports helper; lib/wrong.js exports helper and renamed.
- A caller importing helper from lib/right receives both helper candidates at
  confidence 0.25 instead of selecting the explicit module.
- A caller importing helper as renamed from lib/right incorrectly resolves to
  lib/wrong.js#renamed/1 at confidence 1.0, tagged import.

This is distinct from the repaired cross-language lookup: all candidates are
JavaScript, so language isolation cannot fix it. High confidence and exact token
coordinates alone do not establish correct module binding.

Recommended next work: retain structured import source/export/local-name evidence,
resolve relative paths and supported module extensions deterministically, preserve
re-export/default/namespace distinctions, and fail conservatively on unsupported
package aliases or dynamic imports. Never call a name-only import match exact.
Test duplicate exports, renamed imports, barrels, cycles, packages and incremental
module renames in memory and hybrid modes. Do not add an unbounded module cache.

Update: the subsequent [module-aware implementation](mcp-precision-efficiency.md)
handles the documented static patterns and exposes explicit unresolved reasons.
Verify source for remaining unsupported/ambiguous cases, not every module query.

## LR-2: connected MCP client schema can lag server tools

Status: observed client/server discovery mismatch; operational follow-up.

The connected client exposed older code-navigation schemas while the running
server advertised newer tools such as find_references through tools/list. Tests
used the existing benchmark HTTP client to exercise the actual server contract.
Do not teach normal agents to build shell/HTTP workarounds: refresh/reconnect MCP
tool discovery, or fall back to available local tools and disclose the limitation.
Investigate host schema caching/tool-list refresh rather than adding aliases
without evidence that they are needed.

## LR-3: compact call graphs repeat edges and truncate small caller sets

Status: reproduced in the old deployed response; fixed and tested in the candidate
with distinct relationships and parallel occurrence counts. Not yet deployed.

The depth-one incoming get_call_graph for NativeResults.add returned 20 pairs
but only three distinct caller/target pairs, with truncated=true and omittedNodes=1.
Separate occurrences repeat identical pairs, without occurrence locations in this
compact payload. This is easy for an agent to mistake for a complete small graph.
The response does honestly report truncation; it must not be ignored.

Following find_references pagination instead returned all 21 indexed occurrences
in two pages, with zero non-Java matches. No filesystem search/read was needed.
Raw evidence is in validation/language-resolution/live-compact-call-graph.json
and live-navigation.json.

Consider deduplicating adjacency links while returning an occurrence count, and
applying compact-graph bounds to distinct nodes/links. Keep exact occurrence
locations in find_references. Review compatibility before changing edge meaning.

## Checks that did not establish another bug

- A search page can hit the documented response/memory bound even below its
  numeric maximum. A smaller page succeeded in the old build. The subsequent
  byte-aware collector now automatically returns a smaller page and cursor when
  individual records fit; oversized individual records fail explicitly.
- The advertised class filter did not return a function in the negative check.
  A suspected kind-filter failure was not reproduced and is not filed as a bug.

No Docker containers or images were needed or created for this resolver work.

## Remaining efficiency and freshness follow-ups

- Hybrid changes still rebuild a complete staged project generation. The watcher
  debounce is only 250 ms; it is not a completion promise. The frozen 501-file
  benchmark at 32 MiB measured median rebuild/index times of 51.93 s baseline and
  60.58 s candidate. A bounded disk-delta/index reuse design is a separate future
  optimization, not implemented or benchmarked as part of module resolution.
- Module/config inventory capture includes bounded JSON reads to support local
  configuration inheritance. Profile parsing, inventory capture and resolution
  separately before attributing the measured indexing increase to one cause.
- Outline/position navigation still scans graph nodes. File-owned lookup indexes
  may reduce acquisition latency without changing response semantics.
- Source-review concern, not a reproduced defect: position lookup iterates symbol
  owners, so file-owned top-level JS references deserve a focused fixture before
  claiming complete position coverage.
- Real client refresh/discovery remains an external gate (LR-2). Do not teach
  agents to invent tools or repeatedly rebuild HTTP bridges to compensate.

The candidate's five JS/CommonJS questions needed no fallback searches or source
reads, with all fifteen independently graded runs correct. This is scoped static
evidence, not a claim of exhaustive references for arbitrary programs.
See [raw results and tradeoffs](validation/mcp-efficiency/README.md).
