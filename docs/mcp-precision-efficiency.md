# MCP precision and evidence-acquisition efficiency

This iteration improves the evidence agents can use to stop searching. It does
not promise complete runtime references, replace compilers, or require source
reads after every MCP result. See [delivery evidence](mcp-efficiency-delivery.md).

## Discovery and identity

HTTP and stdio initialize with the Maven build version. Successful index_status
and get_workspace_context responses include a server object with version,
builtAt, toolCatalogFingerprint, and toolCount. These fields do not open database
connections or start indexing. The SHA-256 catalog fingerprint covers canonical
tool definitions, including schemas, descriptions, and annotations, independent
of registration order. Build timestamp and catalog identity are separate: a
changed implementation with unchanged schemas need not change the fingerprint.

The server advertises tools.listChanged. Its owner can replace its registry;
actual SDK tool additions/removals/definition replacements notify sessions.
Identical catalogs and ordinary index generation changes do not notify.
Clients remain responsible for refreshing their own discovery caches.

~~~powershell
node scripts/diagnose-mcp-catalog.cjs http://localhost:3000/mcp
# Optional second argument: an exported client tools/list JSON (maximum 4 MiB).
# Optional CGRAPH_DIAGNOSTIC_SESSION: compare an existing session without ending it.
node scripts/test-catalog-transports.cjs C:/candidate/code-graph-server.jar
~~~

The diagnostic only initializes/lists tools and closes its own sessions. It
distinguishes missing tools, definition differences, and unavailable sessions.
It never invokes writes, modifies client configuration, or retries operations.
A successful fresh list with a differing client catalog indicates a client
refresh/filtering problem, not a reason to invent server tool aliases.

Codex App Server documents config/mcpServer/reload for reloading configuration
and queuing refresh for loaded threads; mcpServerStatus/list exposes discovered
tools. Use the client's supported reconnect/reload operation. Code-graph cannot
force arbitrary clients to clear their cache.
[Official Codex documentation](https://learn.chatgpt.com/docs/app-server).
Other client refresh controls are client/version-specific; reconnect a configured
server through its UI rather than assuming a universal command.
[MCP catalog-change contract](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

## Compact graphs and navigation pages

get_call_graph retains nodes, sigs, up, and down. Adjacency pairs are now distinct
caller/callee relationships. Parallel upOccurrences/downOccurrences give indexed
site counts; parallel upCountsComplete/downCountsComplete state whether those
counts are complete. aggregation, countCompleteness, edgeVisits, edgeVisitLimit,
workLimitReached, generation, and omittedNodesCountComplete explain the bounds.
Incomplete counts are lower bounds, not estimates of all references.

The 20-neighbor and 100-node limits apply to distinct relationships/nodes.
Inspection stops at 100,000 edge visits; direction, depth, cycles, and self-calls
are preserved. Links between already included nodes remain eligible at the node
cap. Adjacency is streamed within one graph read scope. Use find_references for
exact occurrence locations; compact graphs intentionally omit them.

The same budget includes attached risk scoring, which also streams adjacency.
If scoring exhausts the remaining allowance, risk.gate is review_required and
risk.completeness is unavailable_work_limit. An unavailable score is never
reported as low risk; graph occurrence completeness is reported separately.

search_symbols, get_file_outline, resolve_symbol_at_position, find_references,
and find_implementations share a byte-aware collector. limit is a maximum.
requestedCount, returnedCount, and pageEndReason (complete, item_limit, byte_limit)
are additive. Existing fields and signed, five-minute, generation-bound cursors
remain. Follow nextCursor without changing the target/query. A changed generation,
tampering, or expiration rejects the cursor; it never blends generations.

The final UTF-8 tool JSON, including cursor and coverage, must fit
limits.maxResponseBytes. One unrepresentable record produces item_too_large,
never silent skipping or an empty continuation loop. Text plus structuredContent
and the JSON-RPC/SSE envelope consume additional transport bytes; the configured
tool-JSON bound is not a network-payload or total-RAM cap. No full inventory or
graph generation is retained between page requests.

## JS/TS resolution coverage

Structured imports retain module specifier, exported/local names, kind, type-only
state, source span, and lexical scope. Exports retain named/default, explicit
re-export, and star-chain evidence. Named arrows and function expressions are
indexed without changing existing symbol identities. Explicit imported bindings
resolve before generic name heuristics: a missing or unsupported import never
falls back to an unrelated same-named declaration.

Supported static patterns include relative ES modules, namespace calls, named and
default imports, barrels, straightforward CommonJS require/destructuring and
exports/module.exports, local workspace packages, self-name package imports,
package entry points, simple package exports/imports, nearest tsconfig/jsconfig,
local single-file extends, baseUrl, and paths. Runtime conditions and extension
rules distinguish Node ESM/CommonJS and supported TypeScript modes (node/node10,
node16/nodenext, bundler). Declaration files are not runtime implementations.

Unknown conditions, export arrays, customConditions, rootDirs, moduleSuffixes,
package-installed inheritance, dynamic imports/requires/exports, complex imported
object members, and external/unindexed targets remain unresolved. Namespace
re-export evidence is retained; nested namespace/object access is not interpreted
as a verified callable binding. This is deliberately not full compiler/bundler
equivalence. No package install, project-code execution, or arbitrary plugin occurs.
[TypeScript rules](https://www.typescriptlang.org/docs/handbook/modules/reference.html),
[Node package rules](https://nodejs.org/api/packages.html).

Resolution evidence distinguishes resolved/ambiguous bindings and pending reasons
such as external_or_unindexed, type_only, shadowed_binding, cyclic_reexport,
unsupported_dynamic_exports, unsupported_module_configuration, and
work_budget_exhausted. File outlines/symbol details include aggregate
moduleResolutionCoverage. Missing matches never prove non-use.

Config reads are at most 1 MiB per file; config inheritance/re-export depth is
32 hops; candidate file probes are at most 256 per reference. Hybrid auxiliary
module/config/package records are project-isolated, disk-backed, and published
with the graph generation. They share the existing per-file 256-entry/2 MiB
encoded-size lookup-cache allowance with other lookups; no second complete graph
or unbounded module cache is introduced. These are accounted working bounds,
not total-process RAM limits.

Import/export/config changes, additions, deletions and moves invalidate dependent
resolution even when declarations have not changed. Memory mode re-resolves
affected evidence; hybrid mode retains staged rebuild/publication. A fresh index
is necessary when deploying over old fragments lacking module evidence.

## File-change latency

The watcher waits for 250 ms of quiet and checks every 50 ms: an idle watcher
normally starts a batch about 250-300 ms after the last observed event, plus OS
delivery/scheduling time. Continuing events coalesce for at most 1,000 ms before
eligibility. These are internal constants, not exposed startup settings.

This is a start delay, not an indexing-completion guarantee. The worker executes
batches serially. Memory mode reparses changed files and re-resolves affected
references (potentially all files after declaration/module/config changes).
Hybrid mode currently stages a full disk-backed project rebuild, then publishes
one complete generation. Events during a build wait for a subsequent batch;
neither the 250 ms nor 1,000 ms value bounds that wait.

Use index_status state/generation/lastIndexedAt and the pending-change indicator
together when freshness matters. A zero pending count alone is insufficient:
the watcher removes a batch from its queue before executing it. Keep prior
generation results distinct from newly published evidence.

## Reproduction and honest measurement

Freeze baseline and candidate builds plus one source dataset outside both
checkouts. Build outside a checkout whose target files belong to a running app.

~~~powershell
mvn -B -ntp "-Djava.awt.headless=true" package
node scripts/run-efficiency-benchmark.cjs C:/baseline/code-graph-server.jar C:/candidate/code-graph-server.jar C:/frozen-source C:/results
java -cp "C:/candidate/lib/*" scripts/NavigationWorkflowOracle.java C:/frozen-source C:/results/workflow-oracle.json
node scripts/summarize-agent-navigation.cjs C:/results
node scripts/summarize-module-navigation.cjs C:/results
./scripts/benchmark-navigation.ps1 -BaselineLib C:/baseline/lib -CandidateLib C:/candidate/lib -Dataset C:/frozen-source -OutputDirectory C:/results
node scripts/summarize-efficiency-resources.cjs C:/results C:/baseline/code-graph-server.jar C:/candidate/code-graph-server.jar C:/frozen-source
~~~

The runner creates only its authored fixtures under the explicitly supplied
disposable dataset. It runs three independent JVMs per build, alternating order,
and closes their session-owned stores. Application caches start empty; the OS
file cache is not cleared. Queries receive warmup. The Java oracle uses javac;
JS module questions use authored expected targets/call sites independent of the
stopping rule. Neither oracle participates in acquisition.

Each MCP call/search/read records purpose, time, bytes, generation where available,
and the evidence gap requiring a fallback. Implementation reads are separate
from discovery. Deterministic harness acquisition is not model-inclusive coding
time or proof that every client will use the skill. Failed samples and unavailable
client environments must be reported, not silently excluded.
